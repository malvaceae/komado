import AppKit
import ApplicationServices
import KomadoCore

/// Changes a uniquely identified visible window and restores its original frame.
@MainActor
final class WindowResize {
    private let target: ControlWindowSnapshot
    private let element: AXUIElement
    private let original: CGRect

    private init(target: ControlWindowSnapshot, element: AXUIElement, original: CGRect) {
        self.target = target
        self.element = element
        self.original = original
    }

    static func start(target: ControlWindowSnapshot, desired: WindowSize) -> WindowResize? {
        guard desired.width > 0, desired.height > 0,
              let windows = ControlWindowSnapshot.onScreen(),
              WindowControlSafety(target: target).currentTarget(in: windows) == target,
              windows.filter({ $0.processID == target.processID && $0.bounds == target.bounds }).count == 1 else { return nil }
        let application = AXUIElementCreateApplication(target.processID)
        var value: CFTypeRef?
        guard AXUIElementCopyAttributeValue(application, kAXWindowsAttribute as CFString, &value) == .success,
              let elements = value as? [AXUIElement] else { return nil }
        let matches = elements.filter { FocusedWindow.bounds(of: $0) == target.bounds }
        guard matches.count == 1, let element = matches.first else { return nil }
        var settable = DarwinBoolean(false)
        guard AXUIElementIsAttributeSettable(element, kAXSizeAttribute as CFString, &settable) == .success,
              settable.boolValue else { return nil }
        let screenSize = NSScreen.screens.first {
            guard let number = $0.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber else { return false }
            return CGDisplayBounds(number.uint32Value).contains(CGPoint(x: target.bounds.midX, y: target.bounds.midY))
        }?.visibleFrame.size ?? target.bounds.size
        let aspect = CGFloat(desired.width) / CGFloat(desired.height)
        let area = target.bounds.width * target.bounds.height
        let width = min(sqrt(area * aspect), screenSize.width, screenSize.height * aspect)
        var size = CGSize(width: max(1, width.rounded()), height: max(1, (width / aspect).rounded()))
        guard let encoded = AXValueCreate(.cgSize, &size),
              AXUIElementSetAttributeValue(element, kAXSizeAttribute as CFString, encoded) == .success else { return nil }
        return WindowResize(target: target, element: element, original: target.bounds)
    }

    func restore() {
        guard let windows = ControlWindowSnapshot.onScreen(),
              let current = WindowControlSafety(target: target).currentTarget(in: windows),
              FocusedWindow.bounds(of: element) == current.bounds,
              windows.filter({ $0.processID == current.processID && $0.bounds == current.bounds }).count == 1 else { return }
        var size = original.size
        if let value = AXValueCreate(.cgSize, &size) {
            _ = AXUIElementSetAttributeValue(element, kAXSizeAttribute as CFString, value)
        }
        var position = original.origin
        if let value = AXValueCreate(.cgPoint, &position) {
            _ = AXUIElementSetAttributeValue(element, kAXPositionAttribute as CFString, value)
        }
    }
}
