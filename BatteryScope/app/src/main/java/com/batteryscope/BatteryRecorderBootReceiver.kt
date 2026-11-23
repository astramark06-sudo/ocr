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
        if (!prefs.getBoolean(BatteryRecorderService.KEY_ACTIVE, false)) return
        try {
            val start = Intent(context, BatteryRecorderService::class.java)
                .setAction(BatteryRecorderService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(start)
            else context.startService(start)
        } catch (_: Throwable) {
            // OS/OEM restrictions may prevent automatic recovery; the gap remains visible in history.
        }
    }
}
