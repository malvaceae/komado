import CryptoKit
import Foundation
import Security

public struct PairedDevice: Codable, Equatable, Sendable {
    public let id: UUID
    public let name: String
    public let publicKeyDER: Data

    public init(id: UUID, name: String, publicKeyDER: Data) {
        self.id = id
        self.name = name
        self.publicKeyDER = publicKeyDER
    }
}

/// Stores paired device public keys in the user's Keychain. The caller must
/// authenticate the pairing invitation before adding a device and must close
/// existing sessions and invalidate pending challenges when removing one.
public struct PairedDeviceRegistry: Sendable {
    public enum RegistryError: Error, Equatable {
        case invalidDevice
        case duplicateDevice
        case corruptRecord
        case keychain(OSStatus)
    }

    private let service: String

    public init() {
        self.service = "me.mallows.komado.paired-devices"
    }

    // Allows tests to isolate their own records from the application registry.
    init(service: String) {
        self.service = service
    }

    public func add(_ device: PairedDevice) throws {
        guard isValid(device) else { throw RegistryError.invalidDevice }
        let data = try JSONEncoder().encode(device)
        var query = baseQuery(id: device.id)
        query[kSecValueData as String] = data
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status != errSecDuplicateItem else { throw RegistryError.duplicateDevice }
        guard status == errSecSuccess else { throw RegistryError.keychain(status) }
    }

    public func find(id: UUID) throws -> PairedDevice? {
        var query = baseQuery(id: id)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw RegistryError.keychain(status) }
        guard let data = result as? Data else { throw RegistryError.corruptRecord }
        return try decode(data, expectedID: id)
    }

    public func all() throws -> [PairedDevice] {
        var query = baseQuery()
        query[kSecReturnAttributes as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitAll
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return [] }
        guard status == errSecSuccess else { throw RegistryError.keychain(status) }
        guard let rows = result as? [[String: Any]] else { throw RegistryError.corruptRecord }
        return try rows.map { row in
            guard let account = row[kSecAttrAccount as String] as? String,
                  let id = UUID(uuidString: account),
                  let device = try find(id: id) else {
                throw RegistryError.corruptRecord
            }
            return device
        }.sorted { $0.id.uuidString < $1.id.uuidString }
    }

    @discardableResult
    public func remove(id: UUID) throws -> Bool {
        let status = SecItemDelete(baseQuery(id: id) as CFDictionary)
        if status == errSecItemNotFound { return false }
        guard status == errSecSuccess else { throw RegistryError.keychain(status) }
        return true
    }

    private func baseQuery(id: UUID? = nil) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
        ]
        if let id { query[kSecAttrAccount as String] = id.uuidString }
        return query
    }

    func isValid(_ device: PairedDevice) -> Bool {
        let name = device.name.trimmingCharacters(in: .whitespacesAndNewlines)
        return !name.isEmpty && device.name.utf8.count <= 256 &&
            !device.name.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) &&
            (1...128).contains(device.publicKeyDER.count) &&
            (try? P256.Signing.PublicKey(derRepresentation: device.publicKeyDER)) != nil
    }

    private func decode(_ data: Data, expectedID: UUID) throws -> PairedDevice {
        guard let device = try? JSONDecoder().decode(PairedDevice.self, from: data),
              device.id == expectedID, isValid(device) else {
            throw RegistryError.corruptRecord
        }
        return device
    }
}
