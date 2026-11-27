import Foundation
import KomadoCore

enum ControlInput: Equatable, Sendable {
    case click(PointerClick)
    case text(KeyboardText)
    case key(KeyboardKey)
    case scroll(PointerScroll)

    var sequence: UInt64 {
        switch self { case let .click(value): value.sequence; case let .text(value): value.sequence; case let .key(value): value.sequence; case let .scroll(value): value.sequence }
    }
    var captureRevision: UInt64 {
        switch self { case let .click(value): value.captureRevision; case let .text(value): value.captureRevision; case let .key(value): value.captureRevision; case let .scroll(value): value.captureRevision }
    }
}

/// Accepts ordered input only after a capture is announced.
final class ControlInputGate: @unchecked Sendable {
    private let lock = NSLock()
    private var revision: UInt64?
    private var sequence: UInt64 = 0
    private var closed = false

    func enable(revision: UInt64) -> Bool {
        lock.withLock {
            guard !closed, self.revision == nil, (1...PointerClick.maximumSequence).contains(revision) else { return false }
            self.revision = revision
            return true
        }
    }

    func accept(_ data: Data, binary: Bool) -> ControlInput? {
        guard !binary else { return nil }
        let input: ControlInput
        if let click = try? PointerClick.decode(data) { input = .click(click) }
        else if let text = try? KeyboardText.decode(data) { input = .text(text) }
        else if let key = try? KeyboardKey.decode(data) { input = .key(key) }
        else if let scroll = try? PointerScroll.decode(data) { input = .scroll(scroll) }
        else { return nil }
        return lock.withLock {
            guard !closed, revision == input.captureRevision, input.sequence > sequence else { return nil }
            sequence = input.sequence
            return input
        }
    }

    func close() { lock.withLock { closed = true } }
}
