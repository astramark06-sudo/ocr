package com.batteryscope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryRateMathTest {
    @Test
    fun calculatesBatterySideMilliampRateFromChargeCounter() {
        assertEquals(1800.0, BatteryRateMath.counterRateMa(900_000L, 1_800_000L)!!, 0.001)
    }

    @Test
    fun rejectsMissingOrInvalidElapsedTime() {
        assertNull(BatteryRateMath.counterRateMa(900_000L, 0L))
        assertNull(BatteryRateMath.forecastDurationMs(40.0, 0.0))
    }

    @Test
    fun forecastsTimeToSocThreshold() {
        assertEquals(7_200_000L, BatteryRateMath.forecastDurationMs(40.0, 20.0))
    }
}
