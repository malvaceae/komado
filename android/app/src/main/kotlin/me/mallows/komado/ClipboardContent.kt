package me.mallows.komado

import android.content.ClipData
import android.os.PersistableBundle
import me.mallows.komado.protocol.ClipboardCodec

/** Only explicit, plain text is transferred; never resolve URIs or intents. */
internal object ClipboardContent {
    private const val SENSITIVE = "android.content.extra.IS_SENSITIVE"
    private const val REMOTE = "android.content.extra.IS_REMOTE_DEVICE"

    fun text(clip: ClipData?): String? {
        if (clip == null || clip.itemCount != 1) return null
        val item = clip.getItemAt(0)
        if (item.uri != null || item.intent != null) return null
        val raw = item.text ?: return null
        if (raw.length !in 1..ClipboardCodec.MAX_TEXT_BYTES) return null
        val value = raw.toString()
        return value.takeIf(ClipboardCodec::validText)
    }

    fun remote(text: String, label: String): ClipData? {
        if (!ClipboardCodec.validText(text)) return null
        return ClipData.newPlainText(label, text).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(SENSITIVE, true)
                putBoolean(REMOTE, true)
            }
        }
    }
}
