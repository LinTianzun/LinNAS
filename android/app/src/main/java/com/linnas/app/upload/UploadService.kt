package com.linnas.app.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.linnas.app.LinNasClient
import com.linnas.app.Prefs
import kotlinx.coroutines.*

/**
 * 前台服务:串行上传队列,支持单任务暂停 / 继续 / 取消 / 重试。
 * 通过 startUpload(context, uri, displayName, size, vpathDir) 入队。
 */
class UploadService : Service() {

    companion object {
        const val CHANNEL_ID = "upload"
        const val NOTIF_ID = 100

        const val ST_QUEUED = "排队中"
        const val ST_RUNNING = "上传中"
        const val ST_DONE = "完成"
        const val ST_PAUSED = "已暂停"
        const val ST_CANCELLED = "已取消"

        private val queue = java.util.concurrent.ConcurrentLinkedQueue<Task>()
        val progress = java.util.concurrent.ConcurrentHashMap<String, Task>()

        class Task(
            val uri: Uri,
            val name: String,
            val size: Long,
            val dir: String,
        ) {
            @Volatile var sent: Long = 0
            @Volatile var state: String = ST_QUEUED
            @Volatile var ctrl: LinNasClient.UploadControl? = null
        }

        fun startUpload(ctx: Context, uri: Uri, name: String, size: Long, dir: String) {
            val task = Task(uri, name, size, dir)
            queue.add(task)
            progress[name] = task
            kick(ctx)
        }

        /** 暂停:进行中的在当前分块后停下(保留服务端 .part,可续传);排队中的直接移出队列。 */
        fun pause(ctx: Context, name: String) {
            val t = progress[name] ?: return
            if (t.state == ST_DONE || t.state == ST_CANCELLED || t.state == ST_PAUSED) return
            val wasQueued = queue.remove(t)
            t.state = ST_PAUSED
            if (!wasQueued) t.ctrl?.paused = true
        }

        /** 继续 / 重试:重新入队;upload() 按服务端已收字节续传。 */
        fun resume(ctx: Context, name: String) {
            val t = progress[name] ?: return
            if (t.state != ST_PAUSED && !t.state.startsWith("失败")) return
            t.ctrl?.paused = false
            t.ctrl = null
            t.state = ST_QUEUED
            queue.add(t)
            kick(ctx)
        }

        /** 取消:中断当前分块并删除服务端未完成分片。 */
        fun cancel(ctx: Context, name: String) {
            val t = progress[name] ?: return
            if (t.state == ST_DONE || t.state == ST_CANCELLED) return
            queue.remove(t)
            t.state = ST_CANCELLED
            t.ctrl?.cancelled = true
        }

        /** 清掉已完成/已取消的记录。 */
        fun clearFinished() {
            progress.entries.removeAll { it.value.state == ST_DONE || it.value.state == ST_CANCELLED }
            queue.removeAll { it.state == ST_DONE || it.state == ST_CANCELLED }
        }

        private fun kick(ctx: Context) {
            val intent = Intent(ctx, UploadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "上传", NotificationManager.IMPORTANCE_LOW))
            startForeground(NOTIF_ID, buildNotification("上传服务运行中", 0, 0))
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
        val client = LinNasClient()
        while (true) {
            val task = queue.poll() ?: break
            if (task.state == ST_CANCELLED) continue
            task.state = ST_RUNNING
            val ctrl = LinNasClient.UploadControl()
            task.ctrl = ctrl
            updateNotification(task)
            try {
                val factory = {
                    contentResolver.openInputStream(task.uri)
                        ?: throw java.io.IOException("无法打开文件")
                }
                client.upload(factory, task.size, task.name, task.dir, { sent ->
                    task.sent = sent
                    updateNotification(task)
                }, ctrl)
                task.sent = task.size
                task.state = ST_DONE
            } catch (e: LinNasClient.UploadInterrupted) {
                if (e.cancelled) {
                    ctrl.uploadId?.let { id -> runCatching { client.abortUpload(id) } }
                    task.state = ST_CANCELLED
                } else {
                    task.state = ST_PAUSED
                }
            } catch (e: Exception) {
                if (task.state == ST_CANCELLED) {
                    ctrl.uploadId?.let { id -> runCatching { client.abortUpload(id) } }
                } else {
                    task.state = "失败: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            task.ctrl = null
            updateNotification(task)
        }
        running = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun buildNotification(title: String, progressPct: Int, size: Long): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setProgress(100, progressPct, size == 0L)
            .setOngoing(true)
            .build()

    private fun updateNotification(task: Task) {
        val nm = getSystemService(NotificationManager::class.java)
        val pct = if (task.size > 0) ((task.sent * 100) / task.size).toInt() else 0
        nm.notify(NOTIF_ID, buildNotification(
            when {
                task.state == ST_DONE -> "已上传 ${task.name}"
                task.state.startsWith("失败") -> task.state
                task.state == ST_PAUSED -> "已暂停 ${task.name}"
                task.state == ST_CANCELLED -> "已取消 ${task.name}"
                else -> "上传中 ${task.name}"
            }, pct, task.size))
    }
}
