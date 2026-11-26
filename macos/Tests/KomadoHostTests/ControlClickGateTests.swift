import Foundation
import KomadoCore
import XCTest
@testable import KomadoHost

final class ControlClickGateTests: XCTestCase {
    func testOnlyAcceptsCurrentCaptureAndIncreasingSequence() throws {
        let gate = ControlClickGate()
        let first = PointerClick(sequence: 1, captureRevision: 4, x: 500_000, y: 500_000, button: .left)
        let bytes = try first.encode()
        XCTAssertNil(gate.accept(bytes, binary: false))
        XCTAssertTrue(gate.enable(revision: 4))
        XCTAssertFalse(gate.enable(revision: 5))
        XCTAssertNil(gate.accept(bytes, binary: true))
        let stale = PointerClick(sequence: 1, captureRevision: 3, x: 500_000, y: 500_000, button: .left)
        XCTAssertNil(gate.accept(try stale.encode(), binary: false))
        XCTAssertEqual(gate.accept(bytes, binary: false), first)
        XCTAssertNil(gate.accept(bytes, binary: false))
        let later = PointerClick(sequence: 3, captureRevision: 4, x: 1, y: 2, button: .right)
        XCTAssertEqual(gate.accept(try later.encode(), binary: false), later)
        gate.close()
        let afterClose = PointerClick(sequence: 4, captureRevision: 4, x: 1, y: 2, button: .left)
        XCTAssertNil(gate.accept(try afterClose.encode(), binary: false))
        XCTAssertFalse(gate.enable(revision: 4))
    }
}
