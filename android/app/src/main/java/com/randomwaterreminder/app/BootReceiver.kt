package com.randomwaterreminder.app

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val supported = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
        if (action !in supported) return

        val appContext = context.applicationContext
        NotificationHelper.ensureChannels(appContext)
        WaterReminderScheduler.rescheduleIfEnabled(appContext)
        ScreenStateTracker.refreshFromSystem(appContext)
        ScreenOnLimitAlarmScheduler.reschedule(
            appContext,
            refreshState = false,
            forceRecalculate = true,
        )
    }
}
