import Foundation

/// Caps connection attempts, including failed TLS handshakes, per listener.
struct ConnectionRateLimit {
    private static let capacity = 10
    private static let window: Duration = .seconds(60)
    private var arrivals: [Duration] = []

    /// Nil admits the connection; a duration means when to retry accepting.
    mutating func admit(at now: Duration) -> Duration? {
        arrivals.removeAll { $0 + Self.window <= now }
        guard arrivals.count < Self.capacity else { return arrivals[0] + Self.window - now }
        arrivals.append(now)
        return nil
    }
}
