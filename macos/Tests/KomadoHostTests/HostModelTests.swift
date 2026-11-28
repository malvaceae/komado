import Foundation
import CryptoKit
import Security
import XCTest
@testable import KomadoCore
@testable import KomadoHost

final class HostModelTests: XCTestCase {
    @MainActor
    func testRestoresListeningAfterInterruptedRunAndStopCancelsIt() async throws {
        let service = "me.mallows.komado.test.restore.\(UUID().uuidString)"
        let settings = try XCTUnwrap(UserDefaults(suiteName: service))
        defer {
            settings.removePersistentDomain(forName: service)
            SecItemDelete([kSecClass: kSecClassKey, kSecAttrKeyClass: kSecAttrKeyClassPrivate,
                           kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
                           kSecAttrApplicationTag: Data("\(service).private-key".utf8)] as CFDictionary)
            SecItemDelete([kSecClass: kSecClassGenericPassword, kSecAttrService: service,
                           kSecAttrAccount: "certificate-v1"] as CFDictionary)
        }
        let identity = HostTLSIdentityStore(service: service)
        _ = try identity.create()
        let registry = PairedDeviceRegistry(service: "\(service).devices")
        let pairing = PairingCoordinator(registry: registry, now: { .zero })
        let auth = DeviceAuthenticationCoordinator(registry: registry, now: { .zero })
        let deviceID = UUID()
        settings.set(deviceID.uuidString, forKey: "resumeDeviceID")
        settings.set(true, forKey: "restoreListening")
        let restored = HostModel(identityStore: identity, registry: registry, pairing: pairing, authentication: auth, settings: settings)
        try await waitFor { restored.ready || restored.error != nil }
        if restored.error == "利用できるネットワークアドレスがありません。" { throw XCTSkip("No active local interface") }
        XCTAssertNil(restored.error)
        XCTAssertTrue(restored.running)
        XCTAssertEqual(settings.string(forKey: "resumeDeviceID"), deviceID.uuidString)
        await restored.stop()
        XCTAssertFalse(settings.bool(forKey: "restoreListening"))
        XCTAssertNil(settings.string(forKey: "resumeDeviceID"))
        let stopped = HostModel(identityStore: identity, registry: registry, pairing: pairing, authentication: auth, settings: settings)
        try await waitFor { stopped.networkReady }
        XCTAssertFalse(stopped.running)
        XCTAssertFalse(stopped.restoreListening)
    }

    @MainActor
    func testRemovingDeviceRevokesPendingResume() async throws {
        let service = "me.mallows.komado.test.resume.\(UUID().uuidString)"
        let settings = try XCTUnwrap(UserDefaults(suiteName: service))
        let registry = PairedDeviceRegistry(service: service)
        defer {
            settings.removePersistentDomain(forName: service)
            SecItemDelete([kSecClass: kSecClassGenericPassword, kSecAttrService: service] as CFDictionary)
        }
        let first = PairedDevice(id: UUID(), name: "First", publicKeyDER: P256.Signing.PrivateKey().publicKey.derRepresentation)
        let second = PairedDevice(id: UUID(), name: "Second", publicKeyDER: P256.Signing.PrivateKey().publicKey.derRepresentation)
        try registry.add(first)
        try registry.add(second)
        settings.set(first.id.uuidString, forKey: "resumeDeviceID")
        let model = HostModel(identityStore: HostTLSIdentityStore(service: service), registry: registry,
                              pairing: PairingCoordinator(registry: registry, now: { .zero }),
                              authentication: DeviceAuthenticationCoordinator(registry: registry, now: { .zero }), settings: settings)
        await model.remove(second)
        XCTAssertNil(settings.string(forKey: "resumeDeviceID"))
        XCTAssertNotNil(try registry.find(id: first.id))
        XCTAssertNil(try registry.find(id: second.id))
    }

    @MainActor
    func testAccessibilityPermissionRequestAndRefresh() throws {
        let service = "me.mallows.komado.test.permissions.\(UUID().uuidString)"
        let settings = try XCTUnwrap(UserDefaults(suiteName: service))
        defer { settings.removePersistentDomain(forName: service) }
        let registry = PairedDeviceRegistry(service: service)
        var trusted = false
        var requests = 0
        let model = HostModel(identityStore: HostTLSIdentityStore(service: service), registry: registry,
                              pairing: PairingCoordinator(registry: registry, now: { .zero }),
                              authentication: DeviceAuthenticationCoordinator(registry: registry, now: { .zero }), settings: settings,
                              accessibilityStatus: { trusted }, accessibilityPrompt: { requests += 1 })
        XCTAssertFalse(model.accessibilityPermission)
        XCTAssertEqual(requests, 0)
        model.requestAccessibilityPermission()
        XCTAssertEqual(requests, 1)
        XCTAssertFalse(model.accessibilityPermission)
        trusted = true
        model.refreshPermissions()
        XCTAssertTrue(model.accessibilityPermission)
        trusted = false
        model.refreshPermissions()
        XCTAssertFalse(model.accessibilityPermission)
    }

    @MainActor
    func testHostStartsIssuesInvitationAndStops() async throws {
        let service = "me.mallows.komado.test.model.\(UUID().uuidString)", registryService = "\(service).devices"
        let settings = try XCTUnwrap(UserDefaults(suiteName: service))
        defer {
            settings.removePersistentDomain(forName: service)
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
                              pairing: pairing, authentication: auth, settings: settings)
        XCTAssertFalse(model.allowRemoteControl)
        XCTAssertFalse(model.keepAwakeWhileListening)
        XCTAssertEqual(model.captureResolution, .standard)
        model.captureResolution = .reduced
        XCTAssertEqual(settings.string(forKey: "captureResolution"), "reduced")
        let restored = HostModel(identityStore: HostTLSIdentityStore(service: service), registry: registry,
                                 pairing: pairing, authentication: auth, settings: settings)
        XCTAssertEqual(restored.captureResolution, .reduced)
        settings.set("invalid", forKey: "captureResolution")
        let invalid = HostModel(identityStore: HostTLSIdentityStore(service: service), registry: registry,
                                pairing: pairing, authentication: auth, settings: settings)
        XCTAssertEqual(invalid.captureResolution, .standard)
        settings.set("reduced", forKey: "captureResolution")
        model.keepAwakeWhileListening = true
        XCTAssertTrue(settings.bool(forKey: "keepAwakeWhileListening"))
        XCTAssertFalse(try waitingAssertionExists())
        try await waitFor { model.networkReady }
        model.refresh()
        XCTAssertNil(model.fingerprint)
        model.createIdentity()
        XCTAssertNotNil(model.fingerprint)
        await model.start()
        if model.error == "利用できるネットワークアドレスがありません。" { throw XCTSkip("No active local interface") }
        try await waitFor { model.ready || model.error != nil }
        XCTAssertNil(model.error)
        XCTAssertTrue(model.ready)
        XCTAssertTrue(settings.bool(forKey: "restoreListening"))
        XCTAssertFalse(model.addresses.isEmpty)
        XCTAssertEqual(Set(model.pairingEndpoints.map(\.port)).count, 1)
        XCTAssertTrue(try waitingAssertionExists())
        model.keepAwakeWhileListening = false
        XCTAssertFalse(try waitingAssertionExists())
        model.keepAwakeWhileListening = true
        XCTAssertTrue(try waitingAssertionExists())
        await model.issueInvitation()
        XCTAssertEqual(model.invitation?.count, 64)
        model.allowRemoteControl = true
        await model.stop()
        XCTAssertFalse(model.running)
        XCTAssertFalse(settings.bool(forKey: "restoreListening"))
        XCTAssertFalse(model.allowRemoteControl)
        XCTAssertNil(model.invitation)
        XCTAssertFalse(try waitingAssertionExists())
        XCTAssertTrue(settings.bool(forKey: "keepAwakeWhileListening"))
    }

    private func waitingAssertionExists() throws -> Bool {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/pmset")
        process.arguments = ["-g", "assertions"]
        let pipe = Pipe()
        process.standardOutput = pipe
        try process.run()
        let output = String(decoding: pipe.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
        process.waitUntilExit()
        guard process.terminationStatus == 0 else { throw NSError(domain: "HostModelTests", code: Int(process.terminationStatus)) }
        return output.split(separator: "\n").contains { $0.contains("pid \(getpid())(") && $0.contains("komado waiting for connections") }
    }

    @MainActor
    private func waitFor(_ condition: () -> Bool) async throws {
        let clock = ContinuousClock(), deadline = clock.now.advanced(by: .seconds(10))
        while !condition() && clock.now < deadline { try await Task.sleep(for: .milliseconds(50)) }
        XCTAssertTrue(condition(), "Timed out waiting for host state")
    }
}
