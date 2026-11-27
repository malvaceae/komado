import CoreGraphics
import XCTest
@testable import KomadoHost

final class ConsoleSessionTests: XCTestCase {
    func testOnlyExplicitConsoleMembershipAllowsCapture() {
        let key = kCGSessionOnConsoleKey as String
        XCTAssertTrue(ConsoleSession.isOnConsole([key: true]))
        XCTAssertFalse(ConsoleSession.isOnConsole([key: false]))
        XCTAssertFalse(ConsoleSession.isOnConsole([key: "true"]))
        XCTAssertFalse(ConsoleSession.isOnConsole([:]))
        XCTAssertFalse(ConsoleSession.isOnConsole(nil))
    }
}
