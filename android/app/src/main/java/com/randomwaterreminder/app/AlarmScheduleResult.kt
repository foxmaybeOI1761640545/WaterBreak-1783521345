package com.randomwaterreminder.app

data class AlarmScheduleResult(
    val scheduled: Boolean,
    val exact: Boolean,
    val triggerAtMillis: Long,
    val reason: String = "",
)
