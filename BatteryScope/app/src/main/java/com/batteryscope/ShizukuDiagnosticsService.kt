package com.batteryscope

import android.system.Os
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Runs inside the Shizuku UserService process (ADB shell UID or root UID).
 * This service is intentionally read-only. It never opens a sysfs node for writing.
 */
class ShizukuDiagnosticsService : IBatteryDiagnosticsService.Stub() {

    override fun scan(): String = buildString {
        appendLine("BATTERYSCOPE PRIVILEGED READ-ONLY AUDIT")
        appendLine("Process UID: ${safe { Os.getuid().toString() }} (2000 normally means ADB shell; 0 means root)")
        appendLine("PID: ${safe { Os.getpid().toString() }}")
        appendLine("SELinux context: ${readOneLine(File("/proc/self/attr/current"))}")
        appendLine("Timestamp: ${System.currentTimeMillis()}")
        appendLine()
        appendSection("POWER SUPPLY / USB VBUS AND IBUS", File("/sys/class/power_supply"),
            setOf("type", "online", "present", "status", "health", "capacity", "voltage_now",
                "voltage_avg", "voltage_max", "voltage_min", "current_now", "current_avg",
                "current_max", "power_now", "input_current_limit", "input_voltage_limit",
                "input_power_limit", "usb_type", "real_type", "charge_type", "constant_charge_current",
                "constant_charge_voltage", "charge_control_limit", "charge_control_limit_max",
                "charge_counter", "charge_now", "charge_full", "temp", "model_name", "manufacturer"),
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
        appendLine("CONTROL SAFETY")
        appendLine("No attributes were written. Writable status is an access check only, not proof that changing a value is safe.")
        appendLine("No USB role, voltage, current limit, charging mode, or protocol negotiation was changed.")
        appendLine("A missing node means not exposed at this path; it does not prove the hardware lacks that capability.")
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
                        child.canWrite() -> "READABLE; WRITE ACCESS APPEARS AVAILABLE"
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

    private fun readOneLine(file: File): String = try {
        if (!file.canRead()) return "READ_DENIED"
        file.inputStream().bufferedReader().use { it.readLine()?.take(240) ?: "EMPTY" }
    } catch (t: Throwable) {
        "ERROR:${t.javaClass.simpleName}:${t.message ?: "unknown"}".take(240)
    }

    private fun runCommand(command: List<String>, timeoutMs: Long): String = try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!completed) {
            process.destroyForcibly()
            "TIMEOUT after ${timeoutMs} ms"
        } else {
            process.inputStream.bufferedReader().use { it.readText().take(6000) }
        }
    } catch (t: Throwable) {
        "COMMAND FAILED: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
    }

    private fun safe(block: () -> String): String = try { block() } catch (t: Throwable) { "UNAVAILABLE (${t.javaClass.simpleName})" }

    override fun destroy() {
        System.exit(0)
    }
}
