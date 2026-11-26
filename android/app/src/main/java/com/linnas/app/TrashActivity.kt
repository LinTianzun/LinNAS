package com.linnas.app

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * M7 回收站:列出 /api/trash 条目,支持恢复、彻底删除、清空。
 * 删除操作受角色控制(viewer 只读,服务端同样会拒绝写操作)。
 */
class TrashActivity : AppCompatActivity() {

    private val client = LinNasClient()
    private lateinit var list: LinearLayout
    private lateinit var hint: TextView
    private var libs: List<Library> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeStore.styleRes(this))
        super.onCreate(savedInstanceState)
        Prefs.init(applicationContext)
        setContentView(R.layout.activity_trash)
        list = findViewById(R.id.trashList)
        hint = findViewById(R.id.trashHint)

        findViewById<View>(R.id.btnTrashBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnTrashEmpty).setOnClickListener { confirmEmpty(null) }
        loadList()
    }

    private fun loadList() {
        lifecycleScope.launch {
            if (libs.isEmpty()) {
                libs = try { withContext(Dispatchers.IO) { client.libs() } } catch (_: Exception) { emptyList() }
            }
            val items = try {
                withContext(Dispatchers.IO) { client.trash("") }
            } catch (e: Exception) {
                toast("读取失败: ${e.message}"); return@launch
            }
            list.removeAllViews()
            if (items.isEmpty()) {
                hint.text = "回收站是空的"
                return@launch
            }
            hint.text = "共 ${items.size} 个已删除条目"
            for (it in items) {
                val lib = libs.firstOrNull { l -> l.id == it.libId }
                val row = layoutInflater.inflate(R.layout.item_file, list, false)
                row.findViewById<ImageView>(R.id.ficon).setImageResource(
                    if (it.isDir) R.drawable.ic_folder else R.drawable.ic_file
                )
                row.findViewById<ImageView>(R.id.ficon).imageTintList =
                    ThemeColors.csl(this@TrashActivity, R.attr.lPrimary)
                row.findViewById<TextView>(R.id.fname).text = it.name
                row.findViewById<TextView>(R.id.fmeta).text = buildString {
                    append(FileListAdapter.fmtSize(it.size))
                    if (it.trashedAt > 0) append("  ·  " + FileListAdapter.fmtDate(it.trashedAt))
                    if (lib != null) append("  ·  " + lib.name)
                }
                row.findViewById<View>(R.id.fmore).setOnClickListener { _ ->
                    if (!canWrite()) { toast("只读用户无法操作回收站"); return@setOnClickListener }
                    // 用底部 Sheet 替代系统弹出菜单,与其余二级页统一主题化观感
                    val sheet = Sheet(this@TrashActivity, it.name)
                    sheet.row(R.drawable.ic_refresh, "恢复") { restoreEntry(it) }
                    sheet.row(R.drawable.ic_trash_bin, "彻底删除", destructive = true) { confirmDeleteEntry(it) }
                    sheet.show()
                }
                list.addView(row)
            }
        }
    }

    private fun restoreEntry(it: TrashItem) {
        lifecycleScope.launch {
            try {
                val dst = withContext(Dispatchers.IO) { client.trashRestore(it.id, it.libId) }
                toast("已恢复:${dst.ifBlank { it.name }}")
                loadList()
            } catch (e: Exception) { toast("恢复失败: ${e.message}") }
        }
    }

    private fun confirmDeleteEntry(it: TrashItem) {
        val sheet = Sheet(this, "彻底删除", "彻底删除「${it.name}」?此操作不可恢复。")
        sheet.danger("删除") {
            sheet.dismiss()
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { client.trashDelete(it.id, it.libId) }
                    toast("已删除 ${it.name}")
                    loadList()
                } catch (e: Exception) { toast("删除失败: ${e.message}") }
            }
        }
        sheet.tonal("取消") { sheet.dismiss() }
        sheet.show()
    }

    private fun confirmEmpty(lib: Library?) {
        val scope = lib ?: run {
            val names = libs.joinToString("、") { it.name }
            if (libs.isEmpty()) return@confirmEmpty
            val allSheet = Sheet(this, "清空回收站", "将清空 $names 下的回收站内容,此操作不可恢复。")
            allSheet.danger("清空") { allSheet.dismiss(); doEmpty("") }
            allSheet.tonal("取消") { allSheet.dismiss() }
            allSheet.show()
            return@confirmEmpty
        }
        val sheet = Sheet(this, "清空回收站", "将清空「${lib.name}」的回收站内容,此操作不可恢复。")
        sheet.danger("清空") { sheet.dismiss(); doEmpty(lib.id) }
        sheet.tonal("取消") { sheet.dismiss() }
        sheet.show()
    }

    private fun doEmpty(lib: String) {
        lifecycleScope.launch {
            try {
                val n = withContext(Dispatchers.IO) { client.trashEmpty(lib) }
                toast("已清空 $n 个条目")
                loadList()
            } catch (e: Exception) { toast("清空失败: ${e.message}") }
        }
    }

    private fun canWrite(): Boolean = ServerConfig.sessionRole != "viewer"

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
}
