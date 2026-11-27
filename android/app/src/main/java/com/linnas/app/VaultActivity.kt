package com.linnas.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
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
    private var vaultPath: String = ""   // 当前所在子目录(相对保险箱根,""=根)
    /** 本次进入页面是否用的是还剩 5 分钟免密窗口的会话 token(用于判断失败后要不要退回解锁)。 */
    private var resumedFromSession = false
    private lateinit var list: LinearLayout
    private lateinit var hint: TextView
    private lateinit var lockedPanel: View
    private lateinit var unlockedPanel: View
    private lateinit var emptyView: View

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

    override fun onResume() {
        super.onResume()
        // 设置里已关闭生物识别时,顺手清掉已保存的保险箱密码——
        // 关掉开关后残留的凭据不该继续留在磁盘上;正常退出/返回不动它。
        if (!BiometricGate.isEnabled(this)) {
            BiometricGate.clearSecret(this, vaultPwKey())
        }
    }

    private fun showLocked() {
        lockedPanel.visibility = View.VISIBLE
        unlockedPanel.visibility = View.GONE
        hint.text = ""
        findViewById<View>(R.id.btnVaultMkdir).visibility = View.GONE
        findViewById<View>(R.id.btnVaultPw).visibility = View.GONE
    }

    private fun showUnlocked() {
        lockedPanel.visibility = View.GONE
        unlockedPanel.visibility = View.VISIBLE
        findViewById<View>(R.id.btnVaultMkdir).visibility = View.VISIBLE
        findViewById<View>(R.id.btnVaultPw).visibility = View.VISIBLE
    }

    // ---- v1.8.0 指纹快速解锁实现见文件末尾「生物识别快速解锁」小节 ----

    private fun enter() {
        lifecycleScope.launch {
            val st = try { withContext(Dispatchers.IO) { client.vaultStatus() } } catch (e: Exception) {
                toast("连接失败: ${e.message}"); finish(); return@launch
            }
            val exists = st.optBoolean("exists")
            if (!exists) { promptSetup(); return@launch }
            // 5 分钟免密窗口内直接恢复上次解锁,不再重复要密码:用户抱怨的正是
            //「退出保险箱再进又要输」——所以退出页面不加锁,只有超时/整机后台超时才锁。
            if (VaultSession.isUnlocked() && !VaultSession.token().isNullOrBlank()) {
                resumeFromSession()
            } else {
                promptUnlock()
            }
        }
    }

    /** 用进程内会话 token 直接进入已解锁状态(服务端 token 失效时 loadList 会退回解锁)。 */
    private fun resumeFromSession() {
        vt = VaultSession.token()
        resumedFromSession = true
        showUnlocked()
        loadList()
    }

    private fun promptSetup() {
        val sheet = Sheet(this, "创建保险箱", "设置一个至少 4 位的密码。忘记密码将无法恢复保险箱里的文件。")
        sheet.input("密码", inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        sheet.input("再次输入密码", inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        sheet.primary("创建") {
            val a = sheet.inputView(0)?.text?.toString().orEmpty()
            val b = sheet.inputView(1)?.text?.toString().orEmpty()
            if (a.length < 4 || a != b) {
                sheet.inputView(1)?.error = "两次密码不一致或太短"
                toast("两次密码不一致或太短")
                return@primary
            }
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { client.vaultSetup(a) }
                } catch (e: Exception) {
                    toast("创建失败: ${e.message}")
                    return@launch
                }
                // 创建成功后直接用刚设置的密码换 token 进入已解锁状态,
                // 不再「创建完又让用户输一次刚设置的密码」。
                sheet.dismiss()
                val tok = try { withContext(Dispatchers.IO) { client.vaultUnlock(a) } } catch (_: Exception) { "" }
                if (tok.isNotBlank()) {
                    vt = tok
                    VaultSession.unlock(this@VaultActivity, tok)
                    hint.text = "已解锁 · 文件在服务端加密存储"
                    showUnlocked()
                    loadList()
                    toast("保险箱已创建")
                } else {
                    // 极端情况(换 token 网络失败):保险箱已创建,退回手动解锁流程。
                    toast("保险箱已创建,请解锁")
                    promptUnlock()
                }
            }
        }
        sheet.tonal("取消") { sheet.dismiss(); finish() }
        sheet.show()
    }

    /** 解锁入口:设置里开着生物识别就优先自动弹指纹;首次还没有已保存密码时自动回退到密码输入。 */
    private fun promptUnlock() {
        showLocked()
        if (bioAvailable()) {
            bioUnlockThenFallBack()
        } else {
            showPasswordSheet()
        }
    }

    /** 生物识别既要在设置里开启,也要设备真能用;两者缺一都走密码。 */
    private fun bioAvailable(): Boolean =
        BiometricGate.isEnabled(this) && BiometricGate.canUse(this)

    /** 指纹解锁的前提:开关开启 + 设备可用 + 已自动保存过密码。 */
    private fun bioUnlockAvailable(): Boolean =
        bioAvailable() && !BiometricGate.loadSecret(this, vaultPwKey()).isNullOrBlank()

    /** 密码解锁 Sheet:生物识别可用时额外给一行「用指纹解锁」,方便取消/失败后重试。 */
    private fun showPasswordSheet() {
        val sheet = Sheet(this, "解锁保险箱", "输入保险箱密码后,文件在手机上即可浏览与播放。")
        if (bioUnlockAvailable()) {
            sheet.row(R.drawable.ic_login_fingerprint, "用指纹解锁", "验证已保存的指纹,免输入密码") {
                bioUnlockThenFallBack()
            }
        }
        sheet.input("保险箱密码", inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        sheet.primary("解锁") {
            val pwText = sheet.inputView(0)?.text?.toString().orEmpty()
            unlockWithPassword(pwText, viaBio = false, sheet = sheet) { msg ->
                sheet.inputView(0)?.error = msg
                toast(msg)
            }
        }
        sheet.tonal("取消") { sheet.dismiss(); finish() }
        sheet.show()
    }

    /** 用密码换 token 并进入已解锁状态;成功后记录 5 分钟免密窗口。 */
    private fun unlockWithPassword(
        pw: String,
        viaBio: Boolean,
        sheet: Sheet? = null,
        onError: (String) -> Unit,
    ) {
        lifecycleScope.launch {
            try {
                val tok = withContext(Dispatchers.IO) { client.vaultUnlock(pw) }
                if (tok.isBlank()) { onError("密码错误"); return@launch }
                vt = tok
                // 只要设置里开着生物识别就自动保存密码,下次进保险箱直接弹指纹,
                // 用户不必再勾选任何开关;保存失败只影响指纹入口,不影响本次解锁。
                if (bioAvailable() &&
                    !BiometricGate.saveSecret(this@VaultActivity, vaultPwKey(), pw)
                ) {
                    Log.w(TAG, "自动保存指纹解锁密码失败,下次进入仍需输入密码")
                }
                VaultSession.unlock(this@VaultActivity, tok)
                // 解锁成功后自动关闭密码输入弹层,避免停留在已解锁页面上。
                sheet?.dismiss()
                hint.text = if (viaBio) "已通过生物识别解锁 · 文件在服务端加密存储" else "已解锁 · 文件在服务端加密存储"
                showUnlocked()
                loadList()
            } catch (e: Exception) {
                onError(if (viaBio) "指纹解锁失败: ${e.message}" else "解锁失败: ${e.message}")
            }
        }
    }

    /**
     * 指纹验证成功后用已保存的密码解锁;验证框被关掉(取消/超时/锁定)才回退到密码 Sheet。
     * 单次识别失败不会关闭系统弹窗,用户可在弹窗内重试,此时不弹密码 Sheet 打断。
     */
    private fun bioUnlockThenFallBack() {
        val saved = BiometricGate.loadSecret(this, vaultPwKey())
        if (saved.isNullOrBlank()) { showPasswordSheet(); return }
        BiometricGate.authenticate(
            this, "指纹/面容解锁", "验证后解锁保险箱",
            onSuccess = {
                unlockWithPassword(saved, viaBio = true) { msg -> toast(msg) }
            },
            onFail = { msg ->
                if (msg == BiometricGate.MSG_RETRY) toast(msg) else showPasswordSheet()
            },
        )
    }

    private fun lockNow() {
        val token = vt ?: return
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { client.vaultLock(token) } } catch (_: Exception) { }
            vt = null
            vaultPath = ""
            VaultSession.lock()
            list.removeAllViews()
            emptyView.visibility = View.GONE
            // 手动锁定只清会话,不清已保存的指纹密码:重进保险箱仍可自动弹指纹。
            // 真正要清理的是「改密码」和「设置里关闭生物识别」两条路径。
            showLocked()
            promptUnlock()
        }
    }

    private fun loadList() {
        val token = vt ?: return
        lifecycleScope.launch {
            val entries = try {
                withContext(Dispatchers.IO) { client.vaultList(token, vaultPath) }
            } catch (e: Exception) {
                // 从免密会话恢复后首次拉取失败:多半是服务端已失效(改密码/重启),
                // 清掉会话退回解锁,而不是停在空列表反复报错。
                if (resumedFromSession) {
                    resumedFromSession = false
                    vt = null
                    VaultSession.lock()
                    toast("保险箱会话已失效,请重新解锁")
                    promptUnlock()
                } else {
                    toast("读取失败: ${e.message}")
                }
                return@launch
            }
            resumedFromSession = false
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
                row.findViewById<View>(R.id.fmore).setOnClickListener { _ ->
                    // 用底部 Sheet 替代系统弹出菜单,统一操作菜单观感
                    val sheet = Sheet(this@VaultActivity, e.name)
                    if (!e.isDir) sheet.row(R.drawable.ic_upload, "导出到存储库…") { exportEntry(e, rel) }
                    sheet.row(R.drawable.ic_trash_bin, "删除", destructive = true) { confirmDelete(e, rel) }
                    sheet.show()
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
        val sheet = Sheet(this, "新建文件夹")
        sheet.input("文件夹名称")
        sheet.primary("创建") {
            val nm = sheet.value(0)
            if (nm.isBlank()) {
                sheet.inputView(0)?.error = "请输入名称"
                toast("请输入名称")
                return@primary
            }
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { client.vaultMkdir(token, vaultJoin(vaultPath, nm)) }
                    sheet.dismiss()
                    toast("已创建 $nm")
                    loadList()
                } catch (e: Exception) { toast("创建失败: ${e.message}") }
            }
        }
        sheet.tonal("取消") { sheet.dismiss() }
        sheet.show()
    }

    /** 修改保险箱密码(成功后所有保险箱会话失效,需重新解锁)。 */
    private fun showChangePassword() {
        val sheet = Sheet(this, "修改保险箱密码")
        sheet.input("当前密码", inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        sheet.input("新密码(至少 4 位)", inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        sheet.primary("修改") {
            val old = sheet.inputView(0)?.text?.toString().orEmpty()
            val nw = sheet.inputView(1)?.text?.toString().orEmpty()
            if (old.isBlank() || nw.length < 4) {
                sheet.inputView(1)?.error = "请填写当前密码,新密码至少 4 位"
                toast("请填写当前密码,新密码至少 4 位")
                return@primary
            }
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { client.vaultChangePassword(old, nw) }
                    toast("密码已修改,请重新解锁")
                    vt = null
                    vaultPath = ""
                    VaultSession.lock()
                    list.removeAllViews()
                    emptyView.visibility = View.GONE
                    // 旧密码已作废,清掉之前自动保存的指纹密码;下次解锁成功会重新自动保存。
                    BiometricGate.clearSecret(this@VaultActivity, vaultPwKey())
                    sheet.dismiss()
                    showLocked()
                    promptUnlock()
                } catch (e: Exception) { toast("修改失败: ${e.message}") }
            }
        }
        sheet.tonal("取消") { sheet.dismiss() }
        sheet.show()
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
        val sheet = Sheet(this, "删除", "从保险箱删除「${e.name}」?")
        sheet.danger("删除") {
            sheet.dismiss()
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { client.vaultDelete(token, listOf(rel)) }
                    loadList()
                } catch (ex: Exception) { toast("失败: ${ex.message}") }
            }
        }
        sheet.tonal("取消") { sheet.dismiss() }
        sheet.show()
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
        // 目录浏览需要原地刷新 + 自定义内容,用 Sheet 的持久容器承载
        val sheet = Sheet(this, if (selectDirs) "选择导出目录" else "选择要导入的文件")
        val area = sheet.customArea()
        val crumb = TextView(this).apply { setTextColor(ThemeColors.textDim(this@VaultActivity)); textSize = 12f }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(this).apply { addView(box) }
        area.addView(crumb)
        area.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340)))

        // 目录选择用「选这里」;文件选择用「关闭」(点文件本身即选中并关闭)
        if (selectDirs) {
            sheet.primary("选这里") {
                if (currentPath.isNotEmpty()) { sheet.dismiss(); onDir?.invoke(currentPath) }
                else toast("先进入一个目录")
            }
        } else {
            sheet.primary("关闭") { sheet.dismiss() }
        }
        sheet.tonal("取消") { sheet.dismiss() }

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
                    else if (!selectDirs) row(e.name, false) { sheet.dismiss(); onFile?.invoke(child) }
                }
            }
        }
        load("")
        sheet.show()
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

    // 不再有勾选开关与单独的「指纹解锁」按钮:设置里开着生物识别时,
    // 进保险箱(promptUnlock)自动弹指纹;密码解锁成功会自动保存密码,
    // 取消/失败时由密码输入框里的「用指纹解锁」一行作为重试入口。

    // 说明:退出页面时不再清已保存的保险箱密码、也不锁 VaultSession——
    // 用户诉求是「退出保险箱再进不要重输」。真正的重新验证由以下路径保证:
    // 手动「锁定」(lockNow)、修改密码、或 App 真正退到后台/进程结束后超时。

    companion object {
        private const val TAG = "VaultActivity"
        private const val KEY_VAULT_PW = "bio_vault_pw"
    }

    /**
     * 指纹解锁用的保险箱密码按「服务器地址」分开存。
     *
     * 之前只有一个全局键：连过另一台服务端并解锁后，同一串密文被另一台服务的密码覆盖，
     * 换回原服务器时指纹解锁必然失败。分键存储后，每台服务端各自记住自己的密码；
     * 新服务器首次进入时没有对应密文 → 自然回退到密码输入，输入一次后才会启用指纹。
     */
    private fun vaultPwKey(): String = "$KEY_VAULT_PW|${ServerConfig.baseUrl.trimEnd('/')}"
}

/**
 * 进程内保险箱解锁会话:让「退出保险箱页面」不丢失解锁状态,只在
 * 免密时限(5 分钟)耗尽或整个 App 退到后台超时后才重新要求解锁。
 *
 * 为什么放在内存里而不是持久化:保险箱 token 是访问加密目录的凭据,
 * 只应存在于当前进程;进程被杀即失效,避免把凭据长期留在磁盘上。
 */
object VaultSession {
    /** 免密时限:解锁后 5 分钟内再次进入保险箱不再要密码;后台超过此时长也重新要。 */
    private const val TIMEOUT_MS = 5 * 60 * 1000L

    private var unlockedAt = 0L          // 最近一次成功解锁的时刻(elapsedRealtime)
    private var backgroundAt = 0L        // 最近一次整个 App 退到后台的时刻
    private var token: String? = null    // 服务端保险箱 token,退出页面时保留

    /** 是否仍在免密窗口内。用 elapsedRealtime,不受用户改系统时间影响。 */
    fun isUnlocked(): Boolean =
        unlockedAt > 0L && SystemClock.elapsedRealtime() - unlockedAt < TIMEOUT_MS

    /** 记录解锁时刻并保存 token;上下文参数预留给以后做跨进程持久化,当前只用内存。 */
    fun unlock(ctx: Context, tok: String? = null) {
        unlockedAt = SystemClock.elapsedRealtime()
        backgroundAt = 0L
        token = tok ?: token
    }

    /** 免密窗口内返回 token,否则 null。 */
    fun token(): String? = if (isUnlocked()) token else null

    /** 立即失效(手动锁定、改密码、会话被服务端拒绝时调用)。 */
    fun lock() {
        unlockedAt = 0L
        backgroundAt = 0L
        token = null
    }

    /** 整个 App 退到后台:记录时刻,供回前台时判断后台时长。 */
    fun onAppBackground() {
        backgroundAt = SystemClock.elapsedRealtime()
    }

    /** 回到前台:后台时长达到免密时限就上锁,否则保持解锁。 */
    fun onAppForeground() {
        if (backgroundAt > 0L && SystemClock.elapsedRealtime() - backgroundAt >= TIMEOUT_MS) {
            lock()
        }
        backgroundAt = 0L
    }
}
