import CoreGraphics
import CoreMedia
import CoreVideo
import Foundation
import ScreenCaptureKit
import WebRTC

// This local probe only counts frames. It does not transmit, display, or save them.
final class FrameCounter: NSObject, RTCVideoRenderer, @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0

    func setSize(_ size: CGSize) {}

    func renderFrame(_ frame: RTCVideoFrame?) {
        guard frame != nil else { return }
        lock.withLock { count += 1 }
    }

    var value: Int { lock.withLock { count } }
}

final class FrameBridge: NSObject, SCStreamOutput, @unchecked Sendable {
    private let source: RTCVideoSource
    private let capturer: RTCVideoCapturer

    init(source: RTCVideoSource) {
        self.source = source
        self.capturer = RTCVideoCapturer(delegate: source)
    }

    func forward(pixelBuffer: CVPixelBuffer, timestamp: Int64) {
        let buffer = RTCCVPixelBuffer(pixelBuffer: pixelBuffer)
        let frame = RTCVideoFrame(buffer: buffer, rotation: ._0, timeStampNs: timestamp)
        source.capturer(capturer, didCapture: frame)
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen, sampleBuffer.isValid,
              let pixelBuffer = sampleBuffer.imageBuffer else { return }
        let time = CMTimeConvertScale(sampleBuffer.presentationTimeStamp, timescale: 1_000_000_000, method: .default)
        guard time.isNumeric else { return }
        forward(pixelBuffer: pixelBuffer, timestamp: time.value)
    }
}

enum ProbeError: Error {
    case pixelBufferCreationFailed(OSStatus)
    case screenPermissionMissing
    case noDisplay
    case noFrames
}

let factory = RTCPeerConnectionFactory()
let source = factory.videoSource(forScreenCast: true)
let track = factory.videoTrack(with: source, trackId: "capture-probe")
let counter = FrameCounter()
let bridge = FrameBridge(source: source)
track.add(counter)
defer { track.remove(counter) }

do {
    if CommandLine.arguments.contains("--screen") {
        guard CGPreflightScreenCaptureAccess() else { throw ProbeError.screenPermissionMissing }
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
        guard let display = content.displays.first(where: { $0.displayID == CGMainDisplayID() }) else {
            throw ProbeError.noDisplay
        }
        let filter = SCContentFilter(display: display, excludingWindows: [])
        let configuration = SCStreamConfiguration()
        configuration.width = min(display.width, 1280)
        configuration.height = max(1, display.height * configuration.width / display.width)
        configuration.pixelFormat = kCVPixelFormatType_32BGRA
        configuration.minimumFrameInterval = CMTime(value: 1, timescale: 30)
        let stream = SCStream(filter: filter, configuration: configuration, delegate: nil)
        try stream.addStreamOutput(bridge, type: .screen, sampleHandlerQueue: DispatchQueue(label: "me.mallows.komado.capture-probe"))
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            stream.startCapture { error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume() }
            }
        }
        try await Task.sleep(for: .seconds(3))
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            stream.stopCapture { error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume() }
            }
        }
    } else {
        var pixelBuffer: CVPixelBuffer?
        let result = CVPixelBufferCreate(kCFAllocatorDefault, 640, 360, kCVPixelFormatType_32BGRA, nil, &pixelBuffer)
        guard result == kCVReturnSuccess, let pixelBuffer else { throw ProbeError.pixelBufferCreationFailed(result) }
        for _ in 0..<5 {
            bridge.forward(pixelBuffer: pixelBuffer, timestamp: Int64(DispatchTime.now().uptimeNanoseconds))
            try await Task.sleep(for: .milliseconds(100))
        }
    }
    guard counter.value > 0 else { throw ProbeError.noFrames }
    print("Frames received by WebRTC video track: \(counter.value)")
} catch {
    fputs("Capture probe failed: \(error)\n", stderr)
    exit(1)
}
