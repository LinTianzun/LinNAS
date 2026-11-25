package com.linnas.app

import android.content.Context

/**
 * 主题:6 套可切换配色(4 深 + 2 浅),存 SharedPreferences,切换后 recreate。
 * [swatch] 是设置页色块预览用的颜色资源。
 */
object ThemeStore {
    data class T(val key: String, val label: String, val dark: Boolean, val style: Int, val swatch: Int)

    val all = listOf(
        T("blue", "墨蓝", true, R.style.Theme_LinNas, R.color.sw_blue),
        T("green", "墨绿", true, R.style.Theme_LinNas_Green, R.color.sw_green),
        T("amber", "琥珀", true, R.style.Theme_LinNas_Amber, R.color.sw_amber),
        T("mono", "纯黑", true, R.style.Theme_LinNas_Mono, R.color.sw_mono),
        T("light", "云白", false, R.style.Theme_LinNas_Light, R.color.sw_light),
        T("warm", "暖沙", false, R.style.Theme_LinNas_Warm, R.color.sw_warm),
    )

    private fun sp(ctx: Context) = ctx.getSharedPreferences("linnas_ui", Context.MODE_PRIVATE)

    fun index(ctx: Context): Int = sp(ctx).getInt("theme", 0).coerceIn(0, all.lastIndex)
    fun styleRes(ctx: Context): Int = all[index(ctx)].style
    fun setIndex(ctx: Context, i: Int) = sp(ctx).edit().putInt("theme", i).apply()

    /** 供 Java/代码创建视图时判断浅色(状态栏图标反色等)。 */
    fun isDark(ctx: Context): Boolean = all[index(ctx)].dark
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

    /** 上次停留的底部导航页(切换主题 recreate 后恢复,避免跳回文件页)。 */
    fun lastTab(ctx: Context): Int = sp(ctx).getInt("last_tab", R.id.tab_files)
    fun setLastTab(ctx: Context, id: Int) = sp(ctx).edit().putInt("last_tab", id).apply()

    /** 下载保存位置:系统下载目录下的子文件夹名(空 = 直接存下载目录)。 */
    fun downloadDir(ctx: Context): String = sp(ctx).getString("download_dir", "linNas") ?: ""
    fun setDownloadDir(ctx: Context, v: String) = sp(ctx).edit().putString("download_dir", v).apply()
}
