package me.mallows.komado

import android.os.Handler

/** Managed on the handler's thread; a displayed frame or session close cancels the deadline. */
internal class FirstFrameDeadline(
    private val handler: Handler,
    private val delayMillis: Long = 20_000,
    private val onTimeout: () -> Unit,
) {
    private var started = false
    private var finished = false
    private val timeout = Runnable {
        if (!finished) {
            finished = true
            onTimeout()
        }
    }

    fun start() {
        if (!started && !finished) {
            started = true
            handler.postDelayed(timeout, delayMillis)
        }
    }

    fun finish() {
        finished = true
        handler.removeCallbacks(timeout)
    }
}
