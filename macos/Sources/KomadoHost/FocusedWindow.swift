import AppKit
import ApplicationServices

/// Refuse keyboard input if the foreground application's focused window is
/// unknown or centered on a display other than the one being shared.
@MainActor
enum FocusedWindow {
    static func isOnDisplay(_ bounds: CGRect) -> Bool {
        guard let focused = snapshot() else { return false }
        return bounds.contains(CGPoint(x: focused.bounds.midX, y: focused.bounds.midY))
    }

    static func snapshot() -> (processID: Int32, bounds: CGRect)? {
        guard let app = NSWorkspace.shared.frontmostApplication else { return nil }
        let element = AXUIElementCreateApplication(app.processIdentifier)
        var focused: CFTypeRef?
        guard AXUIElementCopyAttributeValue(element, kAXFocusedWindowAttribute as CFString, &focused) == .success,
              let focused, CFGetTypeID(focused) == AXUIElementGetTypeID() else { return nil }
        guard let bounds = bounds(of: focused as! AXUIElement) else { return nil }
        return (app.processIdentifier, bounds)
    }

    /// Accessibility hit-testing follows the window server's stacking order.
    /// Both a window result and a descendant exposing AXWindow are supported.
    static func window(at point: CGPoint) -> (processID: Int32, bounds: CGRect)? {
        guard point.x.isFinite, point.y.isFinite, Float(point.x).isFinite, Float(point.y).isFinite else { return nil }
        var element: AXUIElement?
        guard AXUIElementCopyElementAtPosition(AXUIElementCreateSystemWide(), Float(point.x), Float(point.y), &element) == .success,
              let element else { return nil }
        var hitPID: pid_t = 0
        guard AXUIElementGetPid(element, &hitPID) == .success, hitPID > 0 else { return nil }
        var role: CFTypeRef?
        let window: AXUIElement
        if AXUIElementCopyAttributeValue(element, kAXRoleAttribute as CFString, &role) == .success,
           (role as? String) == (kAXWindowRole as String) {
            window = element
        } else {
            var value: CFTypeRef?
            guard AXUIElementCopyAttributeValue(element, kAXWindowAttribute as CFString, &value) == .success,
                  let value, CFGetTypeID(value) == AXUIElementGetTypeID() else { return nil }
            window = value as! AXUIElement
        }
        var windowPID: pid_t = 0
        guard AXUIElementGetPid(window, &windowPID) == .success, windowPID == hitPID,
              let bounds = bounds(of: window) else { return nil }
        return (hitPID, bounds)
    }

    private static func bounds(of window: AXUIElement) -> CGRect? {
        var positionValue: CFTypeRef?, sizeValue: CFTypeRef?
        guard AXUIElementCopyAttributeValue(window, kAXPositionAttribute as CFString, &positionValue) == .success,
              AXUIElementCopyAttributeValue(window, kAXSizeAttribute as CFString, &sizeValue) == .success,
              let positionValue, CFGetTypeID(positionValue) == AXValueGetTypeID(),
              let sizeValue, CFGetTypeID(sizeValue) == AXValueGetTypeID() else { return nil }
        var position = CGPoint.zero, size = CGSize.zero
        guard AXValueGetValue(positionValue as! AXValue, .cgPoint, &position),
              AXValueGetValue(sizeValue as! AXValue, .cgSize, &size),
              position.x.isFinite, position.y.isFinite, size.width.isFinite, size.height.isFinite,
              size.width > 0, size.height > 0 else { return nil }
        return CGRect(origin: position, size: size)
    }
}
