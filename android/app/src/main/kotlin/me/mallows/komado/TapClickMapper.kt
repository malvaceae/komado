package me.mallows.komado

import me.mallows.komado.input.VideoViewport
import me.mallows.komado.protocol.PointerClick
import me.mallows.komado.protocol.PointerClickCodec

/** Converts taps in an aspect-fitted view into ordered capture-relative clicks. */
internal class TapClickMapper {
    private var sequence = 0L

    fun click(x: Double, y: Double, viewWidth: Int, viewHeight: Int, frameWidth: Int, frameHeight: Int,
              frameRotation: Int, captureRevision: Long?): PointerClick? {
        // Rotated WebRTC frames need a separately verified coordinate transform.
        if (frameRotation != 0 || captureRevision == null || captureRevision !in 1..PointerClickCodec.MAX_SEQUENCE ||
            sequence == PointerClickCodec.MAX_SEQUENCE) return null
        val viewport = VideoViewport.create(viewWidth.toDouble(), viewHeight.toDouble(), frameWidth.toDouble(), frameHeight.toDouble(),
            zoom = 1.0, panX = 0.0, panY = 0.0, captureRevision = captureRevision) ?: return null
        val point = viewport.normalize(x, y) ?: return null
        sequence++
        return PointerClick(sequence, captureRevision, (point.x * 1_000_000).toInt(), (point.y * 1_000_000).toInt(), PointerClick.Button.LEFT)
    }
}
