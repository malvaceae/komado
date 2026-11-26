import Foundation
import Security
import XCTest
@testable import KomadoCore
@testable import KomadoHost

final class HostModelTests: XCTestCase {
    @MainActor
    func testHostStartsIssuesInvitationAndStops() async throws {
        let service = "me.mallows.komado.test.model.\(UUID().uuidString)", registryService = "\(service).devices"
        defer {
            SecItemDelete([kSecClass: kSecClassKey, kSecAttrKeyClass: kSecAttrKeyClassPrivate,
                           kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
                           kSecAttrApplicationTag: Data("\(service).private-key".utf8)] as CFDictionary)
            SecItemDelete([kSecClass: kSecClassGenericPassword, kSecAttrService: service,
                           kSecAttrAccount: "certificate-v1"] as CFDictionary)
            SecItemDelete([kSecClass: kSecClassGenericPassword, kSecAttrService: registryService] as CFDictionary)
        }
        let registry = PairedDeviceRegistry(service: registryService)
        let pairing = PairingCoordinator(registry: registry, now: { .zero })
        let auth = DeviceAuthenticationCoordinator(registry: registry, now: { .zero })
        let model = HostModel(identityStore: HostTLSIdentityStore(service: service), registry: registry,
                              pairing: pairing, authentication: auth)
        try await waitFor { model.networkReady }
        model.refresh()
        XCTAssertNil(model.fingerprint)
        model.createIdentity()
        XCTAssertNotNil(model.fingerprint)
        await model.start()
        if model.error == "利用できるLANまたはVPNのアドレスがありません。" { throw XCTSkip("No active local interface") }
        try await waitFor { model.ready || model.error != nil }
        XCTAssertNil(model.error)
        XCTAssertTrue(model.ready)
        XCTAssertFalse(model.addresses.isEmpty)
        await model.issueInvitation()
        XCTAssertEqual(model.invitation?.count, 64)
        await model.stop()
        XCTAssertFalse(model.running)
        XCTAssertNil(model.invitation)
    }

    @MainActor
    private func waitFor(_ condition: () -> Bool) async throws {
        let clock = ContinuousClock(), deadline = clock.now.advanced(by: .seconds(10))
        while !condition() && clock.now < deadline { try await Task.sleep(for: .milliseconds(50)) }
        XCTAssertTrue(condition(), "Timed out waiting for host state")
    }
}
