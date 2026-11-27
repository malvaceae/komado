import Darwin
import Foundation
import Network
import Security

/// Configures an explicitly bound TLS listener. A local-link listener accepts
/// only local-link peers; a tunnel listener is restricted to the selected utun
/// interface. The owner of the tunnel is responsible for securing that tunnel.
/// These restrictions do not replace peer authentication or connection limits.
public enum ListenerAccess {
    public enum Scope { case localLink, vpnTunnel }
    public enum AccessError: Error, Equatable {
        case interfaceUnavailable
        case addressNotAssigned
        case invalidAddress
        case invalidTunnel
        case invalidIdentity
        case addressEnumerationFailed
    }

    /// Only addresses currently assigned to the selected, up interface are used.
    public static func addresses(on interface: NWInterface) throws -> [NWEndpoint.Host] {
        try assignedAddresses(interfaceName: interface.name).compactMap { bytes in
            if bytes.count == 4, let address = IPv4Address(bytes) { return .ipv4(address) }
            if bytes.count == 16, let address = IPv6Address(bytes, interface) { return .ipv6(address) }
            return nil
        }
    }

    public static func parameters(identity: SecIdentity, scope: Scope, interface: NWInterface,
                                  address: NWEndpoint.Host, port: NWEndpoint.Port) throws -> NWParameters {
        let raw: Data
        switch address {
        case let .ipv4(value): raw = value.rawValue
        case let .ipv6(value): raw = value.rawValue
        default: throw AccessError.invalidAddress
        }
        guard raw.contains(where: { $0 != 0 }) else { throw AccessError.invalidAddress }
        if case .vpnTunnel = scope {
            guard interface.type == .other && interface.name.hasPrefix("utun") else { throw AccessError.invalidTunnel }
        }
        guard try assignedAddresses(interfaceName: interface.name).contains(raw) else { throw AccessError.addressNotAssigned }
        guard let tlsIdentity = sec_identity_create(identity) else { throw AccessError.invalidIdentity }
        let tls = NWProtocolTLS.Options()
        sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
        sec_protocol_options_set_local_identity(tls.securityProtocolOptions, tlsIdentity)
        let parameters = NWParameters(tls: tls, tcp: .init())
        parameters.allowLocalEndpointReuse = true
        guard let ip = parameters.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options else { throw AccessError.invalidAddress }
        switch address {
        case .ipv4: ip.version = .v4
        case .ipv6: ip.version = .v6
        default: throw AccessError.invalidAddress
        }
        parameters.requiredInterface = interface
        parameters.requiredLocalEndpoint = .hostPort(host: address, port: port)
        if case .localLink = scope { parameters.acceptLocalOnly = true }
        return parameters
    }

    /// Extracts address bytes directly from getifaddrs; no DNS or shell command.
    static func assignedAddresses(interfaceName: String) throws -> [Data] {
        var first: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&first) == 0 else { throw AccessError.addressEnumerationFailed }
        defer { freeifaddrs(first) }
        var cursor = first
        var found = false
        var addresses: [Data] = []
        while let current = cursor {
            let entry = current.pointee
            cursor = entry.ifa_next
            guard String(cString: entry.ifa_name) == interfaceName else { continue }
            found = true
            guard entry.ifa_flags & UInt32(IFF_UP) != 0, let address = entry.ifa_addr else { continue }
            switch Int32(address.pointee.sa_family) {
            case AF_INET:
                let socketAddress = UnsafeRawPointer(address).assumingMemoryBound(to: sockaddr_in.self).pointee
                var ip = socketAddress.sin_addr
                addresses.append(Data(bytes: &ip, count: MemoryLayout<in_addr>.size))
            case AF_INET6:
                let socketAddress = UnsafeRawPointer(address).assumingMemoryBound(to: sockaddr_in6.self).pointee
                var ip = socketAddress.sin6_addr
                addresses.append(Data(bytes: &ip, count: MemoryLayout<in6_addr>.size))
            default: break
            }
        }
        guard found else { throw AccessError.interfaceUnavailable }
        return addresses
    }
}
