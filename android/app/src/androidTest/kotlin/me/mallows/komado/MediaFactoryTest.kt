package me.mallows.komado

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaFactoryTest {
    @Test
    fun createsNativeWebRtcFactory() {
        val factory = MediaFactory.create(ApplicationProvider.getApplicationContext())
        try {
            assertNotNull(factory)
        } finally {
            factory.dispose()
        }
    }
}
