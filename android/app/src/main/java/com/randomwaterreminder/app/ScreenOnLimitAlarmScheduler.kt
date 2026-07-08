package com.randomwaterreminder.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object ScreenOnLimitAlarmScheduler {
    private const val REQUEST_CODE = 2408
    private const val STATE_PREFS = "screen_alarm_state"
    private const val KEY_EXACT = "exact"
    private const val KEY_TRIGGER = "trigger"
    private const val KEY_REASON = "reason"
    internal const val POLL_INTERVAL_MILLIS = 60_000L
    internal const val REPEAT_ALERT_INTERVAL_MILLIS = 60_000L

    fun reschedule(
        context: Context,
        refreshState: Boolean = true,
        forceRecalculate: Boolean = false,
    ): AlarmScheduleResult {
        val appContext = context.applicationContext
        if (refreshState) ScreenStateTracker.refreshFromSystem(appContext)
        val config = ReminderPreferences.read(appContext)
        if (!config.screenLimitEnabled || config.screenOnLimitMinutes <= 0) {
            cancel(appContext)
            return saveResult(appContext, AlarmScheduleResult(false, false, 0L, "屏幕提醒未开启"))
        }

        val prefs = appContext.getSharedPreferences(ScreenStateTracker.PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val existing = prefs.getLong(ScreenStateTracker.KEY_NEXT_SCREEN_CHECK_AT, 0L)
        if (!forceRecalculate && existing > now + 500L) {
            return scheduleAt(appContext, existing)
        }

        val state = prefs.getString(ScreenStateTracker.KEY_CURRENT_STATE, "unknown") ?: "unknown"
        val restRequired = prefs.getBoolean(ScreenStateTracker.KEY_REST_REQUIRED, false)
        val triggerAt = when (state) {
            "on" -> {
                val lastOn = prefs.getLong(ScreenStateTracker.KEY_LAST_SCREEN_ON, 0L)
                    .takeIf { it > 0L }
                    ?: prefs.getLong(ScreenStateTracker.KEY_STATE_SINCE, 0L)
                when {
                    lastOn <= 0L -> now + POLL_INTERVAL_MILLIS
                    restRequired -> now + REPEAT_ALERT_INTERVAL_MILLIS
                    else -> (lastOn + config.screenOnLimitMinutes * 60_000L).coerceAtLeast(now + 1_000L)
                }
            }
            "off" -> {
                if (restRequired) {
                    val restStarted = prefs.getLong(ScreenStateTracker.KEY_REST_STARTED_AT, 0L)
                        .takeIf { it > 0L }
                        ?: prefs.getLong(ScreenStateTracker.KEY_STATE_SINCE, now)
                    val clearAt = restStarted + config.requiredScreenOffMinutes.coerceAtLeast(1) * 60_000L
                    minOf(clearAt.coerceAtLeast(now + 1_000L), now + POLL_INTERVAL_MILLIS)
                } else {
                    // Keep one short-lived alarm queued while the process is absent. When the
                    // device wakes, the receiver queries UsageEvents and discovers the new
                    // SCREEN_INTERACTIVE event without a resident service.
                    now + POLL_INTERVAL_MILLIS
                }
            }
            else -> now + POLL_INTERVAL_MILLIS
        }
        return scheduleAt(appContext, triggerAt)
    }

    fun scheduleAt(context: Context, triggerAtMillis: Long): AlarmScheduleResult {
        val appContext = context.applicationContext
        val alarm = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val safeTrigger = triggerAtMillis.coerceAtLeast(System.currentTimeMillis() + 500L)
        val operation = receiverPendingIntent(appContext)
        val result = try {
            if (WaterReminderScheduler.canScheduleExact(appContext)) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, safeTrigger, operation)
                AlarmScheduleResult(true, true, safeTrigger, "屏幕检查已使用精确闹钟")
            } else {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, safeTrigger, operation)
                AlarmScheduleResult(true, false, safeTrigger, "未授予精确闹钟权限，屏幕检查可能延迟")
            }
        } catch (_: SecurityException) {
            runCatching { alarm.set(AlarmManager.RTC_WAKEUP, safeTrigger, operation) }
                .fold(
                    onSuccess = { AlarmScheduleResult(true, false, safeTrigger, "精确闹钟被系统拒绝，屏幕检查已降级") },
                    onFailure = { AlarmScheduleResult(false, false, 0L, it.message ?: "屏幕检查安排失败") },
                )
        } catch (error: Throwable) {
            AlarmScheduleResult(false, false, 0L, error.message ?: "屏幕检查安排失败")
        }
        if (result.scheduled) {
            appContext.getSharedPreferences(ScreenStateTracker.PREFS, Context.MODE_PRIVATE).edit()
                .putLong(ScreenStateTracker.KEY_NEXT_SCREEN_CHECK_AT, result.triggerAtMillis)
                .apply()
        }
        return saveResult(appContext, result)
    }

    fun cancel(context: Context) {
        val appContext = context.applicationContext
        val alarm = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(receiverPendingIntent(appContext))
        appContext.getSharedPreferences(ScreenStateTracker.PREFS, Context.MODE_PRIVATE).edit()
            .putLong(ScreenStateTracker.KEY_NEXT_SCREEN_CHECK_AT, 0L)
            .apply()
        saveResult(appContext, AlarmScheduleResult(false, false, 0L, "屏幕检查已取消"))
    }

    fun lastScheduleResult(context: Context): AlarmScheduleResult {
        val prefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        return AlarmScheduleResult(
            scheduled = prefs.getLong(KEY_TRIGGER, 0L) > 0L,
            exact = prefs.getBoolean(KEY_EXACT, false),
            triggerAtMillis = prefs.getLong(KEY_TRIGGER, 0L),
            reason = prefs.getString(KEY_REASON, "").orEmpty(),
        )
    }

    private fun receiverPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ScreenOnLimitReceiver::class.java).setAction("com.randomwaterreminder.SCREEN_ON_LIMIT"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun saveResult(context: Context, result: AlarmScheduleResult): AlarmScheduleResult {
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_EXACT, result.exact)
            .putLong(KEY_TRIGGER, if (result.scheduled) result.triggerAtMillis else 0L)
            .putString(KEY_REASON, result.reason)
            .apply()
        return result
    }
}
