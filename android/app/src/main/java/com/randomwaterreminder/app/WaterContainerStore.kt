package com.randomwaterreminder.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object WaterContainerStore {
    private const val PREFS = "water_container_store"
    private const val KEY_CONTAINERS = "containers_v1"
    private const val DEFAULT_ID = "default-cup-241-5"
    private val lock = Any()

    fun list(context: Context): JSONArray = synchronized(lock) {
        val stored = readStored(context)
        if (stored.length() > 0) return@synchronized JSONArray(stored.toString())
        val defaults = JSONArray().put(defaultContainer())
        write(context, defaults)
        JSONArray(defaults.toString())
    }

    fun save(context: Context, id: String?, name: String, emptyWeightGrams: Double): JSONObject = synchronized(lock) {
        require(name.trim().isNotBlank()) { "容器名称不能为空" }
        require(emptyWeightGrams in 0.1..100_000.0) { "空容器重量必须在 0.1-100000g 之间" }
        val containers = list(context)
        val stableId = id?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,80}")) } ?: "container-${UUID.randomUUID()}"
        val item = JSONObject()
            .put("id", stableId)
            .put("name", name.trim())
            .put("emptyWeightGrams", roundOneDecimal(emptyWeightGrams))
        var replaced = false
        for (index in 0 until containers.length()) {
            if (containers.optJSONObject(index)?.optString("id") == stableId) {
                containers.put(index, item)
                replaced = true
                break
            }
        }
        if (!replaced) containers.put(item)
        write(context, containers)
        JSONObject(item.toString())
    }

    fun delete(context: Context, id: String): Boolean = synchronized(lock) {
        val containers = list(context)
        if (containers.length() <= 1) return@synchronized false
        for (index in 0 until containers.length()) {
            if (containers.optJSONObject(index)?.optString("id") == id) {
                containers.remove(index)
                write(context, containers)
                return@synchronized true
            }
        }
        false
    }

    fun replaceAll(context: Context, imported: JSONArray) = synchronized(lock) {
        val validated = JSONArray()
        for (index in 0 until imported.length()) {
            val item = imported.optJSONObject(index) ?: continue
            val id = item.optString("id").takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,80}")) } ?: continue
            val name = item.optString("name").trim().takeIf { it.isNotBlank() } ?: continue
            val weight = item.optDouble("emptyWeightGrams", Double.NaN)
            if (!weight.isFinite() || weight !in 0.1..100_000.0) continue
            validated.put(JSONObject().put("id", id).put("name", name).put("emptyWeightGrams", roundOneDecimal(weight)))
        }
        write(context, if (validated.length() > 0) validated else JSONArray().put(defaultContainer()))
    }

    private fun readStored(context: Context): JSONArray = runCatching {
        JSONArray(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CONTAINERS, "[]") ?: "[]")
    }.getOrDefault(JSONArray())

    private fun write(context: Context, containers: JSONArray) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CONTAINERS, containers.toString()).commit()
    }

    private fun defaultContainer(): JSONObject = JSONObject()
        .put("id", DEFAULT_ID)
        .put("name", "常用水杯")
        .put("emptyWeightGrams", 241.5)

    private fun roundOneDecimal(value: Double): Double = kotlin.math.round(value * 10.0) / 10.0
}
