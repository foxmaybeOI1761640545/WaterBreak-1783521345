package com.randomwaterreminder.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WaterReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val config = ReminderPreferences.read(appContext)
        if (!config.enabled) return
        AlertCoordinator.alert(
            appContext,
            ReminderType.WATER,
            config.waterNotificationTitle,
            config.waterNotificationText,
            config,
        )
        WaterReminderScheduler.scheduleNextReminder(appContext, requestedTime = null)
    }
}
