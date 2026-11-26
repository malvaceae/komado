import Foundation
import KomadoCore
import XCTest
@testable import KomadoHost

final class ControlInputGateTests: XCTestCase {
    func testOnlyAcceptsCurrentCaptureAndIncreasingSequence() throws {
        let gate = ControlInputGate()
        let first = PointerClick(sequence: 1, captureRevision: 4, x: 500_000, y: 500_000, button: .left)
        let bytes = try first.encode()
        XCTAssertNil(gate.accept(bytes, binary: false))
        XCTAssertTrue(gate.enable(revision: 4))
        XCTAssertFalse(gate.enable(revision: 5))
        XCTAssertNil(gate.accept(bytes, binary: true))
        let stale = PointerClick(sequence: 1, captureRevision: 3, x: 500_000, y: 500_000, button: .left)
        XCTAssertNil(gate.accept(try stale.encode(), binary: false))
        XCTAssertEqual(gate.accept(bytes, binary: false), .click(first))
        XCTAssertNil(gate.accept(bytes, binary: false))
        let text = KeyboardText(sequence: 2, captureRevision: 4, text: "日本語")
        XCTAssertEqual(gate.accept(try text.encode(), binary: false), .text(text))
        XCTAssertNil(gate.accept(bytes, binary: false))
        let later = PointerClick(sequence: 3, captureRevision: 4, x: 1, y: 2, button: .right)
        XCTAssertEqual(gate.accept(try later.encode(), binary: false), .click(later))
        gate.close()
        let afterClose = PointerClick(sequence: 4, captureRevision: 4, x: 1, y: 2, button: .left)
        XCTAssertNil(gate.accept(try afterClose.encode(), binary: false))
        XCTAssertFalse(gate.enable(revision: 4))
    }
}
