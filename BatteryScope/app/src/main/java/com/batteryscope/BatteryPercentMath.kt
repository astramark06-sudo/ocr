package com.batteryscope

/** Validates Android's level/scale pair without inventing a default scale. */
internal object BatteryPercentMath {
    fun percent(level: Int?, scale: Int?): Int? {
        if (level == null || scale == null || scale <= 0 || level < 0 || level > scale) return null
        return (level.toLong() * 100L / scale.toLong()).toInt()
    }
}
