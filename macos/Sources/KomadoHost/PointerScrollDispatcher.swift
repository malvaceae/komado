import ApplicationServices
import CoreGraphics
import KomadoCore

@MainActor
final class PointerScrollDispatcher {
    private let trusted: () -> Bool
    private let post: (CGEvent) -> Void
    private var validator = PointerClickValidator()
    private var closed = false

    init(trusted: @escaping () -> Bool = { AXIsProcessTrusted() },
         post: @escaping (CGEvent) -> Void = { $0.post(tap: .cghidEventTap) }) {
        self.trusted = trusted; self.post = post
    }

    @discardableResult
    func send(_ scroll: PointerScroll, geometry: CaptureGeometry, mapping: CaptureFrameMapping, sessionAllowed: Bool) -> Bool {
        guard !closed, sessionAllowed, trusted(), (-120...120).contains(scroll.horizontal),
              (-120...120).contains(scroll.vertical), scroll.horizontal != 0 || scroll.vertical != 0 else { return false }
        var next = validator
        let position = PointerClick(sequence: scroll.sequence, captureRevision: scroll.captureRevision,
                                    x: scroll.x, y: scroll.y, button: .left)
        guard let point = next.point(for: position, in: geometry, mapping: mapping),
              let source = CGEventSource(stateID: .hidSystemState),
              let event = CGEvent(scrollWheelEvent2Source: source, units: .pixel, wheelCount: 2,
                                  wheel1: scroll.vertical, wheel2: scroll.horizontal, wheel3: 0),
              trusted(), !closed else { return false }
        event.location = point
        validator = next
        post(event)
        return true
    }

    func close() { closed = true }
}
