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
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

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
        if (currentType == ReminderType.SCREEN_LIMIT) {
            AlertCoordinator.dismissScreenAlert(this, sessionId)
        } else {
            AlertCoordinator.dismissAlert(this, currentType)
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeService = WeakReference(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        currentType = ReminderType.from(intent.getStringExtra(EXTRA_TYPE))
        currentReceiver = getResultReceiver(intent)
        isTest = intent.getBooleanExtra(EXTRA_IS_TEST, false)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        resultReported = false
        val title = intent.getStringExtra(EXTRA_TITLE)
            ?: if (currentType == ReminderType.WATER) "该喝水啦" else "亮屏时间过长"
        val text = intent.getStringExtra(EXTRA_TEXT) ?: "请查看提醒。"
        val config = ReminderPreferences.read(this)

        val foregroundStarted = runCatching {
            startForeground(
                NotificationHelper.OVERLAY_RUNTIME_NOTIFICATION_ID,
                NotificationHelper.buildOverlayRuntimeNotification(this, currentType, title),
            )
            true
        }.onFailure { Log.e(TAG, "startForeground failed type=${currentType.value} session=$sessionId", it) }
            .getOrDefault(false)
        if (!foregroundStarted) {
            fallbackToNotification(title, text, config, "前台悬浮服务启动失败")
            return START_NOT_STICKY
        }

        if (consumePendingDismissal(currentType, sessionId)) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            fallbackToNotification(title, text, config, "悬浮窗权限不可用")
            return START_NOT_STICKY
        }

        if (!showOverlay(currentType, title, text)) {
            fallbackToNotification(title, text, config, "创建悬浮窗失败")
            return START_NOT_STICKY
        }

        report(AlertResult(overlayShown = true, reason = "已显示悬浮窗"))
        handler.removeCallbacks(autoDismiss)
        handler.postDelayed(autoDismiss, AUTO_DISMISS_MILLIS)
        return START_NOT_STICKY
    }

    private fun showOverlay(type: ReminderType, title: String, text: String): Boolean {
        removeOverlay()
        windowManager = runCatching { getSystemService(Context.WINDOW_SERVICE) as WindowManager }.getOrNull() ?: return false
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
                    setOnClickListener { AlertCoordinator.dismissAlert(this@OverlayAlertService, ReminderType.WATER) }
                })
            } else {
                addView(Button(context).apply {
                    this.text = "熄屏"
                    setOnClickListener { lockFromOverlay() }
                })
                addView(Button(context).apply {
                    this.text = "取消"
                    setOnClickListener { cancelScreenAlert() }
                })
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else {
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
        }.onFailure { Log.e(TAG, "add overlay failed type=${type.value} session=$sessionId", it) }
            .getOrElse {
                overlayView = null
                false
            }
    }

    private fun lockFromOverlay() {
        val result = ReminderLockHelper.tryLockNow(this, force = true)
        when {
            result.succeeded -> Toast.makeText(this, "已执行熄屏。", Toast.LENGTH_SHORT).show()
            result.needsAdmin -> Toast.makeText(this, "请先在应用的权限配置中开启设备管理器锁屏权限。", Toast.LENGTH_LONG).show()
            result.error.isNotBlank() -> Toast.makeText(this, result.error, Toast.LENGTH_LONG).show()
        }
        AlertCoordinator.dismissScreenAlert(this, sessionId)
    }

    private fun cancelScreenAlert() {
        if (isTest) {
            AlertCoordinator.dismissScreenAlert(this, sessionId)
            return
        }
        val result = ScreenStateTracker.recordScreenAlertCancel(this, sessionId)
        val message = when {
            !result.accepted -> "本次提醒已经处理，不会重复计数。"
            result.needsAdmin -> "已达取消阈值，请在权限配置中开启设备管理器锁屏权限。"
            result.lockSucceeded -> "已达到取消阈值并执行熄屏。"
            result.shouldForceLock -> "已达到取消阈值，系统将继续执行熄屏。"
            else -> "已取消本次提醒，未完成息屏休息前仍会再次提醒。"
        }
        Toast.makeText(this, message, if (result.needsAdmin) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        AlertCoordinator.dismissScreenAlert(this, sessionId)
    }

    private fun fallbackToNotification(title: String, text: String, config: ReminderConfig, cause: String) {
        stopForegroundCompat(removeNotification = true)
        val notificationResult = runCatching {
            NotificationHelper.showReminder(this, currentType, title, text, config, sessionId)
        }.getOrElse {
            Log.e(TAG, "notification fallback failed", it)
            AlertResult(posted = false, reason = it.message ?: "通知兜底失败")
        }
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
        runCatching { currentReceiver?.send(android.app.Activity.RESULT_OK, result.toBundle()) }
    }

    private fun dismissMatching(type: ReminderType, requestedSession: String) {
        if (type != currentType) return
        if (requestedSession.isNotBlank() && requestedSession != sessionId) return
        removeOverlay()
        stopSelfSafely()
    }

    private fun removeOverlay() {
        handler.removeCallbacks(autoDismiss)
        overlayView?.let { view -> runCatching { windowManager?.removeView(view) }.onFailure { Log.w(TAG, "remove overlay failed", it) } }
        overlayView = null
    }

    private fun stopForegroundCompat(removeNotification: Boolean) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(removeNotification)
            }
        }.onFailure { Log.w(TAG, "stopForeground failed", it) }
    }

    private fun stopSelfSafely() {
        stopForegroundCompat(removeNotification = true)
        runCatching { stopSelf() }
    }

    override fun onDestroy() {
        removeOverlay()
        if (activeService?.get() === this) activeService = null
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
        private const val TAG = "OverlayAlertService"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_RESULT_RECEIVER = "resultReceiver"
        private const val EXTRA_IS_TEST = "isTest"
        private const val EXTRA_SESSION_ID = "sessionId"
        private const val AUTO_DISMISS_MILLIS = 2L * 60L * 1000L
        private const val PENDING_DISMISS_TTL_MS = 10_000L
        private val mainHandler = Handler(Looper.getMainLooper())
        private val pendingDismissals = ConcurrentHashMap<String, Long>()
        @Volatile private var activeService: WeakReference<OverlayAlertService>? = null

        private fun dismissalKey(type: ReminderType, sessionId: String): String = "${type.value}:${sessionId.ifBlank { "*" }}"

        private fun consumePendingDismissal(type: ReminderType, sessionId: String): Boolean {
            val now = System.currentTimeMillis()
            pendingDismissals.entries.removeIf { now - it.value > PENDING_DISMISS_TTL_MS }
            val exact = dismissalKey(type, sessionId)
            val wildcard = dismissalKey(type, "")
            return pendingDismissals.remove(exact) != null || pendingDismissals.remove(wildcard) != null
        }

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
            }.onFailure { Log.e(TAG, "start foreground service failed type=${type.value} session=$sessionId", it) }
                .getOrDefault(false)
        }

        fun dismiss(context: Context, type: ReminderType, sessionId: String = "") {
            pendingDismissals[dismissalKey(type, sessionId)] = System.currentTimeMillis()
            val service = activeService?.get() ?: return
            mainHandler.post {
                service.dismissMatching(type, sessionId)
                consumePendingDismissal(type, sessionId)
            }
        }
    }
}
