package com.linnas.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.8.0 文件列表筛选(类型/大小/时间)单元测试。 */
class FileFiltersTest {

    private fun entry(name: String, dir: Boolean = false, size: Long = 0L, mtime: Long = 0L) =
        Entry(name, dir, size, mtime)

    @Test
    fun typeFilters() {
        val video = entry("a.mp4")
        val image = entry("b.JPG")
        val audio = entry("c.mp3")
        val zip = entry("d.zip")
        val txt = entry("e.txt")
        val dir = entry("folder", dir = true)

        assertTrue(FileFilters.matches(video, "video", "all", "all", 0))
        assertFalse(FileFilters.matches(image, "video", "all", "all", 0))
        assertTrue(FileFilters.matches(image, "image", "all", "all", 0))
        assertTrue(FileFilters.matches(audio, "audio", "all", "all", 0))
        assertTrue(FileFilters.matches(zip, "archive", "all", "all", 0))
        assertTrue(FileFilters.matches(txt, "other", "all", "all", 0))
        assertFalse(FileFilters.matches(video, "other", "all", "all", 0))
        assertTrue(FileFilters.matches(dir, "dir", "all", "all", 0))
        assertFalse(FileFilters.matches(video, "dir", "all", "all", 0))
        // "all" 放行全部
        assertTrue(FileFilters.matches(txt, "all", "all", "all", 0))
    }

    @Test
    fun sizeFilters() {
        assertTrue(FileFilters.matches(entry("x", false, 5 * FileFilters.MB), "all", "lt10m", "all", 0))
        assertFalse(FileFilters.matches(entry("x", false, 50 * FileFilters.MB), "all", "lt10m", "all", 0))
        assertTrue(FileFilters.matches(entry("x", false, 50 * FileFilters.MB), "all", "b10_100m", "all", 0))
        assertTrue(FileFilters.matches(entry("x", false, 500 * FileFilters.MB), "all", "b100m_1g", "all", 0))
        assertTrue(FileFilters.matches(entry("x", false, 2 * FileFilters.GB), "all", "gt1g", "all", 0))
        assertFalse(FileFilters.matches(entry("x", false, 500 * FileFilters.MB), "all", "gt1g", "all", 0))
        // 文件夹不参与大小筛选
        assertTrue(FileFilters.matches(entry("dir", true, 0), "all", "gt1g", "all", 0))
    }

    @Test
    fun timeFilters() {
        val now = 1_000_000_000_000L
        assertTrue(FileFilters.matches(entry("x", false, 0, now - 3600_000L), "all", "all", "today", now))
        assertFalse(
            FileFilters.matches(entry("x", false, 0, now - 2L * 24 * 3600 * 1000), "all", "all", "today", now)
        )
        assertTrue(
            FileFilters.matches(entry("x", false, 0, now - 3L * 24 * 3600 * 1000), "all", "all", "7d", now)
        )
        assertTrue(
            FileFilters.matches(entry("x", false, 0, now - 20L * 24 * 3600 * 1000), "all", "all", "30d", now)
        )
        assertFalse(
            FileFilters.matches(entry("x", false, 0, now - 40L * 24 * 3600 * 1000), "all", "all", "30d", now)
        )
    }
}
