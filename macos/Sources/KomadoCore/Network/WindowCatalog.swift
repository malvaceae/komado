import CoreGraphics
import Foundation
@preconcurrency import ScreenCaptureKit

struct WindowSelection: Sendable {
    let info: WindowInfo
    let processID: Int32
}

/// Window titles are sent only to an authenticated peer and are never logged.
@MainActor
public enum WindowCatalog {
    enum CatalogError: Error { case unavailable }

    static func all() async throws -> [WindowSelection] {
        guard CGPreflightScreenCaptureAccess() else { throw CatalogError.unavailable }
        let content = try await SCShareableContent.excludingDesktopWindows(true, onScreenWindowsOnly: false)
        var result: [WindowSelection] = []
        var ids = Set<UInt32>()
        for window in content.windows {
            guard let selection = selection(window) else { continue }
            guard ids.insert(selection.info.id).inserted else { throw CatalogError.unavailable }
            result.append(selection)
            guard result.count <= 512 else { throw CatalogError.unavailable }
        }
        return result.sorted {
            if $0.info.application != $1.info.application { return $0.info.application < $1.info.application }
            if $0.info.title != $1.info.title { return $0.info.title < $1.info.title }
            return $0.info.id < $1.info.id
        }
    }

    static func matches(_ expected: WindowSelection) async throws -> Bool {
        guard CGPreflightScreenCaptureAccess() else { throw CatalogError.unavailable }
        let content = try await SCShareableContent.excludingDesktopWindows(true, onScreenWindowsOnly: false)
        return content.windows.contains { matches(window: $0, info: expected.info, processID: expected.processID) }
    }

    public static func matches(window: SCWindow, info: WindowInfo, processID: Int32) -> Bool {
        guard let current = selection(window) else { return false }
        return current.processID == processID && current.info == info
    }

    private static func selection(_ window: SCWindow) -> WindowSelection? {
        guard let owner = window.owningApplication, owner.processID > 0, window.windowID > 0,
              window.frame.width.isFinite, window.frame.height.isFinite,
              window.frame.width >= 2, window.frame.height >= 2,
              window.frame.width <= 65_535, window.frame.height <= 65_535 else { return nil }
        let info = WindowInfo(id: window.windowID, width: UInt32(window.frame.width.rounded(.up)),
                              height: UInt32(window.frame.height.rounded(.up)),
                              application: label(owner.applicationName, fallback: "アプリ"),
                              title: label(window.title ?? "", fallback: "タイトルなし"))
        return WindowSelection(info: info, processID: owner.processID)
    }

    private static func label(_ input: String, fallback: String) -> String {
        var result = ""
        for scalar in input.unicodeScalars.prefix(512) {
            if scalar.value < 32 || (0x7f...0x9f).contains(scalar.value) { continue }
            let character = String(scalar)
            if result.utf8.count + character.utf8.count > 96 { break }
            result.append(character)
        }
        let trimmed = result.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? fallback : trimmed
    }
}
