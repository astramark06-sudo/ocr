package com.batteryscope

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import org.json.JSONArray

class BatteryHistoryChart(context: Context) : View(context) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF72E0B2.toInt(); strokeWidth = 5f; style = Paint.Style.STROKE }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF293746.toInt(); strokeWidth = 1f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF91A4B7.toInt(); textSize = 28f }
    var rows: JSONArray = JSONArray()
        set(value) { field = value; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 54f; val right = width - 18f; val top = 20f; val bottom = height - 34f
        for (i in 0..4) {
            val y = top + (bottom - top) * i / 4f
            canvas.drawLine(left, y, right, y, grid)
            canvas.drawText("${100 - i * 25}%", 0f, y + 9f, label)
        }
        if (rows.length() < 2) {
            canvas.drawText("Collecting readings — keep app open", left, height / 2f, label); return
        }
        val path = Path(); val n = rows.length()
        for (i in 0 until n) {
            val p = rows.optJSONObject(i)?.optInt("p", 0) ?: 0
            val x = left + (right - left) * i / (n - 1).toFloat()
            val y = bottom - (bottom - top) * p.coerceIn(0, 100) / 100f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, line)
    }
}
