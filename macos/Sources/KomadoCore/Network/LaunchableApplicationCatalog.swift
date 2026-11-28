import AppKit
import CoreServices
import Foundation

struct LaunchableApplication: Sendable {
    let info: ApplicationInfo
    let url: URL
}

/// Spotlight determines which installed applications are discoverable.
@MainActor
enum LaunchableApplicationCatalog {
    enum CatalogError: Error { case unavailable }

    static func all() async throws -> [LaunchableApplication] {
        let query = NSMetadataQuery()
        query.predicate = NSPredicate(format: "%K == %@", kMDItemContentType as String, "com.apple.application-bundle")
        query.searchScopes = [NSMetadataQueryLocalComputerScope]
        guard query.start() else { throw CatalogError.unavailable }
        defer { query.stop() }
        let clock = ContinuousClock(), deadline = clock.now.advanced(by: .seconds(20))
        while query.isGathering {
            guard clock.now < deadline else { throw CatalogError.unavailable }
            try await Task.sleep(for: .milliseconds(50))
        }
        query.disableUpdates()
        let urls = Set(query.results.compactMap { result -> URL? in
            guard let path = (result as? NSMetadataItem)?.value(forAttribute: NSMetadataItemPathKey) as? String else { return nil }
            return URL(fileURLWithPath: path)
        })
        var candidates: [(url: URL, name: String)] = urls.map { url in
            (url: url, name: FileManager.default.displayName(atPath: url.path))
        }
        candidates.sort { left, right in
            if left.name == right.name { return left.url.path < right.url.path }
            return left.name.localizedStandardCompare(right.name) == .orderedAscending
        }
        guard candidates.count <= Int(UInt16.max) else { throw CatalogError.unavailable }
        let counts = Dictionary(grouping: candidates, by: { $0.name }).mapValues { $0.count }
        return candidates.enumerated().map { index, item in
            let name = counts[item.name, default: 0] > 1 ? "\(item.name) — \(item.url.path)" : item.name
            return LaunchableApplication(info: ApplicationInfo(id: UInt32(index + 1), name: catalogLabel(name, fallback: "アプリ")), url: item.url)
        }
    }

    static func icon(_ application: LaunchableApplication) -> Data {
        guard FileManager.default.fileExists(atPath: application.url.path) else { return Data() }
        return catalogIcon(NSWorkspace.shared.icon(forFile: application.url.path))
    }
}
