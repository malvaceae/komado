import ApplicationServices
import CoreGraphics
import KomadoCore

/// Constructs an indivisible press/release pair after the caller has checked
/// the authenticated session, capture state, route, and user authorization.
@MainActor
final class PointerClickDispatcher {
    private let trusted: () -> Bool
    private let post: (CGEvent) -> Void
    private var validator = PointerClickValidator()
    private var closed = false

    init(trusted: @escaping () -> Bool = { AXIsProcessTrusted() },
         post: @escaping (CGEvent) -> Void = { $0.post(tap: .cghidEventTap) }) {
        self.trusted = trusted
        self.post = post
    }

    @discardableResult
    func click(_ click: PointerClick, geometry: CaptureGeometry, mapping: CaptureFrameMapping,
               sessionAllowed: Bool) -> Bool {
        guard !closed, sessionAllowed, trusted() else { return false }
        var next = validator
        guard let point = next.point(for: click, in: geometry, mapping: mapping) else { return false }
        let button: CGMouseButton
        let downType: CGEventType
        let upType: CGEventType
        switch click.button {
        case .left: button = .left; downType = .leftMouseDown; upType = .leftMouseUp
        case .right: button = .right; downType = .rightMouseDown; upType = .rightMouseUp
        case .middle: button = .center; downType = .otherMouseDown; upType = .otherMouseUp
        }
        guard let source = CGEventSource(stateID: .hidSystemState),
              let down = CGEvent(mouseEventSource: source, mouseType: downType, mouseCursorPosition: point, mouseButton: button),
              let up = CGEvent(mouseEventSource: source, mouseType: upType, mouseCursorPosition: point, mouseButton: button),
              trusted(), !closed else { return false }
        guard (try? click.encode()) != nil else { return false }
        down.setIntegerValueField(.mouseEventClickState, value: Int64(click.count))
        up.setIntegerValueField(.mouseEventClickState, value: Int64(click.count))
        validator = next
        post(down)
        post(up)
        return true
    }

    func close() { closed = true }
}
