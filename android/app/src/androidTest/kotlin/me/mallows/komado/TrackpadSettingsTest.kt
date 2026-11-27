package me.mallows.komado

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TrackpadSettingsTest {
    @Test fun settingsPersistAndInvalidStoredValuesAreBounded() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "komado-test-trackpad-${UUID.randomUUID()}"
        try {
            val settings = TrackpadSettings(context, name)
            assertEquals(TrackpadSettings.Values(100, 100), settings.load())
            settings.save(TrackpadSettings.Values(150, 50))
            assertEquals(TrackpadSettings.Values(150, 50), TrackpadSettings(context, name).load())
            assertThrows(IllegalArgumentException::class.java) { settings.save(TrackpadSettings.Values(49, 100)) }
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
                .putString("cursor", "invalid").putInt("scroll", 999).commit()
            assertEquals(TrackpadSettings.Values(100, 200), settings.load())
        } finally { context.deleteSharedPreferences(name) }
    }
}
