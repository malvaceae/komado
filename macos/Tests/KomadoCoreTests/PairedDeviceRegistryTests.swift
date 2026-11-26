import CryptoKit
import Foundation
import Security
import XCTest
@testable import KomadoCore

final class PairedDeviceRegistryTests: XCTestCase {
    func testAddFindListAndRemoveUsingIsolatedKeychainEntries() throws {
        let registry = PairedDeviceRegistry(service: "me.mallows.komado.test.\(UUID().uuidString)")
        let device = PairedDevice(id: UUID(), name: "Android test", publicKeyDER: P256.Signing.PrivateKey().publicKey.derRepresentation)
        let second = PairedDevice(id: UUID(), name: "Another Android", publicKeyDER: P256.Signing.PrivateKey().publicKey.derRepresentation)
        defer { _ = try? registry.remove(id: device.id); _ = try? registry.remove(id: second.id) }
        XCTAssertNil(try registry.find(id: device.id))
        XCTAssertEqual(try registry.all(), [])
        try registry.add(device)
        XCTAssertEqual(try registry.find(id: device.id), device)
        XCTAssertEqual(try registry.all(), [device])
        try registry.add(second)
        XCTAssertEqual(Set(try registry.all().map(\.id)), Set([device.id, second.id]))
        XCTAssertThrowsError(try registry.add(device)) { XCTAssertEqual($0 as? PairedDeviceRegistry.RegistryError, .duplicateDevice) }
        XCTAssertTrue(try registry.remove(id: device.id))
        XCTAssertEqual(try registry.all(), [second])
        XCTAssertFalse(try registry.remove(id: device.id))
        XCTAssertNil(try registry.find(id: device.id))
    }

    func testRejectsInvalidPublicKeysAndNames() {
        let registry = PairedDeviceRegistry(service: "me.mallows.komado.test.\(UUID().uuidString)")
        let validKey = P256.Signing.PrivateKey().publicKey.derRepresentation
        for device in [
            PairedDevice(id: UUID(), name: " ", publicKeyDER: validKey),
            PairedDevice(id: UUID(), name: "Invalid\nName", publicKeyDER: validKey),
            PairedDevice(id: UUID(), name: "Android", publicKeyDER: Data()),
        ] {
            XCTAssertThrowsError(try registry.add(device)) { XCTAssertEqual($0 as? PairedDeviceRegistry.RegistryError, .invalidDevice) }
        }
    }

    func testCorruptKeychainDataIsNotTreatedAsARegisteredDevice() throws {
        let service = "me.mallows.komado.test.\(UUID().uuidString)"
        let registry = PairedDeviceRegistry(service: service), id = UUID()
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service,
                                    kSecAttrAccount as String: id.uuidString,
                                    kSecValueData as String: Data("invalid".utf8)]
        XCTAssertEqual(SecItemAdd(query as CFDictionary, nil), errSecSuccess)
        defer { _ = try? registry.remove(id: id) }
        XCTAssertThrowsError(try registry.find(id: id)) { XCTAssertEqual($0 as? PairedDeviceRegistry.RegistryError, .corruptRecord) }
        XCTAssertThrowsError(try registry.all()) { XCTAssertEqual($0 as? PairedDeviceRegistry.RegistryError, .corruptRecord) }
    }
}
