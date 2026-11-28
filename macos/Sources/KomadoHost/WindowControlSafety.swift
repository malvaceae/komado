import CoreGraphics
import Foundation

/// Never infer identity solely from an application name or an accessibility title.
struct ControlWindowSnapshot: Equatable {
    let id: UInt32
    let processID: Int32
    let bounds: CGRect
}

extension ControlWindowSnapshot {
    static func onScreen() -> [ControlWindowSnapshot]? {
        guard let raw = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID)
            as? [[String: Any]] else { return nil }
        var result: [ControlWindowSnapshot] = []
        for item in raw {
            guard let id = (item[kCGWindowNumber as String] as? NSNumber)?.uint32Value,
                  let pid = (item[kCGWindowOwnerPID as String] as? NSNumber)?.int32Value,
                  let dictionary = item[kCGWindowBounds as String] as? [String: Any],
                  let bounds = CGRect(dictionaryRepresentation: dictionary as CFDictionary),
                  [bounds.minX, bounds.minY, bounds.width, bounds.height, bounds.maxX, bounds.maxY].allSatisfy(\.isFinite),
                  bounds.width >= 0, bounds.height >= 0 else { continue }
            let alpha = (item[kCGWindowAlpha as String] as? NSNumber)?.doubleValue ?? 1
            guard alpha.isFinite else { continue }
            if alpha <= 0 || bounds.isEmpty { continue }
            result.append(ControlWindowSnapshot(id: id, processID: pid, bounds: bounds))
        }
        return result
    }
}

struct WindowControlSafety {
    let target: ControlWindowSnapshot

    func currentTarget(in windows: [ControlWindowSnapshot]) -> ControlWindowSnapshot? {
        let matches = windows.filter { $0.id == target.id }
        guard matches.count == 1, let current = matches.first, current.processID == target.processID else { return nil }
        return current
    }

    /// Reject closed or replaced targets and points that hit another window.
    func permits(point: CGPoint, hitProcessID: Int32, hitBounds: CGRect,
                 windows: [ControlWindowSnapshot]) -> Bool {
        guard let current = currentTarget(in: windows), point.x.isFinite, point.y.isFinite,
              current.bounds.contains(point), hitProcessID == current.processID, hitBounds == current.bounds else { return false }
        return windows.filter { $0.processID == current.processID && $0.bounds == current.bounds }.count == 1
    }

    /// The focused AX window must belong to the same process and geometry.
    /// Reject identical window bounds in the same process as ambiguous.
    func permitsKeyboard(focusedProcessID: Int32, focusedBounds: CGRect,
                         windows: [ControlWindowSnapshot]) -> Bool {
        guard let current = currentTarget(in: windows), focusedProcessID == current.processID,
              focusedBounds == current.bounds else { return false }
        return windows.filter { $0.processID == current.processID && $0.bounds == current.bounds }.count == 1
    }
}

struct ApplicationControlSafety {
    let processID: Int32

    func permits(point: CGPoint, hitProcessID: Int32, hitBounds: CGRect, windows: [ControlWindowSnapshot]) -> Bool {
        guard point.x.isFinite, point.y.isFinite, hitProcessID == processID, hitBounds.contains(point) else { return false }
        return windows.filter { $0.processID == processID && $0.bounds == hitBounds }.count == 1
    }

    func permitsKeyboard(focusedProcessID: Int32, focusedBounds: CGRect, displayBounds: CGRect) -> Bool {
        focusedProcessID == processID && displayBounds.contains(CGPoint(x: focusedBounds.midX, y: focusedBounds.midY))
    }
}
