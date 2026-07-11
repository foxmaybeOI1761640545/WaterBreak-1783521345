package com.randomwaterreminder.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ScreenOnLimitReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        ReceiverWork.run(this, "ScreenOnLimitReceiver") {
            runCatching { ScreenStateTracker.evaluateAndAlert(appContext, forceDue = true) }
                .onFailure { ScreenOnLimitAlarmScheduler.reschedule(appContext, forceRecalculate = true) }
        }
    }
}
