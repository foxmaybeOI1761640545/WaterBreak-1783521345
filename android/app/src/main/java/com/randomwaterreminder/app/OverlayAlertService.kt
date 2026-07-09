package com.randomwaterreminder.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class OverlayAlertService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var currentType: ReminderType = ReminderType.WATER
    private var currentReceiver: ResultReceiver? = null
    private var resultReported = false
    private var isTest = false
    private var sessionId = ""

    private val autoDismiss = Runnable {
        if (currentType == ReminderType.SCREEN_LIMIT) AlertCoordinator.dismissScreenAlert(this, sessionId) else AlertCoordinator.dismissAlert(this, currentType)
        removeOverlay()
        stopSelfSafely()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS) {
            val requestedType = ReminderType.from(intent.getStringExtra(EXTRA_TYPE))
            val requestedSession = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
            if (requestedType == currentType && (requestedSession.isBlank() || requestedSession == sessionId)) {
                removeOverlay()
                stopSelfSafely()
            }
            return START_NOT_STICKY
        }

        currentType = ReminderType.from(intent?.getStringExtra(EXTRA_TYPE))
        currentReceiver = getResultReceiver(intent)
        isTest = intent?.getBooleanExtra(EXTRA_IS_TEST, false) ?: false
        sessionId = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        resultReported = false
        val title = intent?.getStringExtra(EXTRA_TITLE)
            ?: if (currentType == ReminderType.WATER) "该喝水啦" else "亮屏时间过长"
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "请查看提醒。"
        val config = ReminderPreferences.read(this)

        startForeground(
            NotificationHelper.OVERLAY_RUNTIME_NOTIFICATION_ID,
            NotificationHelper.buildOverlayRuntimeNotification(this, currentType, title),
        )

        if (!Settings.canDrawOverlays(this)) {
            fallbackToNotification(title, text, config, "悬浮窗权限不可用")
            return START_NOT_STICKY
        }

        val shown = showOverlay(currentType, title, text)
        if (!shown) {
            fallbackToNotification(title, text, config, "创建悬浮窗失败")
            return START_NOT_STICKY
        }

        // Keep the short-lived service in the foreground while the overlay is visible.
        // Removing foreground state immediately makes Android free to stop a background
        // service before the user can interact with the window. The runtime notification
        // is removed together with the overlay in stopSelfSafely().
        report(AlertResult(overlayShown = true, reason = "已显示悬浮窗"))
        handler.removeCallbacks(autoDismiss)
        handler.postDelayed(autoDismiss, AUTO_DISMISS_MILLIS)
        return START_NOT_STICKY
    }

    private fun showOverlay(type: ReminderType, title: String, text: String): Boolean {
        removeOverlay()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xF5FFFFFF.toInt())
                cornerRadius = dp(20).toFloat()
            }
            addView(TextView(context).apply {
                this.text = title
                textSize = 20f
                setTextColor(0xFF0C2C40.toInt())
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                this.text = text
                textSize = 15f
                setTextColor(0xFF546976.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(12))
            })
            if (type == ReminderType.WATER) {
                addView(Button(context).apply {
                    this.text = "知道了"
                    setOnClickListener {
                        removeOverlay()
                        stopSelfSafely()
                    }
                })
            } else {
                addView(Button(context).apply {
                    this.text = "熄屏"
                    setOnClickListener { lockFromOverlay(title, text) }
                })
                addView(Button(context).apply {
                    this.text = "取消"
                    setOnClickListener { cancelScreenAlert(title, text) }
                })
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            horizontalMargin = 0.08f
        }

        overlayView = root
        return runCatching {
            windowManager?.addView(root, params)
            true
        }.getOrElse {
            overlayView = null
            false
        }
    }

    private fun lockFromOverlay(title: String, text: String) {
        if (ReminderLockHelper.lockNow(this)) {
            removeOverlay()
            stopSelfSafely()
            return
        }
        Toast.makeText(this, "请在弹出的页面中授予设备管理权限。", Toast.LENGTH_LONG).show()
        runCatching { startActivity(ReminderAlertActivity.intent(this, ReminderType.SCREEN_LIMIT, title, text)) }
        removeOverlay()
        stopSelfSafely()
    }

    private fun cancelScreenAlert(title: String, text: String) {
        if (isTest) { AlertCoordinator.dismissScreenAlert(this, sessionId); stopSelfSafely(); return }
        if (ScreenStateTracker.recordScreenAlertCancel(this, sessionId)) {
            lockFromOverlay(title, text)
            return
        }
        Toast.makeText(this, "已取消本次提醒，未完成息屏休息前仍会再次提醒。", Toast.LENGTH_SHORT).show()
        removeOverlay()
        stopSelfSafely()
    }

    private fun fallbackToNotification(title: String, text: String, config: ReminderConfig, cause: String) {
        stopForegroundCompat(removeNotification = true)
        val notificationResult = NotificationHelper.showReminder(this, currentType, title, text, config)
        report(
            notificationResult.copy(
                fallbackUsed = true,
                reason = if (notificationResult.posted) "$cause，已使用通知兜底" else "$cause；${notificationResult.reason}",
            ),
        )
        stopSelfSafely()
    }

    private fun report(result: AlertResult) {
        if (resultReported) return
        resultReported = true
        currentReceiver?.send(android.app.Activity.RESULT_OK, result.toBundle())
    }

    private fun removeOverlay() {
        handler.removeCallbacks(autoDismiss)
        overlayView?.let { view -> runCatching { windowManager?.removeView(view) } }
        overlayView = null
    }

    private fun stopForegroundCompat(removeNotification: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(removeNotification)
        }
    }

    private fun stopSelfSafely() {
        stopForegroundCompat(removeNotification = true)
        stopSelf()
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun getResultReceiver(intent: Intent?): ResultReceiver? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_RECEIVER, ResultReceiver::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_RECEIVER)
        }
    }

    companion object {
        private const val ACTION_DISMISS = "com.randomwaterreminder.DISMISS_OVERLAY"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_RESULT_RECEIVER = "resultReceiver"
        private const val EXTRA_IS_TEST = "isTest"
        private const val EXTRA_SESSION_ID = "sessionId"
        private const val AUTO_DISMISS_MILLIS = 2L * 60L * 1000L

        fun start(
            context: Context,
            type: ReminderType,
            title: String,
            text: String,
            resultReceiver: ResultReceiver? = null,
            isTest: Boolean = false,
            sessionId: String = "",
        ): Boolean {
            val intent = Intent(context, OverlayAlertService::class.java).apply {
                putExtra(EXTRA_TYPE, type.value)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
                if (resultReceiver != null) putExtra(EXTRA_RESULT_RECEIVER, resultReceiver)
                putExtra(EXTRA_IS_TEST, isTest)
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            return runCatching {
                ContextCompat.startForegroundService(context.applicationContext, intent)
                true
            }.getOrDefault(false)
        }

        fun dismiss(context: Context, type: ReminderType, sessionId: String = "") {
            // stopService triggers onDestroy(), which always removes the current overlay.
            // Avoid starting a background service only to dismiss it.
            runCatching {
                ContextCompat.startForegroundService(context.applicationContext, Intent(context, OverlayAlertService::class.java).apply {
                    action = ACTION_DISMISS
                    putExtra(EXTRA_TYPE, type.value)
                    putExtra(EXTRA_SESSION_ID, sessionId)
                })
            }.onFailure { runCatching { context.stopService(Intent(context, OverlayAlertService::class.java)) } }
        }
    }
}
