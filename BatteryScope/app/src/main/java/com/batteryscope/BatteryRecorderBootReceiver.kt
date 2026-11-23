package com.batteryscope

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Resume only a recorder the user explicitly left active before reboot. */
class BatteryRecorderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(BatteryRecorderService.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(BatteryRecorderService.KEY_USER_ENABLED, false)) return
        try {
            // Do not open SQLite in BOOT_COMPLETED on Android's broadcast main thread.
            // The service records RECORDER_RESTARTED after it reaches its worker thread.
            val start = Intent(context, BatteryRecorderService::class.java)
                .setAction(BatteryRecorderService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(start)
            else context.startService(start)
        } catch (t: Throwable) {
            // Preserve the user's opt-in and expose the exact startup failure in diagnostics.
            prefs.edit()
                .putString(
                    BatteryRecorderService.KEY_BOOT_RESUME_FAILURE,
                    t.javaClass.simpleName + ": " + (t.message ?: "unknown")
                )
                .putLong(BatteryRecorderService.KEY_BOOT_RESUME_FAILURE_AT, System.currentTimeMillis())
                .apply()
        }
    }
}
