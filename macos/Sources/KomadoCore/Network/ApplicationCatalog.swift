import AppKit
import CoreGraphics
import Foundation
@preconcurrency import ScreenCaptureKit

struct ApplicationSelection: Sendable {
    let info: ApplicationInfo
    let bundleIdentifier: String
}

@MainActor
enum ApplicationCatalog {
    enum CatalogError: Error { case unavailable }

    static func all() async throws -> [ApplicationSelection] {
        guard CGPreflightScreenCaptureAccess() else { throw CatalogError.unavailable }
        let content = try await SCShareableContent.excludingDesktopWindows(true, onScreenWindowsOnly: false)
        var ids = Set<UInt32>()
        let values = try content.applications.compactMap { application -> ApplicationSelection? in
            guard application.processID > 0 else { return nil }
            let id = UInt32(application.processID)
            guard ids.insert(id).inserted else { throw CatalogError.unavailable }
            return ApplicationSelection(info: ApplicationInfo(id: id, name: catalogLabel(application.applicationName, fallback: "アプリ")),
                                        bundleIdentifier: application.bundleIdentifier)
        }
        guard values.count <= Int(UInt16.max) else { throw CatalogError.unavailable }
        return values.sorted { $0.info.name == $1.info.name ? $0.info.id < $1.info.id : $0.info.name < $1.info.name }
    }

    static func matches(_ expected: ApplicationSelection) async throws -> Bool {
        guard CGPreflightScreenCaptureAccess() else { throw CatalogError.unavailable }
        let content = try await SCShareableContent.excludingDesktopWindows(true, onScreenWindowsOnly: false)
        return content.applications.contains { matches(application: $0, id: expected.info.id, bundleIdentifier: expected.bundleIdentifier) }
    }

    static func matches(application: SCRunningApplication, id: UInt32, bundleIdentifier: String) -> Bool {
        application.processID > 0 && UInt32(application.processID) == id && application.bundleIdentifier == bundleIdentifier
    }

    static func icon(_ expected: ApplicationSelection) -> Data {
        guard let application = NSRunningApplication(processIdentifier: Int32(expected.info.id)), !application.isTerminated,
              (application.bundleIdentifier ?? "") == expected.bundleIdentifier,
              let icon = application.icon,
              let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 32, pixelsHigh: 32,
                                            bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                                            colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0),
              let context = NSGraphicsContext(bitmapImageRep: bitmap) else { return Data() }
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = context
        context.imageInterpolation = .high
        icon.draw(in: CGRect(x: 0, y: 0, width: 32, height: 32))
        NSGraphicsContext.restoreGraphicsState()
        guard let data = bitmap.representation(using: .png, properties: [:]), data.count <= 8192 else { return Data() }
        return data
    }
}
