import CoreGraphics
import XCTest
@testable import KomadoHost

final class WindowControlSafetyTests: XCTestCase {
    func testApplicationControlRequiresAVisibleWindowOfTheSelectedProcess() {
        let bounds = CGRect(x: 100, y: 100, width: 200, height: 200)
        let target = ControlWindowSnapshot(id: 1, processID: 10, bounds: bounds)
        let other = ControlWindowSnapshot(id: 2, processID: 20, bounds: bounds)
        let safety = ApplicationControlSafety(processID: 10)
        let point = CGPoint(x: 150, y: 150)
        XCTAssertTrue(safety.permits(point: point, hitProcessID: 10, hitBounds: bounds, windows: [target]))
        XCTAssertFalse(safety.permits(point: point, hitProcessID: 20, hitBounds: bounds, windows: [target, other]))
        XCTAssertFalse(safety.permits(point: point, hitProcessID: 10, hitBounds: bounds, windows: []))
        XCTAssertFalse(safety.permits(point: point, hitProcessID: 10, hitBounds: bounds,
                                      windows: [target, .init(id: 3, processID: 10, bounds: bounds)]))
        XCTAssertFalse(safety.permits(point: CGPoint(x: CGFloat.nan, y: 1), hitProcessID: 10, hitBounds: bounds, windows: [target]))
        XCTAssertTrue(safety.permitsKeyboard(focusedProcessID: 10, focusedBounds: bounds, displayBounds: bounds))
        XCTAssertFalse(safety.permitsKeyboard(focusedProcessID: 20, focusedBounds: bounds, displayBounds: bounds))
        XCTAssertFalse(safety.permitsKeyboard(focusedProcessID: 10, focusedBounds: bounds.offsetBy(dx: 500, dy: 0), displayBounds: bounds))
    }

    func testTargetChangeIsDetectedWithoutConfusingOtherWindows() {
        let target = ControlWindowSnapshot(id: 1, processID: 10, bounds: CGRect(x: 100, y: 100, width: 200, height: 200))
        let other = ControlWindowSnapshot(id: 2, processID: 20, bounds: target.bounds)
        let guardrail = WindowControlSafety(target: target)
        XCTAssertEqual(guardrail.currentTarget(in: [other, target]), target)
        XCTAssertNil(guardrail.currentTarget(in: []))
        let moved = ControlWindowSnapshot(id: 1, processID: 10, bounds: target.bounds.offsetBy(dx: 1, dy: 0))
        let resized = ControlWindowSnapshot(id: 1, processID: 10, bounds: target.bounds.insetBy(dx: 0, dy: 1))
        XCTAssertEqual(guardrail.currentTarget(in: [moved]), moved)
        XCTAssertEqual(guardrail.currentTarget(in: [resized]), resized)
        XCTAssertNil(guardrail.currentTarget(in: [.init(id: 1, processID: 11, bounds: target.bounds)]))
        XCTAssertNil(guardrail.currentTarget(in: [target, target]))
    }

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
        let moved = ControlWindowSnapshot(id: 1, processID: 10, bounds: target.bounds.offsetBy(dx: 10, dy: 20))
        XCTAssertTrue(guardrail.permits(point: CGPoint(x: 130, y: 140), hitProcessID: 10, hitBounds: moved.bounds, windows: [moved]))
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
        let moved = ControlWindowSnapshot(id: 1, processID: 10, bounds: target.bounds.offsetBy(dx: 10, dy: 20))
        XCTAssertTrue(guardrail.permitsKeyboard(focusedProcessID: 10, focusedBounds: moved.bounds, windows: [moved]))
        let duplicate = ControlWindowSnapshot(id: 2, processID: 10, bounds: target.bounds)
        XCTAssertFalse(guardrail.permitsKeyboard(focusedProcessID: 10, focusedBounds: target.bounds, windows: [target, duplicate]))
    }
}
