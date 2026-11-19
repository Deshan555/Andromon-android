// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.content.Intent
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.TextView
import java.time.format.TextStyle
import java.util.Locale

/**
 * Tools (the bottom bar's middle item): two tabs. Dashboard shows how the phone is doing, in charts
 * only: rings for health, memory and battery, a storage donut, the battery over the last 24 hours and
 * screen time over the last 7 days. All tools has a tile for every tool.
 */
internal class ToolsScreen(host: MainActivity) : Screen(host) {

    override val name = "Tools"
    override val navIcon = R.drawable.ic_grid
    override val navColor = R.color.bg

    private lateinit var greetSub: TextView
    private lateinit var dashboard: LinearLayout
    private lateinit var allTools: LinearLayout
    private var selectTab: (Int) -> Unit = {}
    private var tab = 0

    /** A small ring with the figure inside and a caption under it. */
    private class Gauge(val box: View, val ring: Ring, val value: TextView, val sub: TextView)
    private lateinit var healthGauge: Gauge
    private lateinit var memGauge: Gauge
    private lateinit var batteryGauge: Gauge

    private lateinit var storageDonut: Ring
    private lateinit var storageUsed: TextView
    private lateinit var storageSub: TextView
    private lateinit var storageLegend: LinearLayout

    private lateinit var batteryChart: LineChart
    private lateinit var batterySub: TextView
    private lateinit var batteryFrom: TextView

    private lateinit var screenCard: LinearLayout

    private lateinit var memTile: Ui.Tile
    private lateinit var batteryTile: Ui.Tile
    private lateinit var netTile: Ui.Tile

    override fun build(): View = ui.column {
        addView(ui.topBar("Tools", host::goHome).first)
        greetSub = ui.subtitle()
        addView(greetSub, ui.lp(top = 6))
        val (tabs, select) = segmented(ui, listOf("Dashboard", "All tools")) { showTab(it, remember = true) }
        selectTab = select
        tabs.contentDescription = "Dashboard or all tools"
        addView(tabs, ui.lp(top = 20))
        dashboard = ui.column {
            addView(gauges())
            addView(storageChart(), ui.lp(top = 12))
            addView(batteryGraph(), ui.lp(top = 12))
            screenCard = ui.glassCard(16)
            addView(screenCard, ui.lp(top = 12))
        }
        addView(dashboard, ui.lp(top = 16))
        allTools = ui.column { addView(tiles()) }
        addView(allTools, ui.lp(top = 16))
        addView(footer(), ui.lp(top = 24))
        showTab(Prefs.toolsTab(host), remember = false)
    }

    private fun showTab(i: Int, remember: Boolean) {
        val changed = i != tab
        tab = i
        selectTab(i)
        dashboard.visibility = if (i == 0) View.VISIBLE else View.GONE
        allTools.visibility = if (i == 1) View.VISIBLE else View.GONE
        if (remember) Prefs.setToolsTab(host, i)
        if (changed && remember) host.scrollToTop()
    }

    override fun onShow() {
        refresh()
        loadStorage()
        loadCharts()
    }

    override fun onPermissionsResult() { refresh(); loadCharts() }

    // ---------- dashboard ----------

    /** Health, memory and battery as three rings side by side; each opens the screen that says more. */
    private fun gauges(): View {
        fun gauge(title: String, screen: String): Gauge {
            val ring = Ring(host).apply { strokeDp = 10f; colors(color(R.color.chip), color(R.color.ink), color(R.color.ink)) }
            val value = ui.text(17f, color(R.color.ink), ui.medium) { gravity = Gravity.CENTER; text = "–" }
            val sub = ui.text(12f, color(R.color.ink_soft)) { gravity = Gravity.CENTER; maxLines = 1 }
            val box = ui.column {
                gravity = Gravity.CENTER_HORIZONTAL
                background = ui.pressable(ui.rounded(color(R.color.surface), 22))
                setPadding(ui.dp(8), ui.dp(14), ui.dp(8), ui.dp(12))
                addView(FrameLayout(context).apply {
                    addView(ring, FrameLayout.LayoutParams(-1, -1))
                    addView(value, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                }, ui.lp(w = ui.dp(78), h = ui.dp(78)))
                addView(ui.text(13f, color(R.color.ink), ui.medium) { gravity = Gravity.CENTER; text = title }, ui.lp(top = 10))
                addView(sub, ui.lp(top = 2))
                setOnClickListener { host.show(screen) }
            }
            return Gauge(box, ring, value, sub)
        }
        healthGauge = gauge("Health", "Cleaner")
        memGauge = gauge("Memory", "Memory")
        batteryGauge = gauge("Battery", "Battery")
        // The health ring is the one figure on lime: the screen's single hero colour.
        healthGauge.box.background = ui.pressable(ui.hero(22))
        healthGauge.ring.colors(color(R.color.glass_strong), color(R.color.ink), color(R.color.ink))
        return ui.row {
            listOf(healthGauge, memGauge, batteryGauge).forEachIndexed { i, g -> addView(g.box, ui.lp(w = 0, weight = 1f, start = if (i == 0) 0 else 10)) }
        }
    }

    /** Storage as a donut: apps, media, documents, the rest, and free space. */
    private fun storageChart() = ui.glassCard(16) {
        val (head, sub) = ui.cardHeader(R.drawable.ic_sd, "Storage") {
            addView(ui.circleButton(R.drawable.ic_arrow_right, "Open Storage: folder sizes", 36) { host.show("Storage") })
        }
        storageSub = sub
        addView(head)
        addView(ui.row {
            gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(context).apply {
                storageDonut = Ring(context).apply { strokeDp = 18f; colors(color(R.color.chip), color(R.color.ink), color(R.color.ink)); busy = true }
                addView(storageDonut, FrameLayout.LayoutParams(-1, -1))
                addView(ui.column {
                    gravity = Gravity.CENTER
                    storageUsed = ui.text(20f, color(R.color.ink), ui.medium) { gravity = Gravity.CENTER; text = "–" }
                    addView(storageUsed, ui.lp(w = WRAP_CONTENT))
                    addView(ui.text(12f, color(R.color.ink_soft)) { gravity = Gravity.CENTER; text = "used" }, ui.lp(w = WRAP_CONTENT, top = 2))
                }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            }, ui.lp(w = ui.dp(128), h = ui.dp(128)))
            storageLegend = ui.column()
            addView(storageLegend, ui.lp(w = 0, weight = 1f, start = 18))
        }, ui.lp(top = 16))
    }

    /** Battery level over the last 24 hours, from the app's own battery log. */
    private fun batteryGraph() = ui.glassCard(16) {
        val (head, sub) = ui.cardHeader(R.drawable.ic_battery, "Battery, last 24 hours") {
            addView(ui.circleButton(R.drawable.ic_arrow_right, "Open Battery", 36) { host.show("Battery") })
        }
        batterySub = sub
        addView(head)
        batteryChart = LineChart(context).apply {
            slots = 96; floorZero = true; minSpan = 100f; fill = true
            marker = color(R.color.marker)
            colors(color(R.color.chart), color(R.color.glass_strong), color(R.color.surface))
        }
        addView(batteryChart, ui.lp(h = ui.dp(120), top = 12))
        addView(ui.row {
            batteryFrom = ui.text(12f, color(R.color.ink_soft)) { text = "24 h ago" }
            addView(batteryFrom, ui.lp(w = 0, weight = 1f))
            addView(ui.text(12f, color(R.color.ink_soft)) { text = "now" }, ui.lp(w = WRAP_CONTENT))
        }, ui.lp(top = 4))
    }

    /** Screen time per day for the last 7 days (needs usage access). */
    private fun fillScreenTime(u: AppStats.Usage?) {
        screenCard.removeAllViews()
        val (head, sub) = ui.cardHeader(R.drawable.ic_timer, "Screen time, last 7 days") {
            addView(ui.circleButton(R.drawable.ic_arrow_right, "Open Stats", 36) { host.show("Stats") })
        }
        screenCard.addView(head)
        if (u == null) {
            sub.text = "Needs usage access"
            screenCard.addView(ui.smallPill("Allow usage access") {
                runCatching { host.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            }, ui.lp(w = WRAP_CONTENT, top = 14))
            return
        }
        val perDay = u.daily.map { it.values[0] }
        sub.text = "About ${duration((perDay.sum() / 7).toLong())} a day"
        screenCard.addView(BarChart(host).apply {
            colors(listOf(color(R.color.green_dot)), color(R.color.chip), color(R.color.ink_soft), color(R.color.ink), color(R.color.line), ui.regular)
            bars = u.daily.map { listOf((it.values[0] / 60_000).toFloat()) }
            labels = u.daily.map { it.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
            highlight = u.daily.lastIndex
            contentDescription = "Screen time per day, last 7 days: " + u.daily.joinToString(", ") {
                "${it.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${duration(it.values[0].toLong())}"
            }
        }, ui.lp(h = ui.dp(140), top = 14))
    }


    private fun duration(ms: Long): String {
        val m = ms / 60_000
        return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
    }

    // ---------- all tools ----------
    private fun tiles(): View {
        memTile = ui.tile(color(R.color.surface), "Memory", R.drawable.ic_memory) { host.show("Memory") }
        batteryTile = ui.tile(color(R.color.surface), "Battery", R.drawable.ic_battery) { host.show("Battery") }
        val junk = ui.tile(color(R.color.surface), "Cleaner", R.drawable.ic_broom) { host.show("Cleaner") }
        junk.sub.text = "Clear caches, duplicates and big files"
        netTile = ui.tile(color(R.color.surface), "Network", R.drawable.ic_wifi) { host.show("Network") }
        val apps = ui.tile(color(R.color.surface), "Apps", R.drawable.ic_apps) { host.show("Apps") }
        apps.sub.text = "See what takes up space"
        val storage = ui.tile(color(R.color.surface), "Storage", R.drawable.ic_storage) { host.show("Storage") }
        storage.sub.text = "Folder sizes, largest first"
        val perms = ui.tile(color(R.color.surface), "Permissions", R.drawable.ic_key) { host.show("Permissions") }
        perms.sub.text = "Which apps can use camera, mic, location…"
        val hardware = ui.tile(color(R.color.surface), "Hardware", R.drawable.ic_tools) { host.show("Hardware") }
        hardware.sub.text = "Screen, sound, sensors and more"
        val saver = ui.tile(color(R.color.surface), "Battery saver", R.drawable.ic_leaf) { host.show("Saver") }
        saver.sub.text = "Deep sleep, charging alerts, app limits"
        val freezer = ui.tile(color(R.color.surface), "Freezer", R.drawable.ic_snow) { host.show("Freezer") }
        freezer.sub.text = "Stop apps you aren't using"
        val recover = ui.tile(color(R.color.surface), "Recover", R.drawable.ic_trash) { host.show("Recover") }
        recover.sub.text = "Deleted photos and files still on the phone"
        val fdroid = ui.tile(color(R.color.surface), "F-Droid", R.drawable.ic_bag) { host.show("F-Droid") }
        fdroid.sub.text = "Free and open-source apps"
        return ui.column {
            listOf(memTile, junk, batteryTile, saver, freezer, netTile, apps, storage, fdroid, recover, perms, hardware).chunked(2).forEachIndexed { r, pair ->
                addView(ui.row {
                    pair.forEachIndexed { i, t -> addView(t.box, ui.lp(w = 0, h = ui.dp(148), weight = 1f, start = if (i == 0) 0 else 12)) }
                    if (pair.size == 1) addView(ui.spacer(), ui.lp(w = 0, h = 1, weight = 1f, start = 12)) // odd count: keep the last tile half width
                }, ui.lp(top = if (r == 0) 0 else 12))
            }
        }
    }

    private fun footer() = ui.text(12f, color(R.color.ink_faint)) {
        gravity = Gravity.CENTER
        val version = try { host.packageManager.getPackageInfo(host.packageName, 0).versionName } catch (_: Exception) { null }
        text = "Andromon Companion" + (version?.let { " · v$it (build ${BuildConfig.BUILD_NUMBER})" } ?: "")
    }


    // ---------- state ----------

    private fun refresh() {
        val (score, issues) = Phone.health(host)
        greetSub.text = when {
            issues.isEmpty() -> "Your phone is in great shape."
            score >= 80 -> "Your phone is in good shape."
            else -> "Your phone could use a tidy-up."
        }
        healthGauge.ring.fraction = score / 100f
        healthGauge.value.text = "$score"
        healthGauge.sub.text = if (issues.isEmpty()) "No issues" else "${issues.size} to fix"
        healthGauge.box.contentDescription = "Phone health $score of 100. ${healthGauge.sub.text}"

        val mem = Phone.memoryUsed(host)
        memGauge.ring.fraction = mem
        memGauge.value.text = "${(mem * 100).toInt()}%"
        memGauge.sub.text = "RAM in use"
        memGauge.box.contentDescription = "Memory: ${(mem * 100).toInt()}% of RAM in use"
        memTile.sub.text = "${(mem * 100).toInt()}% of RAM in use"

        val b = Phone.battery(host)
        batteryGauge.ring.fraction = b.level / 100f
        batteryGauge.value.text = "${b.level}%"
        batteryGauge.sub.text = if (b.charging) "Charging" else b.healthText
        batteryGauge.box.contentDescription = "Battery ${b.level}%, ${batteryGauge.sub.text}"
        batteryTile.sub.text = "${b.level}% · " + if (b.charging) "charging" else b.healthText.lowercase()

        val net = NetTools.info(host)
        netTile.sub.text = if (net.connected) "${net.kind} · connected" else "Not connected"
    }

    /** The storage donut and legend, worked out off the main thread. */
    private fun loadStorage() {
        background({
            val main = Cleaner.volumes(host).first()
            main to StorageBreakdown.of(host, main.total, main.free)
        }) { r ->
            storageDonut.busy = false
            val (main, parts) = r.getOrNull() ?: return@background
            val total = main.total.coerceAtLeast(1)
            storageDonut.segments = parts.map { it.bytes.toFloat() / total to color(it.color) }
            storageUsed.text = "${((total - main.free) * 100 / total)}%"
            storageSub.text = "${fmtSize(main.free)} free of ${fmtSize(main.total)}"
            storageDonut.contentDescription = "Storage: " + parts.joinToString(", ") { "${it.name} ${fmtSize(it.bytes)}" } + ", free ${fmtSize(main.free)}"
            storageLegend.removeAllViews()
            (parts.map { Triple(it.name, it.bytes, color(it.color)) } + Triple("Free", main.free, color(R.color.chip))).forEachIndexed { i, (name, bytes, c) ->
                val (row, dot, text) = ui.dotLabel()
                dot.background = ui.circle(c)
                text.text = "$name · ${fmtSize(bytes)}"
                text.textSize = 13f
                storageLegend.addView(row, ui.lp(top = if (i == 0) 0 else 6))
            }
        }
    }

    /** The battery graph and the screen-time chart, read off the main thread. */
    private fun loadCharts() {
        background({
            val since = System.currentTimeMillis() - BatteryLog.DAY
            val samples = BatteryLog.read(host, BatteryLog.DAY).filter { it.time >= since }
            val usage = if (AppStats.hasUsageAccess(host)) runCatching { AppStats.usage(host, 7) }.getOrNull() else null
            samples to usage
        }) { r ->
            val (samples, usage) = r.getOrNull() ?: (emptyList<BatteryLog.Sample>() to null)
            // One point per 15 minutes from the first reading (at most 24 hours back) to now, carrying the
            // last reading forward over gaps, so the line spans the whole chart.
            val now = System.currentTimeMillis()
            val step = 15 * 60_000L
            val start = samples.firstOrNull()?.time ?: now
            val points = ArrayList<Float>()
            var j = 0; var last: Float? = null
            var t = start
            while (t <= now) {
                while (j < samples.size && samples[j].time <= t) { last = samples[j].level.toFloat(); j++ }
                last?.let { points += it }
                t += step
            }
            samples.lastOrNull()?.let { points += it.level.toFloat() }
            if (points.size == 1) points += points[0]
            batteryChart.slots = points.size.coerceAtLeast(2)
            batteryChart.values = points
            batteryFrom.text = if (now - start >= BatteryLog.DAY - step) "24 h ago"
                else "since " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(start))
            batterySub.text = if (samples.isEmpty()) "Recording; the graph fills in over the day"
                else samples.last().let { "${it.level}% now · lowest ${samples.minOf { s -> s.level }}%" }
            batteryChart.contentDescription = "Battery level over the last 24 hours: " + batterySub.text
            fillScreenTime(usage)
        }
    }
}
