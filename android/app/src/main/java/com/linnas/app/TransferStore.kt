package com.linnas.app

import android.content.Context
import org.json.JSONObject

/**
 * 统一传输中心:记录「下载 / 打包下载」任务的进度与结果。
 * 与离线缓存(OfflineStore)、上传(UploadService)一起构成传输页三个区。
 * 数据存 SharedPreferences("linnas_transfer"),每条任务一个 key(任务 id)。
 */
object TransferStore {
    const val ST_QUEUED = "queued"
    const val ST_RUNNING = "running"
    const val ST_PAUSED = "paused"
    const val ST_DONE = "done"
    const val ST_FAILED = "failed"
    const val ST_CANCELLED = "cancelled"

    const val KIND_DOWNLOAD = "download"
    const val KIND_ZIP = "zip"

    data class Item(
        val id: String,
        val title: String,
        val url: String = "",
        val fileName: String = "",
        val kind: String = KIND_DOWNLOAD,
        /** 目标 SAF 目录(空 = 存到应用私有下载目录)。 */
        val treeUri: String = "",
        val total: Long = 0,
        val done: Long = 0,
        val state: String = ST_QUEUED,
        val error: String = "",
        /** 完成后可打开的位置:content:// (SAF) 或 file:// (应用私有)。 */
        val localUri: String = "",
        val mime: String = "",
        val createdAt: Long = System.currentTimeMillis(),
        /** v1.8.1 瞬时速度(B/s),0 = 无数据。 */
        val speedBps: Long = 0,
        /** v1.8.1 最近一次进度更新时间戳(毫秒)。 */
        val updatedAt: Long = 0,
    ) {
        val percent: Int
            get() = when {
                state == ST_DONE -> 100
                total > 0 -> ((done.toDouble() / total) * 100).toInt().coerceIn(0, 100)
                else -> 0
            }
    }

    private const val PREF = "linnas_transfer"
    private val lock = Any()
    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun put(ctx: Context, item: Item) {
        synchronized(lock) { sp(ctx).edit().putString(item.id, encode(item)).apply() }
    }

    fun get(ctx: Context, id: String): Item? = synchronized(lock) {
        sp(ctx).getString(id, null)?.let { decode(it) }
    }

    fun remove(ctx: Context, id: String) {
        synchronized(lock) { sp(ctx).edit().remove(id).apply() }
    }

    fun all(ctx: Context): List<Item> = synchronized(lock) {
        sp(ctx).all.values.mapNotNull { (it as? String)?.let(::decode) }
            .sortedByDescending { it.createdAt }
    }

    /** 清掉已完成 / 已取消 / 已失败 的记录。 */
    fun clearFinished(ctx: Context) {
        synchronized(lock) {
            val e = sp(ctx).edit()
            for ((k, v) in sp(ctx).all) {
                val st = (v as? String)?.let(::decode)?.state
                if (st == ST_DONE || st == ST_CANCELLED || st == ST_FAILED) e.remove(k)
            }
            e.apply()
        }
    }

    private fun encode(i: Item): String = JSONObject()
        .put("id", i.id).put("title", i.title).put("url", i.url).put("fileName", i.fileName)
        .put("kind", i.kind).put("treeUri", i.treeUri).put("total", i.total).put("done", i.done)
        .put("state", i.state).put("error", i.error).put("localUri", i.localUri)
        .put("mime", i.mime).put("createdAt", i.createdAt)
        .put("speedBps", i.speedBps).put("updatedAt", i.updatedAt).toString()

    private fun decode(s: String): Item? = try {
        val o = JSONObject(s)
        Item(
            id = o.optString("id"), title = o.optString("title"), url = o.optString("url"),
            fileName = o.optString("fileName"), kind = o.optString("kind", KIND_DOWNLOAD),
            treeUri = o.optString("treeUri"), total = o.optLong("total"), done = o.optLong("done"),
            state = o.optString("state", ST_FAILED), error = o.optString("error"),
            localUri = o.optString("localUri"), mime = o.optString("mime"),
            createdAt = o.optLong("createdAt"),
            speedBps = o.optLong("speedBps"), updatedAt = o.optLong("updatedAt"),
        )
    } catch (_: Exception) {
        null
    }
}
