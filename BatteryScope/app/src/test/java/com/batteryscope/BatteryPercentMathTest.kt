package com.batteryscope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryPercentMathTest {
    @Test
    fun convertsValidLevelAndScale() {
        assertEquals(50, BatteryPercentMath.percent(50, 100))
        assertEquals(100, BatteryPercentMath.percent(100, 100))
        assertEquals(0, BatteryPercentMath.percent(0, 100))
    }

    @Test
    fun rejectsMissingOrInvalidScaleAndLevel() {
        assertNull(BatteryPercentMath.percent(50, null))
        assertNull(BatteryPercentMath.percent(null, 100))
        assertNull(BatteryPercentMath.percent(50, 0))
        assertNull(BatteryPercentMath.percent(101, 100))
        assertNull(BatteryPercentMath.percent(-1, 100))
    }
}
