import AppKit
import Foundation

/// Stops a media session when the desktop session, displays, or their
/// configuration changes. These notifications do not cover every lock state.
final class ScreenEnvironmentMonitor: @unchecked Sendable {
    private var registrations: [(NotificationCenter, NSObjectProtocol)] = []

    @MainActor
    init(workspace: NotificationCenter = NSWorkspace.shared.notificationCenter,
         application: NotificationCenter = .default,
         onChange: @escaping @Sendable () -> Void) {
        for (center, name) in [
            (workspace, NSWorkspace.sessionDidResignActiveNotification),
            (workspace, NSWorkspace.willSleepNotification),
            (workspace, NSWorkspace.willPowerOffNotification),
            (workspace, NSWorkspace.screensDidSleepNotification),
            (application, NSApplication.didChangeScreenParametersNotification),
            (application, Notification.Name.NSApplicationProtectedDataWillBecomeUnavailable),
        ] {
            let token = center.addObserver(forName: name, object: nil, queue: nil) { _ in onChange() }
            registrations.append((center, token))
        }
    }

    deinit {
        for (center, token) in registrations { center.removeObserver(token) }
    }
}
