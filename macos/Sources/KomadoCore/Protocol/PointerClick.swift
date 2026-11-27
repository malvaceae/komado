import Foundation

/// A complete click on the capture identified by revision. Coordinates are
/// millionths of the captured image, with (0, 0) at its top left.
public struct PointerClick: Equatable, Sendable {
    public enum Button: String, Sendable { case left, right, middle }
    public enum MessageError: Error { case invalid }
    public static let maximumSequence: UInt64 = 9_007_199_254_740_991
    public let sequence: UInt64
    public let captureRevision: UInt64
    public let x: UInt32
    public let y: UInt32
    public let button: Button
    public let count: UInt8

    public init(sequence: UInt64, captureRevision: UInt64, x: UInt32, y: UInt32, button: Button, count: UInt8 = 1) {
        self.sequence = sequence
        self.captureRevision = captureRevision
        self.x = x
        self.y = y
        self.button = button
        self.count = count
    }

    public static func decode(_ data: Data) throws -> PointerClick {
        guard (1...1024).contains(data.count), !SignalMessage.hasTrailingComma(data),
              let fields = (try? JSONSerialization.jsonObject(with: data)) as? [Any], (7...8).contains(fields.count),
              fields[0] as? String == "1", fields[1] as? String == "pointer.click",
              let sequence = integer(fields[2], max: maximumSequence), sequence > 0,
              let revision = integer(fields[3], max: maximumSequence), revision > 0,
              let x = integer(fields[4], max: 1_000_000), let y = integer(fields[5], max: 1_000_000),
              let rawButton = fields[6] as? String, let button = Button(rawValue: rawButton) else { throw MessageError.invalid }
        if fields.count == 8 && (fields[7] as? String != "2" || button != .left) { throw MessageError.invalid }
        return PointerClick(sequence: sequence, captureRevision: revision, x: UInt32(x), y: UInt32(y), button: button,
                            count: fields.count == 8 ? 2 : 1)
    }

    public func encode() throws -> Data {
        guard (1...Self.maximumSequence).contains(sequence), (1...Self.maximumSequence).contains(captureRevision),
              x <= 1_000_000, y <= 1_000_000, count == 1 || (count == 2 && button == .left) else { throw MessageError.invalid }
        var fields = ["1", "pointer.click", String(sequence), String(captureRevision), String(x), String(y), button.rawValue]
        if count == 2 { fields.append("2") }
        let data = try JSONSerialization.data(withJSONObject: fields)
        guard try Self.decode(data) == self else { throw MessageError.invalid }
        return data
    }

    private static func integer(_ field: Any, max: UInt64) -> UInt64? {
        guard let text = field as? String, !text.isEmpty, text.utf8.count <= 16,
              text.utf8.allSatisfy({ (48...57).contains($0) }),
              (text.count == 1 || !text.hasPrefix("0")), let value = UInt64(text), value <= max else { return nil }
        return value
    }
}
