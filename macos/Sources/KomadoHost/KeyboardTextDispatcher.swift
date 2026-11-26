import ApplicationServices
import CoreGraphics
import KomadoCore

/// Builds Unicode key events after the caller verifies the session, capture,
/// focus and user authorization. Not connected to the DataChannel yet.
@MainActor
final class KeyboardTextDispatcher {
    private let trusted: () -> Bool
    private let post: (CGEvent) -> Void
    private var sequence: UInt64 = 0
    private var closed = false

    init(trusted: @escaping () -> Bool = { AXIsProcessTrusted() },
         post: @escaping (CGEvent) -> Void = { $0.post(tap: .cghidEventTap) }) {
        self.trusted = trusted
        self.post = post
    }

    @discardableResult
    func send(_ input: KeyboardText, captureRevision: UInt64, sessionAllowed: Bool) -> Bool {
        guard !closed, sessionAllowed, trusted(), input.sequence > sequence,
              input.captureRevision == captureRevision, (try? input.encode()) != nil,
              let source = CGEventSource(stateID: .hidSystemState),
              let down = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: true),
              let up = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: false) else { return false }
        let codeUnits = Array(input.text.utf16)
        codeUnits.withUnsafeBufferPointer { buffer in
            down.keyboardSetUnicodeString(stringLength: buffer.count, unicodeString: buffer.baseAddress)
            up.keyboardSetUnicodeString(stringLength: buffer.count, unicodeString: buffer.baseAddress)
        }
        down.flags = []
        up.flags = []
        guard trusted(), !closed else { return false }
        sequence = input.sequence
        post(down)
        post(up)
        return true
    }

    func close() { closed = true }
}
