import Foundation

/// Keeps the system awake during a screen-sharing session. Display sleep and
/// explicit sleep remain under macOS control.
final class IdleSleepActivity {
    private var activity: (any NSObjectProtocol)?

    init(reason: String = "komado 画面転送中") {
        activity = ProcessInfo.processInfo.beginActivity(options: .idleSystemSleepDisabled, reason: reason)
    }

    func end() {
        guard let current = activity else { return }
        activity = nil
        ProcessInfo.processInfo.endActivity(current)
    }

    deinit { end() }
}
