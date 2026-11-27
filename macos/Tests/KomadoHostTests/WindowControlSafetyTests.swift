import CoreGraphics
import XCTest
@testable import KomadoHost

final class WindowControlSafetyTests: XCTestCase {
    func testPointerRequiresUnchangedVisibleTopmostTarget() {
        let target = ControlWindowSnapshot(id: 1, processID: 10, bounds: CGRect(x: 100, y: 100, width: 200, height: 200))
        let other = ControlWindowSnapshot(id: 2, processID: 20, bounds: CGRect(x: 150, y: 150, width: 50, height: 50))
        let guardrail = WindowControlSafety(target: target)
        XCTAssertTrue(guardrail.permits(point: CGPoint(x: 120, y: 120), hitProcessID: 10, hitBounds: target.bounds, windows: [other, target]))
        XCTAssertFalse(guardrail.permits(point: CGPoint(x: 160, y: 160), hitProcessID: 20, hitBounds: other.bounds, windows: [other, target]))
        XCTAssertFalse(guardrail.permits(point: CGPoint(x: 301, y: 120), hitProcessID: 10, hitBounds: target.bounds, windows: [target]))
        XCTAssertFalse(guardrail.permits(point: CGPoint(x: 120, y: 120), hitProcessID: 10, hitBounds: target.bounds, windows: []))
        XCTAssertFalse(guardrail.permits(point: CGPoint(x: 120, y: 120), hitProcessID: 10, hitBounds: target.bounds, windows: [.init(id: 1, processID: 11, bounds: target.bounds)]))
        XCTAssertFalse(guardrail.permits(point: CGPoint(x: 120, y: 120), hitProcessID: 10, hitBounds: target.bounds, windows: [.init(id: 1, processID: 10, bounds: target.bounds.offsetBy(dx: 1, dy: 0))]))
        XCTAssertFalse(guardrail.permits(point: CGPoint(x: CGFloat.nan, y: 120), hitProcessID: 10, hitBounds: target.bounds, windows: [target]))
        XCTAssertFalse(guardrail.permits(point: CGPoint(x: 120, y: 120), hitProcessID: 10, hitBounds: target.bounds,
                                        windows: [target, .init(id: 3, processID: 10, bounds: target.bounds)]))
    }

    func testKeyboardRequiresUnambiguousFocusedWindow() {
        let target = ControlWindowSnapshot(id: 1, processID: 10, bounds: CGRect(x: 100, y: 100, width: 200, height: 200))
        let guardrail = WindowControlSafety(target: target)
        XCTAssertTrue(guardrail.permitsKeyboard(focusedProcessID: 10, focusedBounds: target.bounds, windows: [target]))
        XCTAssertFalse(guardrail.permitsKeyboard(focusedProcessID: 11, focusedBounds: target.bounds, windows: [target]))
        XCTAssertFalse(guardrail.permitsKeyboard(focusedProcessID: 10, focusedBounds: target.bounds.offsetBy(dx: 1, dy: 0), windows: [target]))
        XCTAssertFalse(guardrail.permitsKeyboard(focusedProcessID: 10, focusedBounds: target.bounds, windows: []))
        let duplicate = ControlWindowSnapshot(id: 2, processID: 10, bounds: target.bounds)
        XCTAssertFalse(guardrail.permitsKeyboard(focusedProcessID: 10, focusedBounds: target.bounds, windows: [target, duplicate]))
    }
}
