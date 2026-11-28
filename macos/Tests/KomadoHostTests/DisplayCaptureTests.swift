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
    func testStaticWindowFrameIsDeliveredAfterConnection() async throws {
        guard CGPreflightScreenCaptureAccess() else { throw XCTSkip("Screen capture permission unavailable for test runner") }
        _ = NSApplication.shared
        let window = NSWindow(contentRect: CGRect(x: 40, y: 40, width: 320, height: 240),
                              styleMask: [.titled, .closable], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.title = "komado capture test"
        window.contentView = NSTextField(labelWithString: "komado")
        window.orderFrontRegardless()
        defer { window.orderOut(nil); window.close() }
        var target: SCWindow?
        for _ in 0..<30 {
            let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: false)
            target = content.windows.first { $0.windowID == CGWindowID(window.windowNumber) }
            if target != nil { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        let factory = MediaFactory.create()
        let source = factory.videoSource(forScreenCast: true)
        let track = factory.videoTrack(with: source, trackId: "static-window-test")
        let counter = FrameCounter()
        track.add(counter)
        defer { track.remove(counter) }
        let route = MediaSessionGate()
        let capturedWindow = try XCTUnwrap(target)
        let starting = Task { try await DisplayCapture.start(window: capturedWindow, source: source, route: route) {} }
        try await Task.sleep(for: .seconds(2))
        XCTAssertEqual(counter.value, 0)
        route.connected()
        let capture = try await starting.value
        defer { Task { try? await capture.stop() } }
        for _ in 0..<50 {
            if capture.frameMapping != nil { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertNotNil(capture.frameMapping)
        for _ in 0..<30 {
            if counter.value > 0 { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertGreaterThan(counter.value, 0, "A static window must be shown after the peer connects")
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
        let route = MediaSessionGate()
        route.connected()
        let stoppedUnexpectedly = expectation(description: "capture remains stable")
        stoppedUnexpectedly.isInverted = true
        let capture = try await DisplayCapture.start(display: display, source: source, route: route, resolution: .reduced) { stoppedUnexpectedly.fulfill() }
        try await Task.sleep(for: .seconds(2))
        XCTAssertNotNil(capture.frameMapping)
        let size = try XCTUnwrap(counter.frameSize)
        XCTAssertLessThanOrEqual(max(size.width, size.height), 854)
        XCTAssertLessThanOrEqual(min(size.width, size.height), 480)
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
        window.isReleasedWhenClosed = false
        window.title = "komado capture test"
        window.contentView = NSTextField(labelWithString: "komado")
        window.level = .floating
        window.orderFrontRegardless()
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
        let route = MediaSessionGate()
        route.connected()
        let stopped = expectation(description: "closed window stops capture")
        let capture = try await DisplayCapture.start(window: capturedWindow, source: source, route: route) { stopped.fulfill() }
        try await Task.sleep(for: .seconds(2))
        XCTAssertGreaterThan(counter.value, 0)
        window.close()
        await fulfillment(of: [stopped], timeout: 10)
        try await capture.stop()
        let countAfterStop = counter.value
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(counter.value, countAfterStop)
    }

    @MainActor
    func testWindowResizeUpdatesMappingOrStopsCapture() async throws {
        guard CGPreflightScreenCaptureAccess() else { throw XCTSkip("Screen capture permission unavailable for test runner") }
        _ = NSApplication.shared
        let window = NSWindow(contentRect: CGRect(x: 40, y: 40, width: 360, height: 260),
                              styleMask: [.titled, .closable, .resizable], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.title = "komado resize test"
        window.level = .floating
        window.orderFrontRegardless()
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
        let route = MediaSessionGate()
        route.connected()
        let capture = try await DisplayCapture.start(window: capturedWindow, source: source, route: route) {}
        for _ in 0..<50 {
            if capture.frameMapping != nil { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        let originalMapping = try XCTUnwrap(capture.frameMapping)
        var frame = window.frame
        frame.size.width += 60
        frame.size.height += 40
        window.setFrame(frame, display: true)
        try await Task.sleep(for: .seconds(2))
        if route.isAllowed {
            XCTAssertNotNil(capture.frameMapping)
            XCTAssertNotEqual(capture.frameMapping, originalMapping)
        } else {
            XCTAssertNil(capture.frameMapping)
        }
        try await capture.stop()
    }
}

private final class FrameCounter: NSObject, RTCVideoRenderer, @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    private var lastFrameSize: CGSize?
    var value: Int { lock.withLock { count } }
    var frameSize: CGSize? { lock.withLock { lastFrameSize } }
    func setSize(_ size: CGSize) {}
    func renderFrame(_ frame: RTCVideoFrame?) {
        if let frame { lock.withLock { count += 1; lastFrameSize = CGSize(width: Int(frame.width), height: Int(frame.height)) } }
    }
}
