package com.batteryscope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryCapacityEstimatorTest {
    @Test
    fun estimatesCapacityFromChargeAndDischargeSessions() {
        val samples = listOf(
            CapacitySample(1_000L, 1, 40, "CHARGING", "USB", 1_800_000L),
            CapacitySample(2_000L, 1, 60, "CHARGING", "USB", 2_700_000L),
            CapacitySample(200_000L, 1, 80, "DISCHARGING", "NONE", 3_600_000L),
            CapacitySample(201_000L, 1, 60, "DISCHARGING", "NONE", 2_700_000L)
        )
        val result = BatteryCapacityEstimator.estimate(samples)
        requireNotNull(result)
        assertEquals(4500.0, result.capacityMah, 0.1)
        assertEquals(100.0, result.healthPercent, 0.1)
        assertEquals(2, result.validSessions)
    }

    @Test
    fun rejectsSmallStateOfChargeChanges() {
        val samples = listOf(
            CapacitySample(1_000L, 1, 50, "CHARGING", "USB", 2_250_000L),
            CapacitySample(2_000L, 1, 55, "CHARGING", "USB", 2_475_000L)
        )
        assertNull(BatteryCapacityEstimator.estimate(samples))
    }

    @Test
    fun rejectsCounterMovementThatDisagreesWithPercentageMovement() {
        val samples = listOf(
            CapacitySample(1_000L, 1, 40, "CHARGING", "USB", 2_400_000L),
            CapacitySample(2_000L, 1, 60, "CHARGING", "USB", 1_800_000L)
        )
        assertNull(BatteryCapacityEstimator.estimate(samples))
    }
    @Test
    fun rejectsSessionsWithoutBootCountEvidence() {
        val samples = listOf(
            CapacitySample(1_000L, null, 40, "CHARGING", "USB", 1_800_000L),
            CapacitySample(2_000L, null, 60, "CHARGING", "USB", 2_700_000L)
        )
        assertNull(BatteryCapacityEstimator.estimate(samples))
    }
}
