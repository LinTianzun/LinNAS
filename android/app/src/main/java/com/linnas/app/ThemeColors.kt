package com.linnas.app

import android.content.Context
import android.content.res.ColorStateList

/** 读取当前主题的属性色(供代码里上色用,跟随主题切换)。 */
object ThemeColors {
    fun color(ctx: Context, attr: Int): Int {
        val ta = ctx.obtainStyledAttributes(intArrayOf(attr))
        return try {
            ta.getColor(0, 0xFF88919F.toInt())
        } finally {
            ta.recycle()
        }
    }

    fun csl(ctx: Context, attr: Int): ColorStateList = ColorStateList.valueOf(color(ctx, attr))

    fun primary(ctx: Context) = color(ctx, R.attr.lPrimary)
    fun text(ctx: Context) = color(ctx, R.attr.lText)
    fun textDim(ctx: Context) = color(ctx, R.attr.lTextDim)
    fun outline(ctx: Context) = color(ctx, R.attr.lOutline)
}
