import AppKit
import CoreGraphics
import Foundation
import Network
import ScreenCaptureKit
import WebRTC
import XCTest
@testable import KomadoHost

final class DisplayCaptureTests: XCTestCase {
    @MainActor
    func testDimensionsPreserveAspectAndDoNotUpscale() throws {
        let wide = try DisplayCapture.dimensions(width: 3840, height: 2160)
        XCTAssertEqual(wide.width, 1280)
        XCTAssertEqual(wide.height, 720)
        let tall = try DisplayCapture.dimensions(width: 800, height: 1000)
        XCTAssertEqual(tall.width, 576)
        XCTAssertEqual(tall.height, 720)
        let small = try DisplayCapture.dimensions(width: 640, height: 480)
        XCTAssertEqual(small.width, 640)
        XCTAssertEqual(small.height, 480)
        XCTAssertThrowsError(try DisplayCapture.dimensions(width: 0, height: 480))
    }

    @MainActor
    func testDisplayFramesReachVideoTrackAndStop() async throws {
        guard CGPreflightScreenCaptureAccess() else { throw XCTSkip("Screen capture permission unavailable for test runner") }
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
        guard let display = content.displays.first(where: { $0.displayID == CGMainDisplayID() }) else {
            throw XCTSkip("Main display unavailable")
        }
        let factory = MediaFactory.create()
        let source = factory.videoSource(forScreenCast: true)
        let track = factory.videoTrack(with: source, trackId: "test")
        let counter = FrameCounter()
        track.add(counter)
        defer { track.remove(counter) }
        let route = MediaRouteGate(address: .ipv4(IPv4Address("127.0.0.1")!), onInvalidRoute: {})
        route.selected(localCandidate: "candidate:1 1 udp 1 127.0.0.1 1234 typ host")
        let stoppedUnexpectedly = expectation(description: "capture remains stable")
        stoppedUnexpectedly.isInverted = true
        let capture = try await DisplayCapture.start(display: display, source: source, route: route) { stoppedUnexpectedly.fulfill() }
        try await Task.sleep(for: .seconds(2))
        XCTAssertNotNil(capture.frameMapping)
        await fulfillment(of: [stoppedUnexpectedly], timeout: 0.1)
        try await capture.stop()
        let countAfterStop = counter.value
        XCTAssertGreaterThan(countAfterStop, 0)
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(counter.value, countAfterStop)
    }

    @MainActor
    func testOwnWindowFramesReachVideoTrackAndStop() async throws {
        guard CGPreflightScreenCaptureAccess() else { throw XCTSkip("Screen capture permission unavailable for test runner") }
        _ = NSApplication.shared
        let window = NSWindow(contentRect: CGRect(x: 40, y: 40, width: 320, height: 240),
                              styleMask: [.titled, .closable], backing: .buffered, defer: false)
        window.title = "komado capture test"
        window.contentView = NSTextField(labelWithString: "komado")
        window.orderFront(nil)
        defer { window.orderOut(nil); window.close() }
        var target: SCWindow?
        for _ in 0..<30 {
            let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: false)
            target = content.windows.first {
                $0.windowID == CGWindowID(window.windowNumber) && $0.frame.width > 1 && $0.frame.height > 1
            }
            if target != nil { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        let capturedWindow = try XCTUnwrap(target)
        let factory = MediaFactory.create()
        let source = factory.videoSource(forScreenCast: true)
        let track = factory.videoTrack(with: source, trackId: "window-test")
        let counter = FrameCounter()
        track.add(counter)
        defer { track.remove(counter) }
        let route = MediaRouteGate(address: .ipv4(IPv4Address("127.0.0.1")!), onInvalidRoute: {})
        route.selected(localCandidate: "candidate:1 1 udp 1 127.0.0.1 1234 typ host")
        let capture = try await DisplayCapture.start(window: capturedWindow, source: source, route: route) {}
        try await Task.sleep(for: .seconds(2))
        try await capture.stop()
        let countAfterStop = counter.value
        XCTAssertGreaterThan(countAfterStop, 0)
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(counter.value, countAfterStop)
    }
}

private final class FrameCounter: NSObject, RTCVideoRenderer, @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    var value: Int { lock.withLock { count } }
    func setSize(_ size: CGSize) {}
    func renderFrame(_ frame: RTCVideoFrame?) {
        if frame != nil { lock.withLock { count += 1 } }
    }
}
