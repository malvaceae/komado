import CryptoKit
import Foundation
import Network
import Security
import XCTest
@testable import KomadoCore

private final class ServerTestBox: @unchecked Sendable {
    private let lock = NSLock()
    private var interface: NWInterface?
    private var port: UInt16?
    private var authenticationCount = 0
    private var answer: String?
    func setInterface(_ value: NWInterface?) { lock.withLock { interface = value } }
    func getInterface() -> NWInterface? { lock.withLock { interface } }
    func setPort(_ value: UInt16) { lock.withLock { port = value } }
    func getPort() -> UInt16? { lock.withLock { port } }
    func nextAuthentication() -> Int { lock.withLock { authenticationCount += 1; return authenticationCount } }
    func setAnswer(_ value: String) { lock.withLock { answer = value } }
    func getAnswer() -> String? { lock.withLock { answer } }
}

private final class ServerTestPeer: @unchecked Sendable {
    enum PeerError: Error { case closed, badFrame, timedOut }
    let connection: NWConnection
    let queue = DispatchQueue(label: "me.mallows.komado.test.peer")
    private let lock = NSLock()
    private var started = false

    init(host: NWEndpoint.Host, port: NWEndpoint.Port, fingerprint: Data) {
        let tls = NWProtocolTLS.Options()
        sec_protocol_options_set_verify_block(tls.securityProtocolOptions, { _, trust, complete in
            let ref = sec_trust_copy_ref(trust).takeRetainedValue()
            let chain = SecTrustCopyCertificateChain(ref) as? [SecCertificate]
            if let cert = chain?.first {
                complete(Data(SHA256.hash(data: SecCertificateCopyData(cert) as Data)) == fingerprint)
            } else { complete(false) }
        }, queue)
        connection = NWConnection(to: .hostPort(host: host, port: port), using: NWParameters(tls: tls, tcp: .init()))
    }

    func start() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.stateUpdateHandler = { [self] state in
                switch state {
                case .ready:
                    let first = lock.withLock { if started { return false }; started = true; return true }
                    if first { continuation.resume() }
                case let .failed(error):
                    let first = lock.withLock { if started { return false }; started = true; return true }
                    if first { continuation.resume(throwing: error) }
                case .cancelled:
                    let first = lock.withLock { if started { return false }; started = true; return true }
                    if first { continuation.resume(throwing: PeerError.closed) }
                default: break
                }
            }
            connection.start(queue: queue)
            Task {
                try? await Task.sleep(for: .seconds(10))
                let first = lock.withLock { if started { return false }; started = true; return true }
                if first { connection.cancel(); continuation.resume(throwing: PeerError.timedOut) }
            }
        }
    }

    func send(_ message: SignalMessage) async throws {
        let data = try SignalFrame.encode(message.encode())
        try await sendRaw(data)
    }

    func sendRaw(_ data: Data) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            })
        }
    }

    func receive() async throws -> SignalMessage {
        let header = try await read(4)
        let size = header.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        guard size > 0 && size <= SignalFrame.maximumPayload else { throw PeerError.badFrame }
        return try SignalMessage.decode(try await read(Int(size)))
    }

    private func read(_ count: Int) async throws -> Data {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
            connection.receive(minimumIncompleteLength: count, maximumLength: count) { data, _, _, error in
                if let error { continuation.resume(throwing: error) }
                else if let data, data.count == count { continuation.resume(returning: data) }
                else { continuation.resume(throwing: PeerError.closed) }
            }
        }
    }
}

final class SignalingServerTests: XCTestCase {
    func testLocalServerPairsAndAuthenticatesAgain() async throws {
        let box = ServerTestBox(), pathReady = expectation(description: "network path")
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { path in
            box.setInterface(path.availableInterfaces.first { $0.type == .wifi || $0.type == .wiredEthernet })
            pathReady.fulfill()
        }
        monitor.start(queue: DispatchQueue.global())
        defer { monitor.cancel() }
        await fulfillment(of: [pathReady], timeout: 10)
        guard let interface = box.getInterface(),
              let address = try ListenerAccess.addresses(on: interface).first(where: { if case .ipv4 = $0 { return true }; return false }) else {
            throw XCTSkip("No active local IPv4 interface")
        }
        let service = "me.mallows.komado.test.server.\(UUID().uuidString)", registryService = "\(service).devices"
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
        let invitation = try await pairing.issueInvitation()
        let identity = try HostTLSIdentityStore(service: service).create()
        let fingerprint = identity.fingerprintSHA256
        let listening = expectation(description: "listening")
        let authenticated = expectation(description: "two authenticated connections")
        authenticated.expectedFulfillmentCount = 2
        let firstAuthenticated = expectation(description: "first connection authenticated")
        let mediaRequested = expectation(description: "media requested")
        let answerReceived = expectation(description: "SDP answer")
        let disconnected = expectation(description: "four disconnections")
        disconnected.expectedFulfillmentCount = 4
        let server = try SignalingServer.create(identity: identity.identity,
            bindings: [ListenerBinding(scope: .localLink, interface: interface, address: address)],
            port: .any, pairing: pairing, authentication: auth) { event in
                switch event {
                case let .listening(_, _, port): box.setPort(port); listening.fulfill()
                case .authenticated:
                    if box.nextAuthentication() == 1 { firstAuthenticated.fulfill() }
                    authenticated.fulfill()
                case .mediaRequested: mediaRequested.fulfill()
                case let .answer(_, sdp): box.setAnswer(sdp); answerReceived.fulfill()
                case .disconnected: disconnected.fulfill()
                default: break
                }
            }
        try await server.start()
        await fulfillment(of: [listening], timeout: 10)
        let port = try XCTUnwrap(box.getPort()).asPort()
        do {
            try await server.sendOffer("v=0\r\n")
            XCTFail("Offer must not be sent without an authenticated connection")
        } catch { }
        let key = P256.Signing.PrivateKey()
        let first = ServerTestPeer(host: address, port: port, fingerprint: fingerprint)
        try await first.start()
        try await first.send(.pair(name: "Android", invitation: invitation, publicKeyDER: key.publicKey.derRepresentation))
        guard case let .challenge(challenge) = try await first.receive() else { return XCTFail("Expected challenge") }
        let signature = try key.signature(for: Data("komado.device-auth.v1\0".utf8) + challenge).derRepresentation
        try await first.send(.proof(signature))
        guard case let .accepted(deviceID) = try await first.receive() else { return XCTFail("Expected acceptance") }
        await fulfillment(of: [firstAuthenticated], timeout: 10)
        let localCandidate = "a=candidate:1 1 udp 1 \(address) 54321 typ host\r\n"
        let otherCandidate = "a=candidate:2 1 udp 1 192.0.2.5 54322 typ host\r\n"
        let offer = "v=0\r\ns=offer\r\nc=IN IP4 192.0.2.5\r\n" + localCandidate + otherCandidate
        let expectedOffer = "v=0\r\ns=offer\r\nc=IN IP4 \(address)\r\n" + localCandidate
        let answer = "v=0\r\ns=answer\r\n"
        do {
            try await server.sendOffer(offer)
            XCTFail("Offer must wait for an explicit media request")
        } catch { }
        try await first.send(.startMedia)
        await fulfillment(of: [mediaRequested], timeout: 10)
        do {
            try await server.sendOffer("v=0\r\n" + otherCandidate)
            XCTFail("Offer without a candidate for the signaling address must be rejected")
        } catch { }
        try await server.sendOffer(offer)
        let receivedOffer = try await first.receive()
        XCTAssertEqual(receivedOffer, .offer(expectedOffer))
        try await first.send(.answer(answer))
        await fulfillment(of: [answerReceived], timeout: 10)
        XCTAssertEqual(box.getAnswer(), answer)
        do {
            try await server.sendOffer(offer)
            XCTFail("A second offer must not be accepted")
        } catch { }
        first.connection.cancel()
        // The server permits the next connection after it observes this close.
        await server.disconnectCurrent()
        let second = ServerTestPeer(host: address, port: port, fingerprint: fingerprint)
        try await second.start()
        try await second.send(.authenticate(deviceID: deviceID))
        guard case let .challenge(secondChallenge) = try await second.receive() else { return XCTFail("Expected challenge") }
        let secondSignature = try key.signature(for: Data("komado.device-auth.v1\0".utf8) + secondChallenge).derRepresentation
        try await second.send(.proof(secondSignature))
        let accepted = try await second.receive()
        XCTAssertEqual(accepted, .accepted(deviceID: deviceID))
        await fulfillment(of: [authenticated], timeout: 10)
        let removed = try await server.removeDevice(id: deviceID)
        XCTAssertTrue(removed)
        second.connection.cancel()
        let third = ServerTestPeer(host: address, port: port, fingerprint: fingerprint)
        try await third.start()
        try await third.send(.authenticate(deviceID: deviceID))
        let rejected = try await third.receive()
        XCTAssertEqual(rejected, .rejected)
        third.connection.cancel()
        let fourth = ServerTestPeer(host: address, port: port, fingerprint: fingerprint)
        try await fourth.start()
        try await fourth.sendRaw(Data([0, 0, 0, 0]))
        do {
            _ = try await fourth.receive()
            XCTFail("Invalid frame must close the connection")
        } catch { }
        fourth.connection.cancel()
        await fulfillment(of: [disconnected], timeout: 10)
        XCTAssertEqual(try registry.all().count, 0)
        await server.stop()
    }
}

private extension UInt16 {
    func asPort() -> NWEndpoint.Port { NWEndpoint.Port(rawValue: self)! }
}
