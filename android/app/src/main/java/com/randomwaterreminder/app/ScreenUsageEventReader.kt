package com.randomwaterreminder.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

object ScreenUsageEventReader {
    private const val DEFAULT_LOOKBACK_MILLIS = 7L * 24L * 60L * 60L * 1000L

    data class Snapshot(
        val state: String,
        val lastScreenOn: Long,
        val lastScreenOff: Long,
        val stateSince: Long,
    )

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
}
