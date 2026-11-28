import AppKit
import ApplicationServices
import CoreGraphics
import CoreMedia
import CoreVideo
import ScreenCaptureKit
import WebRTC
import XCTest
import KomadoCore
@testable import KomadoHost

private final class MenuFrameProbe: NSObject, RTCVideoRenderer, @unchecked Sendable {
    private let lock = NSLock()
    private var image: CVPixelBuffer?
    func setSize(_ size: CGSize) {}
    func renderFrame(_ frame: RTCVideoFrame?) {
        guard let buffer = frame?.buffer as? RTCCVPixelBuffer else { return }
        lock.withLock { image = buffer.pixelBuffer }
    }
    func pixel(at point: CGPoint) -> [UInt8]? {
        guard let image = lock.withLock({ image }) else { return nil }
        CVPixelBufferLockBaseAddress(image, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(image, .readOnly) }
        guard let address = CVPixelBufferGetBaseAddress(image) else { return nil }
        let width = CVPixelBufferGetWidth(image), height = CVPixelBufferGetHeight(image)
        let x = max(0, min(width - 1, Int(point.x * CGFloat(width))))
        let y = max(0, min(height - 1, Int(point.y * CGFloat(height))))
        let bytes = address.advanced(by: y * CVPixelBufferGetBytesPerRow(image) + x * 4).assumingMemoryBound(to: UInt8.self)
        return [bytes[0], bytes[1], bytes[2]]
    }
}
final class ApplicationMenuTests: XCTestCase {
    @MainActor func testApplicationMenuIsCapturedAndCanBeClicked() async throws {
        guard CGPreflightScreenCaptureAccess(), AXIsProcessTrusted() else { throw XCTSkip("Permissions unavailable") }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("komado-menu-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: directory) }
        let openFile = directory.appendingPathComponent("open")
        let selectedFile = directory.appendingPathComponent("selected")
        let helper = Process(); helper.executableURL = URL(fileURLWithPath: "/usr/bin/swift")
        helper.arguments = ["-e", """
            import AppKit
            final class MenuTarget: NSObject { @objc func selected(_ sender: Any?) { FileManager.default.createFile(atPath: CommandLine.arguments[1] + "/selected", contents: Data()) } }
            let app = NSApplication.shared; app.setActivationPolicy(.regular); let target = MenuTarget()
            let window = NSWindow(contentRect: NSRect(x: 200, y: 200, width: 360, height: 240), styleMask: [.titled, .closable], backing: .buffered, defer: false)
            window.isReleasedWhenClosed = false; window.title = "Komado Menu Probe"
            let view = NSView(); view.wantsLayer = true; view.layer?.backgroundColor = NSColor.red.cgColor; window.contentView = view
            window.makeKeyAndOrderFront(nil); app.activate(ignoringOtherApps: true)
            let timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { _ in
                let file = CommandLine.arguments[1] + "/open"
                if FileManager.default.fileExists(atPath: file) {
                    try? FileManager.default.removeItem(atPath: file)
                    let menu = NSMenu(); let item = menu.addItem(withTitle: "Komado Probe Item", action: #selector(MenuTarget.selected(_:)), keyEquivalent: ""); item.target = target
                    menu.popUp(positioning: nil, at: NSPoint(x: 110, y: 100), in: view)
                }
            }
            app.run(); timer.invalidate()
            """, directory.path]
        helper.standardOutput = FileHandle.nullDevice; helper.standardError = FileHandle.nullDevice
        try helper.run()
        defer { if helper.isRunning { helper.terminate(); helper.waitUntilExit() } }
        var content: SCShareableContent?
        for _ in 0..<150 {
            content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
            if content?.windows.contains(where: { $0.owningApplication?.processID == helper.processIdentifier }) == true { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        let snapshot = try XCTUnwrap(content)
        let app = try XCTUnwrap(snapshot.applications.first { $0.processID == helper.processIdentifier })
        let display = try XCTUnwrap(snapshot.displays.first { $0.displayID == CGMainDisplayID() })
        let baseline = Set((ControlWindowSnapshot.onScreen() ?? []).filter { $0.processID == helper.processIdentifier }.map(\.id))
        let factory = MediaFactory.create()
        let source = factory.videoSource(forScreenCast: true)
        let track = factory.videoTrack(with: source, trackId: "application-menu-test")
        let output = MenuFrameProbe()
        track.add(output)
        defer { track.remove(output) }
        let route = MediaSessionGate()
        route.connected()
        let capture = try await DisplayCapture.start(application: app, display: display, source: source, route: route) {}
        defer { Task { try? await capture.stop() } }
        FileManager.default.createFile(atPath: openFile.path, contents: Data())
        var menuPoint: CGPoint?
        for _ in 0..<100 {
            let candidates = (ControlWindowSnapshot.onScreen() ?? []).filter {
                $0.processID == helper.processIdentifier && !baseline.contains($0.id) && $0.bounds.width > 20 && $0.bounds.height > 10
            }
            for popup in candidates {
                let point = CGPoint(x: popup.bounds.midX, y: popup.bounds.midY)
                var element: AXUIElement?, role: CFTypeRef?
                if AXUIElementCopyElementAtPosition(AXUIElementCreateSystemWide(), Float(point.x), Float(point.y), &element) == .success,
                   let element, AXUIElementCopyAttributeValue(element, kAXRoleAttribute as CFString, &role) == .success,
                   role as? String == kAXMenuItemRole as String, FocusedWindow.processID(at: point) == helper.processIdentifier {
                    menuPoint = point
                    break
                }
            }
            if menuPoint != nil { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        let point = try XCTUnwrap(menuPoint, "Menu item must be topmost")
        let hitProcessID = try XCTUnwrap(FocusedWindow.processID(at: point))
        XCTAssertTrue(ApplicationControlSafety(processID: helper.processIdentifier).permits(hitProcessID: hitProcessID))
        let samplePoint = CGPoint(x: (point.x - display.frame.minX) / display.frame.width,
                                  y: (point.y - display.frame.minY) / display.frame.height)
        var menuVisible = false
        for _ in 0..<50 {
            if let pixel = output.pixel(at: samplePoint), pixel[0] > 40, pixel[1] > 40 { menuVisible = true; break }
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertTrue(menuVisible, "The menu must appear in the application video")
        let bounds = CGDisplayBounds(display.displayID)
        let geometry = try XCTUnwrap(CaptureGeometry(revision: 1, bounds: bounds))
        let mapping = try XCTUnwrap(CaptureFrameMapping(frameSize: bounds.size, contentRect: CGRect(origin: .zero, size: bounds.size)))
        let x = UInt32(((point.x - bounds.minX) / bounds.width * 1_000_000).rounded())
        let y = UInt32(((point.y - bounds.minY) / bounds.height * 1_000_000).rounded())
        let dispatcher = PointerClickDispatcher()
        XCTAssertTrue(dispatcher.click(PointerClick(sequence: 1, captureRevision: 1, x: x, y: y, button: .left), geometry: geometry, mapping: mapping, sessionAllowed: true))
        for _ in 0..<50 {
            if FileManager.default.fileExists(atPath: selectedFile.path) { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: selectedFile.path), "Menu item should receive the click")
        try await capture.stop()
    }
}
