package com.linnas.app

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

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

    /**
     * 圆形图标底 + 线性图标。
     * @param tileSizeDp 圆底直径(dp)。传 0 表示不限制(底随图标 24dp 自然撑开);
     *   传正值时图标收敛到 min(icon_lg, tileSizeDp-8dp),避免小圆底里图标贴边或被裁掉。
     */
    fun iconTile(ctx: Context, iconRes: Int, tintAttr: Int = R.attr.lPrimary, tileSizeDp: Int = 0): FrameLayout {
        val tile = FrameLayout(ctx).apply { setBackgroundResource(R.drawable.bg_icon_circle) }
        val iconPx = if (tileSizeDp > 0) {
            minOf(dim(ctx, R.dimen.icon_lg), dp(ctx, tileSizeDp - 8).coerceAtLeast(dp(ctx, 12)))
        } else {
            dim(ctx, R.dimen.icon_lg)
        }
        tile.addView(
            ImageView(ctx).apply {
                setImageResource(iconRes)
                tintGlyph(this, ctx, tintAttr)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = ""
            },
            FrameLayout.LayoutParams(iconPx, iconPx, Gravity.CENTER),
        )
        return tile
    }

    /**
     * 给矢量图标上主题色:imageTintList 为主,再叠一层 SRC_IN 颜色过滤兜底。
     * 为什么叠两层:个别机型/主题下 imageTintList 对 vector 不生效,图标会保持资源里
     * 的原始颜色(设备图标是白色描边),叠在浅色圆底上几乎「隐身」;再用 setColorFilter
     * 强制一次,保证任何情况下都是主题色。
     */
    internal fun tintGlyph(iv: ImageView, ctx: Context, tintAttr: Int) {
        iv.imageTintList = ThemeColors.csl(ctx, tintAttr)
        iv.setColorFilter(ThemeColors.color(ctx, tintAttr), PorterDuff.Mode.SRC_IN)
    }

    /**
     * 图标圆底:主题色 + 约 12% 透明度(动态生成,不依赖 drawable)。
     * 为什么在代码里画:bg_sheet_tile 里用 `<item android:alpha="0.12">` 包一个 solid 的
     * lPrimary 形状,这个 item 级 alpha 在真机/部分主题上不生效,会把圆底渲染成"实心主色"圆;
     * 而图标又被 tint 成同一个主色 → 底和图形同色,图标完全看不见。这里直接把 alpha 乘进
     * ARGB 色值,任何设备都保证「淡色底 + 实色图标」。
     */
    internal fun tileTintBackground(ctx: Context, tintAttr: Int): GradientDrawable {
        val wash = (ThemeColors.color(ctx, tintAttr) and 0x00FFFFFF) or 0x1F000000
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(wash)
        }
    }

    /**
     * 选中行的底:高表面色填充 + 主色 1dp 描边。
     * 为什么在代码里画而不是写进 drawable:需要按当前主题取 lSurfaceHigh / lPrimary,
     * 统一用 ThemeColors 取色可避免 drawable 里 ?attr 在不同主题叠加下解析不一致。
     * 描边用全强度主色(而不是低透明度淡描边):淡描边在浅/深主题下几乎不可见,
     * 达不到「让用户一眼看出选中了哪项」的目的。
     */
    internal fun checkedRowBackground(ctx: Context): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dim(ctx, R.dimen.radius_lg).toFloat()
            setColor(ThemeColors.color(ctx, R.attr.lSurfaceHigh))
            setStroke(dim(ctx, R.dimen.stroke_hairline), ThemeColors.color(ctx, R.attr.lPrimary))
        }

    /**
     * 圆角卡片行:图标底 + 标题 + 副标题 + 可选值 + 可选 chevron。
     * onClick / onLongClick 非空时才可点,并加 bg_ripple 按压反馈。
     * checked 时换成「主色描边」的选中底,与右侧主色勾图标配合,选中状态一眼可辨。
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
        checked: Boolean = false,
        more: ((View) -> Unit)? = null,
    ): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dim(ctx, R.dimen.row_min_height)
            if (checked) {
                background = checkedRowBackground(ctx)
            } else {
                setBackgroundResource(R.drawable.bg_card)
            }
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
        } else if (checked) {
            // 选中态:右侧显示主色勾(用于磁盘/目录选择)
            card.addView(
                ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_sheet_check)
                    imageTintList = ThemeColors.csl(ctx, R.attr.lPrimary)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = "已选择"
                },
                LinearLayout.LayoutParams(dim(ctx, R.dimen.icon_md), dim(ctx, R.dimen.icon_md)).apply {
                    marginStart = dim(ctx, R.dimen.space_2)
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

    /** 语义按钮工厂:供 [Sheet] 的底部按钮行复用(同文件内可见)。 */
    internal fun button(
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

/**
 * v1.8.3 底部弹窗(Sheet)体系:统一二级弹窗的视觉与交互。
 *
 * 为什么单独抽类:此前 MainActivity 里十几个弹窗各用 AlertDialog.Builder 拼字符串,
 * 深/浅主题下的间距、字号、按钮层级都不统一;这里用纯代码视图 + 主题语义色一次性收口,
 * 并统一「底部弹层 + 拖动条 + 圆角卡片行」的观感。
 *
 * 结构:拖动条 → 标题/副标题 → 可滚动内容区(NestedScrollView) → 底部按钮区。
 * 典型用法:
 *   Sheet(ctx, "删除 xxx?", "将移入回收站,可恢复")
 *       .danger("删除") { ... }
 *       .tonal("取消") { ... }
 *       .show()
 */
/**
 * 弹层里的滚动内容区:高度最多占屏幕 58%,超出就内部滚动。
 * 之前直接用 WRAP_CONTENT 的 NestedScrollView,内容一多(比如「添加存储库」的目录列表)
 * 整块弹层会被撑得比屏幕还高,底部按钮区(手动输入路径 / 添加为存储库)被挤出屏幕看不见。
 */
private class SheetScrollView(ctx: Context) : NestedScrollView(ctx) {
    private val capPx: Int = (resources.displayMetrics.heightPixels * 0.58f).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Kotlin 不继承父类的嵌套类型,MeasureSpec 需全限定。
        val capped = android.view.View.MeasureSpec.makeMeasureSpec(capPx, android.view.View.MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, capped)
    }
}

class Sheet(private val ctx: Context, title: String, subtitle: String? = null) {

    private val dialog = BottomSheetDialog(ctx)
    private val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val footer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val inputs = mutableListOf<EditText>()
    private var custom: LinearLayout? = null

    init {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_sheet_panel)
            // 底部 24dp 让按钮离系统手势条有余量(Material3 的 BottomSheetDialog 自身还会避让导航条)
            setPadding(dp(20), 0, dp(20), dp(24))
        }
        root.addView(
            View(ctx).apply { setBackgroundResource(R.drawable.bg_sheet_handle) },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(10)
            },
        )
        root.addView(
            ScreenUi.text(ctx, title, R.dimen.text_lg, R.attr.lText, bold = true, maxLines = 2).apply {
                setPadding(0, dp(14), 0, 0)
            },
        )
        if (!subtitle.isNullOrBlank()) {
            root.addView(
                ScreenUi.text(ctx, subtitle, R.dimen.text_sm, R.attr.lTextDim).apply {
                    setPadding(0, dp(4), 0, 0)
                },
            )
        }
        root.addView(
            SheetScrollView(ctx).apply {
                isVerticalScrollBarEnabled = false
                addView(body)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) }
            },
        )
        root.addView(footer)
        dialog.setContentView(root)
    }

    /** 分组小标题。 */
    fun section(label: String): Sheet {
        body.addView(ScreenUi.sectionHeader(ctx, label))
        return this
    }

    /**
     * 一行:图标 + 标题 + 副标题 + 右侧值 + 可选勾选/箭头。
     * @param iconRes 传 0(或负数)表示不显示图标。
     * @param destructive 危险操作用危险色标题与图标。
     * @param checked 选中态显示主色勾。
     * @param dismissOnClick 点击后是否自动关闭弹层(目录浏览这类原地刷新传 false)。
     */
    fun row(
        iconRes: Int,
        title: String,
        subtitle: String? = null,
        value: String? = null,
        destructive: Boolean = false,
        checked: Boolean = false,
        dismissOnClick: Boolean = true,
        onClick: (() -> Unit)? = null,
    ): Sheet {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            // checked 时换成主色描边的选中底,配合右侧主色勾,让当前选项有明确边界
            if (checked) {
                background = ScreenUi.checkedRowBackground(ctx)
            } else {
                setBackgroundResource(R.drawable.bg_sheet_row)
            }
            foreground = ctx.getDrawable(R.drawable.bg_ripple)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        if (iconRes > 0) {
            card.addView(
                iconTile(iconRes, if (destructive) R.attr.lDanger else R.attr.lPrimary),
                LinearLayout.LayoutParams(dp(34), dp(34)),
            )
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = if (iconRes > 0) dp(12) else 0
            }
        }
        col.addView(
            ScreenUi.text(
                ctx, title, R.dimen.text_md,
                if (destructive) R.attr.lDanger else R.attr.lText,
                maxLines = 2, ellipsize = true,
            ),
        )
        if (!subtitle.isNullOrBlank()) {
            col.addView(
                ScreenUi.text(ctx, subtitle, R.dimen.text_caption, R.attr.lTextDim, maxLines = 2).apply {
                    setPadding(0, dp(2), 0, 0)
                },
            )
        }
        card.addView(col)
        if (!value.isNullOrBlank()) {
            card.addView(
                ScreenUi.text(ctx, value, R.dimen.text_caption, R.attr.lTextDim, maxLines = 2, ellipsize = true)
                    .apply {
                        gravity = Gravity.END
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { marginStart = dp(8) }
                    },
            )
        }
        if (checked) {
            card.addView(
                ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_sheet_check)
                    imageTintList = ThemeColors.csl(ctx, R.attr.lPrimary)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = "已选择"
                },
                LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(8) },
            )
        } else if (onClick != null) {
            card.addView(
                ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_chevron)
                    imageTintList = ThemeColors.csl(ctx, R.attr.lTextDim)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = ""
                },
                LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(4) },
            )
        }
        if (onClick != null) {
            card.isClickable = true
            card.setOnClickListener {
                if (dismissOnClick) dismiss()
                onClick()
            }
        }
        body.addView(card)
        return this
    }

    /** 输入框。多个输入框按添加顺序用 value(index)/inputView(index) 取值。 */
    fun input(hint: String, initial: String = "", inputType: Int = android.text.InputType.TYPE_CLASS_TEXT): Sheet {
        val et = EditText(ctx).apply {
            this.hint = hint
            setText(initial)
            setSelection(text?.length ?: 0)
            // 先设 inputType 再 setSingleLine:后者只切换单行 flag,不会清掉 password 等 variation
            this.inputType = inputType
            setSingleLine()
            setTextColor(ThemeColors.color(ctx, R.attr.lText))
            setHintTextColor(ThemeColors.color(ctx, R.attr.lTextDim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ScreenUi.sp(ctx, R.dimen.text_sm))
            setBackgroundResource(R.drawable.bg_sheet_input)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        inputs.add(et)
        body.addView(et)
        return this
    }

    /**
     * 分段选择器:整行放在「高表面色圆角槽」里(padding 4dp),选中项主色实底 +
     * onPrimary 加粗、未选中项透明底 + 次级文字;项高 40dp、项间距 4dp。
     *
     * 为什么改成实底:旧样式是主色 10% 淡底 + 主色文字,浅色主题下选中项与未选中项
     * 几乎同色(都是浅灰),用户「选中了看不见自己选了什么」。主色实底 + onPrimary
     * 文字在全部 6 个主题下对比度 ≥ 4.5:1(浅色 ≈5-6.5:1,深色 ≈7-8:1)。
     * 主题色统一用 ThemeColors 在代码里取,不依赖 drawable 里的 ?attr。
     */
    fun segmented(options: List<String>, selected: Int, onPick: (Int) -> Unit): Sheet {
        var sel = selected
        // 槽底:高表面色 + 12dp 圆角,把一组选项框成一个整体,和普通卡片区分开
        val track = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(ThemeColors.color(ctx, R.attr.lSurfaceHigh))
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        val cells = mutableListOf<TextView>()
        fun paint() {
            cells.forEachIndexed { i, tv ->
                val on = i == sel
                // 选中:主色实底(10dp 圆角);未选中:透明,由槽底承托,不再额外画描边
                tv.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    if (on) setColor(ThemeColors.color(ctx, R.attr.lPrimary))
                }
                tv.setTextColor(ThemeColors.color(ctx, if (on) R.attr.lOnPrimary else R.attr.lTextDim))
                tv.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            }
        }
        options.forEachIndexed { i, label ->
            val tv = TextView(ctx).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, ScreenUi.sp(ctx, R.dimen.text_sm))
                setPadding(dp(4), 0, dp(4), 0)
                // 定高 40dp:选项再多也保持统一节奏,点击区域足够大
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    if (i > 0) marginStart = dp(4)
                }
                setOnClickListener {
                    sel = i
                    paint()
                    onPick(i)
                }
            }
            cells.add(tv)
            track.addView(tv)
        }
        paint()
        body.addView(track)
        return this
    }

    /** 多行说明文字。 */
    fun text(value: String): Sheet {
        body.addView(ScreenUi.note(ctx, value))
        return this
    }

    /** 键值行(左标签右值,可等宽)。 */
    fun keyValue(label: String, value: String, mono: Boolean = false): Sheet {
        body.addView(ScreenUi.keyValue(ctx, label, value, mono))
        return this
    }

    /** 底部主按钮(主色填充)。 */
    fun primary(label: String, onClick: () -> Unit): Sheet =
        addButton(label, R.attr.lOnPrimary, R.drawable.bg_button_primary, onClick)

    /** 底部次按钮(表面色 + 描边)。 */
    fun tonal(label: String, onClick: () -> Unit): Sheet =
        addButton(label, R.attr.lText, R.drawable.bg_button_tonal, onClick)

    /** 底部危险按钮(次级底 + 危险色文字)。 */
    fun danger(label: String, onClick: () -> Unit): Sheet =
        addButton(label, R.attr.lDanger, R.drawable.bg_button_tonal, onClick)

    private fun addButton(label: String, colorAttr: Int, bgRes: Int, onClick: () -> Unit): Sheet {
        footer.addView(ScreenUi.button(ctx, label, colorAttr, bgRes, onClick))
        return this
    }

    /**
     * 懒创建的自定义容器(如目录浏览器):只创建一次并追加到内容区,
     * 调用方可随时 removeAllViews 后重新填充,不会影响已加的输入框/按钮。
     */
    fun customArea(): LinearLayout = custom ?: LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        custom = this
        body.addView(this)
    }

    /** 第 index 个输入框的文本(已 trim)。 */
    fun value(index: Int = 0): String = inputs.getOrNull(index)?.text?.toString()?.trim().orEmpty()

    /** 第 index 个输入框本身(用于 setError / setText 等)。 */
    fun inputView(index: Int = 0): EditText? = inputs.getOrNull(index)

    /** 给最后一个输入框绑定「回车即提交」。 */
    fun onEnter(action: () -> Unit): Sheet {
        inputs.lastOrNull()?.apply {
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, actionId, event ->
                val done = actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (done) {
                    action()
                    true
                } else {
                    false
                }
            }
        }
        return this
    }

    fun isShowing(): Boolean = dialog.isShowing

    fun show() {
        dialog.show()
        // 内容通常比默认 peek 高:直接展开,避免只看到半屏还要手动上拖
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun dp(v: Int): Int = ScreenUi.dp(ctx, v)

    /**
     * 行首圆形图标底 + 实色图标(默认 34dp 底 + 24dp 图标)。
     * 圆底改用动态生成的主色 12% 淡色(ScreenUi.tileTintBackground),替代会渲染成实心主色的
     * bg_sheet_tile —— 否则底与图标同为 lPrimary,图标会「消失」。
     */
    private fun iconTile(iconRes: Int, tintAttr: Int, tileSizeDp: Int = 34): FrameLayout {
        val tile = FrameLayout(ctx).apply { background = ScreenUi.tileTintBackground(ctx, tintAttr) }
        val iconPx = minOf(
            ScreenUi.dim(ctx, R.dimen.icon_lg),
            dp(tileSizeDp - 8).coerceAtLeast(dp(12)),
        )
        tile.addView(
            ImageView(ctx).apply {
                setImageResource(iconRes)
                ScreenUi.tintGlyph(this, ctx, tintAttr)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = ""
            },
            FrameLayout.LayoutParams(iconPx, iconPx, Gravity.CENTER),
        )
        return tile
    }
}
