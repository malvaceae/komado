package me.mallows.komado

/** Ignore classification updates while refusing changes we cannot distinguish. */
internal class ClipboardChangeGuard(private val initialTimestamp: Long?) {
    var changed = false
        private set

    fun afterRegistration(timestamp: Long?) {
        if (timestamp != initialTimestamp) changed = true
    }

    fun onNotification(timestamp: Long?) {
        if (initialTimestamp == null || initialTimestamp <= 0 || timestamp == null || timestamp != initialTimestamp) changed = true
    }
}
