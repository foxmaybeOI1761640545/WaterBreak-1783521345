package com.randomwaterreminder.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

class ReminderSoundService : Service() {
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        val type = ReminderType.from(intent.getStringExtra(EXTRA_TYPE))
        val config = ReminderPreferences.read(this)
        runCatching {
            startForeground(
                NotificationHelper.SOUND_RUNTIME_NOTIFICATION_ID,
                NotificationHelper.buildSoundRuntimeNotification(this, type),
            )
            play(type, config)
        }.onFailure {
            Log.e(TAG, "sound service failed type=${type.value}", it)
            stopSelfSafely()
        }
        return START_NOT_STICKY
    }

    private fun play(type: ReminderType, config: ReminderConfig) {
        releasePlayer()
        val volume = (if (type == ReminderType.WATER) config.waterVolumePercent else config.screenVolumePercent)
            .coerceIn(0, 100) / 100f
        if (volume <= 0f) {
            stopSelfSafely()
            return
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .build()
            focusRequest = request
            audio.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
        val mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(attrs)
            setDataSource(applicationContext, resolveSoundUri(config, type))
            isLooping = false
            setVolume(volume, volume)
            setOnCompletionListener { stopSelfSafely() }
            setOnErrorListener { _, _, _ ->
                stopSelfSafely()
                true
            }
            prepare()
            start()
        }
        player = mediaPlayer
    }

    private fun resolveSoundUri(config: ReminderConfig, type: ReminderType): Uri {
        val mode = if (type == ReminderType.WATER) config.waterSoundMode else config.screenSoundMode
        val custom = if (type == ReminderType.WATER) config.waterCustomSoundUri else config.screenCustomSoundUri
        return if (mode == "custom" && custom.isNotBlank()) {
            Uri.parse(custom)
        } else {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }
    }

    private fun releasePlayer() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        } else {
            @Suppress("DEPRECATION")
            runCatching { audio.abandonAudioFocus(null) }
        }
        focusRequest = null
    }

    private fun stopSelfSafely() {
        releasePlayer()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ReminderSoundService"
        private const val EXTRA_TYPE = "type"

        fun start(context: Context, type: ReminderType): Boolean = runCatching {
            val intent = Intent(context.applicationContext, ReminderSoundService::class.java)
                .putExtra(EXTRA_TYPE, type.value)
            ContextCompat.startForegroundService(context.applicationContext, intent)
            true
        }.onFailure { Log.e(TAG, "start failed type=${type.value}", it) }.getOrDefault(false)

        fun stop(context: Context) {
            runCatching { context.applicationContext.stopService(Intent(context, ReminderSoundService::class.java)) }
        }
    }
}
