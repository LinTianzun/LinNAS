package com.linnas.app

internal object PlaybackProgressPolicy {
    private const val MIN_RESUME_POSITION_MS = 10_000L
    private const val MIN_REMAINING_MS = 30_000L

    fun shouldSave(key: String, positionMs: Long, durationMs: Long): Boolean =
        key.isNotBlank() &&
            durationMs > 0 &&
            positionMs >= MIN_RESUME_POSITION_MS &&
            positionMs < durationMs &&
            durationMs - positionMs >= MIN_REMAINING_MS

    fun resumePosition(positionMs: Long, durationMs: Long): Long {
        if (positionMs < 0 || durationMs <= 0 || positionMs >= durationMs) return 0
        if (positionMs < MIN_RESUME_POSITION_MS) return 0
        if (durationMs - positionMs < MIN_REMAINING_MS) return 0
        return positionMs
    }
}
