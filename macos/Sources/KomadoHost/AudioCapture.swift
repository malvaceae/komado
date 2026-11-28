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
    private let converter = AudioBufferConverter()

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
        guard let (destinationFormat, current) = lock.withLock({ enabled ? format.map { ($0, generation) } : nil }),
              let input = AVAudioPCMBuffer(pcmFormat: sourceFormat, frameCapacity: AVAudioFrameCount(sample.numSamples)) else { return }
        input.frameLength = AVAudioFrameCount(sample.numSamples)
        guard CMSampleBufferCopyPCMDataIntoAudioBufferList(sample, at: 0, frameCount: Int32(sample.numSamples), into: input.mutableAudioBufferList) == noErr,
              let output = converter.convert(input, to: destinationFormat, generation: current) else { return }
        guard output.frameLength > 0 else { return }
        lock.withLock {
            // Avoid retaining an ever-growing backlog when rendering falls behind capture.
            guard enabled, generation == current, format == destinationFormat, let engine = player.engine, engine.isRunning,
                  queuedFrames + output.frameLength <= AVAudioFrameCount(destinationFormat.sampleRate / 2) else { return }
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

/// Retains conversion state across buffers and resets it when sharing is interrupted.
final class AudioBufferConverter: @unchecked Sendable {
    private let lock = NSLock()
    private var converter: AVAudioConverter?
    private var generation: Int?

    func convert(_ input: AVAudioPCMBuffer, to outputFormat: AVAudioFormat, generation: Int) -> AVAudioPCMBuffer? {
        lock.withLock {
            guard input.format.sampleRate > 0, outputFormat.sampleRate > 0 else { return nil }
            if self.generation != generation || converter?.inputFormat != input.format || converter?.outputFormat != outputFormat {
                converter = AVAudioConverter(from: input.format, to: outputFormat)
                self.generation = generation
            }
            let capacity = ceil(Double(input.frameLength) * outputFormat.sampleRate / input.format.sampleRate) + 64
            guard capacity.isFinite, capacity <= Double(AVAudioFrameCount.max), let converter,
                  let output = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: AVAudioFrameCount(capacity)) else { return nil }
            var supplied = false
            var conversionError: NSError?
            let status = converter.convert(to: output, error: &conversionError) { _, result in
                guard !supplied else { result.pointee = .noDataNow; return nil }
                supplied = true
                result.pointee = .haveData
                return input
            }
            return status != .error && conversionError == nil ? output : nil
        }
    }
}
