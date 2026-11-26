package com.linnas.app

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * M7 分享管理:列出 /api/shares 的分享链接,可新建(填写路径/有效期/密码/下载权限)、
 * 复制链接、看二维码、取消。
 *
 * v1.8:列表行改用统一的圆角卡片行(ScreenUi.row),空态用 bg_empty 插图。
 */
class SharesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INITIAL_VPATH = "initial_vpath"
    }

    private val client = LinNasClient()
    private lateinit var list: LinearLayout
    private lateinit var hint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeStore.styleRes(this))
        super.onCreate(savedInstanceState)
        Prefs.init(applicationContext)
        setContentView(R.layout.activity_shares)
        list = findViewById(R.id.sharesList)
        hint = findViewById(R.id.sharesHint)

        findViewById<View>(R.id.btnSharesBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSharesNew).setOnClickListener {
            if (!canWrite()) { toast("只读用户无法创建分享"); return@setOnClickListener }
            promptCreate(initialVpath())
        }
        val initial = initialVpath()
        if (!initial.isBlank() && canWrite()) {
            promptCreate(initial)
        }
        loadList()
    }

    private fun initialVpath(): String =
        intent.getStringExtra(EXTRA_INITIAL_VPATH)?.trim().orEmpty()

    private fun loadList() {
        lifecycleScope.launch {
            val items = try {
                withContext(Dispatchers.IO) { client.shares() }
            } catch (e: Exception) {
                toast("读取失败: ${e.message}")
                list.removeAllViews()
                list.addView(
                    ScreenUi.emptyState(this@SharesActivity, "读取失败", e.message ?: "无法获取分享列表"),
                )
                return@launch
            }
            list.removeAllViews()
            if (items.isEmpty()) {
                hint.text = "还没有分享链接,点右上角「新建」"
                list.addView(
                    ScreenUi.emptyState(this@SharesActivity, "还没有分享链接", "点右上角「新建」创建一个分享链接"),
                )
                return@launch
            }
            hint.text = "共 ${items.size} 个分享链接"
            for (s in items) {
                val url = shareUrl(s.id)
                val meta = buildString {
                    append(s.vpath)
                    append("  ·  ")
                    append(if (s.expiresAt > 0) "${formatDate(s.expiresAt)} 过期" else "永久")
                    if (s.hasPassword) append("  ·  密码保护")
                    if (s.allowDownload) append("  ·  可下载")
                }
                list.addView(
                    ScreenUi.row(
                        this@SharesActivity,
                        R.drawable.ic_link,
                        s.name.ifBlank { s.vpath },
                        meta,
                        value = if (s.expiresAt > 0) formatDate(s.expiresAt) else "永久",
                        onClick = { showShareDialog(s, url) },
                        more = { _ -> showMoreMenu(s, url) },
                    ),
                )
            }
        }
    }

    /** 行尾「更多」按钮菜单:复制链接 / 取消分享。 */
    private fun showMoreMenu(s: Share, url: String) {
        // 用底部 Sheet 替代系统弹出菜单,与其他二级页统一主题化观感
        val sheet = Sheet(this, s.name.ifBlank { s.vpath })
        sheet.row(R.drawable.ic_link, "复制链接") { copyUrl(url); toast("链接已复制") }
        sheet.row(R.drawable.ic_close, "取消分享", destructive = true) { confirmDelete(s) }
        sheet.show()
    }

    private fun shareUrl(id: String): String =
        ServerConfig.baseUrl.trimEnd('/') + "/s/" + id

    private fun showShareDialog(s: Share, url: String) {
        val sheet = Sheet(this, "分享链接")
        sheet.text(url)
        sheet.keyValue("密码", if (s.hasPassword) "已设置" else "无")
        sheet.keyValue("下载", if (s.allowDownload) "允许" else "禁止")
        val bmp = QrCode.make(url, 520)
        if (bmp != null) {
            val area = sheet.customArea().apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }
            area.addView(
                ImageView(this).apply {
                    setImageBitmap(bmp)
                    contentDescription = "分享二维码"
                },
                LinearLayout.LayoutParams(dp(200), dp(200)).apply { topMargin = dp(8) },
            )
        } else {
            sheet.text("(二维码生成失败,可用「复制链接」)")
        }
        // 「复制链接」不关闭弹层,便于同时扫码;与旧版行为一致
        sheet.primary("复制链接") { copyUrl(url); toast("链接已复制") }
        sheet.tonal("关闭") { sheet.dismiss() }
        sheet.show()
    }

    private fun promptCreate(initialVpath: String = "") {
        val sheet = Sheet(this, "新建分享链接")
        sheet.input("虚拟路径(如 /movie/intro.mkv)", initialVpath)
        sheet.input("有效期(小时,留空=永久)", "", InputType.TYPE_CLASS_NUMBER)
        sheet.input("访问密码(留空=无)", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        // 用分段选择器替代「允许下载」复选框,默认禁止下载
        var allowDownload = false
        sheet.segmented(listOf("禁止下载", "允许下载"), 0) { i -> allowDownload = i == 1 }
        sheet.primary("创建") {
            val vpath = sheet.value(0)
            if (vpath.isEmpty()) {
                sheet.inputView(0)?.error = "请填写路径"
                toast("请填写路径")
                return@primary
            }
            lifecycleScope.launch {
                try {
                    val hoursVal = sheet.value(1).toIntOrNull()?.takeIf { it > 0 } ?: 0
                    val s = withContext(Dispatchers.IO) {
                        client.createShare(vpath, hoursVal, sheet.inputView(2)?.text?.toString().orEmpty(), allowDownload)
                    }
                    sheet.dismiss()
                    toast("已创建分享:${s.name}")
                    loadList()
                } catch (e: Exception) { toast("创建失败: ${e.message}") }
            }
        }
        sheet.tonal("取消") { sheet.dismiss() }
        sheet.show()
    }

    private fun confirmDelete(s: Share) {
        val sheet = Sheet(this, "取消分享", "取消「${s.name}」的分享链接?链接将立即失效。")
        sheet.danger("取消分享") {
            sheet.dismiss()
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { client.deleteShare(s.id) }
                    toast("已取消分享")
                    loadList()
                } catch (e: Exception) { toast("操作失败: ${e.message}") }
            }
        }
        sheet.tonal("保留") { sheet.dismiss() }
        sheet.show()
    }

    private fun copyUrl(url: String) {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("linNas分享", url))
    }

    private fun formatDate(unix: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(unix * 1000))

    private fun canWrite(): Boolean = ServerConfig.sessionRole != "viewer"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
}
