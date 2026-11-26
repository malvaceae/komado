import Foundation
import XCTest
@testable import KomadoCore

private final class PairingTestClock: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Duration = .zero
    func now() -> Duration { lock.withLock { value } }
    func advance(_ duration: Duration) { lock.withLock { value += duration } }
}

private final class TokenSequence: @unchecked Sendable {
    private let lock = NSLock()
    private var calls = 0
    func next() -> Data {
        lock.withLock {
            calls += 1
            return Data(repeating: 1, count: calls == 1 ? 32 : 0)
        }
    }
}

final class PairingTokenStoreTests: XCTestCase {
    func testTokenIsConsumedOnceEvenWithConcurrentAttempts() async throws {
        let clock = PairingTestClock()
        let store = PairingTokenStore(now: { clock.now() }, makeToken: { Data(repeating: 7, count: 32) })
        let token = try await store.issue()
        async let first = store.consume(token)
        async let second = store.consume(token)
        let results = await [first, second]
        XCTAssertEqual(results.filter { $0 }.count, 1)
        let replay = await store.consume(token)
        XCTAssertFalse(replay)
    }

    func testInvalidAttemptsLockInvitationAndReissueReplacesIt() async throws {
        let clock = PairingTestClock()
        let store = PairingTokenStore(now: { clock.now() }, makeToken: { Data(repeating: 8, count: 32) })
        let token = try await store.issue()
        for _ in 0..<5 {
            let accepted = await store.consume(Data())
            XCTAssertFalse(accepted)
        }
        let afterLock = await store.consume(token)
        XCTAssertFalse(afterLock)
        let reissued = try await store.issue()
        let accepted = await store.consume(reissued)
        XCTAssertTrue(accepted)
    }

    func testFailedReissueStillInvalidatesPreviousInvitation() async throws {
        let clock = PairingTestClock(), sequence = TokenSequence()
        let store = PairingTokenStore(now: { clock.now() }, makeToken: { sequence.next() })
        let previous = try await store.issue()
        do {
            _ = try await store.issue()
            XCTFail("Invalid token generation accepted")
        } catch { XCTAssertEqual(error as? PairingTokenStore.IssueError, .randomGenerationFailed) }
        let accepted = await store.consume(previous)
        XCTAssertFalse(accepted)
    }

    func testExpiredAndCancelledInvitationsAreRejected() async throws {
        let clock = PairingTestClock()
        let store = PairingTokenStore(now: { clock.now() }, makeToken: { Data(repeating: 9, count: 32) })
        let token = try await store.issue()
        clock.advance(.seconds(120))
        let expired = await store.consume(token)
        XCTAssertFalse(expired)
        _ = try await store.issue()
        await store.invalidate()
        let cancelled = await store.consume(token)
        XCTAssertFalse(cancelled)
    }

    func testNewInvitationInvalidatesPreviousToken() async throws {
        let store = PairingTokenStore()
        let previous = try await store.issue()
        let current = try await store.issue()
        XCTAssertEqual(previous.count, 32)
        XCTAssertEqual(current.count, 32)
        XCTAssertNotEqual(previous, current)
        let oldAccepted = await store.consume(previous)
        let currentAccepted = await store.consume(current)
        XCTAssertFalse(oldAccepted)
        XCTAssertTrue(currentAccepted)
    }
}
