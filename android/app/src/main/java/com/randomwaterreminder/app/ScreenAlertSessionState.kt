package com.randomwaterreminder.app

/** Pure state holder used to make one logical screen reminder session idempotent. */
data class ScreenAlertSessionState(
    val activeSessionId: String = "",
    val handledSessionIds: Set<String> = emptySet(),
    val cancelCount: Int = 0,
    val forceLockActive: Boolean = false,
) {
    fun start(sessionId: String): ScreenAlertSessionState = copy(
        activeSessionId = sessionId,
        handledSessionIds = handledSessionIds - sessionId,
    )

    fun consume(sessionId: String?, cancelBeforeLockCount: Int): ConsumeResult {
        val id = sessionId.orEmpty()
        if (id.isBlank() || id != activeSessionId || handledSessionIds.contains(id)) {
            return ConsumeResult(this, accepted = false, shouldForceLock = false)
        }
        val nextCount = cancelCount + 1
        val shouldLock = nextCount >= cancelBeforeLockCount.coerceAtLeast(1)
        return ConsumeResult(
            state = copy(
                activeSessionId = "",
                handledSessionIds = (handledSessionIds + id).takeLast(MAX_HANDLED_SESSIONS).toSet(),
                cancelCount = nextCount,
                forceLockActive = forceLockActive || shouldLock,
            ),
            accepted = true,
            shouldForceLock = shouldLock,
        )
    }

    fun reset(): ScreenAlertSessionState = copy(activeSessionId = "", cancelCount = 0, forceLockActive = false)

    data class ConsumeResult(val state: ScreenAlertSessionState, val accepted: Boolean, val shouldForceLock: Boolean)

    companion object { private const val MAX_HANDLED_SESSIONS = 20 }
}
