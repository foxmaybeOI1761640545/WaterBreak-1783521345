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
    const val MIN_LOCK_RETRY_INTERVAL_MS = 5_000L

    private val lock = Any()

    fun cancelCount(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CANCEL_COUNT, 0)
    fun forceLockActive(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FORCE_LOCK_ACTIVE, false)
    fun resetCancelCount(context: Context) { synchronized(lock) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_CANCEL_COUNT, 0).putBoolean(KEY_FORCE_LOCK_ACTIVE, false).putString(KEY_ACTIVE_SESSION_ID, "").putStringSet(KEY_HANDLED_SESSION_IDS, emptySet<String>()).commit() } }

    fun startSession(context: Context, sessionId: String) {
        if (sessionId.isBlank()) return
        synchronized(lock) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ACTIVE_SESSION_ID, sessionId)
                .commit()
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
                .putStringSet(KEY_HANDLED_SESSION_IDS, result.state.handledSessionIds.toMutableSet())
                .putInt(KEY_CANCEL_COUNT, result.state.cancelCount)
                .putBoolean(KEY_FORCE_LOCK_ACTIVE, result.state.forceLockActive)
                .commit()
        }
        result.accepted to result.shouldForceLock
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
    fun requestDeviceAdmin(activity: Activity) { activity.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply { putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(activity)); putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "用于在连续取消屏幕超时提醒达到设置次数后执行熄屏锁定。") }) }
    private fun adminComponent(context: Context) = ComponentName(context, ReminderDeviceAdminReceiver::class.java)
}
