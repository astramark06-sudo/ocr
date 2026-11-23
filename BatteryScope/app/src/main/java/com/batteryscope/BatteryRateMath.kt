package com.batteryscope

/** Pure battery-rate calculations shared by history analytics and unit tests. */
internal object BatteryRateMath {
    /** Returns average mA from a charge-counter delta in µAh over elapsed milliseconds. */
    fun counterRateMa(deltaUah: Long, elapsedMs: Long): Double? {
        if (elapsedMs <= 0L) return null
        return (deltaUah / 1000.0) / (elapsedMs / 3_600_000.0)
    }

    /** Returns projected elapsed milliseconds to a target SOC distance at percentage-points/hour. */
    fun forecastDurationMs(remainingPercentagePoints: Double, ratePercentagePointsPerHour: Double): Long? {
        if (!remainingPercentagePoints.isFinite() || !ratePercentagePointsPerHour.isFinite()) return null
        if (remainingPercentagePoints <= 0.0 || ratePercentagePointsPerHour <= 0.0) return null
        val result = remainingPercentagePoints / ratePercentagePointsPerHour * 3_600_000.0
        if (!result.isFinite() || result > Long.MAX_VALUE.toDouble()) return null
        return result.toLong()
    }
}
