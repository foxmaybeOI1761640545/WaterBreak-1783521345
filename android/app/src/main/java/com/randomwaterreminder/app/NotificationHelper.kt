package com.randomwaterreminder.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object NotificationHelper {
    const val WATER_CHANNEL_ID = "water_reminder_alert_v1"
    const val SCREEN_CHANNEL_ID = "screen_limit_alert_v1"
    const val OVERLAY_RUNTIME_CHANNEL_ID = "overlay_runtime_v1"
    private const val CHANNEL_STATE_PREFS = "notification_channel_state"
    private const val WATER_NOTIFICATION_ID = 1001
    private const val SCREEN_NOTIFICATION_ID = 5001
    const val OVERLAY_RUNTIME_NOTIFICATION_ID = 7001
    private val VIBRATION_PATTERN = longArrayOf(0, 350, 180, 350, 180, 350)

    fun ensureChannels(context: Context, config: ReminderConfig = ReminderPreferences.read(context)) {
        deleteLegacyDynamicChannels(context)
        ensureAlertChannel(context, ReminderType.WATER, config)
        ensureAlertChannel(context, ReminderType.SCREEN_LIMIT, config)
        ensureOverlayRuntimeChannel(context)
    }

    fun recreateAlertChannel(context: Context, type: ReminderType, config: ReminderConfig = ReminderPreferences.read(context)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.deleteNotificationChannel(channelId(type))
        }
        context.getSharedPreferences(CHANNEL_STATE_PREFS, Context.MODE_PRIVATE).edit()
            .remove(signatureKey(type))
            .apply()
        ensureAlertChannel(context, type, config)
    }

    fun hasNotificationPermission(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun channelStatus(context: Context, type: ReminderType): Pair<Boolean, Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true to NotificationManager.IMPORTANCE_HIGH
        }
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(channelId(type))
        val importance = channel?.importance ?: NotificationManager.IMPORTANCE_NONE
        return (channel != null && importance != NotificationManager.IMPORTANCE_NONE) to importance
    }

    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }

    fun showReminder(
        context: Context,
        type: ReminderType,
        title: String,
        text: String,
        config: ReminderConfig = ReminderPreferences.read(context),
    ): AlertResult {
        ensureChannels(context, config)
        if (!hasNotificationPermission(context)) {
            return AlertResult(posted = false, reason = "通知权限未开启")
        }
        val (channelEnabled, importance) = channelStatus(context, type)
        if (!channelEnabled) {
            return AlertResult(posted = false, reason = "通知渠道已关闭", channelImportance = importance)
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val requestFullScreen = !powerManager.isInteractive && canUseFullScreenIntent(context)
        val notification = buildReminderNotification(context, type, title, text, config, requestFullScreen)
        return runCatching {
            NotificationManagerCompat.from(context).notify(notificationId(type), notification)
            AlertResult(
                posted = true,
                fullScreenAttempted = requestFullScreen,
                channelImportance = importance,
                reason = if (requestFullScreen) "已发送通知并请求锁屏全屏提醒" else "已发送高优先级通知",
            )
        }.getOrElse { error ->
            AlertResult(
                posted = false,
                fullScreenAttempted = false,
                reason = error.message ?: "发送通知失败",
                channelImportance = importance,
            )
        }
    }

    fun buildReminderNotification(
        context: Context,
        type: ReminderType,
        title: String,
        text: String,
        config: ReminderConfig,
        fullScreen: Boolean,
    ): Notification {
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode(type),
            ReminderAlertActivity.intent(context, type, title, text),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, channelId(type))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(pendingIntent)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setVibrate(longArrayOf(0))
                .setSound(null)
        }
        if (fullScreen) builder.setFullScreenIntent(pendingIntent, true)
        return builder.build()
    }

    fun buildOverlayRuntimeNotification(context: Context, type: ReminderType, title: String): Notification {
        ensureOverlayRuntimeChannel(context)
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(context, OVERLAY_RUNTIME_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(if (type == ReminderType.WATER) "正在显示喝水提醒" else "正在显示亮屏超时提醒")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    fun cancelAlert(context: Context, type: ReminderType) {
        NotificationManagerCompat.from(context).cancel(notificationId(type))
    }

    fun cancelScreenAlert(context: Context) {
        cancelAlert(context, ReminderType.SCREEN_LIMIT)
    }

    fun vibrateAlert(context: Context): Boolean {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (!vibrator.hasVibrator()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(VIBRATION_PATTERN, -1)
        }
        return true
    }

    private fun ensureAlertChannel(context: Context, type: ReminderType, config: ReminderConfig) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val id = channelId(type)
        val channelState = context.getSharedPreferences(CHANNEL_STATE_PREFS, Context.MODE_PRIVATE)
        val signature = soundSignature(config, type)
        val existing = manager.getNotificationChannel(id)
        if (existing != null) {
            if (!channelState.contains(signatureKey(type))) {
                channelState.edit().putString(signatureKey(type), signature).apply()
            }
            return
        }

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(
            id,
            if (type == ReminderType.WATER) "喝水提醒" else "亮屏超时提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = if (type == ReminderType.WATER) "随机喝水提醒通知" else "亮屏时间过长提醒通知"
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
        channelState.edit().putString(signatureKey(type), signature).apply()
    }

    private fun ensureOverlayRuntimeChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(OVERLAY_RUNTIME_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                OVERLAY_RUNTIME_CHANNEL_ID,
                "弹窗运行状态",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "仅在显示悬浮提醒时短暂使用"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    private fun deleteLegacyDynamicChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notificationChannels
            .filter {
                it.id == "water_reminder_channel" ||
                    it.id == "water_reminder_alert_channel_v2" ||
                    it.id == "water_reminder_alert_channel_v3" ||
                    it.id == "water_reminder_status_channel" ||
                    it.id == "reminder_status_v1" ||
                    it.id.startsWith("water_reminder_alert_water") ||
                    it.id.startsWith("water_reminder_alert_screen")
            }
            .forEach { manager.deleteNotificationChannel(it.id) }
    }

    private fun soundSignature(config: ReminderConfig, type: ReminderType): String = when (type) {
        ReminderType.WATER -> "${config.waterSoundMode}:${config.waterCustomSoundUri}"
        ReminderType.SCREEN_LIMIT -> "${config.screenSoundMode}:${config.screenCustomSoundUri}"
    }

    private fun signatureKey(type: ReminderType): String = "signature_${type.value}"
    private fun channelId(type: ReminderType): String = if (type == ReminderType.WATER) WATER_CHANNEL_ID else SCREEN_CHANNEL_ID
    private fun notificationId(type: ReminderType): Int = if (type == ReminderType.WATER) WATER_NOTIFICATION_ID else SCREEN_NOTIFICATION_ID
    private fun requestCode(type: ReminderType): Int = if (type == ReminderType.WATER) 3101 else 3102

    private fun resolveSoundUri(config: ReminderConfig, type: ReminderType): Uri {
        val mode = if (type == ReminderType.WATER) config.waterSoundMode else config.screenSoundMode
        val custom = if (type == ReminderType.WATER) config.waterCustomSoundUri else config.screenCustomSoundUri
        if (mode == "custom" && custom.isNotBlank()) {
            return Uri.parse(custom)
        }
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }
}
