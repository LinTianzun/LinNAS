package com.linnas.app.backup

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.linnas.app.LinNasClient
import com.linnas.app.Prefs
import com.linnas.app.ServerConfig
import java.util.concurrent.TimeUnit

/** 相册自动备份(M4)。配置存 SharedPreferences。 */
object BackupConfig {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("linnas_backup", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = sp(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("enabled", v).apply()

    fun targetDir(ctx: Context) = sp(ctx).getString("dir", "/lib1/手机备份") ?: "/lib1/手机备份"
    fun setTargetDir(ctx: Context, v: String) = sp(ctx).edit().putString("dir", v).apply()

    fun wifiOnly(ctx: Context) = sp(ctx).getBoolean("wifi_only", true)
    fun setWifiOnly(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("wifi_only", v).apply()

    /** 上次成功备份的媒体 DATE_ADDED(秒)。 */
    fun lastTs(ctx: Context) = sp(ctx).getLong("last_ts", 0L)
    fun setLastTs(ctx: Context, v: Long) = sp(ctx).edit().putLong("last_ts", v).apply()

    fun lastResult(ctx: Context) = sp(ctx).getString("last_result", "尚未备份") ?: "尚未备份"
    fun setLastResult(ctx: Context, v: String) = sp(ctx).edit().putString("last_result", v).apply()

    fun uploadedCount(ctx: Context) = sp(ctx).getInt("count", 0)
    fun addUploaded(ctx: Context, n: Int) =
        sp(ctx).edit().putInt("count", uploadedCount(ctx) + n).apply()
}

/** 调度器。 */
object PhotoBackupScheduler {
    private const val WORK = "photo_backup_periodic"

    private fun constraints(ctx: Context) = Constraints.Builder()
        .setRequiredNetworkType(
            if (BackupConfig.wifiOnly(ctx)) NetworkType.UNMETERED else NetworkType.CONNECTED
        )
        .build()

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<PhotoBackupWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints(ctx))
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
    }

    fun runNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<PhotoBackupWorker>()
            .setConstraints(constraints(ctx))
            .build()
        WorkManager.getInstance(ctx).enqueue(req)
    }
}

/** 扫描 MediaStore 中新增的照片/视频并上传。 */
class PhotoBackupWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    private data class MediaItem(
        val uri: Uri,
        val displayName: String,
        val size: Long,
        val dateAdded: Long,
    )

    override fun doWork(): Result {
        val ctx = applicationContext
        Prefs.init(ctx)
        if (!BackupConfig.enabled(ctx)) return Result.success()
        if (ServerConfig.baseUrl.isBlank()) {
            BackupConfig.setLastResult(ctx, "未连接服务器")
            return Result.success()
        }

        val client = LinNasClient()
        val dir = BackupConfig.targetDir(ctx)
        // 确保目标目录存在(已存在会报错,忽略)
        try { client.mkdir(dir) } catch (_: Exception) { }

        val limit = 100
        var lastTs = BackupConfig.lastTs(ctx)
        val items = queryNewMedia(ctx, lastTs, limit)
        if (items.isEmpty()) {
            BackupConfig.setLastResult(ctx, "无新增照片/视频")
            return Result.success()
        }

        var uploaded = 0
        var failed = 0
        for (item in items) {
            try {
                // 有些 provider 的 SIZE 为空/0,用文件描述符补一个准确大小
                var size = item.size
                if (size <= 0) {
                    size = ctx.contentResolver.openAssetFileDescriptor(item.uri, "r")
                        ?.use { it.length } ?: -1L
                }
                if (size <= 0) {
                    failed++
                    lastTs = maxOf(lastTs, item.dateAdded)
                    BackupConfig.setLastTs(ctx, lastTs)
                    continue
                }
                val factory = {
                    ctx.contentResolver.openInputStream(item.uri)
                        ?: throw java.io.IOException("无法读取 ${item.displayName}")
                }
                client.upload(factory, size, item.displayName, dir)
                uploaded++
                lastTs = maxOf(lastTs, item.dateAdded)
                BackupConfig.setLastTs(ctx, lastTs) // 逐条前移,避免失败重传
            } catch (e: Exception) {
                failed++
                break // 出错就停,下次从 lastTs 续
            }
        }
        if (uploaded > 0) BackupConfig.addUploaded(ctx, uploaded)
        BackupConfig.setLastResult(
            ctx,
            "本次上传 %d 张,失败 %d 张(%s)".format(
                uploaded, failed,
                java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date())
            )
        )
        return if (failed > 0) Result.retry() else Result.success()
    }

    private fun queryNewMedia(ctx: Context, sinceSec: Long, limit: Int): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        val collections = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )
        for (col in collections) {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED,
            )
            val selection = "${MediaStore.MediaColumns.DATE_ADDED} > ?"
            val args = arrayOf(sinceSec.toString())
            val sort = "${MediaStore.MediaColumns.DATE_ADDED} ASC"
            try {
                ctx.contentResolver.query(col, projection, selection, args, sort)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val name = c.getString(1) ?: "media_$id"
                        val size = c.getLong(2)
                        val ts = c.getLong(3)
                        out.add(MediaItem(ContentUris.withAppendedId(col, id), name, size, ts))
                    }
                }
            } catch (_: Exception) { /* 权限缺失等,跳过 */ }
        }
        return out.sortedBy { it.dateAdded }.take(limit)
    }
}
