package com.linnas.app

import android.content.Context

/**
 * 主题:6 套可切换配色(4 深 + 2 浅),存 SharedPreferences,切换后 recreate。
 * [swatch] 是设置页色块预览用的颜色资源。
 */
object ThemeStore {
    data class T(
        val key: String,
        val label: String,
        val dark: Boolean,
        val style: Int,
        val swatch: Int,
        val primary: Int,
    )

    val all = listOf(
        T("blue", "墨蓝", true, R.style.Theme_LinNas, R.color.sw_blue, R.color.t_blue_primary),
        T("green", "墨绿", true, R.style.Theme_LinNas_Green, R.color.sw_green, R.color.t_green_primary),
        T("amber", "琥珀", true, R.style.Theme_LinNas_Amber, R.color.sw_amber, R.color.t_amber_primary),
        T("mono", "纯黑", true, R.style.Theme_LinNas_Mono, R.color.sw_mono, R.color.t_mono_primary),
        T("light", "云白", false, R.style.Theme_LinNas_Light, R.color.sw_light, R.color.t_light_primary),
        T("warm", "暖沙", false, R.style.Theme_LinNas_Warm, R.color.sw_warm, R.color.t_warm_primary),
    )

    private fun sp(ctx: Context) = ctx.getSharedPreferences("linnas_ui", Context.MODE_PRIVATE)

    fun index(ctx: Context): Int = sp(ctx).getInt("theme", 0).coerceIn(0, all.lastIndex)
    fun styleRes(ctx: Context): Int = all[index(ctx)].style
    fun setIndex(ctx: Context, i: Int) =
        sp(ctx).edit().putInt("theme", i.coerceIn(0, all.lastIndex)).apply()

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
    fun lastTab(ctx: Context): Int {
        val saved = sp(ctx).getInt("last_tab", R.id.tab_files)
        return if (saved in tabIds) saved else R.id.tab_files
    }

    fun setLastTab(ctx: Context, id: Int) {
        if (id in tabIds) sp(ctx).edit().putInt("last_tab", id).apply()
    }

    private val tabIds = setOf(R.id.tab_files, R.id.tab_media, R.id.tab_transfers, R.id.tab_settings)

    /** 下载保存位置:SAF 目录(用户用系统文件夹选择器选出,可见、可分享)。 */
    fun downloadTreeUri(ctx: Context): String =
        sp(ctx).getString("download_tree", "") ?: ""

    fun setDownloadTree(ctx: Context, uri: String) =
        sp(ctx).edit().putString("download_tree", uri).apply()

    fun downloadTreeName(ctx: Context): String =
        sp(ctx).getString("download_tree_name", "").orEmpty()

    fun setDownloadTreeName(ctx: Context, name: String) =
        sp(ctx).edit().putString("download_tree_name", name).apply()

    /** 下载保存位置(旧字段:下载目录下的子文件夹名,仅用于显示回退)。 */
    fun downloadDir(ctx: Context): String = sp(ctx).getString("download_dir", "linNas") ?: ""
    fun setDownloadDir(ctx: Context, v: String) = sp(ctx).edit().putString("download_dir", v).apply()
}
