package com.batteryscope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryPercentMathTest {
    @Test
    fun convertsValidLevelAndScale() {
        assertEquals(50L, BatteryPercentMath.percent(50, 100)!!.toLong())
        assertEquals(100L, BatteryPercentMath.percent(100, 100)!!.toLong())
        assertEquals(0L, BatteryPercentMath.percent(0, 100)!!.toLong())
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
