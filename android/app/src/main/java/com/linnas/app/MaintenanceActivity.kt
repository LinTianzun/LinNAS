package com.linnas.app

import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** v1.7.0 维护与索引:回收站保留期 / 转码缓存上限设置、立即清理、重建媒体索引。 */
class MaintenanceActivity : ScreenScaffold() {

    private var settings: JSONObject? = null

    override fun title() = "维护与索引"

    override fun onCreateScreen() {
        addHeaderAction("刷新") { load() }
        load()
    }

    private fun load() {
        clearContent()
        addText("加载中…")
        lifecycleScope.launch {
            val info = try {
                withContext(Dispatchers.IO) { client.settingsInfo() }
            } catch (e: Exception) {
                clearContent(); addText("读取失败: ${e.message}"); return@launch
            }
            settings = info
            val idx = try {
                withContext(Dispatchers.IO) { client.indexStatus() }
            } catch (_: Exception) {
                null
            }
            clearContent()
            addText(
                "回收站保留: ${info.optInt("trashRetentionDays")} 天\n" +
                    "转码缓存上限: ${info.optInt("transcodeCacheMB")} MB",
            )
            addText(
                if (idx == null) {
                    "索引: 未启用或不可用"
                } else {
                    val stats = idx.optJSONObject("stats") ?: idx
                    buildString {
                        append("索引状态: ").append(if (idx.optBoolean("scanning")) "扫描中…" else "空闲").append('\n')
                        append("已索引文件: ").append(idx.optInt("fileCount")).append('\n')
                        val last = idx.optLong("lastScan")
                        append("上次扫描: ").append(if (last > 0) formatTs(last) else "无").append('\n')
                        append("视频 ").append(stats.optInt("video")).append(" · 图片 ")
                            .append(stats.optInt("image")).append(" · 音乐 ").append(stats.optInt("audio"))
                        val note = idx.optString("note")
                        if (note.isNotBlank()) append('\n').append(note)
                    }
                },
            )
            addButton("修改保留期 / 缓存设置") { showSettingsDialog() }
            addButton("立即清理(回收站 + 转码缓存)") { doCleanup() }
            addButton("重建媒体索引") { doReindex() }
        }
    }

    private fun showSettingsDialog() {
        val base = settings ?: run { toast("尚未加载"); return }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val etDays = EditText(this).apply {
            hint = "回收站保留天数(0-3650)"
            setText(base.optInt("trashRetentionDays").toString())
            setSingleLine()
        }
        val etCache = EditText(this).apply {
            hint = "转码缓存上限 MB(0-1048576)"
            setText(base.optInt("transcodeCacheMB").toString())
            setSingleLine()
        }
        box.addView(etDays)
        box.addView(etCache)
        AlertDialog.Builder(this).setTitle("维护设置").setView(box)
            .setPositiveButton("保存") { _, _ ->
                val days = etDays.text.toString().trim().toIntOrNull()
                val cache = etCache.text.toString().trim().toIntOrNull()
                if (days == null || cache == null) { toast("请输入数字"); return@setPositiveButton }
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            client.settingsUpdate(mapOf("trashRetentionDays" to days, "transcodeCacheMB" to cache))
                        }
                        toast("已保存")
                        load()
                    } catch (e: Exception) { toast("保存失败: ${e.message}") }
                }
            }
            .setNegativeButton("取消", null).show()
    }

    private fun doCleanup() {
        lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { client.maintenanceCleanup() }
                toast(
                    "清理完成:回收站 ${r.optInt("trashRemoved")} 项,转码缓存 ${r.optInt("transcodeRemoved")} 项," +
                        "释放 ${"%.1f".format(r.optDouble("freedMB"))} MB",
                )
                load()
            } catch (e: Exception) {
                toast("清理失败: ${e.message}")
            }
        }
    }

    private fun doReindex() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { client.reindex() }
                toast("已开始重建索引")
                load()
            } catch (e: Exception) {
                toast("无法重建: ${e.message}")
            }
        }
    }
}
