package com.linnas.app

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
 */
class LoginActivity : AppCompatActivity() {

    private val client = LinNasClient()

    /** 防止进入页面后重复自动弹指纹。 */
    private var bioPrompted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeStore.styleRes(this))
        super.onCreate(savedInstanceState)
        Prefs.init(applicationContext)
        setContentView(R.layout.activity_login)
        // manifest 未设 softInputMode：这里用 adjustResize，保证输入框获焦时不被软键盘遮挡。
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val etUser = findViewById<TextInputEditText>(R.id.etUser)
        val etPassword = findViewById<TextInputEditText>(R.id.etPassword)
        val btnLogin = findViewById<MaterialButton>(R.id.btnLogin)
        val btnSkip = findViewById<MaterialButton>(R.id.btnSkip)
        val btnBio = findViewById<MaterialButton>(R.id.btnBioLogin)
        val cbBio = findViewById<MaterialCheckBox>(R.id.cbBioLogin)
        val error = findViewById<TextView>(R.id.loginError)
        val tvServer = findViewById<TextView>(R.id.tvServer)
        val btnSwitchServer = findViewById<MaterialButton>(R.id.btnSwitchServer)
        val bioDivider = findViewById<View>(R.id.tvBioDivider)

        tvServer.text = ServerConfig.baseUrl.ifBlank { "未设置服务器地址" }
        btnSwitchServer.setOnClickListener { showSwitchServerDialog(tvServer) }

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
     * - 有生物识别且有已保存会话：显示大号指纹主按钮，并自动弹一次验证；
     * - 有生物识别但无已保存会话：隐藏；
     * - 无生物识别但有已保存会话：显示「使用已保存的登录信息进入」，直接进主页。
     */
    private fun setupBioEntry(btn: MaterialButton, cb: MaterialCheckBox, divider: View, error: TextView) {
        val meta = Prefs.sessionMeta()
        val hasSaved = meta != null
        val user = meta?.get(1)?.takeIf { it.isNotBlank() }

        if (BiometricGate.canUse(this)) {
            cb.visibility = View.VISIBLE
            if (hasSaved) {
                btn.visibility = View.VISIBLE
                divider.visibility = View.VISIBLE
                btn.text = if (user != null) "指纹登录 · $user" else "指纹登录"
                btn.setOnClickListener { doBioAuth(btn, error) }
                if (!bioPrompted) {
                    bioPrompted = true
                    btn.post { doBioAuth(btn, error) }
                }
            } else {
                btn.visibility = View.GONE
                divider.visibility = View.GONE
            }
        } else {
            cb.visibility = View.GONE
            if (hasSaved) {
                btn.visibility = View.VISIBLE
                divider.visibility = View.VISIBLE
                btn.text = if (user != null) "使用已保存的登录信息进入 · $user" else "使用已保存的登录信息进入"
                // 服务端若返回 401，MainActivity 会重新拉起登录页。
                btn.setOnClickListener { useSavedSession() }
            } else {
                btn.visibility = View.GONE
                divider.visibility = View.GONE
            }
        }
    }

    /** 指纹验证成功后恢复会话并结束。 */
    private fun doBioAuth(btn: MaterialButton, error: TextView) {
        btn.isEnabled = false
        val onSuccess: () -> Unit = {
            btn.isEnabled = true
            if (applySavedSession()) {
                setResult(RESULT_OK)
                finish()
            } else {
                toast("未找到已保存的会话,请用账号密码登录")
            }
        }
        val onFail: (String) -> Unit = { msg ->
            btn.isEnabled = true
            error.visibility = View.VISIBLE
            error.text = msg
        }
        BiometricGate.authenticate(this, "生物识别登录", "验证后直接进入 LinNAS", onSuccess, onFail)
    }

    /** 无生物识别时用已保存会话直接进入。 */
    private fun useSavedSession() {
        if (applySavedSession()) {
            setResult(RESULT_OK)
            finish()
        } else {
            toast("未找到已保存的会话,请用账号密码登录")
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

    /** 只读展示服务器地址 + 「切换」：这里用简单输入框修改 baseUrl。 */
    private fun showSwitchServerDialog(tv: TextView) {
        val input = EditText(this).apply {
            setText(ServerConfig.baseUrl)
            setHint("http://192.168.x.x:52700")
            setSingleLine(true)
            setPadding(48, 32, 48, 8)
        }
        AlertDialog.Builder(this)
            .setTitle("服务器地址")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val v = input.text.toString().trim()
                ServerConfig.baseUrl = v
                tv.text = v.ifBlank { "未设置服务器地址" }
            }
            .setNegativeButton("取消", null)
            .show()
    }

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
