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
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import org.json.JSONObject
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import rikka.shizuku.Shizuku
import android.content.pm.PackageManager

/** User-started, persistent local recorder. No activity lifecycle owns this sampler. */
class BatteryRecorderService : Service() {
    private lateinit var handler: Handler
    private lateinit var worker: HandlerThread
    private lateinit var db: RecorderDatabase
    private lateinit var batteryManager: BatteryManager
    private var batteryIntent: Intent? = null
    private var lastUsageQueryMs = 0L
    private var foregroundPackage: String? = null
    private var lastSampleState: String? = null
    private var lastSamplePlug: String? = null
    private var lastSysfsReadMs = 0L
    private var lastFullAuditMs = 0L
    private var lastSysfsJson = JSONObject()
    private var receiverRegistered = false
    private var samplingWakeLock: PowerManager.WakeLock? = null
    private var privilegedService: IBatteryDiagnosticsService? = null
    private var privilegedBinding = false

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

    private val diagnosticArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(packageName, ShizukuDiagnosticsService::class.java.name)
        ).daemon(false)
            .processNameSuffix("batteryscope_recorder_diag")
            .version(4)
            .tag("batteryscope_recorder_readonly")
    }

    private val privilegedConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            privilegedService = IBatteryDiagnosticsService.Stub.asInterface(binder)
            privilegedBinding = false
            event("SHIZUKU_CONNECTED", "Privileged read-only telemetry connection established")
            handler.post {
                lastSysfsReadMs = 0L
                sample()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            privilegedService = null
            privilegedBinding = false
            event("SHIZUKU_DISCONNECTED", "Privileged telemetry connection lost; ordinary app telemetry continues")
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
        worker = HandlerThread("BatteryScopeRecorder").apply { start() }
        handler = Handler(worker.looper)
        batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        createChannel()
        registerEvents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ENABLE_SHIZUKU) {
            try {
                startAsForeground()
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_ACTIVE, true).putBoolean(KEY_USER_ENABLED, true).apply()
                acquireSamplingWakeLock()
                bindPrivilegedTelemetry()
                handler.removeCallbacks(sampler)
                handler.post(sampler)
            } catch (t: Throwable) {
                event("SHIZUKU_ENABLE_FAILED", t.javaClass.simpleName + ": " + (t.message ?: "unknown"))
            }
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            stopRecorder()
            return START_NOT_STICKY
        }
        try {
            startAsForeground()
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_ACTIVE, true).putBoolean(KEY_USER_ENABLED, true).apply()
            acquireSamplingWakeLock()
            bindPrivilegedTelemetry()
            event(if (intent == null) "RECORDER_RESTARTED" else "RECORDER_STARTED",
                "Foreground recorder; 30-second sampling; CPU partial wake lock held while recording (adds app power use)")
            handler.removeCallbacks(sampler)
            handler.post(sampler)
        } catch (t: Throwable) {
            event("RECORDER_START_FAILED", t.javaClass.simpleName + ": " + (t.message ?: "unknown"))
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_ACTIVE, false).putBoolean(KEY_USER_ENABLED, false).apply()
            stopSelf()
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("BatteryScope is recording")
            .setContentText("Telemetry active • CPU kept awake for 30-second sampling; increases app drain")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun bindPrivilegedTelemetry() {
        if (privilegedService != null || privilegedBinding) return
        try {
            if (!Shizuku.pingBinder()) {
                event("SHIZUKU_UNAVAILABLE", "Start Shizuku using Wireless debugging and run the audit to enable privileged history")
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                event("SHIZUKU_PERMISSION_REQUIRED", "Grant BatteryScope access in Shizuku, then run the power-path audit")
                return
            }
            privilegedBinding = true
            Shizuku.bindUserService(diagnosticArgs, privilegedConnection)
        } catch (t: Throwable) {
            privilegedBinding = false
            event("SHIZUKU_BIND_FAILED", t.javaClass.simpleName + ": " + (t.message ?: "unknown"))
        }
    }

    private fun acquireSamplingWakeLock() {
        try {
            if (samplingWakeLock?.isHeld == true) return
            val power = getSystemService(POWER_SERVICE) as PowerManager
            val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BatteryScope::ContinuousRecorder")
            lock.setReferenceCounted(false)
            if (!lock.isHeld) lock.acquire()
            samplingWakeLock = lock
        } catch (t: Throwable) {
            event("WAKE_LOCK_FAILED", t.javaClass.simpleName + ": " + (t.message ?: "unknown"))
        }
    }

    private fun releaseSamplingWakeLock() {
        try {
            val lock = samplingWakeLock
            if (lock != null && lock.isHeld) lock.release()
        } catch (t: Throwable) {
            event("WAKE_LOCK_RELEASE_FAILED", t.javaClass.simpleName + ": " + (t.message ?: "unknown"))
        } finally {
            samplingWakeLock = null
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
        if (status != lastSampleState) {
            event("BATTERY_STATE_CHANGED", "from=" + (lastSampleState ?: "UNKNOWN") + " to=" + status)
            lastSampleState = status
        }
        if (plug != lastSamplePlug) {
            event("POWER_SOURCE_CHANGED", "from=" + (lastSamplePlug ?: "UNKNOWN") + " to=" + plug)
            lastSamplePlug = plug
        }
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
        val refreshSysfs = now - lastSysfsReadMs >= SYSFS_INTERVAL_MS
        if (refreshSysfs) {
            lastSysfsJson = readHardwareTrees()
            lastSysfsReadMs = now
            val privileged = privilegedService
            if (privileged != null) {
                try {
                    lastSysfsJson.put("@shizuku_snapshot", privileged.snapshotLink())
                    if (now - lastFullAuditMs >= FULL_AUDIT_INTERVAL_MS) {
                        val audit = privileged.snapshotBatteryStats()
                        val storedAudit = if (audit.length > MAX_AUDIT_CHARS) {
                            audit.take(MAX_AUDIT_CHARS) + "\n[TRUNCATED at " + MAX_AUDIT_CHARS + " characters]"
                        } else audit
                        lastSysfsJson.put("@shizuku_batterystats_charged", storedAudit)
                        lastFullAuditMs = now
                        event("SHIZUKU_BATTERYSTATS_CAPTURED", "Stored dumpsys batterystats --charged chars=" + storedAudit.length)
                    }
                } catch (t: Throwable) {
                    lastSysfsJson.put("@shizuku_error", t.javaClass.simpleName + ": " + (t.message ?: "unknown"))
                    event("SHIZUKU_SNAPSHOT_FAILED", t.javaClass.simpleName + ": " + (t.message ?: "unknown"))
                    privilegedService = null
                }
            } else {
                lastSysfsJson.put("@shizuku_snapshot", "UNAVAILABLE: Shizuku not connected/authorized")
            }
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
            if (refreshSysfs) put("power_supply_json", lastSysfsJson.toString())
            if (refreshSysfs && usb != null) {
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

    private fun readHardwareTrees(): JSONObject {
        val result = JSONObject()
        val roots = listOf(
            "/sys/class/power_supply" to 3,
            "/sys/class/typec" to 4,
            "/sys/class/usb_power_delivery" to 5,
            "/sys/class/dual_role_usb" to 3,
            "/sys/class/usb_role" to 3,
            "/sys/class/thermal" to 3,
            "/sys/class/hwmon" to 3,
            "/sys/bus/usb/devices" to 3
        )
        val skip = setOf("device", "subsystem", "driver", "power", "firmware_node", "of_node")
        var count = 0
        val maxFields = 900
        for ((rootPath, maxDepth) in roots) {
            val root = java.io.File(rootPath)
            val prefix = root.name
            if (!root.exists()) {
                result.put(prefix + "/@root", "NOT_PRESENT")
                continue
            }
            if (!root.canRead()) {
                result.put(prefix + "/@root", "READ_DENIED")
                continue
            }
            fun walk(directory: java.io.File, depth: Int) {
                if (depth > maxDepth || count >= maxFields) return
                val children = try { directory.listFiles()?.sortedBy { it.name } } catch (_: Throwable) { null }
                if (children == null) {
                    result.put(prefix + "/" + directory.path.removePrefix(root.path).trim('/') + "/@list", "LIST_DENIED")
                    return
                }
                for (child in children) {
                    if (count >= maxFields) break
                    if (child.isDirectory) {
                        if (child.name !in skip) walk(child, depth + 1)
                    } else if (child.isFile) {
                        val isUsbBus = rootPath == "/sys/bus/usb/devices"
                        val usbDescriptorFields = setOf("idVendor", "idProduct", "bcdDevice", "manufacturer", "product",
                            "bDeviceClass", "bDeviceSubClass", "bDeviceProtocol", "speed", "authorized")
                        if (isUsbBus && child.name !in usbDescriptorFields) continue
                        if (child.name.equals("serial", ignoreCase = true)) continue
                        count++
                        val value = try {
                            if (!child.canRead()) "READ_DENIED"
                            else child.inputStream().bufferedReader().use { it.readLine()?.take(240) ?: "EMPTY" }
                        } catch (t: Throwable) {
                            "ERROR:" + t.javaClass.simpleName + ":" + (t.message ?: "unknown")
                        }
                        val relative = child.path.removePrefix(root.path).trim('/')
                        result.put(prefix + "/" + relative, value)
                    }
                }
            }
            walk(root, 0)
            if (count >= maxFields) break
        }
        result.put("@field_count", count)
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val power = getSystemService(POWER_SERVICE) as PowerManager
                result.put("@android_thermal_status", power.currentThermalStatus)
            } catch (t: Throwable) {
                result.put("@android_thermal_status", "UNAVAILABLE:" + t.javaClass.simpleName)
            }
        }
        return result
    }

    private fun findUsbCandidate(json: JSONObject): Pair<Long, Long>? {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!key.endsWith("/voltage_now")) continue
            val supplyPath = key.substringBeforeLast('/')
            val supplyName = supplyPath.substringAfterLast('/')
            if (supplyName.lowercase(Locale.US) in setOf("battery", "bms", "main")) continue
            val voltage = json.optString(key).toLongOrNull() ?: continue
            val current = json.optString(supplyPath + "/current_now").toLongOrNull() ?: continue
            val type = json.optString(supplyPath + "/type").uppercase(Locale.US)
            val looksUsb = supplyName.contains("usb", true) || type.contains("USB")
            if (looksUsb && voltage in 1_000_000L..30_000_000L && abs(current) <= 20_000_000L) {
                return voltage to current
            }
        }
        return null
    }

    private fun event(type: String, details: String) {
        try { db.insertEvent(System.currentTimeMillis(), type, details) } catch (_: Throwable) { }
    }

    private fun stopRecorder() {
        handler.removeCallbacks(sampler)
        releaseSamplingWakeLock()
        try {
            if (privilegedBinding || privilegedService != null) Shizuku.unbindUserService(diagnosticArgs, privilegedConnection, true)
        } catch (_: Throwable) { }
        privilegedService = null
        privilegedBinding = false
        event("RECORDER_STOPPED", "Stopped by user")
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVE, false).putBoolean(KEY_USER_ENABLED, false).apply()
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
        releaseSamplingWakeLock()
        try {
            if (privilegedBinding || privilegedService != null) Shizuku.unbindUserService(diagnosticArgs, privilegedConnection, true)
        } catch (_: Throwable) { }
        privilegedService = null
        privilegedBinding = false
        if (receiverRegistered) try { unregisterReceiver(receiver) } catch (_: Throwable) { }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply()
        try { db.close() } catch (_: Throwable) { }
        try { worker.quitSafely() } catch (_: Throwable) { }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.batteryscope.action.START_RECORDER"
        const val ACTION_STOP = "com.batteryscope.action.STOP_RECORDER"
        const val ACTION_ENABLE_SHIZUKU = "com.batteryscope.action.ENABLE_SHIZUKU"
        const val PREFS = "batteryscope_recorder"
        const val KEY_ACTIVE = "recorder_active"
        const val KEY_USER_ENABLED = "recorder_user_enabled"
        const val SAMPLE_INTERVAL_MS = 30_000L
        private const val FULL_AUDIT_INTERVAL_MS = 15L * 60 * 1000
        private const val MAX_AUDIT_CHARS = 20_000
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
        val windows = listOf(
            "24 HOURS" to 24L * 60 * 60 * 1000,
            "7 DAYS" to 7L * 24 * 60 * 60 * 1000,
            "30 DAYS" to 30L * 24 * 60 * 60 * 1000,
            "90 DAYS" to 90L * 24 * 60 * 60 * 1000
        )
        // Session detail uses a bounded recent window; raw history/export retains all records.
        val allRows = readWindow(now - 90L * 24 * 60 * 60 * 1000)
        return buildString {
            val total = readableDatabase.rawQuery("SELECT COUNT(*) FROM samples", null).use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            }
            appendLine("BATTERYSCOPE LOCAL HISTORY • RAW SAMPLES RETAINED UNTIL USER DELETES APP DATA")
            appendLine("Stored samples: " + total + " • target sample interval: 30 seconds")
            appendLine("App foreground correlation is not a direct per-app electrical measurement. Shizuku framework/system audit snapshots are retained every 15 minutes when authorized.")
            for ((label, duration) in windows) {
                val rows = if (duration == 90L * 24 * 60 * 60 * 1000) allRows else readWindow(now - duration)
                val s = summarise(rows)
                appendLine()
                appendLine("=== " + label + " • " + s.count + " samples ===")
                appendLine("Sampling continuity: " + gapReport(rows))
                appendLine("Charging: " + s.chargeHours + " h • +" + s.chargedPct + " percentage points • " + s.chargeRate + " %/h")
                appendLine("Discharging: " + s.dischargeHours + " h • -" + s.dischargedPct + " percentage points • " + s.dischargeRate + " %/h")
                appendLine("Charge-counter gain/loss: +" + s.chargeGainMah + " / -" + s.chargeLossMah + " mAh (Android estimate)")
                appendLine("Charging screen ON: " + s.chargeOnHours + " h • +" + s.chargeOnPct + "% • " + s.chargeOnRate + " %/h")
                appendLine("Charging screen OFF: " + s.chargeOffHours + " h • +" + s.chargeOffPct + "% • " + s.chargeOffRate + " %/h")
                appendLine("Discharging screen ON: " + s.drainOnHours + " h • -" + s.drainOnPct + "% • " + s.drainOnRate + " %/h")
                appendLine("Discharging screen OFF: " + s.drainOffHours + " h • -" + s.drainOffPct + "% • " + s.drainOffRate + " %/h")
                appendLine("Screen state samples: ON " + s.screenOn + " • OFF " + s.screenOff)
                appendLine("Foreground app time + coincident discharge (approx.):")
                if (s.apps.isEmpty()) appendLine("  unavailable / Usage Access not granted / not yet recorded")
                else s.apps.take(8).forEach { app ->
                    appendLine("  " + app.name + ": " + app.minutes + " min foreground • +" + app.chargePct + "% during charging • -" + app.drainPct + "% during discharging")
                }
            }
            appendLine()
            appendLine("=== MOST RECENT CHARGING SESSION ===")
            appendLine(sessionReport(allRows, true))
            appendLine()
            appendLine("=== MOST RECENT DISCHARGING SESSION ===")
            appendLine(sessionReport(allRows, false))
            appendLine()
            appendLine("=== RECENT CHARGING SESSIONS ===")
            appendLine(sessionHistoryReport(allRows, true, 8))
            appendLine()
            appendLine("=== RECENT DISCHARGING SESSIONS ===")
            appendLine(sessionHistoryReport(allRows, false, 8))
            appendLine()
            appendLine("Rate intervals longer than 90 seconds are excluded. Threshold times are resolved to the sample interval, not exact hardware event times.")
            val last = readableDatabase.rawQuery(
                "SELECT timestamp_ms,battery_percent,battery_status,plug_type,foreground_package FROM samples ORDER BY timestamp_ms DESC LIMIT 1", null
            ).use { c ->
                if (!c.moveToFirst()) "No sample recorded yet"
                else "Last sample: " + c.getLong(0) + " • " +
                    (if (c.isNull(1)) "--" else c.getInt(1).toString() + "%") + " • " +
                    c.getString(2) + " • " + c.getString(3) + " • app=" + c.getString(4)
            }
            appendLine(last)
        }
    }

    private data class Row(
        val time: Long, val pct: Int?, val state: String, val plug: String,
        val chargeCounter: Long?, val screen: Boolean, val app: String
    )
    private data class AppMetric(val name: String, val ms: Long, val charge: Double, val drain: Double) {
        val minutes: Long get() = ms / 60_000L
        val chargePct: String get() = String.format(Locale.US, "%.1f", charge)
        val drainPct: String get() = String.format(Locale.US, "%.1f", drain)
    }
    private data class Bucket(var ms: Long = 0L, var pct: Double = 0.0, var chargeCounterUah: Long = 0L)
    private data class Stats(
        val count: Int, val chargeHours: String, val dischargeHours: String,
        val chargedPct: String, val dischargedPct: String, val chargeRate: String, val dischargeRate: String,
        val chargeGainMah: String, val chargeLossMah: String,
        val chargeOnHours: String, val chargeOnPct: String, val chargeOnRate: String,
        val chargeOffHours: String, val chargeOffPct: String, val chargeOffRate: String,
        val drainOnHours: String, val drainOnPct: String, val drainOnRate: String,
        val drainOffHours: String, val drainOffPct: String, val drainOffRate: String,
        val screenOn: Int, val screenOff: Int, val apps: List<AppMetric>
    )

    private fun readWindow(from: Long): List<Row> {
        val rows = mutableListOf<Row>()
        readableDatabase.rawQuery(
            "SELECT timestamp_ms,battery_percent,battery_status,plug_type,charge_counter_uah,screen_on,foreground_package FROM samples WHERE timestamp_ms>=? ORDER BY timestamp_ms",
            arrayOf(from.toString())
        ).use { c ->
            while (c.moveToNext()) rows += Row(
                c.getLong(0), if (c.isNull(1)) null else c.getInt(1),
                c.getString(2) ?: "UNKNOWN", c.getString(3) ?: "NONE",
                if (c.isNull(4)) null else c.getLong(4), c.getInt(5) == 1, c.getString(6) ?: ""
            )
        }
        return rows
    }

    // BatteryManager status is primary; plug state can lag a connection transition.
    private fun gapReport(rows: List<Row>): String {
        var gaps = 0
        var missingMs = 0L
        var longestMs = 0L
        var intervals = 0L
        var intervalTotal = 0L
        for (i in 1 until rows.size) {
            val dt = rows[i].time - rows[i - 1].time
            if (dt <= 0L) continue
            intervals++
            intervalTotal += dt
            if (dt > BatteryRecorderService.SAMPLE_INTERVAL_MS * 3) {
                gaps++
                missingMs += (dt - BatteryRecorderService.SAMPLE_INTERVAL_MS).coerceAtLeast(0L)
                if (dt > longestMs) longestMs = dt
            }
        }
        val average = if (intervals > 0) (intervalTotal / intervals).toString() + " ms" else "n/a"
        return gaps.toString() + " gaps >90s • uncovered estimate " + durationText(missingMs) +
            " • longest gap " + durationText(longestMs) + " • average interval " + average +
            " (gap cause cannot be inferred from timestamps alone)"
    }

    private fun charging(row: Row) = row.state == "CHARGING"
    private fun discharging(row: Row) = row.state == "DISCHARGING"

    private fun add(bucket: Bucket, duration: Long, pct: Double, counter: Long) {
        bucket.ms += duration
        bucket.pct += pct
        bucket.chargeCounterUah += counter
    }

    private fun summarise(rows: List<Row>): Stats {
        val charge = Bucket(); val drain = Bucket()
        val chargeOn = Bucket(); val chargeOff = Bucket()
        val drainOn = Bucket(); val drainOff = Bucket()
        val appTime = linkedMapOf<String, Long>()
        val appCharge = linkedMapOf<String, Double>()
        val appDrain = linkedMapOf<String, Double>()
        var screenOn = 0; var screenOff = 0
        rows.forEach { if (it.screen) screenOn++ else screenOff++ }
        for (i in 1 until rows.size) {
            val before = rows[i - 1]; val after = rows[i]
            val dt = after.time - before.time
            if (dt !in 1_000L..(BatteryRecorderService.SAMPLE_INTERVAL_MS * 3)) continue
            if (after.screen && after.app.isNotBlank() && after.app == before.app)
                appTime[after.app] = (appTime[after.app] ?: 0L) + dt
            if (before.pct == null || after.pct == null) continue
            val delta = (after.pct - before.pct).toDouble()
            val counterDelta = if (before.chargeCounter != null && after.chargeCounter != null) after.chargeCounter - before.chargeCounter else 0L
            if (charging(before) && charging(after) && delta >= 0.0) {
                add(charge, dt, delta, counterDelta.coerceAtLeast(0L))
                add(if (after.screen) chargeOn else chargeOff, dt, delta, counterDelta.coerceAtLeast(0L))
                if (after.screen && after.app.isNotBlank() && after.app == before.app)
                    appCharge[after.app] = (appCharge[after.app] ?: 0.0) + delta
            } else if (discharging(before) && discharging(after) && delta <= 0.0) {
                add(drain, dt, -delta, (-counterDelta).coerceAtLeast(0L))
                add(if (after.screen) drainOn else drainOff, dt, -delta, (-counterDelta).coerceAtLeast(0L))
                if (after.screen && after.app.isNotBlank() && after.app == before.app)
                    appDrain[after.app] = (appDrain[after.app] ?: 0.0) - delta
            }
        }
        fun h(b: Bucket) = b.ms / 3_600_000.0
        fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)
        fun hr(b: Bucket) = fmt(h(b))
        fun rate(b: Bucket) = if (b.ms > 0) fmt(b.pct / h(b)) else "n/a"
        return Stats(
            rows.size, hr(charge), hr(drain), fmt(charge.pct), fmt(drain.pct), rate(charge), rate(drain),
            fmt(charge.chargeCounterUah / 1000.0), fmt(drain.chargeCounterUah / 1000.0),
            hr(chargeOn), fmt(chargeOn.pct), rate(chargeOn), hr(chargeOff), fmt(chargeOff.pct), rate(chargeOff),
            hr(drainOn), fmt(drainOn.pct), rate(drainOn), hr(drainOff), fmt(drainOff.pct), rate(drainOff),
            screenOn, screenOff,
            appTime.entries.sortedByDescending { it.value }.map { AppMetric(it.key, it.value, appCharge[it.key] ?: 0.0, appDrain[it.key] ?: 0.0) }
        )
    }

    private fun sessionHistoryReport(rows: List<Row>, wantCharge: Boolean, limit: Int): String {
        if (rows.isEmpty()) return "No samples recorded."
        fun matches(row: Row) = if (wantCharge) charging(row) else discharging(row)
        val sessions = mutableListOf<List<Row>>()
        var current = mutableListOf<Row>()
        for (row in rows) {
            if (matches(row)) {
                if (current.isNotEmpty()) {
                    val gap = row.time - current.last().time
                    if (gap !in 1_000L..(BatteryRecorderService.SAMPLE_INTERVAL_MS * 3)) {
                        sessions += current
                        current = mutableListOf()
                    }
                }
                current += row
            } else if (current.isNotEmpty()) {
                sessions += current
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) sessions += current
        if (sessions.isEmpty()) return "No recorded sessions."
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return sessions.takeLast(limit).asReversed().joinToString("\n") { run ->
            val first = run.first()
            val last = run.last()
            val start = formatter.format(Date(first.time))
            if (run.size < 2 || first.pct == null || last.pct == null) {
                start + " • one sample only; elapsed time and rate unavailable"
            } else {
                val elapsed = (last.time - first.time).coerceAtLeast(0L)
                val delta = last.pct - first.pct
                val rate = if (elapsed > 0L) kotlin.math.abs(delta.toDouble()) / (elapsed / 3_600_000.0) else 0.0
                val onMs = run.zipWithNext().sumOf { (a, b) ->
                    val dt = b.time - a.time
                    if (dt in 1_000L..(BatteryRecorderService.SAMPLE_INTERVAL_MS * 3) && b.screen) dt else 0L
                }
                val counter = if (first.chargeCounter != null && last.chargeCounter != null)
                    (last.chargeCounter - first.chargeCounter) / 1000.0 else null
                val state = first.pct.toString() + "%→" + last.pct + "%"
                val counterText = if (counter == null) "mAh n/a" else String.format(Locale.US, "%+.1f mAh", counter)
                start + " • " + state + " • " + durationText(elapsed) +
                    " • " + String.format(Locale.US, "%.2f pp/h", rate) +
                    " • screen ON " + durationText(onMs) +
                    " / OFF " + durationText(elapsed - onMs) +
                    " • " + counterText +
                    if (wantCharge && last.state == "FULL") " • full" else ""
            }
        }
    }

    private fun sessionReport(rows: List<Row>, wantCharge: Boolean): String {
        if (rows.isEmpty()) return "No samples recorded."
        fun matches(row: Row) = if (wantCharge) charging(row) else discharging(row)
        var end = rows.lastIndex
        while (end >= 0 && !matches(rows[end])) end--
        if (end < 0) return "No recorded session."
        var start = end
        while (start > 0) {
            val gap = rows[start].time - rows[start - 1].time
            if (!matches(rows[start - 1]) || gap !in 1_000L..(BatteryRecorderService.SAMPLE_INTERVAL_MS * 3)) break
            start--
        }
        val run = rows.subList(start, end + 1)
        if (run.size < 2) return "Session has only one sample; rate and duration are not yet measurable."
        val first = run.first(); val last = run.last()
        val initial = first.pct ?: return "Battery percentage unavailable."
        val current = last.pct ?: return "Battery percentage unavailable."
        val elapsed = last.time - first.time
        if (elapsed <= 0L) return "Session duration unavailable."
        val change = current - initial
        val rate = kotlin.math.abs(change.toDouble()) / (elapsed / 3_600_000.0)
        val counterDelta = if (first.chargeCounter != null && last.chargeCounter != null) last.chargeCounter - first.chargeCounter else null
        val onMs = run.zipWithNext().sumOf { (a, b) ->
            val dt = b.time - a.time
            if (dt in 1_000L..(BatteryRecorderService.SAMPLE_INTERVAL_MS * 3) && b.screen) dt else 0L
        }
        val offMs = elapsed - onMs
        val out = StringBuilder()
        out.appendLine("Observed window: " + first.time + " → " + last.time)
        out.appendLine("Level: " + initial + "% → " + current + "% • elapsed: " + durationText(elapsed))
        out.appendLine("Average rate: " + String.format(Locale.US, "%.2f", rate) + " percentage points/hour")
        if (counterDelta != null) out.appendLine("Charge-counter delta: " + String.format(Locale.US, "%+.1f mAh", counterDelta / 1000.0) +
            " • " + String.format(Locale.US, "%.1f mAh/hour", kotlin.math.abs(counterDelta) / 1000.0 / (elapsed / 3_600_000.0)) + " (Android estimate)")
        else out.appendLine("Charge-counter rate: unavailable")
        out.appendLine("Screen ON: " + durationText(onMs) + " • screen OFF: " + durationText(offMs))
        val thresholds = if (wantCharge) listOf(50, 80, 90, 100) else listOf(80, 50, 20, 10, 0)
        out.appendLine(if (wantCharge) "Time from first recorded sample in this session to charge thresholds:" else "Time from first recorded sample in this session to discharge thresholds:")
        for (threshold in thresholds) {
            val crossing = thresholdTime(run, threshold, wantCharge)
            val status = when {
                (wantCharge && initial >= threshold) || (!wantCharge && initial <= threshold) -> "already at threshold at session start"
                crossing != null -> durationText(crossing - first.time) + " observed"
                rate > 0.0 && ((wantCharge && current < threshold) || (!wantCharge && current > threshold)) ->
                    durationText(((kotlin.math.abs(threshold - current).toDouble() / rate) * 3_600_000.0).toLong()) + " estimated from current session average"
                else -> "not reached / insufficient data"
            }
            out.appendLine("  " + threshold + "%: " + status)
        }
        if (wantCharge && last.state == "FULL") out.appendLine("Android full state observed at final sample.")
        return out.toString().trim()
    }

    private fun thresholdTime(rows: List<Row>, threshold: Int, upward: Boolean): Long? {
        for (i in 1 until rows.size) {
            val before = rows[i - 1].pct ?: continue
            val after = rows[i].pct ?: continue
            if (upward && before < threshold && after >= threshold) return rows[i].time
            if (!upward && before > threshold && after <= threshold) return rows[i].time
        }
        return null
    }

    private fun durationText(ms: Long): String {
        val minutes = ms.coerceAtLeast(0L) / 60_000L
        val days = minutes / (24 * 60)
        val hours = (minutes / 60) % 24
        val remaining = minutes % 60
        return if (days > 0) days.toString() + "d " + hours + "h " + remaining + "m"
        else if (hours > 0) hours.toString() + "h " + remaining + "m" else remaining.toString() + "m"
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
