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
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity() {
    private lateinit var batteryManager: BatteryManager
    private lateinit var batteryText: TextView
    private lateinit var chargerText: TextView
    private lateinit var diagnosticText: TextView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var logging = false
    private var csv = StringBuilder()
    private val sampler = object : Runnable {
        override fun run() {
            if (!logging) return
            refreshReadings()
            handler.postDelayed(this, 1000L)
        }
    }

    private data class Reading(
        val percent: Int?,
        val voltageMv: Int?,
        val currentUa: Long?,
        val averageCurrentUa: Long?,
        val chargeCounterUah: Long?,
        val temperatureC: Double?,
        val status: String,
        val supplies: Map<String, String>,
        val supplyRootReadable: Boolean
    )

    private val supplyAttributes = listOf(
        "type", "online", "present", "status", "health", "technology", "capacity",
        "voltage_now", "voltage_avg", "voltage_max", "current_now", "current_avg",
        "current_max", "power_now", "charge_counter", "charge_now", "charge_full",
        "charge_full_design", "temp", "temp_alert_min", "temp_alert_max",
        "charge_type", "usb_type", "real_type", "input_current_limit",
        "input_voltage_limit", "constant_charge_current", "constant_charge_voltage",
        "constant_charge_current_max", "constant_charge_voltage_max",
        "current_limit", "voltage_limit", "max_voltage", "max_current"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
        buildUi()
        refreshReadings()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 24, 20, 18)
            setBackgroundColor(Color.rgb(9, 13, 20))
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            text = "BatteryScope"
            textSize = 29f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        val subtitle = TextView(this).apply {
            text = "DEVICE POWER DIAGNOSTICS  •  RMX2161"
            textSize = 11f
            setTextColor(Color.rgb(139, 156, 179))
            setPadding(0, 5, 0, 18)
        }
        statusText = makeText(14f, Color.rgb(120, 220, 170), false).apply {
            setPadding(0, 0, 0, 12)
        }
        content.addView(title)
        content.addView(subtitle)
        content.addView(statusText)

        batteryText = addPanel(content, "BATTERY • ANDROID API")
        chargerText = addPanel(content, "CHARGER / USB • SYSFS READ-ONLY")
        diagnosticText = addPanel(content, "ACCESS & CONTROL DIAGNOSTICS")

        val note = makeText(12f, Color.rgb(150, 164, 184), false).apply {
            text = "Important: battery current is not USB input current. Charger values appear only when the phone kernel exposes readable power-supply attributes. BatteryScope does not write to charging controls or change the phone's charging negotiation."
            setPadding(2, 14, 2, 18)
        }
        content.addView(note)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val refreshButton = Button(this).apply {
            text = "Refresh"
            setOnClickListener { refreshReadings() }
        }
        startButton = Button(this).apply {
            text = "Start CSV log"
            setOnClickListener { startLogging() }
        }
        stopButton = Button(this).apply {
            text = "Stop & export"
            isEnabled = false
            setOnClickListener { stopLogging() }
        }
        buttons.addView(refreshButton, LinearLayout.LayoutParams(0, -2, 0.8f))
        buttons.addView(startButton, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(stopButton, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(buttons)

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun makeText(size: Float, color: Int, mono: Boolean): TextView =
        TextView(this).apply {
            textSize = size
            setTextColor(color)
            if (mono) typeface = Typeface.MONOSPACE
            setPadding(14, 14, 14, 14)
            setBackgroundColor(Color.rgb(20, 28, 40))
        }

    private fun addPanel(parent: LinearLayout, heading: String): TextView {
        val label = TextView(this).apply {
            text = heading
            textSize = 11f
            setTextColor(Color.rgb(128, 174, 232))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(2, 14, 2, 7)
        }
        val body = makeText(13f, Color.rgb(230, 236, 245), true)
        parent.addView(label)
        parent.addView(body)
        return body
    }

    private fun batteryIntent(): Intent? =
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun longProperty(id: Int): Long? {
        return try {
            val value = batteryManager.getLongProperty(id)
            if (value == Long.MIN_VALUE) null else value
        } catch (_: Exception) {
            null
        }
    }

    private fun readSupplyNodes(): Pair<Map<String, String>, Boolean> {
        val root = File("/sys/class/power_supply")
        val dirs = try { root.listFiles() } catch (_: Exception) { null }
            ?: return Pair(emptyMap(), false)
        val result = linkedMapOf<String, String>()
        for (dir in dirs.sortedBy { it.name }) {
            if (!dir.isDirectory) continue
            for (attribute in supplyAttributes) {
                val file = File(dir, attribute)
                if (!file.isFile) continue
                val value = try {
                    file.readText().trim().take(180)
                } catch (_: Exception) {
                    null
                }
                if (!value.isNullOrBlank()) {
                    result["${dir.name}/$attribute"] = value
                }
            }
        }
        return Pair(result, true)
    }

    private fun readSnapshot(): Reading {
        val intent = batteryIntent()
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else null
        val rawVoltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        // ACTION_BATTERY_CHANGED reports voltage in millivolts. Reject implausible values.
        val voltage = rawVoltage.takeIf { it in 2500..5000 }
        val rawTemp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val temp = if (rawTemp in 100..800) rawTemp / 10.0 else null
        val statusCode = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val status = when (statusCode) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
            BatteryManager.BATTERY_STATUS_FULL -> "FULL"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT CHARGING"
            else -> "UNKNOWN / IDLE"
        }
        val (supplies, readable) = readSupplyNodes()
        return Reading(
            percent, voltage,
            longProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            longProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
            longProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            temp, status, supplies, readable
        )
    }

    private fun refreshReadings() {
        val reading = readSnapshot()
        val voltageV = reading.voltageMv?.div(1000.0)
        val currentMa = reading.currentUa?.div(1000.0)
        val powerW = if (voltageV != null && currentMa != null) voltageV * currentMa / 1000.0 else null
        val f = { value: Double?, unit: String ->
            if (value == null || !value.isFinite()) "Unavailable"
            else String.format(Locale.US, "%.3f %s", value, unit)
        }

        statusText.text = "● ${reading.status}     ${reading.percent?.let { "$it%" } ?: "--"}"
        batteryText.text = buildString {
            appendLine("Battery level       ${reading.percent?.let { "$it %" } ?: "Unavailable"}")
            appendLine("Battery voltage     ${f(voltageV, "V")}  ${reading.voltageMv?.let { "($it mV)" } ?: ""}")
            appendLine("Battery current     ${f(currentMa, "mA")}")
            appendLine("Current raw         ${reading.currentUa?.let { "$it µA" } ?: "Unavailable"}")
            appendLine("Average current     ${reading.averageCurrentUa?.let { String.format(Locale.US, "%.3f mA", it / 1000.0) } ?: "Unavailable"}")
            appendLine("Battery power       ${f(powerW, "W")}  (calculated)")
            appendLine("Temperature         ${f(reading.temperatureC, "°C")}")
            append("Charge counter      ${reading.chargeCounterUah?.let { "$it µAh  (${String.format(Locale.US, "%.0f mAh", it / 1000.0)})" } ?: "Unavailable"}")
        }

        val external = reading.supplies.filterKeys { key ->
            val node = key.substringBefore('/').lowercase(Locale.US)
            node != "battery" && node != "bms" && node != "main"
        }
        val batteryNode = reading.supplies.filterKeys { key ->
            val node = key.substringBefore('/').lowercase(Locale.US)
            node == "battery" || node == "bms" || node == "main"
        }
        chargerText.text = buildString {
            appendLine("Kernel power-supply directory: ${if (reading.supplyRootReadable) "readable" else "not accessible"}")
            appendLine()
            appendLine("EXTERNAL / CHARGER NODES")
            if (external.isEmpty()) {
                appendLine("No readable USB / AC / charger attributes found.")
            } else {
                external.forEach { (key, value) -> appendLine("$key = $value") }
            }
            appendLine()
            appendLine("BATTERY / BMS KERNEL NODES")
            if (batteryNode.isEmpty()) {
                append("No readable battery sysfs attributes found.")
            } else {
                batteryNode.forEach { (key, value) -> appendLine("$key = $value") }
            }
        }

        val connected = reading.supplies.any { (key, value) ->
            key.endsWith("/online") && value == "1" &&
                key.substringBefore('/').lowercase(Locale.US) !in listOf("battery", "bms")
        }
        diagnosticText.text = buildString {
            appendLine("External power online: ${if (connected) "YES (kernel attribute)" else "not confirmed"}")
            appendLine("Power-supply nodes: ${reading.supplies.keys.map { it.substringBefore('/') }.distinct().joinToString().ifBlank { "none readable" }}")
            appendLine("Fast-charge protocol: ${protocolSummary(reading.supplies)}")
            appendLine("Charger write control: NOT USED")
            appendLine("Charging control access: unavailable through standard Android app APIs")
            append("Reading interval: 1 second • ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}")
        }

        if (logging) {
            val sysfsJson = JSONObject(reading.supplies).toString()
            val row = listOf(
                System.currentTimeMillis().toString(),
                reading.percent?.toString() ?: "",
                reading.status,
                reading.voltageMv?.toString() ?: "",
                reading.currentUa?.toString() ?: "",
                reading.averageCurrentUa?.toString() ?: "",
                reading.chargeCounterUah?.toString() ?: "",
                reading.temperatureC?.toString() ?: "",
                csvEscape(sysfsJson)
            ).joinToString(",")
            csv.append(row).append('\n')
        }
    }

    private fun protocolSummary(supplies: Map<String, String>): String {
        val matches = supplies.filterKeys {
            val field = it.substringAfter('/')
            field == "charge_type" || field == "usb_type" || field == "real_type"
        }
        if (matches.isEmpty()) return "not exposed by readable kernel attributes"
        return matches.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun csvEscape(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

    private fun startLogging() {
        if (logging) return
        logging = true
        csv = StringBuilder("timestamp_ms,battery_percent,battery_status,battery_voltage_mv,battery_current_ua,battery_average_current_ua,charge_counter_uah,temperature_c,sysfs_snapshot_json\n")
        startButton.isEnabled = false
        stopButton.isEnabled = true
        refreshReadings()
        handler.postDelayed(sampler, 1000L)
    }

    private fun stopLogging() {
        logging = false
        handler.removeCallbacks(sampler)
        startButton.isEnabled = true
        stopButton.isEnabled = false
        try {
            val directory = getExternalFilesDir(null) ?: filesDir
            val name = "BatteryScope_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv"
            val file = File(directory, name)
            file.writeText(csv.toString())
            statusText.text = "● CSV EXPORTED: $name"
        } catch (e: Exception) {
            statusText.text = "● CSV EXPORT FAILED: ${e.message ?: "unknown error"}"
        }
    }

    override fun onDestroy() {
        logging = false
        handler.removeCallbacks(sampler)
        super.onDestroy()
    }
}
