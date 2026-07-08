package com.randomwaterreminder.app

enum class ReminderType(val value: String) {
    WATER("water"),
    SCREEN_LIMIT("screen_limit");

    companion object {
        fun from(value: String?): ReminderType = values().firstOrNull { it.value == value } ?: WATER
    }
}
