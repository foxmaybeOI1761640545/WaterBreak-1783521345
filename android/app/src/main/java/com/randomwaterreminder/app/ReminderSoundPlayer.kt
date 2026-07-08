package com.randomwaterreminder.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import kotlin.math.max

object ReminderSoundPlayer {
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    @Synchronized
    fun play(context: Context, type: ReminderType, config: ReminderConfig): Pair<Boolean, String> {
        stop(context)
        val volume = (if (type == ReminderType.WATER) config.waterVolumePercent else config.screenVolumePercent).coerceIn(0, 100) / 100f
        if (volume <= 0f) return true to "音量为 0%，已静音"
        val uri = resolveSoundUri(config, type)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        return runCatching {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val focusGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
                focusRequest = req
                audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                audio.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
            val mp = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(context.applicationContext, uri)
                isLooping = false
                setVolume(volume, volume)
                setOnCompletionListener { stop(context) }
                prepare()
                start()
            }
            player = mp
            true to if (focusGranted) "已播放应用内铃声" else "未获得音频焦点，已尝试播放铃声"
        }.getOrElse { false to (it.message ?: "播放铃声失败") }
    }

    @Synchronized
    fun stop(context: Context) {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        } else {
            @Suppress("DEPRECATION")
            runCatching { audio.abandonAudioFocus(null) }
        }
        focusRequest = null
    }

    private fun resolveSoundUri(config: ReminderConfig, type: ReminderType): Uri {
        val mode = if (type == ReminderType.WATER) config.waterSoundMode else config.screenSoundMode
        val custom = if (type == ReminderType.WATER) config.waterCustomSoundUri else config.screenCustomSoundUri
        return if (mode == "custom" && custom.isNotBlank()) Uri.parse(custom) else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }
}
