import AppKit
import ApplicationServices

/// Refuse keyboard input if the foreground application's focused window is
/// unknown or centered on a display other than the one being shared.
@MainActor
enum FocusedWindow {
    /// Request focus only when the selected window has one unambiguous AX match.
    static func activate(_ target: ControlWindowSnapshot) {
        guard let windows = ControlWindowSnapshot.onScreen(),
              WindowControlSafety(target: target).currentTarget(in: windows) == target,
              windows.filter({ $0.processID == target.processID && $0.bounds == target.bounds }).count == 1,
              let app = NSRunningApplication(processIdentifier: target.processID) else { return }
        let element = AXUIElementCreateApplication(target.processID)
        var value: CFTypeRef?
        guard AXUIElementCopyAttributeValue(element, kAXWindowsAttribute as CFString, &value) == .success,
              let axWindows = value as? [AXUIElement] else { return }
        let matches = axWindows.filter { bounds(of: $0) == target.bounds }
        guard matches.count == 1,
              AXUIElementPerformAction(matches[0], kAXRaiseAction as CFString) == .success else { return }
        _ = app.activate(options: [])
    }

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
        guard let (element, hitPID) = hit(at: point) else { return nil }
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

    /// Menus and popovers do not necessarily expose an AXWindow.
    static func processID(at point: CGPoint) -> Int32? { hit(at: point)?.1 }

    private static func hit(at point: CGPoint) -> (AXUIElement, Int32)? {
        guard point.x.isFinite, point.y.isFinite, Float(point.x).isFinite, Float(point.y).isFinite else { return nil }
        var element: AXUIElement?
        guard AXUIElementCopyElementAtPosition(AXUIElementCreateSystemWide(), Float(point.x), Float(point.y), &element) == .success,
              let element else { return nil }
        var hitPID: pid_t = 0
        guard AXUIElementGetPid(element, &hitPID) == .success, hitPID > 0 else { return nil }
        return (element, hitPID)
    }

    static func bounds(of window: AXUIElement) -> CGRect? {
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
