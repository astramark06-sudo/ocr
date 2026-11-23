package com.batteryscope

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class HistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("battery_history", Context.MODE_PRIVATE)
    @Synchronized fun add(s: BatterySnapshot) {
        val rows = readRows()
        val last = rows.optJSONObject(rows.length() - 1)
        if (last != null && s.timestamp - last.optLong("t") < 55_000L) return
        rows.put(JSONObject().apply {
            put("t", s.timestamp); put("p", s.percent)
            put("ma", s.currentMa ?: JSONObject.NULL); put("v", s.voltageV ?: JSONObject.NULL)
            put("temp", s.temperatureC ?: JSONObject.NULL); put("mah", s.chargeCounterMah ?: JSONObject.NULL)
        })
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        val trimmed = JSONArray()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            if (row.optLong("t") >= cutoff) trimmed.put(row)
        }
        prefs.edit().putString("rows", trimmed.toString()).apply()
    }
    fun readRows(): JSONArray = try { JSONArray(prefs.getString("rows", "[]")) } catch (_: Exception) { JSONArray() }
    fun csv(): String = buildString {
        append("timestamp,level_percent,current_mA,voltage_V,temperature_C,charge_counter_mAh\n")
        val rows = readRows()
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            append(r.optLong("t")).append(',').append(r.optInt("p")).append(',')
            append(cell(r, "ma")).append(',').append(cell(r, "v")).append(',')
            append(cell(r, "temp")).append(',').append(cell(r, "mah")).append('\n')
        }
    }
    private fun cell(r: JSONObject, key: String) = if (r.isNull(key)) "" else r.opt(key).toString()
}
