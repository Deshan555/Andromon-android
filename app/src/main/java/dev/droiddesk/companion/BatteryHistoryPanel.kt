// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

package dev.droiddesk.companion

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The history part of the Battery screen: level and temperature charts over 24 hours or 7 days,
 * figures worked out from the samples ([BatteryLog]), and per-app use when the shell helper can
 * provide it ([BatteryUse]). [BatteryScreen] adds [view] and calls [onShow] / [onHide].
 */
internal class BatteryHistoryPanel(private val host: MainActivity) {
    private val ui get() = host.ui
    private fun color(id: Int) = ui.color(id)

    private var windowMs = BatteryLog.DAY
    private var shown = false
    private var loadingApps = false
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!shown) return
            refresh()
            handler.postDelayed(this, 60_000)
        }
    }

    private lateinit var rangeChips: List<Pair<Long, TextView>>
    private lateinit var levelChart: TimeChart
    private lateinit var tempChart: TimeChart
    private lateinit var readout: TextView
    private lateinit var tempTitle: TextView
    private lateinit var note: TextView
    private lateinit var figures: LinearLayout
    private lateinit var appsBox: LinearLayout

    val view: View by lazy { build() }

    private fun build(): View = ui.column {
        addView(ui.section("History") {
            rangeChips = listOf(BatteryLog.DAY to "24 h", BatteryLog.KEEP_MS to "7 days").map { (ms, label) ->
                val chip = ui.pill(label, 0, 0).apply { textSize = 13f; minHeight = ui.dp(34); setPadding(ui.dp(14), 0, ui.dp(14), 0) }
                addView(chip, ui.lp(w = WRAP_CONTENT, start = 6))
                ms to chip
            }
        })
        addView(ui.glassCard(16) {
            addView(ui.text(13f, color(R.color.ink), ui.medium) { text = "Battery level" })
            readout = ui.text(12f, color(R.color.ink_soft)) { maxLines = 1 }
            addView(readout, ui.lp(top = 3))
            levelChart = TimeChart(context, ui.regular).apply {
                colors(color(R.color.ink), color(R.color.ink_faint), color(R.color.ink_soft), color(R.color.good), color(R.color.surface))
                yMin = 0f; yMax = 100f
                yLabels = listOf(0f to "0", 50f to "50", 100f to "100%")
                contentDescription = "Chart of battery level over time"
                onPick = { p -> readout.text = p?.let(::describe) ?: defaultReadout() }
            }
            addView(levelChart, ui.lp(h = ui.dp(170), top = 8))
            addView(ui.row {
                addView(View(context).apply { background = ui.rounded((color(R.color.good) and 0xFFFFFF) or 0x55000000, 3) },
                    ui.lp(w = ui.dp(14), h = ui.dp(10)))
                addView(ui.text(12f, color(R.color.ink_soft)) { text = "Charging" }, ui.lp(w = WRAP_CONTENT, start = 6))
                addView(ui.text(12f, color(R.color.ink_soft)) { text = "- - -  No samples (phone asleep or off)" },
                    ui.lp(w = WRAP_CONTENT, start = 14))
            }, ui.lp(top = 8))

            tempTitle = ui.text(13f, color(R.color.ink), ui.medium) { text = "Temperature" }
            addView(tempTitle, ui.lp(top = 20))
            tempChart = TimeChart(context, ui.regular).apply {
                colors(color(R.color.peach_dot), color(R.color.ink_faint), color(R.color.ink_soft), 0, color(R.color.surface))
                contentDescription = "Chart of battery temperature over time"
                onPick = { p -> tempTitle.text = if (p == null) "Temperature" else "Temperature · %.1f °C at %s".format(p.v, clock(p.t)) }
            }
            addView(tempChart, ui.lp(h = ui.dp(110), top = 8))
            note = ui.text(12f, color(R.color.ink_soft)) { setLineSpacing(ui.dp(2).toFloat(), 1f) }
            addView(note, ui.lp(top = 12))
        }, ui.lp(top = 12))

        figures = ui.glassCard(16)
        addView(figures, ui.lp(top = 12))

        addView(ui.section("App battery use"), ui.lp(top = 26))
        appsBox = ui.glassCard(14)
        addView(appsBox, ui.lp(top = 12))
    }

    fun onShow() {
        shown = true
        BatteryLog.schedule(host)
        handler.removeCallbacks(tick)
        tick.run()
        loadApps()
    }

    fun onHide() {
        shown = false
        handler.removeCallbacks(tick)
    }

    // ---------- charts and figures ----------

    private var latest: BatteryLog.Sample? = null

    private fun refresh() {
        rangeChips.forEach { (ms, chip) ->
            val label = chip.text.toString()
            val on = ms == windowMs
            ui.stylePill(chip, label, color(if (on) R.color.fab else R.color.glass_strong), color(if (on) R.color.on_fab else R.color.ink)) {
                windowMs = ms; refresh()
            }
        }
        val window = windowMs
        thread(isDaemon = true) {
            BatteryLog.record(host)
            val week = BatteryLog.read(host)
            val now = Phone.battery(host)
            val stats = BatteryLog.stats(week, window, now.level, now.charging)
            host.runOnUiThread { if (!host.isDestroyed && window == windowMs) show(week, stats, now.charging) }
        }
    }

    private fun show(week: List<BatteryLog.Sample>, s: BatteryLog.Stats, charging: Boolean) {
        val now = System.currentTimeMillis()
        val from = now - windowMs
        val samples = week.filter { it.time >= from }
        latest = samples.lastOrNull()
        val xLabels = xLabels(from, now)

        levelChart.set(samples.map { TimeChart.Point(it.time, it.level.toFloat(), it.charging) }, from, now, xLabels)
        readout.text = defaultReadout()

        val temps = samples.filter { it.tempC != null }
        if (s.tempMin != null && s.tempMax != null) {
            val lo = floor(s.tempMin - 1f); val hi = ceil(s.tempMax + 1f)
            val pad = ((6f - (hi - lo)) / 2f).coerceAtLeast(0f)
            tempChart.yMin = lo - pad; tempChart.yMax = hi + pad
            tempChart.yLabels = listOf(tempChart.yMin to "%.0f°".format(tempChart.yMin), tempChart.yMax to "%.0f°C".format(tempChart.yMax))
        }
        tempChart.set(temps.map { TimeChart.Point(it.time, it.tempC!!, false) }, from, now, xLabels)

        val first = week.firstOrNull()
        note.text = when {
            first == null -> "Recording starts now."
            else -> "Recorded about every 15 minutes, also while the app is closed, since ${day(first.time)}. " +
                "Android pauses recording while the phone sleeps deeply, so some stretches have no samples. Drag across a chart to read values."
        }

        figures.removeAllViews()
        val range = if (windowMs == BatteryLog.DAY) "24 h" else "7 days"
        val rows = listOf(
            "Average drain on battery ($range)" to (s.drainPerHour?.let {
                "%.1f %% per hour\nover %s on battery".format(it, span(s.drainMs))
            } ?: "Not enough data yet"),
            "Time left (estimate)" to when {
                charging -> "Charging"
                s.leftMs != null -> "About ${span(s.leftMs)}\nif used like ${s.leftBasis}"
                else -> "Not enough data yet"
            },
            "Last seen at 100%" to (s.lastFull?.let {
                DateUtils.getRelativeTimeSpanString(it, now, DateUtils.MINUTE_IN_MILLIS).toString()
            } ?: "Not in the recorded history"),
            "Temperature ($range)" to (if (s.tempMin != null && s.tempMax != null && s.tempAvg != null)
                "%.1f–%.1f °C\naverage %.1f °C".format(s.tempMin, s.tempMax, s.tempAvg) else "Not reported"),
        )
        rows.forEachIndexed { i, (k, v) ->
            val (row, value) = ui.keyValue(k)
            value.text = v
            figures.addView(row, ui.lp(top = if (i == 0) 0 else 12))
        }
        figures.addView(ui.text(12f, color(R.color.ink_soft)) {
            text = "Worked out from this app's own samples. The time left is an estimate from recent drain, not a figure from Android."
            setLineSpacing(ui.dp(2).toFloat(), 1f)
        }, ui.lp(top = 12))
    }

    private fun defaultReadout(): String = latest?.let { "Latest: " + describe(TimeChart.Point(it.time, it.level.toFloat(), it.charging)) }
        ?: "No samples in this range yet"

    private fun describe(p: TimeChart.Point) =
        "${if (windowMs > BatteryLog.DAY) day(p.t) + " " else ""}${clock(p.t)} · ${p.v.toInt()}%" + if (p.mark) " · charging" else ""

    private fun clock(t: Long): String = DateFormat.getTimeFormat(host).format(Date(t))
    private fun day(t: Long): String = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(t))

    private fun span(ms: Long): String {
        val min = ms / 60_000
        return when {
            min < 60 -> "$min min"
            min < 48 * 60 -> "${min / 60} h ${min % 60} min"
            else -> "${min / (24 * 60)} days ${(min / 60) % 24} h"
        }
    }

    /** 24 h: a clock time every 6 hours. 7 days: the weekday at each midnight. */
    private fun xLabels(from: Long, to: Long): List<Pair<Long, String>> {
        if (windowMs <= BatteryLog.DAY) return (0..4).map { i ->
            val t = from + (to - from) * i / 4
            t to if (i == 4) "now" else clock(t)
        }
        val out = ArrayList<Pair<Long, String>>()
        val cal = Calendar.getInstance().apply {
            timeInMillis = to
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val fmt = SimpleDateFormat("EEE", Locale.getDefault())
        while (cal.timeInMillis > from) {
            out += cal.timeInMillis to fmt.format(cal.time)
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return out
    }

    // ---------- per-app use ----------

    private fun loadApps() {
        if (loadingApps) return
        loadingApps = true
        if (appsBox.childCount == 0) appsBox.addView(appsNote("Checking…"))
        thread(isDaemon = true) {
            val r = runCatching { BatteryUse.load(host) }
            host.runOnUiThread {
                loadingApps = false
                if (!host.isDestroyed) showApps(r)
            }
        }
    }

    private fun appsNote(t: String) = ui.text(13f, color(R.color.ink_soft)) { text = t; setLineSpacing(ui.dp(2).toFloat(), 1f) }

    private fun showApps(r: Result<BatteryUse.Result>) {
        appsBox.removeAllViews()
        val res = r.getOrNull()
        if (res == null) {
            val why = when (val e = r.exceptionOrNull()) {
                is ShellHelperClient.Unavailable ->
                    "Not available right now. Android doesn't let apps read other apps' battery use, so this needs the " +
                        "rights of adb: Shizuku, or the Droid Desk helper the RAM manager uses. " + Privileged.HOW_TO +
                        " Nothing is shown here without it."
                is BatteryUse.OldHelper ->
                    "The helper that is running is from an older version of this app. Turn it off and on again in Droid Desk " +
                        "(Shizuku › Phone RAM manager), restart the phone, or use Shizuku (Settings › Privileged access)."
                else -> "Couldn't read Android's battery statistics: ${e?.message ?: "unknown error"}"
            }
            appsBox.addView(appsNote(why))
            appsBox.addView(ui.pill("Open Android's battery usage", color(R.color.glass_strong), color(R.color.ink), R.drawable.ic_arrow_right) {
                runCatching { host.startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) }
            }, ui.lp(top = 12))
            return
        }
        if (res.apps.isEmpty()) {
            appsBox.addView(appsNote("Android on this phone didn't report per-app figures (or nothing has used a measurable amount since the last full charge)."))
            return
        }
        val top = res.apps.take(12)
        val max = top.first().mah
        for ((i, app) in top.withIndex()) {
            appsBox.addView(ui.row {
                if (app.icon != null) addView(ImageView(context).apply { setImageDrawable(app.icon) }, ui.lp(w = ui.dp(36), h = ui.dp(36)))
                else addView(ui.roundIcon(36, color(R.color.glass_strong), R.drawable.ic_settings, color(R.color.ink), 17))
                addView(ui.column {
                    addView(ui.row {
                        addView(ui.text(14f, color(R.color.ink), ui.medium) {
                            text = app.label; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                        }, ui.lp(w = 0, weight = 1f))
                        addView(ui.text(13f, color(R.color.ink_soft)) {
                            val share = res.computedMah?.let { " · %.0f%%".format((app.mah / it * 100f).coerceAtMost(100f)) } ?: ""
                            text = (if (app.mah >= 10f) "%.0f mAh" else "%.1f mAh").format(app.mah) + share
                        }, ui.lp(w = WRAP_CONTENT, start = 8))
                    })
                    addView(Meter(context).apply {
                        colors(color(R.color.glass_strong), color(R.color.ink))
                        fraction = app.mah / max
                    }, ui.lp(h = ui.dp(6), top = 6))
                }, ui.lp(w = 0, weight = 1f, start = 12))
            }, ui.lp(top = if (i == 0) 0 else 12))
        }
        appsBox.addView(appsNote("Android's own estimate since the last full charge (dumpsys batterystats, read through " +
            "Shizuku or the Droid Desk helper)." + (if (res.computedMah != null) " The percentage is the share of everything Android counted." else "") +
            " Estimates differ between phones."), ui.lp(top = 14))
    }
}

/**
 * A line over time: fixed y range, a few quiet gridlines, optional shaded stretches (points with
 * [Point.mark]), and a dashed line where neighbouring samples are far apart. Dragging across it
 * reports the nearest sample through [onPick].
 */
internal class TimeChart(ctx: Context, face: Typeface) : View(ctx) {
    class Point(val t: Long, val v: Float, val mark: Boolean)

    var yMin = 0f
    var yMax = 100f
    var yLabels: List<Pair<Float, String>> = emptyList()
    var onPick: ((Point?) -> Unit)? = null

    private var points: List<Point> = emptyList()
    private var from = 0L
    private var to = 1L
    private var xLabels: List<Pair<Long, String>> = emptyList()
    private var picked: Point? = null

    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * d
        pathEffect = DashPathEffect(floatArrayOf(4 * d, 4 * d), 0f)
    }
    private val grid = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f * d }
    private val band = Paint()
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private val solidPath = Path()
    private val gapPath = Path()

    fun colors(lineColor: Int, gridColor: Int, labelColor: Int, bandColor: Int, surface: Int) {
        line.color = lineColor; dot.color = lineColor
        dashed.color = (lineColor and 0xFFFFFF) or 0x8C000000.toInt()
        grid.color = (gridColor and 0xFFFFFF) or 0x59000000
        label.color = labelColor
        band.color = (bandColor and 0xFFFFFF) or 0x40000000
        ring.color = surface
    }

    fun set(points: List<Point>, from: Long, to: Long, xLabels: List<Pair<Long, String>>) {
        this.points = points; this.from = from; this.to = maxOf(to, from + 1); this.xLabels = xLabels
        picked = null
        invalidate()
    }

    private var plotLeft = 0f
    private var plotRight = 0f
    private var plotTop = 0f
    private var plotBottom = 0f

    private fun x(t: Long) = plotLeft + (plotRight - plotLeft) * ((t - from).toFloat() / (to - from)).coerceIn(0f, 1f)
    private fun y(v: Float) = plotBottom - (plotBottom - plotTop) * ((v - yMin) / (yMax - yMin)).coerceIn(0f, 1f)

    override fun onDraw(canvas: Canvas) {
        plotLeft = (yLabels.maxOfOrNull { label.measureText(it.second) } ?: 0f) + 8 * d
        plotRight = width - 6 * d
        plotTop = 6 * d
        plotBottom = height - label.textSize - 8 * d
        if (plotRight <= plotLeft || plotBottom <= plotTop) return

        for ((v, text) in yLabels) {
            val gy = y(v)
            canvas.drawLine(plotLeft, gy, plotRight, gy, grid)
            label.textAlign = Paint.Align.RIGHT
            canvas.drawText(text, plotLeft - 6 * d, gy + label.textSize * 0.35f, label)
        }
        // Time labels: skip one that would run into the previous.
        var lastEnd = -1f
        for ((t, text) in xLabels.sortedBy { it.first }) {
            val w = label.measureText(text)
            val cx = (x(t) - w / 2).coerceIn(plotLeft, plotRight - w)
            if (cx < lastEnd + 6 * d) continue
            label.textAlign = Paint.Align.LEFT
            canvas.drawText(text, cx, height - 3 * d, label)
            lastEnd = cx + w
        }

        if (points.size < 2) {
            label.textAlign = Paint.Align.CENTER
            canvas.drawText(if (points.isEmpty()) "No samples yet" else "One sample so far. Check back later.",
                (plotLeft + plotRight) / 2, (plotTop + plotBottom) / 2, label)
            return
        }

        solidPath.reset(); gapPath.reset()
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            val ax = x(a.t); val bx = x(b.t)
            val gap = b.t - a.t > BatteryLog.GAP_MS
            if (a.mark && b.mark && !gap) canvas.drawRect(ax, plotTop, bx, plotBottom, band)
            val path = if (gap) gapPath else solidPath
            path.moveTo(ax, y(a.v)); path.lineTo(bx, y(b.v))
        }
        canvas.drawPath(gapPath, dashed)
        canvas.drawPath(solidPath, line)

        val p = picked ?: points.last()
        val px = x(p.t); val py = y(p.v)
        if (picked != null) canvas.drawLine(px, plotTop, px, plotBottom, grid)
        canvas.drawCircle(px, py, 6f * d, ring)
        canvas.drawCircle(px, py, 4f * d, dot)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (points.size < 2) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val p = points.minByOrNull { abs(x(it.t) - e.x) }
                if (p !== picked) { picked = p; onPick?.invoke(p); invalidate() }
            }
            MotionEvent.ACTION_CANCEL -> { picked = null; onPick?.invoke(null); invalidate() }
        }
        return true
    }
}
