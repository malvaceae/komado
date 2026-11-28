import CryptoKit
import Foundation
import Network
import Security
import XCTest
@testable import KomadoCore

private final class ServerTestBox: @unchecked Sendable {
    private let lock = NSLock()
    private var interface: NWInterface?
    private var pathObserved = false
    private var port: UInt16?
    private var endpoints: [SignalingServer.Endpoint] = []
    private var authenticationCount = 0
    private var lastAuthenticated: UUID?
    private var answer: String?
    private var mediaSession: UUID?
    private var mediaCount = 0
    private var requestedDisplay: UInt32?
    private var requestedControl = false
    private var requestedResume = false
    func setInterface(_ value: NWInterface?) -> Bool {
        lock.withLock {
            guard !pathObserved else { return false }
            pathObserved = true
            interface = value
            return true
        }
    }
    func getInterface() -> NWInterface? { lock.withLock { interface } }
    func setPort(_ value: UInt16) { lock.withLock { port = value } }
    func getPort() -> UInt16? { lock.withLock { port } }
    func setEndpoints(_ value: [SignalingServer.Endpoint]) { lock.withLock { endpoints = value } }
    func getEndpoints() -> [SignalingServer.Endpoint] { lock.withLock { endpoints } }
    func nextAuthentication() -> Int { lock.withLock { authenticationCount += 1; return authenticationCount } }
    func setLastAuthenticated(_ value: UUID) { lock.withLock { lastAuthenticated = value } }
    func getLastAuthenticated() -> UUID? { lock.withLock { lastAuthenticated } }
    func setAnswer(_ value: String) { lock.withLock { answer = value } }
    func getAnswer() -> String? { lock.withLock { answer } }
    func setMediaSession(_ value: UUID) { lock.withLock { mediaSession = value } }
    func getMediaSession() -> UUID? { lock.withLock { mediaSession } }
    func noteMedia(_ display: UInt32?, control: Bool) -> Int { lock.withLock { mediaCount += 1; requestedDisplay = display; requestedControl = control; return mediaCount } }
    func getRequestedDisplay() -> UInt32? { lock.withLock { requestedDisplay } }
    func getRequestedControl() -> Bool { lock.withLock { requestedControl } }
    func setRequestedResume(_ value: Bool) { lock.withLock { requestedResume = value } }
    func getRequestedResume() -> Bool { lock.withLock { requestedResume } }
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
    func testRemovingOneListenerKeepsOtherAvailable() async throws {
        let box = ServerTestBox(), pathReady = expectation(description: "network path")
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { path in
            if box.setInterface(path.availableInterfaces.first { $0.type == .wifi || $0.type == .wiredEthernet }) {
                pathReady.fulfill()
            }
        }
        monitor.start(queue: DispatchQueue.global())
        defer { monitor.cancel() }
        await fulfillment(of: [pathReady], timeout: 10)
        let interface = try XCTUnwrap(box.getInterface())
        let addresses = try ListenerAccess.addresses(on: interface)
        guard let ipv4 = addresses.first(where: { if case .ipv4 = $0 { return true }; return false }),
              let ipv6 = addresses.first(where: { if case .ipv6 = $0 { return true }; return false }) else {
            throw XCTSkip("Requires IPv4 and IPv6")
        }
        let service = "me.mallows.komado.test.listener.\(UUID().uuidString)"
        defer {
            SecItemDelete([kSecClass: kSecClassKey, kSecAttrKeyClass: kSecAttrKeyClassPrivate,
                           kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
                           kSecAttrApplicationTag: Data("\(service).private-key".utf8)] as CFDictionary)
            SecItemDelete([kSecClass: kSecClassGenericPassword, kSecAttrService: service,
                           kSecAttrAccount: "certificate-v1"] as CFDictionary)
        }
        let identity = try HostTLSIdentityStore(service: service).create()
        let registry = PairedDeviceRegistry(service: "\(service).devices")
        let first = ListenerBinding(scope: .localLink, interface: interface, address: ipv4)
        let second = ListenerBinding(scope: .localLink, interface: interface, address: ipv6)
        let server = try SignalingServer.create(identity: identity.identity,
            bindings: [first, second], port: .any,
            pairing: PairingCoordinator(registry: registry, now: { .zero }),
            authentication: DeviceAuthenticationCoordinator(registry: registry, now: { .zero })) { event in
                if case let .listenersChanged(_, endpoints, _) = event { box.setEndpoints(endpoints) }
            }
        try await server.start()
        try await waitForEndpoints(box, count: 2)
        let firstPort = try XCTUnwrap(box.getEndpoints().first { $0.address == "\(ipv4)" }?.port)
        let peer = ServerTestPeer(host: ipv4, port: try firstPort.asPort(), fingerprint: identity.fingerprintSHA256)
        try await peer.start()
        await server.updateListeners([first])
        try await waitForEndpoints(box, count: 1)
        try await peer.send(.startMedia)
        let response = try await peer.receive()
        XCTAssertEqual(response, .rejected)
        peer.connection.cancel()
        await server.updateListeners([])
        try await waitForEndpoints(box, count: 0)
        await server.updateListeners([first, second])
        try await waitForEndpoints(box, count: 2)
        let restoredPort = try XCTUnwrap(box.getEndpoints().first { $0.address == "\(ipv4)" }?.port)
        let restored = ServerTestPeer(host: ipv4, port: try restoredPort.asPort(), fingerprint: identity.fingerprintSHA256)
        try await restored.start()
        try await restored.send(.startMedia)
        let restoredResponse = try await restored.receive()
        XCTAssertEqual(restoredResponse, .rejected)
        restored.connection.cancel()
        await server.stop()
    }

    private func waitForEndpoints(_ box: ServerTestBox, count: Int) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(10))
        while box.getEndpoints().count != count && ContinuousClock.now < deadline {
            try await Task.sleep(for: .milliseconds(50))
        }
        XCTAssertEqual(box.getEndpoints().count, count)
    }

    func testLocalServerPairsAndAuthenticatesAgain() async throws {
        let box = ServerTestBox(), pathReady = expectation(description: "network path")
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { path in
            if box.setInterface(path.availableInterfaces.first { $0.type == .wifi || $0.type == .wiredEthernet }) {
                pathReady.fulfill()
            }
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
        let mediaStopRequested = expectation(description: "media stop requested")
        let switchMediaRequested = expectation(description: "next media requested")
        let selectedMediaRequested = expectation(description: "selected display requested")
        let controlRequested = expectation(description: "control requested")
        let answerReceived = expectation(description: "SDP answer")
        let disconnected = expectation(description: "ten disconnections")
        disconnected.expectedFulfillmentCount = 10
        let server = try SignalingServer.create(identity: identity.identity,
            bindings: [ListenerBinding(scope: .localLink, interface: interface, address: address)],
            port: .any, pairing: pairing, authentication: auth) { event in
                switch event {
                case let .listenersChanged(_, endpoints, _):
                    if let port = endpoints.first?.port, box.getPort() == nil { box.setPort(port); listening.fulfill() }
                case let .authenticated(id, _):
                    box.setLastAuthenticated(id)
                    let count = box.nextAuthentication()
                    if count == 1 { firstAuthenticated.fulfill() }
                    if count <= 2 { authenticated.fulfill() }
                case let .mediaRequested(id, _, target, resuming):
                    box.setMediaSession(id)
                    box.setRequestedResume(resuming)
                    let display: UInt32?
                    switch target {
                    case let .display(id), let .displayControl(id): display = id
                    default: display = nil
                    }
                    if target == .mainDisplayControl {
                        controlRequested.fulfill()
                    } else {
                        switch box.noteMedia(display, control: target.isControl) {
                        case 1: mediaRequested.fulfill()
                        case 2: switchMediaRequested.fulfill()
                        default: selectedMediaRequested.fulfill()
                        }
                    }
                case .mediaStopRequested: mediaStopRequested.fulfill()
                case let .answer(_, _, sdp): box.setAnswer(sdp); answerReceived.fulfill()
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
        var applicationOffset: UInt16 = 0
        var applicationIDs = Set<UInt32>()
        repeat {
            try await first.send(.listApplications(applicationOffset))
            guard case let .applications(next, values) = try await first.receive() else { return XCTFail("Expected application page") }
            for application in values { XCTAssertTrue(applicationIDs.insert(application.id).inserted) }
            if next != 0 {
                XCTAssertEqual(values.count, 32)
                XCTAssertEqual(Int(next), applicationIDs.count)
            }
            applicationOffset = next
        } while applicationOffset != 0
        if let applicationID = applicationIDs.first {
            try await first.send(.applicationIcon(applicationID))
            guard case let .applicationIconResult(id, icon) = try await first.receive() else { return XCTFail("Expected application icon") }
            XCTAssertEqual(id, applicationID)
            XCTAssertLessThanOrEqual(icon.count, 8192)
        }
        applicationOffset = 0
        applicationIDs.removeAll()
        repeat {
            try await first.send(.listApplications(applicationOffset))
            guard case let .applications(next, values) = try await first.receive() else { return XCTFail("Expected refreshed application page") }
            for application in values { XCTAssertTrue(applicationIDs.insert(application.id).inserted) }
            if next != 0 { XCTAssertEqual(Int(next), applicationIDs.count) }
            applicationOffset = next
        } while applicationOffset != 0
        try await first.send(.listDisplays)
        guard case let .displays(displays) = try await first.receive(), let display = displays.first else {
            return XCTFail("Expected a nonempty display list")
        }
        var offset: UInt16 = 0
        var windowIDs = Set<UInt32>()
        repeat {
            try await first.send(.listWindows(offset))
            guard case let .windows(next, values) = try await first.receive() else { return XCTFail("Expected window page") }
            for window in values { XCTAssertTrue(windowIDs.insert(window.id).inserted) }
            if next != 0 {
                XCTAssertEqual(values.count, 32)
                XCTAssertEqual(Int(next), windowIDs.count)
            }
            offset = next
        } while offset != 0
        try await first.send(.listWindows(0))
        guard case .windows = try await first.receive() else { return XCTFail("Expected a refreshed window list") }
        let localCandidate = "a=candidate:1 1 udp 1 \(address) 54321 typ host\r\n"
        let otherCandidate = "a=candidate:2 1 udp 1 192.0.2.5 54322 typ host\r\n"
        let offer = "v=0\r\ns=offer\r\nc=IN IP4 192.0.2.5\r\n" + localCandidate + otherCandidate
        let answer = "v=0\r\ns=answer\r\n"
        do {
            try await server.sendOffer(offer)
            XCTFail("Offer must wait for an explicit media request")
        } catch { }
        try await first.send(.startMedia)
        await fulfillment(of: [mediaRequested], timeout: 10)
        let staleConnection = UUID()
        let currentConnection = try XCTUnwrap(box.getMediaSession())
        let currentIsActive = await server.isActive(sessionID: currentConnection)
        let staleIsActive = await server.isActive(sessionID: staleConnection)
        XCTAssertTrue(currentIsActive)
        XCTAssertFalse(staleIsActive)
        await server.disconnect(sessionID: staleConnection)
        do {
            try await server.sendOffer(offer, sessionID: staleConnection)
            XCTFail("An old connection must not affect the active session")
        } catch { }
        do {
            try await server.sendOffer("invalid SDP")
            XCTFail("Invalid SDP must be rejected")
        } catch { }
        try await server.sendOffer(offer, sessionID: currentConnection)
        let receivedOffer = try await first.receive()
        XCTAssertEqual(receivedOffer, .offer(offer))
        try await first.send(.answer(answer))
        await fulfillment(of: [answerReceived], timeout: 10)
        XCTAssertEqual(box.getAnswer(), answer)
        do {
            try await server.sendOffer(offer)
            XCTFail("A second offer must not be accepted")
        } catch { }
        try await first.send(.stopMedia)
        await fulfillment(of: [mediaStopRequested], timeout: 10)
        try await server.sendMediaStopped(sessionID: currentConnection)
        let stoppedResponse = try await first.receive()
        XCTAssertEqual(stoppedResponse, .mediaStopped)
        try await first.send(.listApplications(0))
        guard case .applications = try await first.receive() else { return XCTFail("Expected a fresh application list") }
        try await first.send(.listDisplays)
        guard case .displays = try await first.receive() else { return XCTFail("Expected a fresh display list") }
        try await first.send(.listWindows(0))
        guard case .windows = try await first.receive() else { return XCTFail("Expected a fresh window list") }
        try await first.send(.startMedia)
        await fulfillment(of: [switchMediaRequested], timeout: 10)
        try await server.sendOffer(offer, sessionID: currentConnection)
        let nextOffer = try await first.receive()
        XCTAssertEqual(nextOffer, .offer(offer))
        first.connection.cancel()
        // The server permits the next connection after it observes this close.
        await server.disconnect(sessionID: currentConnection)
        let second = ServerTestPeer(host: address, port: port, fingerprint: fingerprint)
        try await second.start()
        try await second.send(.authenticate(deviceID: deviceID))
        guard case let .challenge(secondChallenge) = try await second.receive() else { return XCTFail("Expected challenge") }
        let secondSignature = try key.signature(for: Data("komado.device-auth.v1\0".utf8) + secondChallenge).derRepresentation
        try await second.send(.proof(secondSignature))
        let accepted = try await second.receive()
        XCTAssertEqual(accepted, .accepted(deviceID: deviceID))
        await fulfillment(of: [authenticated], timeout: 10)
        await server.setResumeDeviceID(deviceID)
        try await second.send(.resumeCheck)
        let allowed = try await second.receive()
        XCTAssertEqual(allowed, .resumeAllowed)
        try await second.send(.listDisplays)
        guard case let .displays(secondDisplays) = try await second.receive(),
              secondDisplays.contains(where: { $0.id == display.id }) else { return XCTFail("Expected display") }
        try await second.send(.startDisplayControl(display.id))
        await fulfillment(of: [selectedMediaRequested], timeout: 10)
        XCTAssertEqual(box.getRequestedDisplay(), display.id)
        XCTAssertTrue(box.getRequestedControl())
        XCTAssertTrue(box.getRequestedResume())
        await server.setResumeDeviceID(nil)
        let secondID = try XCTUnwrap(box.getLastAuthenticated())
        second.connection.cancel()
        await server.disconnect(sessionID: secondID)
        let deniedPeer = ServerTestPeer(host: address, port: port, fingerprint: fingerprint)
        try await deniedPeer.start()
        try await deniedPeer.send(.authenticate(deviceID: deviceID))
        guard case let .challenge(deniedChallenge) = try await deniedPeer.receive() else { return XCTFail("Expected challenge") }
        let deniedSignature = try key.signature(for: Data("komado.device-auth.v1\0".utf8) + deniedChallenge).derRepresentation
        try await deniedPeer.send(.proof(deniedSignature))
        let deniedAccepted = try await deniedPeer.receive()
        XCTAssertEqual(deniedAccepted, .accepted(deviceID: deviceID))
        await server.setResumeDeviceID(deviceID)
        try await deniedPeer.send(.resumeCheck)
        let allowedBeforeRevocation = try await deniedPeer.receive()
        XCTAssertEqual(allowedBeforeRevocation, .resumeAllowed)
        await server.setResumeDeviceID(nil)
        try await deniedPeer.send(.startControl)
        do {
            _ = try await deniedPeer.receive()
            XCTFail("A revoked resume must not start media")
        } catch { }
        deniedPeer.connection.cancel()
        let deniedID = try XCTUnwrap(box.getLastAuthenticated())
        await server.disconnect(sessionID: deniedID)
        let unlisted = ServerTestPeer(host: address, port: port, fingerprint: fingerprint)
        try await unlisted.start()
        try await unlisted.send(.authenticate(deviceID: deviceID))
        guard case let .challenge(thirdChallenge) = try await unlisted.receive() else { return XCTFail("Expected challenge") }
        let thirdSignature = try key.signature(for: Data("komado.device-auth.v1\0".utf8) + thirdChallenge).derRepresentation
        try await unlisted.send(.proof(thirdSignature))
        let unlistedAccepted = try await unlisted.receive()
        XCTAssertEqual(unlistedAccepted, .accepted(deviceID: deviceID))
        let thirdDeadline = Date().addingTimeInterval(5)
        while box.getLastAuthenticated() == deniedID && Date() < thirdDeadline {
            try await Task.sleep(for: .milliseconds(20))
        }
        let unlistedID = try XCTUnwrap(box.getLastAuthenticated())
        XCTAssertNotEqual(unlistedID, deniedID)
        try await unlisted.send(.startDisplay(display.id))
        let closeDeadline = Date().addingTimeInterval(5)
        while await server.isActive(sessionID: unlistedID) && Date() < closeDeadline {
            try await Task.sleep(for: .milliseconds(20))
        }
        let stillActive = await server.isActive(sessionID: unlistedID)
        XCTAssertFalse(stillActive, "A display cannot be selected before listing")
        unlisted.connection.cancel()
        let unknown = ServerTestPeer(host: address, port: port, fingerprint: fingerprint)
        try await unknown.start()
        try await unknown.send(.authenticate(deviceID: deviceID))
        guard case let .challenge(fourthChallenge) = try await unknown.receive() else { return XCTFail("Expected challenge") }
        let fourthSignature = try key.signature(for: Data("komado.device-auth.v1\0".utf8) + fourthChallenge).derRepresentation
        try await unknown.send(.proof(fourthSignature))
        let unknownAccepted = try await unknown.receive()
        XCTAssertEqual(unknownAccepted, .accepted(deviceID: deviceID))
        let fourthDeadline = Date().addingTimeInterval(5)
        while box.getLastAuthenticated() == unlistedID && Date() < fourthDeadline {
            try await Task.sleep(for: .milliseconds(20))
        }
        let unknownID = try XCTUnwrap(box.getLastAuthenticated())
        XCTAssertNotEqual(unknownID, unlistedID)
        try await unknown.send(.listDisplays)
        guard case let .displays(currentDisplays) = try await unknown.receive() else { return XCTFail("Expected display list") }
        let absent = try XCTUnwrap((UInt32(1)...65).first { candidate in !currentDisplays.contains { $0.id == candidate } })
        try await unknown.send(.startDisplayControl(absent))
        let unknownCloseDeadline = Date().addingTimeInterval(5)
        while await server.isActive(sessionID: unknownID) && Date() < unknownCloseDeadline {
            try await Task.sleep(for: .milliseconds(20))
        }
        let unknownStillActive = await server.isActive(sessionID: unknownID)
        XCTAssertFalse(unknownStillActive, "A display not present in the list must be rejected")
        unknown.connection.cancel()
        let unknownWindow = ServerTestPeer(host: address, port: port, fingerprint: fingerprint)
        try await unknownWindow.start()
        try await unknownWindow.send(.authenticate(deviceID: deviceID))
        guard case let .challenge(windowChallenge) = try await unknownWindow.receive() else { return XCTFail("Expected challenge") }
        let windowSignature = try key.signature(for: Data("komado.device-auth.v1\0".utf8) + windowChallenge).derRepresentation
        try await unknownWindow.send(.proof(windowSignature))
        let windowAccepted = try await unknownWindow.receive()
        XCTAssertEqual(windowAccepted, .accepted(deviceID: deviceID))
        let windowAuthDeadline = Date().addingTimeInterval(5)
        while box.getLastAuthenticated() == unknownID && Date() < windowAuthDeadline {
            try await Task.sleep(for: .milliseconds(20))
        }
        let unknownWindowID = try XCTUnwrap(box.getLastAuthenticated())
        XCTAssertNotEqual(unknownWindowID, unknownID)
        try await unknownWindow.send(.listWindows(0))
        guard case let .windows(_, firstPage) = try await unknownWindow.receive() else { return XCTFail("Expected window page") }
        let absentWindow = try XCTUnwrap((UInt32(1)...33).first { candidate in !firstPage.contains { $0.id == candidate } })
        try await unknownWindow.send(.windowAction(absentWindow, .close))
        let windowCloseDeadline = Date().addingTimeInterval(5)
        while await server.isActive(sessionID: unknownWindowID) && Date() < windowCloseDeadline {
            try await Task.sleep(for: .milliseconds(20))
        }
        let unknownWindowStillActive = await server.isActive(sessionID: unknownWindowID)
        XCTAssertFalse(unknownWindowStillActive, "An action for a window not delivered to the peer must be rejected")
        unknownWindow.connection.cancel()
        await server.disconnect(sessionID: unknownWindowID)
        let control = ServerTestPeer(host: address, port: port, fingerprint: fingerprint)
        try await control.start()
        try await control.send(.authenticate(deviceID: deviceID))
        guard case let .challenge(controlChallenge) = try await control.receive() else { return XCTFail("Expected challenge") }
        let controlSignature = try key.signature(for: Data("komado.device-auth.v1\0".utf8) + controlChallenge).derRepresentation
        try await control.send(.proof(controlSignature))
        let controlAccepted = try await control.receive()
        XCTAssertEqual(controlAccepted, .accepted(deviceID: deviceID))
        try await control.send(.startControl)
        await fulfillment(of: [controlRequested], timeout: 10)
        control.connection.cancel()
        await server.disconnect(sessionID: try XCTUnwrap(box.getMediaSession()))
        let removed = try await server.removeDevice(id: deviceID)
        XCTAssertTrue(removed)
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
        let unauthenticated = ServerTestPeer(host: address, port: port, fingerprint: fingerprint)
        try await unauthenticated.start()
        try await unauthenticated.send(.startControl)
        let unauthenticatedResponse = try await unauthenticated.receive()
        XCTAssertEqual(unauthenticatedResponse, .rejected)
        unauthenticated.connection.cancel()
        await fulfillment(of: [disconnected], timeout: 10)
        XCTAssertEqual(try registry.all().count, 0)
        await server.stop()
    }
}

private extension UInt16 {
    func asPort() -> NWEndpoint.Port { NWEndpoint.Port(rawValue: self)! }
}
