import CoreImage
import CoreImage.CIFilterBuiltins
import CoreGraphics
import Foundation

/// Produces an in-memory QR image, including a four-module white margin.
@MainActor
enum PairingQRCode {
    private static let context = CIContext()

    static func image(for data: Data) -> CGImage? {
        guard !data.isEmpty, data.count <= 1024 else { return nil }
        let filter = CIFilter.qrCodeGenerator()
        filter.message = data
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scale = CGFloat(8), margin = scale * 4
        let code = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
            .transformed(by: CGAffineTransform(translationX: margin, y: margin))
        let bounds = code.extent.insetBy(dx: -margin, dy: -margin)
        let white = CIImage(color: .white).cropped(to: bounds)
        return context.createCGImage(code.composited(over: white), from: bounds)
    }
}
