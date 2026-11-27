import Foundation
import KomadoCore

enum ControlInput: Equatable, Sendable {
    case click(PointerClick)
    case text(KeyboardText)
    case key(KeyboardKey)
    case scroll(PointerScroll)
    case drag(PointerDrag)
    case move(PointerMove)
    case clipboard(ClipboardRequest)

    var pointerCoordinate: (x: UInt32, y: UInt32)? {
        switch self {
        case let .click(value): (value.x, value.y)
        case let .scroll(value): (value.x, value.y)
        case let .drag(value): (value.x, value.y)
        case let .move(value): (value.x, value.y)
        case .text, .key, .clipboard: nil
        }
    }

    var sequence: UInt64 {
        switch self { case let .click(value): value.sequence; case let .text(value): value.sequence; case let .key(value): value.sequence; case let .scroll(value): value.sequence; case let .drag(value): value.sequence; case let .move(value): value.sequence; case let .clipboard(value): value.sequence }
    }
    var captureRevision: UInt64 {
        switch self { case let .click(value): value.captureRevision; case let .text(value): value.captureRevision; case let .key(value): value.captureRevision; case let .scroll(value): value.captureRevision; case let .drag(value): value.captureRevision; case let .move(value): value.captureRevision; case let .clipboard(value): value.captureRevision }
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
        guard !binary, (1...ClipboardCodec.maximumMessageBytes).contains(data.count),
              let fields = (try? JSONSerialization.jsonObject(with: data)) as? [Any],
              fields.count >= 2, let type = fields[1] as? String else { return nil }
        let input: ControlInput
        switch type {
        case "pointer.click": guard let value = try? PointerClick.decode(data) else { return nil }; input = .click(value)
        case "keyboard.text": guard let value = try? KeyboardText.decode(data) else { return nil }; input = .text(value)
        case "keyboard.key": guard let value = try? KeyboardKey.decode(data) else { return nil }; input = .key(value)
        case "pointer.scroll": guard let value = try? PointerScroll.decode(data) else { return nil }; input = .scroll(value)
        case "pointer.drag": guard let value = try? PointerDrag.decode(data) else { return nil }; input = .drag(value)
        case "pointer.move": guard let value = try? PointerMove.decode(data) else { return nil }; input = .move(value)
        case let value where value.hasPrefix("clipboard."):
            guard let value = try? ClipboardRequest.decode(data) else { return nil }; input = .clipboard(value)
        default: return nil
        }
        return lock.withLock {
            guard !closed, revision == input.captureRevision, input.sequence > sequence else { return nil }
            sequence = input.sequence
            return input
        }
    }

    func close() { lock.withLock { closed = true } }
}
