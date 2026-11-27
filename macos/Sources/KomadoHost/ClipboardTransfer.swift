import AppKit
import KomadoCore

/// Call only after the authenticated session and route have been checked.
@MainActor
final class ClipboardTransfer {
    private let pasteboard: NSPasteboard
    private var preparedUntil: TimeInterval?
    private var lastRequest: TimeInterval?
    init(pasteboard: NSPasteboard = .general) { self.pasteboard = pasteboard }

    func reset() { preparedUntil = nil; lastRequest = nil }

    func execute(_ operation: ClipboardRequest.Operation, allowed: Bool, busy: Bool,
                 now: TimeInterval = ProcessInfo.processInfo.systemUptime) -> ClipboardResponse.Result {
        guard allowed, now.isFinite else { reset(); return .unavailable }
        let prepared: Bool
        switch operation {
        case .set:
            prepared = preparedUntil.map { now <= $0 } ?? false
            preparedUntil = nil
        case .get, .prepare:
            prepared = true
            preparedUntil = nil
        }
        guard prepared else { return .unavailable }
        let rateLimited: Bool
        if case .set = operation { rateLimited = false }
        else { rateLimited = lastRequest.map { now - $0 < 0.2 } ?? false }
        lastRequest = now
        guard !busy, !rateLimited else { return .busy }
        switch operation {
        case .prepare:
            preparedUntil = now + 10
            return .ready
        case .get:
            let before = pasteboard.changeCount
            guard let items = pasteboard.pasteboardItems, items.count == 1, let item = items.first,
                  !item.types.contains(.fileURL),
                  !item.types.contains(NSPasteboard.PasteboardType("NSFilenamesPboardType")),
                  let value = item.string(forType: .string), before == pasteboard.changeCount,
                  ClipboardCodec.validText(value) else { return .unavailable }
            return .text(value)
        case let .set(value):
            guard ClipboardCodec.validText(value) else { return .unavailable }
            pasteboard.clearContents()
            return pasteboard.setString(value, forType: .string) ? .ok : .unavailable
        }
    }
}
