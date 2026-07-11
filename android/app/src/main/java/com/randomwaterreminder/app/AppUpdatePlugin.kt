package com.randomwaterreminder.app

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

@CapacitorPlugin(name = "AppUpdate")
class AppUpdatePlugin : Plugin() {
    private val preferences by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val downloadManager by lazy { context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager }
    private var receiverRegistered = false
    @Volatile private var downloadMonitorRunning = false

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (completedId != preferences.getLong(KEY_DOWNLOAD_ID, -2L)) return
            Thread { refreshDownloadState(verifyCompletedFile = true, notify = true) }.start()
        }
    }

    override fun load() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(downloadReceiver, filter)
        }
        receiverRegistered = true
        Thread {
            val state = refreshDownloadState(verifyCompletedFile = true, notify = false)
            val status = state.optString("status")
            if (status == "pending" || status == "running") startDownloadMonitor()
        }.start()
    }

    override fun handleOnDestroy() {
        if (receiverRegistered) runCatching { context.unregisterReceiver(downloadReceiver) }
        receiverRegistered = false
        super.handleOnDestroy()
    }

    @PluginMethod
    fun getUpdateState(call: PluginCall) {
        Thread {
            runCatching { refreshDownloadState(verifyCompletedFile = false, notify = false) }
            call.resolve(buildState())
        }.start()
    }

    @PluginMethod
    fun setUpdateChannel(call: PluginCall) {
        val channel = normalizeChannel(call.getString("channel"))
        preferences.edit().putString(KEY_CHANNEL, channel).apply()
        call.resolve(buildState())
    }

    @PluginMethod
    fun checkForUpdate(call: PluginCall) {
        val repository = BuildConfig.UPDATE_REPOSITORY.trim()
        if (!repository.matches(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"))) {
            call.reject("当前安装包未配置 GitHub 更新仓库，请通过 GitHub Actions 重新构建")
            return
        }
        val channel = normalizeChannel(call.getString("channel", preferences.getString(KEY_CHANNEL, "stable") ?: "stable"))
        val manual = call.getBoolean("manual", false) ?: false
        preferences.edit().putString(KEY_CHANNEL, channel).apply()
        val lastCheckedAt = preferences.getLong(KEY_LAST_CHECKED_AT, 0L)
        if (!manual && System.currentTimeMillis() - lastCheckedAt < AUTO_CHECK_INTERVAL_MS) {
            call.resolve(buildState())
            return
        }

        Thread {
            runCatching {
                val manifest = findLatestManifest(repository, channel)
                val currentVersionCode = currentVersionCode()
                preferences.edit()
                    .putLong(KEY_LAST_CHECKED_AT, System.currentTimeMillis())
                    .putString(KEY_MANIFEST, manifest?.toString().orEmpty())
                    .apply()
                if (manifest == null || manifest.optLong("versionCode", 0L) <= currentVersionCode) {
                    clearDownloadState(deleteFile = true)
                } else {
                    val previousVersionCode = preferences.getLong(KEY_DOWNLOAD_VERSION_CODE, -1L)
                    if (previousVersionCode != manifest.optLong("versionCode", -2L)) clearDownloadState(deleteFile = true)
                }
                buildState()
            }.onSuccess(call::resolve).onFailure { call.reject(it.message ?: "检查更新失败", it) }
        }.start()
    }

    @PluginMethod
    fun downloadUpdate(call: PluginCall) {
        val manifest = readManifest()
        if (manifest == null || manifest.optLong("versionCode", 0L) <= currentVersionCode()) {
            call.reject("没有可下载的新版本")
            return
        }
        val apkUrl = manifest.optString("apkUrl")
        if (!apkUrl.startsWith("https://")) {
            call.reject("更新下载地址无效")
            return
        }

        runCatching {
            clearDownloadState(deleteFile = true)
            val versionCode = manifest.getLong("versionCode")
            val fileName = "waterbreak-${manifest.optString("versionName", versionCode.toString())}.apk"
            val request = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("水息守护 ${manifest.optString("versionName")}")
                .setDescription("正在下载应用更新")
                .setMimeType(APK_MIME_TYPE)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "updates/$fileName")
            val id = downloadManager.enqueue(request)
            preferences.edit()
                .putLong(KEY_DOWNLOAD_ID, id)
                .putLong(KEY_DOWNLOAD_VERSION_CODE, versionCode)
                .putString(KEY_DOWNLOAD_STATUS, "pending")
                .remove(KEY_DOWNLOAD_ERROR)
                .apply()
            startDownloadMonitor()
            call.resolve(buildState())
        }.onFailure { call.reject(it.message ?: "开始下载失败", it) }
    }

    @PluginMethod
    fun installDownloadedUpdate(call: PluginCall) {
        Thread {
            runCatching {
                val file = verifiedDownloadedFile()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                    JSObject().apply {
                        put("started", false)
                        put("requiresPermission", true)
                    }
                } else {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, APK_MIME_TYPE)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(intent)
                    JSObject().apply {
                        put("started", true)
                        put("requiresPermission", false)
                    }
                }
            }.onSuccess(call::resolve).onFailure { call.reject(it.message ?: "无法安装更新", it) }
        }.start()
    }

    @PluginMethod
    fun openUnknownSourceSettings(call: PluginCall) {
        runCatching {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            } else {
                Intent(Settings.ACTION_SECURITY_SETTINGS)
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            call.resolve(JSObject().apply { put("opened", true) })
        }.onFailure { call.resolve(JSObject().apply { put("opened", false) }) }
    }

    private fun findLatestManifest(repository: String, channel: String): JSONObject? {
        val releases = if (channel == "stable") {
            listOf(fetchJsonObject("https://api.github.com/repos/$repository/releases/latest"))
        } else {
            val array = fetchJsonArray("https://api.github.com/repos/$repository/releases?per_page=20")
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }.filterNot { it.optBoolean("draft") }
        }

        for (release in releases) {
            if (channel == "stable" && release.optBoolean("prerelease")) continue
            val assets = release.optJSONArray("assets") ?: continue
            val manifestAsset = (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it) }
                .firstOrNull { it.optString("name") == "update.json" }
                ?: continue
            val manifestUrl = manifestAsset.optString("browser_download_url")
            if (!manifestUrl.startsWith("https://")) continue
            val manifest = fetchJsonObject(manifestUrl)
            manifest.put("prerelease", release.optBoolean("prerelease"))
            manifest.put("releasePageUrl", release.optString("html_url"))
            if (manifest.optString("channel").isBlank()) manifest.put("channel", if (release.optBoolean("prerelease")) "beta" else "stable")
            return manifest
        }
        return null
    }

    private fun fetchJsonObject(url: String): JSONObject = JSONObject(fetchText(url))
    private fun fetchJsonArray(url: String): JSONArray = JSONArray(fetchText(url))

    private fun fetchText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "WaterBreak/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("GitHub 更新服务返回 HTTP $code")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun startDownloadMonitor() {
        if (downloadMonitorRunning) return
        downloadMonitorRunning = true
        Thread {
            try {
                while (true) {
                    val state = refreshDownloadState(verifyCompletedFile = true, notify = true)
                    val status = state.optString("status")
                    if (status != "pending" && status != "running") break
                    Thread.sleep(1_000L)
                }
            } finally {
                downloadMonitorRunning = false
            }
        }.start()
    }

    private fun refreshDownloadState(verifyCompletedFile: Boolean, notify: Boolean): JSObject {
        val id = preferences.getLong(KEY_DOWNLOAD_ID, -1L)
        if (id < 0) return buildState()
        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(id)) ?: return buildState()
        cursor.use {
            if (!it.moveToFirst()) return buildState()
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0L)
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).coerceAtLeast(0L)
            val localUri = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)).orEmpty()
            val editor = preferences.edit()
                .putLong(KEY_DOWNLOADED_BYTES, downloaded)
                .putLong(KEY_TOTAL_BYTES, total)
            when (status) {
                DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED -> editor.putString(KEY_DOWNLOAD_STATUS, "pending")
                DownloadManager.STATUS_RUNNING -> editor.putString(KEY_DOWNLOAD_STATUS, "running")
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val file = localUri.takeIf { uri -> uri.startsWith("file:") }?.let { uri -> File(Uri.parse(uri).path.orEmpty()) }
                    if (file != null) editor.putString(KEY_DOWNLOADED_FILE, file.absolutePath)
                    if (verifyCompletedFile) {
                        runCatching { verifiedDownloadedFile(file) }
                            .onSuccess { editor.putString(KEY_DOWNLOAD_STATUS, "downloaded").remove(KEY_DOWNLOAD_ERROR) }
                            .onFailure { error -> editor.putString(KEY_DOWNLOAD_STATUS, "failed").putString(KEY_DOWNLOAD_ERROR, error.message ?: "更新包校验失败") }
                    } else {
                        editor.putString(KEY_DOWNLOAD_STATUS, "downloaded")
                    }
                }
                DownloadManager.STATUS_FAILED -> {
                    val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    editor.putString(KEY_DOWNLOAD_STATUS, "failed").putString(KEY_DOWNLOAD_ERROR, "下载失败（代码 $reason）")
                }
            }
            editor.apply()
        }
        return buildState().also { if (notify) notifyListeners("updateDownloadState", it) }
    }

    private fun verifiedDownloadedFile(candidate: File? = null): File {
        val manifest = readManifest() ?: throw IllegalStateException("更新信息已失效，请重新检查")
        val file = candidate ?: preferences.getString(KEY_DOWNLOADED_FILE, null)?.let(::File)
            ?: throw IllegalStateException("更新包尚未下载完成")
        if (!file.isFile || file.length() <= 0L) throw IllegalStateException("更新包文件不存在")
        val expectedHash = manifest.optString("sha256").lowercase(Locale.US)
        if (!expectedHash.matches(Regex("^[0-9a-f]{64}$"))) throw IllegalStateException("更新清单缺少有效 SHA-256")
        val actualHash = sha256(file)
        if (actualHash != expectedHash) throw IllegalStateException("更新包 SHA-256 校验失败")

        val archive = archivePackageInfo(file) ?: throw IllegalStateException("下载文件不是有效 APK")
        if (archive.packageName != context.packageName) throw IllegalStateException("更新包应用标识不匹配")
        if (packageVersionCode(archive) <= currentVersionCode()) throw IllegalStateException("更新包版本不高于当前版本")
        if (packageVersionCode(archive) != manifest.optLong("versionCode")) throw IllegalStateException("更新包版本与更新清单不一致")

        val current = currentPackageInfo()
        if (certificateDigests(current) != certificateDigests(archive)) throw IllegalStateException("更新包签名与当前应用不一致")
        return file
    }

    @Suppress("DEPRECATION")
    private fun archivePackageInfo(file: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)?.also {
            it.applicationInfo?.sourceDir = file.absolutePath
            it.applicationInfo?.publicSourceDir = file.absolutePath
        }
    }

    @Suppress("DEPRECATION")
    private fun currentPackageInfo(): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return context.packageManager.getPackageInfo(context.packageName, flags)
    }

    private fun certificateDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners else signingInfo.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            info.signatures ?: emptyArray()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun buildState(): JSObject {
        val manifest = readManifest()
        val currentCode = currentVersionCode()
        val latestCode = manifest?.optLong("versionCode", 0L) ?: 0L
        val total = preferences.getLong(KEY_TOTAL_BYTES, 0L)
        val downloaded = preferences.getLong(KEY_DOWNLOADED_BYTES, 0L)
        val progress = if (total > 0L) ((downloaded * 100L) / total).coerceIn(0L, 100L).toInt() else 0
        return JSObject().apply {
            put("currentVersionName", BuildConfig.VERSION_NAME)
            put("currentVersionCode", currentCode)
            put("repository", BuildConfig.UPDATE_REPOSITORY)
            put("channel", normalizeChannel(preferences.getString(KEY_CHANNEL, "stable")))
            put("available", latestCode > currentCode)
            put("status", preferences.getString(KEY_DOWNLOAD_STATUS, "idle") ?: "idle")
            put("progress", progress)
            put("downloadedBytes", downloaded)
            put("totalBytes", total)
            put("lastCheckedAt", preferences.getLong(KEY_LAST_CHECKED_AT, 0L))
            preferences.getString(KEY_DOWNLOADED_FILE, null)?.let { put("downloadedFile", it) }
            preferences.getString(KEY_DOWNLOAD_ERROR, null)?.let { put("error", it) }
            if (manifest != null && latestCode > currentCode) {
                put("available", true)
                put("latestVersionName", manifest.optString("versionName"))
                put("latestVersionCode", latestCode)
                put("minimumSupportedVersionCode", manifest.optLong("minimumSupportedVersionCode", 1L))
                put("mandatory", manifest.optBoolean("mandatory", false))
                put("prerelease", manifest.optBoolean("prerelease", false))
                put("publishedAt", manifest.optString("publishedAt"))
                put("apkUrl", manifest.optString("apkUrl"))
                put("sha256", manifest.optString("sha256"))
                put("size", manifest.optLong("size", 0L))
                put("releasePageUrl", manifest.optString("releasePageUrl"))
                val notes = manifest.optJSONArray("releaseNotes") ?: JSONArray()
                put("releaseNotes", JSArray(notes.toString()))
            }
        }
    }

    private fun readManifest(): JSONObject? = preferences.getString(KEY_MANIFEST, null)
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun clearDownloadState(deleteFile: Boolean) {
        val id = preferences.getLong(KEY_DOWNLOAD_ID, -1L)
        if (id >= 0L) runCatching { downloadManager.remove(id) }
        if (deleteFile) preferences.getString(KEY_DOWNLOADED_FILE, null)?.let { runCatching { File(it).delete() } }
        preferences.edit()
            .remove(KEY_DOWNLOAD_ID)
            .remove(KEY_DOWNLOAD_VERSION_CODE)
            .remove(KEY_DOWNLOADED_FILE)
            .remove(KEY_DOWNLOADED_BYTES)
            .remove(KEY_TOTAL_BYTES)
            .remove(KEY_DOWNLOAD_ERROR)
            .putString(KEY_DOWNLOAD_STATUS, "idle")
            .apply()
    }

    private fun currentVersionCode(): Long = packageVersionCode(currentPackageInfo())
    @Suppress("DEPRECATION")
    private fun packageVersionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    private fun normalizeChannel(value: String?): String = if (value == "beta") "beta" else "stable"

    companion object {
        private const val PREFS_NAME = "app_update"
        private const val KEY_CHANNEL = "channel"
        private const val KEY_LAST_CHECKED_AT = "last_checked_at"
        private const val KEY_MANIFEST = "manifest"
        private const val KEY_DOWNLOAD_ID = "download_id"
        private const val KEY_DOWNLOAD_VERSION_CODE = "download_version_code"
        private const val KEY_DOWNLOAD_STATUS = "download_status"
        private const val KEY_DOWNLOADED_FILE = "downloaded_file"
        private const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        private const val KEY_TOTAL_BYTES = "total_bytes"
        private const val KEY_DOWNLOAD_ERROR = "download_error"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val AUTO_CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L
    }
}
