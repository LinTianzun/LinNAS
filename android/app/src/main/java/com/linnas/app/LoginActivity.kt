package com.linnas.app

import android.os.Bundle
import android.view.View
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
 * M7 多用户登录:服务器开启多用户(users.json 非空)时,
 * 用账号密码登录拿会话 token;跳过则继续用访问口令(?token=)。
 * 另支持生物识别(指纹/面容)直接登录:密码登录成功后可勾选加密保存会话。
 */
class LoginActivity : AppCompatActivity() {

    private val client = LinNasClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeStore.styleRes(this))
        super.onCreate(savedInstanceState)
        Prefs.init(applicationContext)
        setContentView(R.layout.activity_login)

        val etUser = findViewById<TextInputEditText>(R.id.etUser)
        val etPassword = findViewById<TextInputEditText>(R.id.etPassword)
        val btnLogin = findViewById<MaterialButton>(R.id.btnLogin)
        val btnSkip = findViewById<MaterialButton>(R.id.btnSkip)
        val btnBio = findViewById<MaterialButton>(R.id.btnBioLogin)
        val cbBio = findViewById<MaterialCheckBox>(R.id.cbBioLogin)
        val error = findViewById<TextView>(R.id.loginError)

        // 设备不支持生物识别则隐藏相关控件。
        if (BiometricGate.canUse(this)) {
            refreshBioButton(btnBio)
        } else {
            btnBio.visibility = View.GONE
            cbBio.visibility = View.GONE
        }

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
                    saveSessionForBio(cbBio)
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

        btnBio.setOnClickListener { doBioLogin(btnBio, error) }
    }

    /** 若已保存加密会话则显示生物识别登录按钮,否则隐藏。 */
    private fun refreshBioButton(btn: MaterialButton) {
        val saved = BiometricGate.loadSecret(this, KEY_SESSION_TOKEN)
        btn.visibility = if (!saved.isNullOrBlank()) View.VISIBLE else View.GONE
    }

    /** 密码登录成功后,按勾选保存/清除加密会话(token+用户名+角色+baseUrl)。 */
    private fun saveSessionForBio(cb: MaterialCheckBox) {
        if (!cb.isChecked) {
            BiometricGate.clearSecret(this, KEY_SESSION_TOKEN)
            BiometricGate.clearSecret(this, KEY_SESSION_USER)
            BiometricGate.clearSecret(this, KEY_SESSION_ROLE)
            BiometricGate.clearSecret(this, KEY_BASE_URL)
            return
        }
        if (!BiometricGate.canUse(this)) {
            toast("当前设备不支持生物识别,未保存")
            return
        }
        BiometricGate.saveSecret(this, KEY_SESSION_TOKEN, ServerConfig.sessionToken)
        BiometricGate.saveSecret(this, KEY_SESSION_USER, ServerConfig.sessionUser)
        BiometricGate.saveSecret(this, KEY_SESSION_ROLE, ServerConfig.sessionRole)
        BiometricGate.saveSecret(this, KEY_BASE_URL, ServerConfig.baseUrl)
    }

    /** 生物识别登录:验证成功后恢复 ServerConfig 会话并结束。 */
    private fun doBioLogin(btn: MaterialButton, error: TextView) {
        btn.isEnabled = false
        val onSuccess: () -> Unit = {
            val token = BiometricGate.loadSecret(this, KEY_SESSION_TOKEN)
            if (token.isNullOrBlank()) {
                btn.isEnabled = true
                toast("未找到已保存的生物识别会话,请用密码登录")
            } else {
                ServerConfig.sessionToken = token
                ServerConfig.sessionUser = BiometricGate.loadSecret(this, KEY_SESSION_USER) ?: ""
                ServerConfig.sessionRole = BiometricGate.loadSecret(this, KEY_SESSION_ROLE) ?: ""
                val base = BiometricGate.loadSecret(this, KEY_BASE_URL)
                if (!base.isNullOrBlank()) ServerConfig.baseUrl = base
                ServerConfig.multiUser = true
                setResult(RESULT_OK)
                finish()
            }
        }
        val onFail: (String) -> Unit = { msg ->
            btn.isEnabled = true
            error.visibility = View.VISIBLE
            error.text = msg
        }
        BiometricGate.authenticate(this, "生物识别登录", "验证后直接登录 linNas", onSuccess, onFail)
    }

    private fun roleLabel(role: String) = when (role) {
        "admin" -> "管理员"
        "viewer" -> "只读"
        else -> "普通用户"
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        private const val KEY_SESSION_TOKEN = "bio_session_token"
        private const val KEY_SESSION_USER = "bio_session_user"
        private const val KEY_SESSION_ROLE = "bio_session_role"
        private const val KEY_BASE_URL = "bio_base_url"
    }
}
