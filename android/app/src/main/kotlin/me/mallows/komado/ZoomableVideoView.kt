package me.mallows.komado

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.KeyEvent
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.FrameLayout
import me.mallows.komado.input.VideoZoom
import me.mallows.komado.input.VideoViewport
import me.mallows.komado.protocol.PointerClick
import me.mallows.komado.protocol.PointerDrag
import org.webrtc.SurfaceViewRenderer
import kotlin.math.abs
import kotlin.math.hypot

/** A clipped video viewport with pinch zoom and one-finger panning. */
internal class ZoomableVideoView(context: Context, private val video: SurfaceViewRenderer) : FrameLayout(context) {
    private val zoom = VideoZoom()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val doubleTapSlop = ViewConfiguration.get(context).scaledDoubleTapSlop
    private var frameWidth = 0
    private var frameHeight = 0
    private var frameRotation = 0
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var previousTapTime = 0L
    private var previousTapX = 0f
    private var previousTapY = 0f
    private var dragging = false
    private var dragAttempted = false
    private var dragX = 0f
    private var dragY = 0f
    private var trackpadMode = false
    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorReady = false
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f * resources.displayMetrics.density }
    private val cursorOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 4f * resources.displayMetrics.density }
    private var mouseButton: PointerClick.Button? = null
    private enum class TwoFingerMode { NONE, PENDING, PINCH, SCROLL }
    private var twoFingerMode = TwoFingerMode.NONE
    private var initialSpan = 0f
    private var initialFocusX = 0f
    private var initialFocusY = 0f
    private var scrollX = 0f
    private var scrollY = 0f
    var onVideoTap: ((Float, Float) -> Unit)? = null
    var onVideoLongPress: ((Float, Float) -> Unit)? = null
    var onVideoDoubleTap: ((Float, Float) -> Unit)? = null
    var onVideoScroll: ((Float, Float, Float, Float) -> Unit)? = null
    var onVideoDrag: ((PointerDrag.Phase, Float, Float) -> Boolean)? = null
    var onVideoHover: ((Float, Float) -> Unit)? = null
    var onMouseClick: ((Float, Float, PointerClick.Button) -> Unit)? = null
    private var hoverScheduled = false
    private var hoverX = 0f
    private var hoverY = 0f
    private val sendHover = Runnable {
        hoverScheduled = false
        onVideoHover?.invoke(hoverX, hoverY)
    }
    private val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (twoFingerMode == TwoFingerMode.PINCH) {
                zoom.zoom(detector.scaleFactor.toDouble(), detector.focusX.toDouble(), detector.focusY.toDouble())
                applyTransform()
            }
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    init {
        clipChildren = true
        addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        contentDescription = context.getString(R.string.video_description)
        isFocusable = true
    }

    fun supportsTrackpad(): Boolean = frameWidth > 0 && frameHeight > 0 && frameRotation == 0

    fun setTrackpadMode(enabled: Boolean) {
        if (trackpadMode == enabled) return
        if (dragging) finishDrag(dragX, dragY)
        removeCallbacks(sendHover); hoverScheduled = false
        pointerId = MotionEvent.INVALID_POINTER_ID; previousTapTime = 0
        trackpadMode = enabled
        cursorReady = false
        if (enabled && clampCursor(width / 2f, height / 2f)) scheduleHover(cursorX, cursorY)
        invalidate()
    }

    private fun clampCursor(x: Float, y: Float): Boolean {
        if (frameRotation != 0) { cursorReady = false; return false }
        val viewport = VideoViewport.create(width.toDouble(), height.toDouble(), frameWidth.toDouble(), frameHeight.toDouble(),
            zoom.scale, zoom.panX, zoom.panY, 1) ?: run { cursorReady = false; return false }
        val point = viewport.clampCursor(x.toDouble(), y.toDouble()) ?: run { cursorReady = false; return false }
        cursorX = point.first.toFloat(); cursorY = point.second.toFloat(); cursorReady = true
        invalidate()
        return true
    }

    private fun scheduleHover(x: Float, y: Float) {
        hoverX = x; hoverY = y
        if (!hoverScheduled) { hoverScheduled = true; postOnAnimation(sendHover) }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (trackpadMode && cursorReady) {
            val radius = 7f * resources.displayMetrics.density
            canvas.drawCircle(cursorX, cursorY, radius, cursorOutline)
            canvas.drawCircle(cursorX, cursorY, radius, cursorPaint)
        }
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

    fun mapTap(x: Float, y: Float, revision: Long?, mapper: TapClickMapper,
               button: PointerClick.Button = PointerClick.Button.LEFT, count: Int = 1): PointerClick? =
        mapper.click(x.toDouble(), y.toDouble(), width, height, frameWidth, frameHeight,
            frameRotation, revision, zoom.scale, zoom.panX, zoom.panY, button, count)

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
        if (trackpadMode) {
            val hadCursor = cursorReady
            if (clampCursor(if (hadCursor) cursorX else width / 2f, if (hadCursor) cursorY else height / 2f) && !dragging) {
                scheduleHover(cursorX, cursorY)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return onMouseTouch(event)
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && dragging) finishDrag(dragX, dragY)
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount == 2) {
            initialFocusX = (event.getX(0) + event.getX(1)) / 2
            initialFocusY = (event.getY(0) + event.getY(1)) / 2
            initialSpan = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
            scrollX = initialFocusX; scrollY = initialFocusY
            twoFingerMode = TwoFingerMode.PENDING
        } else if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount > 2) {
            twoFingerMode = TwoFingerMode.NONE
        } else if (event.actionMasked == MotionEvent.ACTION_MOVE && event.pointerCount == 2 && twoFingerMode != TwoFingerMode.NONE) {
            val x = (event.getX(0) + event.getX(1)) / 2
            val y = (event.getY(0) + event.getY(1)) / 2
            val span = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
            if (twoFingerMode == TwoFingerMode.PENDING) {
                val sizeChange = abs(span - initialSpan) / 2
                val movement = hypot(x - initialFocusX, y - initialFocusY)
                if (sizeChange > touchSlop && sizeChange >= movement) twoFingerMode = TwoFingerMode.PINCH
                else if (movement > touchSlop) twoFingerMode = TwoFingerMode.SCROLL
            }
            if (twoFingerMode == TwoFingerMode.SCROLL) {
                if (!trackpadMode || cursorReady) onVideoScroll?.invoke(if (trackpadMode) cursorX else x, if (trackpadMode) cursorY else y, x - scrollX, y - scrollY)
                scrollX = x; scrollY = y
            }
        }
        detector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (trackpadMode) { removeCallbacks(sendHover); hoverScheduled = false }
                pointerId = event.getPointerId(0)
                lastX = event.x
                lastY = event.y
                moved = false
                dragging = false
                dragAttempted = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> { pointerId = MotionEvent.INVALID_POINTER_ID; moved = true; previousTapTime = 0 }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (!detector.isInProgress && index >= 0) {
                    val x = event.getX(index)
                    val y = event.getY(index)
                    val dx = x - lastX
                    val dy = y - lastY
                    if (!moved && onVideoDrag != null && event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout() &&
                        (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        moved = true
                        dragAttempted = true
                        previousTapTime = 0
                        val startX = if (trackpadMode) cursorX else lastX
                        val startY = if (trackpadMode) cursorY else lastY
                        dragging = (!trackpadMode || cursorReady) && onVideoDrag?.invoke(PointerDrag.Phase.BEGIN, startX, startY) == true
                        dragX = startX; dragY = startY
                    }
                    if (trackpadMode) {
                        if (moved || abs(dx) > touchSlop || abs(dy) > touchSlop) {
                            moved = true; previousTapTime = 0
                            if (cursorReady && clampCursor(cursorX + dx, cursorY + dy)) {
                                if (dragging) {
                                    if (onVideoDrag?.invoke(PointerDrag.Phase.MOVE, cursorX, cursorY) == true) {
                                        dragX = cursorX; dragY = cursorY
                                    } else finishDrag(dragX, dragY)
                                } else if (!dragAttempted) scheduleHover(cursorX, cursorY)
                            } else if (dragging) finishDrag(dragX, dragY)
                            lastX = x; lastY = y
                        }
                    } else if (dragging) {
                        if (onVideoDrag?.invoke(PointerDrag.Phase.MOVE, x, y) == true) {
                            dragX = x; dragY = y
                        } else { finishDrag(dragX, dragY) }
                    } else if (!dragAttempted && (moved || abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        moved = true
                        previousTapTime = 0
                        zoom.pan(dx.toDouble(), dy.toDouble())
                        applyTransform()
                        lastX = x
                        lastY = y
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                twoFingerMode = TwoFingerMode.NONE
                val remaining = if (event.actionIndex == 0) 1 else 0
                if (trackpadMode) pointerId = MotionEvent.INVALID_POINTER_ID
                else if (remaining < event.pointerCount) {
                    pointerId = event.getPointerId(remaining)
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                }
                moved = true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) finishDrag(if (trackpadMode) cursorX else event.x, if (trackpadMode) cursorY else event.y)
                val clickX = if (trackpadMode) cursorX else event.x
                val clickY = if (trackpadMode) cursorY else event.y
                if ((!trackpadMode || cursorReady) && !moved && pointerId != MotionEvent.INVALID_POINTER_ID &&
                    abs(event.x - lastX) <= touchSlop && abs(event.y - lastY) <= touchSlop) {
                    if (event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()) {
                        previousTapTime = 0
                        onVideoLongPress?.invoke(clickX, clickY)
                    } else {
                        performClick()
                        val sinceLast = event.eventTime - previousTapTime
                        if (previousTapTime != 0L && sinceLast in 1..ViewConfiguration.getDoubleTapTimeout().toLong() &&
                            hypot(event.x - previousTapX, event.y - previousTapY) <= doubleTapSlop) {
                            previousTapTime = 0
                            onVideoDoubleTap?.invoke(clickX, clickY)
                        } else {
                            previousTapTime = event.eventTime
                            previousTapX = event.x; previousTapY = event.y
                            onVideoTap?.invoke(clickX, clickY)
                        }
                    }
                } else { previousTapTime = 0 }
                pointerId = MotionEvent.INVALID_POINTER_ID
                twoFingerMode = TwoFingerMode.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) finishDrag(dragX, dragY)
                pointerId = MotionEvent.INVALID_POINTER_ID; twoFingerMode = TwoFingerMode.NONE; previousTapTime = 0
            }
        }
        return true
    }

    private fun onMouseTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(sendHover)
                hoverScheduled = false
                mouseButton = when (event.buttonState) {
                    MotionEvent.BUTTON_PRIMARY -> PointerClick.Button.LEFT
                    MotionEvent.BUTTON_SECONDARY -> PointerClick.Button.RIGHT
                    MotionEvent.BUTTON_TERTIARY -> PointerClick.Button.MIDDLE
                    else -> null
                }
                lastX = event.x; lastY = event.y
                moved = false; dragging = false; dragAttempted = false
                if (mouseButton != PointerClick.Button.LEFT) previousTapTime = 0
            }
            MotionEvent.ACTION_MOVE -> {
                val expected = when (mouseButton) {
                    PointerClick.Button.LEFT -> MotionEvent.BUTTON_PRIMARY
                    PointerClick.Button.RIGHT -> MotionEvent.BUTTON_SECONDARY
                    PointerClick.Button.MIDDLE -> MotionEvent.BUTTON_TERTIARY
                    null -> 0
                }
                if (expected == 0 || event.buttonState != expected) {
                    if (dragging) finishDrag(dragX, dragY)
                    mouseButton = null; moved = true; previousTapTime = 0
                } else if (!moved && (abs(event.x - lastX) > touchSlop || abs(event.y - lastY) > touchSlop)) {
                    moved = true; previousTapTime = 0
                    if (mouseButton == PointerClick.Button.LEFT && onVideoDrag != null) {
                        dragAttempted = true
                        dragging = onVideoDrag?.invoke(PointerDrag.Phase.BEGIN, lastX, lastY) == true
                        dragX = lastX; dragY = lastY
                    }
                }
                if (dragging) {
                    if (onVideoDrag?.invoke(PointerDrag.Phase.MOVE, event.x, event.y) == true) {
                        dragX = event.x; dragY = event.y
                    } else finishDrag(dragX, dragY)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) finishDrag(event.x, event.y)
                val button = if (event.buttonState == 0) mouseButton else null
                if (!moved && button != null && abs(event.x - lastX) <= touchSlop && abs(event.y - lastY) <= touchSlop) {
                    if (button == PointerClick.Button.LEFT) {
                        performClick()
                        val sinceLast = event.eventTime - previousTapTime
                        if (previousTapTime != 0L && sinceLast in 1..ViewConfiguration.getDoubleTapTimeout().toLong() &&
                            hypot(event.x - previousTapX, event.y - previousTapY) <= doubleTapSlop) {
                            previousTapTime = 0
                            onVideoDoubleTap?.invoke(event.x, event.y)
                        } else {
                            previousTapTime = event.eventTime; previousTapX = event.x; previousTapY = event.y
                            onVideoTap?.invoke(event.x, event.y)
                        }
                    } else onMouseClick?.invoke(event.x, event.y, button)
                } else previousTapTime = 0
                mouseButton = null
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) finishDrag(dragX, dragY)
                mouseButton = null; previousTapTime = 0
            }
        }
        return true
    }

    private fun finishDrag(x: Float, y: Float) {
        if (!dragging) return
        if (onVideoDrag?.invoke(PointerDrag.Phase.END, x, y) != true && (x != dragX || y != dragY)) {
            onVideoDrag?.invoke(PointerDrag.Phase.END, dragX, dragY)
        }
        dragging = false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) && event.actionMasked == MotionEvent.ACTION_SCROLL &&
            mouseButton == null && onVideoScroll != null) {
            val config = ViewConfiguration.get(context)
            val dx = -event.getAxisValue(MotionEvent.AXIS_HSCROLL) * config.scaledHorizontalScrollFactor
            val dy = event.getAxisValue(MotionEvent.AXIS_VSCROLL) * config.scaledVerticalScrollFactor
            if (dx.isFinite() && dy.isFinite() && (dx != 0f || dy != 0f)) {
                onVideoScroll?.invoke(event.x, event.y, dx, dy)
            }
            return true
        }
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) && event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS && mouseButton != null) {
            val expected = when (mouseButton) {
                PointerClick.Button.LEFT -> MotionEvent.BUTTON_PRIMARY
                PointerClick.Button.RIGHT -> MotionEvent.BUTTON_SECONDARY
                PointerClick.Button.MIDDLE -> MotionEvent.BUTTON_TERTIARY
                null -> 0
            }
            if (event.buttonState != expected) {
                if (dragging) finishDrag(dragX, dragY)
                mouseButton = null; moved = true; previousTapTime = 0
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) && onVideoHover != null) {
            if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                if (trackpadMode) clampCursor(event.x, event.y)
                scheduleHover(event.x, event.y)
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
                removeCallbacks(sendHover)
                hoverScheduled = false
            }
        }
        return super.onHoverEvent(event)
    }


    override fun onDetachedFromWindow() {
        removeCallbacks(sendHover)
        hoverScheduled = false
        super.onDetachedFromWindow()
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
