import Foundation
import Network
import XCTest
@testable import KomadoCore

final class SDPCandidateFilterTests: XCTestCase {
    func testSharedCandidates() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let file = try String(contentsOf: root.appendingPathComponent("protocol/fixtures/sdp-candidates.txt"), encoding: .utf8)
        let rows = file.split(separator: "\n").filter { !$0.hasPrefix("#") }
        XCTAssertFalse(rows.isEmpty)
        for row in rows {
            let fields = row.split(separator: " ")
            let address = NWEndpoint.Host.ipv4(IPv4Address(String(fields[1]))!)
            let sdp = String(data: Data(base64Encoded: String(fields[2]))!, encoding: .utf8)!
            if fields[3] == "ERROR" {
                XCTAssertThrowsError(try SDPCandidateFilter.restrict(sdp, to: address), String(fields[0]))
            } else {
                let expected = String(data: Data(base64Encoded: String(fields[3]))!, encoding: .utf8)!
                XCTAssertEqual(try SDPCandidateFilter.restrict(sdp, to: address), expected, String(fields[0]))
            }
        }
    }

    func testIPv6AddressIsMatchedInBinaryForm() throws {
        let sdp = "v=0\r\nc=IN IP6 2001:db8::2\r\na=candidate:1 1 udp 1 2001:db8:0:0:0:0:0:1 4000 typ host\r\n"
        let output = try SDPCandidateFilter.restrict(sdp, to: .ipv6(IPv6Address("2001:db8::1")!))
        XCTAssertTrue(output.contains("a=candidate:1"))
        XCTAssertTrue(output.contains("c=IN IP6 2001:db8::1\r\n"))
    }

    func testSelectedLocalCandidateMustMatchAddress() {
        let address = NWEndpoint.Host.ipv4(IPv4Address("192.168.1.5")!)
        let candidate = "candidate:1 1 udp 1 192.168.1.5 54321 typ host generation 0"
        XCTAssertTrue(SDPCandidateFilter.permitsSelectedLocalCandidate(candidate, on: address))
        XCTAssertFalse(SDPCandidateFilter.permitsSelectedLocalCandidate(candidate.replacingOccurrences(of: "192.168.1.5", with: "10.0.0.1"), on: address))
        XCTAssertFalse(SDPCandidateFilter.permitsSelectedLocalCandidate(candidate + "\r\na=candidate:2", on: address))
        let reflexive = "candidate:3 1 udp 1 203.0.113.1 1234 typ prflx raddr 192.168.1.5 rport 54321"
        XCTAssertTrue(SDPCandidateFilter.permitsSelectedLocalCandidate(reflexive, on: address))
        XCTAssertFalse(SDPCandidateFilter.permitsSelectedLocalCandidate(reflexive.replacingOccurrences(of: "192.168.1.5", with: "10.0.0.1"), on: address))
        XCTAssertFalse(SDPCandidateFilter.permitsSelectedLocalCandidate(reflexive + " raddr 192.168.1.5", on: address))
        XCTAssertFalse(SDPCandidateFilter.permitsSelectedLocalCandidate(reflexive.replacingOccurrences(of: "rport 54321", with: "rport 0"), on: address))
    }
}
