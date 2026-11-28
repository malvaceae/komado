import ApplicationServices
import KomadoCore
import ScreenCaptureKit

/// Match the selected ScreenCaptureKit window to one unambiguous AX window.
@MainActor
enum WindowActions {
    static func perform(_ action: WindowAction, window: SCWindow, windows: [SCWindow]) -> Bool {
        guard let processID = window.owningApplication?.processID, processID > 0,
              windows.filter({ $0.owningApplication?.processID == processID && $0.frame == window.frame }).count == 1 else { return false }
        let application = AXUIElementCreateApplication(processID)
        var value: CFTypeRef?
        guard AXUIElementCopyAttributeValue(application, kAXWindowsAttribute as CFString, &value) == .success,
              let elements = value as? [AXUIElement] else { return false }
        let matches = elements.filter { FocusedWindow.bounds(of: $0) == window.frame }
        guard matches.count == 1, let element = matches.first else { return false }
        switch action {
        case .minimize, .restore:
            return AXUIElementSetAttributeValue(element, kAXMinimizedAttribute as CFString,
                                                action == .minimize ? kCFBooleanTrue : kCFBooleanFalse) == .success
        case .close:
            var value: CFTypeRef?
            guard AXUIElementCopyAttributeValue(element, kAXCloseButtonAttribute as CFString, &value) == .success,
                  let value, CFGetTypeID(value) == AXUIElementGetTypeID() else { return false }
            return AXUIElementPerformAction(value as! AXUIElement, kAXPressAction as CFString) == .success
        }
    }
}
