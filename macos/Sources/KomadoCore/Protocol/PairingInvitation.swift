import Foundation

/// The QR payload is a short-lived invitation and must not be logged or persisted.
public struct PairingInvitation: Equatable, Sendable {
    public enum MessageError: Error { case invalid }
    public let host: String
    public let port: UInt16
    public let fingerprint: String
    public let token: String

    public init(host: String, port: UInt16, fingerprint: String, token: String) {
        self.host = host
        self.port = port
        self.fingerprint = fingerprint
        self.token = token
    }

    public func encode() throws -> Data {
        guard Self.validHost(host), port != 0, Self.validHex(fingerprint), Self.validHex(token) else { throw MessageError.invalid }
        let data = try JSONSerialization.data(withJSONObject: ["1", "pairing", host, String(port), fingerprint, token])
        guard try Self.decode(data) == self else { throw MessageError.invalid }
        return data
    }

    public static func decode(_ data: Data) throws -> PairingInvitation {
        guard (1...1024).contains(data.count), !SignalMessage.hasTrailingComma(data),
              let fields = (try? JSONSerialization.jsonObject(with: data)) as? [String], fields.count == 6,
              fields[0] == "1", fields[1] == "pairing", validHost(fields[2]),
              !fields[3].isEmpty, !fields[3].hasPrefix("0"), fields[3].utf8.allSatisfy({ (48...57).contains($0) }),
              let port = UInt16(fields[3]), port != 0, validHex(fields[4]), validHex(fields[5]) else { throw MessageError.invalid }
        return PairingInvitation(host: fields[2], port: port, fingerprint: fields[4], token: fields[5])
    }

    private static func validHost(_ host: String) -> Bool {
        (1...253).contains(host.utf8.count) && host.utf8.allSatisfy {
            (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || [45, 46, 58, 95, 37].contains($0)
        }
    }

    private static func validHex(_ value: String) -> Bool {
        value.utf8.count == 64 && value.utf8.allSatisfy { (48...57).contains($0) || (65...70).contains($0) }
    }
}
