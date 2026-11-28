import AppKit
import ApplicationServices
import CoreGraphics
import KomadoCore
import XCTest
@testable import KomadoHost

final class WindowResizeTests: XCTestCase {
    @MainActor
    func testResizesOtherProcessAndRestoresFrame() async throws {
        guard AXIsProcessTrusted(), CGPreflightScreenCaptureAccess() else { throw XCTSkip("Accessibility and screen capture permissions are required") }
        let name = "komado resize \(UUID().uuidString)"
        let helper = Process()
        helper.executableURL = URL(fileURLWithPath: "/usr/bin/swift")
        helper.arguments = ["-e", """
            import AppKit
            let app = NSApplication.shared
            app.setActivationPolicy(.regular)
            let window = NSWindow(contentRect: NSRect(x: 160, y: 160, width: 500, height: 300), styleMask: [.titled, .resizable, .closable], backing: .buffered, defer: false)
            window.isReleasedWhenClosed = false
            window.title = CommandLine.arguments[1]
            window.minSize = NSSize(width: 300, height: 240)
            window.orderFrontRegardless()
            app.run()
            """, name]
        helper.standardOutput = FileHandle.nullDevice
        helper.standardError = FileHandle.nullDevice
        try helper.run()
        defer { if helper.isRunning { helper.terminate(); helper.waitUntilExit() } }
        var initial: ControlWindowSnapshot?
        for _ in 0..<150 {
            initial = ControlWindowSnapshot.onScreen()?.first { $0.processID == helper.processIdentifier && $0.bounds.width >= 300 }
            if initial != nil { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        try await Task.sleep(for: .seconds(2))
        let id = try XCTUnwrap(initial).id
        let target = try XCTUnwrap(ControlWindowSnapshot.onScreen()?.first { $0.id == id && $0.processID == helper.processIdentifier })
        let resize = try XCTUnwrap(WindowResize.start(target: target, desired: WindowSize(width: 400, height: 900)))
        var resized: ControlWindowSnapshot?
        for _ in 0..<50 {
            resized = ControlWindowSnapshot.onScreen()?.first { $0.id == target.id && $0.processID == target.processID }
            if let resized, resized.bounds != target.bounds { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        let changed = try XCTUnwrap(resized)
        XCTAssertNotEqual(changed.bounds.size, target.bounds.size)
        XCTAssertGreaterThanOrEqual(changed.bounds.width, 300)
        XCTAssertGreaterThanOrEqual(changed.bounds.height, 240)
        resize.restore()
        var restored: ControlWindowSnapshot?
        for _ in 0..<50 {
            restored = ControlWindowSnapshot.onScreen()?.first { $0.id == target.id && $0.processID == target.processID }
            if restored?.bounds == target.bounds { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertEqual(restored?.bounds, target.bounds)
    }
}
