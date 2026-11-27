import ApplicationServices
import CoreGraphics
import KomadoCore

@MainActor
final class PointerMoveDispatcher {
    private let trusted: () -> Bool
    private let post: (CGEvent) -> Void
    private var validator = PointerClickValidator()
    private var closed = false

    init(trusted: @escaping () -> Bool = { AXIsProcessTrusted() },
         post: @escaping (CGEvent) -> Void = { $0.post(tap: .cghidEventTap) }) {
        self.trusted = trusted; self.post = post
    }

    @discardableResult
    func send(_ move: PointerMove, geometry: CaptureGeometry, mapping: CaptureFrameMapping, sessionAllowed: Bool) -> Bool {
        guard !closed, sessionAllowed, trusted(), (try? move.encode()) != nil else { return false }
        var next = validator
        let input = PointerClick(sequence: move.sequence, captureRevision: move.captureRevision, x: move.x, y: move.y, button: .left)
        guard let point = next.point(for: input, in: geometry, mapping: mapping),
              let source = CGEventSource(stateID: .hidSystemState),
              let event = CGEvent(mouseEventSource: source, mouseType: .mouseMoved, mouseCursorPosition: point, mouseButton: .left),
              trusted(), !closed else { return false }
        validator = next
        post(event)
        return true
    }

    func close() { closed = true }
}
