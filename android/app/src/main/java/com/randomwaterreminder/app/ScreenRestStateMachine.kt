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
    val restWindowStartedAt: Long = 0L,
    val restAccumulatedOffMs: Long = 0L,
)

data class ScreenRestDecision(
    val restRequired: Boolean,
    val restStartedAt: Long,
    val nextScreenCheckAt: Long,
    val shouldAlert: Boolean,
    val shouldClear: Boolean,
    val forceLockActive: Boolean = false,
    val shouldForceLock: Boolean = false,
    val restWindowStartedAt: Long = 0L,
    val restAccumulatedOffMs: Long = 0L,
)

object ScreenRestStateMachine {
    private const val FORCE_LOCK_RECHECK_INTERVAL_MS = 30_000L

    fun evaluate(snapshot: ScreenRestSnapshot): ScreenRestDecision {
        val onLimitMillis = snapshot.screenOnLimitMinutes.coerceAtLeast(0) * 60_000L
        val offRequiredMillis = snapshot.requiredScreenOffMinutes.coerceAtLeast(1) * 60_000L
        val restWindowMillis = offRequiredMillis * 2L
        val mustLock = snapshot.forceLockActive
        var windowStartedAt = snapshot.restWindowStartedAt.takeIf { it > 0L } ?: snapshot.now
        var accumulatedOff = snapshot.restAccumulatedOffMs.coerceIn(0L, restWindowMillis)
        if ((snapshot.restRequired || mustLock) && snapshot.now >= windowStartedAt + restWindowMillis && accumulatedOff < offRequiredMillis) {
            windowStartedAt = snapshot.now
            accumulatedOff = 0L
        }
        if ((snapshot.restRequired || mustLock) && accumulatedOff >= offRequiredMillis) {
            return ScreenRestDecision(false, 0L, 0L, false, true, false, false, 0L, accumulatedOff)
        }
        if (snapshot.state == "off") {
            if (!snapshot.restRequired && !mustLock) return ScreenRestDecision(false, 0L, 0L, false, false)
            val started = max(snapshot.restStartedAt, snapshot.currentStateSince).takeIf { it > 0L } ?: snapshot.now
            val remainingOff = (offRequiredMillis - accumulatedOff).coerceAtLeast(0L)
            val clearAt = minOf(snapshot.now + remainingOff, windowStartedAt + restWindowMillis)
            return ScreenRestDecision(true, started, clearAt, false, false, mustLock, false, windowStartedAt, accumulatedOff)
        }
        if (snapshot.state != "on" || snapshot.screenOnLimitMinutes <= 0 || snapshot.lastScreenOn <= 0L) {
            val activeRest = snapshot.restRequired || mustLock
            return ScreenRestDecision(activeRest, 0L, 0L, false, false, mustLock, mustLock && snapshot.state == "on", if (activeRest) windowStartedAt else 0L, accumulatedOff)
        }
        if (mustLock) return ScreenRestDecision(true, 0L, minOf(snapshot.now + FORCE_LOCK_RECHECK_INTERVAL_MS, windowStartedAt + restWindowMillis), false, false, true, true, windowStartedAt, accumulatedOff)
        val dueAt = max(snapshot.lastScreenOn + onLimitMillis, snapshot.nextAllowedAlertAt)
        val activeWindowStart = if (snapshot.restRequired) windowStartedAt else snapshot.now
        return if (snapshot.now >= dueAt) ScreenRestDecision(true, 0L, 0L, true, false, false, false, activeWindowStart, accumulatedOff)
        else ScreenRestDecision(snapshot.restRequired, 0L, if (snapshot.restRequired) minOf(dueAt, windowStartedAt + restWindowMillis) else dueAt, false, false, false, false, if (snapshot.restRequired) activeWindowStart else 0L, accumulatedOff)
    }
}
