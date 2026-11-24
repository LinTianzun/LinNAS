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
 * 前台服务:串行上传队列。
 * 通过 startUpload(context, uri, displayName, size, vpathDir) 入队。
 */
class UploadService : Service() {

    companion object {
        const val CHANNEL_ID = "upload"
        const val NOTIF_ID = 100
        private val queue = java.util.concurrent.ConcurrentLinkedQueue<Task>()
        val progress = java.util.concurrent.ConcurrentHashMap<String, Task>()

        data class Task(
            val uri: Uri,
            val name: String,
            val size: Long,
            val dir: String,
            @Volatile var sent: Long = 0,
            @Volatile var state: String = "排队中",
        )

        fun startUpload(ctx: Context, uri: Uri, name: String, size: Long, dir: String) {
            val task = Task(uri, name, size, dir)
            queue.add(task)
            progress[name] = task
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
            task.state = "上传中"
            updateNotification(task)
            try {
                val factory = { contentResolver.openInputStream(task.uri) ?: throw java.io.IOException("无法打开文件") }
                client.upload(factory, task.size, task.name, task.dir) { sent ->
                    task.sent = sent
                    updateNotification(task)
                }
                task.sent = task.size
                task.state = "完成"
            } catch (e: Exception) {
                task.state = "失败: ${e.message ?: e.javaClass.simpleName}"
            }
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
                task.state == "完成" -> "已上传 ${task.name}"
                task.state.startsWith("失败") -> task.state
                else -> "上传中 ${task.name}"
            }, pct, task.size))
    }
}
