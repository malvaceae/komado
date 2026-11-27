import AppKit
import ApplicationServices
import CoreGraphics
import KomadoCore
import XCTest
@testable import KomadoHost

@MainActor
private final class InputTestView: NSView {
    var receivedText = ""
    override var acceptsFirstResponder: Bool { true }
    override func keyDown(with event: NSEvent) {
        receivedText += event.characters ?? ""
    }
}

final class SystemInputTests: XCTestCase {
    @MainActor
    func testTextDispatcherDeliversUnicodeToOwnWindow() async throws {
        guard AXIsProcessTrusted() else { throw XCTSkip("Accessibility permission is required") }
        _ = NSApplication.shared
        let screen = try XCTUnwrap(NSScreen.screens.first)
        let window = NSWindow(contentRect: CGRect(x: screen.visibleFrame.minX + 80, y: screen.visibleFrame.minY + 80,
                                                   width: 240, height: 160),
                              styleMask: [.titled], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        let view = InputTestView(frame: CGRect(x: 0, y: 0, width: 240, height: 160))
        window.contentView = view
        window.makeKeyAndOrderFront(nil)
        XCTAssertTrue(window.makeFirstResponder(view))
        defer { window.close() }

        let sendToSelf: (CGEvent) -> Void = { $0.postToPid(getpid()) }
        let keyboard = KeyboardTextDispatcher(post: sendToSelf)
        let expected = "入力テスト😀"
        XCTAssertTrue(keyboard.send(KeyboardText(sequence: 1, captureRevision: 1, text: expected),
                                    captureRevision: 1, sessionAllowed: true))
        let deadline = ContinuousClock.now.advanced(by: .seconds(5))
        while view.receivedText != expected, ContinuousClock.now < deadline {
            if let event = NSApp.nextEvent(matching: .any, until: Date(timeIntervalSinceNow: 0.05),
                                           inMode: .default, dequeue: true) {
                NSApp.sendEvent(event)
            }
            await Task.yield()
        }
        XCTAssertEqual(view.receivedText, expected)
    }
}
