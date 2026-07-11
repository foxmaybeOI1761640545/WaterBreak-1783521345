package com.randomwaterreminder.app

data class ScreenAlertCancelResult(
    val accepted: Boolean,
    val shouldForceLock: Boolean,
    val cancelCount: Int,
    val limit: Int,
    val lockResult: LockAttemptResult = LockAttemptResult(),
) {
    val lockSucceeded: Boolean get() = lockResult.succeeded
    val needsAdmin: Boolean get() = lockResult.needsAdmin
}
