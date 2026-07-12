package com.randomwaterreminder.app

import android.content.Context
import java.io.File
import java.util.Calendar
import java.util.TimeZone

data class ScreenDayMetrics(val screenOnCount: Int, val screenOnDurationMs: Long)

/**
 * Append-only screen transition log. Each line contains a ten-digit Unix
 * timestamp in seconds and an event type: `timestamp,on` or `timestamp,off`.
 * Unix timestamps are timezone-neutral; day boundaries are evaluated in
 * Asia/Shanghai so exported values consistently map to Beijing time.
 */
object ScreenEventLog {
    private const val FILE_NAME = "screen-events.log"
    private val lock = Any()
    private val beijingTimeZone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    fun append(context: Context, timestampMillis: Long, type: String) {
        appendAll(context, listOf(ScreenUsageEventReader.ScreenEvent(timestampMillis, type)))
    }

    fun appendAll(context: Context, source: List<ScreenUsageEventReader.ScreenEvent>) = synchronized(lock) {
        if (source.isEmpty()) return@synchronized
        val file = logFile(context)
        val existing = readEvents(file)
        val existingEvents = existing.toHashSet()
        var lastSeconds = existing.lastOrNull()?.first ?: 0L
        var lastType = existing.lastOrNull()?.second.orEmpty()
        val additions = StringBuilder()
        for (event in source.sortedBy { it.timestampMillis }) {
            if (event.type !in setOf("on", "off")) continue
            val seconds = event.timestampMillis / 1_000L
            if (seconds <= 0L || seconds < lastSeconds) continue
            if ((seconds to event.type) in existingEvents) continue
            if (seconds == lastSeconds && event.type == lastType) continue
            additions.append(seconds).append(',').append(event.type).append('\n')
            lastSeconds = seconds
            lastType = event.type
        }
        if (additions.isNotEmpty()) file.appendText(additions.toString(), Charsets.UTF_8)
    }

    fun lastTimestampMillis(context: Context): Long = synchronized(lock) {
        (readEvents(logFile(context)).lastOrNull()?.first ?: 0L) * 1_000L
    }

    fun todayMetrics(context: Context, now: Long = System.currentTimeMillis()): ScreenDayMetrics = synchronized(lock) {
        val start = dayStart(now)
        val events = readEvents(logFile(context))
        var state = events.lastOrNull { it.first * 1_000L <= start }?.second ?: "unknown"
        var onSince = start
        var count = if (state == "on") 1 else 0
        var duration = 0L
        for ((seconds, type) in events) {
            val timestamp = seconds * 1_000L
            if (timestamp <= start || timestamp > now || type == state) continue
            if (state == "on") duration += (timestamp - onSince).coerceAtLeast(0L)
            state = type
            if (state == "on") {
                onSince = timestamp
                count += 1
            }
        }
        if (state == "on") duration += (now - onSince).coerceAtLeast(0L)
        ScreenDayMetrics(count, duration.coerceIn(0L, now - start))
    }

    fun offDurationBetween(context: Context, start: Long, end: Long): Long = synchronized(lock) {
        if (start <= 0L || end <= start) return@synchronized 0L
        val events = readEvents(logFile(context))
        var state = events.lastOrNull { it.first * 1_000L <= start }?.second ?: "unknown"
        var offSince = start
        var duration = 0L
        for ((seconds, type) in events) {
            val timestamp = seconds * 1_000L
            if (timestamp <= start || timestamp > end || type == state) continue
            if (state == "off") duration += (timestamp - offSince).coerceAtLeast(0L)
            state = type
            if (state == "off") offSince = timestamp
        }
        if (state == "off") duration += (end - offSince).coerceAtLeast(0L)
        duration.coerceIn(0L, end - start)
    }

    private fun dayStart(timestamp: Long): Long = Calendar.getInstance(beijingTimeZone).apply {
        timeInMillis = timestamp
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun logFile(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME).apply {
        if (!exists()) createNewFile()
    }

    private fun readEvents(file: File): List<Pair<Long, String>> = runCatching {
        file.useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.trim().split(',', limit = 2)
                val seconds = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                val type = parts.getOrNull(1)?.takeIf { it in setOf("on", "off") } ?: return@mapNotNull null
                seconds to type
            }.toList()
        }
    }.getOrDefault(emptyList())
}
