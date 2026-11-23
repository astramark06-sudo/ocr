package com.batteryscope

import kotlin.math.abs

internal data class CapacitySample(
    val timestampMs: Long,
    val bootCount: Int?,
    val percent: Int?,
    val status: String,
    val plug: String,
    val chargeCounterUah: Long?
)

internal data class CapacityEstimate(
    val capacityMah: Double,
    val healthPercent: Double,
    val validSessions: Int,
    val spreadPercent: Double,
    val confidence: String
)

internal object BatteryCapacityEstimator {
    const val DESIGN_CAPACITY_MAH = 4500.0
    private const val MIN_SOC_CHANGE_PERCENT = 12
    private const val MAX_SESSION_GAP_MS = 90_000L

    fun estimate(samples: List<CapacitySample>): CapacityEstimate? {
        if (samples.size < 2) return null
        val ordered = samples.sortedBy { it.timestampMs }
        val estimates = mutableListOf<Double>()
        var session = mutableListOf<CapacitySample>()

        fun flush() {
            if (session.size >= 2) {
                val first = session.first()
                val last = session.last()
                val start = first.percent
                val end = last.percent
                val startCharge = first.chargeCounterUah
                val endCharge = last.chargeCounterUah
                if (start != null && end != null && startCharge != null && endCharge != null) {
                    val deltaPercent = end - start
                    val deltaCharge = endCharge - startCharge
                    if (abs(deltaPercent) >= MIN_SOC_CHANGE_PERCENT &&
                        deltaPercent.toLong() * deltaCharge > 0L) {
                        val capacityMah = abs(deltaCharge).toDouble() /
                            (abs(deltaPercent).toDouble() / 100.0) / 1000.0
                        if (capacityMah in 1500.0..8000.0) estimates += capacityMah
                    }
                }
            }
            session = mutableListOf()
        }

        for (sample in ordered) {
            val mode = mode(sample)
            val valid = sample.percent?.let { it in 1..99 } == true && sample.chargeCounterUah != null && mode != null
            if (!valid) {
                flush()
                continue
            }
            val previous = session.lastOrNull()
            if (previous != null) {
                val continuous = previous.bootCount == sample.bootCount &&
                    mode(previous) == mode &&
                    sample.timestampMs - previous.timestampMs in 1L..MAX_SESSION_GAP_MS
                if (!continuous) flush()
            }
            session += sample
        }
        flush()
        if (estimates.isEmpty()) return null

        val sorted = estimates.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
            else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        val spread = if (median > 0.0) (sorted.last() - sorted.first()) / median * 100.0 else 100.0
        val confidence = when {
            sorted.size >= 5 && spread <= 15.0 -> "HIGH"
            sorted.size >= 3 && spread <= 25.0 -> "MODERATE"
            else -> "LOW"
        }
        return CapacityEstimate(
            capacityMah = median,
            healthPercent = median / DESIGN_CAPACITY_MAH * 100.0,
            validSessions = sorted.size,
            spreadPercent = spread,
            confidence = confidence
        )
    }

    fun report(samples: List<CapacitySample>): String {
        val estimate = estimate(samples) ?: return buildString {
            appendLine("Current full-charge capacity: Not yet measurable")
            appendLine("Original design capacity: 4,500 mAh")
            appendLine("Method: Android charge-counter change compared with battery-percentage change across continuous sessions.")
            appendLine("BatteryScope needs valid charge-counter readings and at least a 12-percentage-point change in one uninterrupted charging or discharging session.")
            append("Keep the recorder enabled during normal use and charging. No forced 0% discharge is required.")
        }
        return buildString {
            appendLine("Estimated current capacity: ~${estimate.capacityMah.toInt()} mAh")
            appendLine("Estimated battery health: ~${String.format(java.util.Locale.US, "%.1f", estimate.healthPercent)}%")
            appendLine("Original design capacity: 4,500 mAh")
            appendLine("Confidence: ${estimate.confidence} • ${estimate.validSessions} valid sessions • ${String.format(java.util.Locale.US, "%.1f", estimate.spreadPercent)}% session spread")
            appendLine()
            appendLine("Estimate uses Android's remaining-charge counter against state-of-charge change. It is not a laboratory capacity test.")
            append("Collect more normal charge/discharge sessions to improve confidence. Avoid deliberately draining the battery to 0%.")
        }
    }

    private fun mode(sample: CapacitySample): String? = when {
        sample.status == "CHARGING" || (sample.status == "FULL" && sample.plug != "NONE") -> "CHARGING"
        sample.status == "DISCHARGING" -> "DISCHARGING"
        else -> null
    }
}
