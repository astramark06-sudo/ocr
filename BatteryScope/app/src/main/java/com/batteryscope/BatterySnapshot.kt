package com.batteryscope

data class BatterySnapshot(
    val timestamp: Long, val level: Int, val scale: Int, val status: Int,
    val plugged: Int, val health: Int, val temperatureC: Double?, val voltageV: Double?,
    val technology: String, val currentMa: Double?, val chargeCounterMah: Double?
) {
    val percent: Int get() = if (scale > 0) (level * 100f / scale).toInt().coerceIn(0, 100) else level.coerceIn(0, 100)
    val isCharging: Boolean get() = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
    val isFull: Boolean get() = status == android.os.BatteryManager.BATTERY_STATUS_FULL
    val isPlugged: Boolean get() = plugged != 0
}
