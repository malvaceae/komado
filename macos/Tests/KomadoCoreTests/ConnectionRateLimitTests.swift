import XCTest
@testable import KomadoCore

final class ConnectionRateLimitTests: XCTestCase {
    func testLimitsAttemptsAndRecoversAfterWindow() {
        var limit = ConnectionRateLimit()
        for _ in 0..<10 { XCTAssertNil(limit.admit(at: .zero)) }
        XCTAssertEqual(limit.admit(at: .seconds(1)), .seconds(59))
        XCTAssertEqual(limit.admit(at: .seconds(59)), .seconds(1))
        XCTAssertNil(limit.admit(at: .seconds(60)))
    }
}
