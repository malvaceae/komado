import WebRTC

/// Creates the WebRTC factory on demand, when a media session is requested.
enum MediaFactory {
    static func create() -> RTCPeerConnectionFactory {
        RTCPeerConnectionFactory()
    }
}
