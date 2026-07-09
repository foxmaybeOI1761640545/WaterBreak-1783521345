package com.randomwaterreminder.app

import android.content.Context

object ReminderPreferences {
    private const val PREFS = "water_reminder_preferences"

    fun read(context: Context): ReminderConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val legacySoundMode = p.getString("soundMode", "default") ?: "default"
        val legacySoundUri = p.getString("customSoundUri", "") ?: ""
        val legacySoundName = p.getString("customSoundName", "") ?: ""
        return ReminderConfig(
            enabled = p.getBoolean("enabled", false),
            startHour = p.getInt("startHour", 6),
            startMinute = p.getInt("startMinute", 35),
            endHour = p.getInt("endHour", 23),
            endMinute = p.getInt("endMinute", 45),
            minIntervalMinutes = p.getInt("minIntervalMinutes", 35),
            maxIntervalMinutes = p.getInt("maxIntervalMinutes", 45),
            nextReminderTime = p.getLong("nextReminderTime", 0L),
            waterNotificationTitle = p.getString("waterNotificationTitle", p.getString("notificationTitle", "该喝水啦")) ?: "该喝水啦",
            waterNotificationText = p.getString("waterNotificationText", p.getString("notificationText", "稳健做人，认真做事。")) ?: "稳健做人，认真做事。",
            waterSoundMode = p.getString("waterSoundMode", legacySoundMode) ?: legacySoundMode,
            waterCustomSoundUri = p.getString("waterCustomSoundUri", legacySoundUri) ?: legacySoundUri,
            waterCustomSoundName = p.getString("waterCustomSoundName", legacySoundName) ?: legacySoundName,
            waterVolumePercent = p.getInt("waterVolumePercent", 100).coerceIn(0, 100),
            screenLimitEnabled = if (p.contains("screenLimitEnabled")) p.getBoolean("screenLimitEnabled", true) else if (p.contains("screenOnLimitMinutes")) p.getInt("screenOnLimitMinutes", 5) > 0 else true,
            screenOnLimitMinutes = p.getInt("screenOnLimitMinutes", 5),
            requiredScreenOffMinutes = p.getInt("requiredScreenOffMinutes", 5),
            cancelBeforeLockCount = p.getInt("cancelBeforeLockCount", 5),
            screenSoundMode = p.getString("screenSoundMode", legacySoundMode) ?: legacySoundMode,
            screenCustomSoundUri = p.getString("screenCustomSoundUri", legacySoundUri) ?: legacySoundUri,
            screenCustomSoundName = p.getString("screenCustomSoundName", legacySoundName) ?: legacySoundName,
            screenVolumePercent = p.getInt("screenVolumePercent", 100).coerceIn(0, 100),
        )
    }

    fun save(context: Context, config: ReminderConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", config.enabled)
            .putInt("startHour", config.startHour)
            .putInt("startMinute", config.startMinute)
            .putInt("endHour", config.endHour)
            .putInt("endMinute", config.endMinute)
            .putInt("minIntervalMinutes", config.minIntervalMinutes)
            .putInt("maxIntervalMinutes", config.maxIntervalMinutes)
            .putLong("nextReminderTime", config.nextReminderTime)
            .putString("waterNotificationTitle", config.waterNotificationTitle)
            .putString("waterNotificationText", config.waterNotificationText)
            .putString("waterSoundMode", config.waterSoundMode)
            .putString("waterCustomSoundUri", config.waterCustomSoundUri)
            .putString("waterCustomSoundName", config.waterCustomSoundName)
            .putInt("waterVolumePercent", config.waterVolumePercent.coerceIn(0, 100))
            .putBoolean("screenLimitEnabled", config.screenLimitEnabled)
            .putInt("screenOnLimitMinutes", config.screenOnLimitMinutes)
            .putInt("requiredScreenOffMinutes", config.requiredScreenOffMinutes)
            .putInt("cancelBeforeLockCount", config.cancelBeforeLockCount)
            .putString("screenSoundMode", config.screenSoundMode)
            .putString("screenCustomSoundUri", config.screenCustomSoundUri)
            .putString("screenCustomSoundName", config.screenCustomSoundName)
            .putInt("screenVolumePercent", config.screenVolumePercent.coerceIn(0, 100))
            .apply()
    }
}
