package com.linnas.app

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * v1.7.0 管理页统一脚手架:顶栏(返回 + 标题 + 右侧动作) + 可滚动内容区。
 * 会话管理 / 用户管理 / 维护与索引 / 网络唤醒 / 存储库管理 都复用它,
 * 避免为每个小页面新增 XML 布局。
 */
abstract class ScreenScaffold : AppCompatActivity() {

    protected val client = LinNasClient()
    protected lateinit var content: LinearLayout
    private lateinit var actionBarRow: LinearLayout

    /** 页面标题。 */
    protected abstract fun title(): String

    /** 构建页面内容(由子类实现,通常直接调用 load())。 */
    protected abstract fun onCreateScreen()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeStore.styleRes(this))
        super.onCreate(savedInstanceState)
        Prefs.init(applicationContext)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeColors.color(this@ScreenScaffold, R.attr.lInk))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        bar.addView(Button(this).apply {
            text = "‹ 返回"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(TextView(this).apply {
            text = title()
            textSize = 18f
            setTextColor(ThemeColors.text(this@ScreenScaffold))
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionBarRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(actionBarRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(bar)

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(16))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        onCreateScreen()
    }

    /** 顶栏右侧加动作按钮。 */
    protected fun addHeaderAction(label: String, onClick: () -> Unit) {
        actionBarRow.addView(Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        })
    }

    protected fun clearContent() = content.removeAllViews()

    /** 加一行(主标题 + 可选副标题);可点/长按。 */
    protected fun addRow(
        title: String,
        subtitle: String = "",
        onClick: (() -> Unit)? = null,
        onLongClick: (() -> Unit)? = null,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
            if (onClick != null) {
                isClickable = true
                setOnClickListener { onClick() }
            }
            if (onLongClick != null) {
                isLongClickable = true
                setOnLongClickListener { onLongClick(); true }
            }
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(ThemeColors.text(this@ScreenScaffold))
        })
        if (subtitle.isNotBlank()) {
            row.addView(TextView(this).apply {
                text = subtitle
                textSize = 12f
                setTextColor(ThemeColors.textDim(this@ScreenScaffold))
                setPadding(0, dp(2), 0, 0)
            })
        }
        content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addDivider()
        return row
    }

    protected fun addDivider() {
        content.addView(
            View(this).apply {
                setBackgroundColor(ThemeColors.color(this@ScreenScaffold, R.attr.lOutline))
                alpha = 0.25f
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1),
        )
    }

    protected fun addText(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(ThemeColors.textDim(this@ScreenScaffold))
            setPadding(0, dp(6), 0, dp(6))
        })
    }

    protected fun addButton(label: String, onClick: () -> Unit) {
        content.addView(Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
    }

    protected fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    protected fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    /** 时间戳(秒)→ "MM-dd HH:mm";0 返回 "-"。 */
    protected fun formatTs(sec: Long): String =
        if (sec <= 0) "-" else java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(sec * 1000))

    protected fun roleLabel(role: String) = when (role) {
        "admin" -> "管理员"
        "viewer" -> "只读"
        else -> "普通用户"
    }
}
