import AVFoundation
import CoreMedia
import Foundation
@preconcurrency import LiveKitWebRTC

/// Supplies ScreenCaptureKit audio to WebRTC's manually rendered audio engine.
final class AudioCapture: NSObject, LKRTCAudioDeviceModuleDelegate, @unchecked Sendable {
    private let player = AVAudioPlayerNode()
    private let lock = NSLock()
    private var enabled = false
    private var format: AVAudioFormat?
    private var queuedFrames: AVAudioFrameCount = 0
    private var generation = 0

    func setEnabled(_ value: Bool) {
        lock.withLock {
            enabled = value
            if !value { generation += 1; queuedFrames = 0 }
        }
        if !value { player.stop() }
    }

    func capture(_ sample: CMSampleBuffer) {
        guard sample.isValid, sample.numSamples > 0, sample.numSamples <= Int(Int32.max),
              let description = sample.formatDescription else { return }
        let sourceFormat = AVAudioFormat(cmAudioFormatDescription: description)
        guard let destinationFormat = lock.withLock({ enabled ? format : nil }),
              sourceFormat.sampleRate > 0, destinationFormat.sampleRate > 0,
              let input = AVAudioPCMBuffer(pcmFormat: sourceFormat, frameCapacity: AVAudioFrameCount(sample.numSamples)) else { return }
        input.frameLength = AVAudioFrameCount(sample.numSamples)
        let capacity = ceil(Double(input.frameLength) * destinationFormat.sampleRate / sourceFormat.sampleRate) + 64
        guard capacity.isFinite, capacity <= Double(AVAudioFrameCount.max),
              CMSampleBufferCopyPCMDataIntoAudioBufferList(sample, at: 0, frameCount: Int32(sample.numSamples), into: input.mutableAudioBufferList) == noErr,
              let output = AVAudioPCMBuffer(pcmFormat: destinationFormat, frameCapacity: AVAudioFrameCount(capacity)),
              let converter = AVAudioConverter(from: sourceFormat, to: destinationFormat) else { return }
        var supplied = false
        var conversionError: NSError?
        let status = converter.convert(to: output, error: &conversionError) { _, result in
            guard !supplied else { result.pointee = .noDataNow; return nil }
            supplied = true
            result.pointee = .haveData
            return input
        }
        guard status != .error, conversionError == nil else { return }
        guard output.frameLength > 0 else { return }
        lock.withLock {
            // Avoid retaining an ever-growing backlog when rendering falls behind capture.
            guard enabled, format == destinationFormat, let engine = player.engine, engine.isRunning,
                  queuedFrames + output.frameLength <= AVAudioFrameCount(destinationFormat.sampleRate / 2) else { return }
            let current = generation
            let frames = output.frameLength
            queuedFrames += frames
            player.scheduleBuffer(output, completionCallbackType: .dataConsumed) { [weak self] _ in
                guard let self else { return }
                self.lock.withLock {
                    if self.generation == current { self.queuedFrames -= frames }
                }
            }
            if !player.isPlaying { player.play() }
        }
    }

    func audioDeviceModule(_ module: LKRTCAudioDeviceModule, didReceiveSpeechActivityEvent event: LKRTCSpeechActivityEvent) {}
    func audioDeviceModuleDidUpdateDevices(_ module: LKRTCAudioDeviceModule) {}
    func audioDeviceModule(_ module: LKRTCAudioDeviceModule, didCreateEngine engine: AVAudioEngine) -> Int {
        engine.attach(player)
        return 0
    }
    func audioDeviceModule(_ module: LKRTCAudioDeviceModule, willStartEngine engine: AVAudioEngine, isPlayoutEnabled: Bool, isRecordingEnabled: Bool) -> Int {
        player.reset()
        return 0
    }
    func audioDeviceModule(_ module: LKRTCAudioDeviceModule, didStopEngine engine: AVAudioEngine, isPlayoutEnabled: Bool, isRecordingEnabled: Bool) -> Int {
        lock.withLock { generation += 1; queuedFrames = 0 }
        player.stop()
        return 0
    }
    func audioDeviceModule(_ module: LKRTCAudioDeviceModule, didDisableEngine engine: AVAudioEngine, isPlayoutEnabled: Bool, isRecordingEnabled: Bool) -> Int { 0 }
    func audioDeviceModule(_ module: LKRTCAudioDeviceModule, willReleaseEngine engine: AVAudioEngine) -> Int {
        lock.withLock { format = nil }
        engine.detach(player)
        return 0
    }
    func audioDeviceModule(_ module: LKRTCAudioDeviceModule, engine: AVAudioEngine, configureInputFromSource source: AVAudioNode?, toDestination destination: AVAudioNode, format: AVAudioFormat, context: [AnyHashable: Any]) -> Int {
        guard let inputFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: format.sampleRate,
                                              channels: format.channelCount, interleaved: format.isInterleaved) else { return -1 }
        engine.connect(player, to: engine.mainMixerNode, format: inputFormat)
        lock.withLock { self.format = inputFormat }
        return 0
    }
    func audioDeviceModule(_ module: LKRTCAudioDeviceModule, engine: AVAudioEngine, configureOutputFromSource source: AVAudioNode, toDestination destination: AVAudioNode?, format: AVAudioFormat, context: [AnyHashable: Any]) -> Int { 0 }
}
