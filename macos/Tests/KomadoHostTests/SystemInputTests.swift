import AppKit
import ApplicationServices
import CoreGraphics
import KomadoCore
import XCTest
@testable import KomadoHost

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
        let view = NSTextView(frame: CGRect(x: 0, y: 0, width: 240, height: 160))
        window.contentView = view
        window.makeKeyAndOrderFront(nil)
        XCTAssertTrue(window.makeFirstResponder(view))
        defer { window.close() }

        let sendToSelf: (CGEvent) -> Void = { $0.postToPid(getpid()) }
        let keyboard = KeyboardTextDispatcher(post: sendToSelf)
        let samples = ["入力テスト😀", String(repeating: "あ", count: 170), "1\n2", "1\t2"]
        for (index, expected) in samples.enumerated() {
            view.string = ""
            XCTAssertTrue(keyboard.send(KeyboardText(sequence: UInt64(index + 1), captureRevision: 1, text: expected),
                                        captureRevision: 1, sessionAllowed: true))
            let deadline = ContinuousClock.now.advanced(by: .seconds(5))
            while view.string != expected, ContinuousClock.now < deadline {
                if let event = NSApp.nextEvent(matching: .any, until: Date(timeIntervalSinceNow: 0.05),
                                               inMode: .default, dequeue: true) {
                    NSApp.sendEvent(event)
                }
                await Task.yield()
            }
            XCTAssertEqual(view.string, expected)
        }
    }

    @MainActor func testClickEventsReachOwnProcess() async throws {
        guard AXIsProcessTrusted() else { throw XCTSkip("Accessibility permission is required") }
        _ = NSApplication.shared
        let screen = try XCTUnwrap(NSScreen.screens.first)
        let window = NSWindow(contentRect: CGRect(x: screen.visibleFrame.minX + 80, y: screen.visibleFrame.minY + 80, width: 240, height: 160),
                              styleMask: [.titled], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        let button = NSButton(frame: CGRect(x: 50, y: 50, width: 140, height: 60))
        button.title = "Test"
        window.contentView = button
        window.makeKeyAndOrderFront(nil)
        defer { window.close() }
        let point = window.convertPoint(toScreen: CGPoint(x: window.contentView!.bounds.midX, y: window.contentView!.bounds.midY))
        let global = CGPoint(x: point.x, y: screen.frame.maxY - point.y)
        let geometry = try XCTUnwrap(CaptureGeometry(revision: 1, bounds: CGRect(x: global.x - 50, y: global.y - 50, width: 100, height: 100)))
        let mapping = try XCTUnwrap(CaptureFrameMapping(frameSize: CGSize(width: 100, height: 100), contentRect: CGRect(x: 0, y: 0, width: 100, height: 100)))
        let marker: Int64 = 0x4b4f4d41444f
        let dispatcher = PointerClickDispatcher(post: { event in
            event.setIntegerValueField(.eventSourceUserData, value: marker)
            event.postToPid(getpid())
        })
        XCTAssertTrue(dispatcher.click(PointerClick(sequence: 1, captureRevision: 1, x: 500_000, y: 500_000, button: .left),
                                       geometry: geometry, mapping: mapping, sessionAllowed: true))
        var observed: [CGEventType] = []
        let deadline = ContinuousClock.now.advanced(by: .seconds(5))
        while observed.count < 2 && ContinuousClock.now < deadline {
            if let event = NSApp.nextEvent(matching: .any, until: Date(timeIntervalSinceNow: 0.05), inMode: .default, dequeue: true) {
                if let cg = event.cgEvent, cg.getIntegerValueField(.eventSourceUserData) == marker {
                    observed.append(cg.type)
                }
                NSApp.sendEvent(event)
            }
            await Task.yield()
        }
        XCTAssertEqual(observed, [.leftMouseDown, .leftMouseUp])
    }
}
