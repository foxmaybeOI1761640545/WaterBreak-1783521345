package com.randomwaterreminder.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.util.Calendar
import java.util.TimeZone

object ScreenUsageEventReader {
    private const val DEFAULT_LOOKBACK_MILLIS = 7L * 24L * 60L * 60L * 1000L

    data class Snapshot(
        val state: String,
        val lastScreenOn: Long,
        val lastScreenOff: Long,
        val stateSince: Long,
    )

    data class TodayMetrics(val screenOnCount: Int, val screenOnDurationMs: Long)
    data class ScreenEvent(val timestampMillis: Long, val type: String)

    fun hasPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun latestSnapshot(
        context: Context,
        now: Long = System.currentTimeMillis(),
        beginAt: Long = now - DEFAULT_LOOKBACK_MILLIS,
    ): Snapshot? {
        if (!hasPermission(context)) return null
        return runCatching {
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val safeBegin = beginAt.coerceAtLeast(0L).coerceAtMost(now)
            val events = manager.queryEvents(safeBegin, now)
            val event = UsageEvents.Event()
            var lastOn = 0L
            var lastOff = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.SCREEN_INTERACTIVE -> lastOn = event.timeStamp
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> lastOff = event.timeStamp
                }
            }
            val state = when {
                lastOn <= 0L && lastOff <= 0L -> return@runCatching null
                lastOn >= lastOff -> "on"
                else -> "off"
            }
            val since = if (state == "on") lastOn else lastOff
            Snapshot(state, lastOn, lastOff, since)
        }.getOrNull()
    }

    fun todayMetrics(context: Context, now: Long = System.currentTimeMillis()): TodayMetrics? {
        if (!hasPermission(context)) return null
        return runCatching {
            val dayStart = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val source = screenEvents(context, now, (dayStart - DEFAULT_LOOKBACK_MILLIS).coerceAtLeast(0L))
            if (source.isEmpty()) return@runCatching null
            calculateTodayMetrics(source, dayStart, now)
        }.getOrNull()
    }

    internal fun calculateTodayMetrics(source: List<ScreenEvent>, dayStart: Long, now: Long): TodayMetrics {
        var state = "unknown"
        var onSince = dayStart
        var onCount = 0
        var duration = 0L
        var dayInitialized = false
        for (event in source.sortedBy { it.timestampMillis }) {
            if (event.timestampMillis < dayStart) {
                state = event.type
                continue
            }
            if (event.timestampMillis > now) continue
            if (!dayInitialized) {
                dayInitialized = true
                if (state == "on") {
                    onSince = dayStart
                    onCount = 1
                }
            }
            when (event.type) {
                "on" -> if (state != "on") {
                    state = "on"
                    onSince = event.timestampMillis.coerceIn(dayStart, now)
                    onCount += 1
                }
                "off" -> {
                    val endedAt = event.timestampMillis.coerceIn(dayStart, now)
                    if (state == "on") duration += (endedAt - onSince).coerceAtLeast(0L)
                    state = "off"
                }
            }
        }
        if (!dayInitialized && state == "on") {
            onCount = 1
            onSince = dayStart
        }
        if (state == "on") duration += (now - onSince).coerceAtLeast(0L)
        return TodayMetrics(onCount, duration.coerceIn(0L, (now - dayStart).coerceAtLeast(0L)))
    }

    fun screenEvents(
        context: Context,
        now: Long = System.currentTimeMillis(),
        beginAt: Long = now - DEFAULT_LOOKBACK_MILLIS,
    ): List<ScreenEvent> {
        if (!hasPermission(context)) return emptyList()
        return runCatching {
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val events = manager.queryEvents(beginAt.coerceAtLeast(0L).coerceAtMost(now), now)
            val event = UsageEvents.Event()
            buildList {
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    when (event.eventType) {
                        UsageEvents.Event.SCREEN_INTERACTIVE -> add(ScreenEvent(event.timeStamp, "on"))
                        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> add(ScreenEvent(event.timeStamp, "off"))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }
}
