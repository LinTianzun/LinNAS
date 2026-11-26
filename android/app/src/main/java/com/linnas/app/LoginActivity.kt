package com.linnas.app

import android.animation.ObjectAnimator
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 多用户登录页：服务器开启多用户时用账号密码登录拿会话；
 * 也可用指纹/面容直接恢复已保存会话。
 *
 * 会话策略（配合 Prefs / SessionRestore）：
 * - 只要登录成功就无条件把会话加密保存，复选框「下次自动登录」只决定下次是否直接进入；
 * - 开启「下次自动登录」时，进程启动由 Prefs.init → SessionRestore 直接恢复，不再进本页；
 * - 未开启时进本页，若存在已保存会话则自动弹出一次指纹验证。
 *
 * 指纹入口可见性（本次修正）：只要「设置里开启 + 硬件可用」就无条件显示指纹按钮，
 * 不再依赖「是否存过加密会话」——v1.8.4 之前登录过的老用户升级后没有加密会话，
 * 旧逻辑直接把按钮藏掉，用户以为指纹功能丢了；此时按钮退化为引导态，
 * 验证通过后会提示先走一次账号密码登录。
 */
class LoginActivity : AppCompatActivity() {

    private val client = LinNasClient()

    /** 防止进入页面后重复自动弹指纹。 */
    private var bioPrompted = false

    /** 当前指纹按钮是否处于「有会话可恢复」模式（决定指纹验证成功后的去向）。 */
    private var bioHasSaved = false

    /** 扫描发现进行中标记，防止重复点击「扫描局域网」叠加任务。 */
    private var scanning = false

    // 失败路径要把焦点交回输入框 / 重绑按钮，存成字段避免层层传参。
    private var etUserView: TextInputEditText? = null
    private var btnSwitchServerView: MaterialButton? = null
    private var cbBioView: MaterialCheckBox? = null
    private var bioDividerView: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeStore.styleRes(this))
        super.onCreate(savedInstanceState)
        Prefs.init(applicationContext)
        setContentView(R.layout.activity_login)
        // manifest 未设 softInputMode：这里用 adjustResize，保证输入框获焦时不被软键盘遮挡。
        // （SOFT_INPUT_ADJUST_RESIZE 虽然在 API 30+ 标记废弃，但 targetSdk 34 下仍生效，
        //  且比 adjustNothing/adjustPan 更符合「表单页」的需求，故显式设置。）
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val etUser = findViewById<TextInputEditText>(R.id.etUser).also { etUserView = it }
        val etPassword = findViewById<TextInputEditText>(R.id.etPassword)
        val btnLogin = findViewById<MaterialButton>(R.id.btnLogin)
        val btnSkip = findViewById<MaterialButton>(R.id.btnSkip)
        val btnBio = findViewById<MaterialButton>(R.id.btnBioLogin)
        val cbBio = findViewById<MaterialCheckBox>(R.id.cbBioLogin).also { cbBioView = it }
        val error = findViewById<TextView>(R.id.loginError)
        val tvServer = findViewById<TextView>(R.id.tvServer)
        val btnSwitchServer = findViewById<MaterialButton>(R.id.btnSwitchServer).also { btnSwitchServerView = it }
        val bioDivider = findViewById<View>(R.id.tvBioDivider).also { bioDividerView = it }

        tvServer.text = ServerConfig.baseUrl.ifBlank { "未设置服务器地址" }
        btnSwitchServer.setOnClickListener { showSwitchServerSheet(tvServer) }

        // 复选框默认反映上次选择
        cbBio.isChecked = Prefs.getBool(KEY_AUTO_LOGIN, false)

        setupBioEntry(btnBio, cbBio, bioDivider, error)

        btnLogin.setOnClickListener {
            val u = etUser.text.toString().trim()
            val p = etPassword.text.toString()
            if (u.isEmpty() || p.isEmpty()) {
                error.visibility = View.VISIBLE
                error.text = "请输入用户名和密码"
                return@setOnClickListener
            }
            btnLogin.isEnabled = false
            error.visibility = View.GONE
            lifecycleScope.launch {
                try {
                    val resp = withContext(Dispatchers.IO) { client.login(u, p) }
                    toast("欢迎,${resp.username}(${roleLabel(resp.role)})")
                    // 无条件保存加密会话；auto_login 决定下次是否直接进入。
                    Prefs.saveSessionMeta(
                        ServerConfig.sessionToken,
                        ServerConfig.sessionUser,
                        ServerConfig.sessionRole,
                        ServerConfig.baseUrl,
                    )
                    Prefs.setBool(KEY_AUTO_LOGIN, cbBio.isChecked)
                    setResult(RESULT_OK)
                    finish()
                } catch (e: LockoutException) {
                    // 防爆破:429 + Retry-After,倒计时期间禁用登录按钮
                    error.visibility = View.VISIBLE
                    btnLogin.isEnabled = false
                    var left = e.retryAfterSec
                    while (left > 0) {
                        error.text = "尝试次数过多,请在 $left 秒后重试"
                        kotlinx.coroutines.delay(1000)
                        left--
                    }
                    btnLogin.isEnabled = true
                    error.text = "可以重新登录了"
                } catch (e: Exception) {
                    btnLogin.isEnabled = true
                    error.visibility = View.VISIBLE
                    error.text = "登录失败: ${e.message}"
                }
            }
        }

        btnSkip.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    /**
     * 配置指纹入口：
     * - 设置里开启 + 设备可用：无条件显示指纹主按钮（不再依赖有没有已保存会话）；
     *   有会话时自动弹一次验证；没会话时按钮是引导态，验证通过后提示先登录一次。
     * - 设置里关掉或设备不支持：隐藏指纹按钮，有已保存会话时退化为「使用已保存的登录信息进入」。
     */
    private fun setupBioEntry(btn: MaterialButton, cb: MaterialCheckBox, divider: View, error: TextView) {
        val meta = Prefs.sessionMeta()
        val hasSaved = meta != null
        val user = meta?.get(1)?.takeIf { it.isNotBlank() }
        val bioReady = BiometricGate.isEnabled(this) && BiometricGate.canUse(this)
        val hint = findViewById<TextView>(R.id.tvBioHint)
        bioHasSaved = hasSaved

        cb.visibility = if (bioReady) View.VISIBLE else View.GONE

        when {
            // 1) 指纹可用（设置开启 + 硬件支持）→ 无条件显示，不看 hasSaved：
            //    老用户升级后没有加密会话，旧逻辑直接藏按钮，用户以为功能丢了。
            bioReady -> {
                btn.visibility = View.VISIBLE
                divider.visibility = View.VISIBLE
                btn.text = if (hasSaved && user != null) "指纹登录 · $user" else "指纹登录"
                btn.setOnClickListener { doBioAuth(btn, error) }
                // 没会话时按钮只是引导态：副标题提示先登录一次；自动弹验证只在有会话时做。
                hint.visibility = if (hasSaved) View.GONE else View.VISIBLE
                if (hasSaved && !bioPrompted) {
                    bioPrompted = true
                    btn.post { doBioAuth(btn, error) }
                }
            }
            // 2) 指纹关闭/不可用但有已保存会话：不显示指纹按钮，但仍允许用加密保存的会话直接进入。
            hasSaved -> {
                btn.visibility = View.VISIBLE
                divider.visibility = View.VISIBLE
                btn.text = if (user != null) "使用已保存的登录信息进入 · $user" else "使用已保存的登录信息进入"
                btn.setOnClickListener { enterWithSession(btn, error) }
                hint.visibility = View.GONE
            }
            // 3) 都没有：隐藏按钮与分隔线（维持现状）。
            else -> {
                btn.visibility = View.GONE
                divider.visibility = View.GONE
                hint.visibility = View.GONE
            }
        }
    }

    /**
     * 指纹验证。成功后的去向由 [bioHasSaved] 决定：
     * - 有会话：走 [enterWithSession]（写回会话 + 服务端校验，都通过才 finish）；
     * - 无会话（引导态）：提示先登录一次并把焦点交回用户名框。
     */
    private fun doBioAuth(btn: MaterialButton, error: TextView) {
        btn.isEnabled = false
        val onSuccess: () -> Unit = {
            if (bioHasSaved) {
                // 校验期间保持禁用，避免重复点按造成重复弹窗/重复校验
                enterWithSession(btn, error)
            } else {
                btn.isEnabled = true
                toast("还没有保存的登录信息,请先用账号密码登录一次")
                etUserView?.requestFocus()
            }
        }
        val onFail: (String) -> Unit = { msg ->
            btn.isEnabled = true
            // 单次按歪不关系统弹窗、用户还能重试,别把红字错误甩到登录页上;
            // 取消/锁定这类真正关闭弹窗的情况才提示。
            if (msg != BiometricGate.MSG_RETRY) {
                error.visibility = View.VISIBLE
                error.text = msg
            }
        }
        BiometricGate.authenticate(this, "生物识别登录", "验证后直接进入 LinNAS", onSuccess, onFail)
    }

    /**
     * 恢复已保存会话并进入主界面。与旧版「直接 finish」的区别：
     * 先把会话写回 ServerConfig，再调一次服务端 /api/auth/me 校验——
     * 旧版把过期 token 带回主界面，主界面第一发请求就 401 弹回登录页，
     * 用户看到的就是「指纹验证通过了却没进去」。
     */
    private fun enterWithSession(btn: MaterialButton, error: TextView) {
        lifecycleScope.launch {
            // 服务器地址没配：谈不上恢复会话，先引导设置地址并高亮「切换」
            if (ServerConfig.baseUrl.isBlank()) {
                btn.isEnabled = true
                toast("请先设置服务器地址")
                error.visibility = View.VISIBLE
                error.text = "请先设置服务器地址"
                highlightSwitchServer()
                return@launch
            }
            // 连加密会话都没有（升级前没登录过 / 已被清）：引导走账号密码
            if (!applySavedSession()) {
                btn.isEnabled = true
                toast("还没有保存的登录信息,请先用账号密码登录一次")
                etUserView?.requestFocus()
                return@launch
            }
            // 服务端校验：401/会话失效 → 清会话引导重登；纯网络问题 → 保留会话只提示重试
            // （Prefs.clearSession 的注释明确警告过：临时离线就清会话会让用户永久丢失指纹登录）
            var expired = false
            val ok = try {
                val me = withContext(Dispatchers.IO) { client.authMe() }
                if (me.username.isBlank()) expired = true
                me.username.isNotBlank()
            } catch (e: ApiException) {
                // API 错误（401 等）按会话过期处理
                expired = true
                false
            } catch (_: Exception) {
                false
            }
            btn.isEnabled = true
            if (ok) {
                setResult(RESULT_OK)
                finish()
                return@launch
            }
            if (expired) {
                // 真过期：清掉本地会话（含加密 token），按钮退回引导态
                Prefs.clearSessionMeta()
                Prefs.clearSession()
                ServerConfig.sessionToken = ""
                ServerConfig.sessionUser = ""
                ServerConfig.sessionRole = ""
                bioHasSaved = false
                error.visibility = View.VISIBLE
                error.text = "登录已过期,请重新输入密码"
                etUserView?.requestFocus()
                // 重绑按钮为「指纹登录」引导态，而不是继续指着一条死会话
                val cb = cbBioView
                val dv = bioDividerView
                if (cb != null && dv != null) setupBioEntry(btn, cb, dv, error)
            } else {
                error.visibility = View.VISIBLE
                error.text = "无法连接服务器,请确认服务端已启动后重试"
            }
        }
    }

    /** 让「切换」按钮脉冲两下，引导用户去设置服务器地址。 */
    private fun highlightSwitchServer() {
        val b = btnSwitchServerView ?: return
        ObjectAnimator.ofFloat(b, View.ALPHA, 1f, 0.2f, 1f, 0.2f, 1f).apply {
            duration = 1200
            start()
        }
    }

    /** 把加密保存的会话写回 ServerConfig（token 会重新激活）。 */
    private fun applySavedSession(): Boolean {
        val meta = Prefs.sessionMeta() ?: return false
        ServerConfig.sessionToken = meta[0]
        ServerConfig.sessionUser = meta[1]
        ServerConfig.sessionRole = meta[2]
        if (meta[3].isNotBlank()) ServerConfig.baseUrl = meta[3]
        ServerConfig.multiUser = true
        return true
    }

    /**
     * 「切换服务器」底部弹窗（Sheet）：手动输入 + 局域网自动发现。
     * 旧版是裸 AlertDialog，观感与其余二级弹窗不统一，这里用全局统一 Sheet 收口。
     */
    private fun showSwitchServerSheet(tv: TextView) {
        scanning = false
        val sheet = Sheet(this, "服务器地址", "手动输入,或在局域网内自动发现")
        // URI 键盘少误触空格/联想；必须带 TYPE_CLASS_TEXT，单独的 VARIATION 位无效
        sheet.input(
            "http://192.168.x.x:52700",
            ServerConfig.baseUrl,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        )
        fun save() {
            var addr = sheet.value(0)
            if (addr.isEmpty()) {
                sheet.inputView(0)?.error = "地址不能为空"
                return
            }
            // 缺协议自动补 http://（用户常只输 ip:port）
            if (!addr.startsWith("http://") && !addr.startsWith("https://")) addr = "http://$addr"
            // ServerConfig.baseUrl 的 setter 本身写 Prefs("base_url"),持久化路径与全局一致
            ServerConfig.baseUrl = addr
            tv.text = addr
            sheet.dismiss()
            toast("服务器地址已保存")
        }
        sheet.primary("保存") { save() }
        sheet.onEnter { save() }
        // 扫描行不关弹层：发现结果要就地列出来让用户挑
        sheet.row(R.drawable.ic_scan, "扫描局域网", "自动发现同一 WiFi 下的 LinNAS 服务器", dismissOnClick = false) {
            scanServersIntoSheet(sheet)
        }
        sheet.tonal("取消") { sheet.dismiss() }
        sheet.show()
    }

    /**
     * 局域网扫描，发现的服务器逐台列进弹层；点击只回填输入框，不关弹层。
     * Discovery.discover 的回调在 IO 线程，必须切回主线程再动 UI。
     */
    private fun scanServersIntoSheet(sheet: Sheet) {
        if (scanning) return
        scanning = true
        val status = TextView(this).apply {
            text = "正在扫描(广播 + mDNS + 网段扫描)…"
            setTextColor(ThemeColors.color(this@LoginActivity, R.attr.lTextDim))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(6))
        }
        sheet.customArea().addView(status)
        val found = HashSet<String>()
        lifecycleScope.launch {
            Discovery.discover { s ->
                runOnUiThread {
                    if (!sheet.isShowing()) return@runOnUiThread
                    if (!found.add(s.baseUrl)) return@runOnUiThread
                    status.text = "找到 ${found.size} 台服务器,点击填入"
                    sheet.row(R.drawable.ic_server, s.name.ifBlank { "LinNAS" } + " · " + s.ip, s.baseUrl, dismissOnClick = false) {
                        sheet.inputView(0)?.setText(s.baseUrl)
                    }
                }
            }
            runOnUiThread {
                scanning = false
                if (!sheet.isShowing()) return@runOnUiThread
                status.text = if (found.isEmpty()) {
                    "没找到服务器。确认电脑端已启动、手机连的是同一个 WiFi。"
                } else {
                    "扫描完成,共找到 ${found.size} 台服务器"
                }
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun roleLabel(role: String) = when (role) {
        "admin" -> "管理员"
        "viewer" -> "只读"
        else -> "普通用户"
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        private const val KEY_AUTO_LOGIN = "auto_login"
    }
}

/**
 * 会话恢复：进程启动时把「下次自动登录」的加密会话写回 ServerConfig，
 * 让 MainActivity 在判断是否需要登录之前就已经拿到 sessionToken。
 *
 * 由 [Prefs.init] 在进程首次初始化时调用（Prefs.init 是所有 Activity 的安全统一入口）。
 */
object SessionRestore {
    fun tryRestore(ctx: Context): Boolean {
        if (!Prefs.getBool("auto_login", false)) return false
        val meta = Prefs.sessionMeta() ?: return false
        ServerConfig.sessionToken = meta[0]
        ServerConfig.sessionUser = meta[1]
        ServerConfig.sessionRole = meta[2]
        if (meta[3].isNotBlank()) ServerConfig.baseUrl = meta[3]
        ServerConfig.multiUser = true
        return true
    }
}
