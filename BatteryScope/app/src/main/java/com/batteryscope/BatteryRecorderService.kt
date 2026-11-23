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
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** User-started, persistent local recorder. No activity lifecycle owns this sampler. */
class BatteryRecorderService : Service() {
    private lateinit var handler: Handler
    private lateinit var worker: HandlerThread
    private lateinit var db: RecorderDatabase
    private val databaseLock = Any()
    private var databaseAvailable = true
    private lateinit var batteryManager: BatteryManager
    @Volatile private var batteryIntent: Intent? = null
    private var lastUsageQueryMs = 0L
    private var foregroundPackage: String? = null
    private var lastSampleState: String? = null
    private var lastSamplePlug: String? = null
    private var lastBatteryPercentStatus: String? = null
    private val lastBatteryPropertyStatus = mutableMapOf<Int, String>()
    private var lastSysfsReadMs = 0L
    private var lastSysfsJson = JSONObject()
    private var receiverRegistered = false
    @Volatile private var recorderRunning = false
    private var samplingWakeLock: PowerManager.WakeLock? = null

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
            finally { if (recorderRunning) handler.postDelayed(this, SAMPLE_INTERVAL_MS) }
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
        if (intent?.action == ACTION_STOP) {
            stopRecorder()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RECONFIGURE) {
            if (recorderRunning) {
                val keepAwake = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_KEEP_CPU_AWAKE, true)
                if (keepAwake) acquireSamplingWakeLock() else releaseSamplingWakeLock()
                startAsForeground()
                event("SAMPLING_MODE_CHANGED", if (keepAwake) "Continuous sampling: partial CPU wake lock enabled" else "Low-impact sampling: partial CPU wake lock released; screen-off gaps may occur")
            }
            return START_STICKY
        }
        try {
            startAsForeground()
            recorderRunning = true
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_ACTIVE, true).putBoolean(KEY_USER_ENABLED, true)
                .remove(KEY_BOOT_RESUME_FAILURE).remove(KEY_BOOT_RESUME_FAILURE_AT).apply()
            val keepAwake = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_KEEP_CPU_AWAKE, true)
            if (keepAwake) acquireSamplingWakeLock() else releaseSamplingWakeLock()
            event(if (intent == null) "RECORDER_RESTARTED" else "RECORDER_STARTED",
                "Foreground recorder; 30-second target sampling; partial CPU wake lock=" + keepAwake + " (enabled mode adds app power use)")
            handler.removeCallbacks(sampler)
            handler.post(sampler)
        } catch (t: Throwable) {
            recorderRunning = false
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
            .setContentText(if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_KEEP_CPU_AWAKE, true))
                "30-second sampling • CPU wake lock ON • adds app drain"
            else "30-second target • CPU wake lock OFF • screen-off gaps possible")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
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
        val bootCount = try { Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 } } catch (_: Throwable) { null }
        val intent = batteryIntent ?: registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)).also { batteryIntent = it }
        val level = intent?.extras?.let {
            if (it.containsKey(BatteryManager.EXTRA_LEVEL)) it.getInt(BatteryManager.EXTRA_LEVEL, -1) else -1
        } ?: -1
        val scale = intent?.extras?.let {
            if (it.containsKey(BatteryManager.EXTRA_SCALE)) it.getInt(BatteryManager.EXTRA_SCALE, -1) else -1
        } ?: -1
        val percent = BatteryPercentMath.percent(level, scale)
        val percentStatus = when {
            intent == null -> "ACTION_BATTERY_CHANGED_UNAVAILABLE"
            !intent.hasExtra(BatteryManager.EXTRA_LEVEL) -> "EXTRA_LEVEL_MISSING"
            !intent.hasExtra(BatteryManager.EXTRA_SCALE) -> "EXTRA_SCALE_MISSING"
            level < 0 -> "EXTRA_LEVEL_INVALID"
            scale <= 0 -> "EXTRA_SCALE_NON_POSITIVE"
            level > scale -> "EXTRA_LEVEL_EXCEEDS_SCALE"
            else -> "AVAILABLE"
        }
        if (lastBatteryPercentStatus != percentStatus) {
            event("BATTERY_PERCENT_METADATA", percentStatus)
            lastBatteryPercentStatus = percentStatus
        }
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
        }
        val usb = findUsbCandidate(lastSysfsJson)
        val row = ContentValues().apply {
            put("timestamp_ms", now)
            put("elapsed_realtime_ms", elapsed)
            if (bootCount != null) put("boot_count", bootCount)
            if (percent != null) put("battery_percent", percent)
            put("battery_status", status)
            put("plug_type", plug)
            if (voltage > 0) put("battery_voltage_mv", voltage)
            val maxChargingCurrent = intent?.getIntExtra("max_charging_current", -1) ?: -1
            val maxChargingVoltage = intent?.getIntExtra("max_charging_voltage", -1) ?: -1
            if (maxChargingCurrent > 0) put("max_charging_current_ua", maxChargingCurrent)
            if (maxChargingVoltage > 0) put("max_charging_voltage_uv", maxChargingVoltage)
            val present = intent?.extras?.getBoolean("present")
            if (present != null) put("battery_present", if (present) 1 else 0)
            val invalidCharger = intent?.extras?.let { if (it.containsKey("invalid_charger")) it.getInt("invalid_charger") else null }
            if (invalidCharger != null) put("invalid_charger_state", invalidCharger)
            val cycles = intent?.getIntExtra("cycle_count", -1) ?: -1
            if (cycles >= 0) put("battery_cycle_count", cycles)
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
        insertSample(row)
    }

    private fun longProperty(id: Int): Long? {
        val result = try {
            val value = batteryManager.getLongProperty(id)
            if (value == Long.MIN_VALUE || value == Long.MAX_VALUE) {
                null to "ANDROID_UNAVAILABLE_SENTINEL"
            } else {
                value to "AVAILABLE"
            }
        } catch (t: SecurityException) {
            null to "SECURITY_EXCEPTION"
        } catch (t: Throwable) {
            null to "ERROR:" + t.javaClass.simpleName
        }
        val propertyName = when (id) {
            BatteryManager.BATTERY_PROPERTY_CURRENT_NOW -> "CURRENT_NOW"
            BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE -> "CURRENT_AVERAGE"
            BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER -> "CHARGE_COUNTER"
            BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER -> "ENERGY_COUNTER"
            else -> "PROPERTY_$id"
        }
        val previous = lastBatteryPropertyStatus.put(id, result.second)
        if (previous != result.second) {
            event("BATTERY_PROPERTY_ACCESS", propertyName + "=" + result.second)
        }
        return result.first
    }

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
        if (!hasUsageAccess()) {
            if (foregroundPackage != null) event("USAGE_ACCESS_REVOKED", "Usage Access unavailable; clearing previous foreground app attribution")
            foregroundPackage = null
            return null
        }
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
        val maxFields = MAX_SYSFS_FIELDS
        for ((rootPath, maxDepth) in roots) {
            val root = java.io.File(rootPath)
            val prefix = root.name
            if (!root.exists()) {
                result.put(prefix + "/@root", "ABSENT_OR_HIDDEN_BY_SELINUX")
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
        result.put("@field_limit", maxFields)
        result.put("@field_limit_reached", count >= maxFields)
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

    private fun insertSample(values: ContentValues) {
        synchronized(databaseLock) {
            if (!databaseAvailable) return
            try {
                db.insertSample(values)
            } catch (t: Throwable) {
                try { db.insertEvent(System.currentTimeMillis(), "DATABASE_SAMPLE_WRITE_FAILED", t.javaClass.simpleName + ": " + (t.message ?: "unknown")) }
                catch (_: Throwable) { }
            }
        }
    }

    /**
     * Broadcast receivers and Service lifecycle callbacks run on the main thread.
     * Queue their SQLite writes on the recorder worker so storage latency cannot
     * stall Android's main thread. Calls already on the worker stay ordered inline.
     */
    private fun event(type: String, details: String) {
        if (::handler.isInitialized && Looper.myLooper() != worker.looper) {
            handler.post { writeEventOnWorker(type, details) }
        } else {
            writeEventOnWorker(type, details)
        }
    }

    private fun writeEventOnWorker(type: String, details: String) {
        synchronized(databaseLock) {
            if (!databaseAvailable) return
            try {
                db.insertEvent(System.currentTimeMillis(), type, details)
            } catch (_: Throwable) {
                // A failed diagnostic write must never crash the recorder or UI.
            }
        }
    }

    private fun stopRecorder() {
        recorderRunning = false
        handler.removeCallbacks(sampler)
        releaseSamplingWakeLock()
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
        recorderRunning = false
        handler.removeCallbacks(sampler)
        releaseSamplingWakeLock()
        if (receiverRegistered) try { unregisterReceiver(receiver) } catch (_: Throwable) { }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply()
        // Drain already-queued event/sample work before closing SQLite. Closing
        // the database here on the main thread could block while a write is active.
        val closeDatabase = Runnable {
            synchronized(databaseLock) {
                databaseAvailable = false
                try { db.close() } catch (_: Throwable) { }
            }
            try { worker.quitSafely() } catch (_: Throwable) { }
        }
        if (!::handler.isInitialized || !handler.post(closeDatabase)) {
            synchronized(databaseLock) {
                databaseAvailable = false
                try { db.close() } catch (_: Throwable) { }
            }
            try { worker.quitSafely() } catch (_: Throwable) { }
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.batteryscope.action.START_RECORDER"
        const val ACTION_STOP = "com.batteryscope.action.STOP_RECORDER"
        const val PREFS = "batteryscope_recorder"
        const val KEY_ACTIVE = "recorder_active"
        const val KEY_USER_ENABLED = "recorder_user_enabled"
        const val KEY_KEEP_CPU_AWAKE = "keep_cpu_awake"
        const val KEY_BOOT_RESUME_FAILURE = "boot_resume_failure"
        const val KEY_BOOT_RESUME_FAILURE_AT = "boot_resume_failure_at"
        const val ACTION_RECONFIGURE = "com.batteryscope.action.RECONFIGURE_RECORDER"
        const val SAMPLE_INTERVAL_MS = 30_000L
        private const val SYSFS_INTERVAL_MS = 120_000L
        private const val MAX_SYSFS_FIELDS = 900
        private const val CHANNEL_ID = "batteryscope_recorder"
        private const val NOTIFICATION_ID = 301
    }
}

/** SQLite keeps raw samples/events until the user deletes app data. */
class RecorderDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "batteryscope_history.db", null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE samples (
            timestamp_ms INTEGER PRIMARY KEY, elapsed_realtime_ms INTEGER NOT NULL, boot_count INTEGER,
            battery_percent INTEGER, battery_status TEXT NOT NULL, plug_type TEXT NOT NULL,
            battery_voltage_mv INTEGER, battery_current_ua INTEGER, battery_average_current_ua INTEGER,
            max_charging_current_ua INTEGER, max_charging_voltage_uv INTEGER, battery_present INTEGER, invalid_charger_state INTEGER, battery_cycle_count INTEGER,
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
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE samples ADD COLUMN boot_count INTEGER")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE samples ADD COLUMN max_charging_current_ua INTEGER")
            db.execSQL("ALTER TABLE samples ADD COLUMN max_charging_voltage_uv INTEGER")
            db.execSQL("ALTER TABLE samples ADD COLUMN battery_present INTEGER")
            db.execSQL("ALTER TABLE samples ADD COLUMN battery_cycle_count INTEGER")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE samples ADD COLUMN invalid_charger_state INTEGER")
        }
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


    fun capacityEstimateReport(nowMs: Long = System.currentTimeMillis()): String {
        val samples = mutableListOf<CapacitySample>()
        val from = nowMs - 90L * 24L * 60L * 60L * 1000L
        readableDatabase.rawQuery(
            """SELECT timestamp_ms,boot_count,battery_percent,battery_status,plug_type,charge_counter_uah
               FROM samples WHERE timestamp_ms>=? ORDER BY timestamp_ms""".trimIndent(),
            arrayOf(from.toString())
        ).use { cursor ->
            val time = cursor.getColumnIndexOrThrow("timestamp_ms")
            val boot = cursor.getColumnIndexOrThrow("boot_count")
            val percent = cursor.getColumnIndexOrThrow("battery_percent")
            val status = cursor.getColumnIndexOrThrow("battery_status")
            val plug = cursor.getColumnIndexOrThrow("plug_type")
            val counter = cursor.getColumnIndexOrThrow("charge_counter_uah")
            while (cursor.moveToNext()) {
                samples += CapacitySample(
                    timestampMs = cursor.getLong(time),
                    bootCount = if (cursor.isNull(boot)) null else cursor.getInt(boot),
                    percent = if (cursor.isNull(percent)) null else cursor.getInt(percent),
                    status = cursor.getString(status) ?: "UNKNOWN",
                    plug = cursor.getString(plug) ?: "NONE",
                    chargeCounterUah = if (cursor.isNull(counter)) null else cursor.getLong(counter)
                )
            }
        }
        val empiricalReport = BatteryCapacityEstimator.report(samples)
        val latestHardwareJson = readableDatabase.rawQuery(
            "SELECT power_supply_json FROM samples WHERE power_supply_json IS NOT NULL ORDER BY timestamp_ms DESC LIMIT 1",
            null
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        val kernelReport = buildString {
            appendLine("KERNEL FUEL-GAUGE CAPACITY / CYCLE REPORT")
            if (latestHardwareJson == null) {
                appendLine("No hardware-tree sample recorded yet. Start the recorder and wait for its first 120-second hardware inventory.")
            } else {
                val json = try { JSONObject(latestHardwareJson) } catch (_: Throwable) { JSONObject() }
                fun findField(name: String): Pair<String, String>? {
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key.startsWith("power_supply/") && key.endsWith("/" + name)) {
                            return key to json.optString(key)
                        }
                    }
                    return null
                }
                val full = findField("charge_full")
                val design = findField("charge_full_design")
                val cycles = findField("cycle_count")
                val fullUah = full?.second?.toLongOrNull()?.takeIf { it > 0L }
                val designUah = design?.second?.toLongOrNull()?.takeIf { it > 0L }
                if (fullUah != null) appendLine("Kernel full-charge capacity: ${String.format(Locale.US, "%.1f mAh", fullUah / 1000.0)} (${full!!.first})")
                else appendLine("Kernel full-charge capacity: unavailable — " + kernelFieldReason(full, "charge_full", json))
                if (designUah != null) appendLine("Kernel design capacity: ${String.format(Locale.US, "%.1f mAh", designUah / 1000.0)} (${design!!.first})")
                else appendLine("Kernel design capacity: unavailable — " + kernelFieldReason(design, "charge_full_design", json))
                if (fullUah != null && designUah != null) {
                    appendLine("Kernel gauge-reported health: ${String.format(Locale.US, "%.1f%%", fullUah.toDouble() / designUah.toDouble() * 100.0)}")
                } else {
                    appendLine("Kernel gauge-reported health: unavailable — both charge_full and charge_full_design are required")
                }
                val cycleValue = cycles?.second?.toLongOrNull()
                if (cycleValue != null) appendLine("Kernel charge cycle count: $cycleValue (${cycles!!.first})")
                else appendLine("Kernel charge cycle count: unavailable — " + kernelFieldReason(cycles, "cycle_count", json))
            }
            append("Kernel gauge values are vendor/driver exports; verify units and meaning on the RMX2161 before treating them as calibrated capacity.")
        }
        return empiricalReport + "\n\n" + kernelReport
    }

    private fun kernelFieldReason(
        field: Pair<String, String>?,
        name: String,
        json: JSONObject
    ): String {
        if (field != null) return when {
            field.second == "READ_DENIED" -> "attribute exists but ordinary app read was denied"
            field.second.startsWith("ERROR:") -> field.second
            else -> "attribute value is not numeric: " + field.second
        }
        val root = json.optString("power_supply/@root")
        return when (root) {
            "READ_DENIED" -> "power_supply root is present but access was denied"
            "ABSENT_OR_HIDDEN_BY_SELINUX" -> "power_supply root is absent or hidden by SELinux"
            else -> "driver does not export " + name + ", or the bounded inventory did not reach it"
        }
    }

    fun historySummary(now: Long = System.currentTimeMillis()): String {
        val windows = listOf(
            "24 HOURS" to 24L * 60 * 60 * 1000,
            "7 DAYS" to 7L * 24 * 60 * 60 * 1000,
            "30 DAYS" to 30L * 24 * 60 * 60 * 1000,
            "90 DAYS" to 90L * 24 * 60 * 60 * 1000,
            "365 DAYS" to 365L * 24 * 60 * 60 * 1000,
            "ALL TIME" to Long.MAX_VALUE
        )
        // Only recent session detail is materialised; rolling statistics stream directly from SQLite.
        val recentRows = readWindow(now - 30L * 24 * 60 * 60 * 1000)
        val latestPercent = readableDatabase.rawQuery(
            "SELECT battery_percent FROM samples WHERE battery_percent IS NOT NULL ORDER BY timestamp_ms DESC LIMIT 1", null
        ).use { if (it.moveToFirst()) it.getInt(0) else null }
        return buildString {
            val total = readableDatabase.rawQuery("SELECT COUNT(*) FROM samples", null).use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            }
            appendLine("BATTERYSCOPE LOCAL HISTORY • RAW SAMPLES RETAINED UNTIL USER DELETES APP DATA")
            appendLine("Stored samples: " + total + " • target sample interval: 30 seconds")
            appendLine("App foreground correlation is not a direct per-app electrical measurement.")
            for ((label, duration) in windows) {
                val from = if (duration == Long.MAX_VALUE) 0L else now - duration
                val stats = summariseSince(from)
                appendLine()
                appendLine("=== " + label + " • " + stats.count + " samples ===")
                appendLine("Sampling continuity: " + gapReportSince(from))
                appendLine("Charging: " + stats.chargeHours + " h • +" + stats.chargedPct + " percentage points • " + stats.chargeRate + " %/h")
                appendLine("Discharging: " + stats.dischargeHours + " h • -" + stats.dischargedPct + " percentage points • " + stats.dischargeRate + " %/h")
                appendLine("Charge-counter gain/loss: +" + stats.chargeGainMah + " / -" + stats.chargeLossMah + " mAh (Android estimate)")
                appendLine("Average battery-side charge rate from counter: " + stats.chargeCounterRateMa + " mA • discharge rate: " + stats.dischargeCounterRateMa + " mA (not USB input current)")
                appendLine("Charging screen ON: " + stats.chargeOnHours + " h • +" + stats.chargeOnPct + "% • " + stats.chargeOnRate + " %/h")
                appendLine("Charging screen OFF: " + stats.chargeOffHours + " h • +" + stats.chargeOffPct + "% • " + stats.chargeOffRate + " %/h")
                appendLine("Charging rate comparison: screen ON " + stats.chargeOnRate + " %/h vs screen OFF " + stats.chargeOffRate + " %/h (historical averages)")
                appendLine("Discharging screen ON: " + stats.drainOnHours + " h • -" + stats.drainOnPct + "% • " + stats.drainOnRate + " %/h")
                appendLine("Discharging screen OFF: " + stats.drainOffHours + " h • -" + stats.drainOffPct + "% • " + stats.drainOffRate + " %/h")
                appendLine("Discharge rate comparison: screen ON " + stats.drainOnRate + " %/h vs screen OFF " + stats.drainOffRate + " %/h (historical averages)")
                if (latestPercent != null) {
                    appendLine("From current " + latestPercent + "%, estimated time to 100% at screen-ON charging rate: " +
                        forecastDuration(100.0 - latestPercent, stats.chargeOnRate) + " • screen-OFF rate: " +
                        forecastDuration(100.0 - latestPercent, stats.chargeOffRate))
                    appendLine("From current " + latestPercent + "%, estimated time to 0% at screen-ON discharge rate: " +
                        forecastDuration(latestPercent.toDouble(), stats.drainOnRate) + " • screen-OFF rate: " +
                        forecastDuration(latestPercent.toDouble(), stats.drainOffRate))
                }
                appendLine("Battery decline while Android reports CHARGING or both samples show a connected source: " + stats.pluggedDrainHours + " h • -" + stats.pluggedDrainPct + "% • " + stats.pluggedDrainRate + " %/h")
                appendLine("  Plugged decline screen ON: " + stats.pluggedDrainOnHours + " h • -" + stats.pluggedDrainOnPct + "% • " + stats.pluggedDrainOnRate + " %/h")
                appendLine("  Plugged decline screen OFF: " + stats.pluggedDrainOffHours + " h • -" + stats.pluggedDrainOffPct + "% • " + stats.pluggedDrainOffRate + " %/h")
                appendLine("Screen state samples: ON " + stats.screenOn + " • OFF " + stats.screenOff)
                appendLine("Foreground app time + coincident battery change (approx.):")
                if (stats.apps.isEmpty()) appendLine("  unavailable / Usage Access not granted / not yet recorded")
                else stats.apps.take(8).forEach { app ->
                    appendLine("  " + app.name + ": " + app.minutes + " min foreground • +" + app.chargePct + "% charging (counter " + app.counter(app.chargeCounterMah) + ") • -" + app.drainPct + "% unplugged (counter " + app.counter(app.drainCounterMah) + ") • -" + app.pluggedDrainPct + "% connected (counter " + app.counter(app.pluggedDrainCounterMah) + ")")
                }
            }
            appendLine()
            appendLine("=== MOST RECENT CHARGING SESSION (LAST 30 DAYS) ===")
            appendLine(sessionReport(recentRows, true))
            appendLine()
            appendLine("=== MOST RECENT DISCHARGING SESSION (LAST 30 DAYS) ===")
            appendLine(sessionReport(recentRows, false))
            appendLine()
            appendLine("=== RECENT CHARGING SESSIONS (LAST 30 DAYS) ===")
            appendLine(sessionHistoryReport(recentRows, true, 8))
            appendLine()
            appendLine("=== RECENT DISCHARGING SESSIONS (LAST 30 DAYS) ===")
            appendLine(sessionHistoryReport(recentRows, false, 8))
            appendLine()
            appendLine("Rate intervals longer than 90 seconds are excluded. Threshold times are resolved to the sample interval, not exact hardware event times.")
            val last = readableDatabase.rawQuery(
                "SELECT timestamp_ms,battery_percent,battery_status,plug_type,foreground_package FROM samples ORDER BY timestamp_ms DESC LIMIT 1", null
            ).use { cursor ->
                if (!cursor.moveToFirst()) "No sample recorded yet"
                else "Last sample: " + cursor.getLong(0) + " • " +
                    (if (cursor.isNull(1)) "--" else cursor.getInt(1).toString() + "%") + " • " +
                    cursor.getString(2) + " • " + cursor.getString(3) + " • app=" + cursor.getString(4)
            }
            appendLine(last)
        }
    }

    private data class Row(
        val time: Long, val elapsed: Long, val pct: Int?, val state: String, val plug: String,
        val chargeCounter: Long?, val screen: Boolean, val app: String, val bootCount: Int?
    )
    private data class AppMetric(
        val name: String,
        val ms: Long,
        val charge: Double,
        val drain: Double,
        val pluggedDrain: Double,
        val chargeCounterMah: Double?,
        val drainCounterMah: Double?,
        val pluggedDrainCounterMah: Double?
    ) {
        val minutes: Long get() = ms / 60_000L
        val chargePct: String get() = String.format(Locale.US, "%.1f", charge)
        val drainPct: String get() = String.format(Locale.US, "%.1f", drain)
        val pluggedDrainPct: String get() = String.format(Locale.US, "%.1f", pluggedDrain)
        fun counter(value: Double?): String = value?.let { String.format(Locale.US, "%.1f mAh", it) } ?: "n/a"
    }
    private data class Bucket(var ms: Long = 0L, var pct: Double = 0.0, var chargeCounterUah: Long = 0L, var counterIntervals: Int = 0)
    private data class Stats(
        val count: Int, val chargeHours: String, val dischargeHours: String,
        val chargedPct: String, val dischargedPct: String, val chargeRate: String, val dischargeRate: String,
        val chargeGainMah: String, val chargeLossMah: String,
        val chargeOnHours: String, val chargeOnPct: String, val chargeOnRate: String,
        val chargeOffHours: String, val chargeOffPct: String, val chargeOffRate: String,
        val drainOnHours: String, val drainOnPct: String, val drainOnRate: String,
        val drainOffHours: String, val drainOffPct: String, val drainOffRate: String,
        val pluggedDrainHours: String, val pluggedDrainPct: String, val pluggedDrainRate: String,
        val pluggedDrainOnHours: String, val pluggedDrainOnPct: String, val pluggedDrainOnRate: String,
        val pluggedDrainOffHours: String, val pluggedDrainOffPct: String, val pluggedDrainOffRate: String,
        val screenOn: Int, val screenOff: Int, val apps: List<AppMetric>,
        val chargeCounterRateMa: String, val dischargeCounterRateMa: String
    )

    private fun readWindow(from: Long): List<Row> {
        val rows = mutableListOf<Row>()
        readableDatabase.rawQuery(
            "SELECT timestamp_ms,elapsed_realtime_ms,battery_percent,battery_status,plug_type,charge_counter_uah,screen_on,foreground_package,boot_count FROM samples WHERE timestamp_ms>=? ORDER BY timestamp_ms",
            arrayOf(from.toString())
        ).use { c ->
            while (c.moveToNext()) rows += Row(
                c.getLong(0), c.getLong(1), if (c.isNull(2)) null else c.getInt(2),
                c.getString(3) ?: "UNKNOWN", c.getString(4) ?: "NONE",
                if (c.isNull(5)) null else c.getLong(5), c.getInt(6) == 1, c.getString(7) ?: "",
                if (c.isNull(8)) null else c.getInt(8)
            )
        }
        return rows
    }

    // BatteryManager status is primary; plug state can lag a connection transition.
    private fun gapReportSince(from: Long): String {
        var gaps = 0
        var missingMs = 0L
        var longestMs = 0L
        var intervals = 0L
        var intervalTotal = 0L
        var previous: Row? = null
        readableDatabase.rawQuery(
            "SELECT timestamp_ms,elapsed_realtime_ms,battery_percent,battery_status,plug_type,charge_counter_uah,screen_on,foreground_package,boot_count FROM samples WHERE timestamp_ms>=? ORDER BY timestamp_ms",
            arrayOf(from.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val current = Row(
                    cursor.getLong(0), cursor.getLong(1),
                    if (cursor.isNull(2)) null else cursor.getInt(2),
                    cursor.getString(3) ?: "UNKNOWN", cursor.getString(4) ?: "NONE",
                    if (cursor.isNull(5)) null else cursor.getLong(5),
                    cursor.getInt(6) == 1, cursor.getString(7) ?: "",
                    if (cursor.isNull(8)) null else cursor.getInt(8)
                )
                val before = previous
                if (before != null) {
                    val dt = sampleIntervalMs(before, current)
                    if (dt > 0L) {
                        intervals++
                        intervalTotal += dt
                        if (dt > BatteryRecorderService.SAMPLE_INTERVAL_MS * 3) {
                            gaps++
                            missingMs += (dt - BatteryRecorderService.SAMPLE_INTERVAL_MS).coerceAtLeast(0L)
                            if (dt > longestMs) longestMs = dt
                        }
                    }
                }
                previous = current
            }
        }
        val average = if (intervals > 0L) (intervalTotal / intervals).toString() + " ms" else "n/a"
        return gaps.toString() + " gaps >90s • uncovered estimate " + durationText(missingMs) +
            " • longest gap " + durationText(longestMs) + " • average interval " + average +
            " (uses monotonic time when samples are within the same boot; wall time across reboot; exact gap cause is unknown)"
    }

    private fun sampleIntervalMs(before: Row, after: Row): Long =
        RecorderTimeMath.intervalMs(
            before.time, before.elapsed, before.bootCount,
            after.time, after.elapsed, after.bootCount
        )

    private fun observedDurationMs(rows: List<Row>): Long {
        var total = 0L
        for (i in 1 until rows.size) {
            val interval = sampleIntervalMs(rows[i - 1], rows[i])
            if (interval > 0L) total += interval
        }
        return total
    }

    private fun charging(row: Row) = row.state == "CHARGING"
    private fun discharging(row: Row) = row.state == "DISCHARGING"

    private fun add(bucket: Bucket, duration: Long, pct: Double, counter: Long?) {
        bucket.ms += duration
        bucket.pct += pct
        if (counter != null) {
            bucket.chargeCounterUah += counter
            bucket.counterIntervals++
        }
    }

    private fun summariseSince(from: Long): Stats {
        val charge = Bucket(); val drain = Bucket()
        val chargeOn = Bucket(); val chargeOff = Bucket()
        val drainOn = Bucket(); val drainOff = Bucket()
        val pluggedDrain = Bucket(); val pluggedDrainOn = Bucket(); val pluggedDrainOff = Bucket()
        val appTime = linkedMapOf<String, Long>()
        val appCharge = linkedMapOf<String, Double>()
        val appDrain = linkedMapOf<String, Double>()
        val appPluggedDrain = linkedMapOf<String, Double>()
        val appChargeCounterMah = linkedMapOf<String, Double>()
        val appDrainCounterMah = linkedMapOf<String, Double>()
        val appPluggedDrainCounterMah = linkedMapOf<String, Double>()
        var count = 0
        var screenOn = 0
        var screenOff = 0
        var previous: Row? = null
        readableDatabase.rawQuery(
            """SELECT timestamp_ms,elapsed_realtime_ms,battery_percent,battery_status,plug_type,charge_counter_uah,screen_on,foreground_package,boot_count
               FROM samples WHERE timestamp_ms>=? ORDER BY timestamp_ms""".trimIndent(),
            arrayOf(from.toString())
        ).use { cursor ->
            val iTime = cursor.getColumnIndexOrThrow("timestamp_ms")
            val iElapsed = cursor.getColumnIndexOrThrow("elapsed_realtime_ms")
            val iPct = cursor.getColumnIndexOrThrow("battery_percent")
            val iState = cursor.getColumnIndexOrThrow("battery_status")
            val iPlug = cursor.getColumnIndexOrThrow("plug_type")
            val iCounter = cursor.getColumnIndexOrThrow("charge_counter_uah")
            val iScreen = cursor.getColumnIndexOrThrow("screen_on")
            val iApp = cursor.getColumnIndexOrThrow("foreground_package")
            val iBootCount = cursor.getColumnIndexOrThrow("boot_count")
            while (cursor.moveToNext()) {
                val after = Row(
                    cursor.getLong(iTime),
                    cursor.getLong(iElapsed),
                    if (cursor.isNull(iPct)) null else cursor.getInt(iPct),
                    cursor.getString(iState) ?: "UNKNOWN",
                    cursor.getString(iPlug) ?: "NONE",
                    if (cursor.isNull(iCounter)) null else cursor.getLong(iCounter),
                    cursor.getInt(iScreen) == 1,
                    cursor.getString(iApp) ?: "",
                    if (cursor.isNull(iBootCount)) null else cursor.getInt(iBootCount)
                )
                count++
                if (after.screen) screenOn++ else screenOff++
                val before = previous
                if (before != null) {
                    val dt = sampleIntervalMs(before, after)
                    if (dt in 1_000L..(BatteryRecorderService.SAMPLE_INTERVAL_MS * 3)) {
                        val sameApp = after.screen && after.app.isNotBlank() && after.app == before.app
                        if (sameApp) appTime[after.app] = (appTime[after.app] ?: 0L) + dt
                        if (before.pct != null && after.pct != null) {
                            val delta = (after.pct - before.pct).toDouble()
                            val counterDelta = if (before.chargeCounter != null && after.chargeCounter != null)
                                after.chargeCounter - before.chargeCounter else null
                            val counterGain = counterDelta?.takeIf { it > 0L }
                            val counterLoss = counterDelta?.takeIf { it < 0L }?.let { -it }
                            val bothPlugged = before.plug != "NONE" && after.plug != "NONE"
                            val androidReportsCharging = charging(before) && charging(after)
                            if ((bothPlugged || androidReportsCharging) && delta < 0.0) {
                                // Some ROMs report EXTRA_PLUGGED=NONE while BATTERY_STATUS remains CHARGING.
                                add(pluggedDrain, dt, -delta, counterLoss)
                                add(if (after.screen) pluggedDrainOn else pluggedDrainOff, dt, -delta, counterLoss)
                                if (sameApp) {
                                    appPluggedDrain[after.app] = (appPluggedDrain[after.app] ?: 0.0) - delta
                                    if (counterLoss != null) appPluggedDrainCounterMah[after.app] =
                                        (appPluggedDrainCounterMah[after.app] ?: 0.0) + counterLoss / 1000.0
                                }
                            } else if (charging(before) && charging(after) && delta >= 0.0) {
                                add(charge, dt, delta, counterGain)
                                add(if (after.screen) chargeOn else chargeOff, dt, delta, counterGain)
                                if (sameApp) {
                                    appCharge[after.app] = (appCharge[after.app] ?: 0.0) + delta
                                    if (counterGain != null) appChargeCounterMah[after.app] =
                                        (appChargeCounterMah[after.app] ?: 0.0) + counterGain / 1000.0
                                }
                            } else if (discharging(before) && discharging(after) && !bothPlugged && delta <= 0.0) {
                                add(drain, dt, -delta, counterLoss)
                                add(if (after.screen) drainOn else drainOff, dt, -delta, counterLoss)
                                if (sameApp) {
                                    appDrain[after.app] = (appDrain[after.app] ?: 0.0) - delta
                                    if (counterLoss != null) appDrainCounterMah[after.app] =
                                        (appDrainCounterMah[after.app] ?: 0.0) + counterLoss / 1000.0
                                }
                            }
                        }
                    }
                }
                previous = after
            }
        }
        fun hours(bucket: Bucket) = bucket.ms / 3_600_000.0
        fun fmt(value: Double) = String.format(Locale.US, "%.2f", value)
        fun duration(bucket: Bucket) = fmt(hours(bucket))
        fun rate(bucket: Bucket) = if (bucket.ms > 0L) fmt(bucket.pct / hours(bucket)) else "n/a"
        return Stats(
            count, duration(charge), duration(drain), fmt(charge.pct), fmt(drain.pct), rate(charge), rate(drain),
            if (charge.counterIntervals > 0) fmt(charge.chargeCounterUah / 1000.0) else "n/a", if (drain.counterIntervals > 0) fmt(drain.chargeCounterUah / 1000.0) else "n/a",
            duration(chargeOn), fmt(chargeOn.pct), rate(chargeOn), duration(chargeOff), fmt(chargeOff.pct), rate(chargeOff),
            duration(drainOn), fmt(drainOn.pct), rate(drainOn), duration(drainOff), fmt(drainOff.pct), rate(drainOff),
            duration(pluggedDrain), fmt(pluggedDrain.pct), rate(pluggedDrain),
            duration(pluggedDrainOn), fmt(pluggedDrainOn.pct), rate(pluggedDrainOn),
            duration(pluggedDrainOff), fmt(pluggedDrainOff.pct), rate(pluggedDrainOff),
            screenOn, screenOff,
            appTime.entries.sortedByDescending { it.value }.map {
                AppMetric(
                    it.key, it.value,
                    appCharge[it.key] ?: 0.0,
                    appDrain[it.key] ?: 0.0,
                    appPluggedDrain[it.key] ?: 0.0,
                    appChargeCounterMah[it.key],
                    appDrainCounterMah[it.key],
                    appPluggedDrainCounterMah[it.key]
                )
            },
            if (charge.counterIntervals > 0) BatteryRateMath.counterRateMa(charge.chargeCounterUah, charge.ms)?.let { fmt(it) } ?: "n/a" else "n/a",
            if (drain.counterIntervals > 0) BatteryRateMath.counterRateMa(drain.chargeCounterUah, drain.ms)?.let { fmt(it) } ?: "n/a" else "n/a"
        )
    }

    private fun sessionHistoryReport(rows: List<Row>, wantCharge: Boolean, limit: Int): String {
        if (rows.isEmpty()) return "No samples recorded."
        fun matches(row: Row) = if (wantCharge) charging(row) else discharging(row)
        val sessions = mutableListOf<List<Row>>()
        var current = mutableListOf<Row>()
        for (row in rows) {
            val firstFull = wantCharge && row.state == "FULL" && row.plug != "NONE"
            if (matches(row)) {
                if (current.isNotEmpty()) {
                    val gap = sampleIntervalMs(current.last(), row)
                    if (gap !in 1_000L..(BatteryRecorderService.SAMPLE_INTERVAL_MS * 3)) {
                        sessions += current
                        current = mutableListOf()
                    }
                }
                current += row
            } else if (firstFull) {
                if (current.isNotEmpty()) {
                    val gap = sampleIntervalMs(current.last(), row)
                    if (gap in 1_000L..(BatteryRecorderService.SAMPLE_INTERVAL_MS * 3)) current += row
                    sessions += current
                    current = mutableListOf()
                }
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
                val elapsed = observedDurationMs(run).coerceAtLeast(0L)
                val delta = last.pct - first.pct
                val rate = if (elapsed > 0L) kotlin.math.abs(delta.toDouble()) / (elapsed / 3_600_000.0) else 0.0
                val onMs = run.zipWithNext().sumOf { (a, b) ->
                    val dt = sampleIntervalMs(a, b)
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
        // Include the first FULL sample after a charging run, but not later full/plugged idle time.
        if (wantCharge && end < rows.lastIndex) {
            val next = rows[end + 1]
            val gap = sampleIntervalMs(rows[end], next)
            if (next.state == "FULL" && next.plug != "NONE" &&
                gap in 1_000L..(BatteryRecorderService.SAMPLE_INTERVAL_MS * 3)) end++
        }
        var start = end
        while (start > 0) {
            val gap = sampleIntervalMs(rows[start - 1], rows[start])
            val prev = rows[start - 1]
            val prevMatches = matches(prev) || (wantCharge && prev.state == "FULL" && prev.plug != "NONE")
            if (!prevMatches || gap !in 1_000L..(BatteryRecorderService.SAMPLE_INTERVAL_MS * 3)) break
            start--
        }
        val run = rows.subList(start, end + 1)
        if (run.size < 2) return "Session has only one sample; rate and duration are not yet measurable."
        val first = run.first(); val last = run.last()
        val initial = first.pct ?: return "Battery percentage unavailable."
        val current = last.pct ?: return "Battery percentage unavailable."
        val elapsed = observedDurationMs(run)
        if (elapsed <= 0L) return "Session duration unavailable."
        val change = current - initial
        val directionMatches = if (wantCharge) change > 0 else change < 0
        val rate = if (directionMatches) kotlin.math.abs(change.toDouble()) / (elapsed / 3_600_000.0) else 0.0
        val counterDelta = if (first.chargeCounter != null && last.chargeCounter != null) last.chargeCounter - first.chargeCounter else null
        var onMs = 0L
        var offMs = 0L
        var onSocChange = 0.0
        var offSocChange = 0.0
        var onCounterUah = 0L
        var offCounterUah = 0L
        var onCounterIntervals = 0
        var offCounterIntervals = 0
        for ((before, after) in run.zipWithNext()) {
            val dt = sampleIntervalMs(before, after)
            if (dt !in 1_000L..(BatteryRecorderService.SAMPLE_INTERVAL_MS * 3)) continue
            val screenBucketOn = after.screen
            if (screenBucketOn) onMs += dt else offMs += dt
            val deltaSoc = if (before.pct != null && after.pct != null) after.pct - before.pct else null
            val socDirectionValid = deltaSoc != null && if (wantCharge) deltaSoc >= 0 else deltaSoc <= 0
            if (socDirectionValid) {
                if (screenBucketOn) onSocChange += kotlin.math.abs(deltaSoc!!.toDouble())
                else offSocChange += kotlin.math.abs(deltaSoc!!.toDouble())
            }
            val deltaCounter = if (before.chargeCounter != null && after.chargeCounter != null)
                after.chargeCounter - before.chargeCounter else null
            val counterDirectionValid = deltaCounter != null && if (wantCharge) deltaCounter > 0L else deltaCounter < 0L
            if (counterDirectionValid) {
                if (screenBucketOn) {
                    onCounterUah += deltaCounter!!
                    onCounterIntervals++
                } else {
                    offCounterUah += deltaCounter!!
                    offCounterIntervals++
                }
            }
        }
        val onRate = if (onMs > 0L) onSocChange / (onMs / 3_600_000.0) else null
        val offRate = if (offMs > 0L) offSocChange / (offMs / 3_600_000.0) else null
        val onCounterRate = if (onCounterIntervals > 0) BatteryRateMath.counterRateMa(onCounterUah, onMs) else null
        val offCounterRate = if (offCounterIntervals > 0) BatteryRateMath.counterRateMa(offCounterUah, offMs) else null
        val out = StringBuilder()
        out.appendLine("Observed window: " + first.time + " → " + last.time)
        out.appendLine("Level: " + initial + "% → " + current + "% • elapsed: " + durationText(elapsed))
        out.appendLine("Average rate: " + String.format(Locale.US, "%.2f", rate) + " percentage points/hour")
        if (!directionMatches) out.appendLine(if (wantCharge) "Battery level declined during CHARGING status; no charging-up forecast is valid." else "Battery level increased during DISCHARGING status; no discharge-down forecast is valid.")
        if (counterDelta != null) out.appendLine("Charge-counter delta: " + String.format(Locale.US, "%+.1f mAh", counterDelta / 1000.0) +
            " • " + String.format(Locale.US, "%.1f mAh/hour", kotlin.math.abs(counterDelta) / 1000.0 / (elapsed / 3_600_000.0)) + " (Android estimate)")
        else out.appendLine("Charge-counter rate: unavailable")
        out.appendLine("Screen ON: " + durationText(onMs) + " • screen OFF: " + durationText(offMs))
        val onRateText = onRate?.let { String.format(Locale.US, "%.2f pp/h", it) } ?: "n/a"
        val offRateText = offRate?.let { String.format(Locale.US, "%.2f pp/h", it) } ?: "n/a"
        val onCounterText = onCounterRate?.let { String.format(Locale.US, "%+.0f mA battery-side", it) } ?: "n/a"
        val offCounterText = offCounterRate?.let { String.format(Locale.US, "%+.0f mA battery-side", it) } ?: "n/a"
        val remaining = if (wantCharge) (100 - current).coerceAtLeast(0).toDouble() else current.coerceAtLeast(0).toDouble()
        val thresholdName = if (wantCharge) "100%" else "0%"
        val onForecast = when {
            !directionMatches -> "not projected: session direction inconsistent"
            remaining <= 0.0 -> "already at threshold"
            onRate == null -> "not enough screen-ON data"
            else -> forecastDuration(remaining, String.format(Locale.US, "%.6f", onRate))
        }
        val offForecast = when {
            !directionMatches -> "not projected: session direction inconsistent"
            remaining <= 0.0 -> "already at threshold"
            offRate == null -> "not enough screen-OFF data"
            else -> forecastDuration(remaining, String.format(Locale.US, "%.6f", offRate))
        }
        out.appendLine("Screen ON (display-on proxy): " + onRateText + " • " + onCounterText +
            " • time to " + thresholdName + ": " + onForecast)
        out.appendLine("Screen OFF (display-off proxy): " + offRateText + " • " + offCounterText +
            " • time to " + thresholdName + ": " + offForecast)
        val thresholds = if (wantCharge) listOf(50, 80, 90, 100) else listOf(80, 50, 20, 10, 0)
        out.appendLine(if (wantCharge) "Time from first recorded sample in this session to charge thresholds:" else "Time from first recorded sample in this session to discharge thresholds:")
        for (threshold in thresholds) {
            val crossing = thresholdTime(run, threshold, wantCharge)
            val status = when {
                (wantCharge && initial >= threshold) || (!wantCharge && initial <= threshold) -> "already at threshold at session start"
                !directionMatches -> if (wantCharge) "not projected: battery level did not increase during this charging-state segment" else "not projected: battery level did not decrease during this discharging-state segment"
                crossing != null -> durationText(crossing) + " observed"
                rate > 0.0 && ((wantCharge && current < threshold) || (!wantCharge && current > threshold)) ->
                    durationText(((kotlin.math.abs(threshold - current).toDouble() / rate) * 3_600_000.0).toLong()) + " estimated from current session average"
                else -> "not reached / insufficient data"
            }
            out.appendLine("  " + threshold + "%: " + status)
        }
        if (wantCharge && last.state == "FULL") out.appendLine("Android full state observed at final sample.")
        return out.toString().trim()
    }

    /** Returns elapsed duration from the first row, not an absolute wall/boot timestamp. */
    private fun thresholdTime(rows: List<Row>, threshold: Int, upward: Boolean): Long? {
        var elapsedFromStart = 0L
        for (i in 1 until rows.size) {
            elapsedFromStart += sampleIntervalMs(rows[i - 1], rows[i]).coerceAtLeast(0L)
            val before = rows[i - 1].pct ?: continue
            val after = rows[i].pct ?: continue
            if (upward && before < threshold && after >= threshold) return elapsedFromStart
            if (!upward && before > threshold && after <= threshold) return elapsedFromStart
        }
        return null
    }

    private fun forecastDuration(remainingPercentagePoints: Double, rateText: String): String {
        val rate = rateText.toDoubleOrNull()
        if (rate == null || rate <= 0.0 || remainingPercentagePoints <= 0.0) return "not enough matching history"
        val ms = BatteryRateMath.forecastDurationMs(remainingPercentagePoints, rate) ?: return "not enough matching history"
        return durationText(ms) + " estimated from historical average"
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
        writer.write(RecorderCsv.encodeRow(listOf("record_type","timestamp_ms","elapsed_realtime_ms","battery_percent","battery_status","plug_type","battery_voltage_mv","battery_current_ua","battery_average_current_ua","max_charging_current_ua","max_charging_voltage_uv","battery_present","invalid_charger_state","battery_cycle_count","charge_counter_uah","energy_counter_nwh","temperature_c","battery_health","technology","screen_on","foreground_package","usage_access","power_supply_json","usb_voltage_candidate_uv","usb_current_candidate_ua","boot_count","event_type","details")))
        val sampleNames = listOf("battery_percent","battery_status","plug_type","battery_voltage_mv","battery_current_ua","battery_average_current_ua","max_charging_current_ua","max_charging_voltage_uv","battery_present","invalid_charger_state","battery_cycle_count","charge_counter_uah","energy_counter_nwh","temperature_c","battery_health","technology","screen_on","foreground_package","usage_access","power_supply_json","usb_voltage_candidate_uv","usb_current_candidate_ua","boot_count")
        readableDatabase.rawQuery("SELECT * FROM samples ORDER BY timestamp_ms", null).use { samples ->
            readableDatabase.rawQuery("SELECT timestamp_ms,event_type,details FROM events ORDER BY timestamp_ms", null).use { events ->
                var hasSample = samples.moveToFirst()
                var hasEvent = events.moveToFirst()
                while (hasSample || hasEvent) {
                    val useSample = hasSample && (!hasEvent || samples.getLong(samples.getColumnIndexOrThrow("timestamp_ms")) <= events.getLong(0))
                    if (useSample) {
                        val fields = mutableListOf(
                            "SAMPLE",
                            samples.getLong(samples.getColumnIndexOrThrow("timestamp_ms")).toString(),
                            value(samples, "elapsed_realtime_ms")
                        )
                        sampleNames.forEach { fields += value(samples, it) }
                        fields += listOf("", "")
                        writer.write(RecorderCsv.encodeRow(fields))
                        hasSample = samples.moveToNext()
                    } else {
                        val fields = listOf("EVENT", events.getLong(0).toString(), "") +
                            List(sampleNames.size) { "" } + listOf(events.getString(1), events.getString(2))
                        writer.write(RecorderCsv.encodeRow(fields))
                        hasEvent = events.moveToNext()
                    }
                }
            }
        }
        writer.flush()
    }
    private fun value(c: Cursor, column: String): String {
        val index = c.getColumnIndexOrThrow(column)
        return if (c.isNull(index)) "" else c.getString(index) ?: ""
    }

}
