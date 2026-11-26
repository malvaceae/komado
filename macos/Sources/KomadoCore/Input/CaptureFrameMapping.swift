import CoreGraphics

/// Maps normalized video-frame coordinates into the actual captured content.
/// Both the frame size and content rectangle are expressed in output pixels.
public struct CaptureFrameMapping: Equatable, Sendable {
    private let frame: CGRect
    private let content: CGRect

    public init?(frameSize: CGSize, contentRect: CGRect) {
        let values = [frameSize.width, frameSize.height, contentRect.minX, contentRect.minY,
                      contentRect.width, contentRect.height, contentRect.maxX, contentRect.maxY]
        guard values.allSatisfy(\.isFinite), frameSize.width > 0, frameSize.height > 0,
              contentRect.width > 0, contentRect.height > 0, contentRect.minX >= 0, contentRect.minY >= 0,
              contentRect.maxX <= frameSize.width, contentRect.maxY <= frameSize.height else { return nil }
        frame = CGRect(origin: .zero, size: frameSize)
        content = contentRect
    }

    public func normalizedContentPoint(x: Double, y: Double) -> CGPoint? {
        guard x.isFinite, y.isFinite, (0...1).contains(x), (0...1).contains(y) else { return nil }
        let point = CGPoint(x: CGFloat(x) * frame.width, y: CGFloat(y) * frame.height)
        guard point.x >= content.minX, point.x <= content.maxX,
              point.y >= content.minY, point.y <= content.maxY else { return nil }
        return CGPoint(x: (point.x - content.minX) / content.width,
                       y: (point.y - content.minY) / content.height)
    }
}
