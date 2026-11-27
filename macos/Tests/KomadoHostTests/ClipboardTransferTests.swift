import AppKit
import KomadoCore
import XCTest
@testable import KomadoHost

final class ClipboardTransferTests: XCTestCase {
    @MainActor func testIsolatedPasteboardRequiresPermissionAndPreparation() {
        let pasteboard = NSPasteboard(name: NSPasteboard.Name("me.mallows.komado.test.\(UUID().uuidString)"))
        defer { pasteboard.releaseGlobally() }
        let transfer = ClipboardTransfer(pasteboard: pasteboard)
        XCTAssertEqual(transfer.execute(.get, allowed: true, busy: false, now: 1), .unavailable)
        XCTAssertEqual(transfer.execute(.prepare, allowed: false, busy: false, now: 2), .unavailable)
        XCTAssertEqual(transfer.execute(.set("blocked"), allowed: true, busy: false, now: 3), .unavailable)
        XCTAssertEqual(transfer.execute(.prepare, allowed: true, busy: true, now: 4), .busy)
        XCTAssertEqual(transfer.execute(.set("blocked"), allowed: true, busy: false, now: 5), .unavailable)
        XCTAssertEqual(transfer.execute(.prepare, allowed: true, busy: false, now: 6), .ready)
        XCTAssertEqual(transfer.execute(.set("日本語😀\r\n"), allowed: true, busy: false, now: 6.1), .ok)
        XCTAssertEqual(transfer.execute(.set("blocked"), allowed: true, busy: false, now: 7), .unavailable)
        XCTAssertEqual(transfer.execute(.get, allowed: true, busy: false, now: 8), .text("日本語😀\r\n"))
        XCTAssertEqual(transfer.execute(.prepare, allowed: true, busy: false, now: 9), .ready)
        XCTAssertEqual(transfer.execute(.set("expired"), allowed: true, busy: false, now: 20), .unavailable)
        XCTAssertEqual(transfer.execute(.prepare, allowed: true, busy: false, now: 21), .ready)
        XCTAssertEqual(transfer.execute(.set("bad\0value"), allowed: true, busy: false, now: 21.1), .unavailable)
        XCTAssertEqual(transfer.execute(.prepare, allowed: true, busy: false, now: 22), .ready)
        XCTAssertEqual(transfer.execute(.set(String(repeating: "x", count: 16_385)), allowed: true, busy: false, now: 22.1), .unavailable)
        XCTAssertEqual(transfer.execute(.prepare, allowed: true, busy: false, now: 23), .ready)
        transfer.reset()
        XCTAssertEqual(transfer.execute(.set("revoked"), allowed: true, busy: false, now: 23.1), .unavailable)
        XCTAssertEqual(transfer.execute(.get, allowed: true, busy: false, now: 24), .text("日本語😀\r\n"))
        XCTAssertEqual(transfer.execute(.prepare, allowed: true, busy: false, now: 24.1), .busy)
        XCTAssertEqual(transfer.execute(.set("blocked"), allowed: true, busy: false, now: 24.15), .unavailable)
        let first = NSPasteboardItem(), second = NSPasteboardItem()
        XCTAssertTrue(first.setString("first", forType: .string))
        XCTAssertTrue(second.setString("second", forType: .string))
        pasteboard.clearContents()
        XCTAssertTrue(pasteboard.writeObjects([first, second]))
        XCTAssertEqual(transfer.execute(.get, allowed: true, busy: false, now: 25), .unavailable)
        pasteboard.clearContents()
        XCTAssertTrue(pasteboard.writeObjects([NSURL(fileURLWithPath: "/tmp/komado-test-file")]))
        XCTAssertEqual(transfer.execute(.get, allowed: true, busy: false, now: 26), .unavailable)
    }
}
