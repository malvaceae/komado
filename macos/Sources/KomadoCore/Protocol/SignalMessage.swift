import Foundation

/// Version 1 signaling messages. A successfully decoded message is still
/// untrusted until it has been accepted by the authenticated session state.
public enum SignalMessage: Equatable, Sendable {
    case pair(name: String, invitation: Data, publicKeyDER: Data)
    case authenticate(deviceID: UUID)
    case challenge(Data)
    case proof(Data)
    case accepted(deviceID: UUID)
    case rejected

    public enum MessageError: Error, Equatable { case invalid }

    public static func decode(_ payload: Data) throws -> SignalMessage {
        guard (1...SignalFrame.maximumPayload).contains(payload.count),
              !hasTrailingComma(payload),
              let fields = (try? JSONSerialization.jsonObject(with: payload)) as? [String],
              fields.count >= 2, fields[0] == "1" else { throw MessageError.invalid }
        switch fields[1] {
        case "pair":
            guard fields.count == 5, validName(fields[2]),
                  let token = decodeBase64(fields[3], range: 32...32),
                  let publicKey = decodeBase64(fields[4], range: 1...128) else { throw MessageError.invalid }
            return .pair(name: fields[2], invitation: token, publicKeyDER: publicKey)
        case "auth":
            guard fields.count == 3, let id = parseUUID(fields[2]) else { throw MessageError.invalid }
            return .authenticate(deviceID: id)
        case "challenge":
            guard fields.count == 3, let data = decodeBase64(fields[2], range: 32...32) else { throw MessageError.invalid }
            return .challenge(data)
        case "proof":
            guard fields.count == 3, let data = decodeBase64(fields[2], range: 1...72) else { throw MessageError.invalid }
            return .proof(data)
        case "accepted":
            guard fields.count == 3, let id = parseUUID(fields[2]) else { throw MessageError.invalid }
            return .accepted(deviceID: id)
        case "rejected":
            guard fields.count == 2 else { throw MessageError.invalid }
            return .rejected
        default:
            throw MessageError.invalid
        }
    }

    public func encode() throws -> Data {
        let fields: [String]
        switch self {
        case let .pair(name, invitation, publicKey):
            guard Self.validName(name), invitation.count == 32, (1...128).contains(publicKey.count) else { throw MessageError.invalid }
            fields = ["1", "pair", name, invitation.base64EncodedString(), publicKey.base64EncodedString()]
        case let .authenticate(id): fields = ["1", "auth", id.uuidString.lowercased()]
        case let .challenge(data):
            guard data.count == 32 else { throw MessageError.invalid }
            fields = ["1", "challenge", data.base64EncodedString()]
        case let .proof(data):
            guard (1...72).contains(data.count) else { throw MessageError.invalid }
            fields = ["1", "proof", data.base64EncodedString()]
        case let .accepted(id): fields = ["1", "accepted", id.uuidString.lowercased()]
        case .rejected: fields = ["1", "rejected"]
        }
        let data = try JSONSerialization.data(withJSONObject: fields, options: [.withoutEscapingSlashes])
        guard try Self.decode(data) == self else { throw MessageError.invalid }
        return data
    }

    private static func validName(_ name: String) -> Bool {
        name.utf8.count <= 256 && !name.trimmingCharacters(in: CharacterSet(charactersIn: " ")).isEmpty &&
            !name.unicodeScalars.contains { CharacterSet.controlCharacters.contains($0) }
    }

    // Foundation accepts a trailing comma in an array even though JSON forbids it.
    private static func hasTrailingComma(_ data: Data) -> Bool {
        var inString = false, escaped = false
        var previous: UInt8?
        for byte in data {
            if inString {
                if escaped { escaped = false }
                else if byte == 0x5c { escaped = true }
                else if byte == 0x22 { inString = false; previous = byte }
            } else if byte == 0x22 {
                inString = true
            } else if byte != 0x20 && byte != 0x09 && byte != 0x0a && byte != 0x0d {
                if byte == 0x5d && previous == 0x2c { return true }
                previous = byte
            }
        }
        return false
    }

    private static func parseUUID(_ string: String) -> UUID? {
        guard let uuid = UUID(uuidString: string), uuid.uuidString.lowercased() == string else { return nil }
        return uuid
    }

    private static func decodeBase64(_ string: String, range: ClosedRange<Int>) -> Data? {
        guard string.utf8.count <= ((range.upperBound + 2) / 3) * 4,
              let data = Data(base64Encoded: string), range.contains(data.count),
              data.base64EncodedString() == string else { return nil }
        return data
    }
}
