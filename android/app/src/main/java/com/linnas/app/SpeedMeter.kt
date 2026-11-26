package com.linnas.app

/**
 * v1.8.1 传输速度统计:滑动窗口估算瞬时速度(B/s)。
 * 纯 Kotlin,不依赖 Android,便于单测。
 */
class SpeedMeter(private val windowMs: Long = 3_000L) {
    private data class Sample(val at: Long, val bytes: Long)

    private val samples = ArrayDeque<Sample>()

    /** 记录一次累计字节数;返回当前估算速度(B/s),无数据时返回 0。 */
    fun update(nowMs: Long, totalBytes: Long): Long {
        samples.addLast(Sample(nowMs, totalBytes))
        while (samples.size > 2 && nowMs - samples.first().at >= windowMs) {
            samples.removeFirst()
        }
        if (samples.size < 2) return 0
        val first = samples.first()
        val last = samples.last()
        val dt = last.at - first.at
        if (dt <= 0) return 0
        val db = last.bytes - first.bytes
        if (db <= 0) return 0
        return db * 1000 / dt
    }

    fun reset() = samples.clear()

    /** 最近一次估算出的速度(B/s)。 */
    fun latest(): Long {
        if (samples.size < 2) return 0
        val first = samples.first()
        val last = samples.last()
        val dt = last.at - first.at
        if (dt <= 0) return 0
        val db = last.bytes - first.bytes
        if (db <= 0) return 0
        return db * 1000 / dt
    }

    companion object {
        /** 把 B/s 格式化成 "1.2 MB/s"。 */
        fun format(bytesPerSec: Long): String {
            if (bytesPerSec <= 0) return "—"
            val mb = bytesPerSec.toDouble() / (1024.0 * 1024.0)
            return if (mb >= 1.0) String.format(java.util.Locale.US, "%.1f MB/s", mb)
            else String.format(java.util.Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
        }
    }
}
