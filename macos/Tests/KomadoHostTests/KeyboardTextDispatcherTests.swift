import CoreGraphics
import KomadoCore
import XCTest
@testable import KomadoHost

final class KeyboardTextDispatcherTests: XCTestCase {
    @MainActor
    func testUnicodeEventsAndSafetyChecks() {
        var trusted = false
        var events: [CGEvent] = []
        let dispatcher = KeyboardTextDispatcher(trusted: { trusted }, post: { events.append($0) })
        let input = KeyboardText(sequence: 1, captureRevision: 2, text: "日本語😀👩‍💻")
        XCTAssertFalse(dispatcher.send(input, captureRevision: 2, sessionAllowed: true))
        trusted = true
        XCTAssertFalse(dispatcher.send(input, captureRevision: 1, sessionAllowed: true))
        XCTAssertFalse(dispatcher.send(input, captureRevision: 2, sessionAllowed: false))
        XCTAssertTrue(events.isEmpty)
        XCTAssertTrue(dispatcher.send(input, captureRevision: 2, sessionAllowed: true))
        XCTAssertEqual(events.map(\.type), [.keyDown, .keyUp])
        for event in events {
            var count = 0
            var units = [UniChar](repeating: 0, count: 128)
            event.keyboardGetUnicodeString(maxStringLength: units.count, actualStringLength: &count, unicodeString: &units)
            XCTAssertEqual(String(decoding: units.prefix(count), as: UTF16.self), input.text)
            XCTAssertTrue(event.flags.isEmpty)
        }
        XCTAssertFalse(dispatcher.send(input, captureRevision: 2, sessionAllowed: true))
        dispatcher.close()
        XCTAssertFalse(dispatcher.send(KeyboardText(sequence: 2, captureRevision: 2, text: "a"), captureRevision: 2, sessionAllowed: true))
        XCTAssertEqual(events.count, 2)
    }

    @MainActor
    func testEventRetainsMaximumSizeText() {
        var events: [CGEvent] = []
        let dispatcher = KeyboardTextDispatcher(trusted: { true }, post: { events.append($0) })
        let text = String(repeating: "あ", count: 170)
        XCTAssertTrue(dispatcher.send(KeyboardText(sequence: 1, captureRevision: 1, text: text), captureRevision: 1, sessionAllowed: true))
        var count = 0
        var units = [UniChar](repeating: 0, count: 512)
        events[0].keyboardGetUnicodeString(maxStringLength: units.count, actualStringLength: &count, unicodeString: &units)
        XCTAssertEqual(String(decoding: units.prefix(count), as: UTF16.self), text)
    }
}
