import Foundation
import KomadoCore

/// Accepts clicks only after a capture is announced and in increasing order.
final class ControlClickGate: @unchecked Sendable {
    private let lock = NSLock()
    private var revision: UInt64?
    private var sequence: UInt64 = 0
    private var closed = false

    func enable(revision: UInt64) -> Bool {
        lock.withLock {
            guard !closed, self.revision == nil, (1...PointerClick.maximumSequence).contains(revision) else { return false }
            self.revision = revision
            return true
        }
    }

    func accept(_ data: Data, binary: Bool) -> PointerClick? {
        guard !binary, let click = try? PointerClick.decode(data) else { return nil }
        return lock.withLock {
            guard !closed, revision == click.captureRevision, click.sequence > sequence else { return nil }
            sequence = click.sequence
            return click
        }
    }

    func close() { lock.withLock { closed = true } }
}
