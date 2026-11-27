import Foundation

/// One complete special-key press, with modifiers applied only to that press.
public struct KeyboardKey: Equatable, Sendable {
    public enum Key: String, CaseIterable, Sendable {
        case backspace, delete, enter, tab, escape, left, right, up, down, home, end, pageup, pagedown, comma, period, slash
        case space, minus, equal, leftbracket, rightbracket, backslash, semicolon, apostrophe, grave
        case a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v, w, x, y, z
        case digit0, digit1, digit2, digit3, digit4, digit5, digit6, digit7, digit8, digit9
        case f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12
    }
    public enum MessageError: Error { case invalid }
    public let sequence: UInt64
    public let captureRevision: UInt64
    public let key: Key
    /// Bit 0: Command, bit 1: Option, bit 2: Control, bit 3: Shift.
    public let modifiers: UInt8

    public init(sequence: UInt64, captureRevision: UInt64, key: Key, modifiers: UInt8) {
        self.sequence = sequence
        self.captureRevision = captureRevision
        self.key = key
        self.modifiers = modifiers
    }

    public static func decode(_ data: Data) throws -> KeyboardKey {
        guard (1...1024).contains(data.count), !SignalMessage.hasTrailingComma(data),
              let fields = (try? JSONSerialization.jsonObject(with: data)) as? [String], fields.count == 6,
              fields[0] == "1", fields[1] == "keyboard.key",
              let sequence = integer(fields[2], maximum: PointerClick.maximumSequence), sequence > 0,
              let revision = integer(fields[3], maximum: PointerClick.maximumSequence), revision > 0,
              let key = Key(rawValue: fields[4]),
              let modifiers = integer(fields[5], maximum: 15) else { throw MessageError.invalid }
        return KeyboardKey(sequence: sequence, captureRevision: revision, key: key, modifiers: UInt8(modifiers))
    }

    public func encode() throws -> Data {
        guard (1...PointerClick.maximumSequence).contains(sequence),
              (1...PointerClick.maximumSequence).contains(captureRevision), modifiers <= 15 else { throw MessageError.invalid }
        let data = try JSONSerialization.data(withJSONObject: ["1", "keyboard.key", String(sequence), String(captureRevision), key.rawValue, String(modifiers)])
        guard try Self.decode(data) == self else { throw MessageError.invalid }
        return data
    }

    private static func integer(_ text: String, maximum: UInt64) -> UInt64? {
        guard !text.isEmpty, text.utf8.count <= 16,
              text.utf8.allSatisfy({ (48...57).contains($0) }),
              (text.count == 1 || !text.hasPrefix("0")),
              let value = UInt64(text), value <= maximum else { return nil }
        return value
    }
}
