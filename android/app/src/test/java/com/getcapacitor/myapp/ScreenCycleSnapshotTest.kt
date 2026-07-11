package com.getcapacitor.myapp

import com.randomwaterreminder.app.ScreenCycleSnapshot
import org.junit.Assert.*
import org.junit.Test

class ScreenCycleSnapshotTest {
    @Test fun blockedAdminIsAnActiveCycleButNotForceLockLabel() {
        val snapshot = ScreenCycleSnapshot(
            cancelCount = 5,
            limit = 5,
            phase = ScreenCycleSnapshot.PHASE_BLOCKED_ADMIN,
            activeSessionId = "",
            sessionStartedAt = 0L,
            cycleId = "cycle-1",
            cycleStartedAt = 1L,
            cycleUpdatedAt = 2L,
        )
        assertTrue(snapshot.inActiveCycle)
        assertEquals(ScreenCycleSnapshot.PHASE_BLOCKED_ADMIN, snapshot.phase)
    }
}
