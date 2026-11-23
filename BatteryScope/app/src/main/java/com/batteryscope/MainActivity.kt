package com.batteryscope

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var reader: BatteryReader
    private lateinit var history: HistoryStore
    private lateinit var root: LinearLayout
    private lateinit var chart: BatteryHistoryChart
    private val values = mutableMapOf<String, TextView>()
    private var previousTrendSample: BatterySnapshot? = null
    private var percentRatePerHour: Double? = null
    private var lastSavedAt = 0L
    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 2_000L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reader = BatteryReader(this); history = HistoryStore(this)
        buildUi(); refresh()
    }
    override fun onResume() { super.onResume(); handler.removeCallbacks(ticker); handler.post(ticker) }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }
    override fun onDestroy() { handler.removeCallbacks(ticker); super.onDestroy() }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(10, 17, 27)); clipToPadding = false }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(32)) }
        scroll.addView(root); setContentView(scroll)
        text("BATTERYSCOPE", 13, 0xFF72E0B2.toInt(), true)
        text("Battery health & live monitor", 26, Color.WHITE, true).setPadding(0, dp(6), 0, dp(18))
        val hero = card()
        val heroRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        val percent = TextView(this).apply { text = "--%"; textSize = 52f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD }
        values["percent"] = percent
        val status = TextView(this).apply { text = "Reading…"; textSize = 15f; setTextColor(0xFF72E0B2.toInt()); gravity = Gravity.END }
        values["status"] = status
        heroRow.addView(percent, LinearLayout.LayoutParams(0, -2, 1f)); heroRow.addView(status)
        hero.addView(heroRow)
        hero.addView(TextView(this).apply { text = "LIVE STATE OF CHARGE"; textSize = 11f; setTextColor(0xFF91A4B7.toInt()) })
        section("LIVE ELECTRICAL")
        gridCard(listOf("current" to "Current", "voltage" to "Voltage", "counter" to "Charge counter", "power" to "Power"))
        section("BATTERY CONDITION")
        gridCard(listOf("temperature" to "Temperature", "health" to "Reported health", "technology" to "Chemistry", "source" to "Power source"))
        section("TIME ESTIMATES")
        val estimate = card()
        addValue(estimate, "remaining", "Time to 20%")
        addValue(estimate, "full", "Time to 100%")
        textInto(estimate, "Forecasts require an observed SOC trend. They are estimates, not guarantees.", 12, 0xFF91A4B7.toInt())
        section("7-DAY HISTORY")
        val historyCard = card()
        chart = BatteryHistoryChart(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(190)); rows = history.readRows() }
        historyCard.addView(chart)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        buttons.addView(button("Refresh") { refresh() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        buttons.addView(button("Export CSV") { exportCsv() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        root.addView(buttons)
        text("Device-reported measurements depend on phone hardware and firmware. Unsupported readings are shown as unavailable, never guessed.", 11, 0xFF718397.toInt(), false).setPadding(0, dp(18), 0, 0)
    }

    private fun gridCard(items: List<Pair<String, String>>): LinearLayout {
        val c = card(); items.forEach { addValue(c, it.first, it.second) }; return c
    }
    private fun addValue(parent: LinearLayout, key: String, label: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(9), 0, dp(9)) }
        val name = TextView(this).apply { text = label; textSize = 14f; setTextColor(0xFF91A4B7.toInt()) }
        val value = TextView(this).apply { text = "—"; textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.END }
        values[key] = value
        row.addView(name, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(value); parent.addView(row)
        parent.addView(View(this).apply { setBackgroundColor(0xFF293746.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)))
    }
    private fun refresh() {
        val s = reader.read() ?: return
        val old = previousTrendSample
        // SOC is integer-valued on many phones. Compare samples at least five minutes apart
        // to avoid wildly overstated forecasts from a one-percent step over a few seconds.
        if (old == null) {
            previousTrendSample = s
        } else if (s.timestamp - old.timestamp >= 5L * 60 * 1000) {
            if (s.isCharging == old.isCharging && s.percent != old.percent) {
                val hours = (s.timestamp - old.timestamp) / 3_600_000.0
                if (hours > 0) percentRatePerHour = abs(s.percent - old.percent) / hours
            }
            previousTrendSample = s
        }
        if (s.timestamp - lastSavedAt >= 60_000L || lastSavedAt == 0L) { history.add(s); lastSavedAt = s.timestamp }
        values["percent"]?.text = "${s.percent}%"
        values["status"]?.text = when { s.isFull -> "FULL"; s.isCharging -> "CHARGING"; s.isPlugged -> "CONNECTED"; else -> "DISCHARGING" }
        values["current"]?.text = s.currentMa?.let { "${fmt(it)} mA" } ?: "Not supported"
        values["voltage"]?.text = s.voltageV?.let { "${fmt(it)} V" } ?: "Unavailable"
        values["counter"]?.text = s.chargeCounterMah?.let { "${fmt(it)} mAh" } ?: "Not supported"
        values["power"]?.text = if (s.currentMa != null && s.voltageV != null) "${fmt(s.currentMa * s.voltageV / 1000)} W" else "Unavailable"
        values["temperature"]?.text = s.temperatureC?.let { "${fmt(it)} °C" } ?: "Unavailable"
        values["health"]?.text = healthName(s.health)
        values["technology"]?.text = s.technology
        values["source"]?.text = when (s.plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC adapter"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            0 -> "Battery"
            else -> "External power"
        }
        val rate = percentRatePerHour
        values["remaining"]?.text = if (!s.isCharging && rate != null && rate > 0)
            BatteryRateMath.forecastDurationMs((s.percent - 20).toDouble(), rate)?.let { duration(it) } ?: "—"
            else "Collecting trend"
        values["full"]?.text = if (s.isCharging && rate != null && rate > 0)
            BatteryRateMath.forecastDurationMs((100 - s.percent).toDouble(), rate)?.let { duration(it) } ?: "—"
            else "Collecting trend"
        chart.rows = history.readRows()
    }
    private fun healthName(v: Int) = when (v) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Failed"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "Too cold"
        else -> "Unknown"
    }
    private fun exportCsv() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "batteryscope-history.csv")
        }
        @Suppress("DEPRECATION") startActivityForResult(intent, 42)
    }
    @Deprecated("Deprecated callback retained for API 23 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == RESULT_OK) data?.data?.let { uri ->
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(history.csv()) }
        }
    }
    private fun duration(ms: Long): String {
        val h = ms / 3_600_000L; val m = (ms / 60_000L) % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
    private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun card(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(15), dp(16), dp(15))
            background = GradientDrawable().apply { setColor(0xFF152231.toInt()); cornerRadius = dp(16).toFloat() }
        }
        root.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }); return box
    }
    private fun section(s: String) { text(s, 12, 0xFF91A4B7.toInt(), true).setPadding(0, dp(14), 0, dp(8)) }
    private fun textInto(parent: LinearLayout, s: String, size: Int, color: Int) {
        parent.addView(TextView(this).apply { text = s; textSize = size.toFloat(); setTextColor(color); setPadding(0, dp(10), 0, 0) })
    }
    private fun text(s: String, size: Int, color: Int, bold: Boolean): TextView {
        val v = TextView(this).apply { text = s; textSize = size.toFloat(); setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD }
        root.addView(v); return v
    }
    private fun button(s: String, action: () -> Unit) = Button(this).apply {
        text = s; setTextColor(Color.WHITE); isAllCaps = false
        backgroundTintList = ColorStateList.valueOf(0xFF236B59.toInt())
        setOnClickListener { action() }
    }
}
