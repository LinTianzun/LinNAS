package com.linnas.app.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.linnas.app.TransferStore
import com.linnas.app.UiPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * 统一下载 / 打包下载前台服务(v1.8.0)。
 *
 * 为什么不再用系统 DownloadManager:它可以下载,但把文件写进应用私有目录,
 * 手机「文件」里根本看不到,下载完也没有"打开"入口 —— 用户感受就是"进度跑完然后卡住/失败"。
 * 这里改为:
 *  - 保存位置由用户在设置里用 SAF(系统文件夹选择器)指定,存到用户能看到的地方;
 *  - 自己实现队列与进度,支持暂停 / 继续(断点续传)、取消、重试,并记录失败原因;
 *  - 完成后在传输页提供「打开 / 分享」。
 */
class TransferService : Service() {

    companion object {
        const val CHANNEL_ID = "download"
        const val NOTIF_ID = 210

        private val queue = ConcurrentLinkedQueue<String>()
        private val cancelled = ConcurrentHashMap.newKeySet<String>()
        private val paused = ConcurrentHashMap.newKeySet<String>()

        @Volatile
        private var running = false

        fun enqueue(ctx: Context, item: TransferStore.Item) {
            TransferStore.put(ctx, item)
            cancelled.remove(item.id)
            paused.remove(item.id)
            queue.add(item.id)
            kick(ctx)
        }

        fun resume(ctx: Context, id: String) {
            val item = TransferStore.get(ctx, id) ?: return
            if (item.state != TransferStore.ST_PAUSED && item.state != TransferStore.ST_FAILED) return
            cancelled.remove(id)
            paused.remove(id)
            TransferStore.put(ctx, item.copy(state = TransferStore.ST_QUEUED, error = ""))
            queue.add(id)
            kick(ctx)
        }

        fun pause(ctx: Context, id: String) {
            paused.add(id)
            val item = TransferStore.get(ctx, id) ?: return
            if (item.state == TransferStore.ST_QUEUED) {
                queue.remove(id)
                TransferStore.put(ctx, item.copy(state = TransferStore.ST_PAUSED))
            }
        }

        fun cancel(ctx: Context, id: String) {
            cancelled.add(id)
            queue.remove(id)
            val item = TransferStore.get(ctx, id) ?: return
            if (item.state != TransferStore.ST_DONE) {
                TransferStore.put(ctx, item.copy(state = TransferStore.ST_CANCELLED, error = ""))
            }
        }

        fun remove(ctx: Context, id: String) {
            cancel(ctx, id)
            paused.remove(id)
            TransferStore.remove(ctx, id)
        }

        private fun kick(ctx: Context) {
            val intent = Intent(ctx, TransferService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
            else ctx.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "下载", NotificationManager.IMPORTANCE_LOW),
            )
            startForeground(NOTIF_ID, notify("下载服务运行中", 0, true))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            scope.launch { runQueue() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runQueue() {
        while (true) {
            val id = queue.poll() ?: break
            val item = TransferStore.get(applicationContext, id) ?: continue
            if (item.state != TransferStore.ST_QUEUED) continue
            if (cancelled.contains(id)) continue
            runCatching { downloadOne(item) }
            cancelled.remove(id)
            paused.remove(id)
        }
        running = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private suspend fun downloadOne(item: TransferStore.Item) {
        val id = item.id
        val treeUri = item.treeUri.ifBlank { UiPrefs.downloadTreeUri(this) }
        val fileName = safeName(item.fileName.ifBlank { "下载" })
        val mime = item.mime.ifBlank { guessMime(fileName) }

        TransferStore.put(this, item.copy(state = TransferStore.ST_RUNNING, error = "", done = 0, total = item.total))
        updateNotify(item.title, 0, true)

        // 私有目录下载支持断点续传;SAF 目标每次从头写(系统文档接口不支持在已有文件里定位)。
        var part: File? = null
        var startAt = 0L
        val reqB = Request.Builder().url(item.url)
        if (treeUri.isBlank()) {
            val dir = privateDir()
            part = File(dir, fileName + ".part")
            if (part.exists()) startAt = part.length()
            if (startAt > 0) reqB.header("Range", "bytes=$startAt-")
        }

        var outputUri: Uri? = null
        var out: OutputStream? = null
        try {
            http.newCall(reqB.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val text = resp.body?.string().orEmpty()
                    throw IOException(httpError(resp.code, text))
                }
                val body = resp.body ?: throw IOException("服务器返回空响应")
                val contentLength = body.contentLength()
                val total = if (contentLength > 0) startAt + contentLength else item.total
                val from = if (resp.code == 206) startAt else 0L
                val expected = if (contentLength > 0) contentLength else -1L
                if (resp.code != 206 && startAt > 0) startAt = 0

                val opened: OutputStream
                if (treeUri.isNotBlank()) {
                    val uri = createDocument(treeUri, mime, fileName)
                        ?: throw IOException("无法在所选文件夹里创建文件(权限可能已失效,请重新选择下载目录)")
                    outputUri = uri
                    opened = contentResolver.openOutputStream(uri)
                        ?: throw IOException("无法写入所选文件夹")
                } else {
                    opened = FileOutputStream(part!!, startAt > 0)
                }
                out = opened

                var received = 0L
                var lastUi = 0L
                val buf = ByteArray(128 * 1024)
                val input: InputStream = body.byteStream()
                while (true) {
                    if (cancelled.contains(id)) throw Cancelled()
                    if (paused.contains(id)) throw Interrupted()
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    opened.write(buf, 0, n)
                    received += n
                    val now = System.currentTimeMillis()
                    if (now - lastUi >= 400) {
                        lastUi = now
                        val doneNow = from + received
                        TransferStore.put(this, TransferStore.get(this, id)?.copy(
                            state = TransferStore.ST_RUNNING, done = doneNow,
                            total = if (total > 0) total else 0,
                        ) ?: item)
                        updateNotify(item.title, percent(doneNow, total), total <= 0)
                    }
                }
                if (expected > 0 && received != expected) throw IOException("下载不完整($received/$expected 字节),可重试")
                opened.flush()
                runCatching { (opened as? FileOutputStream)?.fd?.sync() }
                out = null
                runCatching { opened.close() }

                val finalUri: String
                if (treeUri.isBlank()) {
                    val target = File(privateDir(), fileName)
                    if (target.exists()) target.delete()
                    if (!part!!.renameTo(target)) throw IOException("无法保存下载文件")
                    finalUri = FileProvider.getUriForFile(
                        this, "$packageName.fileprovider", target,
                    ).toString()
                } else {
                    finalUri = outputUri.toString()
                }
                val finalSize = if (total > 0) total else received + from
                TransferStore.put(this, TransferStore.Item(
                    id = id, title = item.title, url = item.url, fileName = fileName, kind = item.kind,
                    treeUri = item.treeUri, total = finalSize, done = finalSize,
                    state = TransferStore.ST_DONE, localUri = finalUri, mime = mime,
                    createdAt = item.createdAt,
                ))
                updateNotify("已下载 ${item.title}", 100, false)
            }
        } catch (e: Cancelled) {
            out?.let { runCatching { it.close() } }
            part?.delete()
            outputUri?.let { runCatching { DocumentsContract.deleteDocument(contentResolver, it) } }
            // 状态已由 cancel() 置为 cancelled,这里不再覆盖
        } catch (e: Interrupted) {
            out?.let { runCatching { it.close() } }
            val saved = if (treeUri.isBlank()) (part?.length() ?: 0L) else 0L
            if (treeUri.isNotBlank()) {
                outputUri?.let { runCatching { DocumentsContract.deleteDocument(contentResolver, it) } }
            }
            TransferStore.get(this, id)?.let {
                TransferStore.put(this, it.copy(state = TransferStore.ST_PAUSED, done = saved))
            }
            updateNotify("已暂停 ${item.title}", 0, false)
        } catch (e: Exception) {
            out?.let { runCatching { it.close() } }
            if (treeUri.isNotBlank()) {
                outputUri?.let { runCatching { DocumentsContract.deleteDocument(contentResolver, it) } }
            }
            val msg = e.message ?: e.javaClass.simpleName
            val saved = if (treeUri.isBlank()) (part?.length() ?: 0L) else 0L
            TransferStore.get(this, id)?.let {
                if (it.state != TransferStore.ST_CANCELLED) {
                    TransferStore.put(this, it.copy(
                        state = TransferStore.ST_FAILED, error = msg, done = saved,
                    ))
                }
            }
            updateNotify("下载失败 ${item.title}:$msg", 0, false)
        }
    }

    private class Cancelled : IOException("已取消")
    private class Interrupted : IOException("已暂停")

    private fun percent(done: Long, total: Long): Int =
        if (total > 0) ((done.toDouble() / total) * 100).toInt().coerceIn(0, 100) else 0

    private fun safeName(name: String): String =
        name.replace('/', '_').replace('\\', '_').replace(":", "_").ifBlank { "下载" }.take(180)

    private fun privateDir(): File {
        val base = getExternalFilesDir(null) ?: filesDir
        return File(base, "Download/linNas").apply {
            if (!exists() && !mkdirs()) throw IOException("无法创建下载目录")
        }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isBlank()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun httpError(code: Int, body: String): String {
        val serverMsg = try {
            JSONObject(body).optString("error")
        } catch (_: Exception) {
            ""
        }
        return when {
            serverMsg.isNotBlank() -> serverMsg
            code == 401 -> "登录已失效,请重新登录"
            code == 403 -> "没有权限下载该内容"
            code == 404 -> "文件不存在(可能已被移动或删除)"
            else -> "HTTP $code"
        }
    }

    /** 在 SAF 树目录里创建文件;返回新文档 Uri。 */
    private fun createDocument(treeUri: String, mime: String, fileName: String): Uri? = try {
        val tree = Uri.parse(treeUri)
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        DocumentsContract.createDocument(contentResolver, parent, mime, fileName)
    } catch (_: Exception) {
        null
    }

    // ---- 通知 ----

    private fun notify(title: String, pct: Int, indeterminate: Boolean): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setOngoing(indeterminate || pct < 100)
            .setOnlyAlertOnce(true)
        if (indeterminate) b.setProgress(0, 0, true) else b.setProgress(100, pct, false)
        return b.build()
    }

    private fun updateNotify(title: String, pct: Int, indeterminate: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        runCatching { nm.notify(NOTIF_ID, notify(title, pct, indeterminate)) }
    }
}
