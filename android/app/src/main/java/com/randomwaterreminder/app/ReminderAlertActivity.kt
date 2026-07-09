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

class ReminderAlertActivity : Activity() {
    private var reminderType: ReminderType = ReminderType.WATER
    private var isTest: Boolean = false
    private var sessionId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadIntent(intent)
        if (intent.action == ACTION_DISMISS) { finishIfSessionMatches(intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()); return }
        val config = ReminderPreferences.read(this)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: if (reminderType == ReminderType.WATER) config.waterNotificationTitle else "亮屏时间过长"
        val text = intent.getStringExtra(EXTRA_TEXT) ?: if (reminderType == ReminderType.WATER) config.waterNotificationText else "已经连续亮屏 ${config.screenOnLimitMinutes} 分钟以上，建议息屏休息一下。"
        setFinishOnTouchOutside(false)
        setContentView(buildContent(title, text, reminderType))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_DISMISS) finishIfSessionMatches(intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()) else loadIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (pendingLockSessionId == sessionId && sessionId.isNotBlank() && reminderType == ReminderType.SCREEN_LIMIT && ReminderLockHelper.forceLockActive(this) && ReminderLockHelper.lockNow(this)) {
            pendingLockSessionId = ""
            ScreenStateTracker.recordScreenAlertCancel(this, sessionId)
            finish()
        }
    }

    private fun loadIntent(source: Intent) {
        reminderType = ReminderType.from(source.getStringExtra(EXTRA_TYPE))
        isTest = source.getBooleanExtra(EXTRA_IS_TEST, false)
        sessionId = source.getStringExtra(EXTRA_SESSION_ID).orEmpty()
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
            val config = ReminderPreferences.read(this)
            val totalCycles = config.cancelBeforeLockCount.coerceAtLeast(1)
            val currentCycle = ReminderLockHelper.cancelCount(this).coerceAtMost(totalCycles)
            card.addView(TextView(this).apply {
                this.text = if (currentCycle > 0) "已取消 $currentCycle / $totalCycles 次" else "未进入提醒循环"
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
        if (ReminderLockHelper.lockNow(this)) {
            AlertCoordinator.dismissScreenAlert(this, sessionId)
            finish()
            return
        }
        pendingLockSessionId = sessionId
        Toast.makeText(this, "请先授予设备管理权限，授予后才能熄屏。", Toast.LENGTH_LONG).show()
        ReminderLockHelper.requestDeviceAdmin(this)
    }

    private fun cancelScreenPopup() {
        if (isTest) { AlertCoordinator.dismissScreenAlert(this, sessionId); Toast.makeText(this, "测试提醒已关闭，不计入取消次数。", Toast.LENGTH_SHORT).show(); finish(); return }
        val config = ReminderPreferences.read(this)
        if (ScreenStateTracker.recordScreenAlertCancel(this, sessionId)) {
            pendingLockSessionId = sessionId
            if (!ReminderLockHelper.isDeviceAdminActive(this)) {
                Toast.makeText(this, "请先授予设备管理权限，授予后才能熄屏。", Toast.LENGTH_LONG).show()
                ReminderLockHelper.requestDeviceAdmin(this)
            } else {
                finish()
            }
            return
        }
        AlertCoordinator.dismissScreenAlert(this, sessionId)
        Toast.makeText(this, "已取消本次屏幕提醒，下次达到阈值会继续提醒。", Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        private const val EXTRA_TYPE = "reminderType"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_IS_TEST = "isTest"
        private const val EXTRA_SESSION_ID = "sessionId"
        private var pendingLockSessionId = ""
        private const val ACTION_DISMISS = "com.randomwaterreminder.DISMISS_ALERT_ACTIVITY"

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
