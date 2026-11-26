package com.linnas.app

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 操作日志页(P2):列出服务器 /api/audit 最近 200 条操作记录。
 * 仅管理员可见(服务端 authorize 对非管理员返回 403,这里入口也按角色隐藏)。
 *
 * v1.8:行改用统一的圆角卡片行(ScreenUi.row),空态用 bg_empty 插图。
 */
class AuditActivity : AppCompatActivity() {

    private val client = LinNasClient()
    private lateinit var list: LinearLayout
    private lateinit var hint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeStore.styleRes(this))
        super.onCreate(savedInstanceState)
        Prefs.init(applicationContext)
        setContentView(R.layout.activity_audit)

        hint = findViewById(R.id.auditHint)
        list = findViewById(R.id.auditList)
        findViewById<Button>(R.id.btnAuditBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnAuditRefresh).setOnClickListener { loadList() }
        loadList()
    }

    private fun loadList() {
        hint.text = "加载中…"
        list.removeAllViews()
        lifecycleScope.launch {
            val entries = try {
                withContext(Dispatchers.IO) { client.audit(200) }
            } catch (e: Exception) {
                toast("读取日志失败:${e.message}")
                hint.text = "服务器记录的操作(最近 200 条)"
                list.removeAllViews()
                list.addView(
                    ScreenUi.emptyState(this@AuditActivity, "读取失败", e.message ?: "无法获取操作日志"),
                )
                return@launch
            }
            list.removeAllViews()
            if (entries.isEmpty()) {
                hint.text = "服务器记录的操作(最近 200 条)"
                list.addView(
                    ScreenUi.emptyState(this@AuditActivity, "暂无日志记录", "服务器上的操作会显示在这里"),
                )
                return@launch
            }
            hint.text = "共 ${entries.size} 条 · 点击查看详情"
            for (e in entries) {
                val ok = e.status < 400
                list.addView(
                    ScreenUi.row(
                        this@AuditActivity,
                        R.drawable.ic_history,
                        e.action.ifBlank { e.method },
                        buildMeta(e),
                        value = e.status.toString(),
                        tintAttr = if (ok) R.attr.lSuccess else R.attr.lDanger,
                        onClick = { showDetail(e) },
                    ),
                )
            }
        }
    }

    private fun buildMeta(e: AuditEntry): String {
        val sb = StringBuilder()
        if (e.time > 0) sb.append(FileListAdapter.fmtDate(e.time))
        if (e.user.isNotBlank()) {
            sb.append(if (sb.isEmpty()) "" else "  ·  ").append(e.user)
        }
        sb.append("  ·  ").append(e.method)
        return sb.toString()
    }

    private fun showDetail(e: AuditEntry) {
        // 多行详情改为逐条 keyValue,避免拼一长串字符串
        val sheet = Sheet(this, e.action.ifBlank { e.method })
        sheet.keyValue("时间", if (e.time > 0) FileListAdapter.fmtDate(e.time) else "—")
        sheet.keyValue("用户", e.user.ifBlank { "—" })
        sheet.keyValue("IP", e.ip.ifBlank { "—" })
        sheet.keyValue("动作", e.action.ifBlank { "—" })
        sheet.keyValue("请求", "${e.method} ${e.path}")
        if (e.target.isNotBlank()) sheet.keyValue("对象", e.target)
        sheet.keyValue("状态", e.status.toString())
        sheet.primary("关闭") { sheet.dismiss() }
        sheet.show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
