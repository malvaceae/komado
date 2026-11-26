import CryptoKit
import Foundation
import Security
import XCTest
@testable import KomadoCore

final class HostTLSIdentityStoreTests: XCTestCase {
    func testCreateAndReloadSameIdentity() throws {
        let service = "me.mallows.komado.test.tls.\(UUID().uuidString)"
        let store = HostTLSIdentityStore(service: service)
        defer { clean(service: service) }
        XCTAssertNil(try store.load())
        let created = try store.create()
        XCTAssertEqual(created.fingerprintSHA256, Data(SHA256.hash(data: created.certificateDER)))
        let restored = try XCTUnwrap(store.load())
        XCTAssertEqual(restored.certificateDER, created.certificateDER)
        XCTAssertEqual(restored.fingerprintSHA256, created.fingerprintSHA256)
        XCTAssertThrowsError(try store.create()) { XCTAssertEqual($0 as? HostTLSIdentityStore.StoreError, .alreadyExists) }
        XCTAssertEqual(try store.load()?.fingerprintSHA256, created.fingerprintSHA256)
    }

    func testIncompleteRecordNeverAutomaticallyCreatesNewIdentity() throws {
        let service = "me.mallows.komado.test.tls.\(UUID().uuidString)"
        let store = HostTLSIdentityStore(service: service)
        defer { clean(service: service) }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: "certificate-v1",
            kSecValueData as String: Data([0]),
        ]
        XCTAssertEqual(SecItemAdd(query as CFDictionary, nil), errSecSuccess)
        XCTAssertThrowsError(try store.load()) { XCTAssertEqual($0 as? HostTLSIdentityStore.StoreError, .incompleteOrInvalid) }
        XCTAssertThrowsError(try store.create()) { XCTAssertEqual($0 as? HostTLSIdentityStore.StoreError, .alreadyExists) }
    }

    func testMissingPrivateKeyDoesNotRegenerateIdentity() throws {
        let service = "me.mallows.komado.test.tls.\(UUID().uuidString)"
        let store = HostTLSIdentityStore(service: service)
        defer { clean(service: service) }
        _ = try store.create()
        XCTAssertEqual(SecItemDelete(keyQuery(service: service) as CFDictionary), errSecSuccess)
        XCTAssertThrowsError(try store.load()) { XCTAssertEqual($0 as? HostTLSIdentityStore.StoreError, .incompleteOrInvalid) }
        XCTAssertThrowsError(try store.create()) { XCTAssertEqual($0 as? HostTLSIdentityStore.StoreError, .alreadyExists) }
    }

    func testOrphanedKeyDoesNotCauseSilentIdentityReplacement() throws {
        let service = "me.mallows.komado.test.tls.\(UUID().uuidString)"
        let store = HostTLSIdentityStore(service: service)
        defer { clean(service: service) }
        let parameters: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: Data("\(service).private-key".utf8),
            ],
        ]
        XCTAssertNotNil(SecKeyCreateRandomKey(parameters as CFDictionary, nil))
        XCTAssertThrowsError(try store.create()) { XCTAssertEqual($0 as? HostTLSIdentityStore.StoreError, .incompleteOrInvalid) }
        XCTAssertThrowsError(try store.load()) { XCTAssertEqual($0 as? HostTLSIdentityStore.StoreError, .incompleteOrInvalid) }
    }

    private func keyQuery(service: String) -> [String: Any] {
        [kSecClass as String: kSecClassKey,
         kSecAttrKeyClass as String: kSecAttrKeyClassPrivate,
         kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
         kSecAttrApplicationTag as String: Data("\(service).private-key".utf8)]
    }

    private func clean(service: String) {
        SecItemDelete(keyQuery(service: service) as CFDictionary)
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service,
                                    kSecAttrAccount as String: "certificate-v1"]
        SecItemDelete(query as CFDictionary)
    }
}
