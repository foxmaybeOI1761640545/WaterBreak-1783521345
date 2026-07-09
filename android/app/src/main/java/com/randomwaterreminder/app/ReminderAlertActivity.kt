package com.randomwaterreminder.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

class ReminderAlertActivity : Activity() {
    private var reminderType: ReminderType = ReminderType.WATER
    private var isTest: Boolean = false
    private var sessionId: String = ""
    private var pendingLockMode: String = PENDING_NONE
    private var interactionHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerInstance(this)
        loadIntent(intent)
        if (intent.action == ACTION_DISMISS) {
            finishIfSessionMatches(intent.getStringExtra(EXTRA_SESSION_ID).orEmpty())
            return
        }
        renderIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_DISMISS) {
            finishIfSessionMatches(intent.getStringExtra(EXTRA_SESSION_ID).orEmpty())
            return
        }
        loadIntent(intent)
        interactionHandled = false
        renderIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (pendingLockMode == PENDING_NONE) return
        val result = if (pendingLockMode == PENDING_FORCE) {
            ScreenStateTracker.retryForceLock(this)
        } else {
            ReminderLockHelper.tryLockNow(this, force = true)
        }
        when {
            result.succeeded -> {
                pendingLockMode = PENDING_NONE
                AlertCoordinator.dismissScreenAlert(this, sessionId)
                finish()
            }
            result.needsAdmin -> interactionHandled = false
            result.error.isNotBlank() -> {
                interactionHandled = false
                Toast.makeText(this, result.error, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        unregisterInstance(this)
        super.onDestroy()
    }

    private fun loadIntent(source: Intent) {
        reminderType = ReminderType.from(source.getStringExtra(EXTRA_TYPE))
        isTest = source.getBooleanExtra(EXTRA_IS_TEST, false)
        sessionId = source.getStringExtra(EXTRA_SESSION_ID).orEmpty()
    }

    private fun renderIntent(source: Intent) {
        val config = ReminderPreferences.read(this)
        val title = source.getStringExtra(EXTRA_TITLE)
            ?: if (reminderType == ReminderType.WATER) config.waterNotificationTitle else "亮屏时间过长"
        val text = source.getStringExtra(EXTRA_TEXT)
            ?: if (reminderType == ReminderType.WATER) config.waterNotificationText else "已经连续亮屏 ${config.screenOnLimitMinutes} 分钟以上，建议息屏休息一下。"
        setFinishOnTouchOutside(false)
        setContentView(buildContent(title, text, reminderType))
    }

    private fun finishIfSessionMatches(requestedSessionId: String) {
        if (requestedSessionId.isBlank() || requestedSessionId == sessionId) finish()
    }

    private fun buildContent(title: String, text: String, type: ReminderType): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.argb(130, 0, 0, 0))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(24).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(8)
                rightMargin = dp(8)
            }
        }
        card.addView(TextView(this).apply {
            this.text = title
            textSize = 24f
            setTextColor(Color.rgb(12, 44, 64))
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (type == ReminderType.SCREEN_LIMIT) {
            val cycle = ScreenStateTracker.cycleSnapshot(this)
            val cycleText = when (cycle.phase) {
                ScreenCycleSnapshot.PHASE_FORCE_LOCK -> "强制熄屏中 ${cycle.cancelCount}/${cycle.limit}"
                ScreenCycleSnapshot.PHASE_BLOCKED_ADMIN -> "已达阈值，等待锁屏权限"
                ScreenCycleSnapshot.PHASE_GRACE -> "紧急宽限中"
                ScreenCycleSnapshot.PHASE_ALERTING -> if (cycle.cancelCount > 0) "已取消 ${cycle.cancelCount} / ${cycle.limit} 次" else "本轮提醒中"
                ScreenCycleSnapshot.PHASE_WAITING_REST -> "已取消 ${cycle.cancelCount} / ${cycle.limit} 次"
                else -> "未进入提醒循环"
            }
            card.addView(TextView(this).apply {
                this.text = cycleText
                textSize = 15f
                setTextColor(Color.rgb(20, 128, 167))
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            })
        }
        card.addView(TextView(this).apply {
            this.text = text
            textSize = 17f
            setTextColor(Color.rgb(84, 105, 118))
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(18))
        })
        if (type == ReminderType.WATER) {
            card.addView(Button(this).apply {
                this.text = "知道了"
                textSize = 18f
                setOnClickListener {
                    if (interactionHandled) return@setOnClickListener
                    interactionHandled = true
                    AlertCoordinator.dismissAlert(this@ReminderAlertActivity, ReminderType.WATER)
                    finish()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        } else {
            card.addView(Button(this).apply {
                this.text = "熄屏"
                textSize = 18f
                setOnClickListener { lockOrRequestAdmin() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
            card.addView(Button(this).apply {
                this.text = "取消"
                textSize = 18f
                setOnClickListener { cancelScreenPopup() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(12) })
        }
        root.addView(card)
        return root
    }

    private fun lockOrRequestAdmin() {
        if (interactionHandled) return
        interactionHandled = true
        val result = ReminderLockHelper.tryLockNow(this, force = true)
        when {
            result.succeeded -> {
                AlertCoordinator.dismissScreenAlert(this, sessionId)
                finish()
            }
            result.needsAdmin -> {
                pendingLockMode = PENDING_MANUAL
                if (!ReminderLockHelper.requestDeviceAdmin(this)) {
                    pendingLockMode = PENDING_NONE
                    interactionHandled = false
                    Toast.makeText(this, "无法打开设备管理器授权页，请从权限配置中手动开启。", Toast.LENGTH_LONG).show()
                }
            }
            else -> {
                interactionHandled = false
                Toast.makeText(this, result.error.ifBlank { "系统暂时无法熄屏，请稍后重试。" }, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun cancelScreenPopup() {
        if (interactionHandled) return
        interactionHandled = true
        if (isTest) {
            AlertCoordinator.dismissScreenAlert(this, sessionId)
            Toast.makeText(this, "测试提醒已关闭，不计入取消次数。", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val result = ScreenStateTracker.recordScreenAlertCancel(this, sessionId)
        if (!result.accepted) {
            AlertCoordinator.dismissScreenAlert(this, sessionId)
            Toast.makeText(this, "本次提醒已经处理，不会重复计数。", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (result.needsAdmin) {
            pendingLockMode = PENDING_FORCE
            AlertCoordinator.dismissScreenAlert(this, sessionId, closeActivity = false)
            if (!ReminderLockHelper.requestDeviceAdmin(this)) {
                pendingLockMode = PENDING_NONE
                AlertCoordinator.dismissScreenAlert(this, sessionId)
                Toast.makeText(this, "已达取消阈值，但无法打开设备管理器授权页。", Toast.LENGTH_LONG).show()
                finish()
            }
            return
        }

        AlertCoordinator.dismissScreenAlert(this, sessionId)
        val message = when {
            result.lockSucceeded -> "已达到取消阈值并执行熄屏。"
            result.shouldForceLock -> "已达到取消阈值，系统将继续执行熄屏。"
            else -> "已取消本次提醒，未完成息屏休息前仍会再次提醒。"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        private const val EXTRA_TYPE = "reminderType"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_IS_TEST = "isTest"
        private const val EXTRA_SESSION_ID = "sessionId"
        private const val ACTION_DISMISS = "com.randomwaterreminder.DISMISS_ALERT_ACTIVITY"
        private const val PENDING_NONE = "none"
        private const val PENDING_MANUAL = "manual"
        private const val PENDING_FORCE = "force"
        private val activeActivities = CopyOnWriteArrayList<WeakReference<ReminderAlertActivity>>()

        private fun registerInstance(activity: ReminderAlertActivity) {
            activeActivities.removeAll { it.get() == null || it.get() === activity }
            activeActivities += WeakReference(activity)
        }

        private fun unregisterInstance(activity: ReminderAlertActivity) {
            activeActivities.removeAll { it.get() == null || it.get() === activity }
        }

        fun dismissActive(type: ReminderType? = null, sessionId: String = "") {
            activeActivities.toList().forEach { reference ->
                val activity = reference.get()
                if (activity == null) {
                    activeActivities.remove(reference)
                } else if ((type == null || activity.reminderType == type) && (sessionId.isBlank() || activity.sessionId == sessionId)) {
                    activity.runOnUiThread { if (!activity.isFinishing) activity.finish() }
                }
            }
        }

        fun dismissIntent(context: Context, sessionId: String = ""): Intent = Intent(context, ReminderAlertActivity::class.java).apply {
            action = ACTION_DISMISS
            putExtra(EXTRA_SESSION_ID, sessionId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        fun intent(context: Context, type: ReminderType, title: String, text: String, isTest: Boolean = false, sessionId: String = ""): Intent = Intent(context, ReminderAlertActivity::class.java).apply {
            putExtra(EXTRA_TYPE, type.value)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_IS_TEST, isTest)
            putExtra(EXTRA_SESSION_ID, sessionId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    }
}
