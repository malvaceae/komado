package me.mallows.komado

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardChangeGuardTest {
    @Test fun ignoresClassificationOnlyAndRejectsAmbiguousChanges() {
        val stable = ClipboardChangeGuard(123)
        stable.afterRegistration(123)
        stable.onNotification(123)
        assertFalse(stable.changed)
        stable.onNotification(124)
        assertTrue(stable.changed)
        val racing = ClipboardChangeGuard(123)
        racing.afterRegistration(124)
        assertTrue(racing.changed)
        val unknown = ClipboardChangeGuard(null)
        unknown.afterRegistration(null)
        assertFalse(unknown.changed)
        unknown.onNotification(null)
        assertTrue(unknown.changed)
        val zero = ClipboardChangeGuard(0)
        zero.onNotification(0)
        assertTrue(zero.changed)
    }
}
