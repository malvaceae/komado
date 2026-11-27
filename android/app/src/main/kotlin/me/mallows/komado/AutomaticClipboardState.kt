package me.mallows.komado

/** Tracks metadata only. Unknown timestamps are never treated as transferable changes. */
internal class AutomaticClipboardState(initialTimestamp: Long?) {
    var timestamp: Long? = initialTimestamp?.takeIf { it > 0 }
        private set
    var dirty: Boolean = false
        private set

    fun reset(value: Long?) {
        timestamp = value?.takeIf { it > 0 }
        dirty = false
    }

    fun changed(value: Long?, allowed: Boolean) {
        val next = value?.takeIf { it > 0 }
        if (next == null) {
            timestamp = null
            dirty = false
        } else if (next != timestamp) {
            timestamp = next
            dirty = allowed
        } else if (!allowed) {
            dirty = false
        }
    }

    fun matches(value: Long?): Boolean = timestamp != null && value == timestamp
    fun delivered(value: Long?) { if (matches(value)) dirty = false }
}
