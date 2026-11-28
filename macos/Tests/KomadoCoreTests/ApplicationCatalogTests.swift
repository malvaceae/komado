import AppKit
import CoreGraphics
import XCTest
@testable import KomadoCore

final class ApplicationCatalogTests: XCTestCase {
    @MainActor
    func testListsApplicationsAndReturnsSmallIcon() async throws {
        guard CGPreflightScreenCaptureAccess() else { throw XCTSkip("Screen capture permission is required") }
        let applications = try await ApplicationCatalog.all()
        XCTAssertEqual(Set(applications.map(\.info.id)).count, applications.count)
        guard let application = applications.first(where: {
            NSRunningApplication(processIdentifier: Int32($0.info.id))?.icon != nil
        }) else { throw XCTSkip("No available application icon") }
        let isCurrent = try await ApplicationCatalog.matches(application)
        XCTAssertTrue(isCurrent)
        let data = ApplicationCatalog.icon(application)
        XCTAssertFalse(data.isEmpty)
        let bitmap = try XCTUnwrap(NSBitmapImageRep(data: data))
        XCTAssertEqual(bitmap.pixelsWide, 32)
        XCTAssertEqual(bitmap.pixelsHigh, 32)
    }
}
