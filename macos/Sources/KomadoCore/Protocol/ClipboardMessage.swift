import Foundation

/// Clipboard transfers; content is never logged or persisted by komado.
public struct ClipboardRequest: Equatable, Sendable {
    public enum Operation: Equatable, Sendable { case get, prepare, set(String), autoStart, autoPoll, autoPrepare, autoSet(String) }
    public let sequence: UInt64
    public let captureRevision: UInt64
    public let operation: Operation
    public init(sequence: UInt64, captureRevision: UInt64, operation: Operation) {
        self.sequence = sequence; self.captureRevision = captureRevision; self.operation = operation
    }

    public static func decode(_ data: Data) throws -> ClipboardRequest {
        let fields = try ClipboardCodec.fields(data)
        guard fields.count >= 4, let sequence = ClipboardCodec.integer(fields[2]),
              let revision = ClipboardCodec.integer(fields[3]) else { throw ClipboardCodec.MessageError.invalid }
        let operation: Operation
        switch fields[1] {
        case "clipboard.get" where fields.count == 4: operation = .get
        case "clipboard.prepare" where fields.count == 4: operation = .prepare
        case "clipboard.auto.start" where fields.count == 4: operation = .autoStart
        case "clipboard.auto.poll" where fields.count == 4: operation = .autoPoll
        case "clipboard.auto.prepare" where fields.count == 4: operation = .autoPrepare
        case "clipboard.auto.set" where fields.count == 5 && ClipboardCodec.validText(fields[4]): operation = .autoSet(fields[4])
        case "clipboard.set" where fields.count == 5 && ClipboardCodec.validText(fields[4]): operation = .set(fields[4])
        default: throw ClipboardCodec.MessageError.invalid
        }
        return ClipboardRequest(sequence: sequence, captureRevision: revision, operation: operation)
    }

    public func encode() throws -> Data {
        guard (1...PointerClick.maximumSequence).contains(sequence), (1...PointerClick.maximumSequence).contains(captureRevision) else {
            throw ClipboardCodec.MessageError.invalid
        }
        switch operation {
        case let .set(text), let .autoSet(text): if !ClipboardCodec.validText(text) { throw ClipboardCodec.MessageError.invalid }
        default: break
        }
        var fields = ["1", "", String(sequence), String(captureRevision)]
        switch operation {
        case .get: fields[1] = "clipboard.get"
        case .prepare: fields[1] = "clipboard.prepare"
        case let .set(text): fields[1] = "clipboard.set"; fields.append(text)
        case .autoStart: fields[1] = "clipboard.auto.start"
        case .autoPoll: fields[1] = "clipboard.auto.poll"
        case .autoPrepare: fields[1] = "clipboard.auto.prepare"
        case let .autoSet(text): fields[1] = "clipboard.auto.set"; fields.append(text)
        }
        let data = try ClipboardCodec.encode(fields)
        guard try Self.decode(data) == self else { throw ClipboardCodec.MessageError.invalid }
        return data
    }
}

public struct ClipboardResponse: Equatable, Sendable {
    public enum Result: Equatable, Sendable { case ok, ready, started, unchanged, text(String), unavailable, busy }
    public let sequence: UInt64
    public let result: Result
    public init(sequence: UInt64, result: Result) { self.sequence = sequence; self.result = result }

    public static func decode(_ data: Data) throws -> ClipboardResponse {
        let fields = try ClipboardCodec.fields(data)
        guard fields.count >= 4, fields[1] == "clipboard.result", let sequence = ClipboardCodec.integer(fields[2]) else { throw ClipboardCodec.MessageError.invalid }
        let result: Result
        switch fields[3] {
        case "ok" where fields.count == 4: result = .ok
        case "ready" where fields.count == 4: result = .ready
        case "started" where fields.count == 4: result = .started
        case "unchanged" where fields.count == 4: result = .unchanged
        case "unavailable" where fields.count == 4: result = .unavailable
        case "busy" where fields.count == 4: result = .busy
        case "text" where fields.count == 5 && ClipboardCodec.validText(fields[4]): result = .text(fields[4])
        default: throw ClipboardCodec.MessageError.invalid
        }
        return ClipboardResponse(sequence: sequence, result: result)
    }

    public func encode() throws -> Data {
        guard (1...PointerClick.maximumSequence).contains(sequence) else { throw ClipboardCodec.MessageError.invalid }
        if case let .text(text) = result, !ClipboardCodec.validText(text) { throw ClipboardCodec.MessageError.invalid }
        var fields = ["1", "clipboard.result", String(sequence), ""]
        switch result {
        case .ok: fields[3] = "ok"
        case .ready: fields[3] = "ready"
        case .started: fields[3] = "started"
        case .unchanged: fields[3] = "unchanged"
        case .unavailable: fields[3] = "unavailable"
        case .busy: fields[3] = "busy"
        case let .text(text): fields[3] = "text"; fields.append(text)
        }
        let data = try ClipboardCodec.encode(fields)
        guard try Self.decode(data) == self else { throw ClipboardCodec.MessageError.invalid }
        return data
    }
}

public enum ClipboardCodec {
    public enum MessageError: Error { case invalid }
    public static let maximumTextBytes = 16_384
    public static let maximumMessageBytes = 65_536

    public static func validText(_ value: String) -> Bool {
        (1...maximumTextBytes).contains(value.utf8.count) && !value.unicodeScalars.contains {
            ($0.value < 32 && ![9, 10, 13].contains($0.value)) || (0x7f...0x9f).contains($0.value)
        }
    }

    fileprivate static func integer(_ value: String) -> UInt64? {
        guard !value.isEmpty, value.utf8.count <= 16, !value.hasPrefix("0"),
              value.utf8.allSatisfy({ (48...57).contains($0) }), let number = UInt64(value),
              (1...PointerClick.maximumSequence).contains(number) else { return nil }
        return number
    }

    fileprivate static func fields(_ data: Data) throws -> [String] {
        guard (1...maximumMessageBytes).contains(data.count), String(data: data, encoding: .utf8) != nil,
              !SignalMessage.hasTrailingComma(data),
              let fields = (try? JSONSerialization.jsonObject(with: data)) as? [String],
              fields.count >= 2, fields[0] == "1" else { throw MessageError.invalid }
        return fields
    }

    fileprivate static func encode(_ fields: [String]) throws -> Data {
        let data = try JSONSerialization.data(withJSONObject: fields, options: [.withoutEscapingSlashes])
        guard data.count <= maximumMessageBytes else { throw MessageError.invalid }
        return data
    }
}
