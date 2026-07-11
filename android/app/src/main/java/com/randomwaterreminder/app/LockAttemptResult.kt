package com.randomwaterreminder.app

data class LockAttemptResult(
    val attempted: Boolean = false,
    val succeeded: Boolean = false,
    val needsAdmin: Boolean = false,
    val throttled: Boolean = false,
    val error: String = "",
)
