package com.linnas.app

import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 操作日志页(P2):列出服务器 /api/audit 最近 200 条操作记录。
 * 仅管理员可见(服务端 authorize 对非管理员返回 403,这里入口也按角色隐藏)。
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
        lifecycleScope.launch {
            val entries = try {
                withContext(Dispatchers.IO) { client.audit(200) }
            } catch (e: Exception) {
                toast("读取日志失败:${e.message}")
                hint.text = "服务器记录的操作(最近 200 条)"
                return@launch
            }
            list.removeAllViews()
            if (entries.isEmpty()) {
                hint.text = "暂无日志记录"
                return@launch
            }
            hint.text = "共 ${entries.size} 条 · 点击查看详情"
            for (e in entries) {
                val row = layoutInflater.inflate(R.layout.item_file, list, false)
                val icon = row.findViewById<ImageView>(R.id.ficon)
                val ok = e.status < 400
                icon.setImageResource(if (ok) R.drawable.ic_file else R.drawable.ic_file)
                icon.setColorFilter(getColor(if (ok) R.color.mint else R.color.danger))
                row.findViewById<TextView>(R.id.fname).text = e.action.ifBlank { e.method }
                row.findViewById<TextView>(R.id.fmeta).text = buildMeta(e)
                row.findViewById<ImageView>(R.id.fmore).visibility = android.view.View.GONE
                row.setOnClickListener { showDetail(e) }
                list.addView(row)
            }
        }
    }

    private fun buildMeta(e: AuditEntry): String {
        val sb = StringBuilder()
        if (e.time > 0) sb.append(FileListAdapter.fmtDate(e.time))
        if (e.user.isNotBlank()) {
            sb.append(if (sb.isEmpty()) "" else "  ·  ").append(e.user)
        }
        sb.append("  ·  ").append(e.status)
        return sb.toString()
    }

    private fun showDetail(e: AuditEntry) {
        val sb = StringBuilder()
        sb.append("时间:").append(if (e.time > 0) FileListAdapter.fmtDate(e.time) else "—").append('\n')
        sb.append("用户:").append(e.user.ifBlank { "—" }).append('\n')
        sb.append("IP:").append(e.ip.ifBlank { "—" }).append('\n')
        sb.append("动作:").append(e.action.ifBlank { "—" }).append('\n')
        sb.append("请求:").append(e.method).append(' ').append(e.path)
        if (e.target.isNotBlank()) sb.append('\n').append("对象:").append(e.target)
        sb.append('\n').append("状态:").append(e.status)
        AlertDialog.Builder(this)
            .setTitle(e.action.ifBlank { e.method })
            .setMessage(sb.toString())
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
