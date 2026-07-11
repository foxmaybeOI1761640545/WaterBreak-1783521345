package com.randomwaterreminder.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WaterReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        ReceiverWork.run(this, "WaterReminderReceiver") {
            val config = ReminderPreferences.read(appContext)
            if (config.enabled) {
                val sessionId = "water-${System.currentTimeMillis()}"
                AlertCoordinator.alert(
                    appContext,
                    ReminderType.WATER,
                    config.waterNotificationTitle,
                    config.waterNotificationText,
                    config,
                    sessionId = sessionId,
                )
                WaterReminderScheduler.scheduleNextReminder(appContext, requestedTime = null)
            }
        }
    }
}
