package com.randomwaterreminder.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import kotlin.math.max

object ScreenStateTracker {
    internal const val PREFS = "screen_state_tracker"
    internal const val KEY_LAST_SCREEN_ON = "lastScreenOnTime"
    internal const val KEY_LAST_SCREEN_OFF = "lastScreenOffTime"
    internal const val KEY_CURRENT_STATE = "currentScreenState"
    internal const val KEY_STATE_SINCE = "currentScreenStateSince"
    internal const val KEY_REST_REQUIRED = "restRequired"
    internal const val KEY_REST_STARTED_AT = "restStartedAt"
    const val KEY_NEXT_SCREEN_CHECK_AT = "nextScreenCheckAt"
    private const val KEY_NEXT_ALLOWED_ALERT_AT = "nextAllowedAlertAt"
    private const val KEY_ACTIVE_SESSION_ID = "activeSessionId"
    private const val KEY_LAST_OBSERVED_AT = "lastObservedAt"
    private const val KEY_GRACE_UNTIL = "emergencyGraceUntil"
    private const val KEY_RAPID_ON_TIMES = "rapidScreenOnTimes"
    private const val MAX_UNVERIFIED_GAP = 10L * 60L * 1000L
    private const val MAX_EVENT_LOOKBACK = 7L * 24L * 60L * 60L * 1000L
    private const val MAX_CLOCK_SKEW = 60_000L

    @Volatile
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val now = System.currentTimeMillis()
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> recordState(context, "on", now)
                Intent.ACTION_SCREEN_OFF -> recordState(context, "off", now)
                else -> return
            }
            evaluateAndAlert(context, forceDue = true)
        }
    }

    fun start(context: Context) {
        val appContext = context.applicationContext
        if (!started) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            runCatching {
                ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            }.onSuccess { started = true }
        }
        refreshFromSystem(appContext)
        ensureInitialState(appContext)
        ScreenOnLimitAlarmScheduler.reschedule(appContext, refreshState = false)
    }

    fun status(context: Context): JSObject {
        start(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = prefs.getString(KEY_CURRENT_STATE, "unknown") ?: "unknown"
        val stateSince = prefs.getLong(KEY_STATE_SINCE, 0L)
        val lastScreenOn = prefs.getLong(KEY_LAST_SCREEN_ON, 0L)
        val lastScreenOff = prefs.getLong(KEY_LAST_SCREEN_OFF, 0L)
        val now = System.currentTimeMillis()
        val usagePermission = ScreenUsageEventReader.hasPermission(context)
        return JSObject().apply {
            put("currentScreenState", state)
            put("currentScreenStateSince", stateSince)
            put("currentScreenStateDurationMs", if (stateSince > 0L) (now - stateSince).coerceAtLeast(0L) else 0L)
            put("lastScreenOnTime", lastScreenOn)
            put("lastScreenOffTime", lastScreenOff)
            put("restRequired", prefs.getBoolean(KEY_REST_REQUIRED, false))
            put("restStartedAt", prefs.getLong(KEY_REST_STARTED_AT, 0L))
            put("nextScreenCheckAt", prefs.getLong(KEY_NEXT_SCREEN_CHECK_AT, 0L))
            put("trackingReliable", usagePermission)
            put(
                "trackingNote",
                if (usagePermission) {
                    "使用系统亮灭屏事件恢复状态；应用被划掉后由一次性闹钟定期核对，不需要常驻后台。"
                } else {
                    "未授予使用情况访问权限。为防止错误累计，监听中断后会从重新确认状态的时间开始计算。"
                },
            )
        }
    }

    fun refreshFromSystem(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        var lastObserved = prefs.getLong(KEY_LAST_OBSERVED_AT, 0L)
        val containsFutureTimestamp = listOf(
            lastObserved,
            prefs.getLong(KEY_STATE_SINCE, 0L),
            prefs.getLong(KEY_LAST_SCREEN_ON, 0L),
            prefs.getLong(KEY_LAST_SCREEN_OFF, 0L),
        ).any { it > now + MAX_CLOCK_SKEW }
        if (containsFutureTimestamp) {
            // Manual clock changes can otherwise leave a stored segment in the future and
            // prevent all subsequent UsageEvents from replacing it.
            prefs.edit()
                .remove(KEY_LAST_SCREEN_ON)
                .remove(KEY_LAST_SCREEN_OFF)
                .remove(KEY_CURRENT_STATE)
                .remove(KEY_STATE_SINCE)
                .remove(KEY_LAST_OBSERVED_AT)
                .putLong(KEY_NEXT_SCREEN_CHECK_AT, 0L)
                .apply()
            lastObserved = 0L
        }
        val beginAt = when {
            lastObserved <= 0L -> now - MAX_EVENT_LOOKBACK
            now - lastObserved > MAX_EVENT_LOOKBACK -> now - MAX_EVENT_LOOKBACK
            else -> lastObserved - 60_000L
        }
        val snapshot = ScreenUsageEventReader.latestSnapshot(appContext, now, beginAt)
        val recordedSince = prefs.getLong(KEY_STATE_SINCE, 0L)

        if (snapshot != null && snapshot.stateSince > recordedSince) {
            prefs.edit().apply {
                putString(KEY_CURRENT_STATE, snapshot.state)
                putLong(KEY_STATE_SINCE, snapshot.stateSince)
                if (snapshot.lastScreenOn > 0L) putLong(KEY_LAST_SCREEN_ON, snapshot.lastScreenOn)
                if (snapshot.lastScreenOff > 0L) putLong(KEY_LAST_SCREEN_OFF, snapshot.lastScreenOff)
                if (snapshot.state == "on" && prefs.getBoolean(KEY_REST_REQUIRED, false)) {
                    putLong(KEY_REST_STARTED_AT, 0L)
                }
                if (snapshot.state == "off" && prefs.getBoolean(KEY_REST_REQUIRED, false)) {
                    putLong(KEY_REST_STARTED_AT, snapshot.stateSince)
                }
            }.apply()
        }

        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val actualState = if (powerManager.isInteractive) "on" else "off"
        val currentState = prefs.getString(KEY_CURRENT_STATE, "unknown") ?: "unknown"
        when {
            actualState != currentState -> recordState(appContext, actualState, now)
            snapshot == null && lastObserved > 0L && now - lastObserved > MAX_UNVERIFIED_GAP -> {
                // The process was absent and no system event can prove continuity. Reset the
                // current segment instead of carrying an old timestamp forward for days.
                recordState(appContext, actualState, now)
            }
        }
        prefs.edit().putLong(KEY_LAST_OBSERVED_AT, now).apply()
    }

    fun evaluateAndAlert(context: Context, forceDue: Boolean = false): Boolean {
        val appContext = context.applicationContext
        refreshFromSystem(appContext)
        ensureInitialState(appContext)
        val config = ReminderPreferences.read(appContext)
        if (!config.screenLimitEnabled || config.screenOnLimitMinutes <= 0) {
            clearRestState(appContext)
            return false
        }

        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val scheduledAt = prefs.getLong(KEY_NEXT_SCREEN_CHECK_AT, 0L)
        if (!forceDue && scheduledAt > now + 500L) {
            ScreenOnLimitAlarmScheduler.scheduleAt(appContext, scheduledAt)
            return false
        }

        val graceUntil = prefs.getLong(KEY_GRACE_UNTIL, 0L)
        if (now < graceUntil) {
            prefs.edit()
                .putBoolean(KEY_REST_REQUIRED, false)
                .putLong(KEY_REST_STARTED_AT, 0L)
                .putLong(KEY_NEXT_ALLOWED_ALERT_AT, graceUntil)
                .putLong(KEY_NEXT_SCREEN_CHECK_AT, graceUntil)
                .apply()
            ReminderLockHelper.resetCancelCount(appContext)
            AlertCoordinator.dismissScreenAlert(appContext)
            ScreenOnLimitAlarmScheduler.scheduleAt(appContext, graceUntil)
            return false
        }

        val decision = ScreenRestStateMachine.evaluate(
            ScreenRestSnapshot(
                state = prefs.getString(KEY_CURRENT_STATE, "unknown") ?: "unknown",
                now = now,
                lastScreenOn = prefs.getLong(KEY_LAST_SCREEN_ON, 0L),
                lastScreenOff = prefs.getLong(KEY_LAST_SCREEN_OFF, 0L),
                currentStateSince = prefs.getLong(KEY_STATE_SINCE, 0L),
                restRequired = prefs.getBoolean(KEY_REST_REQUIRED, false),
                restStartedAt = prefs.getLong(KEY_REST_STARTED_AT, 0L),
                screenOnLimitMinutes = config.screenOnLimitMinutes,
                requiredScreenOffMinutes = config.requiredScreenOffMinutes,
                cancelCount = ReminderLockHelper.cancelCount(appContext),
                cancelBeforeLockCount = config.cancelBeforeLockCount,
                forceLockActive = ReminderLockHelper.forceLockActive(appContext),
                nextAllowedAlertAt = prefs.getLong(KEY_NEXT_ALLOWED_ALERT_AT, 0L),
            ),
        )

        prefs.edit()
            .putBoolean(KEY_REST_REQUIRED, decision.restRequired)
            .putLong(KEY_REST_STARTED_AT, decision.restStartedAt)
            .putLong(KEY_NEXT_SCREEN_CHECK_AT, decision.nextScreenCheckAt)
            .apply()

        if (decision.shouldClear) {
            ReminderLockHelper.resetCancelCount(appContext)
            prefs.edit().putLong(KEY_NEXT_ALLOWED_ALERT_AT, 0L).putString(KEY_ACTIVE_SESSION_ID, "").apply()
            AlertCoordinator.dismissScreenAlert(appContext)
        }

        if (decision.shouldAlert && ReminderLockHelper.activeSessionId(appContext).isBlank()) {
            val title = "亮屏时间过长"
            val text = "已经连续亮屏 ${config.screenOnLimitMinutes} 分钟以上，请连续息屏 ${config.requiredScreenOffMinutes} 分钟休息。"
            val sessionId = "screen-${now}"
            AlertCoordinator.alert(appContext, ReminderType.SCREEN_LIMIT, title, text, config, sessionId = sessionId)
        }

        if (decision.shouldForceLock) {
            executeForceLock(appContext)
        }

        if (decision.nextScreenCheckAt > 0L) {
            ScreenOnLimitAlarmScheduler.scheduleAt(appContext, decision.nextScreenCheckAt)
        } else {
            ScreenOnLimitAlarmScheduler.reschedule(appContext, refreshState = false, forceRecalculate = true)
        }
        return decision.shouldAlert
    }

    fun recordScreenAlertCancel(context: Context, sessionId: String = ""): Boolean {
        val appContext = context.applicationContext
        val config = ReminderPreferences.read(appContext)
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val (accepted, shouldLock) = ReminderLockHelper.consumeSessionAndShouldLock(appContext, sessionId, config)
        if (!accepted) return false
        val now = System.currentTimeMillis()
        val next = now + config.screenOnLimitMinutes.coerceAtLeast(0) * 60_000L
        prefs.edit()
            .putBoolean(KEY_REST_REQUIRED, true)
            .putLong(KEY_NEXT_ALLOWED_ALERT_AT, next)
            .putLong(KEY_NEXT_SCREEN_CHECK_AT, next)
            .putString(KEY_ACTIVE_SESSION_ID, "")
            .commit()
        AlertCoordinator.dismissScreenAlert(appContext, sessionId)
        if (shouldLock) executeForceLock(appContext)
        ScreenOnLimitAlarmScheduler.scheduleAt(appContext, if (shouldLock) now + ReminderLockHelper.MIN_LOCK_RETRY_INTERVAL_MS else next)
        return shouldLock
    }

    fun resetForConfigChange(context: Context) {
        val appContext = context.applicationContext
        ScreenOnLimitAlarmScheduler.cancel(appContext)
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_NEXT_SCREEN_CHECK_AT, 0L)
            .putBoolean(KEY_REST_REQUIRED, false)
            .putLong(KEY_REST_STARTED_AT, 0L)
            .putLong(KEY_NEXT_ALLOWED_ALERT_AT, 0L)
            .putString(KEY_ACTIVE_SESSION_ID, "")
            .apply()
        ReminderLockHelper.resetCancelCount(appContext)
        AlertCoordinator.dismissScreenAlert(appContext)
        refreshFromSystem(appContext)
        ensureInitialState(appContext)
        ScreenOnLimitAlarmScheduler.reschedule(appContext, refreshState = false, forceRecalculate = true)
    }

    fun clearRestState(context: Context) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_REST_REQUIRED, false)
            .putLong(KEY_REST_STARTED_AT, 0L)
            .putLong(KEY_NEXT_SCREEN_CHECK_AT, 0L)
            .putLong(KEY_NEXT_ALLOWED_ALERT_AT, 0L)
            .putString(KEY_ACTIVE_SESSION_ID, "")
            .apply()
        ReminderLockHelper.resetCancelCount(appContext)
        AlertCoordinator.dismissScreenAlert(appContext)
        ScreenOnLimitAlarmScheduler.cancel(appContext)
    }

    private fun executeForceLock(context: Context) {
        ReminderLockHelper.activateForceLock(context)
        AlertCoordinator.dismissScreenAlert(context)
        NotificationHelper.vibrateAlert(context)
        ReminderLockHelper.lockNow(context)
    }

    private fun ensureInitialState(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_STATE_SINCE, 0L) > 0L) return
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        recordState(context, if (powerManager.isInteractive) "on" else "off", System.currentTimeMillis())
    }

    private fun recordState(context: Context, state: String, timestamp: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wasRestRequired = prefs.getBoolean(KEY_REST_REQUIRED, false)
        val previousState = prefs.getString(KEY_CURRENT_STATE, "unknown") ?: "unknown"
        val previousSince = prefs.getLong(KEY_STATE_SINCE, 0L)
        val safeTimestamp = max(timestamp, if (previousState == state) previousSince else 0L)
        if (previousState == "off" && state == "on" && ReminderLockHelper.forceLockActive(context)) {
            val recent = prefs.getString(KEY_RAPID_ON_TIMES, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }.filter { it in (safeTimestamp - RapidScreenOnGraceState.RAPID_SCREEN_ON_WINDOW_MS)..safeTimestamp } + safeTimestamp
            if (recent.size >= RapidScreenOnGraceState.RAPID_SCREEN_ON_THRESHOLD) {
                prefs.edit()
                    .putLong(KEY_GRACE_UNTIL, safeTimestamp + RapidScreenOnGraceState.EMERGENCY_GRACE_PERIOD_MS)
                    .putString(KEY_RAPID_ON_TIMES, "")
                    .putBoolean(KEY_REST_REQUIRED, false)
                    .putLong(KEY_REST_STARTED_AT, 0L)
                    .putLong(KEY_NEXT_ALLOWED_ALERT_AT, safeTimestamp + RapidScreenOnGraceState.EMERGENCY_GRACE_PERIOD_MS)
                    .apply()
                ReminderLockHelper.resetCancelCount(context)
                AlertCoordinator.dismissScreenAlert(context)
            } else {
                prefs.edit().putString(KEY_RAPID_ON_TIMES, recent.joinToString(",")).commit()
            }
        }
        prefs.edit().apply {
            putString(KEY_CURRENT_STATE, state)
            putLong(KEY_STATE_SINCE, safeTimestamp)
            putLong(KEY_LAST_OBSERVED_AT, System.currentTimeMillis())
            if (state == "on") {
                putLong(KEY_LAST_SCREEN_ON, safeTimestamp)
                if (wasRestRequired) putLong(KEY_REST_STARTED_AT, 0L)
            } else {
                putLong(KEY_LAST_SCREEN_OFF, safeTimestamp)
                if (wasRestRequired) putLong(KEY_REST_STARTED_AT, safeTimestamp)
            }
        }.apply()
    }
}
