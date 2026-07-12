package com.randomwaterreminder.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import java.util.Calendar

object ScreenUsageEventReader {
    private const val DEFAULT_LOOKBACK_MILLIS = 7L * 24L * 60L * 60L * 1000L

    data class Snapshot(
        val state: String,
        val lastScreenOn: Long,
        val lastScreenOff: Long,
        val stateSince: Long,
    )

    data class TodayMetrics(val screenOnCount: Int, val screenOnDurationMs: Long)

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
            val dayStart = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val events = manager.queryEvents(dayStart, now)
            val event = UsageEvents.Event()
            var state = "unknown"
            var onSince = dayStart
            var onCount = 0
            var duration = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.SCREEN_INTERACTIVE -> if (state != "on") {
                        state = "on"
                        onSince = event.timeStamp.coerceIn(dayStart, now)
                        onCount += 1
                    }
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                        val endedAt = event.timeStamp.coerceIn(dayStart, now)
                        if (state == "on") {
                            duration += (endedAt - onSince).coerceAtLeast(0L)
                        } else if (state == "unknown") {
                            // The first event being SCREEN_NON_INTERACTIVE proves the
                            // screen was already on for part of today.
                            duration += (endedAt - dayStart).coerceAtLeast(0L)
                            onCount += 1
                        }
                        state = "off"
                    }
                }
            }
            if (state == "on") duration += (now - onSince).coerceAtLeast(0L)
            if (state == "unknown" && (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) {
                onCount = 1
                duration = now - dayStart
            }
            TodayMetrics(onCount, duration.coerceAtMost(now - dayStart))
        }.getOrNull()
    }
}
