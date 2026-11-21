package com.batteryscope

import android.app.Activity
import android.os.BatteryManager
import android.os.Bundle
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity() {
    private lateinit var batteryManager: BatteryManager
    private lateinit var values: TextView
    private lateinit var statusView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var running = false
    private var csv = StringBuilder("timestamp,battery_percent,voltage_v,current_raw_ua,current_ma,power_w,temperature_c,charge_counter_uah,status\n")
    private val sampler = object : Runnable {
        override fun run() {
            if (!running) return
            sample()
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
        buildUi()
        sample()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 28, 22, 20)
            setBackgroundColor(Color.rgb(10, 13, 18))
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            text = "BatteryScope"
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        val subtitle = TextView(this).apply {
            text = "LIVE BATTERY TELEMETRY • REALME RMX2161"
            textSize = 11f
            setTextColor(Color.rgb(130, 145, 165))
            setPadding(0, 5, 0, 22)
        }
        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(105, 220, 160))
            setPadding(0, 0, 0, 14)
        }
        values = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.rgb(230, 236, 245))
            typeface = Typeface.MONOSPACE
            setPadding(16, 18, 16, 18)
            setBackgroundColor(Color.rgb(22, 28, 38))
        }
        val note = TextView(this).apply {
            text = "Battery-side readings from Android. These are not USB charger-input measurements. Current and charge counter depend on device firmware support."
            textSize = 12f
            setTextColor(Color.rgb(150, 163, 180))
            setPadding(2, 16, 2, 18)
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        startButton = Button(this).apply { text = "Start logging"; setOnClickListener { startLogging() } }
        stopButton = Button(this).apply { text = "Stop & export CSV"; isEnabled = false; setOnClickListener { stopLogging() } }
        buttons.addView(startButton, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(stopButton, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(title); content.addView(subtitle); content.addView(statusView)
        content.addView(values); content.addView(note); content.addView(buttons)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun batteryIntent() = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    private fun prop(id: Int): Int? {
        val v = batteryManager.getIntProperty(id)
        return if (v == Int.MIN_VALUE) null else v
    }
    private fun sample() {
        val intent = batteryIntent()
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val tempTenth = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val statusText = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
            BatteryManager.BATTERY_STATUS_FULL -> "FULL"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT CHARGING"
            else -> "UNKNOWN / IDLE"
        }
        val currentUa = prop(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val chargeUah = prop(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val voltage = if (voltageMv > 0) voltageMv / 1000.0 else null
        val currentMa = currentUa?.div(1000.0)
        val powerW = if (voltage != null && currentMa != null) voltage * abs(currentMa) / 1000.0 else null
        val tempC = if (tempTenth >= 0) tempTenth / 10.0 else null
        val fmt = { x: Double?, unit: String -> if (x == null) "Unavailable" else String.format(Locale.US, "%.3f %s", x, unit) }
        statusView.text = "● $statusText     ${if (percent >= 0) "$percent%" else "--"}"
        values.text = buildString {
            appendLine("VOLTAGE       ${fmt(voltage, "V")}")
            appendLine("CURRENT       ${fmt(currentMa, "mA")}")
            appendLine("POWER         ${fmt(powerW, "W")} (estimated)")
            appendLine("TEMPERATURE   ${fmt(tempC, "°C")}")
            appendLine("CHARGE COUNT  ${chargeUah?.let { "$it µAh" } ?: "Unavailable"}")
            appendLine("BATTERY       ${if (percent >= 0) "$percent %" else "Unavailable"}")
            appendLine()
            append("Refresh: 500 ms • ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}")
        }
        if (running) {
            val row = listOf(System.currentTimeMillis().toString(), percent.toString(),
                voltage?.toString() ?: "", currentUa?.toString() ?: "", currentMa?.toString() ?: "",
                powerW?.toString() ?: "", tempC?.toString() ?: "", chargeUah?.toString() ?: "", statusText)
                .joinToString(",")
            csv.append(row).append('\n')
        }
    }
    private fun startLogging() {
        if (running) return
        running = true
        csv = StringBuilder("timestamp,battery_percent,voltage_v,current_raw_ua,current_ma,power_w,temperature_c,charge_counter_uah,status\n")
        startButton.isEnabled = false; stopButton.isEnabled = true
        handler.post(sampler)
    }
    private fun stopLogging() {
        running = false; handler.removeCallbacks(sampler)
        startButton.isEnabled = true; stopButton.isEnabled = false
        try {
            val name = "BatteryScope_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv"
            val file = java.io.File(getExternalFilesDir(null), name)
            file.writeText(csv.toString())
            statusView.text = "CSV SAVED: ${file.name}"
        } catch (e: Exception) { statusView.text = "CSV export failed: ${e.message}" }
    }
    override fun onDestroy() {
        running = false; handler.removeCallbacks(sampler)
        super.onDestroy()
    }
}
