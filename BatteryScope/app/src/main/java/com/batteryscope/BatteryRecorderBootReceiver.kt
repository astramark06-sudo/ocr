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
            val start = Intent(context, BatteryRecorderService::class.java)
                .setAction(BatteryRecorderService.ACTION_START)
            val db = RecorderDatabase(context)
            db.insertEvent(System.currentTimeMillis(), "BOOT_RESUME_REQUESTED", "User had left all-day recording enabled before reboot")
            db.close()
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(start)
            else context.startService(start)
        } catch (t: Throwable) {
            // Keep the user-enabled preference; record the failed attempt for diagnosis.
            try {
                val db = RecorderDatabase(context)
                db.insertEvent(System.currentTimeMillis(), "BOOT_RESUME_FAILED", t.javaClass.simpleName + ": " + (t.message ?: "unknown"))
                db.close()
            } catch (_: Throwable) { }
        }
    }
}
