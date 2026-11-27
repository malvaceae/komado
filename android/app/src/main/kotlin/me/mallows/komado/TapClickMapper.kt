package me.mallows.komado

import me.mallows.komado.input.VideoViewport
import me.mallows.komado.protocol.PointerClick
import me.mallows.komado.protocol.PointerClickCodec

/** Converts taps in an aspect-fitted view into ordered capture-relative clicks. */
internal class TapClickMapper(private val sequence: ControlSequence = ControlSequence()) {

    fun click(x: Double, y: Double, viewWidth: Int, viewHeight: Int, frameWidth: Int, frameHeight: Int,
              frameRotation: Int, captureRevision: Long?, zoom: Double = 1.0, panX: Double = 0.0, panY: Double = 0.0,
              button: PointerClick.Button = PointerClick.Button.LEFT): PointerClick? {
        // Rotated WebRTC frames need a separately verified coordinate transform.
        if (frameRotation != 0 || captureRevision == null || captureRevision !in 1..PointerClickCodec.MAX_SEQUENCE) return null
        val viewport = VideoViewport.create(viewWidth.toDouble(), viewHeight.toDouble(), frameWidth.toDouble(), frameHeight.toDouble(),
            zoom = zoom, panX = panX, panY = panY, captureRevision = captureRevision) ?: return null
        val point = viewport.normalize(x, y) ?: return null
        return PointerClick(sequence.next() ?: return null, captureRevision,
            (point.x * 1_000_000).toInt(), (point.y * 1_000_000).toInt(), button)
    }
}
