package com.linnas.app

import android.content.Context

/** 主题:4 套可切换配色,存 SharedPreferences,切换后 recreate。 */
object ThemeStore {
    data class T(val key: String, val label: String, val style: Int)

    val all = listOf(
        T("blue", "墨蓝", R.style.Theme_LinNas),
        T("green", "墨绿", R.style.Theme_LinNas_Green),
        T("amber", "琥珀", R.style.Theme_LinNas_Amber),
        T("mono", "纯黑", R.style.Theme_LinNas_Mono),
    )

    private fun sp(ctx: Context) = ctx.getSharedPreferences("linnas_ui", Context.MODE_PRIVATE)

    fun index(ctx: Context): Int = sp(ctx).getInt("theme", 0).coerceIn(0, all.lastIndex)
    fun styleRes(ctx: Context): Int = all[index(ctx)].style
    fun setIndex(ctx: Context, i: Int) = sp(ctx).edit().putInt("theme", i).apply()
}

/** 文件页视图/排序偏好。 */
object UiPrefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("linnas_ui", Context.MODE_PRIVATE)

    fun viewMode(ctx: Context): String = sp(ctx).getString("view_mode", "list") ?: "list"
    fun setViewMode(ctx: Context, v: String) = sp(ctx).edit().putString("view_mode", v).apply()

    fun sortKey(ctx: Context): String = sp(ctx).getString("sort_key", "name") ?: "name"
    fun setSortKey(ctx: Context, v: String) = sp(ctx).edit().putString("sort_key", v).apply()

    fun sortAsc(ctx: Context): Boolean = sp(ctx).getBoolean("sort_asc", true)
    fun setSortAsc(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("sort_asc", v).apply()
}
