package com.linnas.app

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * M7 多用户登录:服务器开启多用户(users.json 非空)时,
 * 用账号密码登录拿会话 token;跳过则继续用访问口令(?token=)。
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
        val error = findViewById<TextView>(R.id.loginError)

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

    private fun roleLabel(role: String) = when (role) {
        "admin" -> "管理员"
        "viewer" -> "只读"
        else -> "普通用户"
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
}
