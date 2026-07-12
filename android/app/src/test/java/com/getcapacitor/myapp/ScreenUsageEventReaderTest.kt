package com.getcapacitor.myapp

import com.randomwaterreminder.app.ScreenUsageEventReader
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenUsageEventReaderTest {
    private val hour = 60L * 60L * 1_000L

    @Test fun firstEventBeingScreenOffDoesNotAssumeScreenWasOnSinceMidnight() {
        val dayStart = 100 * hour
        val metrics = ScreenUsageEventReader.calculateTodayMetrics(
            listOf(ScreenUsageEventReader.ScreenEvent(dayStart + 8 * hour, "off")),
            dayStart,
            dayStart + 12 * hour,
        )
        assertEquals(0, metrics.screenOnCount)
        assertEquals(0L, metrics.screenOnDurationMs)
    }

    @Test fun eventBeforeMidnightEstablishesTheRealStartingState() {
        val dayStart = 100 * hour
        val metrics = ScreenUsageEventReader.calculateTodayMetrics(
            listOf(
                ScreenUsageEventReader.ScreenEvent(dayStart - hour, "on"),
                ScreenUsageEventReader.ScreenEvent(dayStart + 8 * hour, "off"),
            ),
            dayStart,
            dayStart + 12 * hour,
        )
        assertEquals(1, metrics.screenOnCount)
        assertEquals(8 * hour, metrics.screenOnDurationMs)
    }
}
