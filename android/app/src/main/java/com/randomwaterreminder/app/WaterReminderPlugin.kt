package com.randomwaterreminder.app

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
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
import org.json.JSONArray
import org.json.JSONObject
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
        NotificationHelper.refreshScreenStatus(context)
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
            waterRetryMinutes = call.getInt("waterRetryMinutes", current.waterRetryMinutes) ?: current.waterRetryMinutes,
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
            current.cancelBeforeLockCount != config.cancelBeforeLockCount

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
        NotificationHelper.refreshStatusNotifications(context)
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
        NotificationHelper.refreshStatusNotifications(context)
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
            text = "已经连续亮屏 ${config.screenOnLimitMinutes.coerceAtLeast(1)} 分钟以上，请在 ${config.requiredScreenOffMinutes.coerceAtLeast(1) * 2} 分钟内累计息屏 ${config.requiredScreenOffMinutes.coerceAtLeast(1)} 分钟。"
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
    fun getScreenDashboardState(call: PluginCall) {
        call.resolve(ScreenStateTracker.dashboardStatus(context))
    }

    @PluginMethod
    fun getWaterCheckInHistory(call: PluginCall) {
        call.resolve(JSObject.fromJSONObject(WaterCheckInStore.snapshot(
            context,
            call.getInt("limit", 20) ?: 20,
            call.getBoolean("deletedOnly", false) ?: false,
        )))
    }

    @PluginMethod
    fun getWaterContainers(call: PluginCall) {
        call.resolve(JSObject.fromJSONObject(JSONObject().put("containers", WaterContainerStore.list(context))))
    }

    @PluginMethod
    fun saveWaterContainer(call: PluginCall) {
        runCatching {
            WaterContainerStore.save(
                context,
                call.getString("id"),
                call.getString("name").orEmpty(),
                call.getDouble("emptyWeightGrams") ?: Double.NaN,
            )
        }.onSuccess {
            call.resolve(JSObject.fromJSONObject(JSONObject().put("containers", WaterContainerStore.list(context))))
        }.onFailure { call.reject(it.message ?: "保存容器失败") }
    }

    @PluginMethod
    fun deleteWaterContainer(call: PluginCall) {
        val deleted = WaterContainerStore.delete(context, call.getString("id").orEmpty())
        call.resolve(JSObject.fromJSONObject(JSONObject().put("deleted", deleted).put("containers", WaterContainerStore.list(context))))
    }

    @PluginMethod
    fun dismissWaterAlertUi(call: PluginCall) {
        AlertCoordinator.dismissAlert(context, ReminderType.WATER, closeActivity = false)
        call.resolve()
    }

    @PluginMethod
    fun saveWaterDrankRecord(call: PluginCall) {
        val amountMl = call.getDouble("amountMl")
        if (amountMl == null) {
            call.reject("请输入有效饮水量")
            return
        }
        val sessionId = call.getString("sessionId").orEmpty()
        val photos = saveWaterPhotos(call, "drank") ?: return
        val measurement = WaterMeasurement(
            entryMode = call.getString("entryMode", "volume") ?: "volume",
            drinkType = call.getString("drinkType", "白水") ?: "白水",
            description = call.getString("description").orEmpty(),
            containerId = call.getString("containerId").orEmpty(),
            containerName = call.getString("containerName").orEmpty(),
            emptyWeightGrams = call.getDouble("emptyWeightGrams"),
            totalWeightGrams = call.getDouble("totalWeightGrams"),
        )
        if (!WaterCheckInStore.recordDrank(context, sessionId, amountMl, photos, measurement)) {
            photos.forEach { it.delete() }
            call.reject("喝水记录保存失败或本次提醒已经处理")
            return
        }
        if (sessionId.isBlank()) {
            val config = ReminderPreferences.read(context)
            if (config.enabled) {
                val next = WaterReminderScheduler.calculateNextReminderTime(config, System.currentTimeMillis())
                WaterReminderScheduler.scheduleNextReminder(context, next)
            }
        }
        call.resolve(JSObject.fromJSONObject(WaterCheckInStore.snapshot(context)))
    }

    @PluginMethod
    fun recordWaterNotDrank(call: PluginCall) {
        val result = WaterCheckInStore.recordNotDrank(context, call.getString("sessionId").orEmpty())
        if (result.accepted && !result.requiresStatePhoto) {
            val config = ReminderPreferences.read(context)
            WaterReminderScheduler.scheduleNextReminder(
                context,
                System.currentTimeMillis() + config.waterRetryMinutes.coerceIn(1, 180) * 60_000L,
            )
        }
        call.resolve(JSObject().apply {
            put("accepted", result.accepted)
            put("consecutiveCount", result.consecutiveCount)
            put("requiresStatePhoto", result.requiresStatePhoto)
            put("retryMinutes", ReminderPreferences.read(context).waterRetryMinutes)
        })
    }

    @PluginMethod
    fun saveWaterStateCheck(call: PluginCall) {
        val photo = saveWaterPhoto(call, "state") ?: return
        if (!WaterCheckInStore.recordForcedStatePhoto(context, call.getString("sessionId").orEmpty(), photo)) {
            photo.delete()
            call.reject("状态验证照片保存失败")
            return
        }
        val config = ReminderPreferences.read(context)
        WaterReminderScheduler.scheduleNextReminder(
            context,
            System.currentTimeMillis() + config.waterRetryMinutes.coerceIn(1, 180) * 60_000L,
        )
        call.resolve(JSObject.fromJSONObject(WaterCheckInStore.snapshot(context)))
    }

    @PluginMethod
    fun updateWaterRecordDescription(call: PluginCall) {
        val id = call.getString("id").orEmpty()
        val description = call.getString("description").orEmpty()
        if (description.length > 500) {
            call.reject("喝水说明不能超过 500 个字符")
            return
        }
        val updated = WaterCheckInStore.updateDescription(context, id, description)
        call.resolve(JSObject.fromJSONObject(JSONObject()
            .put("updated", updated)
            .put("history", WaterCheckInStore.snapshot(context, 200))))
    }

    @PluginMethod
    fun deleteWaterRecord(call: PluginCall) {
        val deleted = WaterCheckInStore.softDelete(context, call.getString("id").orEmpty())
        call.resolve(JSObject.fromJSONObject(JSONObject()
            .put("deleted", deleted)
            .put("history", WaterCheckInStore.snapshot(context, 200))))
    }

    @PluginMethod
    fun restoreWaterRecord(call: PluginCall) {
        val restored = WaterCheckInStore.restore(context, call.getString("id").orEmpty())
        call.resolve(JSObject.fromJSONObject(JSONObject()
            .put("restored", restored)
            .put("history", WaterCheckInStore.snapshot(context, 200))))
    }

    @PluginMethod
    fun getWaterPhoto(call: PluginCall) {
        val file = WaterCheckInStore.photoFile(context, call.getString("photoFileName").orEmpty())
        if (file == null) {
            call.reject("找不到本地照片")
            return
        }
        val mimeType = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        call.resolve(JSObject().apply {
            put("mimeType", mimeType)
            put("dataBase64", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
        })
    }

    @PluginMethod
    fun shareWaterDataExport(call: PluginCall) {
        runCatching {
            val archive = WaterDataArchive.create(context)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archive)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, archive.name)
                clipData = ClipData.newRawUri(archive.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(sendIntent, "导出喝水数据").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            archive.name
        }.onSuccess { fileName ->
            call.resolve(JSObject().apply { put("opened", true); put("fileName", fileName) })
        }.onFailure { call.reject(it.message ?: "导出喝水数据失败") }
    }

    @PluginMethod
    fun importWaterData(call: PluginCall) {
        val dataBase64 = call.getString("dataBase64")
        if (dataBase64.isNullOrBlank() || dataBase64.length > 260_000_000) {
            call.reject("迁移档案为空或过大")
            return
        }
        runCatching {
            val archive = File(context.cacheDir, "water-import-${System.currentTimeMillis()}.zip")
            archive.writeBytes(Base64.decode(dataBase64, Base64.DEFAULT))
            WaterDataArchive.import(context, archive)
        }.onSuccess {
            call.resolve(JSObject.fromJSONObject(JSONObject()
                .put("history", WaterCheckInStore.snapshot(context, 100))
                .put("containers", WaterContainerStore.list(context))))
        }.onFailure { call.reject(it.message ?: "导入喝水数据失败") }
    }


    @PluginMethod
    fun openExactAlarmSettings(call: PluginCall) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            call.resolve(settingsResult(false, "exact_alarm", reason = "当前 Android 版本无需单独设置精确闹钟"))
            return
        }
        openSettingsIntent(call, WaterReminderScheduler.exactAlarmSettingsIntent(context), "exact_alarm", fallback = appDetailsIntent())
    }

    @PluginMethod
    fun openNotificationSettings(call: PluginCall) {
        val type = ReminderType.from(call.getString("type", ReminderType.WATER.value))
        val channelId = if (type == ReminderType.WATER) NotificationHelper.WATER_CHANNEL_ID else NotificationHelper.SCREEN_CHANNEL_ID
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
        }
        openSettingsIntent(call, intent, "notification_${type.value}", fallback = appDetailsIntent())
    }

    @PluginMethod
    fun openOverlaySettings(call: PluginCall) {
        openSettingsIntent(
            call,
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
            "overlay",
            fallback = appDetailsIntent(),
        )
    }

    @PluginMethod
    fun openUsageAccessSettings(call: PluginCall) {
        openSettingsIntent(call, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), "usage_access", fallback = appDetailsIntent())
    }

    @PluginMethod
    fun openFullScreenIntentSettings(call: PluginCall) {
        if (Build.VERSION.SDK_INT < 34) {
            call.resolve(settingsResult(false, "full_screen_intent", reason = "当前 Android 版本无需单独设置全屏提醒"))
            return
        }
        openSettingsIntent(
            call,
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply { data = Uri.parse("package:${context.packageName}") },
            "full_screen_intent",
            fallback = appDetailsIntent(),
        )
    }

    @PluginMethod
    fun openDeviceAdminSettings(call: PluginCall) {
        val addAdmin = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(context, ReminderDeviceAdminReceiver::class.java))
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "用于连续取消屏幕提醒后执行系统锁屏。")
        }
        val fallback = Intent(Settings.ACTION_SECURITY_SETTINGS)
        openSettingsIntent(call, addAdmin, "device_admin", fallback = fallback, fallbackTarget = "device_security")
    }

    @PluginMethod
    fun openAppDetailsSettings(call: PluginCall) {
        openSettingsIntent(call, appDetailsIntent(), "app_details")
    }

    @PluginMethod
    fun openBatteryOptimizationSettings(call: PluginCall) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            appDetailsIntent()
        }
        openSettingsIntent(call, intent, "battery_optimization", fallback = appDetailsIntent())
    }

    @PluginMethod
    fun openManufacturerPermissionSettings(call: PluginCall) {
        if (!isXiaomiLikeDevice()) {
            call.resolve(settingsResult(false, "manufacturer", reason = "当前设备不是 MIUI/HyperOS，未显示小米专用入口"))
            return
        }
        val target = call.getString("target", "permissions") ?: "permissions"
        val intent = when (target) {
            "autostart" -> Intent("miui.intent.action.OP_AUTO_START").apply {
                setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            }
            else -> Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                putExtra("extra_pkgname", context.packageName)
            }
        }
        openSettingsIntent(call, intent, "miui_$target")
    }

    private fun openSettingsIntent(
        call: PluginCall,
        intent: Intent,
        target: String,
        fallback: Intent? = null,
        fallbackTarget: String = "app_details",
    ) {
        val hostActivity = activity

        fun launch(candidate: Intent): Result<Unit> = runCatching {
            // Do not preflight system Settings intents with resolveActivity(). On
            // Android 11+ and some MIUI/HyperOS builds package visibility may make
            // that check return null even though startActivity() succeeds.
            if (hostActivity != null) {
                hostActivity.startActivity(candidate)
            } else {
                context.startActivity(candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        val primaryResult = launch(intent)
        if (primaryResult.isSuccess) {
            call.resolve(settingsResult(true, target))
            return
        }

        if (fallback != null) {
            val fallbackResult = launch(fallback)
            if (fallbackResult.isSuccess) {
                call.resolve(settingsResult(true, fallbackTarget, fallback = true))
                return
            }
            val error = fallbackResult.exceptionOrNull() ?: primaryResult.exceptionOrNull()
            call.resolve(settingsResult(false, fallbackTarget, fallback = true, reason = settingsLaunchError(error)))
            return
        }

        call.resolve(settingsResult(false, target, reason = settingsLaunchError(primaryResult.exceptionOrNull())))
    }

    private fun settingsLaunchError(error: Throwable?): String = when (error) {
        is ActivityNotFoundException -> "系统没有可打开的设置页面"
        else -> error?.message ?: "无法打开系统设置"
    }

    private fun settingsResult(opened: Boolean, target: String, fallback: Boolean = false, reason: String? = null): JSObject = JSObject().apply {
        put("opened", opened)
        put("target", target)
        put("fallback", fallback)
        if (!reason.isNullOrBlank()) put("reason", reason)
    }

    private fun appDetailsIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:${context.packageName}") }
    private fun isXiaomiLikeDevice(): Boolean = listOf(Build.MANUFACTURER, Build.BRAND).any { it.contains("xiaomi", ignoreCase = true) || it.contains("redmi", ignoreCase = true) || it.contains("poco", ignoreCase = true) }

    private fun saveWaterPhoto(call: PluginCall, prefix: String): File? {
        val mimeType = call.getString("mimeType", "image/jpeg") ?: "image/jpeg"
        val dataBase64 = call.getString("dataBase64")
        if (!mimeType.startsWith("image/") || dataBase64.isNullOrBlank()) {
            call.reject("请选择有效自拍图片")
            return null
        }
        if (dataBase64.length > 30_000_000) {
            call.reject("自拍图片过大，请选择小于约 20MB 的图片")
            return null
        }
        val extension = when (mimeType.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        return runCatching {
            WaterCheckInStore.createPhotoFile(context, prefix, extension).apply {
                writeBytes(Base64.decode(dataBase64, Base64.DEFAULT))
                require(length() > 0L) { "自拍图片为空" }
            }
        }.getOrElse {
            call.reject(it.message ?: "保存自拍失败")
            null
        }
    }

    private fun saveWaterPhotos(call: PluginCall, prefix: String): List<File>? {
        val encodedPhotos = call.getArray("photos")?.let { JSONArray(it.toString()) } ?: JSONArray()
        if (encodedPhotos.length() > 6) {
            call.reject("每条记录最多保存 6 张凭证照片")
            return null
        }
        // Accept the pre-v1.0.14 single-photo shape so older web bundles remain usable.
        if (encodedPhotos.length() == 0 && !call.getString("dataBase64").isNullOrBlank()) {
            return saveWaterPhoto(call, prefix)?.let(::listOf)
        }
        val saved = mutableListOf<File>()
        var encodedSize = 0L
        for (index in 0 until encodedPhotos.length()) {
            val item = encodedPhotos.optJSONObject(index)
            val mimeType = item?.optString("mimeType", "image/jpeg").orEmpty()
            val dataBase64 = item?.optString("dataBase64").orEmpty()
            encodedSize += dataBase64.length
            if (!mimeType.startsWith("image/") || dataBase64.isBlank()) {
                saved.forEach { it.delete() }
                call.reject("第 ${index + 1} 张凭证不是有效图片")
                return null
            }
            if (dataBase64.length > 30_000_000 || encodedSize > 60_000_000L) {
                saved.forEach { it.delete() }
                call.reject("凭证照片过大，请减少数量或选择更小的图片")
                return null
            }
            val extension = when (mimeType.lowercase()) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            val file = runCatching {
                WaterCheckInStore.createPhotoFile(context, prefix, extension).apply {
                    writeBytes(Base64.decode(dataBase64, Base64.DEFAULT))
                    require(length() > 0L) { "凭证照片为空" }
                }
            }.getOrElse {
                saved.forEach { photo -> photo.delete() }
                call.reject(it.message ?: "保存凭证照片失败")
                return null
            }
            saved += file
        }
        return saved
    }

    @PluginMethod
    fun requestNotificationPermission(call: PluginCall) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            getPermissionState("notifications") != com.getcapacitor.PermissionState.GRANTED
        ) {
            requestPermissionForAlias("notifications", call, "notificationPermissionCallback")
        } else {
            NotificationHelper.refreshStatusNotifications(context)
            call.resolve(permissionStatus())
        }
    }

    @PermissionCallback
    private fun notificationPermissionCallback(call: PluginCall) {
        NotificationHelper.refreshStatusNotifications(context)
        call.resolve(permissionStatus())
    }

    @PluginMethod
    fun getPermissionStatus(call: PluginCall) {
        call.resolve(permissionStatus())
    }

    private fun permissionStatus(): JSObject {
        val exactAlarms = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) "unavailable" else if (WaterReminderScheduler.canScheduleExact(context)) "granted" else "denied"
        val notifications = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) "granted" else if (NotificationHelper.hasNotificationPermission(context)) "granted" else "denied"
        val fullScreenIntent = if (Build.VERSION.SDK_INT < 34) "unavailable" else if (NotificationHelper.canUseFullScreenIntent(context)) "granted" else "denied"
        val overlays = if (Settings.canDrawOverlays(context)) "granted" else "denied"
        val usageStats = if (ScreenUsageEventReader.hasPermission(context)) "granted" else "denied"
        val waterChannel = NotificationHelper.channelStatus(context, ReminderType.WATER)
        val screenChannel = NotificationHelper.channelStatus(context, ReminderType.SCREEN_LIMIT)
        val deviceAdmin = if (ReminderLockHelper.isDeviceAdminActive(context)) "granted" else "denied"
        val batteryOptimization = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) "granted" else "prompt"
        } else "unavailable"
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
            put("deviceAdmin", deviceAdmin)
            put("batteryOptimization", batteryOptimization)
            put("manufacturerSettingsAvailable", isXiaomiLikeDevice())
        }
    }

    private fun validateConfig(config: ReminderConfig): String? = when {
        config.startHour !in 0..23 || config.endHour !in 0..23 -> "小时必须在 0-23 之间"
        config.startMinute !in 0..59 || config.endMinute !in 0..59 -> "分钟必须在 0-59 之间"
        config.minIntervalMinutes < 1 -> "最小间隔必须大于 0"
        config.maxIntervalMinutes < config.minIntervalMinutes -> "最大间隔不可小于最小间隔"
        config.screenOnLimitMinutes < 0 -> "亮屏超时提醒分钟数不可小于 0"
        config.requiredScreenOffMinutes < 1 -> "累计息屏分钟数必须大于 0"
        config.cancelBeforeLockCount < 1 -> "取消后强制熄屏次数必须大于 0"
        config.waterRetryMinutes !in 1..180 -> "未喝后的再次提醒间隔必须在 1-180 分钟之间"
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
            put("waterRetryMinutes", waterRetryMinutes)
            put("waterConsecutiveNotDrank", WaterCheckInStore.consecutiveNotDrank(context))
            put("screenLimitEnabled", screenLimitEnabled)
            put("screenOnLimitMinutes", screenOnLimitMinutes)
            put("requiredScreenOffMinutes", requiredScreenOffMinutes)
            put("cancelBeforeLockCount", cancelBeforeLockCount)
            val cycle = ScreenStateTracker.cycleSnapshot(context)
            put("cancelCycleCount", cycle.cancelCount)
            put("screenCyclePhase", cycle.phase)
            put("screenCycleLimit", cycle.limit)
            put("screenCycleActiveSessionId", cycle.activeSessionId)
            put("screenCycleSessionStartedAt", cycle.sessionStartedAt)
            put("screenCycleId", cycle.cycleId)
            put("screenCycleStartedAt", cycle.cycleStartedAt)
            put("screenCycleUpdatedAt", cycle.cycleUpdatedAt)
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
