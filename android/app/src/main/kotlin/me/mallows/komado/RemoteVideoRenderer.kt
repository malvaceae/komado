package me.mallows.komado

import android.content.Context
import android.os.Looper
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/** Owns the view and EGL resources for one remote video display. */
internal class RemoteVideoRenderer(
    context: Context,
    events: RendererCommon.RendererEvents? = null,
) : AutoCloseable {
    val view = SurfaceViewRenderer(context)
    private val egl: EglBase
    val eglContext: EglBase.Context get() = egl.eglBaseContext
    private var track: VideoTrack? = null
    private var closed = false

    init {
        requireMainThread()
        egl = EglBase.create()
        try {
            view.init(egl.eglBaseContext, events)
            view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            view.setMirror(false)
        } catch (error: Throwable) {
            egl.release()
            throw error
        }
    }

    fun show(next: VideoTrack?) {
        requireMainThread()
        check(!closed) { "Renderer is closed" }
        if (track === next) return
        track?.removeSink(view)
        track = null
        view.clearImage()
        next?.addSink(view)
        track = next
    }

    override fun close() {
        requireMainThread()
        if (closed) return
        closed = true
        track?.removeSink(view)
        track = null
        view.release()
        egl.release()
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Renderer must be managed on the main thread" }
    }
}
