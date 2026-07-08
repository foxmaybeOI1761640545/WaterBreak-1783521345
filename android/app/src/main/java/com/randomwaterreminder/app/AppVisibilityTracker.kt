package com.randomwaterreminder.app

object AppVisibilityTracker {
    @Volatile
    private var foreground = false

    fun setForeground(value: Boolean) {
        foreground = value
    }

    fun isForeground(): Boolean = foreground
}
