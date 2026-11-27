package me.mallows.komado.input

import kotlin.math.min
import kotlin.math.max

/** Coordinates relative to the captured image, before conversion on the host. */
data class NormalizedPoint(val x: Double, val y: Double, val captureRevision: Long)

/** A video image fitted inside a view, with zoom and pan applied in view pixels. */
class VideoViewport private constructor(
    private val viewWidth: Double,
    private val viewHeight: Double,
    private val left: Double,
    private val top: Double,
    private val imageWidth: Double,
    private val imageHeight: Double,
    private val captureRevision: Long,
) {
    /** Returns null for invalid touches, letterboxing, or content outside the view. */
    fun normalize(x: Double, y: Double): NormalizedPoint? {
        if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 || x >= viewWidth || y >= viewHeight) return null
        val normalizedX = (x - left) / imageWidth
        val normalizedY = (y - top) / imageHeight
        if (!normalizedX.isFinite() || !normalizedY.isFinite() ||
            normalizedX < 0 || normalizedY < 0 || normalizedX >= 1 || normalizedY >= 1
        ) return null
        return NormalizedPoint(normalizedX, normalizedY, captureRevision)
    }

    /** Clamp a view-space cursor inside the visible part of the image. */
    fun clampCursor(x: Double, y: Double): Pair<Double, Double>? {
        if (!x.isFinite() || !y.isFinite()) return null
        // Select representable float coordinates inside the half-open visible image.
        fun lower(value: Double): Float {
            val rounded = value.toFloat()
            return if (rounded.toDouble() < value) java.lang.Math.nextUp(rounded) else rounded
        }
        fun upper(value: Double): Float {
            val rounded = value.toFloat()
            return if (rounded.toDouble() >= value) java.lang.Math.nextDown(rounded) else rounded
        }
        val minX = lower(max(0.0, left)); val minY = lower(max(0.0, top))
        val maxX = upper(min(viewWidth, left + imageWidth)); val maxY = upper(min(viewHeight, top + imageHeight))
        if (!minX.isFinite() || !minY.isFinite() || !maxX.isFinite() || !maxY.isFinite() || minX > maxX || minY > maxY) return null
        return x.coerceIn(minX.toDouble(), maxX.toDouble()) to y.coerceIn(minY.toDouble(), maxY.toDouble())
    }

    companion object {
        fun create(
            viewWidth: Double,
            viewHeight: Double,
            videoWidth: Double,
            videoHeight: Double,
            zoom: Double,
            panX: Double,
            panY: Double,
            captureRevision: Long,
        ): VideoViewport? {
            val sizes = listOf(viewWidth, viewHeight, videoWidth, videoHeight, zoom)
            if (sizes.any { !it.isFinite() || it <= 0 } || !panX.isFinite() || !panY.isFinite() || captureRevision < 0) return null
            val scale = min(viewWidth / videoWidth, viewHeight / videoHeight) * zoom
            val imageWidth = videoWidth * scale
            val imageHeight = videoHeight * scale
            val left = (viewWidth - imageWidth) / 2 + panX
            val top = (viewHeight - imageHeight) / 2 + panY
            if (!imageWidth.isFinite() || !imageHeight.isFinite() || imageWidth <= 0 || imageHeight <= 0 ||
                !left.isFinite() || !top.isFinite() || !(left + imageWidth).isFinite() || !(top + imageHeight).isFinite()
            ) return null
            return VideoViewport(viewWidth, viewHeight, left, top, imageWidth, imageHeight, captureRevision)
        }
    }
}
