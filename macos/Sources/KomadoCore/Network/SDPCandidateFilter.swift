import Foundation
import Network

/// Narrows locally generated SDP to host candidates for one local address.
/// This limits what is advertised; it does not bind WebRTC's network sockets.
public enum SDPCandidateFilter {
    public enum FilterError: Error { case invalidSDP, invalidAddress, noCandidate }

    /// A selected local candidate must still use the signaling address. This
    /// check is meaningful only after WebRTC reports the selected pair.
    public static func permitsSelectedLocalCandidate(_ candidate: String, on address: NWEndpoint.Host) -> Bool {
        guard candidate.hasPrefix("candidate:"), !candidate.contains("\r"), !candidate.contains("\n") else { return false }
        let parts = candidate.split(separator: " ", omittingEmptySubsequences: false)
        guard parts.count >= 8, !parts.contains("") else { return false }
        if parts[7] == "host" {
            return (try? restrict("v=0\r\na=\(candidate)\r\n", to: address)) != nil
        }
        // A local peer-reflexive address can be a NAT mapping. Only its
        // related/base address can be compared with the signaling socket.
        guard parts[7] == "prflx", (parts.count - 8).isMultiple(of: 2),
              numericAddress(String(parts[4])) != nil,
              let port = UInt16(parts[5]), port > 0 else { return false }
        let extensions = stride(from: 8, to: parts.count, by: 2).map { (parts[$0], parts[$0 + 1]) }
        let related = extensions.filter { $0.0 == "raddr" }.map(\.1)
        let relatedPorts = extensions.filter { $0.0 == "rport" }.map(\.1)
        guard related.count == 1, relatedPorts.count == 1,
              let relatedPort = UInt16(relatedPorts[0]), relatedPort > 0 else { return false }
        var base = Array(parts.prefix(8))
        base[4] = related[0]
        base[5] = relatedPorts[0]
        base[7] = "host"
        return (try? restrict("v=0\r\na=\(base.joined(separator: " "))\r\n", to: address)) != nil
    }

    public static func restrict(_ sdp: String, to address: NWEndpoint.Host) throws -> String {
        let target: Data
        let connectionLine: String
        switch address {
        case let .ipv4(ip):
            target = ip.rawValue
            connectionLine = "c=IN IP4 \(ip)"
        case let .ipv6(ip):
            target = ip.rawValue
            connectionLine = "c=IN IP6 \(String(describing: ip).split(separator: "%").first!)"
        default: throw FilterError.invalidAddress
        }
        guard sdp.utf8.count <= 12_288, sdp.hasPrefix("v=0\r\n"), sdp.hasSuffix("\r\n"),
              !sdp.unicodeScalars.contains(where: { $0.value < 32 && ![9, 10, 13].contains($0.value) || $0.value == 127 }) else {
            throw FilterError.invalidSDP
        }
        let lines = sdp.components(separatedBy: "\r\n")
        guard !lines.contains(where: { $0.contains("\r") || $0.contains("\n") }) else { throw FilterError.invalidSDP }
        var kept = 0
        var result: [String] = []
        for line in lines {
            if line.hasPrefix("a=remote-candidates:") { throw FilterError.invalidSDP }
            if line.hasPrefix("c=") {
                guard line.hasPrefix("c=IN IP4 ") || line.hasPrefix("c=IN IP6 ") else { throw FilterError.invalidSDP }
                result.append(connectionLine)
            } else if line.hasPrefix("a=candidate:") {
                let parts = line.split(separator: " ", omittingEmptySubsequences: false)
                guard parts.count >= 8, !parts.contains(""), parts[0].count > "a=candidate:".count,
                      parts[1] == "1" || parts[1] == "2",
                      parts[2].lowercased() == "udp" || parts[2].lowercased() == "tcp",
                      let priority = UInt32(parts[3]), priority > 0,
                      let port = UInt16(parts[5]), port > 0, parts[6] == "typ",
                      (parts.count - 8).isMultiple(of: 2) else { throw FilterError.invalidSDP }
                guard parts[7] == "host" else { continue }
                let extensions = stride(from: 8, to: parts.count, by: 2).map { parts[$0] }
                guard !extensions.contains("raddr"), !extensions.contains("rport") else { throw FilterError.invalidSDP }
                let raw = numericAddress(String(parts[4]))
                if raw == target {
                    result.append(line)
                    kept += 1
                }
            } else {
                result.append(line)
            }
        }
        guard kept > 0 else { throw FilterError.noCandidate }
        let filtered = result.joined(separator: "\r\n")
        guard filtered.utf8.count <= 12_288 else { throw FilterError.invalidSDP }
        return filtered
    }

    private static func numericAddress(_ value: String) -> Data? {
        if value.contains(":") {
            guard !value.contains("."), !value.contains("%") else { return nil }
            return IPv6Address(value)?.rawValue
        }
        return IPv4Address(value)?.rawValue
    }
}
