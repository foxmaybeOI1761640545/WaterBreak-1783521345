package com.randomwaterreminder.app

data class RapidScreenOnGraceState(
    val lastState: String = "unknown",
    val screenOnTimestamps: List<Long> = emptyList(),
    val graceUntil: Long = 0L,
) {
    fun recordState(state: String, now: Long, forceLockActive: Boolean): RapidScreenOnGraceResult {
        if (now < 0L) return RapidScreenOnGraceResult(copy(lastState = state, screenOnTimestamps = emptyList()), false)
        if (now < graceUntil) return RapidScreenOnGraceResult(copy(lastState = state), false)
        val realOffToOn = forceLockActive && lastState == "off" && state == "on"
        val recent = screenOnTimestamps.filter { it in (now - RAPID_SCREEN_ON_WINDOW_MS)..now }
        val next = if (realOffToOn) recent + now else recent
        val enters = next.size >= RAPID_SCREEN_ON_THRESHOLD
        return RapidScreenOnGraceResult(
            state = copy(
                lastState = state,
                screenOnTimestamps = if (enters) emptyList() else next,
                graceUntil = if (enters) now + EMERGENCY_GRACE_PERIOD_MS else graceUntil,
            ),
            enteredGrace = enters,
        )
    }

    fun inGrace(now: Long): Boolean = now in 0 until graceUntil

    companion object {
        const val RAPID_SCREEN_ON_WINDOW_MS = 60_000L
        const val RAPID_SCREEN_ON_THRESHOLD = 3
        const val EMERGENCY_GRACE_PERIOD_MS = 10L * 60L * 1000L
    }
}

data class RapidScreenOnGraceResult(val state: RapidScreenOnGraceState, val enteredGrace: Boolean)
