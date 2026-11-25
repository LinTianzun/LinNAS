package com.linnas.app

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * M7 分享管理:列出 /api/shares 的分享链接,可新建(填写路径/有效期/密码/下载权限)、
 * 复制链接、看二维码、取消。
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
                toast("读取失败: ${e.message}"); return@launch
            }
            list.removeAllViews()
            if (items.isEmpty()) {
                hint.text = "还没有分享链接,点右上角「新建」"
                return@launch
            }
            hint.text = "共 ${items.size} 个分享链接"
            for (s in items) {
                val url = shareUrl(s.id)
                val row = layoutInflater.inflate(R.layout.item_file, list, false)
                row.findViewById<ImageView>(R.id.ficon).setImageResource(R.drawable.ic_link)
                row.findViewById<ImageView>(R.id.ficon).imageTintList =
                    ThemeColors.csl(this@SharesActivity, R.attr.lPrimary)
                row.findViewById<TextView>(R.id.fname).text = s.name.ifBlank { s.vpath }
                row.findViewById<TextView>(R.id.fmeta).text = buildString {
                    append(if (s.expiresAt > 0) "${formatDate(s.expiresAt)} 过期" else "永久")
                    if (s.hasPassword) append("  ·  密码保护")
                    if (s.allowDownload) append("  ·  可下载")
                }
                row.setOnClickListener { showShareDialog(s, url) }
                row.findViewById<View>(R.id.fmore).setOnClickListener { anchor ->
                    android.widget.PopupMenu(this@SharesActivity, anchor).apply {
                        menu.add(0, 1, 0, "复制链接")
                        menu.add(0, 2, 1, "取消分享")
                        setOnMenuItemClickListener { mi ->
                            when (mi.itemId) {
                                1 -> { copyUrl(url); toast("链接已复制") }
                                2 -> confirmDelete(s)
                            }
                            true
                        }
                        show()
                    }
                }
                list.addView(row)
            }
        }
    }

    private fun shareUrl(id: String): String =
        ServerConfig.baseUrl.trimEnd('/') + "/s/" + id

    private fun showShareDialog(s: Share, url: String) {
        val scroll = android.widget.ScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), 0) }
        box.addView(TextView(this).apply {
            text = url
            setTextColor(ThemeColors.textDim(this@SharesActivity)); textSize = 12f
            maxLines = 3
        })
        box.addView(TextView(this).apply {
            text = "密码:${if (s.hasPassword) "已设置" else "无"}  下载:${if (s.allowDownload) "允许" else "禁止"}"
            setTextColor(ThemeColors.textDim(this@SharesActivity)); textSize = 12f
        })
        val qrView = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(260, 260) }
        val bmp = QrCode.make(url, 520)
        if (bmp != null) qrView.setImageBitmap(bmp) else box.addView(
            TextView(this).apply {
                text = "(二维码生成失败,可用「复制链接」)"; setTextColor(ThemeColors.textDim(this@SharesActivity))
            }
        )
        box.addView(qrView)
        scroll.addView(box)
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(scroll) }

        val dlg = AlertDialog.Builder(this).setTitle("分享链接")
            .setPositiveButton("复制链接") { _, _ -> copyUrl(url); toast("链接已复制") }
            .setNegativeButton("关闭", null).setView(wrap).show()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { copyUrl(url); toast("链接已复制") }
        }
    }

    private fun promptCreate(initialVpath: String = "") {
        val vp = TextInputEditText(this).apply {
            hint = "虚拟路径(如 /movie/intro.mkv)"
            setText(initialVpath)
        }
        val hours = TextInputEditText(this).apply {
            hint = "有效期(小时,留空=永久)"; inputType = InputType.TYPE_CLASS_NUMBER
        }
        val pw = TextInputEditText(this).apply {
            hint = "访问密码(留空=无)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val dl = CheckBox(this).apply { text = "允许下载" }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), 0)
            addView(vp); addView(hours); addView(pw); addView(dl)
        }
        AlertDialog.Builder(this).setTitle("新建分享链接")
            .setView(wrap)
            .setPositiveButton("创建", null)
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val vpath = vp.text.toString().trim()
                        if (vpath.isEmpty()) { toast("请填写路径"); return@setOnClickListener }
                        lifecycleScope.launch {
                            try {
                                val hoursVal = hours.text.toString().trim().toIntOrNull()?.takeIf { it > 0 } ?: 0
                                val s = withContext(Dispatchers.IO) {
                                    client.createShare(vpath, hoursVal, pw.text.toString(), dl.isChecked)
                                }
                                toast("已创建分享:${s.name}")
                                loadList()
                            } catch (e: Exception) { toast("创建失败: ${e.message}") }
                        }
                    }
                }
                show()
            }
    }

    private fun confirmDelete(s: Share) {
        AlertDialog.Builder(this).setTitle("取消分享")
            .setMessage("取消「${s.name}」的分享链接?链接将立即失效。")
            .setPositiveButton("取消分享") { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { client.deleteShare(s.id) }
                        toast("已取消分享")
                        loadList()
                    } catch (e: Exception) { toast("操作失败: ${e.message}") }
                }
            }
            .setNegativeButton("保留", null).show()
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
