import AppKit
import ApplicationServices
import CoreGraphics
import ScreenCaptureKit
import WebRTC
import XCTest
@testable import KomadoCore
@testable import KomadoHost

final class WindowActionsTests: XCTestCase {
    @MainActor func testCloseRespectsApplicationRefusal() async throws {
        guard AXIsProcessTrusted(), CGPreflightScreenCaptureAccess() else { throw XCTSkip("Accessibility and screen capture permissions are required") }
        let helper = Process()
        helper.executableURL = URL(fileURLWithPath: "/usr/bin/swift")
        helper.arguments = ["-e", """
            import AppKit
            final class Delegate: NSObject, NSWindowDelegate {
                func windowShouldClose(_ sender: NSWindow) -> Bool { false }
            }
            let app = NSApplication.shared
            app.setActivationPolicy(.regular)
            let delegate = Delegate()
            let window = NSWindow(contentRect: NSRect(x: 150, y: 150, width: 450, height: 300), styleMask: [.titled, .resizable, .closable], backing: .buffered, defer: false)
            window.isReleasedWhenClosed = false; window.delegate = delegate
            window.title = "komado refusal test"; window.orderFrontRegardless()
            app.run()
            """]
        helper.standardOutput = FileHandle.nullDevice; helper.standardError = FileHandle.nullDevice
        try helper.run()
        defer { if helper.isRunning { helper.terminate(); helper.waitUntilExit() } }
        var initial: ControlWindowSnapshot?
        for _ in 0..<150 {
            initial = ControlWindowSnapshot.onScreen()?.first { $0.processID == helper.processIdentifier && $0.bounds.width >= 300 }
            if initial != nil { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        let id = try XCTUnwrap(initial).id
        try await Task.sleep(for: .seconds(2))
        let content = try await SCShareableContent.excludingDesktopWindows(true, onScreenWindowsOnly: false)
        let window = try XCTUnwrap(content.windows.first { $0.windowID == id && $0.owningApplication?.processID == helper.processIdentifier })
        XCTAssertTrue(WindowActions.perform(.close, window: window, windows: content.windows))
        try await Task.sleep(for: .milliseconds(500))
        XCTAssertTrue(try XCTUnwrap(ControlWindowSnapshot.onScreen()).contains { $0.id == id })
    }

    @MainActor func testMinimizesRestoresAndClosesSelectedWindowInOtherProcess() async throws {
        guard AXIsProcessTrusted(), CGPreflightScreenCaptureAccess() else { throw XCTSkip("Accessibility and screen capture permissions are required") }
        let helper = Process()
        helper.executableURL = URL(fileURLWithPath: "/usr/bin/swift")
        helper.arguments = ["-e", """
            import AppKit
            let app = NSApplication.shared
            app.setActivationPolicy(.regular)
            let window = NSWindow(contentRect: NSRect(x: 150, y: 150, width: 450, height: 300), styleMask: [.titled, .resizable, .closable, .miniaturizable], backing: .buffered, defer: false)
            window.isReleasedWhenClosed = false
            window.title = "komado window action test"
            window.orderFrontRegardless()
            app.run()
            """]
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
        let id = try XCTUnwrap(initial).id
        try await Task.sleep(for: .seconds(2))
        var content = try await SCShareableContent.excludingDesktopWindows(true, onScreenWindowsOnly: false)
        var window = try XCTUnwrap(content.windows.first { $0.windowID == id && $0.owningApplication?.processID == helper.processIdentifier })
        XCTAssertTrue(WindowActions.perform(.minimize, window: window, windows: content.windows))
        for _ in 0..<50 {
            if ControlWindowSnapshot.onScreen()?.contains(where: { $0.id == id }) == false { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertFalse(try XCTUnwrap(ControlWindowSnapshot.onScreen()).contains { $0.id == id })
        content = try await SCShareableContent.excludingDesktopWindows(true, onScreenWindowsOnly: false)
        window = try XCTUnwrap(content.windows.first { $0.windowID == id && $0.owningApplication?.processID == helper.processIdentifier })
        XCTAssertFalse(window.isOnScreen)
        let minimizedWindows = try await WindowCatalog.all()
        let minimized = try XCTUnwrap(minimizedWindows.first { $0.info.id == id })
        XCTAssertFalse(minimized.info.onScreen)
        XCTAssertTrue(WindowActions.perform(.restore, window: window, windows: content.windows))
        for _ in 0..<50 {
            if ControlWindowSnapshot.onScreen()?.contains(where: { $0.id == id }) == true { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertTrue(try XCTUnwrap(ControlWindowSnapshot.onScreen()).contains { $0.id == id })
        try await Task.sleep(for: .seconds(1))
        content = try await SCShareableContent.excludingDesktopWindows(true, onScreenWindowsOnly: false)
        window = try XCTUnwrap(content.windows.first { $0.windowID == id && $0.owningApplication?.processID == helper.processIdentifier })
        XCTAssertTrue(window.isOnScreen)
        let restoredWindows = try await WindowCatalog.all()
        let restored = try XCTUnwrap(restoredWindows.first { $0.info.id == id })
        XCTAssertTrue(restored.info.onScreen)
        let factory = MediaFactory.create(), route = MediaSessionGate()
        route.connected()
        let capture = try await DisplayCapture.start(window: window, source: factory.videoSource(forScreenCast: true), route: route) {}
        for _ in 0..<50 {
            if capture.frameMapping != nil { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertNotNil(capture.frameMapping)
        try await capture.stop()
        content = try await SCShareableContent.excludingDesktopWindows(true, onScreenWindowsOnly: false)
        window = try XCTUnwrap(content.windows.first { $0.windowID == id && $0.owningApplication?.processID == helper.processIdentifier })
        XCTAssertTrue(WindowActions.perform(.close, window: window, windows: content.windows))
        for _ in 0..<50 {
            if ControlWindowSnapshot.onScreen()?.contains(where: { $0.id == id }) == false { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertFalse(try XCTUnwrap(ControlWindowSnapshot.onScreen()).contains { $0.id == id })
    }
}
