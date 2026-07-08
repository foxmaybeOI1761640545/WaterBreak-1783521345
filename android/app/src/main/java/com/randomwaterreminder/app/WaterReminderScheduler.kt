package com.randomwaterreminder.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar
import java.util.concurrent.ThreadLocalRandom

object WaterReminderScheduler {
    private const val REQUEST_CODE = 2308
    private const val STATE_PREFS = "water_alarm_state"
    private const val KEY_EXACT = "exact"
    private const val KEY_TRIGGER = "trigger"
    private const val KEY_REASON = "reason"

    fun scheduleNextReminder(context: Context, requestedTime: Long? = null): ReminderConfig {
        val current = ReminderPreferences.read(context)
        val now = System.currentTimeMillis()
        val next = requestedTime?.takeIf { it > now }
            ?: current.nextReminderTime.takeIf { it > now }
            ?: calculateNextReminderTime(current, now)
        val updated = current.copy(enabled = true, nextReminderTime = next)
        ReminderPreferences.save(context, updated)
        saveResult(context, scheduleAt(context, next))
        return updated
    }

    fun rescheduleIfEnabled(context: Context): AlarmScheduleResult {
        val config = ReminderPreferences.read(context)
        if (!config.enabled) {
            cancelAlarmOnly(context)
            val result = AlarmScheduleResult(false, false, 0L, "喝水提醒未开启")
            saveResult(context, result)
            return result
        }
        val now = System.currentTimeMillis()
        val trigger = config.nextReminderTime.takeIf { it > now } ?: calculateNextReminderTime(config, now)
        if (trigger != config.nextReminderTime) {
            ReminderPreferences.save(context, config.copy(nextReminderTime = trigger))
        }
        return scheduleAt(context, trigger).also { saveResult(context, it) }
    }

    fun cancelReminder(context: Context): ReminderConfig {
        cancelAlarmOnly(context)
        val updated = ReminderPreferences.read(context).copy(enabled = false, nextReminderTime = 0L)
        ReminderPreferences.save(context, updated)
        saveResult(context, AlarmScheduleResult(false, false, 0L, "喝水提醒已关闭"))
        return updated
    }

    fun canScheduleExact(context: Context): Boolean {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()
    }

    fun exactAlarmSettingsIntent(context: Context): Intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
        data = android.net.Uri.parse("package:${context.packageName}")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
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

    fun calculateNextReminderTime(config: ReminderConfig, nowMillis: Long): Long {
        val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val startToday = boundary(now, config.startHour, config.startMinute)
        val endToday = boundary(now, config.endHour, config.endMinute)
        val crossesMidnight = startToday.timeInMillis >= endToday.timeInMillis

        val windowStart: Calendar
        val windowEnd: Calendar
        val activeNow: Boolean

        if (!crossesMidnight) {
            activeNow = now.timeInMillis in startToday.timeInMillis until endToday.timeInMillis
            if (activeNow) {
                windowStart = startToday
                windowEnd = endToday
            } else if (now.before(startToday)) {
                windowStart = startToday
                windowEnd = endToday
            } else {
                windowStart = nextDayBoundary(now, config.startHour, config.startMinute)
                windowEnd = nextDayBoundary(now, config.endHour, config.endMinute)
            }
        } else {
            val endTomorrow = (endToday.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
            val startYesterday = (startToday.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
            activeNow = now.timeInMillis >= startToday.timeInMillis || now.timeInMillis < endToday.timeInMillis
            if (now.timeInMillis >= startToday.timeInMillis) {
                windowStart = startToday
                windowEnd = endTomorrow
            } else if (now.timeInMillis < endToday.timeInMillis) {
                windowStart = startYesterday
                windowEnd = endToday
            } else {
                windowStart = startToday
                windowEnd = endTomorrow
            }
        }

        val base = if (activeNow) nowMillis else windowStart.timeInMillis
        val candidate = base + randomIntervalMillis(config)
        if (candidate < windowEnd.timeInMillis) return candidate

        val nextStart = if (activeNow || now.timeInMillis >= windowEnd.timeInMillis) {
            (windowStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        } else windowStart
        val nextEnd = if (crossesMidnight) {
            (nextStart.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, config.endHour)
                set(Calendar.MINUTE, config.endMinute)
                if (timeInMillis <= nextStart.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
            }
        } else {
            (nextStart.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, config.endHour)
                set(Calendar.MINUTE, config.endMinute)
            }
        }
        return (nextStart.timeInMillis + randomIntervalMillis(config)).coerceAtMost(nextEnd.timeInMillis - 1_000L)
    }

    private fun scheduleAt(context: Context, triggerAtMillis: Long): AlarmScheduleResult {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val operation = pendingIntent(context)
        return try {
            if (canScheduleExact(context)) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
                AlarmScheduleResult(true, true, triggerAtMillis, "已使用精确闹钟")
            } else {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
                AlarmScheduleResult(true, false, triggerAtMillis, "未授予精确闹钟权限，已降级为非精确定时")
            }
        } catch (_: SecurityException) {
            runCatching { alarm.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation) }
                .fold(
                    onSuccess = { AlarmScheduleResult(true, false, triggerAtMillis, "精确闹钟被系统拒绝，已降级") },
                    onFailure = { AlarmScheduleResult(false, false, 0L, it.message ?: "闹钟安排失败") },
                )
        } catch (error: Throwable) {
            AlarmScheduleResult(false, false, 0L, error.message ?: "闹钟安排失败")
        }
    }

    private fun cancelAlarmOnly(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, WaterReminderReceiver::class.java).setAction("com.randomwaterreminder.REMIND"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun saveResult(context: Context, result: AlarmScheduleResult) {
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_EXACT, result.exact)
            .putLong(KEY_TRIGGER, if (result.scheduled) result.triggerAtMillis else 0L)
            .putString(KEY_REASON, result.reason)
            .apply()
    }

    private fun randomIntervalMillis(config: ReminderConfig): Long {
        val min = config.minIntervalMinutes.coerceAtLeast(1)
        val max = config.maxIntervalMinutes.coerceAtLeast(min)
        return ThreadLocalRandom.current().nextLong(min.toLong(), max.toLong() + 1L) * 60_000L
    }

    private fun boundary(base: Calendar, hour: Int, minute: Int): Calendar = (base.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun nextDayBoundary(base: Calendar, hour: Int, minute: Int): Calendar = boundary(base, hour, minute).apply {
        add(Calendar.DAY_OF_YEAR, 1)
    }
}
