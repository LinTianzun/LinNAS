package com.linnas.app

/**
 * v1.8.0 文件列表筛选策略(纯函数,不依赖 Android,便于单元测试)。
 * 维度:类型 / 大小 / 时间。文件夹不参与大小筛选;存储库行由调用方放行。
 */
object FileFilters {
    val VIDEO = setOf(
        "mp4", "mkv", "mov", "avi", "webm", "m4v", "3gp", "ts", "m2ts",
        "rmvb", "flv", "wmv", "mpg", "mpeg", "vob", "ogv", "asf"
    )
    val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
    val AUDIO = setOf("mp3", "flac", "wav", "aac", "ogg", "m4a", "wma")
    val ARCHIVE = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")

    const val KB = 1024L
    const val MB = 1024L * 1024
    const val GB = 1024L * 1024 * 1024

    /**
     * @param type  all / dir / video / image / audio / archive / other
     * @param size  all / lt10m / b10_100m / b100m_1g / gt1g
     * @param time  all / today / 7d / 30d
     * @param now   当前时间戳(毫秒),便于测试注入
     */
    fun matches(e: Entry, type: String, size: String, time: String, now: Long): Boolean {
        val ext = e.name.substringAfterLast('.', "").lowercase()
        val okType = when (type) {
            "dir" -> e.isDir
            "video" -> !e.isDir && ext in VIDEO
            "image" -> !e.isDir && ext in IMAGE
            "audio" -> !e.isDir && ext in AUDIO
            "archive" -> !e.isDir && ext in ARCHIVE
            "other" -> !e.isDir && ext !in VIDEO && ext !in IMAGE && ext !in AUDIO
            else -> true   // "all" 或未知
        }
        if (!okType) return false

        if (!e.isDir && size != "all") {
            val okSize = when (size) {
                "lt10m" -> e.size < 10 * MB
                "b10_100m" -> e.size in (10 * MB) until (100 * MB)
                "b100m_1g" -> e.size in (100 * MB) until GB
                "gt1g" -> e.size >= GB
                else -> true
            }
            if (!okSize) return false
        }

        if (time != "all" && e.modTime > 0) {
            val span = when (time) {
                "today" -> 24L * 3600 * 1000
                "7d" -> 7L * 24 * 3600 * 1000
                "30d" -> 30L * 24 * 3600 * 1000
                else -> 0L
            }
            if (span > 0 && now - e.modTime > span) return false
        }
        return true
    }
}
