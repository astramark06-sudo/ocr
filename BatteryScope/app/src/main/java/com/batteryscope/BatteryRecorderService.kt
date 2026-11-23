package com.batteryscope

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import org.json.JSONObject
import java.io.Writer
import java.util.Locale
import kotlin.math.abs

/** User-started, persistent local recorder. No activity lifecycle owns this sampler. */
class BatteryRecorderService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var db: RecorderDatabase
    private lateinit var batteryManager: BatteryManager
    private var batteryIntent: Intent? = null
    private var lastUsageQueryMs = 0L
    private var foregroundPackage: String? = null
    private var lastSysfsReadMs = 0L
    private var lastSysfsJson = JSONObject()
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                Intent.ACTION_BATTERY_CHANGED -> batteryIntent = intent
                Intent.ACTION_POWER_CONNECTED -> event("POWER_CONNECTED", "Android power-connected broadcast")
                Intent.ACTION_POWER_DISCONNECTED -> event("POWER_DISCONNECTED", "Android power-disconnected broadcast")
                Intent.ACTION_SCREEN_ON -> event("SCREEN_ON", "Display became interactive")
                Intent.ACTION_SCREEN_OFF -> event("SCREEN_OFF", "Display became non-interactive")
                Intent.ACTION_BATTERY_LOW -> event("BATTERY_LOW", "Android battery-low broadcast")
                Intent.ACTION_BATTERY_OKAY -> event("BATTERY_OKAY", "Battery-low condition cleared")
            }
        }
    }

    private val sampler = object : Runnable {
        override fun run() {
            try { sample() }
            catch (t: Throwable) { event("SAMPLE_ERROR", t.javaClass.simpleName + ": " + (t.message ?: "unknown")) }
            finally { handler.postDelayed(this, SAMPLE_INTERVAL_MS) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        db = RecorderDatabase(this)
        batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
        batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        createChannel()
        registerEvents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecorder()
            return START_NOT_STICKY
        }
        try {
            startAsForeground()
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, true).apply()
            event(if (intent == null) "RECORDER_RESTARTED" else "RECORDER_STARTED",
                "User-visible foreground recorder; sample interval " + SAMPLE_INTERVAL_MS + " ms")
            handler.removeCallbacks(sampler)
            sample()
            handler.postDelayed(sampler, SAMPLE_INTERVAL_MS)
        } catch (t: Throwable) {
            event("RECORDER_START_FAILED", t.javaClass.simpleName + ": " + (t.message ?: "unknown"))
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply()
            stopSelf()
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("BatteryScope is recording")
            .setContentText("Battery, charger, charging, discharging and app-usage telemetry")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Battery telemetry recorder", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Visible notification while BatteryScope records telemetry"
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    @Suppress("DEPRECATION")
    private fun registerEvents() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)
        receiverRegistered = true
    }

    private fun sample() {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val intent = batteryIntent ?: registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val temperature = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val statusCode = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val status = when (statusCode) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
            BatteryManager.BATTERY_STATUS_FULL -> "FULL"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
            else -> "UNKNOWN"
        }
        val plugCode = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val plug = when (plugCode) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
            else -> if (plugCode == 0) "NONE" else "OTHER_" + plugCode
        }
        val healthCode = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val health = when (healthCode) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
            BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER_VOLTAGE"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "UNSPECIFIED_FAILURE"
            BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
            else -> "UNKNOWN"
        }
        val current = longProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val average = longProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        val charge = longProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val energy = longProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        val screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        val activeApp = updateForegroundPackage(now)
        if (now - lastSysfsReadMs >= SYSFS_INTERVAL_MS) {
            lastSysfsJson = readPowerSupplyTree()
            lastSysfsReadMs = now
        }
        val usb = findUsbCandidate(lastSysfsJson)
        val row = ContentValues().apply {
            put("timestamp_ms", now)
            put("elapsed_realtime_ms", elapsed)
            if (percent != null) put("battery_percent", percent)
            put("battery_status", status)
            put("plug_type", plug)
            if (voltage > 0) put("battery_voltage_mv", voltage)
            if (temperature in 0..1000) put("temperature_c", temperature / 10.0)
            if (current != null) put("battery_current_ua", current)
            if (average != null) put("battery_average_current_ua", average)
            if (charge != null) put("charge_counter_uah", charge)
            if (energy != null) put("energy_counter_nwh", energy)
            put("battery_health", health)
            put("technology", intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "NOT_REPORTED")
            put("screen_on", if (screenOn) 1 else 0)
            put("foreground_package", activeApp ?: "")
            put("usage_access", if (hasUsageAccess()) 1 else 0)
            put("power_supply_json", lastSysfsJson.toString())
            if (usb != null) {
                put("usb_voltage_candidate_uv", usb.first)
                put("usb_current_candidate_ua", usb.second)
            }
        }
        db.insertSample(row)
    }

    private fun longProperty(id: Int): Long? = try {
        val value = batteryManager.getLongProperty(id)
        if (value == Long.MIN_VALUE || value == Long.MAX_VALUE) null else value
    } catch (_: Throwable) { null }

    private fun hasUsageAccess(): Boolean = try {
        val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) { false }

    private fun updateForegroundPackage(now: Long): String? {
        if (!hasUsageAccess()) return null
        val manager = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val from = if (lastUsageQueryMs > 0L) lastUsageQueryMs else now - 60_000L
        return try {
            val events = manager.queryEvents(from, now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                        val next = event.packageName
                        if (next != foregroundPackage) {
                            if (foregroundPackage != null) this.event("APP_FOREGROUND_END", foregroundPackage!!)
                            foregroundPackage = next
                            this.event("APP_FOREGROUND_START", next)
                        }
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                        if (event.packageName == foregroundPackage) {
                            this.event("APP_FOREGROUND_END", event.packageName)
                            foregroundPackage = null
                        }
                    }
                }
            }
            lastUsageQueryMs = now
            foregroundPackage
        } catch (t: Throwable) {
            this.event("USAGE_QUERY_ERROR", t.javaClass.simpleName + ": " + (t.message ?: "unknown"))
            lastUsageQueryMs = now
            foregroundPackage
        }
    }

    private fun readPowerSupplyTree(): JSONObject {
        val result = JSONObject()
        val root = java.io.File("/sys/class/power_supply")
        if (!root.exists()) { result.put("@root", "NOT_PRESENT"); return result }
        if (!root.canRead()) { result.put("@root", "READ_DENIED"); return result }
        val attributes = listOf(
            "type", "online", "present", "status", "health", "capacity", "voltage_now",
            "voltage_avg", "voltage_max", "current_now", "current_avg", "current_max", "power_now",
            "charge_counter", "charge_now", "charge_full", "charge_full_design", "temp",
            "usb_type", "real_type", "charge_type", "input_current_limit", "input_current_limit_max",
            "input_voltage_limit", "input_power_limit", "constant_charge_current",
            "constant_charge_voltage", "constant_charge_current_max", "constant_charge_voltage_max",
            "charge_control_limit", "charge_control_start_threshold", "charge_control_end_threshold",
            "fastcharger", "fast_chg_type", "voocchg_ing", "charge_technology", "adapter_fw_update",
            "chargerid_volt", "charger_ic", "cool_down"
        )
        try {
            val supplies = root.listFiles()?.sortedBy { it.name } ?: emptyList()
            var count = 0
            for (supply in supplies) {
                if (count >= MAX_SYSFS_FIELDS) break
                if (!supply.isDirectory) continue
                for (attribute in attributes) {
                    if (count >= MAX_SYSFS_FIELDS) break
                    val node = java.io.File(supply, attribute)
                    if (!node.exists()) continue
                    count++
                    val value = try {
                        if (!node.canRead()) "READ_DENIED" else node.readText().trim().take(240).ifBlank { "EMPTY" }
                    } catch (t: Throwable) {
                        "ERROR:" + t.javaClass.simpleName + ":" + (t.message ?: "unknown")
                    }
                    result.put(supply.name + "/" + attribute, value)
                }
            }
            result.put("@field_count", count)
        } catch (t: Throwable) {
            result.put("@scan_error", t.javaClass.simpleName + ":" + (t.message ?: "unknown"))
        }
        return result
    }

    private fun findUsbCandidate(json: JSONObject): Pair<Long, Long>? {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val supply = key.substringBefore('/')
            if (supply.lowercase(Locale.US) in setOf("battery", "bms", "main") || !key.endsWith("/voltage_now")) continue
            val voltage = json.optString(key).toLongOrNull() ?: continue
            val current = json.optString(supply + "/current_now").toLongOrNull() ?: continue
            if (voltage in 1_000_000L..30_000_000L && abs(current) <= 20_000_000L) return voltage to current
        }
        return null
    }

    private fun event(type: String, details: String) {
        try { db.insertEvent(System.currentTimeMillis(), type, details) } catch (_: Throwable) { }
    }

    private fun stopRecorder() {
        handler.removeCallbacks(sampler)
        event("RECORDER_STOPPED", "Stopped by user")
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply()
        if (receiverRegistered) {
            try { unregisterReceiver(receiver) } catch (_: Throwable) { }
            receiverRegistered = false
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep recording after the UI task is swiped away.
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(sampler)
        if (receiverRegistered) try { unregisterReceiver(receiver) } catch (_: Throwable) { }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply()
        try { db.close() } catch (_: Throwable) { }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.batteryscope.action.START_RECORDER"
        const val ACTION_STOP = "com.batteryscope.action.STOP_RECORDER"
        const val PREFS = "batteryscope_recorder"
        const val KEY_ACTIVE = "recorder_active"
        const val SAMPLE_INTERVAL_MS = 30_000L
        private const val SYSFS_INTERVAL_MS = 120_000L
        private const val MAX_SYSFS_FIELDS = 180
        private const val CHANNEL_ID = "batteryscope_recorder"
        private const val NOTIFICATION_ID = 301
    }
}

/** SQLite keeps raw samples/events until the user deletes app data. */
class RecorderDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "batteryscope_history.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE samples (
            timestamp_ms INTEGER PRIMARY KEY, elapsed_realtime_ms INTEGER NOT NULL,
            battery_percent INTEGER, battery_status TEXT NOT NULL, plug_type TEXT NOT NULL,
            battery_voltage_mv INTEGER, battery_current_ua INTEGER, battery_average_current_ua INTEGER,
            charge_counter_uah INTEGER, energy_counter_nwh INTEGER, temperature_c REAL,
            battery_health TEXT, technology TEXT, screen_on INTEGER NOT NULL,
            foreground_package TEXT, usage_access INTEGER NOT NULL, power_supply_json TEXT,
            usb_voltage_candidate_uv INTEGER, usb_current_candidate_ua INTEGER
        )""".trimIndent())
        db.execSQL("CREATE INDEX idx_samples_time ON samples(timestamp_ms)")
        db.execSQL("CREATE INDEX idx_samples_state_time ON samples(battery_status,timestamp_ms)")
        db.execSQL("""CREATE TABLE events (
            id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp_ms INTEGER NOT NULL,
            event_type TEXT NOT NULL, details TEXT
        )""".trimIndent())
        db.execSQL("CREATE INDEX idx_events_time ON events(timestamp_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations must preserve raw history.
    }

    fun insertSample(values: ContentValues) {
        writableDatabase.insertWithOnConflict("samples", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun insertEvent(timestamp: Long, type: String, details: String) {
        val values = ContentValues().apply {
            put("timestamp_ms", timestamp)
            put("event_type", type)
            put("details", details.take(1000))
        }
        writableDatabase.insert("events", null, values)
    }

    fun historySummary(now: Long = System.currentTimeMillis()): String {
        val windows = listOf("24 HOURS" to 24L * 60 * 60 * 1000, "7 DAYS" to 7L * 24 * 60 * 60 * 1000, "30 DAYS" to 30L * 24 * 60 * 60 * 1000)
        return buildString {
            val total = readableDatabase.rawQuery("SELECT COUNT(*) FROM samples", null).use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            }
            appendLine("LOCAL HISTORY • RAW SAMPLES RETAINED UNTIL USER DELETES APP DATA")
            appendLine("Stored samples: " + total)
            appendLine("App attribution is time-correlation, not direct per-app electrical measurement.")
            for ((label, duration) in windows) {
                val rows = readWindow(now - duration)
                val stats = summarise(rows)
                appendLine()
                appendLine(label + " • " + stats.count + " samples")
                appendLine("Charging: " + stats.chargeHours + " h recorded • " + stats.chargedPct + " percentage points gained • " + stats.chargeRate + " %/h")
                appendLine("Discharging: " + stats.dischargeHours + " h recorded • " + stats.dischargedPct + " percentage points lost • " + stats.dischargeRate + " %/h")
                appendLine("Screen-on samples: " + stats.screenOn + " • screen-off samples: " + stats.screenOff)
                if (stats.apps.isNotEmpty()) {
                    appendLine("Foreground apps (approximate sampled time):")
                    stats.apps.take(5).forEach { appendLine("  " + it.first + " • " + it.second / 60 + " min") }
                } else appendLine("Foreground app history: unavailable / not yet recorded")
            }
            val last = readableDatabase.rawQuery(
                "SELECT timestamp_ms,battery_percent,battery_status,plug_type,foreground_package FROM samples ORDER BY timestamp_ms DESC LIMIT 1", null
            ).use { c ->
                if (!c.moveToFirst()) "No sample recorded yet"
                else "Last sample: " + c.getLong(0) + " • " +
                    (if (c.isNull(1)) "--" else c.getInt(1).toString() + "%") + " • " +
                    c.getString(2) + " • " + c.getString(3) + " • app=" + c.getString(4)
            }
            appendLine()
            appendLine(last)
        }
    }

    private data class Row(val time: Long, val pct: Int?, val state: String, val screen: Boolean, val app: String)
    private data class Stats(
        val count: Int, val chargeHours: String, val dischargeHours: String,
        val chargedPct: String, val dischargedPct: String, val chargeRate: String,
        val dischargeRate: String, val screenOn: Int, val screenOff: Int,
        val apps: List<Pair<String, Long>>
    )

    private fun readWindow(from: Long): List<Row> {
        val rows = mutableListOf<Row>()
        readableDatabase.rawQuery(
            "SELECT timestamp_ms,battery_percent,battery_status,screen_on,foreground_package FROM samples WHERE timestamp_ms>=? ORDER BY timestamp_ms",
            arrayOf(from.toString())
        ).use { c ->
            while (c.moveToNext()) rows += Row(c.getLong(0), if (c.isNull(1)) null else c.getInt(1), c.getString(2), c.getInt(3) == 1, c.getString(4) ?: "")
        }
        return rows
    }

    private fun summarise(rows: List<Row>): Stats {
        var chargeMs = 0L
        var dischargeMs = 0L
        var charged = 0.0
        var discharged = 0.0
        var screenOn = 0
        var screenOff = 0
        val apps = linkedMapOf<String, Long>()
        rows.forEach { if (it.screen) screenOn++ else screenOff++ }
        for (i in 1 until rows.size) {
            val a = rows[i - 1]
            val b = rows[i]
            val dt = b.time - a.time
            if (dt !in 1_000L..(BatteryRecorderService.SAMPLE_INTERVAL_MS * 3)) continue
            if (b.screen && b.app.isNotBlank()) apps[b.app] = (apps[b.app] ?: 0L) + dt
            if (a.pct == null || b.pct == null) continue
            val delta = (b.pct - a.pct).toDouble()
            if (a.state == "CHARGING" && b.state == "CHARGING" && delta >= 0) {
                chargeMs += dt
                charged += delta
            } else if (a.state == "DISCHARGING" && b.state == "DISCHARGING" && delta <= 0) {
                dischargeMs += dt
                discharged += -delta
            }
        }
        val ch = chargeMs / 3_600_000.0
        val dh = dischargeMs / 3_600_000.0
        fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)
        return Stats(rows.size, fmt(ch), fmt(dh), fmt(charged), fmt(discharged),
            if (ch > 0) fmt(charged / ch) else "n/a",
            if (dh > 0) fmt(discharged / dh) else "n/a",
            screenOn, screenOff, apps.entries.sortedByDescending { it.value }.map { it.key to it.value / 1000L })
    }

    fun exportCsv(writer: Writer) {
        writer.write("record_type,timestamp_ms,battery_percent,battery_status,plug_type,battery_voltage_mv,battery_current_ua,battery_average_current_ua,charge_counter_uah,energy_counter_nwh,temperature_c,battery_health,technology,screen_on,foreground_package,usage_access,power_supply_json,usb_voltage_candidate_uv,usb_current_candidate_ua,event_type,details\n")
        readableDatabase.rawQuery("SELECT * FROM samples ORDER BY timestamp_ms", null).use { c ->
            while (c.moveToNext()) {
                val names = listOf("battery_percent","battery_status","plug_type","battery_voltage_mv","battery_current_ua","battery_average_current_ua","charge_counter_uah","energy_counter_nwh","temperature_c","battery_health","technology","screen_on","foreground_package","usage_access","power_supply_json","usb_voltage_candidate_uv","usb_current_candidate_ua")
                val fields = mutableListOf("SAMPLE", c.getLong(c.getColumnIndexOrThrow("timestamp_ms")).toString())
                names.forEach { fields += value(c, it) }
                fields += listOf("", "")
                writer.write(fields.joinToString(",") { csv(it) } + "\n")
            }
        }
        readableDatabase.rawQuery("SELECT timestamp_ms,event_type,details FROM events ORDER BY timestamp_ms", null).use { c ->
            while (c.moveToNext()) {
                val fields = listOf("EVENT", c.getLong(0).toString()) + List(17) { "" } + listOf(c.getString(1), c.getString(2))
                writer.write(fields.joinToString(",") { csv(it) } + "\n")
            }
        }
        writer.flush()
    }

    private fun value(c: Cursor, column: String): String {
        val index = c.getColumnIndexOrThrow(column)
        return if (c.isNull(index)) "" else c.getString(index) ?: ""
    }

    private fun csv(value: String): String =
        "\"" + value.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ") + "\""
}
