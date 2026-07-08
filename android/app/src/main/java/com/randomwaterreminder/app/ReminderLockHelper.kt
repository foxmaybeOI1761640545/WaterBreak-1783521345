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
    const val MIN_LOCK_RETRY_INTERVAL_MS = 5_000L

    fun cancelCount(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CANCEL_COUNT, 0)
    fun forceLockActive(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FORCE_LOCK_ACTIVE, false)
    fun resetCancelCount(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_CANCEL_COUNT, 0).putBoolean(KEY_FORCE_LOCK_ACTIVE, false).apply() }
    fun recordCancelAndShouldLock(context: Context, config: ReminderConfig): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = p.getInt(KEY_CANCEL_COUNT, 0) + 1
        val force = next >= config.cancelBeforeLockCount.coerceAtLeast(1)
        p.edit().putInt(KEY_CANCEL_COUNT, next).putBoolean(KEY_FORCE_LOCK_ACTIVE, force).apply()
        return force
    }
    fun activateForceLock(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_FORCE_LOCK_ACTIVE, true).apply() }
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
