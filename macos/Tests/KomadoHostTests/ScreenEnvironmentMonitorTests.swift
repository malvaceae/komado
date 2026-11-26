import AppKit
import Foundation
import XCTest
@testable import KomadoHost

private final class EventCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    func increment() { lock.withLock { count += 1 } }
    var value: Int { lock.withLock { count } }
}

final class ScreenEnvironmentMonitorTests: XCTestCase {
    @MainActor
    func testExpectedNotificationsAndObserverRemoval() {
        let workspace = NotificationCenter(), application = NotificationCenter(), counter = EventCounter()
        var monitor: ScreenEnvironmentMonitor? = ScreenEnvironmentMonitor(workspace: workspace, application: application) { counter.increment() }
        XCTAssertNotNil(monitor)
        workspace.post(name: NSWorkspace.sessionDidResignActiveNotification, object: nil)
        workspace.post(name: NSWorkspace.willSleepNotification, object: nil)
        workspace.post(name: NSWorkspace.willPowerOffNotification, object: nil)
        workspace.post(name: NSWorkspace.screensDidSleepNotification, object: nil)
        application.post(name: NSApplication.didChangeScreenParametersNotification, object: nil)
        XCTAssertEqual(counter.value, 5)
        monitor = nil
        workspace.post(name: NSWorkspace.willSleepNotification, object: nil)
        workspace.post(name: NSWorkspace.willPowerOffNotification, object: nil)
        workspace.post(name: NSWorkspace.screensDidSleepNotification, object: nil)
        application.post(name: NSApplication.didChangeScreenParametersNotification, object: nil)
        XCTAssertEqual(counter.value, 5)
    }
}
