package com.randomwaterreminder.app

import kotlin.math.max

data class ScreenRestSnapshot(
    val state: String,
    val now: Long,
    val lastScreenOn: Long,
    val lastScreenOff: Long,
    val currentStateSince: Long,
    val restRequired: Boolean,
    val restStartedAt: Long,
    val screenOnLimitMinutes: Int,
    val requiredScreenOffMinutes: Int,
    val cancelCount: Int = 0,
    val cancelBeforeLockCount: Int = 3,
    val forceLockActive: Boolean = false,
    val nextAllowedAlertAt: Long = 0L,
)

data class ScreenRestDecision(
    val restRequired: Boolean,
    val restStartedAt: Long,
    val nextScreenCheckAt: Long,
    val shouldAlert: Boolean,
    val shouldClear: Boolean,
    val forceLockActive: Boolean = false,
    val shouldForceLock: Boolean = false,
)

object ScreenRestStateMachine {
    fun evaluate(snapshot: ScreenRestSnapshot): ScreenRestDecision {
        val onLimitMillis = snapshot.screenOnLimitMinutes.coerceAtLeast(0) * 60_000L
        val offRequiredMillis = snapshot.requiredScreenOffMinutes.coerceAtLeast(1) * 60_000L
        val mustLock = snapshot.forceLockActive || snapshot.cancelCount >= snapshot.cancelBeforeLockCount.coerceAtLeast(1)
        if (snapshot.state == "off") {
            if (!snapshot.restRequired && !mustLock) return ScreenRestDecision(false, 0L, 0L, false, false)
            val started = max(snapshot.restStartedAt, snapshot.currentStateSince).takeIf { it > 0L } ?: snapshot.now
            val clearAt = started + offRequiredMillis
            return if (snapshot.now >= clearAt) ScreenRestDecision(false, 0L, 0L, false, true, false, false)
            else ScreenRestDecision(true, started, clearAt, false, false, mustLock, false)
        }
        if (snapshot.state != "on" || snapshot.screenOnLimitMinutes <= 0 || snapshot.lastScreenOn <= 0L) {
            return ScreenRestDecision(snapshot.restRequired || mustLock, 0L, 0L, false, false, mustLock, mustLock && snapshot.state == "on")
        }
        if (mustLock) return ScreenRestDecision(true, 0L, snapshot.now + ReminderLockHelper.MIN_LOCK_RETRY_INTERVAL_MS, false, false, true, true)
        val dueAt = max(snapshot.lastScreenOn + onLimitMillis, snapshot.nextAllowedAlertAt)
        return if (snapshot.now >= dueAt) ScreenRestDecision(true, 0L, 0L, true, false, false, false)
        else ScreenRestDecision(snapshot.restRequired, 0L, dueAt, false, false, false, false)
    }
}
