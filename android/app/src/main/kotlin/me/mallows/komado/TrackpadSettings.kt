package me.mallows.komado

import android.content.Context

/** Percentages are bounded even if stored preferences are corrupted. */
internal class TrackpadSettings(context: Context, name: String = "trackpad_settings") {
    data class Values(val cursor: Int, val scroll: Int)
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun load(): Values = Values(runCatching { preferences.getInt("cursor", 100) }.getOrDefault(100).coerceIn(50, 200),
                               runCatching { preferences.getInt("scroll", 100) }.getOrDefault(100).coerceIn(50, 200))

    fun save(values: Values) {
        require(values.cursor in 50..200 && values.scroll in 50..200)
        preferences.edit().putInt("cursor", values.cursor).putInt("scroll", values.scroll).apply()
    }
}
