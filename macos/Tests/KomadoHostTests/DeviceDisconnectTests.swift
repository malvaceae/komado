import CryptoKit
import Foundation
import Network
import Security
import XCTest
@testable import KomadoCore
@testable import KomadoHost

final class DeviceDisconnectTests: XCTestCase {
    @MainActor func testDisconnectKeepsListeningAndRejectsAutomaticResume() async throws {
        let service = "me.mallows.komado.test.disconnect.\(UUID().uuidString)"
        let registryService = "\(service).devices"
        let settings = try XCTUnwrap(UserDefaults(suiteName: service))
        defer {
            settings.removePersistentDomain(forName: service)
            SecItemDelete([kSecClass: kSecClassKey, kSecAttrKeyClass: kSecAttrKeyClassPrivate,
                           kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
                           kSecAttrApplicationTag: Data("\(service).private-key".utf8)] as CFDictionary)
            SecItemDelete([kSecClass: kSecClassGenericPassword, kSecAttrService: service] as CFDictionary)
            SecItemDelete([kSecClass: kSecClassGenericPassword, kSecAttrService: registryService] as CFDictionary)
        }
        let registry = PairedDeviceRegistry(service: registryService)
        let key = P256.Signing.PrivateKey()
        let device = PairedDevice(id: UUID(), name: "Test device", publicKeyDER: key.publicKey.derRepresentation)
        try registry.add(device)
        settings.set(device.id.uuidString, forKey: "resumeDeviceID")
        let identity = HostTLSIdentityStore(service: service)
        let fingerprint = try identity.create().fingerprintSHA256
        let model = HostModel(identityStore: identity, registry: registry,
                              pairing: PairingCoordinator(registry: registry, now: { .zero }),
                              authentication: DeviceAuthenticationCoordinator(registry: registry, now: { .zero }), settings: settings)
        defer { Task { await model.stop() } }
        for _ in 0..<100 {
            if model.networkReady { break }
            try await Task.sleep(for: .milliseconds(50))
        }
        await model.start()
        for _ in 0..<100 {
            if model.ready || model.error != nil { break }
            try await Task.sleep(for: .milliseconds(50))
        }
        guard model.ready, let endpoint = model.pairingEndpoints.first(where: { !$0.address.contains(":") }) else {
            throw XCTSkip("No local IPv4 listener")
        }
        let first = DisconnectTestPeer(host: endpoint.address, port: endpoint.port, fingerprint: fingerprint)
        defer { first.close() }
        try await first.authenticate(device.id, key: key)
        for _ in 0..<100 {
            if model.connectedDevice != nil { break }
            try await Task.sleep(for: .milliseconds(50))
        }
        XCTAssertEqual(model.connectedDevice, device.name)
        model.allowRemoteControl = true
        await model.disconnectDevice()
        for _ in 0..<100 {
            if model.connectedDevice == nil { break }
            try await Task.sleep(for: .milliseconds(50))
        }
        XCTAssertNil(model.connectedDevice)
        XCTAssertTrue(model.running)
        XCTAssertTrue(model.ready)
        XCTAssertFalse(model.allowRemoteControl)
        XCTAssertNil(settings.string(forKey: "resumeDeviceID"))
        XCTAssertNotNil(try registry.find(id: device.id))
        let second = DisconnectTestPeer(host: endpoint.address, port: endpoint.port, fingerprint: fingerprint)
        defer { second.close() }
        try await second.authenticate(device.id, key: key)
        try await second.send(.resumeCheck)
        let response = try await second.receive()
        XCTAssertEqual(response, .resumeDenied)
        await model.stop()
    }
}

private final class DisconnectTestPeer: @unchecked Sendable {
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "me.mallows.komado.test.disconnect")
    private let lock = NSLock()
    private var completed = false
    enum Failure: Error { case closed, timedOut, invalidFrame, unexpectedResponse }

    init(host: String, port: UInt16, fingerprint: Data) {
        let tls = NWProtocolTLS.Options()
        sec_protocol_options_set_verify_block(tls.securityProtocolOptions, { _, trust, complete in
            let ref = sec_trust_copy_ref(trust).takeRetainedValue()
            let chain = SecTrustCopyCertificateChain(ref) as? [SecCertificate]
            complete(chain?.first.map { Data(SHA256.hash(data: SecCertificateCopyData($0) as Data)) == fingerprint } ?? false)
        }, queue)
        connection = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!, using: NWParameters(tls: tls, tcp: .init()))
    }

    func authenticate(_ id: UUID, key: P256.Signing.PrivateKey) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.stateUpdateHandler = { [self] state in
                let result: Result<Void, Error>?
                switch state {
                case .ready: result = .success(())
                case let .failed(error): result = .failure(error)
                case .cancelled: result = .failure(Failure.closed)
                default: result = nil
                }
                guard let result, lock.withLock({ if completed { return false }; completed = true; return true }) else { return }
                continuation.resume(with: result)
            }
            connection.start(queue: queue)
            Task {
                try? await Task.sleep(for: .seconds(10))
                if lock.withLock({ if completed { return false }; completed = true; return true }) {
                    connection.cancel(); continuation.resume(throwing: Failure.timedOut)
                }
            }
        }
        try await send(.authenticate(deviceID: id))
        guard case let .challenge(challenge) = try await receive() else { throw Failure.unexpectedResponse }
        let signature = try key.signature(for: Data("komado.device-auth.v1\0".utf8) + challenge).derRepresentation
        try await send(.proof(signature))
        guard case .accepted = try await receive() else { throw Failure.unexpectedResponse }
    }

    func send(_ message: SignalMessage) async throws {
        let data = try SignalFrame.encode(message.encode())
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            })
        }
    }

    func receive() async throws -> SignalMessage {
        let header = try await read(4)
        let size = header.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        guard (1...SignalFrame.maximumPayload).contains(Int(size)) else { throw Failure.invalidFrame }
        return try SignalMessage.decode(try await read(Int(size)))
    }

    private func read(_ count: Int) async throws -> Data {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
            connection.receive(minimumIncompleteLength: count, maximumLength: count) { data, _, _, error in
                if let error { continuation.resume(throwing: error) }
                else if let data, data.count == count { continuation.resume(returning: data) }
                else { continuation.resume(throwing: Failure.closed) }
            }
        }
    }

    func close() { connection.cancel() }
}
