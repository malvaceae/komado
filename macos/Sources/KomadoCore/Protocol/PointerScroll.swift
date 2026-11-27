import Foundation

/// A bounded pixel scroll at a capture-relative location.
public struct PointerScroll: Equatable, Sendable {
    public enum MessageError: Error { case invalid }
    public let sequence: UInt64
    public let captureRevision: UInt64
    public let x: UInt32
    public let y: UInt32
    public let horizontal: Int32
    public let vertical: Int32

    public init(sequence: UInt64, captureRevision: UInt64, x: UInt32, y: UInt32, horizontal: Int32, vertical: Int32) {
        self.sequence = sequence; self.captureRevision = captureRevision; self.x = x; self.y = y
        self.horizontal = horizontal; self.vertical = vertical
    }

    public static func decode(_ data: Data) throws -> PointerScroll {
        guard (1...1024).contains(data.count), !SignalMessage.hasTrailingComma(data),
              let fields = (try? JSONSerialization.jsonObject(with: data)) as? [Any], fields.count == 8,
              fields[0] as? String == "1", fields[1] as? String == "pointer.scroll",
              let sequence = unsigned(fields[2], max: PointerClick.maximumSequence), sequence > 0,
              let revision = unsigned(fields[3], max: PointerClick.maximumSequence), revision > 0,
              let x = unsigned(fields[4], max: 1_000_000), let y = unsigned(fields[5], max: 1_000_000),
              let horizontal = delta(fields[6]), let vertical = delta(fields[7]), horizontal != 0 || vertical != 0 else { throw MessageError.invalid }
        return PointerScroll(sequence: sequence, captureRevision: revision, x: UInt32(x), y: UInt32(y), horizontal: horizontal, vertical: vertical)
    }

    public func encode() throws -> Data {
        let data = try JSONSerialization.data(withJSONObject: ["1", "pointer.scroll", String(sequence), String(captureRevision), String(x), String(y), String(horizontal), String(vertical)])
        guard try Self.decode(data) == self else { throw MessageError.invalid }
        return data
    }

    private static func unsigned(_ value: Any, max: UInt64) -> UInt64? {
        guard let text = value as? String, !text.isEmpty, text.utf8.count <= 16,
              text.utf8.allSatisfy({ (48...57).contains($0) }),
              (text.count == 1 || !text.hasPrefix("0")), let number = UInt64(text), number <= max else { return nil }
        return number
    }

    private static func delta(_ value: Any) -> Int32? {
        guard let text = value as? String, !text.isEmpty, text.utf8.count <= 4,
              let number = Int32(text), (-120...120).contains(number), String(number) == text else { return nil }
        return number
    }
}
