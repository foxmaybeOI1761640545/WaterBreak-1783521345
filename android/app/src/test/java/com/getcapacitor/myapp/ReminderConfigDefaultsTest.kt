package com.getcapacitor.myapp

import com.randomwaterreminder.app.ReminderConfig
import org.junit.Assert.*
import org.junit.Test

class ReminderConfigDefaultsTest {
    @Test fun defaultsMatchReferenceScreenshots() {
        val config = ReminderConfig()
        assertFalse(config.enabled)
        assertEquals(6, config.startHour)
        assertEquals(35, config.startMinute)
        assertEquals(23, config.endHour)
        assertEquals(45, config.endMinute)
        assertEquals(35, config.minIntervalMinutes)
        assertEquals(45, config.maxIntervalMinutes)
        assertEquals("该喝水啦", config.waterNotificationTitle)
        assertEquals("稳健做人，认真做事。", config.waterNotificationText)
        assertEquals("default", config.waterSoundMode)
        assertEquals(100, config.waterVolumePercent)
        assertEquals(10, config.waterRetryMinutes)
        assertTrue(config.screenLimitEnabled)
        assertEquals(5, config.screenOnLimitMinutes)
        assertEquals(5, config.requiredScreenOffMinutes)
        assertEquals(5, config.cancelBeforeLockCount)
        assertEquals("default", config.screenSoundMode)
        assertEquals(100, config.screenVolumePercent)
    }
}
