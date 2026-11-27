import Foundation
import Network
import Security
import XCTest
@testable import KomadoCore

private final class InterfaceBox: @unchecked Sendable {
    private let lock = NSLock()
    private var value: NWInterface?
    func set(_ interface: NWInterface?) { lock.withLock { value = interface } }
    func get() -> NWInterface? { lock.withLock { value } }
}

final class ListenerAccessTests: XCTestCase {
    func testAddressEnumerationRequiresAnExistingInterface() throws {
        let addresses = try ListenerAccess.assignedAddresses(interfaceName: "lo0")
        XCTAssertTrue(addresses.contains(Data([127, 0, 0, 1])))
        XCTAssertThrowsError(try ListenerAccess.assignedAddresses(interfaceName: "komado-invalid-interface")) {
            XCTAssertEqual($0 as? ListenerAccess.AccessError, .interfaceUnavailable)
        }
    }

    func testListenerRequiresAssignedAddressAndRestrictsLocalLink() throws {
        let box = InterfaceBox(), ready = expectation(description: "network path")
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { path in
            box.set(path.availableInterfaces.first { $0.type == .wifi || $0.type == .wiredEthernet })
            ready.fulfill()
        }
        monitor.start(queue: DispatchQueue.global())
        defer { monitor.cancel() }
        wait(for: [ready], timeout: 10)
        guard let interface = box.get() else { throw XCTSkip("No Wi-Fi or Ethernet interface") }
        let addresses = try ListenerAccess.addresses(on: interface)
        guard let address = addresses.first(where: { if case .ipv4 = $0 { return true }; return false }) else {
            throw XCTSkip("No IPv4 address on local interface")
        }
        let service = "me.mallows.komado.test.scope.\(UUID().uuidString)"
        defer {
            SecItemDelete([kSecClass: kSecClassKey, kSecAttrKeyClass: kSecAttrKeyClassPrivate,
                           kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
                           kSecAttrApplicationTag: Data("\(service).private-key".utf8)] as CFDictionary)
            SecItemDelete([kSecClass: kSecClassGenericPassword, kSecAttrService: service,
                           kSecAttrAccount: "certificate-v1"] as CFDictionary)
        }
        let identity = try HostTLSIdentityStore(service: service).create().identity
        let parameters = try ListenerAccess.parameters(identity: identity, scope: .localLink, interface: interface,
                                                       address: address, port: .any)
        XCTAssertTrue(parameters.acceptLocalOnly)
        XCTAssertTrue(parameters.allowLocalEndpointReuse)
        XCTAssertEqual(parameters.requiredInterface?.index, interface.index)
        XCTAssertThrowsError(try ListenerAccess.parameters(identity: identity, scope: .localLink, interface: interface,
                                                            address: .ipv4(IPv4Address("0.0.0.0")!), port: .any)) {
            XCTAssertEqual($0 as? ListenerAccess.AccessError, .invalidAddress)
        }
        XCTAssertThrowsError(try ListenerAccess.parameters(identity: identity, scope: .vpnTunnel, interface: interface,
                                                            address: address, port: .any)) {
            XCTAssertEqual($0 as? ListenerAccess.AccessError, .invalidTunnel)
        }
    }
}
