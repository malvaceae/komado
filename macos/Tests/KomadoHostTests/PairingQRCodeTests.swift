import Foundation
import KomadoCore
import Vision
import XCTest
@testable import KomadoHost

final class PairingQRCodeTests: XCTestCase {
    @MainActor func testGeneratedQRCodeCanBeDecoded() throws {
        let invitation = PairingInvitation(name: "仕事用 Mac 👩‍💻", host: "192.0.2.1", port: 48365,
            fingerprint: String(repeating: "A", count: 64), token: String(repeating: "B", count: 64))
        let data = try invitation.encode()
        let image = try XCTUnwrap(PairingQRCode.image(for: data))
        let request = VNDetectBarcodesRequest()
        request.symbologies = [.qr]
        try VNImageRequestHandler(cgImage: image).perform([request])
        let decoded = try XCTUnwrap(request.results?.first?.payloadStringValue)
        XCTAssertEqual(try PairingInvitation.decode(Data(decoded.utf8)), invitation)
        XCTAssertNil(PairingQRCode.image(for: Data()))
    }
}
