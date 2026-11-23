package com.batteryscope

import kotlin.math.abs

/**
 * Uses the persisted boot counter to distinguish same-boot monotonic intervals
 * from wall-clock intervals that cross a reboot.
 */
internal object RecorderTimeMath {
    private const val UNKNOWN_BOOT_TOLERANCE_MS = 120_000L

    fun intervalMs(
        beforeWallMs: Long,
        beforeElapsedMs: Long,
        beforeBootCount: Int?,
        afterWallMs: Long,
        afterElapsedMs: Long,
        afterBootCount: Int?
    ): Long {
        val wallDelta = afterWallMs - beforeWallMs
        val monotonicDelta = afterElapsedMs - beforeElapsedMs
        if (beforeBootCount != null && afterBootCount != null) {
            return if (beforeBootCount == afterBootCount) monotonicDelta else wallDelta
        }
        return if (monotonicDelta > 0L && wallDelta > 0L &&
            abs(monotonicDelta - wallDelta) <= UNKNOWN_BOOT_TOLERANCE_MS) {
            monotonicDelta
        } else {
            wallDelta
        }
    }
}
