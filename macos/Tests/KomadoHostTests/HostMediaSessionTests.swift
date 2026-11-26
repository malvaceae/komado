import Network
import XCTest
@testable import KomadoHost

final class HostMediaSessionTests: XCTestCase {
    @MainActor
    func testOfferAndSessionLifecycle() async throws {
        let route = MediaRouteGate(address: .ipv4(IPv4Address("127.0.0.1")!), onInvalidRoute: {})
        let session = try HostMediaSession(route: route, onFailure: {})
        let offer = try await session.makeOffer()
        XCTAssertTrue(offer.hasPrefix("v=0\r\n"))
        XCTAssertTrue(offer.contains("m=video "))
        XCTAssertTrue(offer.contains("m=application "))
        do {
            _ = try await session.makeOffer()
            XCTFail("A second offer must be rejected")
        } catch { }
        session.close()
        session.close()
        do {
            try await session.applyAnswer("v=0\r\n")
            XCTFail("A closed session must reject an answer")
        } catch { }
    }
}
