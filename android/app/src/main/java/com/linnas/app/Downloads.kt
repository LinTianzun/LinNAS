package com.linnas.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

/** 下载:用系统 DownloadManager(带进度通知),并记录任务供「传输」页展示进度。 */
object Downloads {
    private const val PREF = "linnas_dl"

    data class DTask(val id: Long, val name: String, val done: Long, val total: Long, val status: Int)

    fun enqueue(ctx: Context, url: String, fileName: String): Long {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val safe = fileName.replace('/', '_').replace('\\', '_')
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(safe)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, safe)
        val id = dm.enqueue(req)
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val set = sp.getStringSet("ids", HashSet())?.toMutableSet() ?: mutableSetOf()
        set.add("$id|$safe")
        sp.edit().putStringSet("ids", set).apply()
        return id
    }

    fun tasks(ctx: Context): List<DTask> {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = sp.getStringSet("ids", HashSet())?.toList() ?: emptyList()
        val out = mutableListOf<DTask>()
        for (item in raw) {
            val parts = item.split('|', limit = 2)
            val id = parts.getOrNull(0)?.toLongOrNull() ?: continue
            val name = parts.getOrNull(1) ?: "file"
            try {
                val cur = dm.query(DownloadManager.Query().setFilterById(id))
                cur?.use { c ->
                    if (c.moveToFirst()) {
                        val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        out.add(DTask(id, name, done, total, st))
                    }
                }
            } catch (_: Exception) { }
        }
        return out
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
