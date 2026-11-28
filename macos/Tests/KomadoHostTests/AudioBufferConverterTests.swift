import AVFoundation
import XCTest
@testable import KomadoHost

final class AudioBufferConverterTests: XCTestCase {
    func testConvertsContinuousAudioWithoutLosingFrames() throws {
        let source = try XCTUnwrap(AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 44_100, channels: 2, interleaved: false))
        let destination = try XCTUnwrap(AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: 1, interleaved: false))
        let converter = AudioBufferConverter()
        var frames = 0
        for _ in 0..<100 {
            let input = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: source, frameCapacity: 441))
            input.frameLength = 441
            let output = try XCTUnwrap(converter.convert(input, to: destination, generation: 0))
            frames += Int(output.frameLength)
        }
        // A streaming resampler may buffer a few frames, but must not lose samples at every boundary.
        XCTAssertGreaterThan(frames, 47_900)
        XCTAssertLessThanOrEqual(frames, 48_000)
    }

    func testSharingRestartDoesNotRetainPreviousAudio() throws {
        let source = try XCTUnwrap(AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 44_100, channels: 1, interleaved: false))
        let destination = try XCTUnwrap(AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: 1, interleaved: false))
        let converter = AudioBufferConverter()
        let loud = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: source, frameCapacity: 441))
        loud.frameLength = 441
        for index in 0..<441 { loud.floatChannelData![0][index] = 0.5 }
        _ = try XCTUnwrap(converter.convert(loud, to: destination, generation: 0))
        let silence = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: source, frameCapacity: 441))
        silence.frameLength = 441
        for index in 0..<441 { silence.floatChannelData![0][index] = 0 }
        let output = try XCTUnwrap(converter.convert(silence, to: destination, generation: 1))
        let channel = try XCTUnwrap(output.floatChannelData?[0])
        for index in 0..<Int(output.frameLength) { XCTAssertEqual(channel[index], 0, accuracy: 0.000_001) }
    }
}
