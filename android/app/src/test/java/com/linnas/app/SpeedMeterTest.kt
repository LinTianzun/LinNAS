package com.linnas.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.8.1 传输速度统计单元测试。 */
class SpeedMeterTest {

    @Test
    fun computesSpeedOverWindow() {
        val m = SpeedMeter(windowMs = 3000)
        // 1 秒内传了 1MB → 约 1MB/s
        m.update(0, 0)
        val s = m.update(1000, 1024L * 1024)
        assertTrue("speed should be > 0, was $s", s > 0)
        assertEquals(1024L * 1024, s)   // 1 秒窗口 → 1MB/s
    }

    @Test
    fun noSpeedWithoutSamples() {
        val m = SpeedMeter()
        assertEquals(0L, m.update(0, 0))
    }

    @Test
    fun dropsOldSamplesOutsideWindow() {
        val m = SpeedMeter(windowMs = 1000)
        m.update(0, 0)
        m.update(500, 100)
        // 超出窗口后旧样本被丢弃,速度按剩余窗口计算
        val s = m.update(2000, 200)
        assertTrue("unexpected speed $s", s > 0)
    }

    @Test
    fun formatSpeed() {
        assertEquals("—", SpeedMeter.format(0))
        assertEquals("1.0 MB/s", SpeedMeter.format(1024L * 1024))
        assertEquals("512 KB/s", SpeedMeter.format(512L * 1024))
    }
}
