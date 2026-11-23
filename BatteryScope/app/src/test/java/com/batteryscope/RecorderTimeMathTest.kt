package com.batteryscope

import org.junit.Assert.assertEquals
import org.junit.Test

class RecorderTimeMathTest {
    @Test
    fun sameBootUsesMonotonicTimeDespiteWallClockAdjustment() {
        assertEquals(30_000L, RecorderTimeMath.intervalMs(
            1_000_000L, 500_000L, 7,
            900_000L, 530_000L, 7
        ))
    }

    @Test
    fun rebootUsesWallTimeEvenWhenNewUptimeIsLarger() {
        assertEquals(900_000L, RecorderTimeMath.intervalMs(
            1_000_000L, 120_000L, 7,
            1_900_000L, 1_020_000L, 8
        ))
    }

    @Test
    fun missingBootCountRequiresWallAndMonotonicAgreement() {
        assertEquals(30_000L, RecorderTimeMath.intervalMs(
            1_000_000L, 500_000L, null,
            1_030_000L, 530_000L, null
        ))
        assertEquals(900_000L, RecorderTimeMath.intervalMs(
            1_000_000L, 120_000L, null,
            2_500_000L, 1_020_000L, null
        ))
    }
}
