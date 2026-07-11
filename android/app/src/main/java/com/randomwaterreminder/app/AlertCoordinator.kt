package com.randomwaterreminder.app

import android.content.Context
import android.provider.Settings
import android.util.Log

object AlertCoordinator {
    private const val TAG = "AlertCoordinator"

    fun alert(
        context: Context,
        type: ReminderType,
        title: String,
        text: String,
        config: ReminderConfig = ReminderPreferences.read(context),
        isTest: Boolean = false,
        sessionId: String = "",
    ): AlertResult = dispatch(context, type, title, text, config, isTest, sessionId)

    fun alertAsync(
        context: Context,
        type: ReminderType,
        title: String,
        text: String,
        config: ReminderConfig = ReminderPreferences.read(context),
        isTest: Boolean = false,
        sessionId: String = "",
        callback: (AlertResult) -> Unit,
    ) {
        callback(dispatch(context, type, title, text, config, isTest, sessionId))
    }

    private fun dispatch(
        context: Context,
        type: ReminderType,
        title: String,
        text: String,
        config: ReminderConfig,
        isTest: Boolean,
        sessionId: String,
    ): AlertResult {
        val app = context.applicationContext
        val notes = mutableListOf<String>()
        val isScreenSession = !isTest && type == ReminderType.SCREEN_LIMIT && sessionId.isNotBlank()
        if (isScreenSession && !ReminderLockHelper.startSession(app, sessionId)) {
            return AlertResult(reason = "已有未处理的屏幕提醒会话，忽略重复创建")
        }

        val notificationResult = runCatching {
            NotificationHelper.showReminder(app, type, title, text, config, sessionId, isTest)
        }.getOrElse {
            Log.e(TAG, "notification failed type=${type.value} session=$sessionId", it)
            AlertResult(posted = false, reason = it.message ?: "通知失败")
        }
        notes += "通知:${notificationResult.reason}"

        val overlayResult = if (runCatching { Settings.canDrawOverlays(app) }.getOrDefault(false)) {
            if (OverlayAlertService.start(app, type, title, text, null, isTest, sessionId)) {
                ChannelResult(true, "已请求悬浮窗")
            } else {
                ChannelResult(false, "悬浮窗服务启动失败")
            }
        } else {
            ChannelResult(false, "未授予悬浮窗权限")
        }
        notes += "悬浮:${overlayResult.reason}"

        val centerResult = runCatching {
            if (type == ReminderType.WATER) {
                app.startActivity(AppNavigation.waterCheckInIntent(app, sessionId, isTest))
                ChannelResult(true, "已打开应用内喝水确认页")
            } else {
                app.startActivity(ReminderAlertActivity.intent(app, type, title, text, isTest, sessionId))
                ChannelResult(true, "已显示居中弹窗")
            }
        }.getOrElse {
            Log.w(TAG, "center activity failed type=${type.value} session=$sessionId", it)
            ChannelResult(false, it.message ?: "居中弹窗失败")
        }
        notes += "居中:${centerResult.reason}"

        val soundResult = runCatching {
            val (ok, reason) = ReminderSoundPlayer.play(app, type, config)
            ChannelResult(ok, reason)
        }.getOrElse {
            Log.w(TAG, "sound failed type=${type.value}", it)
            ChannelResult(false, it.message ?: "声音播放失败")
        }
        notes += "声音:${soundResult.reason}"

        val vibrationOk = NotificationHelper.vibrateAlert(app)
        val vibrationResult = ChannelResult(vibrationOk, if (vibrationOk) "已触发三段振动" else "振动不可用")
        notes += "振动:${vibrationResult.reason}"

        if (isScreenSession && !notificationResult.posted && !overlayResult.ok && !centerResult.ok) {
            ReminderLockHelper.invalidateActiveSession(app, sessionId)
        }

        return AlertResult(
            posted = notificationResult.posted,
            overlayShown = overlayResult.ok,
            inAppShown = centerResult.ok,
            fullScreenAttempted = notificationResult.fullScreenAttempted,
            fallbackUsed = false,
            reason = notes.joinToString("；"),
            channelImportance = notificationResult.channelImportance,
            notification = ChannelResult(notificationResult.posted, notificationResult.reason),
            overlay = overlayResult,
            centerDialog = centerResult,
            sound = soundResult,
            vibration = vibrationResult,
        )
    }

    fun dismissScreenAlert(context: Context, sessionId: String = "", closeActivity: Boolean = true) {
        val app = context.applicationContext
        NotificationHelper.cancelScreenAlert(app)
        OverlayAlertService.dismiss(app, ReminderType.SCREEN_LIMIT, sessionId)
        NotificationHelper.cancelVibration(app)
        ReminderLockHelper.invalidateActiveSession(app, sessionId)
        if (closeActivity) ReminderAlertActivity.dismissActive(ReminderType.SCREEN_LIMIT, sessionId)
    }

    fun dismissAlert(context: Context, type: ReminderType, closeActivity: Boolean = true) {
        val app = context.applicationContext
        NotificationHelper.cancelAlert(app, type)
        OverlayAlertService.dismiss(app, type)
        NotificationHelper.cancelVibration(app)
        if (closeActivity) ReminderAlertActivity.dismissActive(type)
    }
}
