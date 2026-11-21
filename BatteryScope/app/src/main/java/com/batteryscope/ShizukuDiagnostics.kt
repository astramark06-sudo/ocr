package com.batteryscope

import rikka.shizuku.Shizuku

object ShizukuDiagnostics {
    fun scan(): String {
        val script = "id; getenforce 2>&1; for d in /sys/class/power_supply/*; do [ -e \"\$d\" ] || continue; echo NODE:\$d; for a in type online present status health capacity voltage_now voltage_avg current_now current_avg power_now charge_counter charge_now charge_full temp charge_type usb_type real_type input_current_limit input_voltage_limit constant_charge_current constant_charge_voltage current_limit voltage_limit; do f=\"\$d/\$a\"; if [ -r \"\$f\" ]; then printf '%s=' \"\$a\"; cat \"\$f\" 2>&1; fi; done; done"
        val process = Shizuku.newProcess(arrayOf("sh", "-c", script), null, null)
        val output = process.inputStream.bufferedReader().readText()
        val errors = process.errorStream.bufferedReader().readText()
        val code = process.waitFor()
        return "Exit code: " + code + "\n" + output + if (errors.isNotBlank()) "\nSTDERR:\n" + errors else ""
    }
}
