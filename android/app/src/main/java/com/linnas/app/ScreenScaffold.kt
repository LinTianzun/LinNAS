package com.linnas.app

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * v1.8 二级页通用视觉构件(纯代码生成视图,无 Compose)。
 *
 * 放在本文件里,与 [ScreenScaffold] 同属一个可改文件,方便非脚手架子页
 * (操作日志 / 分享管理)复用同一套样式,保证「二级页视觉统一」。
 *
 * 所有颜色走语义属性(?attr/lInk / lSurface / lOutline / lText / lTextDim /
 * lPrimary / lOnPrimary / lDanger / lSuccess),尺寸走 dimens 资源,不写死色值。
 */
object ScreenUi {

    /** dp → px。 */
    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /** 主题里的无边框按压背景(顶栏文字按钮用);取不到时返回 null。 */
    fun borderlessBackground(ctx: Context): android.graphics.drawable.Drawable? {
        val ta = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        val d = ta.getDrawable(0)
        ta.recycle()
        return d
    }

    /** @dimen 里的 dp 尺寸 → px。 */
    fun dim(ctx: Context, resId: Int): Int = ctx.resources.getDimensionPixelSize(resId)

    /** @dimen 里的 sp 字号 → sp 数值(交给 setTextSize 走系统字体缩放)。 */
    fun sp(ctx: Context, resId: Int): Float {
        val px = ctx.resources.getDimension(resId)
        val scaled = ctx.resources.configuration.fontScale *
            (ctx.resources.displayMetrics.densityDpi / 160f)
        return if (scaled > 0f) px / scaled else px
    }

    fun text(
        ctx: Context,
        value: String,
        sizeRes: Int,
        colorAttr: Int,
        bold: Boolean = false,
        maxLines: Int = Int.MAX_VALUE,
        ellipsize: Boolean = false,
        mono: Boolean = false,
    ): TextView = TextView(ctx).apply {
        text = value
        setTextColor(ThemeColors.color(ctx, colorAttr))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(ctx, sizeRes))
        if (bold) setTypeface(null, Typeface.BOLD)
        if (mono) typeface = Typeface.MONOSPACE
        if (maxLines != Int.MAX_VALUE) {
            this.maxLines = maxLines
            if (ellipsize) this.ellipsize = android.text.TextUtils.TruncateAt.END
        }
    }

    /** 42dp 圆形图标底 + 24dp 线性图标。 */
    fun iconTile(ctx: Context, iconRes: Int, tintAttr: Int = R.attr.lPrimary): FrameLayout {
        val tile = FrameLayout(ctx).apply { setBackgroundResource(R.drawable.bg_icon_circle) }
        tile.addView(
            ImageView(ctx).apply {
                setImageResource(iconRes)
                imageTintList = ThemeColors.csl(ctx, tintAttr)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = ""
            },
            FrameLayout.LayoutParams(dim(ctx, R.dimen.icon_lg), dim(ctx, R.dimen.icon_lg), Gravity.CENTER),
        )
        return tile
    }

    /**
     * 圆角卡片行:图标底 + 标题 + 副标题 + 可选值 + 可选 chevron。
     * onClick / onLongClick 非空时才可点,并加 bg_ripple 按压反馈。
     */
    fun row(
        ctx: Context,
        iconRes: Int,
        title: String,
        subtitle: String = "",
        value: String = "",
        tintAttr: Int = R.attr.lPrimary,
        onClick: (() -> Unit)? = null,
        onLongClick: (() -> Unit)? = null,
        more: ((View) -> Unit)? = null,
    ): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dim(ctx, R.dimen.row_min_height)
            setBackgroundResource(R.drawable.bg_card)
            foreground = ctx.getDrawable(R.drawable.bg_ripple)
            setPadding(
                dim(ctx, R.dimen.card_padding),
                dim(ctx, R.dimen.space_3),
                dim(ctx, R.dimen.space_3),
                dim(ctx, R.dimen.space_3),
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dim(ctx, R.dimen.space_2) }
            if (onClick != null) {
                isClickable = true
                setOnClickListener { onClick() }
            }
            if (onLongClick != null) {
                isLongClickable = true
                setOnLongClickListener { onLongClick(); true }
            }
        }

        card.addView(iconTile(ctx, iconRes, tintAttr))

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dim(ctx, R.dimen.space_3)
            }
        }
        col.addView(
            text(ctx, title, R.dimen.text_md, R.attr.lText, maxLines = 1, ellipsize = true).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            },
        )
        if (subtitle.isNotBlank()) {
            col.addView(
                text(ctx, subtitle, R.dimen.text_caption, R.attr.lTextDim, maxLines = 2).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dim(ctx, R.dimen.space_1) }
                },
            )
        }
        card.addView(col)

        if (value.isNotBlank()) {
            card.addView(
                text(ctx, value, R.dimen.text_caption, R.attr.lTextDim, maxLines = 1, ellipsize = true).apply {
                    gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginStart = dim(ctx, R.dimen.space_2) }
                },
            )
        }

        if (more != null) {
            val moreBtn = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_more)
                imageTintList = ThemeColors.csl(ctx, R.attr.lTextDim)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dim(ctx, R.dimen.space_2), dim(ctx, R.dimen.space_2), dim(ctx, R.dimen.space_2), dim(ctx, R.dimen.space_2))
                contentDescription = "更多操作"
                setOnClickListener { more(this) }
            }
            card.addView(
                moreBtn,
                LinearLayout.LayoutParams(dim(ctx, R.dimen.touch_target), dim(ctx, R.dimen.touch_target)).apply {
                    marginStart = dim(ctx, R.dimen.space_1)
                },
            )
        } else if (onClick != null) {
            card.addView(
                ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_chevron)
                    imageTintList = ThemeColors.csl(ctx, R.attr.lTextDim)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = ""
                },
                LinearLayout.LayoutParams(dim(ctx, R.dimen.icon_md), dim(ctx, R.dimen.icon_md)).apply {
                    marginStart = dim(ctx, R.dimen.space_2)
                },
            )
        }
        return card
    }

    /** 分区小标题:主色 + 前置小圆点。 */
    fun sectionHeader(ctx: Context, label: String): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dim(ctx, R.dimen.space_6)
                bottomMargin = dim(ctx, R.dimen.space_2)
            }
        }
        row.addView(
            View(ctx).apply { setBackgroundResource(R.drawable.bg_dot) },
            LinearLayout.LayoutParams(dim(ctx, R.dimen.space_2), dim(ctx, R.dimen.space_2)).apply {
                marginEnd = dim(ctx, R.dimen.space_2)
            },
        )
        row.addView(
            text(ctx, label, R.dimen.text_sm, R.attr.lPrimary, bold = true).apply {
                letterSpacing = 0.02f
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        return row
    }

    /** 卡片容器(扁平:0dp 投影 + 1dp 描边)。 */
    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        setPadding(
            dim(ctx, R.dimen.card_padding),
            dim(ctx, R.dimen.space_3),
            dim(ctx, R.dimen.card_padding),
            dim(ctx, R.dimen.space_3),
        )
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dim(ctx, R.dimen.space_3) }
    }

    /**
     * 卡片头部:圆形图标底 + 标题 + 副标题(横向),用于「索引状态 / 清理 / 设置」这类分组卡片。
     */
    fun cardHeader(ctx: Context, iconRes: Int, title: String, subtitle: String = ""): LinearLayout {
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        head.addView(iconTile(ctx, iconRes))
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dim(ctx, R.dimen.space_3)
            }
        }
        col.addView(
            text(ctx, title, R.dimen.text_md, R.attr.lText, bold = true, maxLines = 1, ellipsize = true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        if (subtitle.isNotBlank()) {
            col.addView(
                text(ctx, subtitle, R.dimen.text_caption, R.attr.lTextDim, maxLines = 2).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dim(ctx, R.dimen.space_1) }
                },
            )
        }
        head.addView(col)
        return head
    }

    /** 卡片内的键值行:左标签(次级色)+ 右值(主文本 / 可选等宽)。 */
    fun keyValue(ctx: Context, label: String, value: String, mono: Boolean = false): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dim(ctx, R.dimen.space_2) }
        }
        row.addView(
            text(ctx, label, R.dimen.text_caption, R.attr.lTextDim, maxLines = 1),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            text(ctx, value, R.dimen.text_sm, R.attr.lText, maxLines = 2, ellipsize = true, mono = mono).apply {
                gravity = Gravity.END
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dim(ctx, R.dimen.space_3) },
        )
        return row
    }

    /** 次级说明文字。 */
    fun note(ctx: Context, value: String): TextView = text(ctx, value, R.dimen.text_sm, R.attr.lTextDim).apply {
        setPadding(0, dim(ctx, R.dimen.space_2), 0, dim(ctx, R.dimen.space_2))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    /** 空状态:bg_empty 插图占位 + 标题 + 提示。 */
    fun emptyState(ctx: Context, title: String, hint: String): LinearLayout {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dim(ctx, R.dimen.space_8), 0, dim(ctx, R.dimen.space_8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        box.addView(
            ImageView(ctx).apply {
                setImageResource(R.drawable.bg_empty)
                contentDescription = "暂无数据"
            },
            LinearLayout.LayoutParams(
                dim(ctx, R.dimen.screen_empty_illustration),
                dim(ctx, R.dimen.screen_empty_illustration),
            ),
        )
        box.addView(
            text(ctx, title, R.dimen.text_md, R.attr.lText, bold = true).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dim(ctx, R.dimen.space_4) }
            },
        )
        box.addView(
            text(ctx, hint, R.dimen.text_sm, R.attr.lTextDim).apply {
                gravity = Gravity.CENTER
                setPadding(dim(ctx, R.dimen.space_4), 0, dim(ctx, R.dimen.space_4), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dim(ctx, R.dimen.space_2) }
            },
        )
        return box
    }

    /** 主按钮(主色填充)。 */
    fun primaryButton(ctx: Context, label: String, onClick: () -> Unit): Button =
        button(ctx, label, R.attr.lOnPrimary, R.drawable.bg_button_primary, onClick)

    /** 次级按钮(表面色 + 描边)。 */
    fun tonalButton(ctx: Context, label: String, onClick: () -> Unit): Button =
        button(ctx, label, R.attr.lText, R.drawable.bg_button_tonal, onClick)

    /** 危险按钮(次级底 + 危险色文字)。 */
    fun dangerButton(ctx: Context, label: String, onClick: () -> Unit): Button =
        button(ctx, label, R.attr.lDanger, R.drawable.bg_button_tonal, onClick)

    /** 顶栏文字按钮(主色文字 + 无边框按压背景)。 */
    fun headerTextButton(ctx: Context, label: String, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            setTextColor(ThemeColors.color(ctx, R.attr.lPrimary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(ctx, R.dimen.text_sm))
            minWidth = 0
            minHeight = 0
            backgroundTintList = null
            stateListAnimator = null
            setPadding(dim(ctx, R.dimen.space_3), 0, dim(ctx, R.dimen.space_3), 0)
            borderlessBackground(ctx)?.let { setBackground(it) }
            setOnClickListener { onClick() }
        }

    private fun button(
        ctx: Context,
        label: String,
        colorAttr: Int,
        bgRes: Int,
        onClick: () -> Unit,
    ): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        setTextColor(ThemeColors.color(ctx, colorAttr))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(ctx, R.dimen.text_sm))
        setTypeface(null, Typeface.BOLD)
        minHeight = 0
        minWidth = 0
        stateListAnimator = null
        // Material3 主题会给 Button 套 backgroundTint,会盖掉 bg_button_* 的语义色,
        // 这里清掉,让 drawable 里的 ?attr/lPrimary / lSurfaceHigh 生效。
        backgroundTintList = null
        setBackgroundResource(bgRes)
        setPadding(
            dim(ctx, R.dimen.space_4),
            0,
            dim(ctx, R.dimen.space_4),
            0,
        )
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dim(ctx, R.dimen.button_height),
        ).apply { topMargin = dim(ctx, R.dimen.space_2) }
    }
}

/**
 * v1.7.0 管理页统一脚手架:顶栏(返回 + 标题 + 右侧动作) + 可滚动内容区。
 * v1.8 按 DESIGN.md 重做:文字返回按钮 + 单行省略标题 + 分隔线 + 卡片行内容区。
 *
 * 会话管理 / 用户管理 / 维护与索引 / 网络唤醒 / 存储库管理 / 版本检查 都复用它,
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

        // ---- 顶栏:左侧「返回」+ 中间标题 + 右侧动作 ----
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        bar.addView(
            ScreenUi.headerTextButton(this, "返回") { finish() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        bar.addView(
            ScreenUi.text(this, title(), R.dimen.text_lg, R.attr.lText, bold = true, maxLines = 1, ellipsize = true).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(4), 0)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        actionBarRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(actionBarRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 顶栏下分隔线
        root.addView(
            View(this).apply { setBackgroundResource(R.drawable.bg_divider) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                setMargins(dp(16), 0, dp(16), 0)
            },
        )

        // ---- 内容区 ----
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        onCreateScreen()
    }

    /** 顶栏右侧加动作按钮(主色文字按钮)。 */
    protected fun addHeaderAction(label: String, onClick: () -> Unit) {
        actionBarRow.addView(
            ScreenUi.headerTextButton(this, label, onClick),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    protected fun clearContent() = content.removeAllViews()

    /** 分区小标题(主色 + 小圆点)。 */
    protected fun addSectionHeader(text: String) {
        content.addView(ScreenUi.sectionHeader(this, text))
    }

    /**
     * 圆角卡片行:左侧圆形图标底 + 标题 + 副标题 + 右侧值 + chevron。
     */
    protected fun addIconRow(
        iconRes: Int,
        title: String,
        subtitle: String = "",
        value: String = "",
        tintAttr: Int = R.attr.lPrimary,
        onClick: (() -> Unit)? = null,
        onLongClick: (() -> Unit)? = null,
    ): LinearLayout {
        val row = ScreenUi.row(
            this,
            iconRes,
            title,
            subtitle,
            value,
            tintAttr,
            onClick,
            onLongClick,
        )
        content.addView(row)
        return row
    }

    /** 加一行(主标题 + 可选副标题);可点/长按。 */
    protected fun addRow(
        title: String,
        subtitle: String = "",
        onClick: (() -> Unit)? = null,
        onLongClick: (() -> Unit)? = null,
    ): LinearLayout = addIconRow(
        R.drawable.ic_info,
        title,
        subtitle,
        onClick = onClick,
        onLongClick = onLongClick,
    )

    /** 分隔线。 */
    protected fun addDivider() {
        content.addView(
            View(this).apply { setBackgroundResource(R.drawable.bg_divider) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            },
        )
    }

    /** 次级说明文字。 */
    protected fun addText(text: String) {
        content.addView(ScreenUi.note(this, text))
    }

    /** 主按钮。 */
    protected fun addButton(label: String, onClick: () -> Unit) {
        content.addView(ScreenUi.primaryButton(this, label, onClick))
    }

    /** 次级按钮。 */
    protected fun addTonalButton(label: String, onClick: () -> Unit) {
        content.addView(ScreenUi.tonalButton(this, label, onClick))
    }

    /** 危险操作按钮(删除 / 清空)。 */
    protected fun addDangerButton(label: String, onClick: () -> Unit) {
        content.addView(ScreenUi.dangerButton(this, label, onClick))
    }

    /** 卡片容器:在回调里往里塞内容。 */
    protected fun addCard(block: (LinearLayout) -> Unit): LinearLayout {
        val card = ScreenUi.card(this)
        block(card)
        content.addView(card)
        return card
    }

    /** 卡片内的键值行。 */
    protected fun addKeyValue(parent: LinearLayout, label: String, value: String, mono: Boolean = false) {
        parent.addView(ScreenUi.keyValue(this, label, value, mono))
    }

    /** 空状态。 */
    protected fun addEmptyState(title: String, hint: String) {
        content.addView(ScreenUi.emptyState(this, title, hint))
    }

    /** 加载中提示。 */
    protected fun addLoading(text: String = "加载中…") {
        content.addView(
            ScreenUi.text(this, text, R.dimen.text_sm, R.attr.lTextDim).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(12))
            },
        )
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
