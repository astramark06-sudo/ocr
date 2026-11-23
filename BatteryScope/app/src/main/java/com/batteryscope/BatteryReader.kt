package com.batteryscope

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import kotlin.math.abs

class BatteryReader(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    @Suppress("DEPRECATION")
    fun read(): BatterySnapshot? {
        val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0) return null
        val current = if (Build.VERSION.SDK_INT >= 21) manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            .takeIf { it != Int.MIN_VALUE && it != 0 }?.let { abs(it.toDouble()) / 1000.0 } else null
        val counter = if (Build.VERSION.SDK_INT >= 21) manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            .takeIf { it != Int.MIN_VALUE && it > 0 }?.let { it / 1000.0 } else null
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1).takeIf { it >= 0 }?.div(10.0)
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it > 0 }?.div(1000.0)
        return BatterySnapshot(
            System.currentTimeMillis(), level, scale,
            intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
            intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN),
            temp, voltage, intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Unknown",
            current, counter, 4500
        )
    }
}
