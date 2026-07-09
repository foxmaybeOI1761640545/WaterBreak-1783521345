package com.getcapacitor.myapp

import com.randomwaterreminder.app.RapidScreenOnGraceState
import com.randomwaterreminder.app.ScreenAlertSessionState
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import kotlin.concurrent.thread

class ScreenAlertSessionStateTest {
    @Test fun sameSessionOverlayAndActivityCancelOnlyCountsOnce() {
        val first = ScreenAlertSessionState().start("s1").consume("s1", 3)
        val second = first.state.consume("s1", 3)
        assertTrue(first.accepted)
        assertFalse(second.accepted)
        assertEquals(1, second.state.cancelCount)
    }

    @Test fun doubleClickOldAndBlankSessionsAreIgnoredAndDoNotAffectNewSession() {
        val afterS1 = ScreenAlertSessionState().start("s1").consume("s1", 3).state
        assertFalse(afterS1.consume("s1", 3).accepted)
        assertFalse(afterS1.consume("old", 3).accepted)
        assertFalse(afterS1.consume("", 3).accepted)
        val afterS2 = afterS1.start("s2").consume("s2", 3)
        assertTrue(afterS2.accepted)
        assertEquals(2, afterS2.state.cancelCount)
    }

    @Test fun differentValidSessionsEachCountOnce() {
        val s1 = ScreenAlertSessionState().start("s1").consume("s1", 3).state
        val s2 = s1.start("s2").consume("s2", 3).state
        assertEquals(2, s2.cancelCount)
    }

    @Test fun thresholdActivatesForceLockOnlyOnAcceptedSession() {
        val s1 = ScreenAlertSessionState(cancelCount = 2).start("s3")
        val lock = s1.consume("s3", 3)
        val duplicate = lock.state.consume("s3", 3)
        assertTrue(lock.shouldForceLock)
        assertTrue(lock.state.forceLockActive)
        assertFalse(duplicate.shouldForceLock)
        assertEquals(3, duplicate.state.cancelCount)
    }


    @Test fun sameSessionCancelledThreeTimesOnlyCountsOnce() {
        var state = ScreenAlertSessionState().start("s-repeat")
        repeat(3) { state = state.consume("s-repeat", 5).state }
        assertEquals(1, state.cancelCount)
    }

    @Test fun concurrentCancelsOfSameSessionOnlyCountOnce() {
        val monitor = Any()
        var state = ScreenAlertSessionState().start("s-concurrent")
        val accepted = Collections.synchronizedList(mutableListOf<Boolean>())
        val workers = (1..2).map {
            thread {
                val result = synchronized(monitor) {
                    val r = state.consume("s-concurrent", 5)
                    state = r.state
                    r
                }
                accepted += result.accepted
            }
        }
        workers.forEach { it.join() }
        assertEquals(1, state.cancelCount)
        assertEquals(1, accepted.count { it })
    }

    @Test fun lateOldSessionDoesNotConsumeNewActiveSession() {
        val afterOld = ScreenAlertSessionState().start("old").consume("old", 5).state
        val newActive = afterOld.start("new")
        val oldLate = newActive.consume("old", 5)
        assertFalse(oldLate.accepted)
        assertEquals("new", oldLate.state.activeSessionId)
        val newCancel = oldLate.state.consume("new", 5)
        assertTrue(newCancel.accepted)
        assertEquals(2, newCancel.state.cancelCount)
    }

    @Test fun rapidThreeRealOffToOnEventsEnterGraceButOneOrTwoDoNot() {
        var state = RapidScreenOnGraceState(lastState = "off")
        var result = state.recordState("on", 1_000L, true)
        assertFalse(result.enteredGrace)
        state = result.state.recordState("off", 2_000L, true).state
        result = state.recordState("on", 20_000L, true)
        assertFalse(result.enteredGrace)
        state = result.state.recordState("off", 21_000L, true).state
        result = state.recordState("on", 40_000L, true)
        assertTrue(result.enteredGrace)
        assertEquals(40_000L + RapidScreenOnGraceState.EMERGENCY_GRACE_PERIOD_MS, result.state.graceUntil)
    }

    @Test fun graceSuppressesLockUntilExpiryThenNormalTrackingCanResume() {
        val grace = RapidScreenOnGraceState(graceUntil = 100_000L)
        assertTrue(grace.inGrace(99_999L))
        assertFalse(grace.inGrace(100_000L))
    }

    @Test fun repeatedSameStateAndNonForceLockEventsAreNotCounted() {
        var state = RapidScreenOnGraceState(lastState = "on")
        state = state.recordState("on", 1_000L, true).state
        state = state.recordState("off", 2_000L, false).state
        val result = state.recordState("on", 3_000L, false)
        assertFalse(result.enteredGrace)
        assertTrue(result.state.screenOnTimestamps.isEmpty())
    }
}
