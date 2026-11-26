package me.mallows.komado

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.FrameLayout
import me.mallows.komado.input.VideoZoom
import org.webrtc.SurfaceViewRenderer
import kotlin.math.abs

/** A clipped video viewport with pinch zoom and one-finger panning. */
internal class ZoomableVideoView(context: Context, private val video: SurfaceViewRenderer) : FrameLayout(context) {
    private val zoom = VideoZoom()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var frameWidth = 0
    private var frameHeight = 0
    private var frameRotation = 0
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom.zoom(detector.scaleFactor.toDouble(), detector.focusX.toDouble(), detector.focusY.toDouble())
            applyTransform()
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    init {
        clipChildren = true
        addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        contentDescription = context.getString(R.string.video_description)
        isFocusable = true
    }

    fun setFrameSize(width: Int, height: Int, rotation: Int): Boolean {
        frameWidth = width
        frameHeight = height
        frameRotation = rotation
        return updateGeometry()
    }

    fun zoomBy(factor: Double) {
        zoom.zoom(factor, width / 2.0, height / 2.0)
        applyTransform()
    }

    fun resetZoom() {
        zoom.reset()
        applyTransform()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateGeometry()
    }

    private fun updateGeometry(): Boolean {
        val ready = zoom.layout(width, height, frameWidth, frameHeight, frameRotation)
        applyTransform()
        return ready
    }

    private fun applyTransform() {
        video.pivotX = video.width / 2f
        video.pivotY = video.height / 2f
        video.scaleX = zoom.scale.toFloat()
        video.scaleY = zoom.scale.toFloat()
        video.translationX = zoom.panX.toFloat()
        video.translationY = zoom.panY.toFloat()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(0)
                lastX = event.x
                lastY = event.y
                moved = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> { pointerId = MotionEvent.INVALID_POINTER_ID; moved = true }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (!detector.isInProgress && index >= 0) {
                    val x = event.getX(index)
                    val y = event.getY(index)
                    val dx = x - lastX
                    val dy = y - lastY
                    if (moved || abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        moved = true
                        zoom.pan(dx.toDouble(), dy.toDouble())
                        applyTransform()
                        lastX = x
                        lastY = y
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                if (remaining < event.pointerCount) {
                    pointerId = event.getPointerId(remaining)
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                }
                moved = true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) performClick()
                pointerId = MotionEvent.INVALID_POINTER_ID
            }
            MotionEvent.ACTION_CANCEL -> pointerId = MotionEvent.INVALID_POINTER_ID
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (zoom.scale <= 1.0) return super.onKeyDown(keyCode, event)
        val step = 48.0 * resources.displayMetrics.density
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> zoom.pan(step, 0.0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> zoom.pan(-step, 0.0)
            KeyEvent.KEYCODE_DPAD_UP -> zoom.pan(0.0, step)
            KeyEvent.KEYCODE_DPAD_DOWN -> zoom.pan(0.0, -step)
            else -> return super.onKeyDown(keyCode, event)
        }
        applyTransform()
        return true
    }
}
