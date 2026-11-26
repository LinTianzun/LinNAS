package com.linnas.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.linnas.app.backup.BackupConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 保险箱:密码解锁后浏览/上传/播放/删除(AES 加密目录在服务端)。 */
class VaultActivity : AppCompatActivity() {

    private val client = LinNasClient()
    private var vt: String? = null
    private var vaultPath: String = ""   // 当前所在子目录(相对保险箱根,""=根)
    private lateinit var list: LinearLayout
    private lateinit var hint: TextView
    private lateinit var lockedPanel: View
    private lateinit var unlockedPanel: View
    private lateinit var emptyView: View
    private lateinit var btnVaultBio: MaterialButton
    private lateinit var cbVaultBio: MaterialCheckBox

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val token = vt ?: return@registerForActivityResult
        if (uris.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            for (uri in uris) {
                try {
                    val name = queryName(uri) ?: "vault_${System.currentTimeMillis()}"
                    val size = querySize(uri)
                    withContext(Dispatchers.IO) {
                        client.vaultUpload(token, name, { contentResolver.openInputStream(uri)!! }, size, vaultPath)
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

        lockedPanel = findViewById(R.id.vaultLocked)
        unlockedPanel = findViewById(R.id.vaultUnlocked)
        emptyView = findViewById(R.id.vaultEmpty)

        btnVaultBio = findViewById(R.id.btnVaultBio)
        cbVaultBio = findViewById(R.id.cbVaultBio)
        cbVaultBio.setOnCheckedChangeListener(cbVaultBioListener)
        btnVaultBio.setOnClickListener { doBioUnlock() }
        refreshBio()

        findViewById<View>(R.id.btnVaultBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnVaultLock).setOnClickListener { lockNow() }
        findViewById<View>(R.id.btnVaultUnlock).setOnClickListener { promptUnlock() }
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
        findViewById<View>(R.id.btnVaultMkdir).setOnClickListener {
            if (vt == null) { toast("请先解锁"); return@setOnClickListener }
            showMkdir()
        }
        findViewById<View>(R.id.btnVaultPw).setOnClickListener {
            if (vt == null) { toast("请先解锁"); return@setOnClickListener }
            showChangePassword()
        }
        enter()
    }

    private fun showLocked() {
        lockedPanel.visibility = View.VISIBLE
        unlockedPanel.visibility = View.GONE
        hint.text = ""
        findViewById<View>(R.id.btnVaultMkdir).visibility = View.GONE
        findViewById<View>(R.id.btnVaultPw).visibility = View.GONE
        refreshBio()
    }

    private fun showUnlocked() {
        lockedPanel.visibility = View.GONE
        unlockedPanel.visibility = View.VISIBLE
        findViewById<View>(R.id.btnVaultMkdir).visibility = View.VISIBLE
        findViewById<View>(R.id.btnVaultPw).visibility = View.VISIBLE
        btnVaultBio.visibility = View.GONE
    }

    // ---- v1.8.0 指纹快速解锁实现见文件末尾「生物识别快速解锁」小节 ----

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
        val dlgView = layoutInflater.inflate(R.layout.dialog_password, null)
        dlgView.findViewById<TextView>(R.id.dlgHint).text =
            "设置一个至少 4 位的密码。忘记密码将无法恢复保险箱里的文件。"
        dlgView.findViewById<View>(R.id.pwWrap2).visibility = View.VISIBLE
        val pw = dlgView.findViewById<EditText>(R.id.etPassword)
        val pw2 = dlgView.findViewById<EditText>(R.id.etPassword2)
        val wrap = dlgView
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
        showLocked()
        val dlgView = layoutInflater.inflate(R.layout.dialog_password, null)
        dlgView.findViewById<TextView>(R.id.dlgHint).text = "输入保险箱密码后,文件在手机上即可浏览与播放。"
        val pw = dlgView.findViewById<EditText>(R.id.etPassword)
        AlertDialog.Builder(this).setTitle("解锁保险箱").setView(dlgView)
            .setPositiveButton("解锁") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val tok = withContext(Dispatchers.IO) { client.vaultUnlock(pw.text.toString()) }
                        if (tok.isBlank()) { toast("密码错误"); promptUnlock(); return@launch }
                        vt = tok
                        // 勾选后把保险箱密码加密保存,供指纹快速解锁。
                        if (cbVaultBio.isChecked && BiometricGate.canUse(this@VaultActivity)) {
                            if (BiometricGate.saveSecret(this@VaultActivity, KEY_VAULT_PW, pw.text.toString())) {
                                toast("已启用指纹快速解锁")
                            } else {
                                toast("指纹保存失败,可稍后重试")
                            }
                        }
                        hint.text = "已解锁 · 文件在服务端加密存储"
                        showUnlocked()
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
            vaultPath = ""
            list.removeAllViews()
            emptyView.visibility = View.GONE
            BiometricGate.clearSecret(this@VaultActivity, KEY_VAULT_PW)
            showLocked()
            promptUnlock()
        }
    }

    private fun loadList() {
        val token = vt ?: return
        lifecycleScope.launch {
            val entries = try { withContext(Dispatchers.IO) { client.vaultList(token, vaultPath) } } catch (e: Exception) { toast("读取失败: ${e.message}"); return@launch }
            list.removeAllViews()
            // 面包屑/当前位置
            hint.text = if (vaultPath.isBlank()) "保险箱根目录 · 服务端加密存储" else "位置: /$vaultPath"
            // 返回上级
            if (vaultPath.isNotBlank()) {
                val up = layoutInflater.inflate(R.layout.item_file, list, false)
                up.findViewById<ImageView>(R.id.ficon).setImageResource(R.drawable.ic_folder)
                up.findViewById<ImageView>(R.id.ficon).imageTintList = ThemeColors.csl(this@VaultActivity, R.attr.lTextDim)
                up.findViewById<TextView>(R.id.fname).text = ".. 返回上级"
                up.findViewById<TextView>(R.id.fmeta).text = ""
                up.findViewById<View>(R.id.fmore).visibility = View.GONE
                up.setOnClickListener {
                    vaultPath = if (vaultPath.contains('/')) vaultPath.substringBeforeLast('/') else ""
                    loadList()
                }
                list.addView(up)
            }
            emptyView.visibility = if (entries.isEmpty() && vaultPath.isBlank()) View.VISIBLE else View.GONE
            if (entries.isEmpty() && vaultPath.isBlank()) { hint.text = "保险箱是空的"; return@launch }
            for (e in entries) {
                val rel = e.path.ifBlank { vaultJoin(vaultPath, e.name) }
                val row = layoutInflater.inflate(R.layout.item_file, list, false)
                row.findViewById<ImageView>(R.id.ficon).setImageResource(if (e.isDir) R.drawable.ic_folder else R.drawable.ic_file)
                row.findViewById<ImageView>(R.id.ficon).imageTintList = ThemeColors.csl(this@VaultActivity, R.attr.lPrimary)
                row.findViewById<TextView>(R.id.fname).text = e.name
                row.findViewById<TextView>(R.id.fmeta).text =
                    if (e.isDir) ""
                    else FileListAdapter.fmtSize(e.size) + if (e.modTime > 0) "  ·  " + FileListAdapter.fmtDate(e.modTime) else ""
                row.findViewById<View>(R.id.fmore).setOnClickListener { anchor ->
                    val popup = android.widget.PopupMenu(this@VaultActivity, anchor)
                    if (!e.isDir) popup.menu.add(0, 1, 0, "导出到存储库…")
                    popup.menu.add(0, 2, 1, "删除")
                    popup.setOnMenuItemClickListener { mi ->
                        when (mi.itemId) {
                            1 -> exportEntry(e, rel)
                            2 -> confirmDelete(e, rel)
                        }
                        true
                    }
                    popup.show()
                }
                row.setOnClickListener {
                    if (e.isDir) { vaultPath = rel; loadList() } else openEntry(e, rel)
                }
                list.addView(row)
            }
        }
    }

    private fun vaultJoin(dir: String, name: String) =
        if (dir.isBlank()) name else dir.trimEnd('/') + "/" + name

    /** 在保险箱内新建文件夹(当前目录下)。 */
    private fun showMkdir() {
        val token = vt ?: return
        val et = EditText(this).apply { hint = "文件夹名称"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("新建文件夹").setView(et)
            .setPositiveButton("创建") { _, _ ->
                val nm = et.text.toString().trim()
                if (nm.isBlank()) { toast("请输入名称"); return@setPositiveButton }
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { client.vaultMkdir(token, vaultJoin(vaultPath, nm)) }
                        toast("已创建 $nm")
                        loadList()
                    } catch (e: Exception) { toast("创建失败: ${e.message}") }
                }
            }
            .setNegativeButton("取消", null).show()
    }

    /** 修改保险箱密码(成功后所有保险箱会话失效,需重新解锁)。 */
    private fun showChangePassword() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val etOld = EditText(this).apply { hint = "当前密码"; setSingleLine() }
        val etNew = EditText(this).apply { hint = "新密码(至少 4 位)"; setSingleLine() }
        box.addView(etOld); box.addView(etNew)
        AlertDialog.Builder(this).setTitle("修改保险箱密码").setView(box)
            .setPositiveButton("修改") { _, _ ->
                val old = etOld.text.toString()
                val nw = etNew.text.toString()
                if (old.isBlank() || nw.length < 4) { toast("请填写当前密码,新密码至少 4 位"); return@setPositiveButton }
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { client.vaultChangePassword(old, nw) }
                        toast("密码已修改,请重新解锁")
                        vt = null
                        vaultPath = ""
                        list.removeAllViews()
                        emptyView.visibility = View.GONE
                        BiometricGate.clearSecret(this@VaultActivity, KEY_VAULT_PW)
                        showLocked()
                        promptUnlock()
                    } catch (e: Exception) { toast("修改失败: ${e.message}") }
                }
            }
            .setNegativeButton("取消", null).show()
    }

    private fun openEntry(e: VaultEntry, rel: String) {
        val token = vt ?: return
        val ext = e.name.substringAfterLast('.', "").lowercase()
        val url = client.vaultRawUrl(token, rel)
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

    private fun confirmDelete(e: VaultEntry, rel: String) {
        val token = vt ?: return
        AlertDialog.Builder(this).setTitle("删除").setMessage("从保险箱删除「${e.name}」?")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { client.vaultDelete(token, listOf(rel)) }
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
                withContext(Dispatchers.IO) { client.vaultImport(token, listOf(vpath), vaultPath) }
                toast("已移入保险箱:$vpath")
                loadList()
            } catch (e: Exception) { toast("导入失败: ${e.message}") }
        }
    }

    private fun exportEntry(e: VaultEntry, rel: String) {
        pickLibraryDir { dest ->
            val token = vt ?: return@pickLibraryDir
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { client.vaultExport(token, listOf(rel), dest) }
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
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val crumb = TextView(this).apply { setTextColor(ThemeColors.textDim(this@VaultActivity)); textSize = 12f }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(this).apply { addView(box) }
        wrap.addView(crumb); wrap.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340)))

        val dlg = AlertDialog.Builder(this).setTitle(if (selectDirs) "选择导出目录" else "选择要导入的文件").setView(wrap)
            .setPositiveButton(if (selectDirs) "选这里" else "关闭", null).setNegativeButton("取消", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (selectDirs && currentPath.isNotEmpty()) { dlg.dismiss(); onDir?.invoke(currentPath) }
                else if (selectDirs) toast("先进入一个目录") else dlg.dismiss()
            }
        }
        dlg.show()

        fun row(label: String, isDir: Boolean, onClick: () -> Unit) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = (52 * resources.displayMetrics.density).toInt()
                setPadding(4, 6, 4, 6)
                isClickable = true
                isFocusable = true
                val ta = theme.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                background = ta.getDrawable(0)
                ta.recycle()
                setOnClickListener { onClick() }
            }
            val ic = ImageView(this).apply {
                setImageResource(if (isDir) R.drawable.ic_folder else R.drawable.ic_file)
                imageTintList = ThemeColors.csl(this@VaultActivity, if (isDir) R.attr.lPrimary else R.attr.lTextDim)
            }
            item.addView(ic, LinearLayout.LayoutParams((20 * resources.displayMetrics.density).toInt(), (20 * resources.displayMetrics.density).toInt()))
            val tv = TextView(this).apply {
                text = label
                setTextColor(ThemeColors.text(this@VaultActivity))
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setPadding((12 * resources.displayMetrics.density).toInt(), 0, 0, 0)
            }
            item.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        fun load(vp: String) {
            currentPath = vp
            crumb.text = "位置: " + vp.ifEmpty { "存储库" }
            box.removeAllViews()
            if (vp.isEmpty()) {
                lifecycleScope.launch {
                    val libs = try { withContext(Dispatchers.IO) { client.libs() } } catch (_: Exception) { emptyList() }
                    libs.forEach { l -> row(l.name, true) { load("/" + l.id) } }
                }
                return
            }
            row(".. 返回上级", true) { load(if (vp.count { it == '/' } <= 1) "" else vp.substringBeforeLast('/')) }
            lifecycleScope.launch {
                val resp = try { withContext(Dispatchers.IO) { client.ls(vp) } } catch (_: Exception) { null }
                resp?.entries?.forEach { e ->
                    val child = vp.trimEnd('/') + "/" + e.name
                    if (e.isDir) row(e.name, true) { load(child) }
                    else if (!selectDirs) row(e.name, false) { dlg.dismiss(); onFile?.invoke(child) }
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    // ---------- 生物识别快速解锁 ----------

    private val cbVaultBioListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
        if (!isChecked) {
            BiometricGate.clearSecret(this@VaultActivity, KEY_VAULT_PW)
            refreshBio()
        } else if (!BiometricGate.canUse(this)) {
            cbVaultBio.isChecked = false
            toast("当前设备不支持生物识别")
        } else if (BiometricGate.loadSecret(this, KEY_VAULT_PW).isNullOrBlank()) {
            // 尚未保存密码(需先手动解锁一次),保持未勾选。
            cbVaultBio.isChecked = false
            toast("请先手动解锁并勾选,再启用指纹快速解锁")
        }
    }

    /** 根据已保存密码与生物识别可用性刷新「指纹解锁」按钮与开关状态。 */
    private fun refreshBio() {
        val saved = BiometricGate.loadSecret(this, KEY_VAULT_PW)
        val enabled = !saved.isNullOrBlank()
        btnVaultBio.visibility = if (BiometricGate.canUse(this) && enabled) View.VISIBLE else View.GONE
        if (cbVaultBio.isChecked != enabled) {
            cbVaultBio.setOnCheckedChangeListener(null)
            cbVaultBio.isChecked = enabled
            cbVaultBio.setOnCheckedChangeListener(cbVaultBioListener)
        }
    }

    /** 指纹解锁:验证成功后用保存的密码调用 vaultUnlock。 */
    private fun doBioUnlock() {
        btnVaultBio.isEnabled = false
        val onSuccess: () -> Unit = {
            val saved = BiometricGate.loadSecret(this, KEY_VAULT_PW)
            if (saved.isNullOrBlank()) {
                btnVaultBio.isEnabled = true
                toast("未保存保险箱密码,请先手动解锁")
            } else {
                val pw = saved
                lifecycleScope.launch {
                    try {
                        val tok = withContext(Dispatchers.IO) { client.vaultUnlock(pw) }
                        if (tok.isBlank()) {
                            btnVaultBio.isEnabled = true
                            toast("保存的密码已失效,请重新解锁")
                            BiometricGate.clearSecret(this@VaultActivity, KEY_VAULT_PW)
                            refreshBio()
                        } else {
                            vt = tok
                            hint.text = "已通过生物识别解锁 · 文件在服务端加密存储"
                            showUnlocked()
                            loadList()
                        }
                    } catch (e: Exception) {
                        btnVaultBio.isEnabled = true
                        toast("指纹解锁失败: ${e.message}")
                    }
                }
            }
        }
        val onFail: (String) -> Unit = { msg ->
            btnVaultBio.isEnabled = true
            toast(msg)
        }
        BiometricGate.authenticate(this, "指纹/面容解锁", "验证后解锁保险箱", onSuccess, onFail)
    }

    override fun onDestroy() {
        // 退出页面时清除已保存的保险箱密码(锁定/改密码路径也已单独清除)。
        if (isFinishing) BiometricGate.clearSecret(this@VaultActivity, KEY_VAULT_PW)
        super.onDestroy()
    }

    companion object {
        private const val KEY_VAULT_PW = "bio_vault_pw"
    }
}
