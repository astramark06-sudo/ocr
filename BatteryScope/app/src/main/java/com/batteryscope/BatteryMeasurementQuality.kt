package com.batteryscope

/**
 * Validates BatteryManager values before presenting derived battery-side measurements.
 *
 * Android documents CURRENT_NOW as microamperes, positive into the battery and
 * negative out of it. Some device firmware may violate that contract. Raw values
 * are retained; this class never silently flips signs or guesses a scale.
 */
internal data class MeasurementAssessment(
    val usable: Boolean,
    val status: String
)

internal object BatteryMeasurementQuality {
    fun voltage(rawVoltageMv: Int?): MeasurementAssessment = when {
        rawVoltageMv == null -> MeasurementAssessment(false, "UNAVAILABLE")
        rawVoltageMv in 2_500..5_000 -> MeasurementAssessment(true, "AVAILABLE")
        else -> MeasurementAssessment(false, "OUT_OF_RANGE_EXPECTED_MILLIVOLTS")
    }

    fun current(rawCurrentUa: Long?, batteryStatus: String, plugType: String): MeasurementAssessment {
        if (rawCurrentUa == null) return MeasurementAssessment(false, "UNAVAILABLE")
        if (rawCurrentUa == 0L) return MeasurementAssessment(false, "ZERO_OR_IDLE")

        val status = batteryStatus.uppercase().replace(' ', '_')
        val plug = plugType.uppercase()
        val expectedSign = when {
            status == "CHARGING" -> 1
            status == "DISCHARGING" -> -1
            status == "NOT_CHARGING" && (plug == "NONE" || plug.startsWith("NONE ")) -> -1
            else -> null
        } ?: return MeasurementAssessment(false, "DIRECTION_UNVERIFIED")

        val signMatches = if (expectedSign > 0) rawCurrentUa > 0L else rawCurrentUa < 0L
        return if (signMatches) {
            MeasurementAssessment(true, "AVAILABLE_BY_ANDROID_CONVENTION")
        } else {
            MeasurementAssessment(false, "SIGN_CONFLICT_WITH_ANDROID_CONVENTION")
        }
    }

    /** Signed battery-side watts. This is never charger/USB input power. */
    fun batteryPowerW(rawVoltageMv: Int?, rawCurrentUa: Long?, batteryStatus: String, plugType: String): Double? {
        if (!voltage(rawVoltageMv).usable) return null
        if (!current(rawCurrentUa, batteryStatus, plugType).usable) return null
        val voltageV = rawVoltageMv!!.toDouble() / 1_000.0
        val currentA = rawCurrentUa!!.toDouble() / 1_000_000.0
        return voltageV * currentA
    }
}
