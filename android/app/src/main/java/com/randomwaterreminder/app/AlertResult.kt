package com.randomwaterreminder.app

import android.os.Bundle
import com.getcapacitor.JSObject

data class ChannelResult(val ok: Boolean = false, val reason: String = "") {
    fun toJsObject(): JSObject = JSObject().put("ok", ok).put("reason", reason)
}

data class AlertResult(
    val posted: Boolean = false,
    val overlayShown: Boolean = false,
    val inAppShown: Boolean = false,
    val fullScreenAttempted: Boolean = false,
    val fallbackUsed: Boolean = false,
    val reason: String = "",
    val channelImportance: Int = 0,
    val notification: ChannelResult = ChannelResult(posted, reason),
    val overlay: ChannelResult = ChannelResult(overlayShown, reason),
    val centerDialog: ChannelResult = ChannelResult(inAppShown, reason),
    val sound: ChannelResult = ChannelResult(false, "未播放"),
    val vibration: ChannelResult = ChannelResult(false, "未振动"),
) {
    fun toJsObject(): JSObject = JSObject().apply {
        put("posted", posted); put("overlayShown", overlayShown); put("inAppShown", inAppShown)
        put("fullScreenAttempted", fullScreenAttempted); put("fallbackUsed", fallbackUsed); put("reason", reason)
        put("channelImportance", channelImportance); put("ok", posted || overlayShown || inAppShown || sound.ok || vibration.ok)
        put("notification", notification.toJsObject()); put("overlay", overlay.toJsObject()); put("centerDialog", centerDialog.toJsObject())
        put("sound", sound.toJsObject()); put("vibration", vibration.toJsObject())
    }
    fun toBundle(): Bundle = Bundle().apply {
        putBoolean("posted", posted); putBoolean("overlayShown", overlayShown); putBoolean("inAppShown", inAppShown)
        putBoolean("fullScreenAttempted", fullScreenAttempted); putBoolean("fallbackUsed", fallbackUsed); putString("reason", reason); putInt("channelImportance", channelImportance)
    }
    companion object { fun fromBundle(bundle: Bundle): AlertResult = AlertResult(
        posted = bundle.getBoolean("posted"), overlayShown = bundle.getBoolean("overlayShown"), inAppShown = bundle.getBoolean("inAppShown"),
        fullScreenAttempted = bundle.getBoolean("fullScreenAttempted"), fallbackUsed = bundle.getBoolean("fallbackUsed"),
        reason = bundle.getString("reason").orEmpty(), channelImportance = bundle.getInt("channelImportance"),
    ) }
}
