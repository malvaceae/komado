import CoreVideo
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

    func run(offerURL: URL, answerURL: URL, adapterFilter: String?) throws {
        let factory = RTCPeerConnectionFactory()
        if let adapterFilter {
            let options = RTCPeerConnectionFactoryOptions()
            if adapterFilter == "--filter-known-non-vpn" {
                options.ignoreWiFiNetworkAdapter = true
                options.ignoreEthernetNetworkAdapter = true
                options.ignoreCellularNetworkAdapter = true
                options.ignoreLoopbackNetworkAdapter = true
            } else {
                options.ignoreVPNNetworkAdapter = true
            }
            factory.setOptions(options)
        }
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
        let statsResult = CallbackResult<[String: String]>()
        peer.statistics { report in
            var types: [String: String] = [:]
            for statistic in report.statistics.values where statistic.type == "local-candidate" {
                if let address = (statistic.values["address"] ?? statistic.values["ip"]) as? String,
                   let networkType = statistic.values["networkType"] as? String {
                    types[address] = networkType
                }
            }
            statsResult.complete(.success(types))
        }
        let types = try statsResult.value()
        let json = try JSONSerialization.data(withJSONObject: ["sdp": gatheredOffer.sdp, "networkTypes": types])
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
        try wait(acknowledged, stage: "data acknowledgement", seconds: 5)
        print("Data channel acknowledgement received")
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

do {
    guard (3...4).contains(CommandLine.arguments.count),
          CommandLine.arguments.count == 3 || ["--filter-known-non-vpn", "--ignore-vpn"].contains(CommandLine.arguments[3]) else {
        throw ProbeError.failed("usage: ExchangeProbe OFFER_FILE ANSWER_FILE [--filter-known-non-vpn|--ignore-vpn]")
    }
    try ExchangeProbe().run(offerURL: URL(fileURLWithPath: CommandLine.arguments[1]),
                            answerURL: URL(fileURLWithPath: CommandLine.arguments[2]),
                            adapterFilter: CommandLine.arguments.count == 4 ? CommandLine.arguments[3] : nil)
} catch {
    fputs("Exchange probe failed: \(error)\n", stderr)
    exit(1)
}
