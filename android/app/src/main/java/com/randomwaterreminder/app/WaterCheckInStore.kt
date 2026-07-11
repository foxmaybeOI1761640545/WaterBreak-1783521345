package com.randomwaterreminder.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.UUID

data class WaterNotDrankResult(
    val accepted: Boolean,
    val consecutiveCount: Int,
    val requiresStatePhoto: Boolean,
)

object WaterCheckInStore {
    private const val PREFS = "water_check_in_state"
    private const val KEY_CONSECUTIVE_NOT_DRANK = "consecutiveNotDrank"
    private const val KEY_PENDING_FORCED_SESSION = "pendingForcedSession"
    private const val KEY_HANDLED_SESSIONS = "handledSessions"
    private const val KEY_RECORDS = "records"
    private const val MAX_RECORDS = 200
    private const val MAX_HANDLED_SESSIONS = 100
    private val lock = Any()

    fun consecutiveNotDrank(context: Context): Int = synchronized(lock) {
        prefs(context).getInt(KEY_CONSECUTIVE_NOT_DRANK, 0).coerceAtLeast(0)
    }

    fun requiresForcedVerification(context: Context): Boolean = consecutiveNotDrank(context) >= 3

    fun recordNotDrank(
        context: Context,
        sessionId: String,
        now: Long = System.currentTimeMillis(),
    ): WaterNotDrankResult = synchronized(lock) {
        val p = prefs(context)
        val handled = handledSessions(p)
        val pendingForcedSession = p.getString(KEY_PENDING_FORCED_SESSION, "").orEmpty()
        if (sessionId.isNotBlank() && (sessionId in handled || sessionId == pendingForcedSession)) {
            val count = p.getInt(KEY_CONSECUTIVE_NOT_DRANK, 0).coerceAtLeast(0)
            return@synchronized WaterNotDrankResult(false, count, count >= 3)
        }

        val nextCount = (p.getInt(KEY_CONSECUTIVE_NOT_DRANK, 0).coerceAtLeast(0) + 1).coerceAtMost(3)
        val requiresPhoto = nextCount >= 3
        val records = records(p)
        appendRecord(
            records,
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("type", "not_drank")
                .put("timestamp", now)
                .put("consecutiveNotDrank", nextCount)
                .put("sessionId", sessionId),
        )
        if (!requiresPhoto && sessionId.isNotBlank()) addHandledSession(handled, sessionId)
        p.edit()
            .putInt(KEY_CONSECUTIVE_NOT_DRANK, nextCount)
            .putString(KEY_PENDING_FORCED_SESSION, if (requiresPhoto) sessionId else "")
            .putStringSet(KEY_HANDLED_SESSIONS, handled)
            .putString(KEY_RECORDS, records.toString())
            .commit()
        WaterNotDrankResult(true, nextCount, requiresPhoto)
    }

    fun recordDrank(
        context: Context,
        sessionId: String,
        amountMl: Int,
        photoFile: File,
        now: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(lock) {
        if (amountMl !in 1..5_000 || !photoFile.isFile || photoFile.length() <= 0L) return@synchronized false
        val p = prefs(context)
        val handled = handledSessions(p)
        if (sessionId.isNotBlank() && sessionId in handled) return@synchronized false
        val records = records(p)
        appendRecord(
            records,
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("type", "drank")
                .put("timestamp", now)
                .put("amountMl", amountMl)
                .put("photoFileName", photoFile.name)
                .put("photoPath", photoFile.absolutePath)
                .put("sessionId", sessionId),
        )
        if (sessionId.isNotBlank()) addHandledSession(handled, sessionId)
        p.edit()
            .putInt(KEY_CONSECUTIVE_NOT_DRANK, 0)
            .putString(KEY_PENDING_FORCED_SESSION, "")
            .putStringSet(KEY_HANDLED_SESSIONS, handled)
            .putString(KEY_RECORDS, records.toString())
            .commit()
    }

    fun recordForcedStatePhoto(
        context: Context,
        sessionId: String,
        photoFile: File,
        now: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(lock) {
        if (!photoFile.isFile || photoFile.length() <= 0L) return@synchronized false
        val p = prefs(context)
        val handled = handledSessions(p)
        if (sessionId.isNotBlank() && sessionId in handled) return@synchronized false
        val records = records(p)
        appendRecord(
            records,
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("type", "state_check")
                .put("timestamp", now)
                .put("photoFileName", photoFile.name)
                .put("photoPath", photoFile.absolutePath)
                .put("sessionId", sessionId),
        )
        if (sessionId.isNotBlank()) addHandledSession(handled, sessionId)
        p.edit()
            .putInt(KEY_CONSECUTIVE_NOT_DRANK, 0)
            .putString(KEY_PENDING_FORCED_SESSION, "")
            .putStringSet(KEY_HANDLED_SESSIONS, handled)
            .putString(KEY_RECORDS, records.toString())
            .commit()
    }

    fun snapshot(context: Context, limit: Int = 20): JSONObject = synchronized(lock) {
        val p = prefs(context)
        val stored = records(p)
        val now = System.currentTimeMillis()
        val todayStart = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        var todayTotalMl = 0
        var todayRecordCount = 0
        var lastDrankAt = 0L
        for (index in 0 until stored.length()) {
            val record = stored.optJSONObject(index) ?: continue
            val timestamp = record.optLong("timestamp", 0L)
            if (record.optString("type") == "drank" && timestamp in todayStart..now) {
                todayTotalMl += record.optInt("amountMl", 0).coerceAtLeast(0)
                todayRecordCount += 1
                lastDrankAt = maxOf(lastDrankAt, timestamp)
            }
        }
        val recent = JSONArray()
        val start = (stored.length() - limit.coerceIn(1, MAX_RECORDS)).coerceAtLeast(0)
        for (index in stored.length() - 1 downTo start) {
            stored.optJSONObject(index)?.let { record ->
                recent.put(JSONObject(record.toString()).apply { remove("photoPath") })
            }
        }
        JSONObject()
            .put("consecutiveNotDrank", p.getInt(KEY_CONSECUTIVE_NOT_DRANK, 0).coerceAtLeast(0))
            .put("requiresStatePhoto", p.getInt(KEY_CONSECUTIVE_NOT_DRANK, 0) >= 3)
            .put("todayTotalMl", todayTotalMl)
            .put("todayRecordCount", todayRecordCount)
            .put("lastDrankAt", lastDrankAt)
            .put("records", recent)
    }

    fun createPhotoFile(context: Context, prefix: String): File {
        val directory = File(context.filesDir, "water-verifications").apply { mkdirs() }
        return File(directory, "${prefix}-${System.currentTimeMillis()}-${UUID.randomUUID()}.jpg")
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun records(prefs: android.content.SharedPreferences): JSONArray = runCatching {
        JSONArray(prefs.getString(KEY_RECORDS, "[]") ?: "[]")
    }.getOrDefault(JSONArray())

    private fun handledSessions(prefs: android.content.SharedPreferences): MutableSet<String> =
        prefs.getStringSet(KEY_HANDLED_SESSIONS, emptySet<String>()).orEmpty().toMutableSet()

    private fun addHandledSession(handled: MutableSet<String>, sessionId: String) {
        handled += sessionId
        while (handled.size > MAX_HANDLED_SESSIONS) handled.remove(handled.first())
    }

    private fun appendRecord(records: JSONArray, record: JSONObject) {
        records.put(record)
        while (records.length() > MAX_RECORDS) records.remove(0)
    }
}
