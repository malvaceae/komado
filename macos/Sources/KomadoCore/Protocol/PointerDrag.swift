import Foundation

public struct PointerDrag: Equatable, Sendable {
    public enum Phase: String, Sendable { case begin, move, end }
    public enum MessageError: Error { case invalid }
    public let sequence: UInt64
    public let captureRevision: UInt64
    public let x: UInt32
    public let y: UInt32
    public let phase: Phase

    public init(sequence: UInt64, captureRevision: UInt64, x: UInt32, y: UInt32, phase: Phase) {
        self.sequence = sequence; self.captureRevision = captureRevision; self.x = x; self.y = y; self.phase = phase
    }

    public static func decode(_ data: Data) throws -> PointerDrag {
        guard (1...1024).contains(data.count), !SignalMessage.hasTrailingComma(data),
              let fields = (try? JSONSerialization.jsonObject(with: data)) as? [Any], fields.count == 7,
              fields[0] as? String == "1", fields[1] as? String == "pointer.drag",
              let sequence = integer(fields[2], max: PointerClick.maximumSequence), sequence > 0,
              let revision = integer(fields[3], max: PointerClick.maximumSequence), revision > 0,
              let x = integer(fields[4], max: 1_000_000), let y = integer(fields[5], max: 1_000_000),
              let raw = fields[6] as? String, let phase = Phase(rawValue: raw) else { throw MessageError.invalid }
        return PointerDrag(sequence: sequence, captureRevision: revision, x: UInt32(x), y: UInt32(y), phase: phase)
    }

    public func encode() throws -> Data {
        let data = try JSONSerialization.data(withJSONObject: ["1", "pointer.drag", String(sequence), String(captureRevision), String(x), String(y), phase.rawValue])
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
