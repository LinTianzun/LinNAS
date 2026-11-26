package com.linnas.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 离线缓存登记表:把服务器上的文件下载到本机,按 vpath 记录进度与状态。
 * 数据存 SharedPreferences("linnas_offline"),文件存 getExternalFilesDir("offline")。
 */
object OfflineStore {
    private const val PREF = "linnas_offline"
    private val lock = Any()

    const val ST_DOWNLOADING = "downloading"
    const val ST_PAUSED = "paused"
    const val ST_DONE = "done"
    const val ST_FAILED = "failed"

    data class Item(
        val vpath: String,
        val name: String,
        val size: Long,
        val bytes: Long,
        val status: String,
        val msg: String = "",
        val taskId: String = "",
        /** v1.8.1 瞬时速度(B/s)。 */
        val speedBps: Long = 0,
        /** v1.8.1 缓存文件另存到用户下载目录后的 content:// 地址(空 = 只在应用内)。 */
        val savedUri: String = "",
    ) {
        val percent: Int
            get() = if (size > 0) ((bytes.toDouble() / size) * 100).toInt().coerceIn(0, 100) else 0
    }

    fun dir(ctx: Context): File {
        val parent = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return File(parent, "offline").apply {
            if (!exists() && !mkdirs()) throw IllegalStateException("无法创建离线缓存目录")
            if (!isDirectory) throw IllegalStateException("离线缓存路径不是文件夹")
        }
    }

    /** 已下载完成的本地文件(未完成返回 null)。 */
    fun localFile(ctx: Context, vpath: String?): File? {
        if (vpath.isNullOrBlank()) return null
        val it = get(ctx, vpath) ?: return null
        if (it.status != ST_DONE) return null
        val f = fileFor(ctx, vpath)
        return if (f.isFile && f.length() == it.size) f else null
    }

    fun fileFor(ctx: Context, vpath: String): File = File(dir(ctx), fileName(vpath))

    fun partFor(ctx: Context, vpath: String): File = File(dir(ctx), fileName(vpath) + ".part")

    private fun fileName(vpath: String): String {
        val base = vpath.substringAfterLast('/').replace(Regex("[^\\w.\\-]"), "_")
            .ifBlank { "file" }.take(120)
        return stableId(vpath) + "_" + base
    }

    internal fun stableId(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun put(ctx: Context, item: Item) {
        synchronized(lock) {
            putUnlocked(ctx, item)
        }
    }

    fun putDurable(ctx: Context, item: Item): Boolean =
        synchronized(lock) {
            sp(ctx).edit().putString(item.vpath, encode(item)).commit()
        }

    private fun putUnlocked(ctx: Context, item: Item) {
        sp(ctx).edit().putString(item.vpath, encode(item)).apply()
    }

    private fun encode(item: Item): String {
        val o = JSONObject()
            .put("vpath", item.vpath)
            .put("name", item.name)
            .put("size", item.size)
            .put("bytes", item.bytes)
            .put("status", item.status)
            .put("msg", item.msg)
            .put("taskId", item.taskId)
            .put("speedBps", item.speedBps)
            .put("savedUri", item.savedUri)
        return o.toString()
    }

    fun updateIfStatus(
        ctx: Context,
        item: Item,
        expectedStatus: String,
        expectedTaskId: String? = null,
    ): Boolean =
        synchronized(lock) {
            val current = getUnlocked(ctx, item.vpath) ?: return@synchronized false
            if (current.status != expectedStatus) return@synchronized false
            if (expectedTaskId != null && current.taskId != expectedTaskId) return@synchronized false
            putUnlocked(ctx, item)
            true
        }

    fun isStatus(ctx: Context, vpath: String, status: String): Boolean =
        synchronized(lock) { getUnlocked(ctx, vpath)?.status == status }

    fun get(ctx: Context, vpath: String): Item? {
        synchronized(lock) {
            return getUnlocked(ctx, vpath)
        }
    }

    private fun getUnlocked(ctx: Context, vpath: String): Item? {
        val raw = sp(ctx).getString(vpath, null) ?: return null
        return try {
            val o = JSONObject(raw)
            Item(
                vpath = o.optString("vpath", vpath),
                name = o.optString("name", vpath.substringAfterLast('/')),
                size = o.optLong("size", 0),
                bytes = o.optLong("bytes", 0),
                status = o.optString("status", ST_FAILED),
                msg = o.optString("msg", ""),
                taskId = o.optString("taskId", ""),
                speedBps = o.optLong("speedBps"),
                savedUri = o.optString("savedUri", ""),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun all(ctx: Context): List<Item> =
        sp(ctx).all.values.mapNotNull { v ->
            try {
                val o = JSONObject(v as String)
                Item(
                    vpath = o.optString("vpath"),
                    name = o.optString("name"),
                    size = o.optLong("size", 0),
                    bytes = o.optLong("bytes", 0),
                    status = o.optString("status", ST_FAILED),
                    msg = o.optString("msg", ""),
                    taskId = o.optString("taskId", ""),
                    speedBps = o.optLong("speedBps"),
                    savedUri = o.optString("savedUri", ""),
                )
            } catch (_: Exception) {
                null
            }
        }.sortedBy { it.name }

    /** 删除记录与文件。 */
    fun remove(ctx: Context, vpath: String) {
        synchronized(lock) {
            sp(ctx).edit().remove(vpath).apply()
            fileFor(ctx, vpath).delete()
            partFor(ctx, vpath).delete()
        }
    }

    /** 清理所有记录(不动已下载文件之外的其它数据)。 */
    fun clearAll(ctx: Context) = synchronized(lock) { sp(ctx).edit().clear().apply() }

    fun totalBytes(ctx: Context): Long = all(ctx).sumOf { it.bytes }
}
