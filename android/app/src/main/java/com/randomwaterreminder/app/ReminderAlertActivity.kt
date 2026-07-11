package com.randomwaterreminder.app

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

class ReminderAlertActivity : Activity() {
    private var reminderType: ReminderType = ReminderType.WATER
    private var isTest: Boolean = false
    private var sessionId: String = ""
    private var pendingLockMode: String = PENDING_NONE
    private var interactionHandled = false
    private var waterMode: String = WATER_MODE_PROMPT
    private var pendingPhotoFile: File? = null
    private var previousPhotoBeforeCapture: File? = null
    private var photoStatusView: TextView? = null
    private var amountInput: EditText? = null
    private var photoCommitted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerInstance(this)
        loadIntent(intent)
        pendingPhotoFile = savedInstanceState?.getString(STATE_PENDING_PHOTO_PATH)?.let(::File)
        waterMode = savedInstanceState?.getString(STATE_WATER_MODE) ?: waterMode
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
        if (!photoCommitted) pendingPhotoFile?.delete()
        pendingPhotoFile = null
        photoCommitted = false
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
        if (!isChangingConfigurations && !photoCommitted) {
            pendingPhotoFile?.delete()
            previousPhotoBeforeCapture?.delete()
        }
        unregisterInstance(this)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android SDK; retained for compatibility with the Activity base class")
    override fun onBackPressed() {
        if (reminderType == ReminderType.WATER && !isTest) {
            val message = when (waterMode) {
                WATER_MODE_FORCED_STATE -> "请先完成状态自拍验证。"
                WATER_MODE_DRANK -> "请完成喝水自拍和毫升数记录。"
                else -> "请选择“已喝”或“未喝”完成本次提醒。"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            return
        }
        super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_WATER_MODE, waterMode)
        outState.putString(STATE_PENDING_PHOTO_PATH, pendingPhotoFile?.absolutePath)
    }

    @Deprecated("Deprecated in Android SDK; retained for broad camera-app compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE_WATER_PHOTO) return
        val photo = pendingPhotoFile
        if (resultCode == RESULT_OK && photo != null && photo.length() <= 0L) {
            @Suppress("DEPRECATION")
            val thumbnail = data?.extras?.get("data") as? Bitmap
            if (thumbnail != null) runCatching { photo.outputStream().use { thumbnail.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
        }
        if (resultCode == RESULT_OK && photo?.isFile == true && photo.length() > 0L) {
            previousPhotoBeforeCapture?.takeIf { it != photo }?.delete()
            previousPhotoBeforeCapture = null
            photoStatusView?.text = "自拍已保存到本机：${photo.name}"
        } else {
            photo?.delete()
            pendingPhotoFile = previousPhotoBeforeCapture
            previousPhotoBeforeCapture = null
            photoStatusView?.text = pendingPhotoFile?.takeIf { it.isFile && it.length() > 0L }
                ?.let { "自拍已保存到本机：${it.name}" }
                ?: "尚未拍摄自拍"
            Toast.makeText(this, "未保存照片，请重新拍摄。", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadIntent(source: Intent) {
        reminderType = ReminderType.from(source.getStringExtra(EXTRA_TYPE))
        isTest = source.getBooleanExtra(EXTRA_IS_TEST, false)
        sessionId = source.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        waterMode = source.getStringExtra(EXTRA_WATER_MODE) ?: WATER_MODE_PROMPT
    }

    private fun renderIntent(source: Intent) {
        val config = ReminderPreferences.read(this)
        if (reminderType == ReminderType.WATER && !isTest && WaterCheckInStore.requiresForcedVerification(this)) {
            waterMode = WATER_MODE_FORCED_STATE
        }
        val title = when {
            reminderType == ReminderType.WATER && waterMode == WATER_MODE_DRANK -> "记录本次喝水"
            reminderType == ReminderType.WATER && waterMode == WATER_MODE_FORCED_STATE -> "需要状态自拍验证"
            else -> source.getStringExtra(EXTRA_TITLE)
        }
            ?: if (reminderType == ReminderType.WATER) config.waterNotificationTitle else "亮屏时间过长"
        val text = when {
            reminderType == ReminderType.WATER && waterMode == WATER_MODE_DRANK -> "请拍摄喝水自拍并填写本次喝水量，记录仅保存在本机。"
            reminderType == ReminderType.WATER && waterMode == WATER_MODE_FORCED_STATE -> "已经连续三次选择未喝，请拍摄自拍验证当前状态。照片仅保存在本机。"
            else -> source.getStringExtra(EXTRA_TEXT)
        }
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
            when (waterMode) {
                WATER_MODE_DRANK -> {
                    amountInput = EditText(this).apply {
                        hint = "本次喝水量（毫升）"
                        inputType = InputType.TYPE_CLASS_NUMBER
                        setTextColor(Color.rgb(12, 44, 64))
                        setHintTextColor(Color.rgb(120, 140, 150))
                    }
                    card.addView(amountInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
                    addPhotoControls(card, ::dp, forced = false)
                }
                WATER_MODE_FORCED_STATE -> addPhotoControls(card, ::dp, forced = true)
                else -> {
                    val notDrankCount = WaterCheckInStore.consecutiveNotDrank(this)
                    if (notDrankCount > 0) {
                        card.addView(TextView(this).apply {
                            this.text = "已连续选择未喝 $notDrankCount/3 次"
                            textSize = 15f
                            setTextColor(Color.rgb(190, 82, 35))
                            gravity = Gravity.CENTER
                            setPadding(0, 0, 0, dp(10))
                        })
                    }
                    card.addView(Button(this).apply {
                        this.text = "已喝"
                        textSize = 18f
                        setOnClickListener { openDrankVerification() }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
                    card.addView(Button(this).apply {
                        this.text = "未喝"
                        textSize = 18f
                        setOnClickListener { handleNotDrank() }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(12) })
                }
            }
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

    private fun addPhotoControls(card: LinearLayout, dp: (Int) -> Int, forced: Boolean) {
        photoStatusView = TextView(this).apply {
            text = if (pendingPhotoFile?.let { it.isFile && it.length() > 0L } == true) {
                "自拍已保存到本机：${pendingPhotoFile?.name}"
            } else {
                "尚未拍摄自拍"
            }
            textSize = 15f
            setTextColor(Color.rgb(84, 105, 118))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(10))
        }
        card.addView(photoStatusView)
        card.addView(Button(this).apply {
            text = if (forced) "拍摄状态自拍" else "拍摄喝水自拍"
            textSize = 17f
            setOnClickListener { captureWaterPhoto(if (forced) "state" else "drank") }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        card.addView(Button(this).apply {
            text = if (forced) "提交状态验证" else "保存喝水记录"
            textSize = 17f
            setOnClickListener { if (forced) completeForcedStateVerification() else completeDrankVerification() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(12) })
        if (!forced) {
            card.addView(Button(this).apply {
                text = "返回“已喝 / 未喝”选择"
                textSize = 16f
                setOnClickListener { returnToWaterPrompt() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(12) })
        }
    }

    private fun returnToWaterPrompt() {
        pendingPhotoFile?.delete()
        previousPhotoBeforeCapture?.delete()
        pendingPhotoFile = null
        previousPhotoBeforeCapture = null
        amountInput = null
        photoStatusView = null
        waterMode = WATER_MODE_PROMPT
        interactionHandled = false
        renderIntent(intent)
    }

    private fun openDrankVerification() {
        if (interactionHandled) return
        if (isTest) {
            AlertCoordinator.dismissAlert(this, ReminderType.WATER)
            Toast.makeText(this, "测试提醒已关闭，不保存喝水记录。", Toast.LENGTH_SHORT).show()
            return
        }
        waterMode = WATER_MODE_DRANK
        interactionHandled = false
        AlertCoordinator.dismissAlert(this, ReminderType.WATER, closeActivity = false)
        renderIntent(intent)
    }

    private fun handleNotDrank() {
        if (interactionHandled) return
        interactionHandled = true
        if (isTest) {
            AlertCoordinator.dismissAlert(this, ReminderType.WATER)
            Toast.makeText(this, "测试提醒已关闭，不计入未喝次数。", Toast.LENGTH_SHORT).show()
            return
        }
        val result = WaterCheckInStore.recordNotDrank(this, sessionId)
        if (!result.accepted) {
            if (result.requiresStatePhoto) {
                waterMode = WATER_MODE_FORCED_STATE
                interactionHandled = false
                AlertCoordinator.dismissAlert(this, ReminderType.WATER, closeActivity = false)
                renderIntent(intent)
                return
            }
            AlertCoordinator.dismissAlert(this, ReminderType.WATER)
            Toast.makeText(this, "本次喝水提醒已经处理。", Toast.LENGTH_SHORT).show()
            return
        }
        if (result.requiresStatePhoto) {
            waterMode = WATER_MODE_FORCED_STATE
            interactionHandled = false
            pendingPhotoFile = null
            AlertCoordinator.dismissAlert(this, ReminderType.WATER, closeActivity = false)
            renderIntent(intent)
            return
        }
        val config = ReminderPreferences.read(this)
        WaterReminderScheduler.scheduleNextReminder(
            this,
            System.currentTimeMillis() + config.waterRetryMinutes.coerceIn(1, 180) * 60_000L,
        )
        AlertCoordinator.dismissAlert(this, ReminderType.WATER)
        Toast.makeText(this, "将在 ${config.waterRetryMinutes} 分钟后再次提醒。", Toast.LENGTH_SHORT).show()
    }

    private fun captureWaterPhoto(prefix: String) {
        val previous = pendingPhotoFile
        val photo = WaterCheckInStore.createPhotoFile(this, prefix)
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", photo)
        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            putExtra("android.intent.extras.CAMERA_FACING", 1)
            putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
            clipData = ClipData.newRawUri("water-verification", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        runCatching {
            pendingPhotoFile = photo
            previousPhotoBeforeCapture = previous
            startActivityForResult(cameraIntent, REQUEST_CAPTURE_WATER_PHOTO)
        }.onFailure {
            pendingPhotoFile = previous
            previousPhotoBeforeCapture = null
            photo.delete()
            Toast.makeText(this, "无法打开相机，请确认设备已安装相机应用。", Toast.LENGTH_LONG).show()
        }
    }

    private fun completeDrankVerification() {
        if (interactionHandled) return
        val amount = amountInput?.text?.toString()?.trim()?.toIntOrNull()
        val photo = pendingPhotoFile
        when {
            amount == null || amount !in 1..5_000 -> Toast.makeText(this, "请输入 1-5000 毫升的喝水量。", Toast.LENGTH_SHORT).show()
            photo == null || !photo.isFile || photo.length() <= 0L -> Toast.makeText(this, "请先拍摄喝水自拍。", Toast.LENGTH_SHORT).show()
            else -> {
                interactionHandled = true
                if (!WaterCheckInStore.recordDrank(this, sessionId, amount, photo)) {
                    interactionHandled = false
                    Toast.makeText(this, "本次记录未保存，可能已经处理过。", Toast.LENGTH_SHORT).show()
                    return
                }
                photoCommitted = true
                AlertCoordinator.dismissAlert(this, ReminderType.WATER)
                Toast.makeText(this, "已在本机保存：${amount} 毫升。", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun completeForcedStateVerification() {
        if (interactionHandled) return
        val photo = pendingPhotoFile
        if (photo == null || !photo.isFile || photo.length() <= 0L) {
            Toast.makeText(this, "请先拍摄状态自拍。", Toast.LENGTH_SHORT).show()
            return
        }
        interactionHandled = true
        if (!WaterCheckInStore.recordForcedStatePhoto(this, sessionId, photo)) {
            interactionHandled = false
            Toast.makeText(this, "状态验证照片保存失败。", Toast.LENGTH_SHORT).show()
            return
        }
        photoCommitted = true
        val config = ReminderPreferences.read(this)
        WaterReminderScheduler.scheduleNextReminder(
            this,
            System.currentTimeMillis() + config.waterRetryMinutes.coerceIn(1, 180) * 60_000L,
        )
        AlertCoordinator.dismissAlert(this, ReminderType.WATER)
        Toast.makeText(this, "状态自拍已保存在本机，将在 ${config.waterRetryMinutes} 分钟后再次提醒。", Toast.LENGTH_LONG).show()
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
        private const val EXTRA_WATER_MODE = "waterMode"
        private const val ACTION_DISMISS = "com.randomwaterreminder.DISMISS_ALERT_ACTIVITY"
        private const val WATER_MODE_PROMPT = "prompt"
        private const val WATER_MODE_DRANK = "drank"
        private const val WATER_MODE_FORCED_STATE = "forced_state"
        private const val STATE_WATER_MODE = "stateWaterMode"
        private const val STATE_PENDING_PHOTO_PATH = "statePendingPhotoPath"
        private const val REQUEST_CAPTURE_WATER_PHOTO = 9201
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

        fun intent(
            context: Context,
            type: ReminderType,
            title: String,
            text: String,
            isTest: Boolean = false,
            sessionId: String = "",
            waterMode: String = WATER_MODE_PROMPT,
        ): Intent = Intent(context, ReminderAlertActivity::class.java).apply {
            putExtra(EXTRA_TYPE, type.value)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_IS_TEST, isTest)
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_WATER_MODE, waterMode)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        fun waterDrankIntent(context: Context, title: String, text: String, isTest: Boolean, sessionId: String): Intent =
            intent(context, ReminderType.WATER, title, text, isTest, sessionId, WATER_MODE_DRANK)

        fun waterForcedStateIntent(context: Context, title: String, text: String, sessionId: String): Intent =
            intent(context, ReminderType.WATER, title, text, false, sessionId, WATER_MODE_FORCED_STATE)
    }
}
