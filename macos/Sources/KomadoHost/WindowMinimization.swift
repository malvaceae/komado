import ApplicationServices
import ScreenCaptureKit

/// Match the selected ScreenCaptureKit window to one unambiguous AX window.
@MainActor
enum WindowMinimization {
    static func set(_ minimized: Bool, window: SCWindow, windows: [SCWindow]) -> Bool {
        guard let processID = window.owningApplication?.processID, processID > 0,
              windows.filter({ $0.owningApplication?.processID == processID && $0.frame == window.frame }).count == 1 else { return false }
        let application = AXUIElementCreateApplication(processID)
        var value: CFTypeRef?
        guard AXUIElementCopyAttributeValue(application, kAXWindowsAttribute as CFString, &value) == .success,
              let elements = value as? [AXUIElement] else { return false }
        let matches = elements.filter { FocusedWindow.bounds(of: $0) == window.frame }
        guard matches.count == 1, let element = matches.first else { return false }
        return AXUIElementSetAttributeValue(element, kAXMinimizedAttribute as CFString,
                                            minimized ? kCFBooleanTrue : kCFBooleanFalse) == .success
    }
}
