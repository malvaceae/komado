import Foundation
import XCTest
@testable import KomadoCore

final class SignalFrameTests: XCTestCase {
    private func bytes(_ hex: Substring) -> Data {
        var result = Data()
        var index = hex.startIndex
        while index < hex.endIndex {
            let end = hex.index(index, offsetBy: 2)
            result.append(UInt8(hex[index..<end], radix: 16)!)
            index = end
        }
        return result
    }

    func testSharedFramesWithEverySplit() throws {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let file = try String(contentsOf: root.appendingPathComponent("protocol/fixtures/signal-framing.txt"), encoding: .utf8)
        let rows = file.split(separator: "\n").filter { !$0.hasPrefix("#") }
        XCTAssertFalse(rows.isEmpty)
        for row in rows {
            let fields = row.split(separator: " ")
            let frame = bytes(fields[0]), payload = bytes(fields[1])
            XCTAssertEqual(try SignalFrame.encode(payload), frame)
            for split in 0...frame.count {
                var decoder = SignalFrame.Decoder()
                let first = try decoder.append(Data(frame.prefix(split)))
                let second = try decoder.append(Data(frame.dropFirst(split)))
                XCTAssertEqual(first + second, [payload])
                try decoder.finish()
            }
        }
    }

    func testMultipleFramesAndLengthBoundaries() throws {
        let first = Data([1, 2]), second = Data(repeating: 9, count: SignalFrame.maximumPayload)
        let framed = try SignalFrame.encode(first) + SignalFrame.encode(second)
        var decoder = SignalFrame.Decoder()
        let part = SignalFrame.maximumChunk
        XCTAssertEqual(try decoder.append(Data(framed.prefix(part))), [first])
        XCTAssertEqual(try decoder.append(Data(framed.dropFirst(part))), [second])
        try decoder.finish()
        XCTAssertThrowsError(try SignalFrame.encode(Data()))
        XCTAssertThrowsError(try SignalFrame.encode(Data(repeating: 0, count: SignalFrame.maximumPayload + 1)))
    }

    func testRejectsInvalidTruncatedAndOversizedChunks() throws {
        for header in [Data([0, 0, 0, 0]), Data([0, 0, 0x40, 1]), Data([255, 255, 255, 255])] {
            var decoder = SignalFrame.Decoder()
            XCTAssertThrowsError(try decoder.append(header)) { XCTAssertEqual($0 as? SignalFrame.FrameError, .invalidLength) }
            XCTAssertThrowsError(try decoder.append(Data())) { XCTAssertEqual($0 as? SignalFrame.FrameError, .closed) }
        }
        var incomplete = SignalFrame.Decoder()
        _ = try incomplete.append(Data([0, 0, 0, 2, 1]))
        XCTAssertThrowsError(try incomplete.finish()) { XCTAssertEqual($0 as? SignalFrame.FrameError, .truncated) }
        var oversized = SignalFrame.Decoder()
        XCTAssertThrowsError(try oversized.append(Data(repeating: 0, count: SignalFrame.maximumChunk + 1))) {
            XCTAssertEqual($0 as? SignalFrame.FrameError, .chunkTooLarge)
        }
    }
}
