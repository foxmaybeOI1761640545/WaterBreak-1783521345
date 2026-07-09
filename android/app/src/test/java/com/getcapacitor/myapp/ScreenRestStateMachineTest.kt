package com.getcapacitor.myapp

import com.randomwaterreminder.app.ScreenRestSnapshot
import com.randomwaterreminder.app.ScreenRestStateMachine
import org.junit.Assert.*
import org.junit.Test

class ScreenRestStateMachineTest {
    private val minute = 60_000L

    @Test fun screenOnBeforeLimitDoesNotAlert() {
        val decision = ScreenRestStateMachine.evaluate(snapshot(state = "on", now = 1_000L + 4 * minute, lastScreenOn = 1_000L, limit = 5))
        assertFalse(decision.shouldAlert); assertFalse(decision.restRequired); assertEquals(1_000L + 5 * minute, decision.nextScreenCheckAt)
    }
    @Test fun screenOnAtLimitStartsSessionWithoutHardCodedRepeat() {
        val now = 1_000L + 5 * minute
        val decision = ScreenRestStateMachine.evaluate(snapshot(state = "on", now = now, lastScreenOn = 1_000L, limit = 5))
        assertTrue(decision.shouldAlert); assertTrue(decision.restRequired); assertEquals(0L, decision.nextScreenCheckAt)
    }
    @Test fun cancelRequiresFullConfiguredIntervalBeforeNextAlert() {
        val now = 10 * minute
        val decision = ScreenRestStateMachine.evaluate(snapshot(state = "on", now = now, lastScreenOn = 1_000L, limit = 5, restRequired = true, cancelCount = 1, nextAllowedAlertAt = 12 * minute))
        assertFalse(decision.shouldAlert); assertEquals(12 * minute, decision.nextScreenCheckAt)
    }
    @Test fun forceLockRestoredOnUnlock() {
        val decision = ScreenRestStateMachine.evaluate(snapshot(state = "on", now = 20 * minute, lastScreenOn = 19 * minute, forceLockActive = true, cancelCount = 3))
        assertTrue(decision.forceLockActive); assertTrue(decision.shouldForceLock); assertFalse(decision.shouldAlert)
    }
    @Test fun screenOffTooShortKeepsRestRequired() {
        val decision = ScreenRestStateMachine.evaluate(snapshot(state = "off", now = 10 * minute, currentSince = 8 * minute, restStartedAt = 6 * minute, restRequired = true, requiredOff = 5))
        assertFalse(decision.shouldClear); assertTrue(decision.restRequired); assertEquals(13 * minute, decision.nextScreenCheckAt)
    }
    @Test fun screenOffLongEnoughClearsRestAndForceLock() {
        val decision = ScreenRestStateMachine.evaluate(snapshot(state = "off", now = 14 * minute, currentSince = 8 * minute, restStartedAt = 6 * minute, restRequired = true, requiredOff = 5, forceLockActive = true))
        assertTrue(decision.shouldClear); assertFalse(decision.restRequired); assertFalse(decision.forceLockActive)
    }
    @Test fun invalidLastScreenOnDoesNotAlert() {
        val decision = ScreenRestStateMachine.evaluate(snapshot(state = "on", now = 10 * minute, lastScreenOn = 0L, limit = 5))
        assertFalse(decision.shouldAlert); assertFalse(decision.restRequired)
    }
    private fun snapshot(state: String, now: Long, lastScreenOn: Long = 0L, lastScreenOff: Long = 0L, currentSince: Long = lastScreenOn, restRequired: Boolean = false, restStartedAt: Long = 0L, limit: Int = 5, requiredOff: Int = 5, cancelCount: Int = 0, cancelBeforeLockCount: Int = 3, forceLockActive: Boolean = false, nextAllowedAlertAt: Long = 0L) = ScreenRestSnapshot(state, now, lastScreenOn, lastScreenOff, currentSince, restRequired, restStartedAt, limit, requiredOff, cancelCount, cancelBeforeLockCount, forceLockActive, nextAllowedAlertAt)
}

