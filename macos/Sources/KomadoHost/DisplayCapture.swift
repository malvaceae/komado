import CoreGraphics
import CoreMedia
import CoreVideo
import Foundation
import KomadoCore
import ScreenCaptureKit
import WebRTC

/// Captures a display into a WebRTC video source. The caller must stop it when
/// the session ends and handle an unexpected stop by closing that session.
@MainActor
final class DisplayCapture {
    enum CaptureError: Error { case permissionRequired, invalidDimensions }

    private let stream: SCStream
    private let bridge: DisplayFrameBridge
    private var stopped = false
    var frameMapping: CaptureFrameMapping? { bridge.frameMapping }

    private init(stream: SCStream, bridge: DisplayFrameBridge) {
        self.stream = stream
        self.bridge = bridge
    }

    static func start(display: SCDisplay, source: RTCVideoSource, route: MediaRouteGate,
                      onStopped: @escaping @Sendable () -> Void) async throws -> DisplayCapture {
        guard CGPreflightScreenCaptureAccess() else { throw CaptureError.permissionRequired }
        let size = try dimensions(width: display.width, height: display.height)
        let configuration = SCStreamConfiguration()
        configuration.width = size.width
        configuration.height = size.height
        configuration.pixelFormat = kCVPixelFormatType_32BGRA
        configuration.minimumFrameInterval = CMTime(value: 1, timescale: 30)
        let bridge = DisplayFrameBridge(source: source, route: route, onStopped: onStopped)
        let stream = SCStream(filter: SCContentFilter(display: display, excludingWindows: []),
                              configuration: configuration, delegate: bridge)
        try stream.addStreamOutput(bridge, type: .screen,
                                   sampleHandlerQueue: DispatchQueue(label: "me.mallows.komado.capture"))
        do {
            try await stream.startCapture()
            if Task.isCancelled {
                bridge.deactivate()
                try? await stream.stopCapture()
                throw CancellationError()
            }
            return DisplayCapture(stream: stream, bridge: bridge)
        } catch {
            bridge.deactivate()
            throw error
        }
    }

    func stop() async throws {
        guard !stopped else { return }
        stopped = true
        bridge.deactivate()
        try await stream.stopCapture()
    }

    /// Conservative initial size pending encoder and device-specific testing.
    static func dimensions(width: Int, height: Int) throws -> (width: Int, height: Int) {
        guard width >= 2, height >= 2 else { throw CaptureError.invalidDimensions }
        let scale = min(1, 1280 / Double(width), 720 / Double(height))
        return (max(2, Int(Double(width) * scale) / 2 * 2),
                max(2, Int(Double(height) * scale) / 2 * 2))
    }
}

private final class DisplayFrameBridge: NSObject, SCStreamOutput, SCStreamDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var active = true
    private var mapping: CaptureFrameMapping?
    private let source: RTCVideoSource
    private let capturer: RTCVideoCapturer
    private let route: MediaRouteGate
    private let onStopped: @Sendable () -> Void

    init(source: RTCVideoSource, route: MediaRouteGate, onStopped: @escaping @Sendable () -> Void) {
        self.source = source
        self.capturer = RTCVideoCapturer(delegate: source)
        self.route = route
        self.onStopped = onStopped
    }

    func deactivate() { lock.withLock { active = false } }
    var frameMapping: CaptureFrameMapping? { lock.withLock { active ? mapping : nil } }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        let notify = lock.withLock {
            let wasActive = active
            active = false
            return wasActive
        }
        if notify { onStopped() }
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen else { return }
        guard sampleBuffer.isValid,
              let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false)
                as? [[SCStreamFrameInfo: Any]],
              let statusValue = attachments.first?[.status] as? Int, let status = SCFrameStatus(rawValue: statusValue) else {
            stopForUnusableFrame()
            return
        }
        switch CaptureFrameStatusPolicy.action(for: status) {
        case .ignore: return
        case .stop:
            stopForUnusableFrame()
            return
        case .deliver: break
        }
        guard let image = sampleBuffer.imageBuffer else { stopForUnusableFrame(); return }
        let timestamp = CMTimeConvertScale(sampleBuffer.presentationTimeStamp, timescale: 1_000_000_000, method: .default)
        guard timestamp.isNumeric else { stopForUnusableFrame(); return }
        let frame = RTCVideoFrame(buffer: RTCCVPixelBuffer(pixelBuffer: image), rotation: ._0, timeStampNs: timestamp.value)
        let newMapping = Self.frameMapping(attachments: attachments.first!, image: image)
        let invalidated = lock.withLock {
            guard active else { return false }
            if let mapping, mapping != newMapping {
                active = false
                route.close()
                return true
            }
            if mapping == nil { mapping = newMapping }
            route.withAllowedFrame { source.capturer(capturer, didCapture: frame) }
            return false
        }
        if invalidated { onStopped() }
    }

    private func stopForUnusableFrame() {
        let notify = lock.withLock {
            guard active else { return false }
            active = false
            route.close()
            return true
        }
        if notify { onStopped() }
    }

    private static func frameMapping(attachments: [SCStreamFrameInfo: Any], image: CVPixelBuffer) -> CaptureFrameMapping? {
        guard let dictionary = attachments[.contentRect] as? NSDictionary,
              let rect = CGRect(dictionaryRepresentation: dictionary as CFDictionary),
              let scale = attachments[.scaleFactor] as? CGFloat, scale.isFinite, scale > 0 else { return nil }
        let pixels = CGRect(x: rect.minX * scale, y: rect.minY * scale,
                            width: rect.width * scale, height: rect.height * scale)
        return CaptureFrameMapping(frameSize: CGSize(width: CVPixelBufferGetWidth(image), height: CVPixelBufferGetHeight(image)),
                                   contentRect: pixels)
    }
}

enum CaptureFrameStatusPolicy {
    enum Action { case deliver, ignore, stop }
    static func action(for status: SCFrameStatus) -> Action {
        switch status {
        case .complete: return .deliver
        case .idle, .started: return .ignore
        case .blank, .suspended, .stopped: return .stop
        @unknown default: return .stop
        }
    }
}
