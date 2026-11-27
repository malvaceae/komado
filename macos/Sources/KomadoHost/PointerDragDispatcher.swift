import ApplicationServices
import CoreGraphics
import KomadoCore

/// A release event is prepared before pressing and retained until release or teardown.
@MainActor
final class PointerDragDispatcher {
    private let trusted: () -> Bool
    private let post: (CGEvent) -> Void
    private var validator = PointerClickValidator()
    private var release: CGEvent?
    private var closed = false
    var isDragging: Bool { release != nil }

    init(trusted: @escaping () -> Bool = { AXIsProcessTrusted() },
         post: @escaping (CGEvent) -> Void = { $0.post(tap: .cghidEventTap) }) {
        self.trusted = trusted; self.post = post
    }

    @discardableResult
    func send(_ drag: PointerDrag, geometry: CaptureGeometry, mapping: CaptureFrameMapping, sessionAllowed: Bool) -> Bool {
        guard !closed, sessionAllowed, trusted() else { return false }
        var next = validator
        let input = PointerClick(sequence: drag.sequence, captureRevision: drag.captureRevision, x: drag.x, y: drag.y, button: .left)
        guard let point = next.point(for: input, in: geometry, mapping: mapping),
              let source = CGEventSource(stateID: .hidSystemState) else { return false }
        switch drag.phase {
        case .begin:
            guard release == nil,
                  let down = CGEvent(mouseEventSource: source, mouseType: .leftMouseDown, mouseCursorPosition: point, mouseButton: .left),
                  let up = CGEvent(mouseEventSource: source, mouseType: .leftMouseUp, mouseCursorPosition: point, mouseButton: .left),
                  trusted(), !closed else { return false }
            down.setIntegerValueField(.mouseEventClickState, value: 1)
            up.setIntegerValueField(.mouseEventClickState, value: 1)
            release = up
            validator = next
            post(down)
        case .move:
            guard let release,
                  let move = CGEvent(mouseEventSource: source, mouseType: .leftMouseDragged, mouseCursorPosition: point, mouseButton: .left),
                  trusted(), !closed else { return false }
            release.location = point
            validator = next
            post(move)
        case .end:
            guard let release, trusted(), !closed else { return false }
            release.location = point
            self.release = nil
            validator = next
            post(release)
        }
        return true
    }

    func close() {
        guard !closed else { return }
        closed = true
        if let release { self.release = nil; post(release) }
    }
}
