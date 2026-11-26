import CoreGraphics

/// The captured region in the global Core Graphics coordinate space, in points.
/// A revision identifies the capture geometry used to produce a video frame.
public struct CaptureGeometry: Sendable {
    public let revision: UInt64
    public let bounds: CGRect

    public init?(revision: UInt64, bounds: CGRect) {
        guard bounds.origin.x.isFinite, bounds.origin.y.isFinite,
              bounds.size.width.isFinite, bounds.size.height.isFinite,
              bounds.size.width > 0, bounds.size.height > 0,
              bounds.maxX.isFinite, bounds.maxY.isFinite,
              bounds.maxX > bounds.minX, bounds.maxY > bounds.minY else {
            return nil
        }
        self.revision = revision
        self.bounds = bounds
    }

    /// Maps a point in the captured image to global coordinates. The right and
    /// bottom edges are kept inside the region. Rejects stale or invalid input.
    public func point(x: Double, y: Double, captureRevision: UInt64) -> CGPoint? {
        guard captureRevision == revision,
              x.isFinite, y.isFinite,
              (0...1).contains(x), (0...1).contains(y) else {
            return nil
        }

        let globalX = min(bounds.minX + CGFloat(x) * bounds.width, bounds.maxX.nextDown)
        let globalY = min(bounds.minY + CGFloat(y) * bounds.height, bounds.maxY.nextDown)
        return CGPoint(x: globalX, y: globalY)
    }
}
