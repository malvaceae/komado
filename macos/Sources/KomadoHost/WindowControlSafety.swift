import CoreGraphics
import Foundation

/// Never infer identity solely from an application name or an accessibility title.
struct ControlWindowSnapshot: Equatable {
    let id: UInt32
    let processID: Int32
    let bounds: CGRect
}

extension ControlWindowSnapshot {
    /// Refuse the entire snapshot when a window cannot be interpreted; an
    /// unknown front window must not be silently treated as transparent.
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
                  bounds.width >= 0, bounds.height >= 0 else { return nil }
            let alpha = (item[kCGWindowAlpha as String] as? NSNumber)?.doubleValue ?? 1
            guard alpha.isFinite else { return nil }
            if alpha <= 0 || bounds.isEmpty { continue }
            result.append(ControlWindowSnapshot(id: id, processID: pid, bounds: bounds))
        }
        return result
    }
}

struct WindowControlSafety {
    let target: ControlWindowSnapshot

    /// Reject moved, resized, closed or replaced targets and points where
    /// Accessibility hit-testing resolves to another window.
    func permits(point: CGPoint, hitProcessID: Int32, hitBounds: CGRect,
                 windows: [ControlWindowSnapshot]) -> Bool {
        guard point.x.isFinite, point.y.isFinite, target.bounds.contains(point),
              hitProcessID == target.processID, hitBounds == target.bounds,
              windows.first(where: { $0.id == target.id }) == target else { return false }
        return windows.filter { $0.processID == target.processID && $0.bounds == target.bounds }.count == 1
    }

    /// The focused AX window must belong to the same process and geometry.
    /// Reject identical window bounds in the same process as ambiguous.
    func permitsKeyboard(focusedProcessID: Int32, focusedBounds: CGRect,
                         windows: [ControlWindowSnapshot]) -> Bool {
        guard focusedProcessID == target.processID, focusedBounds == target.bounds,
              windows.first(where: { $0.id == target.id }) == target else { return false }
        return windows.filter { $0.processID == target.processID && $0.bounds == target.bounds }.count == 1
    }
}
