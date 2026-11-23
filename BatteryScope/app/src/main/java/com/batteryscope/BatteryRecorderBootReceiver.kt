package com.batteryscope

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Resume permanent local monitoring after boot or an application update. */
class BatteryRecorderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = context.getSharedPreferences(BatteryRecorderService.PREFS, Context.MODE_PRIVATE)
        try {
            // Do not open SQLite in a broadcast callback. The foreground service
            // initializes its worker and records the restart event itself.
            val start = Intent(context, BatteryRecorderService::class.java)
                .setAction(BatteryRecorderService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(start)
            else context.startService(start)
        } catch (t: Throwable) {
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
