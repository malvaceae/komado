import CoreGraphics

/// Rejects replayed and stale coordinates before they reach an input API.
public struct PointerClickValidator {
    private var lastSequence: UInt64 = 0
    public init() {}

    public mutating func point(for click: PointerClick, in geometry: CaptureGeometry, mapping: CaptureFrameMapping) -> CGPoint? {
        guard click.sequence > lastSequence, click.sequence <= PointerClick.maximumSequence,
              (1...PointerClick.maximumSequence).contains(click.captureRevision),
              click.x <= 1_000_000, click.y <= 1_000_000,
              let normalized = mapping.normalizedContentPoint(x: Double(click.x) / 1_000_000, y: Double(click.y) / 1_000_000),
              let point = geometry.point(x: normalized.x, y: normalized.y,
                                         captureRevision: click.captureRevision) else { return nil }
        lastSequence = click.sequence
        return point
    }
}
