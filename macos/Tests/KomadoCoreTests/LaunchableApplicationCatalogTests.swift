import Foundation
import XCTest
@testable import KomadoCore

final class LaunchableApplicationCatalogTests: XCTestCase {
    @MainActor
    func testFindsAnIndexedTemporaryApplication() async throws {
        let repository = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let directory = repository.appendingPathComponent("komado-index-test-\(UUID().uuidString)")
        let application = directory.appendingPathComponent("Probe.app")
        let contents = application.appendingPathComponent("Contents")
        let executable = contents.appendingPathComponent("MacOS/Probe")
        try FileManager.default.createDirectory(at: executable.deletingLastPathComponent(), withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let bundleID = "me.mallows.komado.test.index.\(UUID().uuidString)"
        let info: [String: Any] = ["CFBundleName": "Komado Index Probe", "CFBundleIdentifier": bundleID,
                                   "CFBundleExecutable": "Probe", "CFBundlePackageType": "APPL"]
        try PropertyListSerialization.data(fromPropertyList: info, format: .xml, options: 0).write(to: contents.appendingPathComponent("Info.plist"))
        try Data("#!/bin/sh\nexit 0\n".utf8).write(to: executable)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: executable.path)
        let importer = Process()
        importer.executableURL = URL(fileURLWithPath: "/usr/bin/mdimport")
        importer.arguments = [application.path]
        importer.standardOutput = FileHandle.nullDevice
        importer.standardError = FileHandle.nullDevice
        try importer.run()
        importer.waitUntilExit()
        guard importer.terminationStatus == 0 else { throw XCTSkip("Spotlight import is unavailable") }
        var indexed = false
        for _ in 0..<50 {
            let search = Process()
            search.executableURL = URL(fileURLWithPath: "/usr/bin/mdfind")
            search.arguments = ["-onlyin", directory.path, "kMDItemCFBundleIdentifier == \"\(bundleID)\""]
            let output = Pipe()
            search.standardOutput = output
            search.standardError = FileHandle.nullDevice
            try search.run()
            let data = output.fileHandleForReading.readDataToEndOfFile()
            search.waitUntilExit()
            if !data.isEmpty { indexed = true; break }
            try await Task.sleep(for: .milliseconds(100))
        }
        guard indexed else { throw XCTSkip("Spotlight did not index the test application") }
        let applications = try await LaunchableApplicationCatalog.all()
        XCTAssertTrue(applications.contains { $0.url.standardizedFileURL.path == application.standardizedFileURL.path })
    }
}
