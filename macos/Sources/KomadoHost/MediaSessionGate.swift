import Foundation

/// Allows media and input only while the authenticated WebRTC connection is active.
final class MediaSessionGate: @unchecked Sendable {
    enum ConnectionError: Error { case unavailable }
    private enum State { case awaitingConnection, allowed, closed }
    private let lock = NSLock()
    private var state: State = .awaitingConnection
    private let connection: AsyncStream<Void>
    private let continuation: AsyncStream<Void>.Continuation

    init() { (connection, continuation) = AsyncStream.makeStream(bufferingPolicy: .bufferingNewest(1)) }

    func connected() {
        let notify = lock.withLock {
            guard state == .awaitingConnection else { return false }
            state = .allowed
            return true
        }
        if notify { continuation.yield(()) }
    }
    func close() {
        lock.withLock { state = .closed }
        continuation.finish()
    }
    var isAllowed: Bool { lock.withLock { state == .allowed } }

    func waitUntilConnected() async throws {
        try await withThrowingTaskGroup(of: Void.self) { group in
            group.addTask { [connection] in
                var iterator = connection.makeAsyncIterator()
                guard await iterator.next() != nil else { throw ConnectionError.unavailable }
            }
            group.addTask {
                try await Task.sleep(for: .seconds(20))
                throw ConnectionError.unavailable
            }
            defer { group.cancelAll() }
            _ = try await group.next()
        }
        try Task.checkCancellation()
        guard isAllowed else { throw ConnectionError.unavailable }
    }
    func whileConnected(_ action: () -> Void) {
        lock.withLock {
            guard state == .allowed else { return }
            action()
        }
    }
}
