import Foundation
import KomadoCore
import Network

/// Blocks screen frames until WebRTC reports the expected local route. A
/// subsequent mismatch closes the gate permanently and tells the session to end.
final class MediaRouteGate: @unchecked Sendable {
    private enum State { case awaitingSelection, allowed, closed }
    private let lock = NSLock()
    private let address: NWEndpoint.Host
    private let onInvalidRoute: @Sendable () -> Void
    private var state: State = .awaitingSelection

    init(address: NWEndpoint.Host, onInvalidRoute: @escaping @Sendable () -> Void) {
        self.address = address
        self.onInvalidRoute = onInvalidRoute
    }

    func selected(localCandidate: String) {
        let allowed = SDPCandidateFilter.permitsSelectedLocalCandidate(localCandidate, on: address)
        let notify = lock.withLock {
            guard state != .closed else { return false }
            state = allowed ? .allowed : .closed
            return !allowed
        }
        if notify { onInvalidRoute() }
    }

    func close() { lock.withLock { state = .closed } }

    func withAllowedFrame(_ action: () -> Void) {
        lock.withLock {
            guard state == .allowed else { return }
            action()
        }
    }
}
