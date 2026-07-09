package com.randomwaterreminder.app

data class ScreenCycleSnapshot(
    val cancelCount: Int,
    val limit: Int,
    val phase: String,
    val activeSessionId: String,
    val sessionStartedAt: Long,
) {
    val inActiveCycle: Boolean get() = phase != PHASE_IDLE

    companion object {
        const val PHASE_IDLE = "idle"
        const val PHASE_ALERTING = "alerting"
        const val PHASE_WAITING_REST = "waiting_rest"
        const val PHASE_FORCE_LOCK = "force_lock"
        const val PHASE_GRACE = "grace"
    }
}
