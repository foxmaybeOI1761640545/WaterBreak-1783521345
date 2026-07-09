package com.randomwaterreminder.app

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.UUID

object ReminderLockHelper {
    private const val TAG = "ReminderLockHelper"
    private const val PREFS = "reminder_lock_state"
    private const val KEY_CANCEL_COUNT = "cancelCount"
    private const val KEY_FORCE_LOCK_ACTIVE = "forceLockActive"
    private const val KEY_LAST_LOCK_ATTEMPT_AT = "lastLockAttemptAt"
    private const val KEY_ACTIVE_SESSION_ID = "activeSessionId"
    private const val KEY_HANDLED_SESSION_IDS = "handledSessionIds"
    private const val KEY_ACTIVE_SESSION_STARTED_AT = "activeSessionStartedAt"
    private const val KEY_SCHEMA_VERSION = "lockStateSchemaVersion"
    private const val KEY_CYCLE_ID = "cycleId"
    private const val KEY_CYCLE_STARTED_AT = "cycleStartedAt"
    private const val KEY_CYCLE_UPDATED_AT = "cycleUpdatedAt"
    private const val CURRENT_SCHEMA_VERSION = 3
    private const val ACTIVE_SESSION_TTL_MS = 10L * 60L * 1000L
    private const val CYCLE_TTL_MS = 7L * 24L * 60L * 60L * 1000L
    const val MIN_LOCK_RETRY_INTERVAL_MS = 5_000L
    const val FORCE_LOCK_RECHECK_INTERVAL_MS = 30_000L

    private val lock = Any()

    fun cancelCount(context: Context): Int = synchronized(lock) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CANCEL_COUNT, 0)
    }

    fun forceLockActive(context: Context): Boolean = synchronized(lock) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FORCE_LOCK_ACTIVE, false)
    }

    fun resetCancelCount(context: Context) {
        synchronized(lock) {
            clearTemporaryState(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), keepSchema = true)
        }
    }

    fun startSession(context: Context, sessionId: String, now: Long = System.currentTimeMillis()): Boolean {
        if (sessionId.isBlank()) return false
        return synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            migrateLegacyIfNeeded(context, prefs, limit = Int.MAX_VALUE, restRequired = context.getSharedPreferences(ScreenStateTracker.PREFS, Context.MODE_PRIVATE).getBoolean(ScreenStateTracker.KEY_REST_REQUIRED, false), now = now)
            val active = prefs.getString(KEY_ACTIVE_SESSION_ID, "").orEmpty()
            val activeStarted = prefs.getLong(KEY_ACTIVE_SESSION_STARTED_AT, 0L)
            val activeExpired = active.isNotBlank() && (activeStarted <= 0L || activeStarted > now + 60_000L || now - activeStarted > ACTIVE_SESSION_TTL_MS)
            if (active.isNotBlank() && active != sessionId && !activeExpired) return@synchronized false

            val handled = prefs.getStringSet(KEY_HANDLED_SESSION_IDS, emptySet<String>()).orEmpty().toMutableSet().apply { remove(sessionId) }
            val existingCycleId = prefs.getString(KEY_CYCLE_ID, "").orEmpty()
            val cycleId = existingCycleId.ifBlank { sessionId }
            val cycleStartedAt = prefs.getLong(KEY_CYCLE_STARTED_AT, 0L).takeIf { it in 1..now } ?: now
            prefs.edit()
                .putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
                .putString(KEY_ACTIVE_SESSION_ID, sessionId)
                .putLong(KEY_ACTIVE_SESSION_STARTED_AT, now)
                .putStringSet(KEY_HANDLED_SESSION_IDS, handled)
                .putString(KEY_CYCLE_ID, cycleId)
                .putLong(KEY_CYCLE_STARTED_AT, cycleStartedAt)
                .putLong(KEY_CYCLE_UPDATED_AT, now)
                .commit()
        }
    }

    fun invalidateActiveSession(context: Context, sessionId: String = "") {
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val active = prefs.getString(KEY_ACTIVE_SESSION_ID, "").orEmpty()
            if (sessionId.isBlank() || sessionId == active) {
                prefs.edit()
                    .putString(KEY_ACTIVE_SESSION_ID, "")
                    .putLong(KEY_ACTIVE_SESSION_STARTED_AT, 0L)
                    .putLong(KEY_CYCLE_UPDATED_AT, System.currentTimeMillis())
                    .commit()
            }
        }
    }

    fun activeSessionId(context: Context): String = synchronized(lock) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACTIVE_SESSION_ID, "").orEmpty()
    }

    fun consumeSession(context: Context, sessionId: String, config: ReminderConfig): ScreenAlertCancelResult = synchronized(lock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val safeLimit = config.cancelBeforeLockCount.coerceAtLeast(1)
        val state = ScreenAlertSessionState(
            activeSessionId = prefs.getString(KEY_ACTIVE_SESSION_ID, "").orEmpty(),
            handledSessionIds = prefs.getStringSet(KEY_HANDLED_SESSION_IDS, emptySet<String>()).orEmpty(),
            cancelCount = prefs.getInt(KEY_CANCEL_COUNT, 0).coerceAtLeast(0),
            forceLockActive = prefs.getBoolean(KEY_FORCE_LOCK_ACTIVE, false),
        )
        val result = state.consume(sessionId, safeLimit)
        if (!result.accepted) {
            return@synchronized ScreenAlertCancelResult(false, false, state.cancelCount, safeLimit)
        }
        val now = System.currentTimeMillis()
        val cycleId = prefs.getString(KEY_CYCLE_ID, "").orEmpty().ifBlank { sessionId }
        val cycleStartedAt = prefs.getLong(KEY_CYCLE_STARTED_AT, 0L).takeIf { it in 1..now } ?: now
        prefs.edit()
            .putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
            .putString(KEY_ACTIVE_SESSION_ID, result.state.activeSessionId)
            .putLong(KEY_ACTIVE_SESSION_STARTED_AT, 0L)
            .putStringSet(KEY_HANDLED_SESSION_IDS, result.state.handledSessionIds.toMutableSet())
            .putInt(KEY_CANCEL_COUNT, result.state.cancelCount)
            .putBoolean(KEY_FORCE_LOCK_ACTIVE, result.state.forceLockActive)
            .putString(KEY_CYCLE_ID, cycleId)
            .putLong(KEY_CYCLE_STARTED_AT, cycleStartedAt)
            .putLong(KEY_CYCLE_UPDATED_AT, now)
            .commit()
        ScreenAlertCancelResult(true, result.shouldForceLock, result.state.cancelCount, safeLimit)
    }

    fun migrateAndSnapshot(
        context: Context,
        limit: Int,
        restRequired: Boolean,
        graceUntil: Long,
        now: Long = System.currentTimeMillis(),
    ): ScreenCycleSnapshot = synchronized(lock) {
        val safeLimit = limit.coerceAtLeast(1)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        migrateLegacyIfNeeded(context, prefs, safeLimit, restRequired, now)

        var count = prefs.getInt(KEY_CANCEL_COUNT, 0)
        var force = prefs.getBoolean(KEY_FORCE_LOCK_ACTIVE, false)
        var active = prefs.getString(KEY_ACTIVE_SESSION_ID, "").orEmpty()
        var activeStarted = prefs.getLong(KEY_ACTIVE_SESSION_STARTED_AT, 0L)
        var cycleId = prefs.getString(KEY_CYCLE_ID, "").orEmpty()
        var cycleStarted = prefs.getLong(KEY_CYCLE_STARTED_AT, 0L)
        var cycleUpdated = prefs.getLong(KEY_CYCLE_UPDATED_AT, 0L)

        val invalidActive = active.isNotBlank() && (activeStarted <= 0L || activeStarted > now + 60_000L || now - activeStarted > ACTIVE_SESSION_TTL_MS)
        val invalidCycleTime = cycleStarted > now + 60_000L || cycleUpdated > now + 60_000L ||
            (cycleUpdated > 0L && now - cycleUpdated > CYCLE_TTL_MS)
        val invalidCount = count !in 0..safeLimit
        val forceWithoutCycle = force && (!restRequired || cycleId.isBlank() || cycleStarted <= 0L || cycleUpdated <= 0L)
        val thresholdWithoutForce = count >= safeLimit && !force

        if (invalidActive) {
            active = ""
            activeStarted = 0L
        }
        if (invalidCount || invalidCycleTime || forceWithoutCycle || thresholdWithoutForce) {
            count = 0
            force = false
            active = ""
            activeStarted = 0L
            cycleId = ""
            cycleStarted = 0L
            cycleUpdated = 0L
        } else if (!restRequired && !force && active.isBlank() && graceUntil <= now) {
            count = 0
            cycleId = ""
            cycleStarted = 0L
            cycleUpdated = 0L
        }

        prefs.edit()
            .putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
            .putInt(KEY_CANCEL_COUNT, count)
            .putBoolean(KEY_FORCE_LOCK_ACTIVE, force)
            .putString(KEY_ACTIVE_SESSION_ID, active)
            .putLong(KEY_ACTIVE_SESSION_STARTED_AT, activeStarted)
            .putString(KEY_CYCLE_ID, cycleId)
            .putLong(KEY_CYCLE_STARTED_AT, cycleStarted)
            .putLong(KEY_CYCLE_UPDATED_AT, cycleUpdated)
            .commit()

        val adminActive = isDeviceAdminActive(context)
        val phase = when {
            graceUntil > now -> ScreenCycleSnapshot.PHASE_GRACE
            force && !adminActive -> ScreenCycleSnapshot.PHASE_BLOCKED_ADMIN
            force -> ScreenCycleSnapshot.PHASE_FORCE_LOCK
            active.isNotBlank() -> ScreenCycleSnapshot.PHASE_ALERTING
            restRequired && count > 0 -> ScreenCycleSnapshot.PHASE_WAITING_REST
            else -> ScreenCycleSnapshot.PHASE_IDLE
        }
        ScreenCycleSnapshot(
            cancelCount = if (phase == ScreenCycleSnapshot.PHASE_IDLE) 0 else count,
            limit = safeLimit,
            phase = phase,
            activeSessionId = active,
            sessionStartedAt = activeStarted,
            cycleId = cycleId,
            cycleStartedAt = cycleStarted,
            cycleUpdatedAt = cycleUpdated,
        )
    }

    fun activateForceLock(context: Context, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val cycleId = prefs.getString(KEY_CYCLE_ID, "").orEmpty().ifBlank { "cycle-${UUID.randomUUID()}" }
            val startedAt = prefs.getLong(KEY_CYCLE_STARTED_AT, 0L).takeIf { it in 1..now } ?: now
            prefs.edit()
                .putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
                .putBoolean(KEY_FORCE_LOCK_ACTIVE, true)
                .putString(KEY_CYCLE_ID, cycleId)
                .putLong(KEY_CYCLE_STARTED_AT, startedAt)
                .putLong(KEY_CYCLE_UPDATED_AT, now)
                .commit()
        }
    }

    fun isDeviceAdminActive(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        manager.isAdminActive(adminComponent(context))
    }.getOrDefault(false)

    fun tryLockNow(context: Context, force: Boolean = false): LockAttemptResult {
        val appContext = context.applicationContext
        return synchronized(lock) {
            val manager = runCatching { appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager }
                .getOrElse { return@synchronized LockAttemptResult(error = it.message ?: "无法访问设备管理器") }
            if (!runCatching { manager.isAdminActive(adminComponent(appContext)) }.getOrDefault(false)) {
                return@synchronized LockAttemptResult(needsAdmin = true)
            }
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val lastAttempt = prefs.getLong(KEY_LAST_LOCK_ATTEMPT_AT, 0L)
            if (!force && now - lastAttempt < MIN_LOCK_RETRY_INTERVAL_MS) {
                return@synchronized LockAttemptResult(throttled = true)
            }
            prefs.edit().putLong(KEY_LAST_LOCK_ATTEMPT_AT, now).putLong(KEY_CYCLE_UPDATED_AT, now).commit()
            runCatching { manager.lockNow() }
                .fold(
                    onSuccess = { LockAttemptResult(attempted = true, succeeded = true) },
                    onFailure = {
                        Log.e(TAG, "lockNow failed", it)
                        LockAttemptResult(attempted = true, error = it.message ?: "系统锁屏失败")
                    },
                )
        }
    }

    fun lockNow(context: Context, force: Boolean = false): Boolean = tryLockNow(context, force).succeeded

    fun requestDeviceAdmin(activity: Activity): Boolean = runCatching {
        activity.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(activity))
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "用于在连续取消屏幕超时提醒达到设置次数后执行熄屏锁定。")
        })
        true
    }.onFailure { Log.e(TAG, "requestDeviceAdmin failed", it) }.getOrDefault(false)

    private fun migrateLegacyIfNeeded(
        context: Context,
        prefs: android.content.SharedPreferences,
        limit: Int,
        restRequired: Boolean,
        now: Long,
    ) {
        val schema = prefs.getInt(KEY_SCHEMA_VERSION, 0)
        if (schema >= CURRENT_SCHEMA_VERSION) return
        val safeLimit = limit.coerceAtLeast(1)
        val legacyCount = prefs.getInt(KEY_CANCEL_COUNT, 0)
        val legacyForce = prefs.getBoolean(KEY_FORCE_LOCK_ACTIVE, false)
        val legacyActive = prefs.getString(KEY_ACTIVE_SESSION_ID, "").orEmpty()
        val legacyActiveStarted = prefs.getLong(KEY_ACTIVE_SESSION_STARTED_AT, 0L)
        val activeValid = legacyActive.isNotBlank() && legacyActiveStarted in 1..now && now - legacyActiveStarted <= ACTIVE_SESSION_TTL_MS
        val waitingRestValid = restRequired && legacyCount in 1 until safeLimit && !legacyForce
        if (activeValid || waitingRestValid) {
            val cycleId = legacyActive.ifBlank { "legacy-cycle-$now" }
            prefs.edit()
                .putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
                .putInt(KEY_CANCEL_COUNT, if (waitingRestValid) legacyCount else legacyCount.coerceIn(0, safeLimit - 1))
                .putBoolean(KEY_FORCE_LOCK_ACTIVE, false)
                .putString(KEY_ACTIVE_SESSION_ID, if (activeValid) legacyActive else "")
                .putLong(KEY_ACTIVE_SESSION_STARTED_AT, if (activeValid) legacyActiveStarted else 0L)
                .putString(KEY_CYCLE_ID, cycleId)
                .putLong(KEY_CYCLE_STARTED_AT, if (activeValid) legacyActiveStarted else now)
                .putLong(KEY_CYCLE_UPDATED_AT, now)
                .putLong(KEY_LAST_LOCK_ATTEMPT_AT, 0L)
                .commit()
        } else {
            clearTemporaryState(prefs, keepSchema = true)
        }
    }

    private fun clearTemporaryState(prefs: android.content.SharedPreferences, keepSchema: Boolean) {
        val editor = prefs.edit()
            .putInt(KEY_CANCEL_COUNT, 0)
            .putBoolean(KEY_FORCE_LOCK_ACTIVE, false)
            .putLong(KEY_LAST_LOCK_ATTEMPT_AT, 0L)
            .putString(KEY_ACTIVE_SESSION_ID, "")
            .putLong(KEY_ACTIVE_SESSION_STARTED_AT, 0L)
            .putStringSet(KEY_HANDLED_SESSION_IDS, emptySet<String>())
            .putString(KEY_CYCLE_ID, "")
            .putLong(KEY_CYCLE_STARTED_AT, 0L)
            .putLong(KEY_CYCLE_UPDATED_AT, 0L)
        if (keepSchema) editor.putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
        editor.commit()
    }

    private fun adminComponent(context: Context) = ComponentName(context, ReminderDeviceAdminReceiver::class.java)
}
