import Foundation

/// Confirms event posting, not delivery to or processing by the target app.
public struct KeyboardTextPosted: Equatable, Sendable {
    public enum MessageError: Error { case invalid }
    public let sequence: UInt64
    public init(sequence: UInt64) { self.sequence = sequence }

    public static func decode(_ data: Data) throws -> KeyboardTextPosted {
        guard (1...256).contains(data.count), !SignalMessage.hasTrailingComma(data),
              let fields = (try? JSONSerialization.jsonObject(with: data)) as? [String],
              fields.count == 3, fields[0] == "1", fields[1] == "keyboard.text.posted",
              !fields[2].isEmpty, fields[2].utf8.count <= 16,
              fields[2].utf8.allSatisfy({ (48...57).contains($0) }), !fields[2].hasPrefix("0"),
              let sequence = UInt64(fields[2]), (1...PointerClick.maximumSequence).contains(sequence) else { throw MessageError.invalid }
        return KeyboardTextPosted(sequence: sequence)
    }

    public func encode() throws -> Data {
        guard (1...PointerClick.maximumSequence).contains(sequence) else { throw MessageError.invalid }
        return try JSONSerialization.data(withJSONObject: ["1", "keyboard.text.posted", String(sequence)])
    }
}
