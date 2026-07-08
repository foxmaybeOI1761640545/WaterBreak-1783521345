package com.randomwaterreminder.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ScreenOnLimitReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        runCatching {
            ScreenStateTracker.evaluateAndAlert(context.applicationContext, forceDue = true)
        }.onFailure {
            ScreenOnLimitAlarmScheduler.reschedule(context.applicationContext, forceRecalculate = true)
        }
    }
}
