import LiveKitWebRTC

/// Creates the WebRTC factory on demand, when a media session is requested.
enum MediaFactory {
    static func create() -> LKRTCPeerConnectionFactory {
        LKRTCPeerConnectionFactory(audioDeviceModuleType: .audioEngine, bypassVoiceProcessing: true,
                                   encoderFactory: LKRTCDefaultVideoEncoderFactory(),
                                   decoderFactory: LKRTCDefaultVideoDecoderFactory(), audioProcessingModule: nil)
    }
}
