package com.linnas.app

import android.content.Context
import org.json.JSONArray

/**
 * v1.8.0 本机偏好数据:收藏、最近访问、搜索历史。
 * 只存 vpath + 名称,不缓存文件内容;上限:最近 30 条、搜索历史 12 条。
 */
object FavoritesStore {
    private const val PREF = "linnas_user_prefs"
    private const val K_FAV = "favorites"
    private const val K_RECENT = "recent"
    private const val K_SEARCH = "search_history"
    private const val MAX_RECENT = 30
    private const val MAX_SEARCH = 12

    data class Ref(val vpath: String, val name: String, val isDir: Boolean = false)

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ---- 收藏 ----
    fun favorites(ctx: Context): List<Ref> = decode(sp(ctx).getString(K_FAV, null))

    fun isFavorite(ctx: Context, vpath: String): Boolean =
        favorites(ctx).any { it.vpath == vpath }

    /** 返回 true = 现在是收藏状态。 */
    fun toggleFavorite(ctx: Context, ref: Ref): Boolean {
        val list = favorites(ctx).toMutableList()
        val idx = list.indexOfFirst { it.vpath == ref.vpath }
        return if (idx >= 0) {
            list.removeAt(idx); save(ctx, K_FAV, list); false
        } else {
            list.add(0, ref); save(ctx, K_FAV, list); true
        }
    }

    // ---- 最近访问 ----
    fun recents(ctx: Context): List<Ref> = decode(sp(ctx).getString(K_RECENT, null))

    fun touchRecent(ctx: Context, ref: Ref) {
        val list = recents(ctx).filterNot { it.vpath == ref.vpath }.toMutableList()
        list.add(0, ref)
        save(ctx, K_RECENT, list.take(MAX_RECENT))
    }

    fun clearRecents(ctx: Context) = sp(ctx).edit().remove(K_RECENT).apply()

    // ---- 搜索历史 ----
    fun searchHistory(ctx: Context): List<String> = try {
        val arr = JSONArray(sp(ctx).getString(K_SEARCH, "[]") ?: "[]")
        (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }

    fun addSearch(ctx: Context, q: String) {
        val query = q.trim()
        if (query.length < 2) return
        val list = searchHistory(ctx).filterNot { it.equals(query, ignoreCase = true) }.toMutableList()
        list.add(0, query)
        val arr = JSONArray()
        list.take(MAX_SEARCH).forEach { arr.put(it) }
        sp(ctx).edit().putString(K_SEARCH, arr.toString()).apply()
    }

    fun clearSearch(ctx: Context) = sp(ctx).edit().remove(K_SEARCH).apply()

    // ---- 编解码 ----
    private fun save(ctx: Context, key: String, list: List<Ref>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(org.json.JSONObject().put("v", r.vpath).put("n", r.name).put("d", r.isDir))
        }
        sp(ctx).edit().putString(key, arr.toString()).apply()
    }

    private fun decode(raw: String?): List<Ref> = try {
        val arr = JSONArray(raw ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Ref(o.optString("v"), o.optString("n"), o.optBoolean("d"))
        }
    } catch (_: Exception) {
        emptyList()
    }
}
