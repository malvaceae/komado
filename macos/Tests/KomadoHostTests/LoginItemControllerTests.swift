import XCTest
@testable import KomadoHost

final class LoginItemControllerTests: XCTestCase {
    @MainActor
    func testRegistrationApprovalExternalChangesAndErrors() {
        var status = LoginItemController.State.disabled
        var registrations = 0, removals = 0, opened = 0
        var fail = false
        let controller = LoginItemController(service: .init(status: { status }, register: {
            registrations += 1
            if fail { throw TestError.failed }
            status = .requiresApproval
        }, unregister: {
            removals += 1
            if fail { throw TestError.failed }
            status = .disabled
        }, openSettings: { opened += 1 }))

        XCTAssertFalse(controller.registered)
        controller.setRegistered(true)
        XCTAssertEqual(controller.state, .requiresApproval)
        XCTAssertTrue(controller.registered)
        controller.setRegistered(true)
        XCTAssertEqual(registrations, 1)
        controller.openSettings()
        XCTAssertEqual(opened, 1)
        status = .enabled
        controller.refresh()
        XCTAssertEqual(controller.state, .enabled)
        fail = true
        controller.setRegistered(false)
        XCTAssertTrue(controller.error)
        XCTAssertTrue(controller.registered)
        fail = false
        controller.setRegistered(false)
        XCTAssertFalse(controller.error)
        XCTAssertEqual(controller.state, .disabled)
        XCTAssertEqual(removals, 2)
        status = .unavailable
        controller.refresh()
        controller.setRegistered(true)
        XCTAssertEqual(registrations, 1)
        XCTAssertFalse(controller.registered)
    }

    private enum TestError: Error { case failed }
}
