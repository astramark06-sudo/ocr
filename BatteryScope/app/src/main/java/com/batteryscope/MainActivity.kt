package com.batteryscope

import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Process
import android.os.HandlerThread
import java.util.concurrent.atomic.AtomicBoolean
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.net.Uri
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

class MainActivity : Activity() {
    private lateinit var batteryManager: BatteryManager
    private lateinit var batteryText: TextView
    private lateinit var chargerText: TextView
    private lateinit var diagnosticText: TextView
    private lateinit var statusText: TextView
    private lateinit var historyText: TextView
    private lateinit var capacityText: TextView
    private var historyUpdatedAtMs = 0L
    private var historyLoadInFlight = false
    private var exportInFlight = false
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var samplingModeButton: Button
    private var cachedSupplyNodes: Map<String, String> = emptyMap()
    private var cachedSupplyRootVisible = false
    private var supplyScanCompletedAtMs = 0L

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var telemetryThread: HandlerThread
    private lateinit var telemetryHandler: android.os.Handler
    private val telemetryReadInFlight = AtomicBoolean(false)
    private var pendingExportFile: File? = null

    private val sampler = object : Runnable {
        override fun run() {
            refreshReadings()
            handler.postDelayed(this, 2000L)
        }
    }

    private data class Reading(
        val percent: Int?,
        val batteryPercentStatus: String,
        val rawVoltageMv: Int?,
        val currentUa: Long?,
        val currentPropertyStatus: String,
        val averageCurrentUa: Long?,
        val averageCurrentPropertyStatus: String,
        val chargeCounterUah: Long?,
        val chargeCounterPropertyStatus: String,
        val energyNwh: Long?,
        val energyPropertyStatus: String,
        val temperatureC: Double?,
        val batteryBroadcastAvailable: Boolean,
        val status: String,
        val plugged: String,
        val health: String,
        val technology: String,
        val maxChargingCurrentUa: Int?,
        val maxChargingVoltageUv: Int?,
        val batteryPresent: Boolean?,
        val invalidCharger: Int?,
        val cycleCount: Int?,
        val supplies: Map<String, String>,
        val supplyRootReadable: Boolean
    )

    private val supplyAttributes = listOf(
        "type", "online", "present", "status", "health", "technology", "capacity",
        "voltage_now", "voltage_avg", "voltage_max", "voltage_min",
        "current_now", "current_avg", "current_max", "power_now",
        "charge_counter", "charge_now", "charge_full", "charge_full_design",
        "temp", "temp_alert_min", "temp_alert_max", "charge_type", "usb_type",
        "real_type", "input_current_limit", "input_current_limit_max",
        "input_voltage_limit", "input_voltage_limit_max", "input_power_limit",
        "constant_charge_current", "constant_charge_voltage",
        "constant_charge_current_max", "constant_charge_voltage_max",
        "charge_control_limit", "charge_control_limit_max",
        "charge_control_start_threshold", "charge_control_end_threshold",
        "current_limit", "voltage_limit", "max_voltage", "max_current",
        "fastcharger", "fast_chg_type", "voocchg_ing", "charge_technology", "adapter_fw_update", "mmi_charging_enable", "stop_charging_enable", "chargerid_volt", "charger_ic", "usb_status", "typec_cc_orientation", "typec_sbu_voltage"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
        telemetryThread = HandlerThread("BatteryScopeLiveTelemetry").apply { start() }
        telemetryHandler = android.os.Handler(telemetryThread.looper)
        buildUi()
        refreshReadings()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(sampler)
        handler.post(sampler)
        updateRecorderControls()
        refreshHistoryDisplay(true)
    }

    override fun onPause() {
        handler.removeCallbacks(sampler)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::telemetryThread.isInitialized) telemetryThread.quitSafely()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
            setBackgroundColor(Color.rgb(8, 13, 22))
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 18)
        }

        val title = TextView(this).apply {
            text = "BatteryScope"
            textSize = 30f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        val subtitle = TextView(this).apply {
            text = "POWER PATH & BATTERY ANALYSER  •  RMX2161"
            textSize = 10f
            setTextColor(Color.rgb(143, 163, 190))
            setPadding(1, 4, 1, 16)
        }
        statusText = makeText(15f, Color.rgb(142, 226, 185), false).apply {
            setPadding(14, 14, 14, 14)
        }
        content.addView(title)
        content.addView(subtitle)
        content.addView(statusText)

        batteryText = addPanel(content, "01  BATTERY TELEMETRY  •  ANDROID FRAMEWORK")
        chargerText = addPanel(content, "02  POWER SUPPLY NODES  •  KERNEL")
        diagnosticText = addPanel(content, "03  ACCESS, SOURCE & DATA QUALITY")
        historyText = addPanel(content, "04  CHARGE / DISCHARGE HISTORY & APP USAGE")
        historyText.text = "No recorder history yet. Start the all-day recorder to build daily, weekly and monthly data."
        capacityText = addPanel(content, "05  BATTERY HEALTH & FULL-CHARGE CAPACITY")
        capacityText.text = "Capacity estimate not available yet. Record several charging/discharging sessions with the recorder enabled."

        val note = makeText(11f, Color.rgb(155, 170, 191), false).apply {
            text = "Measurement rules: battery current is not USB input current. Only USB/charger supply nodes can report charger-side voltage/current. BatteryScope does not decode the proprietary SuperDart wire handshake, alter the selected USB protocol or write charging controls."
            setPadding(2, 12, 2, 16)
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
            text = "Start all-day recorder"
            setOnClickListener { startLogging() }
        }
        stopButton = Button(this).apply {
            text = "Stop recorder"
            isEnabled = false
            setOnClickListener { stopLogging() }
        }
        buttons.addView(refreshButton, LinearLayout.LayoutParams(0, -2, 0.8f))
        buttons.addView(startButton, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(stopButton, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(buttons)

        val historyButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val usageButton = Button(this).apply {
            text = "Enable app usage tracking"
            setOnClickListener { openUsageAccessSettings() }
        }
        val exportButton = Button(this).apply {
            text = "Export all history"
            setOnClickListener { exportRecorderHistory() }
        }
        historyButtons.addView(usageButton, LinearLayout.LayoutParams(0, -2, 1f))
        historyButtons.addView(exportButton, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(historyButtons)
        val batterySettingsButton = Button(this).apply {
            text = "Battery optimisation settings"
            setOnClickListener { openBatteryOptimisationSettings() }
        }
        content.addView(batterySettingsButton, LinearLayout.LayoutParams(-1, -2))
        samplingModeButton = Button(this).apply {
            setOnClickListener { toggleSamplingMode() }
        }
        content.addView(samplingModeButton, LinearLayout.LayoutParams(-1, -2))
        updateSamplingModeButton()

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
            setBackgroundColor(Color.rgb(20, 29, 43))
            setTextIsSelectable(true)
        }

    private fun addPanel(parent: LinearLayout, heading: String): TextView {
        val label = TextView(this).apply {
            text = heading
            textSize = 10f
            setTextColor(Color.rgb(123, 177, 242))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(2, 17, 2, 7)
        }
        val body = makeText(12f, Color.rgb(230, 237, 247), true)
        parent.addView(label)
        parent.addView(body)
        return body
    }

    private fun batteryIntent(): Intent? =
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private data class PropertyRead(val value: Long?, val status: String)

    private fun readLongProperty(id: Int): PropertyRead = try {
        val value = batteryManager.getLongProperty(id)
        if (value == Long.MIN_VALUE || value == Long.MAX_VALUE) {
            PropertyRead(null, "ANDROID_UNAVAILABLE_SENTINEL")
        } else {
            PropertyRead(value, "AVAILABLE")
        }
    } catch (t: SecurityException) {
        PropertyRead(null, "SECURITY_EXCEPTION")
    } catch (t: Throwable) {
        PropertyRead(null, "ERROR:" + t.javaClass.simpleName)
    }

    /** Enumerates read-only kernel power, USB-C, PD, thermal and hwmon class attributes. */
    private fun readSupplyNodes(): Pair<Map<String, String>, Boolean> {
        val now = System.currentTimeMillis()
        // BatteryManager refreshes on the dashboard cadence; sysfs traversal is separately cached.
        if (supplyScanCompletedAtMs > 0L && now >= supplyScanCompletedAtMs &&
            now - supplyScanCompletedAtMs < SYSFS_UI_REFRESH_MS) {
            return cachedSupplyNodes to cachedSupplyRootVisible
        }
        val roots = linkedMapOf(
            "power_supply" to "/sys/class/power_supply",
            "typec" to "/sys/class/typec",
            "usb_power_delivery" to "/sys/class/usb_power_delivery",
            "dual_role_usb" to "/sys/class/dual_role_usb",
            "usb_role" to "/sys/class/usb_role",
            "thermal" to "/sys/class/thermal",
            "hwmon" to "/sys/class/hwmon"
        )
        val result = linkedMapOf<String, String>()
        val excluded = setOf("device", "subsystem", "driver", "power", "firmware_node", "of_node")
        var visibleRoots = 0
        var powerSupplyRootVisible = false
        for ((prefix, rootPath) in roots) {
            val root = File(rootPath)
            if (!root.exists()) {
                result["@diagnostic/" + prefix + "/root"] = "ABSENT_OR_HIDDEN_BY_SELINUX"
                continue
            }
            val dirs = try { root.listFiles()?.sortedBy { it.name } } catch (_: Throwable) { null }
            if (dirs == null) {
                result["@diagnostic/" + prefix + "/root"] = "LIST_DENIED"
                continue
            }
            visibleRoots++
            if (prefix == "power_supply") powerSupplyRootVisible = true
            result["@diagnostic/" + prefix + "/root"] = "VISIBLE"
            for (dir in dirs) {
                if (result.size >= 1200) break
                if (dir.name in excluded) continue
                val attrs = try {
                    if (dir.isDirectory) dir.listFiles()?.sortedBy { it.name } else listOf(dir)
                } catch (_: Throwable) { null }
                if (attrs == null) {
                    result["@diagnostic/" + prefix + "/" + dir.name] = "LIST_DENIED"
                    continue
                }
                for (file in attrs) {
                    if (result.size >= 1200) break
                    if (file.name in excluded || !file.isFile) continue
                    val value = try {
                        if (!file.canRead()) "READ_DENIED"
                        else file.inputStream().bufferedReader().use { it.readLine()?.trim()?.take(240) ?: "EMPTY" }
                    } catch (t: Throwable) {
                        "ERROR:" + t.javaClass.simpleName + ":" + (t.message ?: "unknown")
                    }
                    val key = if (prefix == "power_supply") dir.name + "/" + file.name
                        else prefix + "/" + dir.name + "/" + file.name
                    result[key] = value
                }
            }
        }
        result["@diagnostic/visible_roots"] = visibleRoots.toString()
        val capturedFields = result.keys.count { !it.startsWith("@") }
        result["@field_count"] = capturedFields.toString()
        result["@field_limit"] = "1200"
        result["@field_limit_reached"] = (result.size >= 1200).toString()
        if (Build.VERSION.SDK_INT >= 29) {
            result["@android_thermal_status"] = try {
                (getSystemService(POWER_SERVICE) as android.os.PowerManager).currentThermalStatus.toString()
            } catch (t: Throwable) {
                "UNAVAILABLE:" + t.javaClass.simpleName + ":" + (t.message ?: "unknown")
            }
        }
        cachedSupplyNodes = result
        cachedSupplyRootVisible = powerSupplyRootVisible
        supplyScanCompletedAtMs = now
        return result to powerSupplyRootVisible
    }

    private fun buildMeasurementDiagnosis(nodes: Map<String, String>, powerSupplyRootVisible: Boolean): String {
        val lines = mutableListOf<String>()
        lines += "AUTOMATIC MEASUREMENT ACCESS DIAGNOSIS"
        lines += "Power-supply root: " + if (powerSupplyRootVisible) "visible" else "not visible / not enumerable"
        val rootChecks = nodes.filterKeys { it.startsWith("@diagnostic/") && it.endsWith("/root") }
        if (rootChecks.isNotEmpty()) {
            lines += "Kernel class-root access results:"
            rootChecks.toSortedMap().forEach { (key, value) -> lines += "  ${key.removePrefix("@diagnostic/")} = $value" }
        }
        lines += "Foreground-app attribution permission: " + if (hasUsageAccessNow()) "GRANTED; foreground-time correlation available" else "NOT GRANTED; app-specific foreground correlation unavailable"
        lines += "Battery sampling source: ACTION_BATTERY_CHANGED + BatteryManager public properties; unavailable sentinels are retained as unavailable"
        val keepAwake = getSharedPreferences(BatteryRecorderService.PREFS, MODE_PRIVATE)
            .getBoolean(BatteryRecorderService.KEY_KEEP_CPU_AWAKE, true)
        lines += "Recorder screen-off mode: " + if (keepAwake) "continuous CPU wake lock enabled (more complete sampling; adds app drain)" else "low-impact mode; CPU may suspend and create screen-off gaps"
        val recorderPrefs = getSharedPreferences(BatteryRecorderService.PREFS, MODE_PRIVATE)
        val bootResumeFailure = recorderPrefs.getString(BatteryRecorderService.KEY_BOOT_RESUME_FAILURE, null)
        if (!bootResumeFailure.isNullOrBlank()) {
            val failedAt = recorderPrefs.getLong(BatteryRecorderService.KEY_BOOT_RESUME_FAILURE_AT, 0L)
            lines += "Last automatic boot-resume failure: " + bootResumeFailure +
                if (failedAt > 0L) " at " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(failedAt)) else ""
        } else {
            lines += "Last automatic boot-resume failure: none recorded"
        }
        val nonSupplyRoots = setOf("typec", "usb_power_delivery", "dual_role_usb", "usb_role", "thermal", "hwmon", "@diagnostic")
        val supplies = nodes.keys.filter { !it.startsWith("@") && it.substringBefore("/") !in nonSupplyRoots }
            .map { it.substringBefore("/") }.distinct()
        val external = supplies.filter { it !in listOf("battery", "bms", "main") }
        lines += "External power-supply entries: " + external.joinToString().ifBlank { "none discovered" }
        var usbPairFound = false
        var nonUsbPairFound = false
        for (name in external) {
            val base = name + "/"
            val voltage = nodes[base + "voltage_now"]?.toLongOrNull()
            val current = nodes[base + "current_now"]?.toLongOrNull()
            val type = nodes[base + "type"]?.uppercase(Locale.US).orEmpty()
            val usbSupply = name.contains("usb", ignoreCase = true) || type.contains("USB")
            lines += "Supply " + name + ": type=" + (nodes[base + "type"] ?: "not exported") +
                "; online=" + (nodes[base + "online"] ?: "not exported")
            if (voltage != null && current != null &&
                voltage in 1_000_000L..30_000_000L && kotlin.math.abs(current) <= 20_000_000L) {
                if (usbSupply) {
                    usbPairFound = true
                    lines += "  USB-source same-supply V/I candidate: " +
                        String.format(Locale.US, "%.3f V / %.3f A", voltage / 1_000_000.0, current / 1_000_000.0)
                    lines += "  Candidate USB input power: " +
                        String.format(Locale.US, "%.3f W", voltage.toDouble() * current.toDouble() / 1.0e12) +
                        "; source path/driver semantics still require handset validation"
                } else {
                    nonUsbPairFound = true
                    lines += "  External same-supply V/I pair exists, but supply name/type does not identify USB; NOT classified as VBUS/IBUS or USB input power."
                    lines += "  Raw candidate: " + voltage + " µV / " + current + " µA"
                }
            } else {
                lines += "  No plausible same-supply live pair: voltage_now=" +
                    (nodes[base + "voltage_now"] ?: "NOT EXPORTED") + "; current_now=" +
                    (nodes[base + "current_now"] ?: "NOT EXPORTED")
            }
            val limits = listOf("input_voltage_limit", "input_current_limit", "input_power_limit", "constant_charge_current_max")
                .mapNotNull { key -> nodes[base + key]?.let { key + "=" + it } }
            if (limits.isNotEmpty()) lines += "  CONFIGURED LIMITS (not measurements): " + limits.joinToString()
        }
        if (!usbPairFound) {
            lines += "USB input V/A/W unavailable as a USB-identified source: no external supply with USB name/type exposed a plausible same-supply voltage_now/current_now pair."
        }
        if (nonUsbPairFound) lines += "At least one external supply exposed a numeric pair, but its source identity is not USB; no USB claim was made for it."
        val usb = nodes.filterKeys {
            it.startsWith("typec/") || it.startsWith("usb_power_delivery/") ||
                it.startsWith("dual_role_usb/") || it.startsWith("usb_role/")
        }
        lines += "USB-C / Type-C / PD / role attributes discovered: " + usb.size
        if (usb.isEmpty()) lines += "USB-C interface diagnosis: class absent, hidden by access controls, or not enumerable by ordinary app UID."
        val thermal = nodes.filterKeys { it.startsWith("thermal/") || it.startsWith("hwmon/") }
        lines += "Thermal / hwmon attributes discovered: " + thermal.size
        lines += "Android thermal status: " + (nodes["@android_thermal_status"] ?: "not available from PowerManager on this Android build")
        lines += "Raw kernel attributes captured: " + (nodes["@field_count"] ?: nodes.size.toString())
        if (nodes["@field_limit_reached"] == "true") lines += "WARNING: bounded kernel scan reached its configured field cap of " + (nodes["@field_limit"] ?: "unknown") + "; later paths were not scanned.
        val denied = nodes.filterValues { it == "READ_DENIED" || it == "LIST_DENIED" || it.startsWith("ERROR:") }
        lines += "Permission / read errors: " + denied.size
        denied.entries.take(20).forEach { lines += "  " + it.key + " = " + it.value }
        lines += "Root: not invoked automatically; no su prompt or root write attempted."
        lines += "PDO capabilities, configured limits and VOOC flags are not classified as measured USB input power."
        lines += "No kernel attributes were written."
        return lines.joinToString("\n")
    }

    private fun readSnapshot(): Reading {
        val intent = batteryIntent()
        val level = intent?.extras?.let {
            if (it.containsKey(BatteryManager.EXTRA_LEVEL)) it.getInt(BatteryManager.EXTRA_LEVEL, -1) else -1
        } ?: -1
        val scale = intent?.extras?.let {
            if (it.containsKey(BatteryManager.EXTRA_SCALE)) it.getInt(BatteryManager.EXTRA_SCALE, -1) else -1
        } ?: -1
        val percent = BatteryPercentMath.percent(level, scale)
        val percentStatus = when {
            intent == null -> "ACTION_BATTERY_CHANGED_UNAVAILABLE"
            !intent.hasExtra(BatteryManager.EXTRA_LEVEL) -> "EXTRA_LEVEL_MISSING"
            !intent.hasExtra(BatteryManager.EXTRA_SCALE) -> "EXTRA_SCALE_MISSING"
            level < 0 -> "EXTRA_LEVEL_INVALID"
            scale <= 0 -> "EXTRA_SCALE_NON_POSITIVE"
            level > scale -> "EXTRA_LEVEL_EXCEEDS_SCALE"
            else -> "AVAILABLE"
        }
        val rawVoltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val rawTemp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val temp = if (rawTemp in 0..1000) rawTemp / 10.0 else null
        val statusCode = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val status = when (statusCode) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
            BatteryManager.BATTERY_STATUS_FULL -> "FULL"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT CHARGING"
            else -> "UNKNOWN"
        }
        val pluggedCode = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val plugged = when (pluggedCode) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
            else -> if (pluggedCode == 0) "NONE / NOT REPORTED" else "OTHER ($pluggedCode)"
        }
        val healthCode = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val health = when (healthCode) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
            BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER VOLTAGE"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "UNSPECIFIED FAILURE"
            BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
            else -> "UNKNOWN"
        }
        val technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "NOT REPORTED"
        val currentRead = readLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val averageRead = readLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        val chargeRead = readLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val energyRead = readLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        val (supplies, readable) = readSupplyNodes()
        return Reading(
            percent = percent,
            batteryPercentStatus = percentStatus,
            rawVoltageMv = rawVoltage.takeIf { it > 0 },
            currentUa = currentRead.value,
            currentPropertyStatus = currentRead.status,
            averageCurrentUa = averageRead.value,
            averageCurrentPropertyStatus = averageRead.status,
            chargeCounterUah = chargeRead.value,
            chargeCounterPropertyStatus = chargeRead.status,
            energyNwh = energyRead.value,
            energyPropertyStatus = energyRead.status,
            temperatureC = temp,
            batteryBroadcastAvailable = intent != null,
            status = status,
            plugged = plugged,
            health = health,
            technology = technology,
            maxChargingCurrentUa = intent?.getIntExtra("max_charging_current", -1)?.takeIf { it > 0 },
            maxChargingVoltageUv = intent?.getIntExtra("max_charging_voltage", -1)?.takeIf { it > 0 },
            batteryPresent = intent?.extras?.let { if (it.containsKey("present")) it.getBoolean("present") else null },
            invalidCharger = intent?.extras?.let { if (it.containsKey("invalid_charger")) it.getInt("invalid_charger") else null },
            cycleCount = intent?.getIntExtra("cycle_count", -1)?.takeIf { it >= 0 },
            supplies = supplies,
            supplyRootReadable = readable
        )
    }

    private fun refreshReadings() {
        if (!telemetryReadInFlight.compareAndSet(false, true)) return
        telemetryHandler.post {
        try {
        val reading = readSnapshot()
        val voltageV = reading.rawVoltageMv?.takeIf { it in 2500..5000 }?.div(1000.0)
        val currentMa = reading.currentUa?.div(1000.0)
        val estimatedBatteryPowerW = if (voltageV != null && currentMa != null) {
            voltageV * currentMa / 1000.0
        } else null
        val format = { value: Double?, unit: String ->
            if (value == null || !value.isFinite()) "Unavailable"
            else String.format(Locale.US, "%.3f %s", value, unit)
        }

        val statusReport = "${reading.status}   •   ${reading.percent?.let { "$it%" } ?: "--"}   •   POWER SOURCE: ${reading.plugged}"
        val external = reading.supplies.filterKeys { key ->
            val node = key.substringBefore('/').lowercase(Locale.US)
            node !in listOf("battery", "bms", "main")
        }
        val batteryNodes = reading.supplies.filterKeys { key ->
            val node = key.substringBefore('/').lowercase(Locale.US)
            node in listOf("battery", "bms", "main")
        }
        fun batteryGaugeValue(field: String): Long? = batteryNodes.entries
            .firstOrNull { it.key.substringAfterLast('/') == field }
            ?.value?.toLongOrNull()
        val kernelCycleCount = batteryGaugeValue("cycle_count")
        val kernelFullChargeUah = batteryGaugeValue("charge_full")?.takeIf { it > 0L }
        val kernelDesignChargeUah = batteryGaugeValue("charge_full_design")?.takeIf { it > 0L }
        val kernelHealthPercent = if (kernelFullChargeUah != null && kernelDesignChargeUah != null)
            kernelFullChargeUah.toDouble() / kernelDesignChargeUah.toDouble() * 100.0 else null
        val batteryReport = buildString {
            appendLine("Battery level          ${reading.percent?.let { "$it %" } ?: "Unavailable"}")
            appendLine("Battery voltage        ${reading.rawVoltageMv?.let { if (it in 2500..5000) "$it mV raw" else "$it mV raw — INVALID / outside expected battery range" } ?: "Unavailable"}")
            appendLine("Battery current        ${format(currentMa, "mA")}  (${reading.currentUa?.let { "$it µA raw" } ?: "Unavailable"})")
            appendLine("Average current        ${reading.averageCurrentUa?.let { String.format(Locale.US, "%.3f mA (%d µA)", it / 1000.0, it) } ?: "Unavailable"}")
            appendLine("Battery power estimate ${format(estimatedBatteryPowerW, "W")}  [battery-side only]")
            appendLine("Remaining-charge counter ${reading.chargeCounterUah?.let { "$it µAh / ${String.format(Locale.US, "%.1f mAh", it / 1000.0)} (Android estimate; not full-charge capacity)" } ?: "Unavailable"}")
            appendLine("Energy counter         ${reading.energyNwh?.let { "$it nWh / ${String.format(Locale.US, "%.3f Wh", it / 1_000_000_000.0)}" } ?: "Unavailable"}")
            appendLine("Temperature            ${format(reading.temperatureC, "°C")}")
            appendLine("Android battery health ${reading.health} (framework status; not capacity health)")
            appendLine("Technology             ${reading.technology}")
            appendLine("Battery present        ${reading.batteryPresent?.toString() ?: "NOT REPORTED"}")
            appendLine("Invalid charger flag   ${reading.invalidCharger?.let { if (it != 0) "YES ($it)" else "NO (0)" } ?: "NOT REPORTED BY FRAMEWORK"}")
            appendLine("Charge cycle count     ${reading.cycleCount?.toString() ?: kernelCycleCount?.toString() ?: "NOT EXPOSED"}")
            appendLine("Kernel charge_full     ${kernelFullChargeUah?.let { String.format(Locale.US, "%.1f mAh (raw µAh)", it / 1000.0) } ?: "NOT EXPOSED"}")
            appendLine("Kernel design capacity ${kernelDesignChargeUah?.let { String.format(Locale.US, "%.1f mAh (raw µAh)", it / 1000.0) } ?: "NOT EXPOSED"}")
            appendLine("Kernel gauge capacity health ${kernelHealthPercent?.let { String.format(Locale.US, "%.1f%% (charge_full / charge_full_design)", it) } ?: "UNAVAILABLE — both kernel capacity attributes are required"}")
            appendLine("Max charging current   ${reading.maxChargingCurrentUa?.let { String.format(Locale.US, "%.0f mA (%d µA)", it / 1000.0, it) } ?: "NOT REPORTED"}")
            append("Max charging voltage   ${reading.maxChargingVoltageUv?.let { String.format(Locale.US, "%.3f V (%d µV)", it / 1_000_000.0, it) } ?: "NOT REPORTED"}")
        }

        val chargerReport = buildString {
            appendLine("App process sysfs access: ${if (reading.supplyRootReadable) "directory listed" else "denied / unavailable"}")
            appendLine("External power / charger nodes")
            if (external.isEmpty()) appendLine("No readable external-supply attributes from the ordinary app process.")
            else external.forEach { (key, value) -> appendLine(formatSupplyValue(key, value)) }
            appendLine()
            appendLine("Battery / BMS nodes")
            if (batteryNodes.isEmpty()) append("No readable battery attributes from the ordinary app process.")
            else batteryNodes.forEach { (key, value) -> appendLine(formatSupplyValue(key, value)) }
        }

        val onlineNodes = reading.supplies.filter { (key, value) ->
            key.endsWith("/online") && value == "1" &&
                key.substringBefore('/').lowercase(Locale.US) !in listOf("battery", "bms", "main")
        }.keys
        val diagnosticReport = buildString {
            appendLine("Android battery status: ${reading.status}")
            appendLine("Android plug type: ${reading.plugged}")
            appendLine("Kernel external online flags: ${onlineNodes.joinToString().ifBlank { "not observed by app UID" }}")
            appendLine("Ordinary app sysfs: ${if (reading.supplyRootReadable) "directory visible; individual files may still be denied" else "not visible"}")
            appendLine("ACTION_BATTERY_CHANGED: " + if (reading.batteryBroadcastAvailable) "available" else "NOT AVAILABLE from Android sticky broadcast")
            appendLine("Battery percentage: " + (reading.percent?.let { "available (${it}%)" } ?: "unavailable — " + reading.batteryPercentStatus))
            appendLine("Battery voltage: " + (reading.rawVoltageMv?.let { "available (${it} mV)" } ?: "unavailable — EXTRA_VOLTAGE missing or invalid"))
            appendLine("Battery temperature: " + (reading.temperatureC?.let { "available (${it} °C)" } ?: "unavailable — EXTRA_TEMPERATURE missing or outside accepted range"))
            appendLine("Android charge counter: ${reading.chargeCounterUah?.let { "available (${it} µAh)" } ?: "unavailable — " + reading.chargeCounterPropertyStatus}")
            appendLine("Instantaneous battery current: ${reading.currentUa?.let { "available (${it} µA)" } ?: "unavailable — " + reading.currentPropertyStatus}")
            appendLine("Average battery current: ${reading.averageCurrentUa?.let { "available (${it} µA)" } ?: "unavailable — " + reading.averageCurrentPropertyStatus}")
            appendLine("Battery energy counter: ${reading.energyNwh?.let { "available (${it} nWh)" } ?: "unavailable — " + reading.energyPropertyStatus}")
            appendLine("Invalid charger status: ${reading.invalidCharger?.let { if (it != 0) "framework reports invalid charger ($it)" else "framework reports no invalid-charger flag" } ?: "extra not supplied by Android/ROM"}")
            appendLine("Charger advertised maximum current/voltage: ${if (reading.maxChargingCurrentUa != null || reading.maxChargingVoltageUv != null) "partially or fully reported in ACTION_BATTERY_CHANGED" else "not supplied by Android/charger; live USB input V/I may require restricted kernel driver nodes"}")
            appendLine("Battery cycle count: ${reading.cycleCount?.let { "Android extra=$it" } ?: kernelCycleCount?.let { "kernel power_supply=$it" } ?: "not exposed by Android extra or readable battery/BMS sysfs nodes"}")
            appendLine("Kernel FCC/design-capacity pair: ${if (kernelFullChargeUah != null && kernelDesignChargeUah != null) "available; health estimate " + String.format(Locale.US, "%.1f%%", kernelHealthPercent) else "unavailable — one or both charge_full / charge_full_design attributes are not readable or not exported"}")
            appendLine("Control writes: NEVER PERFORMED")
            appendLine()
            append(buildMeasurementDiagnosis(reading.supplies, reading.supplyRootReadable))
            append("Refresh: ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} • 2-second dashboard refresh")
        }

        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                statusText.text = statusReport
                batteryText.text = batteryReport
                chargerText.text = chargerReport
                diagnosticText.text = diagnosticReport
                refreshHistoryDisplay(false)
            }
        }
        } catch (t: Throwable) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) diagnosticText.text =
                    "LIVE TELEMETRY READ FAILED: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
            }
        } finally {
            telemetryReadInFlight.set(false)
        }
        }
    }

    private fun hasUsageAccessNow(): Boolean = try {
        val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) { false }

    private fun formatSupplyValue(key: String, raw: String): String {
        val field = key.substringAfter('/')
        val number = raw.toLongOrNull()
        val vendorRawOnly = field in setOf(
            "fastcharger", "fast_chg_type", "voocchg_ing", "charge_technology",
            "adapter_fw_update", "mmi_charging_enable", "stop_charging_enable",
            "chargerid_volt", "charger_ic", "usb_status", "typec_cc_orientation", "typec_sbu_voltage"
        ) || (key.startsWith("battery/") && field == "current_max")
        val converted = if (vendorRawOnly) null else when (field) {
            "voltage_now", "voltage_avg", "voltage_max", "voltage_min",
            "input_voltage_limit", "constant_charge_voltage", "constant_charge_voltage_max", "voltage_limit", "max_voltage" ->
                number?.let { String.format(Locale.US, "  [%.3f V; raw µV]", it / 1_000_000.0) }
            "current_now", "current_avg", "current_max", "input_current_limit",
            "input_current_limit_max", "constant_charge_current", "constant_charge_current_max",
            "charge_control_limit", "charge_control_limit_max", "current_limit", "max_current" ->
                number?.let { String.format(Locale.US, "  [%.1f mA; raw µA]", it / 1000.0) }
            "power_now", "input_power_limit" ->
                number?.let { String.format(Locale.US, "  [%.3f W; raw µW]", it / 1_000_000.0) }
            "charge_counter", "charge_now", "charge_full", "charge_full_design" ->
                number?.let { String.format(Locale.US, "  [%.1f mAh; raw µAh]", it / 1000.0) }
            "capacity", "charge_control_start_threshold", "charge_control_end_threshold" -> number?.let { "  [$it %]" }
            else -> null
        }
        val annotation = when (field) {
            "fastcharger" -> "  [vendor boolean; VOOC UI indicator]"
            "fast_chg_type" -> "  [vendor subtype enum; raw value only]"
            "mmi_charging_enable" -> "  [vendor state; read only; no write]"
            "stop_charging_enable" -> "  [vendor state; read only; no write]"
            "voocchg_ing" -> "  [vendor boolean; VOOC in-progress state]"
            "charge_technology" -> "  [vendor enum; raw value only]"
            "adapter_fw_update" -> "  [vendor state; raw value only]"
            "current_max" -> if (key.startsWith("battery/")) "  [vendor-specific units not assumed]" else (converted ?: "")
            else -> converted ?: ""
        }
        return "$key = $raw$annotation"
    }

    private fun csvEscape(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

    private fun startLogging() {
        try {
            val intent = Intent(this, BatteryRecorderService::class.java).setAction(BatteryRecorderService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            startButton.isEnabled = false
            stopButton.isEnabled = true
            statusText.text = "Starting persistent recorder. Target: 30-second samples; screen-off continuity depends on the selected CPU wake-lock mode."
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), NOTIFICATION_REQUEST_CODE)
            }
            handler.postDelayed({ updateRecorderControls(); refreshHistoryDisplay(true) }, 1200L)
        } catch (t: Throwable) {
            statusText.text = "RECORDER START FAILED: " + t.javaClass.simpleName + ": " + (t.message ?: "unknown")
            updateRecorderControls()
        }
    }

    private fun stopLogging() {
        try {
            startService(Intent(this, BatteryRecorderService::class.java).setAction(BatteryRecorderService.ACTION_STOP))
            statusText.text = "Recorder stop requested. Previously collected history remains stored."
        } catch (t: Throwable) {
            statusText.text = "RECORDER STOP FAILED: " + t.javaClass.simpleName + ": " + (t.message ?: "unknown")
        }
        handler.postDelayed({ updateRecorderControls(); refreshHistoryDisplay(true) }, 800L)
    }

    private fun updateRecorderControls() {
        if (!::startButton.isInitialized || !::stopButton.isInitialized) return
        val active = getSharedPreferences(BatteryRecorderService.PREFS, MODE_PRIVATE)
            .getBoolean(BatteryRecorderService.KEY_ACTIVE, false)
        startButton.isEnabled = !active
        stopButton.isEnabled = active
        updateSamplingModeButton()
        if (active) statusText.text = "All-day recorder active • 30-second target samples • local history database"
    }

    private fun toggleSamplingMode() {
        val prefs = getSharedPreferences(BatteryRecorderService.PREFS, MODE_PRIVATE)
        val keepAwake = prefs.getBoolean(BatteryRecorderService.KEY_KEEP_CPU_AWAKE, true)
        val next = !keepAwake
        prefs.edit().putBoolean(BatteryRecorderService.KEY_KEEP_CPU_AWAKE, next).apply()
        val active = prefs.getBoolean(BatteryRecorderService.KEY_ACTIVE, false)
        if (active) {
            try {
                startService(Intent(this, BatteryRecorderService::class.java).setAction(BatteryRecorderService.ACTION_RECONFIGURE))
            } catch (t: Throwable) {
                statusText.text = "Sampling mode saved, but recorder reconfiguration failed: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
            }
        }
        updateSamplingModeButton()
        statusText.text = if (next) {
            "Continuous screen-off sampling enabled. CPU wake lock improves sample continuity but increases BatteryScope's own battery use."
        } else {
            "Low-impact sampling enabled. No permanent CPU wake lock; Android may suspend sampling and history will report the resulting gaps."
        }
    }

    private fun updateSamplingModeButton() {
        if (!::samplingModeButton.isInitialized) return
        val keepAwake = getSharedPreferences(BatteryRecorderService.PREFS, MODE_PRIVATE)
            .getBoolean(BatteryRecorderService.KEY_KEEP_CPU_AWAKE, true)
        samplingModeButton.text = if (keepAwake) {
            "Sampling mode: CONTINUOUS (wake lock ON) — tap for low-impact"
        } else {
            "Sampling mode: LOW-IMPACT (wake lock OFF) — tap for continuous"
        }
    }

    private fun openUsageAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (t: Throwable) {
            statusText.text = "Could not open Usage Access settings: " + (t.message ?: "unknown")
        }
    }

    private fun openBatteryOptimisationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (t: Throwable) {
            statusText.text = "Could not open battery optimisation settings: " + (t.message ?: "unknown")
        }
    }

    private fun refreshHistoryDisplay(force: Boolean) {
        if (!::historyText.isInitialized || historyLoadInFlight) return
        val now = System.currentTimeMillis()
        if (!force && now - historyUpdatedAtMs < 300_000L) return
        historyUpdatedAtMs = now
        refreshCapacityDisplay()
        historyLoadInFlight = true
        Thread {
            var database: RecorderDatabase? = null
            val result = try {
                database = RecorderDatabase(applicationContext)
                database.historySummary(now)
            } catch (t: Throwable) {
                "History read failed: " + t.javaClass.simpleName + ": " + (t.message ?: "unknown")
            } finally {
                try { database?.close() } catch (_: Throwable) { }
            }
            runOnUiThread {
                historyLoadInFlight = false
                if (!isFinishing && ::historyText.isInitialized) historyText.text = result
            }
        }.start()
    }


    private fun refreshCapacityDisplay() {
        if (!::capacityText.isInitialized) return
        Thread {
            val report = try {
                val database = RecorderDatabase(applicationContext)
                try { database.capacityEstimateReport() } finally { database.close() }
            } catch (t: Throwable) {
                "Capacity estimate failed: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
            }
            runOnUiThread {
                if (!isFinishing && ::capacityText.isInitialized) capacityText.text = report
            }
        }.start()
    }

    private fun exportRecorderHistory() {
        if (exportInFlight) {
            statusText.text = "A history export is already in progress."
            return
        }
        exportInFlight = true
        statusText.text = "Preparing CSV history in the background…"
        Thread {
            var database: RecorderDatabase? = null
            var file: File? = null
            var failure: String? = null
            var noData = false
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val directory = File(getExternalFilesDir(null) ?: filesDir, "exports")
                if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("Could not create private export directory")
                val exportFile = File(directory, "BatteryScope_History_" + timestamp + ".csv")
                file = exportFile
                database = RecorderDatabase(applicationContext)
                exportFile.bufferedWriter(Charsets.UTF_8).use { database!!.exportCsv(it) }
                noData = exportFile.length() == 0L
            } catch (t: Throwable) {
                failure = t.javaClass.simpleName + ": " + (t.message ?: "unknown")
            } finally {
                try { database?.close() } catch (_: Throwable) { }
            }
            runOnUiThread {
                exportInFlight = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    failure != null -> statusText.text = "HISTORY EXPORT FAILED: " + failure
                    noData -> statusText.text = "No telemetry history exists to export yet."
                    file != null -> launchCsvExport(file!!)
                }
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun launchCsvExport(file: File) {
        if (!file.isFile || !file.canRead()) {
            statusText.text = "CSV export unavailable: saved file cannot be read."
            return
        }
        pendingExportFile = file
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, file.name)
        }
        try {
            startActivityForResult(intent, CSV_EXPORT_REQUEST_CODE)
        } catch (t: Throwable) {
            pendingExportFile = null
            statusText.text = "Could not open Android document picker: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}. CSV remains in app storage."
        }
    }

    @Deprecated("Handled for Android document-picker compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CSV_EXPORT_REQUEST_CODE) return
        val source = pendingExportFile
        pendingExportFile = null
        if (resultCode != RESULT_OK || data?.data == null) {
            statusText.text = "CSV remains in app storage; document export was cancelled."
            return
        }
        val destination: Uri = data.data!!
        if (source == null || !source.isFile) {
            statusText.text = "CSV export failed: source file is missing. Internal history remains stored."
            return
        }
        if (exportInFlight) return
        exportInFlight = true
        statusText.text = "Copying CSV to the selected destination in the background…"
        val resolver = contentResolver
        Thread {
            var failure: String? = null
            try {
                val output = resolver.openOutputStream(destination, "w")
                    ?: throw IllegalStateException("Android could not open the selected destination")
                output.use { stream -> source.inputStream().use { input -> input.copyTo(stream) } }
            } catch (t: Throwable) {
                failure = t.javaClass.simpleName + ": " + (t.message ?: "unknown")
            }
            runOnUiThread {
                exportInFlight = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                statusText.text = if (failure == null) {
                    "CSV exported successfully: ${source.name}"
                } else {
                    "CSV export failed: ${failure}. Internal copy retained."
                }
            }
        }.start()
    }

    companion object {
        private const val SYSFS_UI_REFRESH_MS = 15_000L
        private const val CSV_EXPORT_REQUEST_CODE = 5101
        private const val NOTIFICATION_REQUEST_CODE = 5102
    }
}
