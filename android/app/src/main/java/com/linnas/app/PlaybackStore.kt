package com.linnas.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * 续播记忆:记录每个视频(按 vpath 键)上次播放位置,存在手机本地。
 * 位置 > 10 秒且距结尾 > 30 秒才记为"可续播"。
 */
object PlaybackStore {
    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("linnas_playback", Context.MODE_PRIVATE)

    /** 保存进度(毫秒)。 */
    fun save(ctx: Context, key: String, positionMs: Long, durationMs: Long) {
        if (!PlaybackProgressPolicy.shouldSave(key, positionMs, durationMs)) {
            sp(ctx).edit().remove(key).apply()
            return
        }
        val json = JSONObject()
            .put("pos", positionMs)
            .put("dur", durationMs)
            .put("ts", System.currentTimeMillis())
        sp(ctx).edit().putString(key, json.toString()).apply()
    }

    /** 读取进度(毫秒),没有或已看完返回 0。 */
    fun position(ctx: Context, key: String): Long {
        val raw = sp(ctx).getString(key, null) ?: return 0
        return try {
            val o = JSONObject(raw)
            val pos = o.optLong("pos")
            val dur = o.optLong("dur")
            PlaybackProgressPolicy.resumePosition(pos, dur)
        } catch (_: Exception) {
            0
        }
    }

    /** 剩余秒数(用于"继续观看 · 还剩 x 分钟")。 */
    fun remainingMin(ctx: Context, key: String): Int {
        val raw = sp(ctx).getString(key, null) ?: return 0
        return try {
            val o = JSONObject(raw)
            val duration = o.optLong("dur")
            val position = PlaybackProgressPolicy.resumePosition(o.optLong("pos"), duration)
            val remaining = duration - position
            if (position <= 0 || remaining <= 0) 0 else ((remaining / 60000) + 1).toInt()
        } catch (_: Exception) {
            0
        }
    }

    fun clear(ctx: Context, key: String) = sp(ctx).edit().remove(key).apply()
}
