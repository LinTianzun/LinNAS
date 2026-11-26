package com.linnas.app

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
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
                    // 开启指纹时顺便加密记住账号密码（仅本地 Keystore 加密存储），
                    // 供退出登录后指纹登录换新 token。不打日志、不外传。
                    if (BiometricGate.isEnabled(this@LoginActivity) && BiometricGate.canUse(this@LoginActivity)) {
                        Prefs.saveBioCredentials(this@LoginActivity, u, p)
                    }
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
        // 「身份」与「指纹凭据」是两件事：
        // - hasBioCred：加密保存过账号密码 → 指纹按钮可真正换新 token 登录；
        // - hasSaved：存在已登录身份 sess_user（退出登录后仍保留）→ 无指纹时退化为「用已保存信息进入」。
        val hasBioCred = Prefs.hasBioCredentials(this)
        val bioUserName = Prefs.bioUser()
        val savedUser = Prefs.get("sess_user", "").takeIf { it.isNotBlank() }
        val hasSaved = savedUser != null
        val bioReady = BiometricGate.isEnabled(this) && BiometricGate.canUse(this)
        val hint = findViewById<TextView>(R.id.tvBioHint)

        cb.visibility = if (bioReady) View.VISIBLE else View.GONE

        when {
            // 1) 指纹可用（设置开启 + 硬件支持）→ 无条件显示，不看有没有已保存会话：
            //    老用户升级后没有加密会话，旧逻辑直接藏按钮，用户以为功能丢了。
            bioReady -> {
                btn.visibility = View.VISIBLE
                divider.visibility = View.VISIBLE
                btn.text = if (hasBioCred && !bioUserName.isNullOrBlank()) {
                    "指纹登录 · $bioUserName"
                } else {
                    "指纹登录"
                }
                btn.setOnClickListener { doBioAuth(btn, error) }
                // 有凭据 → 可直接指纹登录；没有 → 引导态，副标题提示先登录一次。
                hint.visibility = if (hasBioCred) View.GONE else View.VISIBLE
                // 仅当仍存在「已保存会话(token)」时才自动弹一次，避免刚退出登录就弹窗打扰。
                if (Prefs.sessionMeta() != null && !bioPrompted) {
                    bioPrompted = true
                    btn.post { doBioAuth(btn, error) }
                }
            }
            // 2) 指纹关闭/不可用但有已保存身份：不显示指纹，退化为用已保存信息进入。
            hasSaved -> {
                btn.visibility = View.VISIBLE
                divider.visibility = View.VISIBLE
                btn.text = if (savedUser.isNotBlank()) "使用已保存的登录信息进入 · $savedUser" else "使用已保存的登录信息进入"
                btn.setOnClickListener { lifecycleScope.launch { enterWithSession(btn, error) } }
                hint.visibility = View.GONE
            }
            // 3) 都没有：隐藏按钮与分隔线。
            else -> {
                btn.visibility = View.GONE
                divider.visibility = View.GONE
                hint.visibility = View.GONE
            }
        }
    }

    /**
     * 指纹验证 = 真正的「指纹登录」。成功后：
     * 1) 先试已保存会话（token）：[enterWithSession] 写回会话 + /api/auth/me 校验，通过即进入；
     * 2) token 不存在/已过期 → 用加密保存的账号密码换一个新 token 再进入；
     * 3) 没有保存的凭据 → 提示先登录一次并把焦点交回用户名框。
     *
     * 这样用户「退出登录」后（token 被清、身份与凭据仍在），点指纹仍能真正登录，
     * 不再出现「还没有保存登录信息」的死路。
     */
    private fun doBioAuth(btn: MaterialButton, error: TextView) {
        btn.isEnabled = false
        val onSuccess: () -> Unit = {
            // 验证通过后进入校验/换票流程；期间保持按钮禁用，避免重复点按造成重复登录
            lifecycleScope.launch {
                // 1) 先用已保存会话（token）尝试进入；silent=true 避免 token 失效时先闪「登录已过期」
                if (enterWithSession(btn, error, silent = true)) return@launch

                // 2) token 不存在/过期 → 用加密保存的账号密码换新 token
                val cred = Prefs.loadBioCredentials(this@LoginActivity)
                if (cred == null) {
                    btn.isEnabled = true
                    error.visibility = View.VISIBLE
                    error.text = "还没有保存登录信息,请先用账号密码登录一次"
                    etUserView?.requestFocus()
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    runCatching { client.login(cred.first, cred.second) }
                }
                val resp = result.getOrNull()
                if (resp != null) {
                    Prefs.saveSessionMeta(resp.token, resp.username, resp.role, ServerConfig.baseUrl)
                    Prefs.saveIdentity(resp.username, ServerConfig.baseUrl)
                    // 与 enterWithSession 一致：LoginActivity 是 MainActivity 的 for-result 子页，
                    // setResult(RESULT_OK)+finish 后由 MainActivity 的 loginLauncher 回调继续加载主流程。
                    setResult(RESULT_OK)
                    finish()
                    return@launch
                }
                btn.isEnabled = true
                error.visibility = View.VISIBLE
                if (result.exceptionOrNull() is ApiException) {
                    // 401 等 API 错误：保存的密码已失效，清凭据让用户重新用密码登录
                    Prefs.clearBioCredentials(this@LoginActivity)
                    error.text = "保存的密码已失效,请用密码重新登录"
                } else {
                    // 纯网络异常：不清凭据，稍后重试
                    error.text = "无法连接服务器,请确认服务端已启动后重试"
                }
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
     * 恢复已保存会话并进入主界面。返回 true 表示已成功进入并 finish。
     *
     * - silent=false（「使用已保存信息进入」按钮）：失败时把原因写到登录页红字并引导。
     * - silent=true（指纹登录的第一步）：token 不存在/过期/网络异常时只返回 false，
     *   不闪错误，交由 doBioAuth 接着用账号密码换新 token。
     */
    private suspend fun enterWithSession(btn: MaterialButton, error: TextView, silent: Boolean = false): Boolean {
        // 服务器地址没配：谈不上恢复会话，先引导设置地址并高亮「切换」
        if (ServerConfig.baseUrl.isBlank()) {
            btn.isEnabled = true
            if (!silent) {
                toast("请先设置服务器地址")
                error.visibility = View.VISIBLE
                error.text = "请先设置服务器地址"
                highlightSwitchServer()
            }
            return false
        }
        // 连加密会话都没有（升级前没登录过 / 已被清）：引导走账号密码
        if (!applySavedSession()) {
            btn.isEnabled = true
            if (!silent) {
                toast("还没有保存的登录信息,请先用账号密码登录一次")
                etUserView?.requestFocus()
            }
            return false
        }
        // 服务端校验：401/会话失效 → 清 token；纯网络问题 → 保留会话只提示重试
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
            return true
        }
        if (expired) {
            // 真过期：静默路径只清 token（保留身份与指纹凭据，供换票）；交互路径保持原引导行为
            if (silent) {
                Prefs.clearSessionToken()
            } else {
                Prefs.clearSessionMeta()
                Prefs.clearSession()
            }
            ServerConfig.sessionToken = ""
            ServerConfig.sessionUser = ""
            ServerConfig.sessionRole = ""
            if (!silent) {
                error.visibility = View.VISIBLE
                error.text = "登录已过期,请重新输入密码"
                etUserView?.requestFocus()
                // 重绑按钮为「指纹登录」引导态，而不是继续指着一条死会话
                val cb = cbBioView
                val dv = bioDividerView
                if (cb != null && dv != null) setupBioEntry(btn, cb, dv, error)
            }
        } else if (!silent) {
            error.visibility = View.VISIBLE
            error.text = "无法连接服务器,请确认服务端已启动后重试"
        }
        return false
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
