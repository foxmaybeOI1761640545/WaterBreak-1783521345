package com.randomwaterreminder.app

import android.content.Context
import android.os.PowerManager
import android.provider.Settings

object AlertCoordinator {
    fun alert(context: Context, type: ReminderType, title: String, text: String, config: ReminderConfig = ReminderPreferences.read(context), isTest: Boolean = false, sessionId: String = ""): AlertResult = dispatch(context, type, title, text, config, isTest, sessionId)
    fun alertAsync(context: Context, type: ReminderType, title: String, text: String, config: ReminderConfig = ReminderPreferences.read(context), isTest: Boolean = false, sessionId: String = "", callback: (AlertResult) -> Unit) { callback(dispatch(context, type, title, text, config, isTest, sessionId)) }

    private fun dispatch(context: Context, type: ReminderType, title: String, text: String, config: ReminderConfig, isTest: Boolean, sessionId: String): AlertResult {
        val app = context.applicationContext
        val notes = mutableListOf<String>()
        if (type == ReminderType.SCREEN_LIMIT && sessionId.isNotBlank()) ReminderLockHelper.startSession(app, sessionId)
        val notificationResult = NotificationHelper.showReminder(app, type, title, text, config)
        notes += "通知:${notificationResult.reason}"
        val overlayResult = if (Settings.canDrawOverlays(app)) {
            if (OverlayAlertService.start(app, type, title, text, null, isTest, sessionId)) ChannelResult(true, "已请求悬浮窗") else ChannelResult(false, "悬浮窗服务启动失败")
        } else ChannelResult(false, "未授予悬浮窗权限")
        notes += "悬浮:${overlayResult.reason}"
        val centerResult = runCatching { app.startActivity(ReminderAlertActivity.intent(app, type, title, text, isTest, sessionId)); ChannelResult(true, "已显示居中弹窗") }.getOrElse { ChannelResult(false, it.message ?: "居中弹窗失败") }
        notes += "居中:${centerResult.reason}"
        val (soundOk, soundReason) = ReminderSoundPlayer.play(app, type, config)
        val soundResult = ChannelResult(soundOk, soundReason); notes += "声音:${soundReason}"
        val vibrationOk = NotificationHelper.vibrateAlert(app)
        val vibrationResult = ChannelResult(vibrationOk, if (vibrationOk) "已触发三段振动" else "设备无振动器")
        notes += "振动:${vibrationResult.reason}"
        return AlertResult(
            posted = notificationResult.posted, overlayShown = overlayResult.ok, inAppShown = centerResult.ok,
            fullScreenAttempted = notificationResult.fullScreenAttempted, fallbackUsed = false, reason = notes.joinToString("；"), channelImportance = notificationResult.channelImportance,
            notification = ChannelResult(notificationResult.posted, notificationResult.reason), overlay = overlayResult, centerDialog = centerResult, sound = soundResult, vibration = vibrationResult,
        )
    }

    fun dismissScreenAlert(context: Context, sessionId: String = "") {
        NotificationHelper.cancelScreenAlert(context)
        OverlayAlertService.dismiss(context.applicationContext, ReminderType.SCREEN_LIMIT, sessionId)
        ReminderSoundPlayer.stop(context.applicationContext)
        NotificationHelper.cancelVibration(context.applicationContext)
        runCatching { context.applicationContext.startActivity(ReminderAlertActivity.dismissIntent(context.applicationContext, sessionId)) }
    }
    fun dismissAlert(context: Context, type: ReminderType) { NotificationHelper.cancelAlert(context, type); OverlayAlertService.dismiss(context.applicationContext, type); ReminderSoundPlayer.stop(context.applicationContext) }
}
