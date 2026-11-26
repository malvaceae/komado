package me.mallows.komado.input

import kotlin.math.max
import kotlin.math.min

/** View-space transform for an aspect-fitted video. */
class VideoZoom {
    var scale = 1.0
        private set
    var panX = 0.0
        private set
    var panY = 0.0
        private set
    private var viewWidth = 0.0
    private var viewHeight = 0.0
    private var fittedWidth = 0.0
    private var fittedHeight = 0.0

    fun layout(width: Int, height: Int, videoWidth: Int, videoHeight: Int, rotation: Int): Boolean {
        if (width <= 0 || height <= 0 || videoWidth <= 0 || videoHeight <= 0 || rotation !in listOf(0, 90, 180, 270)) {
            viewWidth = 0.0
            viewHeight = 0.0
            reset()
            return false
        }
        val rotated = rotation == 90 || rotation == 270
        val w = (if (rotated) videoHeight else videoWidth).toDouble()
        val h = (if (rotated) videoWidth else videoHeight).toDouble()
        viewWidth = width.toDouble()
        viewHeight = height.toDouble()
        val fit = min(viewWidth / w, viewHeight / h)
        fittedWidth = w * fit
        fittedHeight = h * fit
        clampPan()
        return true
    }

    fun zoom(factor: Double, focusX: Double, focusY: Double) {
        if (viewWidth <= 0 || !factor.isFinite() || factor <= 0 || !focusX.isFinite() || !focusY.isFinite()) return
        val next = (scale * factor).coerceIn(1.0, 4.0)
        val ratio = next / scale
        panX = focusX - viewWidth / 2 - (focusX - viewWidth / 2 - panX) * ratio
        panY = focusY - viewHeight / 2 - (focusY - viewHeight / 2 - panY) * ratio
        scale = next
        clampPan()
    }

    fun pan(dx: Double, dy: Double) {
        if (viewWidth <= 0 || !dx.isFinite() || !dy.isFinite()) return
        panX += dx
        panY += dy
        clampPan()
    }

    fun reset() {
        scale = 1.0
        panX = 0.0
        panY = 0.0
    }

    private fun clampPan() {
        val maxX = max(0.0, (fittedWidth * scale - viewWidth) / 2)
        val maxY = max(0.0, (fittedHeight * scale - viewHeight) / 2)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }
}
