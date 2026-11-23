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
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.os.IBinder
import android.net.Uri
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
    private lateinit var historyText: TextView
    private var historyUpdatedAtMs = 0L
    private var historyLoadInFlight = false
    private var exportInFlight = false
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private val coolDownButtons = mutableListOf<Button>()
    private var currentCoolDownLevel: Int? = null
    private var coolDownNodeWritable = false
    private var linkPollInFlight = false
    private var activityResumed = false
    private var latestLinkSnapshot = ""
    private var cachedSupplyNodes: Map<String, String> = emptyMap()
    private var cachedSupplyRootVisible = false
    private var supplyScanCompletedAtMs = 0L

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingExportFile: File? = null
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
            notifyRecorderShizukuEnabled()
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
        updateRecorderControls()
        refreshHistoryDisplay(true)
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
        historyText = addPanel(content, "06  CHARGE / DISCHARGE HISTORY & APP USAGE")
        historyText.text = "No recorder history yet. Start the all-day recorder to build daily, weekly and monthly data."
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

    private fun notifyRecorderShizukuEnabled() {
        val active = getSharedPreferences(BatteryRecorderService.PREFS, MODE_PRIVATE)
            .getBoolean(BatteryRecorderService.KEY_ACTIVE, false)
        if (!active) return
        try {
            startService(Intent(this, BatteryRecorderService::class.java).setAction(BatteryRecorderService.ACTION_ENABLE_SHIZUKU))
        } catch (t: Throwable) {
            setBridgeStatus("Could not enable privileged history sampling: " + (t.message ?: "unknown"))
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
            if (Shizuku.getVersion() < 11) {
                setBridgeStatus("Shizuku API is below v11, which BatteryScope requires for UserService. Update Shizuku and retry.")
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
        val rows = snapshot.lineSequence()
            .filter { it.startsWith("/sys/") }
            .take(120).toList()
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
            "sink-capabilities", "maximum_current", "operational_current",
            "voltage", "current", "power", "revision", "svid", "mode", "active"
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
        val identityLines = report.lineSequence().filter {
            it.startsWith("Android model:") || it.startsWith("Android device:") ||
                it.startsWith("Android product:") || it.startsWith("Build fingerprint:")
        }.toList()
        val detectedModel = identityLines.joinToString(" | ") { it.substringAfter(":").trim() }
            .ifBlank { "UNAVAILABLE" }
        val targetModel = identityLines.any { it.contains("RMX2161", ignoreCase = true) }
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
        val identityLines = report.lineSequence().filter {
            it.startsWith("Android model:") || it.startsWith("Android device:") ||
                it.startsWith("Android product:") || it.startsWith("Build fingerprint:")
        }.toList()
        val detectedModel = identityLines.joinToString(" | ") { it.substringAfter(":").trim() }
            .ifBlank { "UNAVAILABLE" }
        val targetModel = identityLines.any { it.contains("RMX2161", ignoreCase = true) }
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
        if (value == Long.MIN_VALUE || value == Long.MAX_VALUE) null else value
    } catch (_: Throwable) {
        null
    }

    /** Enumerates read-only kernel power, USB-C, PD, thermal and hwmon class attributes. */
    private fun readSupplyNodes(): Pair<Map<String, String>, Boolean> {
        val now = System.currentTimeMillis()
        // BatteryManager is refreshed every second; sysfs directory traversal is not.
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
                result["@diagnostic/" + prefix + "/root"] = "NOT_PRESENT"
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
        cachedSupplyNodes = result
        cachedSupplyRootVisible = powerSupplyRootVisible
        supplyScanCompletedAtMs = now
        return result to powerSupplyRootVisible
    }

    private fun buildMeasurementDiagnosis(nodes: Map<String, String>, powerSupplyRootVisible: Boolean): String {
        val lines = mutableListOf<String>()
        lines += "AUTOMATIC MEASUREMENT ACCESS DIAGNOSIS"
        lines += "Power-supply root: " + if (powerSupplyRootVisible) "visible" else "not visible / not enumerable"
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
        val denied = nodes.filterValues { it == "READ_DENIED" || it == "LIST_DENIED" || it.startsWith("ERROR:") }
        lines += "Permission / read errors: " + denied.size
        denied.entries.take(20).forEach { lines += "  " + it.key + " = " + it.value }
        lines += "Shizuku audit: " + if (latestDiagnosticReport.isBlank()) "not run" else "available; inspect privileged UID and per-path results"
        lines += "Root: not invoked automatically; no su prompt or root write attempted."
        lines += "ADB shell: not available to ordinary app process; Shizuku UserService is the optional shell-UID route."
        lines += "PDO capabilities, configured limits and VOOC flags are not classified as measured USB input power."
        lines += "No kernel attributes were written."
        return lines.joinToString("\n")
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
            appendLine("Battery voltage        ${reading.rawVoltageMv?.let { if (it in 2500..5000) "$it mV raw" else "$it mV raw — INVALID / outside expected battery range" } ?: "Unavailable"}")
            appendLine("Battery current        ${format(currentMa, "mA")}  (${reading.currentUa?.let { "$it µA raw" } ?: "Unavailable"})")
            appendLine("Average current        ${reading.averageCurrentUa?.let { String.format(Locale.US, "%.3f mA (%d µA)", it / 1000.0, it) } ?: "Unavailable"}")
            appendLine("Battery power estimate ${format(estimatedBatteryPowerW, "W")}  [battery-side only]")
            appendLine("Remaining-charge counter ${reading.chargeCounterUah?.let { "$it µAh / ${String.format(Locale.US, "%.1f mAh", it / 1000.0)} (Android estimate; not full-charge capacity)" } ?: "Unavailable"}")
            appendLine("Energy counter         ${reading.energyNwh?.let { "$it nWh / ${String.format(Locale.US, "%.3f Wh", it / 1_000_000_000.0)}" } ?: "Unavailable"}")
            appendLine("Temperature            ${format(reading.temperatureC, "°C")}")
            appendLine("Android battery health ${reading.health} (framework status; not capacity health)")
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
            appendLine()
            append(buildMeasurementDiagnosis(reading.supplies, reading.supplyRootReadable))
            append("Refresh: ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} • 1 second")
        }

        refreshHistoryDisplay(false)
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
        try {
            val intent = Intent(this, BatteryRecorderService::class.java).setAction(BatteryRecorderService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            startButton.isEnabled = false
            stopButton.isEnabled = true
            statusText.text = "Starting persistent recorder. It samples every 30 seconds and saves history locally."
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
        if (active) statusText.text = "All-day recorder active • 30-second samples • local history database"
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
                file = File(directory, "BatteryScope_History_" + timestamp + ".csv")
                database = RecorderDatabase(applicationContext)
                file.bufferedWriter(Charsets.UTF_8).use { database!!.exportCsv(it) }
                noData = file.length() == 0L
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

    override fun onDestroy() {
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
        private const val SYSFS_UI_REFRESH_MS = 15_000L
        private const val SHIZUKU_REQUEST_CODE = 4102
        private const val LINK_POLL_INTERVAL_MS = 2_000L
        private const val CSV_EXPORT_REQUEST_CODE = 5101
        private const val NOTIFICATION_REQUEST_CODE = 5102
    }
}
