package com.batteryscope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryMeasurementQualityTest {
    @Test
    fun acceptsAndroidDocumentedChargingPolarity() {
        val result = BatteryMeasurementQuality.current(1_500_000L, "CHARGING", "AC")
        assertTrue(result.usable)
        assertEquals("AVAILABLE_BY_ANDROID_CONVENTION", result.status)
    }

    @Test
    fun rejectsOppositePolarityWhileCharging() {
        val result = BatteryMeasurementQuality.current(-551L, "CHARGING", "AC")
        assertFalse(result.usable)
        assertEquals("SIGN_CONFLICT_WITH_ANDROID_CONVENTION", result.status)
    }

    @Test
    fun rejectsPositiveCurrentWhileUnpluggedAndNotCharging() {
        val result = BatteryMeasurementQuality.current(674L, "NOT_CHARGING", "NONE")
        assertFalse(result.usable)
        assertEquals("SIGN_CONFLICT_WITH_ANDROID_CONVENTION", result.status)
    }

    @Test
    fun acceptsNegativeCurrentWhileDischarging() {
        assertTrue(BatteryMeasurementQuality.current(-800_000L, "DISCHARGING", "NONE").usable)
    }

    @Test
    fun doesNotGuessDirectionForAmbiguousState() {
        assertEquals(
            "DIRECTION_UNVERIFIED",
            BatteryMeasurementQuality.current(800_000L, "NOT_CHARGING", "AC").status
        )
    }

    @Test
    fun rejectsVoltageOutsideMillivoltRange() {
        assertFalse(BatteryMeasurementQuality.voltage(3).usable)
        assertTrue(BatteryMeasurementQuality.voltage(3_850).usable)
    }

    @Test
    fun refusesPowerWhenEitherMeasurementIsInvalid() {
        assertNull(BatteryMeasurementQuality.batteryPowerW(3, 2_000_000L, "CHARGING", "AC"))
        assertNull(BatteryMeasurementQuality.batteryPowerW(3_850, -551L, "CHARGING", "AC"))
    }

    @Test
    fun calculatesSignedBatterySidePowerOnlyFromValidatedInputs() {
        val result = BatteryMeasurementQuality.batteryPowerW(3_850, 2_000_000L, "CHARGING", "AC")
        assertNotNull(result)
        assertEquals(7.7, result!!, 0.0001)
    }
}
