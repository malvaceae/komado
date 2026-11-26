import CoreVideo
import CoreGraphics
import CoreMedia
import ScreenCaptureKit
import Foundation
import WebRTC

// The probe exchanges only generated frames and a fixed test message. SDP is
// passed via local files and adb, never through a signaling listener.
final class ExchangeProbe: NSObject, RTCPeerConnectionDelegate, RTCDataChannelDelegate, @unchecked Sendable {
    private let gatheringDone = DispatchSemaphore(value: 0)
    private let channelOpen = DispatchSemaphore(value: 0)
    private let acknowledged = DispatchSemaphore(value: 0)

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        print("ICE state: \(newState.rawValue)")
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {
        if newState == .complete { gatheringDone.signal() }
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
    func dataChannelDidChangeState(_ dataChannel: RTCDataChannel) {
        if dataChannel.readyState == .open { channelOpen.signal() }
    }
    func dataChannel(_ dataChannel: RTCDataChannel, didReceiveMessageWith buffer: RTCDataBuffer) {
        if !buffer.isBinary && String(data: buffer.data, encoding: .utf8) == "komado-ack" { acknowledged.signal() }
    }

    private func wait(_ semaphore: DispatchSemaphore, stage: String, seconds: Int = 20) throws {
        guard semaphore.wait(timeout: .now() + .seconds(seconds)) == .success else { throw ProbeError.timeout(stage) }
    }

    func run(offerURL: URL, answerURL: URL, captureScreen: Bool) throws {
        let factory = RTCPeerConnectionFactory()
        let configuration = RTCConfiguration()
        configuration.iceServers = []
        configuration.sdpSemantics = .unifiedPlan
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        guard let peer = factory.peerConnection(with: configuration, constraints: constraints, delegate: self) else {
            throw ProbeError.failed("peer creation")
        }
        defer { peer.close() }
        let source = factory.videoSource(forScreenCast: true)
        let capturer = RTCVideoCapturer(delegate: source)
        let track = factory.videoTrack(with: source, trackId: "synthetic-video")
        guard peer.add(track, streamIds: ["probe"]) != nil,
              let channel = peer.dataChannel(forLabel: "probe", configuration: RTCDataChannelConfiguration()) else {
            throw ProbeError.failed("track or channel creation")
        }
        channel.delegate = self

        let offerResult = CallbackResult<RTCSessionDescription>()
        peer.offer(for: constraints) { description, error in
            if let description { offerResult.complete(.success(description)) }
            else { offerResult.complete(.failure(error ?? ProbeError.failed("offer creation"))) }
        }
        let offer = try offerResult.value()
        let localSet = CallbackResult<Bool>()
        peer.setLocalDescription(offer) { error in
            if let error { localSet.complete(.failure(error)) } else { localSet.complete(.success(true)) }
        }
        _ = try localSet.value()
        try wait(gatheringDone, stage: "ICE gathering")
        guard let gatheredOffer = peer.localDescription else { throw ProbeError.failed("local description") }
        let json = try JSONSerialization.data(withJSONObject: ["sdp": gatheredOffer.sdp])
        try json.write(to: offerURL, options: .atomic)
        print("Offer ready")

        let deadline = Date().addingTimeInterval(30)
        while !FileManager.default.fileExists(atPath: answerURL.path) && Date() < deadline {
            Thread.sleep(forTimeInterval: 0.1)
        }
        guard FileManager.default.fileExists(atPath: answerURL.path) else { throw ProbeError.timeout("answer") }
        guard let answerJSON = try JSONSerialization.jsonObject(with: Data(contentsOf: answerURL)) as? [String: String],
              let answerSDP = answerJSON["sdp"] else { throw ProbeError.failed("answer format") }
        let answer = RTCSessionDescription(type: .answer, sdp: answerSDP)
        let remoteSet = CallbackResult<Bool>()
        peer.setRemoteDescription(answer) { error in
            if let error { remoteSet.complete(.failure(error)) } else { remoteSet.complete(.success(true)) }
        }
        _ = try remoteSet.value()
        try wait(channelOpen, stage: "data channel")
        guard channel.sendData(RTCDataBuffer(data: Data("komado-probe".utf8), isBinary: false)) else {
            throw ProbeError.failed("data send")
        }
        if captureScreen {
            try captureDisplay(source: source, track: track)
        } else {
            var pixelBuffer: CVPixelBuffer?
            guard CVPixelBufferCreate(kCFAllocatorDefault, 640, 360, kCVPixelFormatType_32BGRA, nil, &pixelBuffer) == kCVReturnSuccess,
                  let pixelBuffer else { throw ProbeError.failed("pixel buffer") }
            guard CVPixelBufferLockBaseAddress(pixelBuffer, []) == kCVReturnSuccess else { throw ProbeError.failed("pixel buffer lock") }
            if let bytes = CVPixelBufferGetBaseAddress(pixelBuffer) {
                memset(bytes, 0, CVPixelBufferGetBytesPerRow(pixelBuffer) * CVPixelBufferGetHeight(pixelBuffer))
            } else {
                CVPixelBufferUnlockBaseAddress(pixelBuffer, [])
                throw ProbeError.failed("pixel buffer address")
            }
            CVPixelBufferUnlockBaseAddress(pixelBuffer, [])
            for _ in 0..<90 {
                let frame = RTCVideoFrame(buffer: RTCCVPixelBuffer(pixelBuffer: pixelBuffer), rotation: ._0,
                                          timeStampNs: Int64(DispatchTime.now().uptimeNanoseconds))
                source.capturer(capturer, didCapture: frame)
                Thread.sleep(forTimeInterval: 1.0 / 30.0)
            }
        }
        try wait(acknowledged, stage: "data acknowledgement", seconds: 5)
        let diagnostics = CallbackResult<[String]>()
        peer.statistics { report in
            let descriptions = report.statistics.values.filter { $0.type == "outbound-rtp" }.map { stat in
                ["kind", "framesEncoded", "framesSent", "encoderImplementation", "qualityLimitationReason"]
                    .compactMap { key in stat.values[key].map { "\(key)=\($0)" } }.joined(separator: ", ")
            }
            diagnostics.complete(.success(descriptions))
        }
        print("Outbound video statistics: \(try diagnostics.value())")
        print("Data channel acknowledgement received")
    }

    private func captureDisplay(source: RTCVideoSource, track: RTCVideoTrack) throws {
        guard CGPreflightScreenCaptureAccess() else { throw ProbeError.failed("screen capture permission missing") }
        let contentResult = CallbackResult<SCShareableContent>()
        Task.detached {
            do {
                let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
                contentResult.complete(.success(content))
            } catch { contentResult.complete(.failure(error)) }
        }
        let content = try contentResult.value()
        guard let display = content.displays.first(where: { $0.displayID == CGMainDisplayID() }) else {
            throw ProbeError.failed("main display unavailable")
        }
        let filter = SCContentFilter(display: display, excludingWindows: [])
        let configuration = SCStreamConfiguration()
        let scale = min(1.0, 1280.0 / Double(display.width), 720.0 / Double(display.height))
        configuration.width = max(2, Int(Double(display.width) * scale) / 2 * 2)
        configuration.height = max(2, Int(Double(display.height) * scale) / 2 * 2)
        configuration.pixelFormat = kCVPixelFormatType_32BGRA
        configuration.minimumFrameInterval = CMTime(value: 1, timescale: 30)
        let bridge = ScreenBridge(source: source)
        let counter = VideoCounter()
        track.add(counter)
        defer { track.remove(counter) }
        print("Screen capture dimensions: \(configuration.width)x\(configuration.height)")
        let stream = SCStream(filter: filter, configuration: configuration, delegate: nil)
        try stream.addStreamOutput(bridge, type: .screen, sampleHandlerQueue: DispatchQueue(label: "me.mallows.komado.exchange-probe"))
        let started = CallbackResult<Bool>()
        stream.startCapture { error in
            if let error { started.complete(.failure(error)) } else { started.complete(.success(true)) }
        }
        _ = try started.value()
        Thread.sleep(forTimeInterval: 5)
        let stopped = CallbackResult<Bool>()
        stream.stopCapture { error in
            if let error { stopped.complete(.failure(error)) } else { stopped.complete(.success(true)) }
        }
        _ = try stopped.value()
        print("Screen capture frames: \(bridge.value), video track frames: \(counter.value)")
        if let first = bridge.timestamps.0, let last = bridge.timestamps.1 {
            print("Capture timestamp span seconds: \(Double(last - first) / 1_000_000_000)")
            print("Last capture timestamp relative to uptime seconds: \(Double(last - Int64(DispatchTime.now().uptimeNanoseconds)) / 1_000_000_000)")
        }
    }

}

final class VideoCounter: NSObject, RTCVideoRenderer, @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    func setSize(_ size: CGSize) {}
    func renderFrame(_ frame: RTCVideoFrame?) {
        if frame != nil { lock.withLock { count += 1 } }
    }
    var value: Int { lock.withLock { count } }
}

final class ScreenBridge: NSObject, SCStreamOutput, @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    private var firstTimestamp: Int64?
    private var lastTimestamp: Int64?
    var value: Int { lock.withLock { count } }
    var timestamps: (Int64?, Int64?) { lock.withLock { (firstTimestamp, lastTimestamp) } }
    private let source: RTCVideoSource
    private let capturer: RTCVideoCapturer
    init(source: RTCVideoSource) {
        self.source = source
        self.capturer = RTCVideoCapturer(delegate: source)
    }
    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen, sampleBuffer.isValid, let image = sampleBuffer.imageBuffer else { return }
        let timestamp = CMTimeConvertScale(sampleBuffer.presentationTimeStamp, timescale: 1_000_000_000, method: .default)
        guard timestamp.isNumeric else { return }
        let frame = RTCVideoFrame(buffer: RTCCVPixelBuffer(pixelBuffer: image), rotation: ._0, timeStampNs: timestamp.value)
        lock.withLock {
            count += 1
            if firstTimestamp == nil { firstTimestamp = timestamp.value }
            lastTimestamp = timestamp.value
        }
        source.capturer(capturer, didCapture: frame)
    }
}

enum ProbeError: Error {
    case failed(String)
    case timeout(String)
}

final class CallbackResult<T>: @unchecked Sendable {
    private let ready = DispatchSemaphore(value: 0)
    private let lock = NSLock()
    private var result: Result<T, Error>?
    func complete(_ result: Result<T, Error>) {
        lock.withLock { self.result = result }
        ready.signal()
    }
    func value() throws -> T {
        guard ready.wait(timeout: .now() + .seconds(20)) == .success else { throw ProbeError.timeout("callback") }
        return try lock.withLock { try result!.get() }
    }
}

RTCSetMinDebugLogLevel(.none)
do {
    guard (3...4).contains(CommandLine.arguments.count),
          CommandLine.arguments.count == 3 || CommandLine.arguments[3] == "--screen" else {
        throw ProbeError.failed("usage: ExchangeProbe OFFER_FILE ANSWER_FILE [--screen]")
    }
    try ExchangeProbe().run(offerURL: URL(fileURLWithPath: CommandLine.arguments[1]),
                            answerURL: URL(fileURLWithPath: CommandLine.arguments[2]),
                            captureScreen: CommandLine.arguments.count == 4)
} catch {
    fputs("Exchange probe failed: \(error)\n", stderr)
    exit(1)
}
