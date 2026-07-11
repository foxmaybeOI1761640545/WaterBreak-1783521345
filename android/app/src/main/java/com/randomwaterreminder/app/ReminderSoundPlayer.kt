package com.randomwaterreminder.app

import android.content.Context
object ReminderSoundPlayer {
    fun play(context: Context, type: ReminderType, config: ReminderConfig): Pair<Boolean, String> {
        val volume = if (type == ReminderType.WATER) config.waterVolumePercent else config.screenVolumePercent
        if (volume <= 0) return true to "音量为 0%，已静音"
        val started = ReminderSoundService.start(context.applicationContext, type)
        return started to if (started) "已启动独立铃声播放任务" else "无法启动铃声播放任务"
    }

    fun stop(context: Context) {
        ReminderSoundService.stop(context)
    }
}
