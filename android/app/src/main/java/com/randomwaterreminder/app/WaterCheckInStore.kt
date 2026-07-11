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

data class WaterMeasurement(
    val entryMode: String = "volume",
    val containerId: String = "",
    val containerName: String = "",
    val emptyWeightGrams: Double? = null,
    val totalWeightGrams: Double? = null,
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
        amountMl: Double,
        photoFile: File,
        measurement: WaterMeasurement = WaterMeasurement(),
        now: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(lock) {
        if (!amountMl.isFinite() || amountMl !in 1.0..5_000.0 || !photoFile.isFile || photoFile.length() <= 0L) return@synchronized false
        val p = prefs(context)
        val handled = handledSessions(p)
        if (sessionId.isNotBlank() && sessionId in handled) return@synchronized false
        val records = records(p)
        val record = JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("type", "drank")
                .put("timestamp", now)
                .put("amountMl", roundOneDecimal(amountMl))
                .put("photoFileName", photoFile.name)
                .put("photoPath", photoFile.absolutePath)
                .put("sessionId", sessionId)
                .put("entryMode", if (measurement.entryMode == "container") "container" else "volume")
        if (measurement.entryMode == "container") {
            measurement.containerId.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,80}")) }?.let { record.put("containerId", it) }
            measurement.containerName.trim().takeIf { it.isNotBlank() }?.let { record.put("containerName", it.take(80)) }
            measurement.emptyWeightGrams?.takeIf { it.isFinite() }?.let { record.put("emptyWeightGrams", roundOneDecimal(it)) }
            measurement.totalWeightGrams?.takeIf { it.isFinite() }?.let { record.put("totalWeightGrams", roundOneDecimal(it)) }
        }
        appendRecord(records, record)
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
        var todayTotalMl = 0.0
        var todayRecordCount = 0
        var lastDrankAt = 0L
        for (index in 0 until stored.length()) {
            val record = stored.optJSONObject(index) ?: continue
            val timestamp = record.optLong("timestamp", 0L)
            if (record.optString("type") == "drank" && timestamp in todayStart..now) {
                todayTotalMl += record.optDouble("amountMl", 0.0).coerceAtLeast(0.0)
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

    fun portableState(context: Context): JSONObject = synchronized(lock) {
        val p = prefs(context)
        val portableRecords = JSONArray()
        val stored = records(p)
        for (index in 0 until stored.length()) {
            stored.optJSONObject(index)?.let { record ->
                portableRecords.put(JSONObject(record.toString()).apply {
                    remove("photoPath")
                    remove("sessionId")
                })
            }
        }
        JSONObject()
            .put("consecutiveNotDrank", p.getInt(KEY_CONSECUTIVE_NOT_DRANK, 0).coerceIn(0, 3))
            .put("records", portableRecords)
    }

    fun replacePortableState(context: Context, imported: JSONObject) = synchronized(lock) {
        val validated = JSONArray()
        val source = imported.optJSONArray("records") ?: JSONArray()
        for (index in 0 until minOf(source.length(), MAX_RECORDS)) {
            val item = source.optJSONObject(index) ?: continue
            val id = item.optString("id").takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,100}")) } ?: UUID.randomUUID().toString()
            val type = item.optString("type").takeIf { it in setOf("drank", "not_drank", "state_check") } ?: continue
            val timestamp = item.optLong("timestamp", 0L).takeIf { it > 0L } ?: continue
            val record = JSONObject().put("id", id).put("type", type).put("timestamp", timestamp)
            if (type == "drank") {
                val amount = item.optDouble("amountMl", Double.NaN)
                if (!amount.isFinite() || amount !in 1.0..5_000.0) continue
                record.put("amountMl", roundOneDecimal(amount))
                val entryMode = if (item.optString("entryMode") == "container") "container" else "volume"
                record.put("entryMode", entryMode)
                if (entryMode == "container") {
                    item.optString("containerId").takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,80}")) }?.let { record.put("containerId", it) }
                    item.optString("containerName").trim().takeIf { it.isNotBlank() }?.let { record.put("containerName", it.take(80)) }
                    listOf("emptyWeightGrams", "totalWeightGrams").forEach { key ->
                        item.optDouble(key, Double.NaN).takeIf { it.isFinite() && it in 0.0..105_000.0 }?.let { record.put(key, roundOneDecimal(it)) }
                    }
                }
            }
            if (type == "not_drank") record.put("consecutiveNotDrank", item.optInt("consecutiveNotDrank", 1).coerceIn(1, 3))
            item.optString("photoFileName").takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,180}")) }?.let { record.put("photoFileName", it) }
            validated.put(record)
        }
        val count = imported.optInt("consecutiveNotDrank", 0).coerceIn(0, 3)
        prefs(context).edit()
            .putInt(KEY_CONSECUTIVE_NOT_DRANK, count)
            .putString(KEY_PENDING_FORCED_SESSION, "")
            .putStringSet(KEY_HANDLED_SESSIONS, emptySet<String>())
            .putString(KEY_RECORDS, validated.toString())
            .commit()
    }

    fun createPhotoFile(context: Context, prefix: String, extension: String = "jpg"): File {
        val directory = File(context.filesDir, "water-verifications").apply { mkdirs() }
        val safeExtension = extension.lowercase().takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "jpg"
        return File(directory, "${prefix}-${System.currentTimeMillis()}-${UUID.randomUUID()}.$safeExtension")
    }

    fun photoFile(context: Context, fileName: String): File? {
        if (fileName != File(fileName).name || !fileName.matches(Regex("[A-Za-z0-9._-]{1,180}"))) return null
        return File(context.filesDir, "water-verifications/$fileName").takeIf { it.isFile }
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

    private fun roundOneDecimal(value: Double): Double = kotlin.math.round(value * 10.0) / 10.0
}
