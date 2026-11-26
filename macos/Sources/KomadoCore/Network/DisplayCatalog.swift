import CoreGraphics
import Foundation

/// Enumerates active drawable displays without capturing their contents.
enum DisplayCatalog {
    enum CatalogError: Error { case unavailable }

    static func active() throws -> [DisplayInfo] {
        var count: UInt32 = 0
        guard CGGetActiveDisplayList(0, nil, &count) == .success, (1...64).contains(count) else {
            throw CatalogError.unavailable
        }
        var ids = [CGDirectDisplayID](repeating: 0, count: Int(count))
        var written: UInt32 = 0
        guard CGGetActiveDisplayList(count, &ids, &written) == .success, written == count else {
            throw CatalogError.unavailable
        }
        var currentCount: UInt32 = 0
        guard CGGetActiveDisplayList(0, nil, &currentCount) == .success, currentCount == count else {
            throw CatalogError.unavailable
        }
        return try ids.map { id in
            let width = CGDisplayPixelsWide(id), height = CGDisplayPixelsHigh(id)
            guard id > 0, (1...65_535).contains(width), (1...65_535).contains(height) else {
                throw CatalogError.unavailable
            }
            return DisplayInfo(id: id, width: UInt32(width), height: UInt32(height))
        }
    }
}
