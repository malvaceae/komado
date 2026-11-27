import Foundation

/// A committed text fragment; composition updates remain local to Android.
public struct KeyboardText: Equatable, Sendable {
    public enum MessageError: Error { case invalid }
    public let sequence: UInt64
    public let captureRevision: UInt64
    public let text: String
    public let confirm: Bool

    public init(sequence: UInt64, captureRevision: UInt64, text: String, confirm: Bool = false) {
        self.sequence = sequence
        self.captureRevision = captureRevision
        self.text = text
        self.confirm = confirm
    }

    public static func decode(_ data: Data) throws -> KeyboardText {
        guard (1...2048).contains(data.count), !SignalMessage.hasTrailingComma(data),
              let fields = (try? JSONSerialization.jsonObject(with: data)) as? [String], fields.count == 5,
              fields[0] == "1", ["keyboard.text", "keyboard.text.checked"].contains(fields[1]),
              let sequence = positiveInteger(fields[2]), let revision = positiveInteger(fields[3]),
              validText(fields[4]) else { throw MessageError.invalid }
        return KeyboardText(sequence: sequence, captureRevision: revision, text: fields[4], confirm: fields[1] == "keyboard.text.checked")
    }

    public func encode() throws -> Data {
        guard (1...PointerClick.maximumSequence).contains(sequence), (1...PointerClick.maximumSequence).contains(captureRevision),
              Self.validText(text) else { throw MessageError.invalid }
        let data = try JSONSerialization.data(withJSONObject: ["1", confirm ? "keyboard.text.checked" : "keyboard.text", String(sequence), String(captureRevision), text])
        guard try Self.decode(data) == self else { throw MessageError.invalid }
        return data
    }

    private static func validText(_ value: String) -> Bool {
        (1...512).contains(value.utf8.count) && !value.unicodeScalars.contains {
            ($0.value < 32 && $0.value != 9 && $0.value != 10) || (0x7f...0x9f).contains($0.value)
        }
    }

    private static func positiveInteger(_ value: String) -> UInt64? {
        guard !value.isEmpty, value.utf8.count <= 16, !value.hasPrefix("0"),
              value.utf8.allSatisfy({ (48...57).contains($0) }), let number = UInt64(value),
              (1...PointerClick.maximumSequence).contains(number) else { return nil }
        return number
    }
}
