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
        addLoading()
        lifecycleScope.launch {
            val info = try {
                withContext(Dispatchers.IO) { client.settingsInfo() }
            } catch (e: Exception) {
                clearContent()
                addEmptyState("读取失败", e.message ?: "无法获取维护设置")
                return@launch
            }
            settings = info
            val idx = try {
                withContext(Dispatchers.IO) { client.indexStatus() }
            } catch (_: Exception) {
                null
            }
            clearContent()

            // ---- 索引状态 ----
            addSectionHeader("索引状态")
            addCard { card ->
                card.addView(
                    ScreenUi.cardHeader(
                        this@MaintenanceActivity,
                        R.drawable.ic_index,
                        "媒体索引",
                        if (idx == null) "未启用或不可用" else "扫描与统计",
                    ),
                )
                if (idx == null) {
                    card.addView(
                        ScreenUi.text(this@MaintenanceActivity, "索引未启用或不可用", R.dimen.text_sm, R.attr.lTextDim).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            )
                        },
                    )
                } else {
                    val stats = idx.optJSONObject("stats") ?: idx
                    val scanning = idx.optBoolean("scanning")
                    addKeyValue(card, "状态", if (scanning) "扫描中…" else "空闲")
                    addKeyValue(card, "已索引文件", idx.optInt("fileCount").toString())
                    val last = idx.optLong("lastScan")
                    addKeyValue(card, "上次扫描", if (last > 0) formatTs(last) else "无")
                    addKeyValue(
                        card,
                        "媒体分类",
                        "视频 ${stats.optInt("video")}  ·  图片 ${stats.optInt("image")}  ·  音乐 ${stats.optInt("audio")}",
                    )
                    val note = idx.optString("note")
                    if (note.isNotBlank()) {
                        card.addView(
                            ScreenUi.text(this@MaintenanceActivity, note, R.dimen.text_caption, R.attr.lTextDim).apply {
                                setPadding(0, dp(8), 0, 0)
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                )
                            },
                        )
                    }
                }
            }

            // ---- 清理 ----
            addSectionHeader("清理")
            addCard { card ->
                card.addView(
                    ScreenUi.cardHeader(
                        this@MaintenanceActivity,
                        R.drawable.ic_sweep,
                        "回收站与转码缓存",
                        "回收站 ${info.optInt("trashRetentionDays")} 天 · 转码缓存上限 ${info.optInt("transcodeCacheMB")} MB",
                    ),
                )
                card.addView(
                    ScreenUi.tonalButton(this@MaintenanceActivity, "立即清理(回收站 + 转码缓存)") { doCleanup() }.apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(44),
                        ).apply { topMargin = dp(12) }
                    },
                )
            }

            // ---- 设置 ----
            addSectionHeader("设置")
            addCard { card ->
                card.addView(
                    ScreenUi.cardHeader(
                        this@MaintenanceActivity,
                        R.drawable.ic_gear,
                        "保留期与缓存上限",
                        "修改后立即生效",
                    ),
                )
                card.addView(
                    ScreenUi.tonalButton(this@MaintenanceActivity, "修改保留期 / 缓存设置") { showSettingsDialog() }.apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(44),
                        ).apply { topMargin = dp(12) }
                    },
                )
            }

            addSectionHeader("索引")
            addCard { card ->
                card.addView(
                    ScreenUi.cardHeader(
                        this@MaintenanceActivity,
                        R.drawable.ic_index,
                        "媒体索引",
                        "扫描全部媒体库并重建索引",
                    ),
                )
                card.addView(
                    ScreenUi.primaryButton(this@MaintenanceActivity, "重建媒体索引") { doReindex() }.apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(44),
                        ).apply { topMargin = dp(12) }
                    },
                )
            }
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
                            client.settingsUpdate(
                                mapOf("trashRetentionDays" to days, "transcodeCacheMB" to cache),
                            )
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
