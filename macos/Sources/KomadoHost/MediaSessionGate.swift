import Foundation

/// Allows media and input only while the authenticated WebRTC connection is active.
final class MediaSessionGate: @unchecked Sendable {
    private enum State { case awaitingConnection, allowed, closed }
    private let lock = NSLock()
    private var state: State = .awaitingConnection

    func connected() { lock.withLock { if state == .awaitingConnection { state = .allowed } } }
    func close() { lock.withLock { state = .closed } }
    var isAllowed: Bool { lock.withLock { state == .allowed } }
    func whileConnected(_ action: () -> Void) {
        lock.withLock {
            guard state == .allowed else { return }
            action()
        }
    }
}
