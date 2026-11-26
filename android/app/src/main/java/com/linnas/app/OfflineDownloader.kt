package com.linnas.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import com.linnas.app.SpeedMeter
import com.linnas.app.UiPrefs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 离线下载调度:使用唯一 WorkManager 任务避免重复缓存同一文件。 */
object OfflineDownloader {
    private val scheduleLock = Any()
    private fun workName(vpath: String) = "offline-${OfflineStore.stableId(vpath)}"

    suspend fun start(ctx: Context, vpath: String, name: String, size: Long): Boolean =
        withContext(Dispatchers.IO) { schedule(ctx, vpath, name, size, force = false) }

    private fun schedule(ctx: Context, vpath: String, name: String, size: Long, force: Boolean): Boolean {
        return synchronized(scheduleLock) {
            val app = ctx.applicationContext
            val previous = OfflineStore.get(app, vpath)
            if (previous?.status == OfflineStore.ST_DONE && OfflineStore.localFile(app, vpath) != null) {
                return@synchronized true
            }
            if (!force && previous?.status == OfflineStore.ST_DOWNLOADING && previous.taskId.isNotBlank()) {
                return@synchronized true
            }
            val totalSize = size.takeIf { it > 0 } ?: (previous?.size ?: 0L)
            val taskId = UUID.randomUUID().toString()
            val item = OfflineStore.Item(
                vpath, name, totalSize, previous?.bytes ?: 0,
                OfflineStore.ST_DOWNLOADING, taskId = taskId,
            )
            if (!OfflineStore.putDurable(app, item)) {
                OfflineStore.put(app, item.copy(status = OfflineStore.ST_FAILED, msg = "无法保存下载任务"))
                return@synchronized false
            }
            val request = OneTimeWorkRequestBuilder<OfflineDownloadWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(OfflineDownloadWorker.KEY_VPATH, vpath)
                        .putString(OfflineDownloadWorker.KEY_NAME, name)
                        .putLong(OfflineDownloadWorker.KEY_SIZE, totalSize)
                        .putString(OfflineDownloadWorker.KEY_TASK_ID, taskId)
                        .build(),
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            try {
                WorkManager.getInstance(app).enqueueUniqueWork(
                    workName(vpath),
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request,
                )
            } catch (e: IllegalStateException) {
                markScheduleFailure(app, item, taskId, e)
                return@synchronized false
            } catch (e: IllegalArgumentException) {
                markScheduleFailure(app, item, taskId, e)
                return@synchronized false
            } catch (e: SecurityException) {
                markScheduleFailure(app, item, taskId, e)
                return@synchronized false
            }
            true
        }
    }

    private fun markScheduleFailure(ctx: Context, item: OfflineStore.Item, taskId: String, error: Exception) {
        OfflineStore.updateIfStatus(
            ctx,
            item.copy(status = OfflineStore.ST_FAILED, msg = error.message ?: "下载任务无法启动"),
            OfflineStore.ST_DOWNLOADING,
            taskId,
        )
    }

    suspend fun recoverInterrupted(ctx: Context) = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        for (item in OfflineStore.all(app)) {
            if (item.status != OfflineStore.ST_DOWNLOADING) continue
            if (item.taskId.isBlank()) {
                OfflineStore.updateIfStatus(
                    app,
                    item.copy(status = OfflineStore.ST_FAILED, msg = "下载任务已中断，请重试"),
                    OfflineStore.ST_DOWNLOADING,
                    "",
                )
                continue
            }
            val workInfos = WorkManager.getInstance(app)
                .getWorkInfosForUniqueWork(workName(item.vpath))
                .get(5, TimeUnit.SECONDS)
            if (workInfos.isEmpty()) {
                schedule(app, item.vpath, item.name, item.size, force = true)
            } else if (workInfos.all { it.state.isFinished }) {
                OfflineStore.updateIfStatus(
                    app,
                    item.copy(status = OfflineStore.ST_FAILED, msg = "下载任务已中断，请重试"),
                    OfflineStore.ST_DOWNLOADING,
                    item.taskId,
                )
            }
        }
    }

    fun pause(ctx: Context, vpath: String) {
        val app = ctx.applicationContext
        synchronized(scheduleLock) {
            OfflineStore.get(app, vpath)
                ?.takeIf { it.status == OfflineStore.ST_DOWNLOADING }
                ?.let {
                    OfflineStore.put(app, it.copy(status = OfflineStore.ST_PAUSED, msg = "已暂停"))
                }
            WorkManager.getInstance(app).cancelUniqueWork(workName(vpath))
        }
    }

    fun cancel(ctx: Context, vpath: String) {
        val app = ctx.applicationContext
        synchronized(scheduleLock) {
            WorkManager.getInstance(app).cancelUniqueWork(workName(vpath))
            OfflineStore.remove(app, vpath)
        }
    }

    suspend fun resume(ctx: Context, vpath: String, name: String, size: Long): Boolean =
        withContext(Dispatchers.IO) {
            val item = OfflineStore.get(ctx, vpath)
            schedule(ctx, vpath, name, if (size > 0) size else (item?.size ?: 0L), force = false)
        }
}

class OfflineDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    companion object {
        const val KEY_VPATH = "vpath"
        const val KEY_NAME = "name"
        const val KEY_SIZE = "size"
        const val KEY_TASK_ID = "taskId"

        private const val CHANNEL_ID = "offline_dl"
        private const val NOTIFICATION_ID_BASE = 2000
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** v1.8.1 每个任务的速度计量器。 */
    private val speedMeters = java.util.concurrent.ConcurrentHashMap<String, SpeedMeter>()

    override suspend fun doWork(): Result {
        val vpath = inputData.getString(KEY_VPATH)?.takeIf { it.isNotBlank() }
            ?: return Result.failure()
        val name = inputData.getString(KEY_NAME)?.takeIf { it.isNotBlank() }
            ?: vpath.substringAfterLast('/')
        val taskId = inputData.getString(KEY_TASK_ID)?.takeIf { it.isNotBlank() }
            ?: return Result.failure()
        val declaredSize = inputData.getLong(KEY_SIZE, 0).coerceAtLeast(0)
        val item = OfflineStore.get(applicationContext, vpath) ?: return Result.failure()
        if (item.taskId != taskId || item.status != OfflineStore.ST_DOWNLOADING) return Result.failure()
        val part = try {
            OfflineStore.partFor(applicationContext, vpath)
        } catch (e: IllegalStateException) {
            markFailed(item, 0, e, taskId)
            return Result.failure()
        } catch (e: SecurityException) {
            markFailed(item, 0, e, taskId)
            return Result.failure()
        }

        var wasCancelled = false
        return try {
            setForeground(foregroundInfo(name, 0))
            download(vpath, name, declaredSize, item, part, taskId)
            Result.success()
        } catch (e: CancellationException) {
            wasCancelled = true
            throw e
        } catch (e: IOException) {
            markFailed(item, part.length(), e, taskId)
            Result.failure()
        } catch (e: IllegalArgumentException) {
            markFailed(item, part.length(), e, taskId)
            Result.failure()
        } catch (e: IllegalStateException) {
            markFailed(item, part.length(), e, taskId)
            Result.failure()
        } catch (e: SecurityException) {
            markFailed(item, part.length(), e, taskId)
            Result.failure()
        } finally {
            if (!wasCancelled) {
                val current = OfflineStore.get(applicationContext, vpath)
                if (current == null) {
                    part.delete()
                } else if (current.status == OfflineStore.ST_DOWNLOADING && current.taskId == taskId) {
                    markFailed(current, part.length(), IOException("下载任务已中断"), taskId)
                }
            }
        }
    }

    private fun markFailed(item: OfflineStore.Item, bytes: Long, error: Exception, taskId: String) {
        OfflineStore.updateIfStatus(
            applicationContext,
            item.copy(
                bytes = bytes.coerceAtLeast(0),
                status = OfflineStore.ST_FAILED,
                msg = error.message ?: "下载失败",
            ),
            OfflineStore.ST_DOWNLOADING,
            taskId,
        )
    }

    private suspend fun download(
        vpath: String,
        name: String,
        declaredSize: Long,
        oldItem: OfflineStore.Item,
        part: File,
        taskId: String,
    ) {
        val parent = part.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("无法创建离线缓存目录")
        }
        var existing = if (part.exists()) part.length() else 0L
        if (declaredSize > 0 && existing >= declaredSize) {
            if (!part.delete() && part.exists()) throw IOException("无法重置不完整的缓存文件")
            existing = 0
        }
        if (declaredSize > 0) {
            val availableBytes = StatFs(parent?.absolutePath ?: applicationContext.filesDir.absolutePath).availableBytes
            if (availableBytes < declaredSize - existing) throw IOException("手机存储空间不足")
        }

        val request = Request.Builder()
            .url(LinNasClient().rawUrl(vpath, true))
            .apply {
                if (existing > 0) header("Range", "bytes=$existing-")
            }
            .build()
        val call = http.newCall(request)
        call.awaitResponse().use { response ->
                val body = response.body ?: throw IOException("服务器返回空响应")
                if (response.code == 401) throw IOException("登录已失效，请重新登录后重试")
                val plan = planDownloadResponse(
                    statusCode = response.code,
                    contentRangeHeader = response.header("Content-Range"),
                    contentLength = body.contentLength(),
                    existingBytes = existing,
                    declaredSize = declaredSize,
                )
                val total = plan.totalBytes ?: declaredSize
                OfflineStore.updateIfStatus(
                    applicationContext,
                    oldItem.copy(
                        size = total,
                        bytes = plan.offset,
                        status = OfflineStore.ST_DOWNLOADING,
                        msg = "",
                    ),
                    OfflineStore.ST_DOWNLOADING,
                    taskId,
                )

                var received = 0L
                var lastUpdate = 0L
                FileOutputStream(part, plan.append).use { output ->
                    val stream = body.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        if (plan.expectedBytes != null && received + count > plan.expectedBytes) {
                            throw IOException("服务器发送的数据超过声明范围")
                        }
                        output.write(buffer, 0, count)
                        received += count
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 400) {
                            lastUpdate = now
                            publishProgress(vpath, name, plan.offset + received, total, taskId)
                        }
                    }
                    output.fd.sync()
                }

                if (plan.expectedBytes != null && received != plan.expectedBytes) {
                    throw IOException("下载数据不完整，可继续重试")
                }
                if (!plan.isFinalRange) {
                    throw IOException("服务器仅返回部分内容，可继续重试")
                }
                val finalSize = part.length()
                if (total > 0 && finalSize != total) {
                    throw IOException("文件大小校验失败: 预期 $total 字节,实际 $finalSize 字节")
                }
                coroutineContext.ensureActive()
                val target = OfflineStore.fileFor(applicationContext, vpath)
                try {
                    android.system.Os.rename(part.absolutePath, target.absolutePath)
                } catch (e: android.system.ErrnoException) {
                    throw IOException("无法安全完成离线缓存: ${e.message}", e)
                }
                // v1.8.1:缓存等同下载 —— 若用户选了下载目录,把成品同步一份过去(可在「文件」里找到)
                val savedUri = copyToDownloadTree(target, name)
                val saved = OfflineStore.updateIfStatus(
                    applicationContext,
                    OfflineStore.Item(
                        vpath = vpath,
                        name = name,
                        size = finalSize,
                        bytes = finalSize,
                        status = OfflineStore.ST_DONE,
                        speedBps = 0,
                        savedUri = savedUri,
                    ),
                    OfflineStore.ST_DOWNLOADING,
                    taskId,
                )
                if (!saved) {
                    target.delete()
                    throw CancellationException("下载任务已取消")
                }
                updateNotification(name, 100, ongoing = false)
        }
    }

    /**
     * 把缓存成品复制到用户设置的下载目录(SAF),返回 content:// 地址;失败返回空串。
     * 仍保留应用内副本用于离线播放。
     */
    private fun copyToDownloadTree(src: File, name: String): String {
        val tree = UiPrefs.downloadTreeUri(applicationContext)
        if (tree.isBlank()) return ""
        return try {
            val treeUri = android.net.Uri.parse(tree)
            val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                treeUri, android.provider.DocumentsContract.getTreeDocumentId(treeUri),
            )
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
            val doc = android.provider.DocumentsContract.createDocument(
                applicationContext.contentResolver, parent, mime, name,
            ) ?: return ""
            applicationContext.contentResolver.openOutputStream(doc)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: return ""
            doc.toString()
        } catch (_: Exception) {
            ""
        }
    }

    private fun publishProgress(vpath: String, name: String, bytes: Long, total: Long, taskId: String) {
        val current = OfflineStore.get(applicationContext, vpath) ?: return
        if (current.status != OfflineStore.ST_DOWNLOADING) return
        val now = System.currentTimeMillis()
        val bps = speedMeters.getOrPut(vpath) { SpeedMeter() }.update(now, bytes)
        OfflineStore.updateIfStatus(
            applicationContext,
            current.copy(size = total, bytes = bytes, msg = "", speedBps = bps),
            OfflineStore.ST_DOWNLOADING,
            taskId,
        )
        val percent = if (total > 0) ((bytes.toDouble() / total) * 100).toInt().coerceIn(0, 100) else 0
        updateNotification(name, percent, ongoing = true)
    }

    private fun foregroundInfo(name: String, percent: Int): ForegroundInfo {
        ensureNotificationChannel()
        val notification = buildNotification(name, percent, ongoing = true)
        val id = notificationId()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    private fun updateNotification(name: String, percent: Int, ongoing: Boolean) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        ensureNotificationChannel()
        manager.notify(notificationId(), buildNotification(name, percent, ongoing))
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "离线缓存", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(name: String, percent: Int, ongoing: Boolean) =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (ongoing) "正在缓存: $name" else "缓存完成: $name")
            .setProgress(100, percent, false)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .build()

    private fun notificationId(): Int =
        NOTIFICATION_ID_BASE + (inputData.getString(KEY_VPATH).orEmpty().hashCode() and 0x3fffffff)

    private suspend fun Call.awaitResponse(): Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) {
                        continuation.resume(response)
                    } else {
                        response.close()
                    }
                }
            })
        }
}
