import AppKit
import CoreGraphics
import ScreenCaptureKit
import XCTest
@testable import KomadoCore

final class WindowCatalogTests: XCTestCase {
    @MainActor
    func testSelectedWindowRemainsValidAfterTitleAndSizeChange() async throws {
        guard CGPreflightScreenCaptureAccess() else { throw XCTSkip("Screen capture permission unavailable") }
        _ = NSApplication.shared
        let window = NSWindow(contentRect: CGRect(x: 80, y: 80, width: 320, height: 240),
                              styleMask: [.titled, .closable, .resizable], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.title = "komado catalog test"
        window.level = .floating
        window.orderFrontRegardless()
        defer { window.close() }

        let windowID = CGWindowID(window.windowNumber)
        var initial: WindowSelection?
        for _ in 0..<30 {
            initial = try await WindowCatalog.all().first { $0.info.id == windowID }
            if initial != nil { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        let selected = try XCTUnwrap(initial)
        window.title = "komado catalog changed"
        var renamed: SCWindow?
        for _ in 0..<30 {
            let content = try await SCShareableContent.excludingDesktopWindows(true, onScreenWindowsOnly: false)
            renamed = content.windows.first { $0.windowID == windowID && $0.title == "komado catalog changed" }
            if renamed != nil { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertTrue(WindowCatalog.matches(window: try XCTUnwrap(renamed), info: selected.info, processID: selected.processID))

        var bounds = window.frame
        bounds.size.width += 80
        bounds.size.height += 40
        window.setFrame(bounds, display: true)

        var changed: SCWindow?
        for _ in 0..<30 {
            let content = try await SCShareableContent.excludingDesktopWindows(true, onScreenWindowsOnly: false)
            changed = content.windows.first { $0.windowID == windowID && $0.title == "komado catalog changed" && $0.frame.width > CGFloat(selected.info.width) }
            if changed != nil { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        let current = try XCTUnwrap(changed)
        XCTAssertTrue(WindowCatalog.matches(window: current, info: selected.info, processID: selected.processID))
        XCTAssertFalse(WindowCatalog.matches(window: current, info: selected.info, processID: selected.processID + 1))
        let other = WindowInfo(id: 0, width: selected.info.width, height: selected.info.height,
                               application: selected.info.application, title: selected.info.title)
        XCTAssertFalse(WindowCatalog.matches(window: current, info: other, processID: selected.processID))
        let stillMatches = try await WindowCatalog.matches(selected)
        XCTAssertTrue(stillMatches)
    }
}
