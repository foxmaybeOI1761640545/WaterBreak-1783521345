package com.randomwaterreminder.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object WaterDataArchive {
    private const val SCHEMA_ID = "water-reminder.portable-data"
    private const val SCHEMA_VERSION = 1
    private const val MAX_ENTRIES = 1_220
    private const val MAX_UNCOMPRESSED_BYTES = 180L * 1024L * 1024L

    fun create(context: Context): File {
        val target = File(context.cacheDir, "water-data-${System.currentTimeMillis()}.waterdata.zip")
        val checkIn = WaterCheckInStore.portableState(context)
        val manifest = JSONObject()
            .put("schemaId", SCHEMA_ID)
            .put("schemaVersion", SCHEMA_VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("containers", WaterContainerStore.list(context))
            .put("waterCheckIn", checkIn)
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            val names = linkedSetOf<String>()
            val records = checkIn.optJSONArray("records")
            for (index in 0 until (records?.length() ?: 0)) {
                val record = records?.optJSONObject(index) ?: continue
                val photoNames = record.optJSONArray("photoFileNames")
                for (photoIndex in 0 until (photoNames?.length() ?: 0)) {
                    photoNames?.optString(photoIndex)
                        ?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,180}")) }
                        ?.let { names += it }
                }
                record.optString("photoFileName")
                    .takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,180}")) }
                    ?.let { names += it }
            }
            names.forEach { name ->
                WaterCheckInStore.photoFile(context, name)?.let { photo ->
                    zip.putNextEntry(ZipEntry("photos/$name"))
                    FileInputStream(photo).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        return target
    }

    fun import(context: Context, archive: File) {
        val staging = File(context.cacheDir, "water-import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            var manifest: JSONObject? = null
            var entryCount = 0
            var totalBytes = 0L
            ZipInputStream(FileInputStream(archive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount += 1
                    require(entryCount <= MAX_ENTRIES) { "迁移档案包含过多文件" }
                    val name = entry.name
                    require(!entry.isDirectory && !name.contains("..") && !name.startsWith('/')) { "迁移档案包含非法路径" }
                    if (name == "manifest.json") {
                        val bytes = readLimited(zip, 2L * 1024L * 1024L)
                        totalBytes += bytes.size
                        manifest = JSONObject(bytes.toString(Charsets.UTF_8))
                    } else if (name.startsWith("photos/")) {
                        val fileName = name.removePrefix("photos/")
                        require(fileName == File(fileName).name && fileName.matches(Regex("[A-Za-z0-9._-]{1,180}"))) { "照片文件名无效" }
                        val target = File(staging, fileName)
                        FileOutputStream(target).use { output ->
                            val copied = copyLimited(zip, output, MAX_UNCOMPRESSED_BYTES - totalBytes)
                            totalBytes += copied
                        }
                    }
                    require(totalBytes <= MAX_UNCOMPRESSED_BYTES) { "迁移档案解压后过大" }
                    zip.closeEntry()
                }
            }
            val data = requireNotNull(manifest) { "迁移档案缺少 manifest.json" }
            require(data.optString("schemaId") == SCHEMA_ID) { "不是受支持的喝水数据档案" }
            require(data.optInt("schemaVersion") == SCHEMA_VERSION) { "暂不支持此档案版本" }
            val checkIn = data.optJSONObject("waterCheckIn") ?: error("迁移档案缺少喝水记录")
            val photoDirectory = File(context.filesDir, "water-verifications").apply { mkdirs() }
            staging.listFiles().orEmpty().forEach { source -> source.copyTo(File(photoDirectory, source.name), overwrite = true) }
            WaterContainerStore.replaceAll(context, data.optJSONArray("containers") ?: org.json.JSONArray())
            WaterCheckInStore.replacePortableState(context, checkIn)
        } finally {
            staging.deleteRecursively()
            archive.delete()
        }
    }

    private fun readLimited(input: java.io.InputStream, limit: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        copyLimited(input, output, limit)
        return output.toByteArray()
    }

    private fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream, limit: Long): Long {
        require(limit > 0L) { "迁移档案解压后过大" }
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "迁移档案解压后过大" }
            output.write(buffer, 0, read)
        }
        return total
    }
}
