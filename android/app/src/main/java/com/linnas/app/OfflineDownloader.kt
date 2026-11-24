package com.linnas.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object OfflineDownloader {
    private const val CHANNEL_ID = "offline_dl"
    private var notifId = 2000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val canceled = ConcurrentHashMap<String, Boolean>()

    fun active(): Set<String> = jobs.keys

    fun start(ctx: Context, vpath: String, name: String, size: Long) {
        if (jobs.containsKey(vpath)) return
        canceled.remove(vpath)
        val app = ctx.applicationContext
        val job = scope.launch { runDownload(app, vpath, name, size) }
        jobs[vpath] = job
        job.invokeOnCompletion { jobs.remove(vpath) }
    }

    fun pause(ctx: Context, vpath: String) {
        canceled[vpath] = true
        jobs.remove(vpath)?.cancel()
        OfflineStore.get(ctx, vpath)?.let {
            OfflineStore.put(ctx, it.copy(status = OfflineStore.ST_PAUSED, msg = "paused"))
        }
    }

    fun cancel(ctx: Context, vpath: String) {
        canceled[vpath] = true
        jobs.remove(vpath)?.cancel()
        OfflineStore.remove(ctx, vpath)
    }

    fun resume(ctx: Context, vpath: String, name: String, size: Long) {
        val it = OfflineStore.get(ctx, vpath)
        start(ctx, vpath, name, if (size > 0) size else (it?.size ?: 0L))
    }

    private fun notify(ctx: Context, title: String, pct: Int, ongoing: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "offline", NotificationManager.IMPORTANCE_LOW))
                nm.notify(notifId, NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(title)
                    .setProgress(100, pct, false)
                    .setOngoing(ongoing)
                    .build())
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun runDownload(ctx: Context, vpath: String, name: String, size: Long) {
        val part = OfflineStore.partFor(ctx, vpath)
        val target = OfflineStore.fileFor(ctx, vpath)
        part.parentFile?.mkdirs()
        val existing = if (part.exists()) part.length() else 0L
        val old = OfflineStore.get(ctx, vpath)
        val total0 = if (size > 0) size else (old?.size ?: 0L)
        OfflineStore.put(ctx, OfflineStore.Item(vpath, name, total0, existing,
            OfflineStore.ST_DOWNLOADING, ""))
        try {
            val req = Request.Builder().url(LinNasClient().rawUrl(vpath, true)).apply {
                if (existing > 0) header("Range", "bytes=$existing-")
            }.build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 401) throw ApiException("token invalid")
                if (!resp.isSuccessful && resp.code != 206) {
                    throw ApiException("download failed HTTP ${resp.code}")
                }
                val append = resp.code == 206 && existing > 0
                val cr = resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: 0L
                val bodyLen = resp.body?.contentLength() ?: -1L
                val total = when {
                    resp.code == 206 -> existing + cr
                    bodyLen > 0 -> bodyLen
                    else -> total0
                }
                var written = if (append) existing else 0L
                val body = resp.body ?: throw ApiException("empty body")
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(65536)
                    val stream = body.byteStream()
                    var last = 0L
                    while (true) {
                        if (canceled[vpath] == true) return
                        val n = stream.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        written += n
                        val now = System.currentTimeMillis()
                        if (now - last > 400) {
                            last = now
                            OfflineStore.put(ctx, OfflineStore.Item(vpath, name, total, written,
                                OfflineStore.ST_DOWNLOADING, ""))
                            val pct = if (total > 0) ((written * 100) / total).toInt() else 0
                            notify(ctx, "offline $name $pct%", pct, true)
                        }
                    }
                    out.flush()
                }
                if (canceled[vpath] == true) return
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    part.copyTo(target, true)
                    part.delete()
                }
                OfflineStore.put(ctx, OfflineStore.Item(vpath, name, total, written,
                    OfflineStore.ST_DONE, ""))
                notify(ctx, "cached $name", 100, false)
            }
        } catch (e: Exception) {
            if (canceled[vpath] == true) return
            val msg = e.message ?: e.javaClass.simpleName
            OfflineStore.put(ctx, OfflineStore.Item(vpath, name, total0, existing,
                OfflineStore.ST_FAILED, msg))
            notify(ctx, "failed $name: $msg", 0, false)
        } finally {
            canceled.remove(vpath)
        }
    }
}
