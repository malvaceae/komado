import Carbon.HIToolbox
import CoreGraphics
import KomadoCore
import XCTest
@testable import KomadoHost

final class KeyboardKeyDispatcherTests: XCTestCase {
    @MainActor
    func testNavigationAndPunctuationKeysUseExpectedVirtualCodes() {
        var events: [CGEvent] = []
        let dispatcher = KeyboardKeyDispatcher(trusted: { true }, post: { events.append($0) })
        let cases: [(KeyboardKey.Key, Int)] = [(.home, kVK_Home), (.end, kVK_End), (.pageup, kVK_PageUp), (.pagedown, kVK_PageDown),
                                                (.comma, kVK_ANSI_Comma), (.period, kVK_ANSI_Period), (.slash, kVK_ANSI_Slash)]
        for (index, (key, expected)) in cases.enumerated() {
            XCTAssertTrue(dispatcher.send(KeyboardKey(sequence: UInt64(index + 1), captureRevision: 1, key: key, modifiers: 0),
                                          captureRevision: 1, sessionAllowed: true))
            XCTAssertEqual(events.suffix(2).map { $0.getIntegerValueField(.keyboardEventKeycode) }, [Int64(expected), Int64(expected)])
        }
    }

    @MainActor
    func testBuildsCompleteChordAndRejectsUnsafeState() {
        var trusted = false
        var events: [CGEvent] = []
        let dispatcher = KeyboardKeyDispatcher(trusted: { trusted }, post: { events.append($0) })
        let input = KeyboardKey(sequence: 1, captureRevision: 2, key: .c, modifiers: 9)
        XCTAssertFalse(dispatcher.send(input, captureRevision: 2, sessionAllowed: true))
        trusted = true
        XCTAssertFalse(dispatcher.send(input, captureRevision: 1, sessionAllowed: true))
        XCTAssertFalse(dispatcher.send(input, captureRevision: 2, sessionAllowed: false))
        XCTAssertTrue(events.isEmpty)
        XCTAssertTrue(dispatcher.send(input, captureRevision: 2, sessionAllowed: true))
        XCTAssertEqual(events.map(\.type), [.keyDown, .keyUp])
        XCTAssertEqual(events.map { $0.getIntegerValueField(.keyboardEventKeycode) }, [Int64(kVK_ANSI_C), Int64(kVK_ANSI_C)])
        XCTAssertEqual(events.map(\.flags), [[.maskCommand, .maskShift], [.maskCommand, .maskShift]])
        XCTAssertFalse(dispatcher.send(input, captureRevision: 2, sessionAllowed: true))
        dispatcher.close()
        XCTAssertFalse(dispatcher.send(KeyboardKey(sequence: 2, captureRevision: 2, key: .tab, modifiers: 0),
                                       captureRevision: 2, sessionAllowed: true))
        XCTAssertEqual(events.count, 2)
    }

    @MainActor
    func testAllKeysAndModifierCombinationsGeneratePairs() {
        var events: [CGEvent] = []
        let dispatcher = KeyboardKeyDispatcher(trusted: { true }, post: { events.append($0) })
        var sequence: UInt64 = 0
        for key in KeyboardKey.Key.allCases {
            for modifiers in UInt8(0)...15 {
                sequence += 1
                let input = KeyboardKey(sequence: sequence, captureRevision: 1, key: key, modifiers: modifiers)
                XCTAssertTrue(dispatcher.send(input, captureRevision: 1, sessionAllowed: true))
                let pair = events.suffix(2)
                XCTAssertEqual(pair.map(\.type), [.keyDown, .keyUp])
                XCTAssertEqual(pair.first?.getIntegerValueField(.keyboardEventKeycode), pair.last?.getIntegerValueField(.keyboardEventKeycode))
                XCTAssertEqual(pair.first?.flags, pair.last?.flags)
                var expected: CGEventFlags = []
                if modifiers & 1 != 0 { expected.insert(.maskCommand) }
                if modifiers & 2 != 0 { expected.insert(.maskAlternate) }
                if modifiers & 4 != 0 { expected.insert(.maskControl) }
                if modifiers & 8 != 0 { expected.insert(.maskShift) }
                XCTAssertEqual(pair.first?.flags, expected)
            }
        }
    }
}
