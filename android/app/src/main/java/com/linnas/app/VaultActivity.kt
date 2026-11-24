package com.linnas.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.linnas.app.backup.BackupConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 保险箱:密码解锁后浏览/上传/播放/删除(AES 加密目录在服务端)。 */
class VaultActivity : AppCompatActivity() {

    private val client = LinNasClient()
    private var vt: String? = null
    private lateinit var list: LinearLayout
    private lateinit var hint: TextView

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val token = vt ?: return@registerForActivityResult
        if (uris.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            for (uri in uris) {
                try {
                    val name = queryName(uri) ?: "vault_${System.currentTimeMillis()}"
                    val size = querySize(uri)
                    withContext(Dispatchers.IO) {
                        client.vaultUpload(token, name, { contentResolver.openInputStream(uri)!! }, size)
                    }
                    toast("已上传 $name")
                } catch (e: Exception) {
                    toast("上传失败: ${e.message}")
                }
            }
            loadList()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeStore.styleRes(this))
        super.onCreate(savedInstanceState)
        Prefs.init(applicationContext)
        setContentView(R.layout.activity_vault)
        list = findViewById(R.id.vaultList)
        hint = findViewById(R.id.vaultHint)

        findViewById<View>(R.id.btnVaultBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnVaultLock).setOnClickListener { lockNow() }
        findViewById<View>(R.id.btnVaultUpload).setOnClickListener {
            if (vt == null) toast("请先解锁") else pickFile.launch("*/*")
        }
        findViewById<View>(R.id.btnVaultImport).setOnClickListener {
            if (vt == null) { toast("请先解锁"); return@setOnClickListener }
            pickLibraryFile { vp -> importPath(vp) }
        }
        findViewById<View>(R.id.btnVaultExport).setOnClickListener {
            if (vt == null) { toast("请先解锁"); return@setOnClickListener }
            toast("在文件右侧「⋯」菜单里选择「导出到…」")
        }
        enter()
    }

    private fun enter() {
        lifecycleScope.launch {
            val st = try { withContext(Dispatchers.IO) { client.vaultStatus() } } catch (e: Exception) {
                toast("连接失败: ${e.message}"); finish(); return@launch
            }
            val exists = st.optBoolean("exists")
            if (!exists) promptSetup() else promptUnlock()
        }
    }

    private fun promptSetup() {
        val pw = EditText(this).apply { hint = "设置保险箱密码(至少 4 位)"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val pw2 = EditText(this).apply { hint = "再次输入密码"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0); addView(pw); addView(pw2) }
        AlertDialog.Builder(this).setTitle("创建保险箱").setView(wrap)
            .setPositiveButton("创建") { _, _ ->
                val a = pw.text.toString(); val b = pw2.text.toString()
                if (a.length < 4 || a != b) { toast("两次密码不一致或太短"); promptSetup(); return@setPositiveButton }
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { client.vaultSetup(a) }
                        toast("保险箱已创建,请解锁")
                        promptUnlock()
                    } catch (e: Exception) { toast("创建失败: ${e.message}") }
                }
            }
            .setNegativeButton("取消") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun promptUnlock() {
        hint.text = "输入密码解锁保险箱"
        val pw = EditText(this).apply { hint = "保险箱密码"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AlertDialog.Builder(this).setTitle("解锁保险箱").setView(pw)
            .setPositiveButton("解锁") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val tok = withContext(Dispatchers.IO) { client.vaultUnlock(pw.text.toString()) }
                        if (tok.isBlank()) { toast("密码错误"); promptUnlock(); return@launch }
                        vt = tok
                        hint.text = "已解锁 · 文件在服务端加密存储"
                        loadList()
                    } catch (e: Exception) { toast("解锁失败: ${e.message}") }
                }
            }
            .setNegativeButton("取消") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun lockNow() {
        val token = vt ?: return
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { client.vaultLock(token) } } catch (_: Exception) { }
            vt = null
            list.removeAllViews()
            promptUnlock()
        }
    }

    private fun loadList() {
        val token = vt ?: return
        lifecycleScope.launch {
            val entries = try { withContext(Dispatchers.IO) { client.vaultList(token) } } catch (e: Exception) { toast("读取失败: ${e.message}"); return@launch }
            list.removeAllViews()
            if (entries.isEmpty()) {
                hint.text = "保险箱是空的 · 点下方上传私密文件"
                return@launch
            }
            hint.text = "共 ${entries.size} 个文件(服务端加密存储)"
            for (e in entries) {
                val row = layoutInflater.inflate(R.layout.item_file, list, false)
                row.findViewById<ImageView>(R.id.ficon).setImageResource(R.drawable.ic_file)
                row.findViewById<ImageView>(R.id.ficon).imageTintList = ThemeColors.csl(this@VaultActivity, R.attr.lPrimary)
                row.findViewById<TextView>(R.id.fname).text = e.name
                row.findViewById<TextView>(R.id.fmeta).text =
                    FileListAdapter.fmtSize(e.size) + if (e.modTime > 0) "  ·  " + FileListAdapter.fmtDate(e.modTime) else ""
                row.findViewById<View>(R.id.fmore).setOnClickListener { anchor ->
                    val popup = android.widget.PopupMenu(this@VaultActivity, anchor)
                    popup.menu.add(0, 1, 0, "导出到存储库…")
                    popup.menu.add(0, 2, 1, "删除")
                    popup.setOnMenuItemClickListener { mi ->
                        when (mi.itemId) {
                            1 -> exportEntry(e)
                            2 -> confirmDelete(e)
                        }
                        true
                    }
                    popup.show()
                }
                row.setOnClickListener { openEntry(e) }
                list.addView(row)
            }
        }
    }

    private fun openEntry(e: VaultEntry) {
        val token = vt ?: return
        val ext = e.name.substringAfterLast('.', "").lowercase()
        val url = client.vaultRawUrl(token, e.name)
        if (ext in FileListAdapter.VIDEO || ext in FileListAdapter.AUDIO) {
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_URL, url)
                putExtra(PlayerActivity.EXTRA_TITLE, e.name)
            })
        } else {
            Downloads.enqueue(this, url, e.name)
            toast("开始下载 ${e.name}")
        }
    }

    private fun confirmDelete(e: VaultEntry) {
        val token = vt ?: return
        AlertDialog.Builder(this).setTitle("删除").setMessage("从保险箱删除「${e.name}」?")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { client.vaultDelete(token, listOf(e.name)) }
                        loadList()
                    } catch (ex: Exception) { toast("失败: ${ex.message}") }
                }
            }
            .setNegativeButton("取消", null).show()
    }

    // ---------- 导入 / 导出 ----------

    private fun importPath(vpath: String) {
        val token = vt ?: return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { client.vaultImport(token, listOf(vpath), "") }
                toast("已移入保险箱:$vpath")
                loadList()
            } catch (e: Exception) { toast("导入失败: ${e.message}") }
        }
    }

    private fun exportEntry(e: VaultEntry) {
        pickLibraryDir { dest ->
            val token = vt ?: return@pickLibraryDir
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { client.vaultExport(token, listOf(e.name), dest) }
                    toast("已导出到 $dest")
                    loadList()
                } catch (ex: Exception) { toast("导出失败: ${ex.message}") }
            }
        }
    }

    /** 存储库文件选择:库 → 目录 → 文件(单选)。 */
    private fun pickLibraryFile(onPick: (String) -> Unit) = pickLibrary(selectDirs = false, onDir = null, onFile = onPick)

    /** 存储库目录选择:库 → 目录。 */
    private fun pickLibraryDir(onPick: (String) -> Unit) = pickLibrary(selectDirs = true, onDir = onPick, onFile = null)

    private fun pickLibrary(selectDirs: Boolean, onDir: ((String) -> Unit)?, onFile: ((String) -> Unit)?) {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 8, 40, 0)
        }
        val crumb = TextView(this).apply { setTextColor(ThemeColors.textDim(this@VaultActivity)); textSize = 12f }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(this).apply { addView(box) }
        wrap.addView(crumb); wrap.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 900))

        val dlg = AlertDialog.Builder(this).setTitle(if (selectDirs) "选择导出目录" else "选择要导入的文件").setView(wrap)
            .setPositiveButton(if (selectDirs) "选这里" else "关闭", null).setNegativeButton("取消", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (selectDirs && currentPath.isNotEmpty()) { dlg.dismiss(); onDir?.invoke(currentPath) }
                else if (selectDirs) toast("先进入一个目录") else dlg.dismiss()
            }
        }
        dlg.show()

        fun row(label: String, onClick: () -> Unit) {
            val tv = TextView(this).apply {
                text = label; setTextColor(ThemeColors.text(this@VaultActivity)); textSize = 14f
                setPadding(10, 24, 10, 24); setOnClickListener { onClick() }
            }
            box.addView(tv)
        }
        fun load(vp: String) {
            currentPath = vp
            crumb.text = "位置: " + vp.ifEmpty { "存储库" }
            box.removeAllViews()
            if (vp.isEmpty()) {
                lifecycleScope.launch {
                    val libs = try { withContext(Dispatchers.IO) { client.libs() } } catch (_: Exception) { emptyList() }
                    libs.forEach { l -> row(l.name) { load("/" + l.id) } }
                }
                return
            }
            row(".. 返回上级") { load(if (vp.count { it == '/' } <= 1) "" else vp.substringBeforeLast('/')) }
            lifecycleScope.launch {
                val resp = try { withContext(Dispatchers.IO) { client.ls(vp) } } catch (_: Exception) { null }
                resp?.entries?.forEach { e ->
                    val child = vp.trimEnd('/') + "/" + e.name
                    if (e.isDir) row("[目录] " + e.name) { load(child) }
                    else if (!selectDirs) row("[文件] " + e.name) { dlg.dismiss(); onFile?.invoke(child) }
                }
            }
        }
        load("")
    }

    private var currentPath: String = ""

    private fun queryName(uri: Uri): String? = contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
    }

    private fun querySize(uri: Uri): Long = contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.SIZE)
        if (i >= 0 && c.moveToFirst()) c.getLong(i) else 0L
    } ?: 0L

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
}
