package com.randomwaterreminder.app

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

object ReminderLockHelper {
    private const val PREFS = "reminder_lock_state"
    private const val KEY_CANCEL_COUNT = "cancelCount"
    private const val KEY_FORCE_LOCK_ACTIVE = "forceLockActive"
    private const val KEY_LAST_LOCK_ATTEMPT_AT = "lastLockAttemptAt"
    private const val KEY_ACTIVE_SESSION_ID = "activeSessionId"
    private const val KEY_HANDLED_SESSION_IDS = "handledSessionIds"
    private const val KEY_ACTIVE_SESSION_STARTED_AT = "activeSessionStartedAt"
    private const val KEY_SCHEMA_VERSION = "lockStateSchemaVersion"
    private const val CURRENT_SCHEMA_VERSION = 2
    private const val ACTIVE_SESSION_TTL_MS = 10L * 60L * 1000L
    const val MIN_LOCK_RETRY_INTERVAL_MS = 5_000L

    private val lock = Any()

    fun cancelCount(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CANCEL_COUNT, 0)
    fun forceLockActive(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FORCE_LOCK_ACTIVE, false)
    fun resetCancelCount(context: Context) { synchronized(lock) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_CANCEL_COUNT, 0).putBoolean(KEY_FORCE_LOCK_ACTIVE, false).putString(KEY_ACTIVE_SESSION_ID, "").putLong(KEY_ACTIVE_SESSION_STARTED_AT, 0L).putStringSet(KEY_HANDLED_SESSION_IDS, emptySet<String>()).putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION).commit() } }

    fun startSession(context: Context, sessionId: String): Boolean {
        if (sessionId.isBlank()) return false
        return synchronized(lock) {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val active = p.getString(KEY_ACTIVE_SESSION_ID, "").orEmpty()
            if (active.isNotBlank() && active != sessionId) return@synchronized false
            val handled = p.getStringSet(KEY_HANDLED_SESSION_IDS, emptySet<String>()).orEmpty().toMutableSet().apply { remove(sessionId) }
            p.edit()
                .putString(KEY_ACTIVE_SESSION_ID, sessionId)
                .putLong(KEY_ACTIVE_SESSION_STARTED_AT, System.currentTimeMillis())
                .putStringSet(KEY_HANDLED_SESSION_IDS, handled)
                .commit()
        }
    }

    fun invalidateActiveSession(context: Context, sessionId: String = "") {
        synchronized(lock) {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val active = p.getString(KEY_ACTIVE_SESSION_ID, "").orEmpty()
            if (sessionId.isBlank() || sessionId == active) p.edit().putString(KEY_ACTIVE_SESSION_ID, "").putLong(KEY_ACTIVE_SESSION_STARTED_AT, 0L).commit()
        }
    }

    fun activeSessionId(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACTIVE_SESSION_ID, "").orEmpty()

    fun consumeSessionAndShouldLock(context: Context, sessionId: String, config: ReminderConfig): Pair<Boolean, Boolean> = synchronized(lock) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = ScreenAlertSessionState(
            activeSessionId = p.getString(KEY_ACTIVE_SESSION_ID, "").orEmpty(),
            handledSessionIds = p.getStringSet(KEY_HANDLED_SESSION_IDS, emptySet<String>()).orEmpty(),
            cancelCount = p.getInt(KEY_CANCEL_COUNT, 0),
            forceLockActive = p.getBoolean(KEY_FORCE_LOCK_ACTIVE, false),
        )
        val result = state.consume(sessionId, config.cancelBeforeLockCount)
        if (result.accepted) {
            p.edit()
                .putString(KEY_ACTIVE_SESSION_ID, result.state.activeSessionId)
                .putLong(KEY_ACTIVE_SESSION_STARTED_AT, if (result.state.activeSessionId.isBlank()) 0L else p.getLong(KEY_ACTIVE_SESSION_STARTED_AT, 0L))
                .putStringSet(KEY_HANDLED_SESSION_IDS, result.state.handledSessionIds.toMutableSet())
                .putInt(KEY_CANCEL_COUNT, result.state.cancelCount)
                .putBoolean(KEY_FORCE_LOCK_ACTIVE, result.state.forceLockActive)
                .commit()
        }
        result.accepted to result.shouldForceLock
    }


    fun migrateAndSnapshot(
        context: Context,
        limit: Int,
        restRequired: Boolean,
        graceUntil: Long,
        now: Long = System.currentTimeMillis(),
    ): ScreenCycleSnapshot = synchronized(lock) {
        val safeLimit = limit.coerceAtLeast(1)
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val schema = p.getInt(KEY_SCHEMA_VERSION, 0)
        var count = p.getInt(KEY_CANCEL_COUNT, 0)
        var force = p.getBoolean(KEY_FORCE_LOCK_ACTIVE, false)
        var active = p.getString(KEY_ACTIVE_SESSION_ID, "").orEmpty()
        var started = p.getLong(KEY_ACTIVE_SESSION_STARTED_AT, 0L)
        val futureStarted = started > now + 60_000L
        val expiredActive = active.isNotBlank() && (started <= 0L || futureStarted || now - started > ACTIVE_SESSION_TTL_MS)
        if (schema < CURRENT_SCHEMA_VERSION || count < 0 || count > safeLimit || futureStarted || expiredActive || (!restRequired && !force && active.isBlank())) {
            if (count < 0) count = 0
            if (count > safeLimit) { count = safeLimit; force = true }
            if (expiredActive || futureStarted) { active = ""; started = 0L }
            if (!restRequired && !force && active.isBlank()) count = 0
            p.edit()
                .putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
                .putInt(KEY_CANCEL_COUNT, count)
                .putBoolean(KEY_FORCE_LOCK_ACTIVE, force)
                .putString(KEY_ACTIVE_SESSION_ID, active)
                .putLong(KEY_ACTIVE_SESSION_STARTED_AT, started)
                .commit()
        }
        val phase = when {
            graceUntil > now -> ScreenCycleSnapshot.PHASE_GRACE
            force || count >= safeLimit -> ScreenCycleSnapshot.PHASE_FORCE_LOCK
            active.isNotBlank() -> ScreenCycleSnapshot.PHASE_ALERTING
            restRequired && count > 0 -> ScreenCycleSnapshot.PHASE_WAITING_REST
            else -> ScreenCycleSnapshot.PHASE_IDLE
        }
        ScreenCycleSnapshot(if (phase == ScreenCycleSnapshot.PHASE_IDLE) 0 else count.coerceIn(0, safeLimit), safeLimit, phase, active, started)
    }
    fun activateForceLock(context: Context) { synchronized(lock) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_FORCE_LOCK_ACTIVE, true).commit() } }
    fun isDeviceAdminActive(context: Context): Boolean = (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isAdminActive(adminComponent(context))
    fun lockNow(context: Context, force: Boolean = false): Boolean {
        val now = System.currentTimeMillis(); val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force && now - p.getLong(KEY_LAST_LOCK_ATTEMPT_AT, 0L) < MIN_LOCK_RETRY_INTERVAL_MS) return false
        p.edit().putLong(KEY_LAST_LOCK_ATTEMPT_AT, now).apply()
        val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!manager.isAdminActive(adminComponent(context))) return false
        manager.lockNow(); return true
    }
    fun deviceAdminIntent(context: Context): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(context))
        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "用于在连续取消屏幕超时提醒达到设置次数后执行熄屏锁定。")
    }

    fun requestDeviceAdmin(activity: Activity) { activity.startActivity(deviceAdminIntent(activity)) }
    private fun adminComponent(context: Context) = ComponentName(context, ReminderDeviceAdminReceiver::class.java)
}
