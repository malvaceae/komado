import Foundation

/// The QR payload is a short-lived invitation and must not be logged or persisted.
public struct PairingInvitation: Equatable, Sendable {
    public enum MessageError: Error { case invalid }
    public let name: String
    public let host: String
    public let port: UInt16
    public let fingerprint: String
    public let token: String

    public init(name: String, host: String, port: UInt16, fingerprint: String, token: String) {
        self.name = name
        self.host = host
        self.port = port
        self.fingerprint = fingerprint
        self.token = token
    }

    public func encode() throws -> Data {
        guard Self.validName(name), Self.validHost(host), port != 0, Self.validHex(fingerprint), Self.validHex(token) else { throw MessageError.invalid }
        let data = try JSONSerialization.data(withJSONObject: ["1", "pairing", name, host, String(port), fingerprint, token])
        guard try Self.decode(data) == self else { throw MessageError.invalid }
        return data
    }

    public static func decode(_ data: Data) throws -> PairingInvitation {
        guard (1...1024).contains(data.count), !SignalMessage.hasTrailingComma(data),
              let fields = (try? JSONSerialization.jsonObject(with: data)) as? [String], fields.count == 7,
              fields[0] == "1", fields[1] == "pairing", validName(fields[2]), validHost(fields[3]),
              !fields[4].isEmpty, !fields[4].hasPrefix("0"), fields[4].utf8.allSatisfy({ (48...57).contains($0) }),
              let port = UInt16(fields[4]), port != 0, validHex(fields[5]), validHex(fields[6]) else { throw MessageError.invalid }
        return PairingInvitation(name: fields[2], host: fields[3], port: port, fingerprint: fields[5], token: fields[6])
    }

    private static func validName(_ name: String) -> Bool {
        !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && name.utf8.count <= 256 &&
            name.unicodeScalars.allSatisfy { !($0.value <= 0x1F || (0x7F...0x9F).contains($0.value)) }
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
