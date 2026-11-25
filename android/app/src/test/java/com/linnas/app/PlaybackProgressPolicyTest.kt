package com.linnas.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProgressPolicyTest {
    @Test
    fun savesOnlyMeaningfulPositionsWithEnoughTimeRemaining() {
        assertFalse(PlaybackProgressPolicy.shouldSave("video", 9_999, 120_000))
        assertTrue(PlaybackProgressPolicy.shouldSave("video", 10_000, 120_000))
        assertTrue(PlaybackProgressPolicy.shouldSave("video", 90_000, 120_000))
        assertFalse(PlaybackProgressPolicy.shouldSave("video", 90_001, 120_000))
        assertFalse(PlaybackProgressPolicy.shouldSave("", 30_000, 120_000))
    }

    @Test
    fun ignoresInvalidOrNearlyFinishedResumePositions() {
        assertEquals(0L, PlaybackProgressPolicy.resumePosition(-1, 120_000))
        assertEquals(0L, PlaybackProgressPolicy.resumePosition(9_999, 120_000))
        assertEquals(0L, PlaybackProgressPolicy.resumePosition(120_000, 120_000))
        assertEquals(0L, PlaybackProgressPolicy.resumePosition(90_001, 120_000))
        assertEquals(90_000L, PlaybackProgressPolicy.resumePosition(90_000, 120_000))
    }
}
