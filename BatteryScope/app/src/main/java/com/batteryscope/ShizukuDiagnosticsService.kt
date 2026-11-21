package com.batteryscope

import android.system.Os
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Runs inside the Shizuku UserService process (ADB shell UID or root UID).
 * The audit and live snapshot are read-only. The only write is the fixed, bounded
 * Oplus cool_down property, available only after a separately confirmed user action.
 */
class ShizukuDiagnosticsService : IBatteryDiagnosticsService.Stub() {

    override fun scan(): String = buildString {
        appendLine("BATTERYSCOPE PRIVILEGED READ-ONLY AUDIT")
        appendLine("Process UID: ${safe { Os.getuid().toString() }} (2000 normally means ADB shell; 0 means root)")
        appendLine("PID: ${safe { Os.getpid().toString() }}")
        appendLine("Android manufacturer: ${Build.MANUFACTURER}")
        appendLine("Android model: ${Build.MODEL}")
        appendLine("Android device: ${Build.DEVICE}")
        appendLine("Android product: ${Build.PRODUCT}")
        appendLine("Build fingerprint: ${Build.FINGERPRINT}")
        appendLine("SELinux context: ${readOneLine(File("/proc/self/attr/current"))}")
        appendLine("Timestamp: ${System.currentTimeMillis()}")
        appendLine("Kernel config CONFIG_OPLUS_SMART_CHARGER_SUPPORT: ${readSmartChargerConfig()}")
        appendLine()
        appendSection("POWER SUPPLY / USB VBUS AND IBUS", File("/sys/class/power_supply"),
            setOf("type", "online", "present", "status", "health", "capacity", "voltage_now",
                "voltage_avg", "voltage_max", "voltage_min", "current_now", "current_avg",
                "current_max", "power_now", "input_current_limit", "input_current_limit_max", "input_voltage_limit", "input_voltage_limit_max",
                "input_power_limit", "usb_type", "real_type", "charge_type", "constant_charge_current",
                "constant_charge_voltage", "charge_control_limit", "charge_control_limit_min", "charge_control_limit_max",
                "charge_control_start_threshold", "charge_control_end_threshold", "charge_counter", "charge_now", "charge_full", "temp", "model_name", "manufacturer",
                "fastcharger", "fast_chg_type", "voocchg_ing", "charge_technology", "adapter_fw_update",
                "mmi_charging_enable", "stop_charging_enable", "charger_ic", "chargerid_volt",
                "internal_capacity", "usb_status", "typec_cc_orientation", "typec_sbu_voltage", "cool_down"),
            2)
        appendLine()
        appendSection("USB TYPE-C PORT / PARTNER ROLE AND MODE", File("/sys/class/typec"),
            setOf("data_role", "power_role", "port_type", "power_operation_mode", "vconn_source",
                "usb_power_delivery_revision", "usb_typec_revision", "orientation",
                "preferred_role", "supported_power_roles", "supported_data_roles",
                "accessory_mode", "identity", "svid", "mode", "active"), 3)
        appendLine()
        appendSection("USB POWER DELIVERY CAPABILITIES / PDO DATA", File("/sys/class/usb_power_delivery"),
            setOf("revision", "version", "voltage", "maximum_current", "operational_current",
                "peak_current", "power", "current", "type", "dual_role_power",
                "usb_suspend_supported", "unconstrained_power", "usb_communications_capable",
                "dual_role_data", "unchunked_extended_messages"), 5)
        appendLine()
        appendSection("LEGACY USB ROLE NODES", File("/sys/class/dual_role_usb"),
            setOf("mode", "power_role", "data_role", "power_type", "supports_usb_power_delivery"), 2)
        appendSection("USB ROLE SWITCH NODES", File("/sys/class/usb_role"),
            setOf("role"), 2)
        appendLine()
        appendSection("ANDROID FRAMEWORK: dumpsys battery", null, emptySet(), 0, "dumpsys battery")
        appendLine()
        appendSection("ANDROID FRAMEWORK: dumpsys usb", null, emptySet(), 0, "dumpsys usb")
        appendLine()
        appendLine("=== OPLUS COOL_DOWN CURRENT-LIMITER CAPABILITY ===")
        val coolDownNode = File("/sys/class/power_supply/battery/cool_down")
        appendLine("Node: ${coolDownNode.path}")
        appendLine("Present: ${coolDownNode.exists() && coolDownNode.isFile}")
        appendLine("Readable: ${coolDownNode.canRead()}")
        appendLine("ADB-shell process canWrite(): ${coolDownNode.canWrite()}")
        appendLine("Current driver level: ${if (coolDownNode.canRead()) readOneLine(coolDownNode) else "UNAVAILABLE"}")
        appendLine("Reference-driver semantics: level 0 restores OEM defaults; levels 1..6 select bounded input-current caps.")
        appendLine("Reference caps (normal/VOOC input current): L1 500/1000 mA; L2 900/1500 mA; L3 1200/2000 mA; L4 1500/2500 mA; L5 2000/3000 mA; L6 2000/3500 mA.")
        appendLine("These are driver limit settings from the RM6785 reference source, not measured current or negotiated USB power.")
        appendLine("This scan is read-only. No attributes were written during the audit.")
        appendLine()
        appendLine("CONTROL SAFETY")
        appendLine("Writable status is an access check only; a control operation separately checks the exact node, valid range, charging state, write result and read-back.")
        appendLine("Oplus kernel-source audit targets (when exposed by this firmware): battery/fastcharger, battery/voocchg_ing, battery/charge_technology, battery/adapter_fw_update and usb/fast_chg_type.")
        appendLine("fastcharger is the Oplus VOOC fast-charge UI indicator; voocchg_ing reads the driver fastchg_ing state. Neither reports USB VBUS/IBUS or the exact adapter contract.")
        appendLine("fast_chg_type is a driver-reported charger subtype code; BatteryScope preserves it raw. Vendor enums/status values are not guessed.")
        appendLine("mmi_charging_enable and stop_charging_enable are read-only observations in this app; no write is attempted.")
        appendLine("No USB role, voltage, current limit, charging mode, or protocol negotiation was changed.")
        appendLine("A missing node means not exposed at this path; it does not prove the hardware lacks that capability.")
    }

    override fun snapshotLink(): String = buildString {
        appendLine("BATTERYSCOPE LIVE POWER-PATH SAMPLE")
        appendLine("Timestamp: ${System.currentTimeMillis()}")
        appendQuickSection("POWER SUPPLY / CHARGER / BATTERY", File("/sys/class/power_supply"),
            setOf("type", "online", "present", "status", "voltage_now", "voltage_avg",
                "current_now", "current_avg", "power_now", "usb_type", "real_type",
                "charge_type", "fastcharger", "fast_chg_type", "voocchg_ing",
                "charge_technology", "adapter_fw_update", "cool_down"), 2)
        appendQuickSection("USB TYPE-C PORT / PARTNER", File("/sys/class/typec"),
            setOf("power_role", "data_role", "port_type", "power_operation_mode",
                "vconn_source", "usb_power_delivery_revision", "usb_typec_revision",
                "orientation", "preferred_role", "svid", "mode", "active"), 3)
        appendQuickSection("USB POWER DELIVERY", File("/sys/class/usb_power_delivery"),
            setOf("revision", "voltage", "maximum_current", "operational_current",
                "peak_current", "power", "current", "type"), 4)
        appendLine("Poll type: live sysfs snapshot; not a packet capture or electrical waveform.")
    }

    private fun StringBuilder.appendQuickSection(
        title: String,
        root: File,
        allowed: Set<String>,
        maxDepth: Int
    ) {
        appendLine("=== $title ===")
        if (!root.exists()) {
            appendLine("NOT EXPOSED: ${root.path}")
            return
        }
        var count = 0
        fun walk(dir: File, depth: Int) {
            if (depth > maxDepth || count >= 80) return
            val children = try { dir.listFiles()?.sortedBy { it.name } } catch (_: Throwable) { null }
            if (children == null) {
                appendLine("LIST DENIED: ${dir.path}")
                return
            }
            for (child in children) {
                if (count >= 80) break
                if (child.isDirectory) {
                    walk(child, depth + 1)
                } else if (child.isFile && child.name in allowed) {
                    count++
                    appendLine("${child.path} = ${readOneLine(child)}")
                }
            }
        }
        walk(root, 0)
        if (count == 0) appendLine("No readable matching attributes.")
        if (count >= 80) appendLine("Live snapshot limit reached.")
    }

    private fun readSmartChargerConfig(): String {
        val config = File("/proc/config.gz")
        if (!config.exists()) return "NOT EXPORTED at /proc/config.gz"
        return try {
            GZIPInputStream(config.inputStream()).bufferedReader().useLines { lines ->
                lines.firstOrNull { it.startsWith("CONFIG_OPLUS_SMART_CHARGER_SUPPORT=") }
                    ?: "SYMBOL NOT FOUND"
            }
        } catch (t: Throwable) {
            "UNREADABLE: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
        }
    }

    override fun setCoolDown(level: Int): String {
        if (!Build.MODEL.contains("RMX2161", ignoreCase = true)) {
            return "DENIED: this control is only enabled for the audited RMX2161 model; detected model='" + Build.MODEL + "'."
        }
        if (level !in 0..6) return "REJECTED: level must be between 0 and 6."
        val node = File("/sys/class/power_supply/battery/cool_down")
        if (!node.exists() || !node.isFile) return "UNAVAILABLE: exact battery/cool_down node is not present."
        if (!node.canRead()) return "DENIED: cooldown node cannot be read."
        if (!node.canWrite()) return "DENIED: Shizuku process does not have write access. No write was attempted."
        val before = readOneLine(node)
        val beforeLevel = before.toIntOrNull()
            ?: return "REJECTED: current driver value is not a numeric level (raw='$before')."
        if (beforeLevel !in 0..6) return "REJECTED: current driver value $beforeLevel is outside the verified 0..6 range."
        val statusFile = File("/sys/class/power_supply/battery/status")
        val batteryStatus = if (statusFile.canRead()) readOneLine(statusFile) else "UNKNOWN"
        if (!batteryStatus.equals("Charging", ignoreCase = true)) {
            return "REJECTED: battery status is '$batteryStatus', not Charging. Connect the charger and rescan before changing the limit."
        }
        return try {
            FileOutputStream(node).use { stream ->
                stream.write("$level\n".toByteArray(StandardCharsets.US_ASCII))
                stream.flush()
            }
            val after = readOneLine(node)
            val actual = after.toIntOrNull()
            if (actual == level) {
                "READ-BACK VERIFIED: old level=$beforeLevel, requested level=$level, read-back level=$actual. This verifies the driver setting only; it does not prove the physical input current changed."
            } else {
                "READ-BACK MISMATCH: old level=$beforeLevel, requested level=$level, returned raw='$after'. Treat the setting as unverified and run a fresh audit."
            }
        } catch (t: Throwable) {
            "WRITE FAILED: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}. No further changes were attempted."
        }
    }

    private fun StringBuilder.appendSection(
        title: String,
        root: File?,
        allowed: Set<String>,
        maxDepth: Int,
        command: String? = null
    ) {
        appendLine("=== $title ===")
        if (command != null) {
            val output = runCommand(command.split(" "), 5000)
            appendLine(output.ifBlank { "No output returned." })
            return
        }
        if (root == null) return
        if (!root.exists()) {
            appendLine("NOT PRESENT: ${root.path}")
            return
        }
        if (!root.canRead()) {
            appendLine("PRESENT BUT NOT READABLE: ${root.path}")
            return
        }
        var emitted = 0
        fun walk(dir: File, depth: Int) {
            if (depth > maxDepth || emitted >= 180) return
            val children = try { dir.listFiles()?.sortedBy { it.name } } catch (t: Throwable) { null }
            if (children == null) {
                appendLine("LIST DENIED/FAILED: ${dir.path}")
                return
            }
            for (child in children) {
                if (emitted >= 180) break
                if (child.isDirectory) {
                    walk(child, depth + 1)
                } else if (child.isFile && child.name in allowed) {
                    emitted++
                    val value = readOneLine(child)
                    val mode = when {
                        !child.canRead() -> "NO_READ"
                        child.canWrite() -> "DAC ACCESS CHECK: READABLE AND WRITABLE; SELINUX/DRIVER BEHAVIOR NOT TESTED"
                        else -> "READABLE; NO WRITE ACCESS"
                    }
                    appendLine("${child.path} = ${value}  [${mode}]")
                }
            }
        }
        walk(root, 0)
        if (emitted == 0) appendLine("No matching readable attributes found.")
        if (emitted >= 180) appendLine("Output cap reached; scan truncated.")
    }

    private fun readOneLine(file: File): String {
        return try {
            if (!file.canRead()) {
                "READ_DENIED"
            } else {
                file.inputStream().bufferedReader().use { it.readLine()?.take(240) ?: "EMPTY" }
            }
        } catch (t: Throwable) {
            "ERROR:${t.javaClass.simpleName}:${t.message ?: "unknown"}".take(240)
        }
    }

    private fun runCommand(command: List<String>, timeoutMs: Long): String = try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) {
                            if (output.length < 6000) output.append(line).append('\n')
                        }
                    }
                }
            } catch (_: Throwable) { }
        }
        reader.isDaemon = true
        reader.start()
        val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!completed) {
            process.destroyForcibly()
            reader.join(500)
            "TIMEOUT after ${timeoutMs} ms"
        } else {
            reader.join(1000)
            synchronized(output) { output.toString().take(6000) }
        }
    } catch (t: Throwable) {
        "COMMAND FAILED: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
    }

    private fun safe(block: () -> String): String = try { block() } catch (t: Throwable) { "UNAVAILABLE (${t.javaClass.simpleName})" }

    override fun destroy() {
        System.exit(0)
    }
}
