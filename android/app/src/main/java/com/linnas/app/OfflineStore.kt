package com.linnas.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 离线缓存登记表:把服务器上的文件下载到本机,按 vpath 记录进度与状态。
 * 数据存 SharedPreferences("linnas_offline"),文件存 getExternalFilesDir("offline")。
 */
object OfflineStore {
    private const val PREF = "linnas_offline"

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
    ) {
        val percent: Int
            get() = if (size > 0) ((bytes * 100) / size).toInt().coerceIn(0, 100) else 0
    }

    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "offline").apply { mkdirs() }

    /** 已下载完成的本地文件(未完成返回 null)。 */
    fun localFile(ctx: Context, vpath: String?): File? {
        if (vpath.isNullOrBlank()) return null
        val it = get(ctx, vpath) ?: return null
        if (it.status != ST_DONE) return null
        val f = fileFor(ctx, vpath)
        return if (f.exists() && f.length() > 0) f else null
    }

    fun fileFor(ctx: Context, vpath: String): File = File(dir(ctx), fileName(vpath))

    fun partFor(ctx: Context, vpath: String): File = File(dir(ctx), fileName(vpath) + ".part")

    private fun fileName(vpath: String): String {
        val base = vpath.substringAfterLast('/').replace(Regex("[^\\w.\\-]"), "_").ifBlank { "file" }
        return Integer.toHexString(vpath.hashCode()) + "_" + base
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun put(ctx: Context, item: Item) {
        val o = JSONObject()
            .put("vpath", item.vpath)
            .put("name", item.name)
            .put("size", item.size)
            .put("bytes", item.bytes)
            .put("status", item.status)
            .put("msg", item.msg)
        sp(ctx).edit().putString(item.vpath, o.toString()).apply()
    }

    fun get(ctx: Context, vpath: String): Item? {
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
                )
            } catch (_: Exception) {
                null
            }
        }.sortedBy { it.name }

    /** 删除记录与文件。 */
    fun remove(ctx: Context, vpath: String) {
        sp(ctx).edit().remove(vpath).apply()
        runCatching { fileFor(ctx, vpath).delete() }
        runCatching { partFor(ctx, vpath).delete() }
    }

    /** 清理所有记录(不动已下载文件之外的其它数据)。 */
    fun clearAll(ctx: Context) = sp(ctx).edit().clear().apply()

    fun totalBytes(ctx: Context): Long = all(ctx).sumOf { it.bytes }
}
