package com.batteryscope

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var batteryManager: BatteryManager
    private lateinit var batteryText: TextView
    private lateinit var chargerText: TextView
    private lateinit var diagnosticText: TextView
    private lateinit var communicationText: TextView
    private lateinit var liveLinkText: TextView
    private lateinit var controlText: TextView
    private lateinit var coolDownStatusText: TextView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private val coolDownButtons = mutableListOf<Button>()
    private var currentCoolDownLevel: Int? = null
    private var coolDownNodeWritable = false
    private var linkPollInFlight = false
    private var activityResumed = false
    private var latestLinkSnapshot = ""

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var logging = false
    private var csvWriter: java.io.BufferedWriter? = null
    private var csvFile: File? = null
    private var sessionTimestamp: String? = null
    private var csvSampleCount = 0L
    private var controlHistory = StringBuilder()
    private var latestDiagnosticReport = ""
    private var latestDiagnosticTimeMs = 0L
    private var remoteService: IBatteryDiagnosticsService? = null
    private var serviceBindingRequested = false

    private val diagnosticArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(packageName, ShizukuDiagnosticsService::class.java.name)
        ).daemon(false)
            .processNameSuffix("batteryscope_diag")
            .version(3)
            .tag("batteryscope_readonly_diagnostics")
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                runOnUiThread {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        bindDiagnosticService()
                    } else {
                        setBridgeStatus("Shizuku permission denied. No privileged scan was run.")
                    }
                }
            }
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread {
            if (::communicationText.isInitialized) {
                setBridgeStatus("Shizuku service connected. Tap the scan button to inspect the charging path.")
            }
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            remoteService = null
            serviceBindingRequested = false
            handler.removeCallbacks(linkSampler)
            if (::liveLinkText.isInitialized) liveLinkText.text = "Shizuku disconnected. Live link polling stopped."
            if (::communicationText.isInitialized) {
                setBridgeStatus("Shizuku service disconnected. Restart Shizuku and scan again.")
            }
        }
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            remoteService = IBatteryDiagnosticsService.Stub.asInterface(binder)
            serviceBindingRequested = false
            runRemoteDiagnosticScan()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            remoteService = null
            serviceBindingRequested = false
            runOnUiThread { setBridgeStatus("Diagnostic service disconnected.") }
        }
    }

    private val linkSampler = object : Runnable {
        override fun run() {
            val service = remoteService ?: return
            if (linkPollInFlight) {
                handler.postDelayed(this, LINK_POLL_INTERVAL_MS)
                return
            }
            linkPollInFlight = true
            Thread {
                val snapshot = try {
                    service.snapshotLink()
                } catch (t: Throwable) {
                    "LIVE SNAPSHOT FAILED: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
                }
                runOnUiThread {
                    linkPollInFlight = false
                    handler.removeCallbacks(this)
                    if (remoteService != null && activityResumed && !isFinishing) {
                        latestLinkSnapshot = snapshot
                        liveLinkText.text = buildLiveLinkSummary(snapshot)
                        handler.postDelayed(this, LINK_POLL_INTERVAL_MS)
                    }
                }
            }.start()
        }
    }

    private val sampler = object : Runnable {
        override fun run() {
            refreshReadings()
            handler.postDelayed(this, 1000L)
        }
    }

    private data class Reading(
        val percent: Int?,
        val rawVoltageMv: Int?,
        val currentUa: Long?,
        val averageCurrentUa: Long?,
        val chargeCounterUah: Long?,
        val energyNwh: Long?,
        val temperatureC: Double?,
        val status: String,
        val plugged: String,
        val health: String,
        val technology: String,
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
        "current_limit", "voltage_limit", "max_voltage", "max_current", "cool_down",
        "fastcharger", "fast_chg_type", "voocchg_ing", "charge_technology", "adapter_fw_update", "mmi_charging_enable", "stop_charging_enable", "chargerid_volt", "charger_ic", "usb_status", "typec_cc_orientation", "typec_sbu_voltage"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        buildUi()
        refreshReadings()
        updateShizukuAvailability()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        handler.removeCallbacks(sampler)
        handler.post(sampler)
        updateShizukuAvailability()
        if (remoteService != null) startLiveLinkPolling()
    }

    override fun onPause() {
        activityResumed = false
        handler.removeCallbacks(sampler)
        handler.removeCallbacks(linkSampler)
        super.onPause()
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
        communicationText = addPanel(content, "03  PHONE ↔ CHARGER COMMUNICATION  •  FULL AUDIT")
        communicationText.text = "Not scanned. Start Shizuku and run the full audit for Type-C role, USB-PD capability, charger supply nodes and Oplus VOOC driver state."
        val liveLabel = TextView(this).apply {
            text = "LIVE POWER-PATH SAMPLE  •  2 SECOND POLL"
            textSize = 10f
            setTextColor(Color.rgb(123, 177, 242))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(2, 14, 2, 7)
        }
        content.addView(liveLabel)
        liveLinkText = makeText(11f, Color.rgb(230, 237, 247), true)
        liveLinkText.text = "Waiting for Shizuku. Live USB / VOOC driver attributes are not yet available."
        content.addView(liveLinkText)
        controlText = addPanel(content, "04  OPLUS INPUT-CURRENT LIMITER  •  CONDITIONAL")
        controlText.text = "Waiting for device audit. The possible control is the Oplus cool_down driver interface, not a USB-PD/VOOC protocol command."
        coolDownStatusText = makeText(11f, Color.rgb(225, 231, 241), false)
        content.addView(coolDownStatusText)
        coolDownStatusText.text = "No device capability scan yet. Controls are locked."
        val levelRows = listOf(0..3, 4..6)
        levelRows.forEach { levels ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            levels.forEach { level ->
                val button = Button(this).apply {
                    text = if (level == 0) "OEM" else "L$level"
                    isEnabled = false
                    setOnClickListener { confirmCoolDownChange(level) }
                }
                coolDownButtons.add(button)
                row.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
            }
            content.addView(row)
        }
        val controlLegend = makeText(10f, Color.rgb(155, 170, 191), false).apply {
            text = "Reference driver caps, normal / VOOC input current: L1 500 / 1000 mA • L2 900 / 1500 mA • L3 1200 / 2000 mA • L4 1500 / 2500 mA • L5 2000 / 3000 mA • L6 2000 / 3500 mA. These are source-code limit values, not measured draw or a negotiated charger profile. OEM resets to factory driver defaults."
        }
        content.addView(controlLegend)
        diagnosticText = addPanel(content, "05  ACCESS, SOURCE & DATA QUALITY")
        val scanButton = Button(this).apply {
            text = "Run Shizuku power-path audit"
            setOnClickListener { startShizukuScan() }
        }
        content.addView(scanButton, LinearLayout.LayoutParams(-1, -2))

        val note = makeText(11f, Color.rgb(155, 170, 191), false).apply {
            text = "Measurement rules: battery current is not USB input current. Only USB/charger supply nodes can report charger-side voltage/current. BatteryScope does not decode the proprietary SuperDart wire handshake or alter the selected USB protocol. The optional Oplus control is available only when the exact bounded kernel interface is present and writable."
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

    private fun setBridgeStatus(message: String) {
        if (::communicationText.isInitialized) communicationText.text = message
    }

    private fun updateShizukuAvailability() {
        if (!::communicationText.isInitialized) return
        val running = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        if (!running) {
            if (latestDiagnosticReport.isBlank()) {
                setBridgeStatus("Shizuku is not running. Install/start Shizuku using Android Wireless debugging, then run the audit. No privileged values are currently available.")
            }
        } else {
            val apiVersion = try { Shizuku.getVersion() } catch (_: Throwable) { -1 }
            setBridgeStatus("Shizuku connected • API $apiVersion. Privileged scan has not been run in this app session.")
        }
    }

    private fun startShizukuScan() {
        try {
            if (Shizuku.isPreV11()) {
                setBridgeStatus("Unsupported Shizuku version. BatteryScope requires Shizuku API v11 or later.")
                return
            }
            if (!Shizuku.pingBinder()) {
                setBridgeStatus("Shizuku is not running. Open Shizuku, start it using Wireless debugging, then retry.")
                return
            }
            if (Shizuku.getVersion() < 10) {
                setBridgeStatus("Shizuku API is too old for UserService. Update Shizuku to a current version.")
                return
            }
            when (Shizuku.checkSelfPermission()) {
                PackageManager.PERMISSION_GRANTED -> bindDiagnosticService()
                else -> {
                    if (Shizuku.shouldShowRequestPermissionRationale()) {
                        setBridgeStatus("Permission was denied earlier. Open Shizuku, find BatteryScope in Authorized applications, grant access, then retry.")
                    } else {
                        setBridgeStatus("Waiting for Shizuku permission…")
                        Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
                    }
                }
            }
        } catch (t: Throwable) {
            setBridgeStatus("Shizuku setup error: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
        }
    }

    private fun bindDiagnosticService() {
        if (serviceBindingRequested || remoteService != null) {
            if (remoteService != null) runRemoteDiagnosticScan()
            return
        }
        try {
            serviceBindingRequested = true
            setBridgeStatus("Starting privileged diagnostic service…")
            Shizuku.bindUserService(diagnosticArgs, userServiceConnection)
        } catch (t: Throwable) {
            serviceBindingRequested = false
            setBridgeStatus("Could not start Shizuku UserService: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
        }
    }

    private fun runRemoteDiagnosticScan() {
        val service = remoteService ?: return
        setBridgeStatus("Reading power-supply, Type-C, USB-PD and Android USB diagnostic interfaces…")
        Thread {
            val report = try {
                service.scan()
            } catch (t: Throwable) {
                "REMOTE SCAN FAILED: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
            }
            runOnUiThread {
                latestDiagnosticReport = report
                latestDiagnosticTimeMs = System.currentTimeMillis()
                communicationText.text = buildCommunicationSummary(report)
                controlText.text = buildControlSummary(report)
                updateCoolDownControls(report)
                liveLinkText.text = if (latestLinkSnapshot.isBlank()) {
                    "Full audit complete. Starting live power-path polling…"
                } else buildLiveLinkSummary(latestLinkSnapshot)
                startLiveLinkPolling()
                diagnosticText.text = buildString {
                    appendLine("Shizuku service: connected")
                    appendLine("Privileged scan: completed")
                    appendLine("Scan timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(latestDiagnosticTimeMs))}")
                    appendLine("Report bytes: ${report.toByteArray().size}")
                    appendLine("Evidence classification: raw kernel/framework output; no inferred contract unless explicitly labelled")
                    appendLine("Audit itself: read-only")
            appendLine("Control actions: logged separately and require a confirmation plus exact-node read-back")
                }
            }
        }.start()
    }

    private fun startLiveLinkPolling() {
        if (remoteService == null || !activityResumed) return
        handler.removeCallbacks(linkSampler)
        handler.post(linkSampler)
    }

    private fun buildLiveLinkSummary(snapshot: String): String {
        val timestamp = snapshot.lineSequence()
            .firstOrNull { it.startsWith("Timestamp:") }
            ?.substringAfter(":")?.trim()?.toLongOrNull()
        val timeLabel = timestamp?.let {
            SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(it))
        } ?: "unknown"
        val keys = listOf(
            "online", "status", "voltage_now", "voltage_avg", "current_now",
            "current_avg", "power_now", "usb_type", "real_type", "charge_type",
            "fastcharger", "fast_chg_type", "voocchg_ing", "charge_technology",
            "adapter_fw_update", "cool_down", "input_current_limit", "input_voltage_limit",
            "input_power_limit", "constant_charge_current", "constant_charge_voltage",
            "power_role", "data_role", "port_type", "power_operation_mode", "operational_current",
            "maximum_current"
        )
        val rows = snapshot.lineSequence().filter { line ->
            line.startsWith("/sys/") && keys.any { line.contains("/$it =") }
        }.take(60).toList()
        return buildString {
            appendLine("Last sample: $timeLabel  •  target poll: 2 seconds")
            if (rows.isEmpty()) {
                appendLine("No readable live power-path / Type-C / VOOC attributes were returned.")
                appendLine("This is unavailable data, not a zero current or a confirmed inactive fast-charge state.")
            } else {
                rows.forEach { appendLine(it) }
            }
            appendLine()
            appendLine("Provenance: raw kernel sysfs paths and values. Battery / BMS current is not USB input current.")
            appendLine("VOOC state flags are driver state, not wire-packet capture. PDO/contract selection is shown only if the kernel explicitly exports it.")
            appendLine("The poll is a sampled driver snapshot, not a USB protocol trace or electrical measurement at the connector.")
        }
    }

    private fun buildCommunicationSummary(report: String): String {
        val keys = listOf(
            "power_role", "data_role", "port_type", "power_operation_mode", "online",
            "usb_power_delivery_revision", "usb_typec_revision", "usb_type",
            "real_type", "charge_type", "voltage_now", "current_now",
            "fastcharger", "fast_chg_type", "voocchg_ing", "charge_technology", "adapter_fw_update", "mmi_charging_enable", "stop_charging_enable",
            "input_current_limit", "input_voltage_limit", "source-capabilities",
            "sink-capabilities", "maximum_current", "operational_current"
        )
        val matches = report.lineSequence().filter { line ->
            val lower = line.lowercase(Locale.US)
            val batteryPowerSupply = Regex("/sys/class/power_supply/(battery|bms|main)/").containsMatchIn(lower)
            val knownCommunicationPath =
                lower.contains("/sys/class/typec/") ||
                lower.contains("/sys/class/usb_power_delivery/") ||
                lower.contains("/sys/class/dual_role_usb/") ||
                lower.contains("/sys/class/usb_role/") ||
                (lower.contains("/sys/class/power_supply/") && !batteryPowerSupply)
            val hasProtocolField = keys.any { lower.contains(it) } ||
                (lower.contains("type =") && lower.contains("usb"))
            knownCommunicationPath && hasProtocolField
        }.distinct().take(45).toList()

        return buildString {
            appendLine("LATEST POWER-PATH SNAPSHOT • tap audit again to refresh")
            if (matches.isEmpty()) {
                appendLine("No readable Type-C role, USB-PD, charger-type or VBUS/IBUS kernel fields were returned.")
                appendLine("Result: link-level protocol negotiation is NOT OBSERVED by this scan.")
            } else {
                appendLine("The following values were read directly from kernel attributes. Paths are preserved as provenance.")
                matches.forEach { appendLine(it) }
            }
            appendLine()
            appendLine("OPLUS / VOOC DRIVER STATE")
            val voocLines = report.lineSequence().filter { line ->
                listOf("/fastcharger", "/fast_chg_type", "/voocchg_ing", "/charge_technology", "/adapter_fw_update", "/mmi_charging_enable", "/stop_charging_enable")
                    .any { line.contains(it) }
            }.toList()
            if (voocLines.isEmpty()) {
                appendLine("Not exposed at the scanned power-supply paths, or not readable.")
            } else {
                voocLines.forEach { line ->
                    val property = line.substringBefore(" = ").substringAfterLast("/")
                    val raw = line.substringAfter(" = ", "unknown").substringBefore("  [")
                    val meaning = when (property) {
                        "fastcharger" -> "Oplus VOOC fast-charge UI indicator"
                        "fast_chg_type" -> "driver-reported charger subtype; raw enum"
                        "voocchg_ing" -> "Oplus VOOC driver fast-charge-in-progress flag"
                        "charge_technology" -> "vendor project enum; interpretation intentionally omitted"
                        "adapter_fw_update" -> "adapter firmware update state; enum interpretation omitted"
                        "mmi_charging_enable" -> "vendor charge enable state; read only, never written"
                        "stop_charging_enable" -> "vendor stop-charge state; read only, never written"
                        else -> "raw driver property"
                    }
                    appendLine("$property = $raw  [$meaning]")
                }
            }
            appendLine()
            appendLine("Interpretation limits")
            appendLine("• BatteryScope can report only interfaces published by this phone's kernel/firmware.")
            appendLine("• USB-PD PDO data, if present, is USB-PD evidence; it is not proof of SuperDart negotiation.")
            appendLine("• Oplus VOOC state properties, when exposed, are driver state values, not captured electrical packets.")
            appendLine("• USB-PD PDO values show advertised capabilities, not necessarily the selected active contract.")
            appendLine("• The app does not transmit protocol requests or alter the negotiated profile.")
            appendLine("• This is an on-demand snapshot, not a continuous packet trace or oscilloscope.")
            appendLine()
            appendLine("FULL RAW SCAN")
            append(report)
        }
    }

    private fun buildControlSummary(report: String): String {
        val present = report.contains("Present: true")
        val readable = report.contains("Readable: true")
        val writable = report.contains("ADB-shell process canWrite(): true")
        val detectedModel = report.lineSequence().firstOrNull { it.startsWith("Android model:") }
            ?.substringAfter(":")?.trim()
        val targetModel = detectedModel?.contains("RMX2161", ignoreCase = true) == true
        val current = report.lineSequence()
            .firstOrNull { it.startsWith("Current driver level:") }
            ?.substringAfter(":")?.trim()
        return buildString {
            appendLine("Detected model: ${detectedModel ?: "UNAVAILABLE"}")
            appendLine("Target model check: ${if (targetModel) "RMX2161 MATCH" else "MISMATCH / UNVERIFIED"}")
            appendLine("Oplus COOL_DOWN node: ${if (present) "PRESENT" else "NOT EXPOSED"}")
            appendLine("ADB-shell read access: ${if (readable) "YES" else "NO"}")
            appendLine("ADB-shell write access: ${if (writable) "YES (permission check only)" else "NO"}")
            appendLine("Current driver level: ${current ?: "UNAVAILABLE"}")
            appendLine("Accepted reference levels: 0 (restore OEM defaults), 1..6 (input-current limit steps)")
            appendLine("No value is written during a scan.")
            if (!targetModel) {
                appendLine("Device identity does not match RMX2161. Charging writes are blocked.")
            } else if (!present) {
                appendLine("The installed kernel did not expose /sys/class/power_supply/battery/cool_down at the audited path. No control is available through this interface.")
            } else if (!writable) {
                appendLine("The node exists but the Shizuku process cannot write it. Shizuku is ADB-shell privilege, not root; controls remain disabled.")
            } else if (current?.toIntOrNull()?.let { it in 0..6 } != true) {
                appendLine("The live value is outside the source-verified 0..6 range. Controls remain disabled.")
            } else {
                appendLine("The bounded driver interface appears writable to this process. The app still requires an explicit confirmation, charging-state check and exact read-back after any user-requested change.")
            }
            appendLine()
            appendLine("Reference driver table (normal / VOOC input cap):")
            appendLine("L1 500 / 1000 mA; L2 900 / 1500 mA; L3 1200 / 2000 mA; L4 1500 / 2500 mA; L5 2000 / 3000 mA; L6 2000 / 3500 mA.")
            appendLine("These are limits in the inspected RM6785 source, not measured current, USB-C voltage/current or the active negotiated contract. Installed firmware may differ.")
            appendLine()
            appendLine("Known unsafe Oplus control: MMI_CHARGING_ENABLE is binary charge enable/disable; its setter can turn charging off and end VOOC. It is excluded.")
            appendLine("STOP_CHARGING_ENABLE and OTG_SWITCH are also excluded; they do not provide a safe current-rate control.")
            appendLine("No USB-C role change, PMIC/I²C write, or raw SuperDart protocol command is available in this app.")
        }
    }

    private fun updateCoolDownControls(report: String) {
        val present = report.contains("Present: true")
        val readable = report.contains("Readable: true")
        val writable = report.contains("ADB-shell process canWrite(): true")
        val detectedModel = report.lineSequence().firstOrNull { it.startsWith("Android model:") }
            ?.substringAfter(":")?.trim()
        val targetModel = detectedModel?.contains("RMX2161", ignoreCase = true) == true
        val rawLevel = report.lineSequence()
            .firstOrNull { it.startsWith("Current driver level:") }
            ?.substringAfter(":")?.trim()
        val parsedLevel = rawLevel?.toIntOrNull()
        currentCoolDownLevel = parsedLevel
        coolDownNodeWritable = targetModel && present && readable && writable && parsedLevel?.let { it in 0..6 } == true
        val charging = isChargingAndPlugged()
        coolDownButtons.forEachIndexed { index, button ->
            val level = index
            val name = if (level == 0) "OEM" else "L$level"
            button.text = if (parsedLevel == level) "$name ✓" else name
            button.isEnabled = coolDownNodeWritable && charging && remoteService != null
        }
        coolDownStatusText.text = when {
            !targetModel -> "LOCKED: detected model '${detectedModel ?: "unknown"}' does not match RMX2161."
            !present -> "CONTROL UNAVAILABLE: /sys/class/power_supply/battery/cool_down is not exposed by this firmware."
            !readable -> "CONTROL UNAVAILABLE: cooldown node is present but unreadable to Shizuku."
            !writable -> "READ-ONLY: cooldown node is visible, but the Shizuku ADB-shell UID has no write permission. Root or an OEM privileged service would be required; controls are locked."
            parsedLevel?.let { it in 0..6 } != true -> "LOCKED: live driver level '$rawLevel' is outside the source-verified 0..6 range."
            !charging -> "CONTROL DETECTED BUT LOCKED: connect a charger and wait until Android reports CHARGING before applying a limit."
            remoteService == null -> "CONTROL DETECTED BUT LOCKED: Shizuku diagnostic service is not connected. Run the audit again."
            else -> "AVAILABLE: driver level $parsedLevel read from the exact sysfs node. Select a level only after reviewing the cap table and confirmation."
        }
    }

    private fun isChargingAndPlugged(): Boolean {
        val intent = batteryIntent() ?: return false
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return status == BatteryManager.BATTERY_STATUS_CHARGING && plugged != 0
    }

    private fun confirmCoolDownChange(level: Int) {
        val service = remoteService
        if (service == null || !coolDownNodeWritable) {
            coolDownStatusText.text = "LOCKED: the Shizuku service or writable driver node is unavailable."
            return
        }
        if (!isChargingAndPlugged()) {
            coolDownStatusText.text = "LOCKED: the phone must report CHARGING with an external power source attached."
            updateCoolDownControls(latestDiagnosticReport)
            return
        }
        if (level !in 0..6) return
        if (currentCoolDownLevel == level) {
            coolDownStatusText.text = "No change requested: driver is already at level $level."
            return
        }
        val capDescription = when (level) {
            0 -> "Restore the OEM driver defaults. This removes the manual cool_down level."
            1 -> "Request the most restrictive reference cap: normal 500 mA / VOOC 1000 mA input."
            2 -> "Request reference cap: normal 900 mA / VOOC 1500 mA input."
            3 -> "Request reference cap: normal 1200 mA / VOOC 2000 mA input."
            4 -> "Request reference cap: normal 1500 mA / VOOC 2500 mA input."
            5 -> "Request reference cap: normal 2000 mA / VOOC 3000 mA input."
            else -> "Request reference cap: normal 2000 mA / VOOC 3500 mA input."
        }
        AlertDialog.Builder(this)
            .setTitle(if (level == 0) "Restore OEM charging limits?" else "Apply Oplus input-current limit?")
            .setMessage(
                "$capDescription\n\nThis changes only the driver's cool_down limit setting. It does not select a SuperDart/VOOC/USB-PD voltage profile and does not measure the current actually drawn. The phone's firmware may clamp, override or ignore the requested cap. BatteryScope will verify the exact sysfs read-back; it will not report success without that read-back.\n\nProceed?"
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ -> performCoolDownChange(level) }
            .show()
    }

    private fun performCoolDownChange(level: Int) {
        val service = remoteService ?: return
        coolDownButtons.forEach { it.isEnabled = false }
        coolDownStatusText.text = "Applying level $level through the exact Oplus driver node and checking read-back…"
        Thread {
            val result = try {
                service.setCoolDown(level)
            } catch (t: Throwable) {
                "CONTROL CALL FAILED: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
            }
            runOnUiThread {
                controlHistory.append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                    .append(" | requested cool_down level=").append(level)
                    .append(" | ").append(result).append('\n')
                controlText.text = result
                if (result.startsWith("READ-BACK VERIFIED:")) {
                    coolDownStatusText.text = result
                    runRemoteDiagnosticScan()
                } else {
                    coolDownStatusText.text = result
                    coolDownButtons.forEach { it.isEnabled = false }
                }
            }
        }.start()
    }

    private fun batteryIntent(): Intent? =
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun longProperty(id: Int): Long? = try {
        val value = batteryManager.getLongProperty(id)
        if (value == Long.MIN_VALUE) null else value
    } catch (_: Throwable) {
        null
    }

    private fun readSupplyNodes(): Pair<Map<String, String>, Boolean> {
        val root = File("/sys/class/power_supply")
        val dirs = try { root.listFiles() } catch (_: Throwable) { null }
            ?: return Pair(emptyMap(), false)
        val result = linkedMapOf<String, String>()
        for (dir in dirs.sortedBy { it.name }) {
            if (!dir.isDirectory) continue
            for (attribute in supplyAttributes) {
                val file = File(dir, attribute)
                if (!file.isFile) continue
                val value = try { file.inputStream().bufferedReader().use { it.readLine()?.trim()?.take(180) } }
                catch (_: Throwable) { null }
                if (!value.isNullOrBlank()) result["${dir.name}/$attribute"] = value
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
        val (supplies, readable) = readSupplyNodes()
        return Reading(
            percent = percent,
            rawVoltageMv = rawVoltage.takeIf { it > 0 },
            currentUa = longProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            averageCurrentUa = longProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
            chargeCounterUah = longProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            energyNwh = longProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER),
            temperatureC = temp,
            status = status,
            plugged = plugged,
            health = health,
            technology = technology,
            supplies = supplies,
            supplyRootReadable = readable
        )
    }

    private fun refreshReadings() {
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

        statusText.text = "${reading.status}   •   ${reading.percent?.let { "$it%" } ?: "--"}   •   POWER SOURCE: ${reading.plugged}"
        batteryText.text = buildString {
            appendLine("Battery level          ${reading.percent?.let { "$it %" } ?: "Unavailable"}")
            appendLine("Battery voltage        ${reading.rawVoltageMv?.let { "$it mV raw" } ?: "Unavailable"}")
            appendLine("Battery current        ${format(currentMa, "mA")}  (${reading.currentUa?.let { "$it µA raw" } ?: "Unavailable"})")
            appendLine("Average current        ${reading.averageCurrentUa?.let { String.format(Locale.US, "%.3f mA (%d µA)", it / 1000.0, it) } ?: "Unavailable"}")
            appendLine("Battery power estimate ${format(estimatedBatteryPowerW, "W")}  [battery-side only]")
            appendLine("Charge counter         ${reading.chargeCounterUah?.let { "$it µAh / ${String.format(Locale.US, "%.1f mAh", it / 1000.0)}" } ?: "Unavailable"}")
            appendLine("Energy counter         ${reading.energyNwh?.let { "$it nWh / ${String.format(Locale.US, "%.3f Wh", it / 1_000_000_000.0)}" } ?: "Unavailable"}")
            appendLine("Temperature            ${format(reading.temperatureC, "°C")}")
            appendLine("Battery health         ${reading.health}")
            append("Technology             ${reading.technology}")
        }

        val external = reading.supplies.filterKeys { key ->
            val node = key.substringBefore('/').lowercase(Locale.US)
            node !in listOf("battery", "bms", "main")
        }
        val batteryNodes = reading.supplies.filterKeys { key ->
            val node = key.substringBefore('/').lowercase(Locale.US)
            node in listOf("battery", "bms", "main")
        }
        chargerText.text = buildString {
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
        diagnosticText.text = buildString {
            appendLine("Android battery status: ${reading.status}")
            appendLine("Android plug type: ${reading.plugged}")
            appendLine("Kernel external online flags: ${onlineNodes.joinToString().ifBlank { "not observed by app UID" }}")
            appendLine("Ordinary app sysfs: ${if (reading.supplyRootReadable) "directory visible; individual files may still be denied" else "not visible"}")
            appendLine("Shizuku audit: ${if (latestDiagnosticReport.isBlank()) "not run" else "available at ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(latestDiagnosticTimeMs))}"}")
            appendLine("Control writes: NEVER PERFORMED")
            append("Refresh: ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} • 1 second")
        }

        if (logging) {
            val row = listOf(
                System.currentTimeMillis().toString(),
                reading.percent?.toString() ?: "",
                reading.status,
                reading.plugged,
                reading.rawVoltageMv?.toString() ?: "",
                reading.currentUa?.toString() ?: "",
                reading.averageCurrentUa?.toString() ?: "",
                reading.chargeCounterUah?.toString() ?: "",
                reading.energyNwh?.toString() ?: "",
                reading.temperatureC?.toString() ?: "",
                reading.health,
                reading.technology,
                csvEscape(JSONObject(reading.supplies).toString())
            ).joinToString(",")
            try {
                val writer = csvWriter ?: throw IllegalStateException("CSV writer is not open")
                writer.write(row)
                writer.newLine()
                csvSampleCount++
                if (csvSampleCount % 10L == 0L) writer.flush()
            } catch (t: Throwable) {
                logging = false
                try { csvWriter?.close() } catch (_: Throwable) { }
                csvWriter = null
                startButton.isEnabled = true
                stopButton.isEnabled = false
                statusText.text = "CSV logging stopped: ${t.javaClass.simpleName}: ${t.message ?: "write error"}"
            }
        }
    }

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
        if (logging) return
        try {
            val directory = getExternalFilesDir(null) ?: filesDir
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(directory, "BatteryScope_$timestamp.csv")
            val writer = file.bufferedWriter(Charsets.UTF_8)
            writer.write("timestamp_ms,battery_percent,battery_status,plug_type,battery_voltage_mv,battery_current_ua,battery_average_current_ua,charge_counter_uah,energy_counter_nwh,temperature_c,battery_health,technology,app_uid_sysfs_json")
            writer.newLine()
            writer.flush()
            csvFile = file
            csvWriter = writer
            sessionTimestamp = timestamp
            csvSampleCount = 0L
            logging = true
            startButton.isEnabled = false
            stopButton.isEnabled = true
            statusText.text = "CSV logging: ${file.name}"
            refreshReadings()
        } catch (t: Throwable) {
            logging = false
            startButton.isEnabled = true
            stopButton.isEnabled = false
            statusText.text = "CSV START FAILED: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
        }
    }

    private fun stopLogging() {
        logging = false
        startButton.isEnabled = true
        stopButton.isEnabled = false
        var closeError: String? = null
        try {
            csvWriter?.flush()
            csvWriter?.close()
        } catch (t: Throwable) {
            closeError = "${t.javaClass.simpleName}: ${t.message ?: "close error"}"
        } finally {
            csvWriter = null
        }
        val file = csvFile
        val timestamp = sessionTimestamp
            ?: SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var reportFile: File? = null
        try {
            if (latestDiagnosticReport.isNotBlank() || latestLinkSnapshot.isNotBlank() || controlHistory.isNotEmpty()) {
                val directory = getExternalFilesDir(null) ?: filesDir
                reportFile = File(directory, "BatteryScope_${timestamp}-diagnostics.txt")
                reportFile.writeText(
                    "FULL SHIZUKU AUDIT\\n$latestDiagnosticReport\\n\\nLATEST LIVE POWER-PATH SAMPLE\\n$latestLinkSnapshot\\n\\nCONTROL ACTION HISTORY\\n$controlHistory"
                )
            }
            statusText.text = buildString {
                appendLine(if (file != null) "CSV saved: ${file.name} ($csvSampleCount samples)" else "No CSV file was created.")
                if (reportFile != null) appendLine("Diagnostics saved: ${reportFile.name}")
                if (closeError != null) appendLine("CSV close warning: $closeError")
            }.trim()
        } catch (t: Throwable) {
            statusText.text = "EXPORT FAILED: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
        }
    }

    override fun onDestroy() {
        logging = false
        handler.removeCallbacks(sampler)
        handler.removeCallbacks(linkSampler)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        try {
            if (Shizuku.pingBinder()) {
                Shizuku.unbindUserService(diagnosticArgs, userServiceConnection, true)
            }
        } catch (_: Throwable) { }
        super.onDestroy()
    }

    companion object {
        private const val SHIZUKU_REQUEST_CODE = 4102
        private const val LINK_POLL_INTERVAL_MS = 2000L
    }
}
