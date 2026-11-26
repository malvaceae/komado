import Foundation
import XCTest
@testable import KomadoHost

final class IdleSleepActivityTests: XCTestCase {
    func testActivityIsReleasedExplicitlyAndOnDeallocation() throws {
        let reason = "komado test \(UUID().uuidString)"
        var activity: IdleSleepActivity? = IdleSleepActivity(reason: reason)
        XCTAssertTrue(try assertionExists(reason))
        activity?.end()
        activity?.end()
        XCTAssertFalse(try assertionExists(reason))
        activity = IdleSleepActivity(reason: reason)
        XCTAssertTrue(try assertionExists(reason))
        activity = nil
        XCTAssertFalse(try assertionExists(reason))
    }

    private func assertionExists(_ reason: String) throws -> Bool {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/pmset")
        process.arguments = ["-g", "assertions"]
        let pipe = Pipe()
        process.standardOutput = pipe
        try process.run()
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else { throw NSError(domain: "IdleSleepActivityTests", code: Int(process.terminationStatus)) }
        return String(decoding: data, as: UTF8.self).contains(reason)
    }
}
