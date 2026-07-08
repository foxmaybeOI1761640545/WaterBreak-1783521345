package com.randomwaterreminder.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import java.io.File

@CapacitorPlugin(
    name = "WaterReminder",
    permissions = [Permission(strings = [Manifest.permission.POST_NOTIFICATIONS], alias = "notifications")],
)
class WaterReminderPlugin : Plugin() {
    override fun load() {
        NotificationHelper.ensureChannels(context)
        ScreenStateTracker.start(context)
        WaterReminderScheduler.rescheduleIfEnabled(context)
    }

    @PluginMethod
    fun startReminder(call: PluginCall) {
        val current = ReminderPreferences.read(context)
        var config = current.copy(
            enabled = call.getBoolean("enabled", true) ?: true,
            startHour = call.getInt("startHour", current.startHour) ?: current.startHour,
            startMinute = call.getInt("startMinute", current.startMinute) ?: current.startMinute,
            endHour = call.getInt("endHour", current.endHour) ?: current.endHour,
            endMinute = call.getInt("endMinute", current.endMinute) ?: current.endMinute,
            minIntervalMinutes = call.getInt("minIntervalMinutes", current.minIntervalMinutes) ?: current.minIntervalMinutes,
            maxIntervalMinutes = call.getInt("maxIntervalMinutes", current.maxIntervalMinutes) ?: current.maxIntervalMinutes,
            nextReminderTime = call.getLong("nextReminderTime", current.nextReminderTime) ?: current.nextReminderTime,
            waterNotificationTitle = call.getString(
                "waterNotificationTitle",
                call.getString("notificationTitle", current.waterNotificationTitle),
            ) ?: current.waterNotificationTitle,
            waterNotificationText = call.getString(
                "waterNotificationText",
                call.getString("notificationText", current.waterNotificationText),
            ) ?: current.waterNotificationText,
            waterSoundMode = call.getString(
                "waterSoundMode",
                call.getString("soundMode", current.waterSoundMode),
            ) ?: current.waterSoundMode,
            waterCustomSoundUri = current.waterCustomSoundUri,
            waterCustomSoundName = current.waterCustomSoundName,
            waterVolumePercent = call.getInt("waterVolumePercent", current.waterVolumePercent) ?: current.waterVolumePercent,
            screenLimitEnabled = call.getBoolean("screenLimitEnabled", current.screenLimitEnabled) ?: current.screenLimitEnabled,
            screenOnLimitMinutes = call.getInt("screenOnLimitMinutes", current.screenOnLimitMinutes) ?: current.screenOnLimitMinutes,
            requiredScreenOffMinutes = call.getInt("requiredScreenOffMinutes", current.requiredScreenOffMinutes) ?: current.requiredScreenOffMinutes,
            cancelBeforeLockCount = call.getInt("cancelBeforeLockCount", current.cancelBeforeLockCount) ?: current.cancelBeforeLockCount,
            screenSoundMode = call.getString("screenSoundMode", current.screenSoundMode) ?: current.screenSoundMode,
            screenCustomSoundUri = current.screenCustomSoundUri,
            screenCustomSoundName = current.screenCustomSoundName,
            screenVolumePercent = call.getInt("screenVolumePercent", current.screenVolumePercent) ?: current.screenVolumePercent,
        )
        validateConfig(config)?.let {
            call.reject(it)
            return
        }

        val waterTimingChanged = current.startHour != config.startHour ||
            current.startMinute != config.startMinute ||
            current.endHour != config.endHour ||
            current.endMinute != config.endMinute ||
            current.minIntervalMinutes != config.minIntervalMinutes ||
            current.maxIntervalMinutes != config.maxIntervalMinutes
        if (waterTimingChanged && config.nextReminderTime == current.nextReminderTime) {
            config = config.copy(nextReminderTime = 0L)
        }

        val screenConfigChanged = current.screenLimitEnabled != config.screenLimitEnabled ||
            current.screenOnLimitMinutes != config.screenOnLimitMinutes ||
            current.requiredScreenOffMinutes != config.requiredScreenOffMinutes ||
            current.cancelBeforeLockCount != config.cancelBeforeLockCount ||
            current.waterVolumePercent != config.waterVolumePercent ||
            current.screenVolumePercent != config.screenVolumePercent

        ReminderPreferences.save(context, config)
        NotificationHelper.ensureChannels(context, config)

        if (config.enabled) {
            WaterReminderScheduler.scheduleNextReminder(context, config.nextReminderTime.takeIf { it > System.currentTimeMillis() })
        } else {
            WaterReminderScheduler.cancelReminder(context)
        }

        when {
            !config.screenLimitEnabled -> ScreenStateTracker.clearRestState(context)
            screenConfigChanged -> ScreenStateTracker.resetForConfigChange(context)
            else -> ScreenOnLimitAlarmScheduler.reschedule(context, forceRecalculate = true)
        }
        call.resolve(ReminderPreferences.read(context).toJsObject())
    }

    @PluginMethod
    fun stopReminder(call: PluginCall) {
        call.resolve(WaterReminderScheduler.cancelReminder(context).toJsObject())
    }

    @PluginMethod
    fun getStatus(call: PluginCall) {
        val config = ReminderPreferences.read(context)
        NotificationHelper.ensureChannels(context, config)
        WaterReminderScheduler.rescheduleIfEnabled(context)
        ScreenStateTracker.start(context)
        call.resolve(ReminderPreferences.read(context).toJsObject())
    }

    @PluginMethod
    fun showTestNotification(call: PluginCall) {
        val type = ReminderType.from(call.getString("type", ReminderType.WATER.value))
        val config = ReminderPreferences.read(context)
        val title: String
        val text: String
        if (type == ReminderType.WATER) {
            title = "${config.waterNotificationTitle}（测试）"
            text = config.waterNotificationText
        } else {
            title = "亮屏时间过长（测试）"
            text = "已经连续亮屏 ${config.screenOnLimitMinutes.coerceAtLeast(1)} 分钟以上，建议息屏休息一下。"
        }
        AlertCoordinator.alertAsync(context, type, title, text, config, isTest = true, sessionId = "test-${System.currentTimeMillis()}") { result ->
            call.resolve(result.toJsObject())
        }
    }

    @PluginMethod
    fun saveCustomSound(call: PluginCall) {
        val type = ReminderType.from(call.getString("type", ReminderType.WATER.value))
        val fileName = call.getString("fileName", "custom-sound") ?: "custom-sound"
        val mimeType = call.getString("mimeType", "audio/mpeg") ?: "audio/mpeg"
        val dataBase64 = call.getString("dataBase64")
        if (dataBase64.isNullOrBlank()) {
            call.reject("音频文件为空")
            return
        }
        val extensionFromName = fileName.substringAfterLast('.', "mp3").lowercase()
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?: extensionFromName.takeIf { it in setOf("mp3", "wav", "ogg", "m4a", "aac", "flac") }
            ?: "mp3"
        if (!mimeType.startsWith("audio/") && extension !in setOf("mp3", "wav", "ogg", "m4a", "aac", "flac")) {
            call.reject("请选择常见音频格式文件")
            return
        }

        runCatching {
            val soundDir = File(context.filesDir, "sounds").apply { mkdirs() }
            val sourceVersion = kotlin.math.abs((fileName + dataBase64.take(64)).hashCode())
            val soundFile = File(soundDir, "${type.value}-reminder-v${sourceVersion}.$extension")
            soundFile.writeBytes(Base64.decode(dataBase64, Base64.DEFAULT))
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", soundFile)
            context.grantUriPermission(
                "com.android.systemui",
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            val current = ReminderPreferences.read(context)
            val updated = if (type == ReminderType.WATER) {
                current.copy(
                    waterSoundMode = "custom",
                    waterCustomSoundUri = uri.toString(),
                    waterCustomSoundName = fileName,
                )
            } else {
                current.copy(
                    screenSoundMode = "custom",
                    screenCustomSoundUri = uri.toString(),
                    screenCustomSoundName = fileName,
                )
            }
            ReminderPreferences.save(context, updated)
            NotificationHelper.recreateAlertChannel(context, type, updated)
            call.resolve(updated.toJsObject())
        }.getOrElse { error -> call.reject(error.message ?: "导入提示音失败") }
    }

    @PluginMethod
    fun useDefaultSound(call: PluginCall) {
        val type = ReminderType.from(call.getString("type", ReminderType.WATER.value))
        val current = ReminderPreferences.read(context)
        val updated = if (type == ReminderType.WATER) {
            current.copy(waterSoundMode = "default")
        } else {
            current.copy(screenSoundMode = "default")
        }
        ReminderPreferences.save(context, updated)
        NotificationHelper.recreateAlertChannel(context, type, updated)
        call.resolve(updated.toJsObject())
    }

    @PluginMethod
    fun getScreenState(call: PluginCall) {
        call.resolve(ScreenStateTracker.status(context))
    }

    @PluginMethod
    fun openExactAlarmSettings(call: PluginCall) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            call.resolve(JSObject().put("opened", false))
            return
        }
        runCatching { context.startActivity(WaterReminderScheduler.exactAlarmSettingsIntent(context)) }
            .onSuccess { call.resolve(JSObject().put("opened", true)) }
            .onFailure { call.reject(it.message ?: "无法打开精确闹钟设置") }
    }

    @PluginMethod
    fun openNotificationSettings(call: PluginCall) {
        val type = ReminderType.from(call.getString("type", ReminderType.WATER.value))
        val channelId = if (type == ReminderType.WATER) {
            NotificationHelper.WATER_CHANNEL_ID
        } else {
            NotificationHelper.SCREEN_CHANNEL_ID
        }
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
            }
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra("app_package", context.packageName)
                putExtra("app_uid", context.applicationInfo.uid)
            }
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onSuccess { call.resolve(JSObject().put("opened", true)) }
            .onFailure { call.reject(it.message ?: "无法打开通知设置") }
    }

    @PluginMethod
    fun openOverlaySettings(call: PluginCall) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onSuccess { call.resolve(JSObject().put("opened", true)) }
            .onFailure { call.reject(it.message ?: "无法打开悬浮窗设置") }
    }

    @PluginMethod
    fun openUsageAccessSettings(call: PluginCall) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onSuccess { call.resolve(JSObject().put("opened", true)) }
            .onFailure { call.reject(it.message ?: "无法打开使用情况访问设置") }
    }

    @PluginMethod
    fun openFullScreenIntentSettings(call: PluginCall) {
        if (Build.VERSION.SDK_INT < 34) {
            call.resolve(JSObject().put("opened", false))
            return
        }
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }.onSuccess { call.resolve(JSObject().put("opened", true)) }
            .onFailure { call.reject(it.message ?: "无法打开全屏提醒设置") }
    }

    @PluginMethod
    fun requestNotificationPermission(call: PluginCall) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            getPermissionState("notifications") != com.getcapacitor.PermissionState.GRANTED
        ) {
            requestPermissionForAlias("notifications", call, "notificationPermissionCallback")
        } else {
            call.resolve(permissionStatus())
        }
    }

    @PermissionCallback
    private fun notificationPermissionCallback(call: PluginCall) {
        call.resolve(permissionStatus())
    }

    @PluginMethod
    fun getPermissionStatus(call: PluginCall) {
        call.resolve(permissionStatus())
    }

    private fun permissionStatus(): JSObject {
        val exactAlarms = if (WaterReminderScheduler.canScheduleExact(context)) "granted" else "denied"
        val notifications = if (NotificationHelper.hasNotificationPermission(context)) "granted" else "denied"
        val fullScreenIntent = if (NotificationHelper.canUseFullScreenIntent(context)) "granted" else "denied"
        val overlays = if (Settings.canDrawOverlays(context)) "granted" else "denied"
        val usageStats = if (ScreenUsageEventReader.hasPermission(context)) "granted" else "denied"
        val waterChannel = NotificationHelper.channelStatus(context, ReminderType.WATER)
        val screenChannel = NotificationHelper.channelStatus(context, ReminderType.SCREEN_LIMIT)
        return JSObject().apply {
            put("notifications", notifications)
            put("exactAlarms", exactAlarms)
            put("fullScreenIntent", fullScreenIntent)
            put("overlays", overlays)
            put("usageStats", usageStats)
            put("waterChannelEnabled", waterChannel.first)
            put("waterChannelImportance", waterChannel.second)
            put("screenChannelEnabled", screenChannel.first)
            put("screenChannelImportance", screenChannel.second)
        }
    }

    private fun validateConfig(config: ReminderConfig): String? = when {
        config.startHour !in 0..23 || config.endHour !in 0..23 -> "小时必须在 0-23 之间"
        config.startMinute !in 0..59 || config.endMinute !in 0..59 -> "分钟必须在 0-59 之间"
        config.minIntervalMinutes < 1 -> "最小间隔必须大于 0"
        config.maxIntervalMinutes < config.minIntervalMinutes -> "最大间隔不可小于最小间隔"
        config.screenOnLimitMinutes < 0 -> "亮屏超时提醒分钟数不可小于 0"
        config.requiredScreenOffMinutes < 1 -> "连续息屏分钟数必须大于 0"
        config.cancelBeforeLockCount < 1 -> "取消后强制熄屏次数必须大于 0"
        config.waterVolumePercent !in 0..100 || config.screenVolumePercent !in 0..100 -> "提醒音量必须在 0-100 之间"
        config.waterSoundMode == "custom" && config.waterCustomSoundUri.isBlank() -> "请先导入喝水提醒自定义提示音"
        config.screenSoundMode == "custom" && config.screenCustomSoundUri.isBlank() -> "请先导入屏幕提醒自定义提示音"
        else -> null
    }

    private fun ReminderConfig.toJsObject(): JSObject {
        val waterAlarm = WaterReminderScheduler.lastScheduleResult(context)
        val screenAlarm = ScreenOnLimitAlarmScheduler.lastScheduleResult(context)
        return JSObject().apply {
            put("enabled", enabled)
            put("startHour", startHour)
            put("startMinute", startMinute)
            put("endHour", endHour)
            put("endMinute", endMinute)
            put("minIntervalMinutes", minIntervalMinutes)
            put("maxIntervalMinutes", maxIntervalMinutes)
            put("nextReminderTime", nextReminderTime)
            put("waterNotificationTitle", waterNotificationTitle)
            put("waterNotificationText", waterNotificationText)
            put("waterSoundMode", waterSoundMode)
            put("waterCustomSoundUri", waterCustomSoundUri)
            put("waterCustomSoundName", waterCustomSoundName)
            put("waterVolumePercent", waterVolumePercent)
            put("screenLimitEnabled", screenLimitEnabled)
            put("screenOnLimitMinutes", screenOnLimitMinutes)
            put("requiredScreenOffMinutes", requiredScreenOffMinutes)
            put("cancelBeforeLockCount", cancelBeforeLockCount)
            put("cancelCycleCount", ReminderLockHelper.cancelCount(context))
            put("screenSoundMode", screenSoundMode)
            put("screenCustomSoundUri", screenCustomSoundUri)
            put("screenCustomSoundName", screenCustomSoundName)
            put("screenVolumePercent", screenVolumePercent)
            put("waterAlarmScheduled", waterAlarm.scheduled)
            put("waterAlarmExact", waterAlarm.exact)
            put("waterAlarmReason", waterAlarm.reason)
            put("screenAlarmScheduled", screenAlarm.scheduled)
            put("screenAlarmExact", screenAlarm.exact)
            put("screenAlarmReason", screenAlarm.reason)
            // Compatibility aliases used by older web bundles.
            put("notificationTitle", waterNotificationTitle)
            put("notificationText", waterNotificationText)
            put("soundMode", waterSoundMode)
            put("customSoundUri", waterCustomSoundUri)
            put("customSoundName", waterCustomSoundName)
        }
    }
}
