// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.TextView
import java.time.format.TextStyle
import java.util.Locale

/**
 * Stats: how apps use the phone, in five segments. Usage (screen time and opens), Data (mobile
 * and Wi‑Fi per app), Battery (use since the last full charge), Storage (app, data and cache size)
 * and PC link (what the link to Andromon did). Each has a hero figure, a bar chart and a list of
 * apps that opens the app's details. Figures come from [AppStats] and [LinkStats].
 */
internal class StatsScreen(host: MainActivity) : Screen(host) {

    override val name = "Stats"
    override val navIcon = R.drawable.ic_chart
    override val navColor = R.color.bg

    private enum class Segment(val label: String, val ranged: Boolean) {
        USAGE("Usage", true), DATA("Data", true), BATTERY("Battery", false), STORAGE("Storage", false), LINK("PC link", true)
    }

    private var segment = Segment.USAGE
    private var week = false
    private var token = 0
    private var showAll = false

    private lateinit var chips: Map<Segment, TextView>
    private lateinit var chipScroll: HorizontalScrollView
    private lateinit var rangeBox: View
    private lateinit var selectRange: (Int) -> Unit
    private lateinit var content: LinearLayout
    private lateinit var loading: TextView

    companion object {
        /** Segment to show next time Stats opens (the PC link screen's summary opens "PC link"). */
        var pending: String? = null
    }

    override fun build(): View = ui.column {
        addView(ui.topBar("Stats", host::goHome, R.drawable.ic_refresh, "Refresh") { load() }.first)
        addView(ui.subtitle("How apps use this phone, from Android's own records"), ui.lp(top = 6))
        val strip = ui.row {
            chips = Segment.values().associateWith { s ->
                ui.smallPill(s.label) { pick(s) }.apply { minHeight = ui.dp(40); textSize = 14f }
            }
            chips.values.forEachIndexed { i, c -> addView(c, ui.lp(w = WRAP_CONTENT, start = if (i == 0) 0 else 8)) }
        }
        chipScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
        addView(chipScroll, ui.lp(top = 20))
        val (range, select) = segmented(ui, listOf("Today", "7 days")) { i ->
            week = i == 1
            Prefs.setStatsWeek(host, week)
            selectRange(i)
            load()
        }
        rangeBox = range; selectRange = select
        addView(range, ui.lp(top = 12))
        loading = ui.text(13f, color(R.color.ink_soft)) { text = "Loading…"; visibility = View.GONE }
        addView(loading, ui.lp(top = 12))
        content = ui.column()
        addView(content)
    }

    override fun onShow() {
        view
        val saved = pending?.let { p -> Segment.values().firstOrNull { it.label == p } }
        pending = null
        segment = saved ?: Segment.values().getOrElse(Prefs.statsSegment(host)) { Segment.USAGE }
        week = Prefs.statsWeek(host)
        selectRange(if (week) 1 else 0)
        styleChips()
        load()
    }

    override fun onReselect() { pick(Segment.USAGE) }

    private fun pick(s: Segment) {
        if (s == segment && content.childCount > 0) return
        segment = s
        showAll = false
        Prefs.setStatsSegment(host, s.ordinal)
        styleChips()
        load()
    }

    private fun styleChips() {
        chips.forEach { (s, chip) ->
            ui.stylePill(chip, s.label, color(if (s == segment) R.color.fab else R.color.glass_strong),
                color(if (s == segment) R.color.on_fab else R.color.ink)) { pick(s) }
            chip.contentDescription = if (s == segment) "${s.label}, selected" else s.label
        }
        rangeBox.visibility = if (segment.ranged) View.VISIBLE else View.GONE
        // The five segments are wider than a phone: keep the chosen one in view.
        chips[segment]?.let { c -> chipScroll.post { chipScroll.smoothScrollTo((c.left - ui.dp(40)).coerceAtLeast(0), 0) } }
    }

    // ---------- loading ----------

    private fun load() {
        val my = ++token
        val s = segment
        val days = if (week) 7 else 1
        loading.visibility = View.VISIBLE
        content.alpha = 0.5f
        if (s != Segment.LINK && s != Segment.BATTERY && !AppStats.hasUsageAccess(host)) {
            show(my) { needsUsageAccess() }
            return
        }
        background({
            when (s) {
                Segment.USAGE -> AppStats.usage(host, days)
                Segment.DATA -> AppStats.data(host, days)
                Segment.BATTERY -> AppStats.battery(host)
                Segment.STORAGE -> AppStats.storage(host)
                Segment.LINK -> LinkStats.days(host, 7)
            }
        }) { r ->
            if (my != token) return@background
            val v = r.getOrNull()
            show(my) {
                when {
                    v == null -> failed(r.exceptionOrNull())
                    s == Segment.USAGE -> usage(v as AppStats.Usage)
                    s == Segment.DATA -> data(v as AppStats.Data)
                    s == Segment.BATTERY -> battery(v as AppStats.Battery)
                    s == Segment.STORAGE -> storage(v as AppStats.Storage)
                    else -> {
                        @Suppress("UNCHECKED_CAST")
                        link(v as List<LinkStats.Day>)
                    }
                }
            }
        }
    }

    private fun show(my: Int, fill: LinearLayout.() -> Unit) {
        if (my != token) return
        loading.visibility = View.GONE
        content.alpha = 1f
        content.removeAllViews()
        content.fill()
    }

    // ---------- shared parts ----------

    /** The hero card: what is measured, the figure, and a line under it. */
    private fun LinearLayout.hero(icon: Int, label: String, figure: CharSequence, sub: String) {
        addView(ui.heroCard(18) {
            addView(ui.row {
                addView(ui.chipIcon(icon))
                addView(ui.text(15f, color(R.color.ink), ui.medium) { text = label; maxLines = 2 }, ui.lp(w = 0, weight = 1f, start = 10))
            })
            addView(ui.text(if (figure.length > 9) 36f else 44f, color(R.color.ink), ui.regular) {
                text = figure; letterSpacing = -0.03f; maxLines = 1
            }, ui.lp(top = 14))
            addView(ui.text(13f, color(R.color.ink), ui.medium) { text = sub; setLineSpacing(ui.dp(2).toFloat(), 1f) }, ui.lp(top = 8))
        }, ui.lp(top = 20))
    }

    /** A card with a bar chart: title, a line or legend under it, the bars. */
    private fun LinearLayout.chart(title: String, sub: String, bars: List<List<Float>>, labels: List<String>, highlight: Int,
                                   stack: List<Int>, legend: List<Pair<String, Int>> = emptyList(), describe: String) {
        addView(ui.glassCard(16) {
            val (head, s) = ui.cardHeader(R.drawable.ic_chart, title)
            s.text = sub; s.maxLines = 2
            addView(head)
            if (legend.isNotEmpty()) addView(ui.row {
                legend.forEachIndexed { i, (name, c) ->
                    val (row, dot, text) = ui.dotLabel()
                    dot.background = ui.circle(c); text.text = name; text.textSize = 13f
                    addView(row, ui.lp(w = WRAP_CONTENT, start = if (i == 0) 0 else 16))
                }
            }, ui.lp(top = 12))
            addView(BarChart(context).apply {
                colors(stack, color(R.color.chip), color(R.color.ink_soft), color(R.color.ink), color(R.color.line), ui.regular)
                this.bars = bars; this.labels = labels; this.highlight = highlight
                contentDescription = describe
            }, ui.lp(h = ui.dp(150), top = 16))
        }, ui.lp(top = 12))
    }

    /** Section heading and the list card; long lists show 15 rows and a "Show all" pill. */
    private fun LinearLayout.list(title: String, rows: List<AppStats.Row>, note: String?, row: (AppStats.Row) -> View) {
        addView(ui.section(title) {
            addView(ui.text(14f, color(R.color.ink_soft)) { text = plural(rows.size, "item") }, ui.lp(w = WRAP_CONTENT))
        }, ui.lp(top = 26))
        addView(ui.glassCard(6) {
            if (rows.isEmpty()) UiRows(host, ui).note(this, "Nothing recorded in this time.")
            val shown = if (showAll) rows.take(300) else rows.take(15)
            shown.forEach { addView(row(it)) }
            if (rows.size > shown.size) addView(ui.smallPill("Show all ${rows.size}") { showAll = true; load() },
                ui.lp(w = WRAP_CONTENT, top = 4).apply { marginStart = ui.dp(10); bottomMargin = ui.dp(8) })
            if (note != null) UiRows(host, ui).note(this, note)
        }, ui.lp(top = 12))
    }

    private fun openApp(pkg: String?): (() -> Unit)? = pkg?.let { { host.showAppDetails(it) } }

    private fun pct(v: Double, total: Double) = if (total > 0) "${Math.round(v / total * 100)}%" else "0%"

    private fun plural(n: Int, noun: String) = "%,d %s".format(n, noun) + if (n == 1) "" else "s"

    private fun weekdays(dates: List<java.time.LocalDate>) =
        dates.map { it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }

    private fun range() = if (week) "last 7 days" else "today"

    // ---------- segments ----------

    private fun LinearLayout.usage(u: AppStats.Usage) {
        val top = u.apps.firstOrNull()
        hero(R.drawable.ic_timer, "Screen time " + range(), duration(u.totalMs),
            listOfNotNull(plural(u.opens, "open"), top?.let { "most used: ${it.label}" },
                if (week) "about ${duration(u.totalMs / 7)} a day" else null).joinToString(" · "))
        val perDay = u.daily.map { it.values[0] }
        chart("Last 7 days", "Screen time per day · average ${duration((perDay.sum() / 7).toLong())}",
            u.daily.map { listOf((it.values[0] / 60_000).toFloat()) }, weekdays(u.daily.map { it.date }), 6,
            listOf(color(R.color.green_dot)), describe = "Screen time per day, last 7 days: " +
                u.daily.joinToString(", ") { "${it.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${duration(it.values[0].toLong())}" })
        list("Apps", u.apps, "From Android's usage records. The home screen and the system bars aren't counted; an open is an app coming to the front.") { r ->
            val opens = r.extra["opens"] as Int
            val last = r.extra["lastUsed"] as Long
            ui.shareRow(r.icon, R.drawable.ic_apps, r.label, duration(r.value.toLong()), (r.value / u.totalMs.coerceAtLeast(1)).toFloat(),
                listOfNotNull(pct(r.value, u.totalMs.toDouble()), plural(opens, "open"), if (last > 0) "used ${ago(last)}" else null).joinToString(" · "),
                openApp(r.pkg))
        }
    }

    private fun LinearLayout.data(d: AppStats.Data) {
        val total = (d.mobile + d.wifi).toDouble()
        hero(R.drawable.ic_swap, "Data used " + range(), fmtSize(d.mobile + d.wifi),
            "Mobile ${fmtSize(d.mobile)} · Wi‑Fi ${fmtSize(d.wifi)}")
        val mobileC = color(R.color.cat_blue); val wifiC = color(R.color.green_dot)
        chart("Last 7 days", "Data per day, mobile and Wi‑Fi",
            d.daily.map { b -> b.values.map { (it / 1e6).toFloat() } }, weekdays(d.daily.map { it.date }), 6,
            listOf(mobileC, wifiC), listOf("Mobile" to mobileC, "Wi‑Fi" to wifiC),
            "Data per day, last 7 days: " + d.daily.joinToString(", ") { "${it.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${fmtSize(it.values.sum().toLong())}" })
        list("Apps", d.apps, listOfNotNull(d.mobileProblem,
            "Bytes sent and received, as Android counts them. Android keeps them in 2-hour blocks, so Today can include a little from late yesterday.")
            .joinToString(" ")) { r ->
            ui.shareRow(r.icon, if (r.pkg == null) R.drawable.ic_info else R.drawable.ic_apps, r.label, fmtSize(r.value.toLong()), (r.value / total.coerceAtLeast(1.0)).toFloat(),
                "${pct(r.value, total)} · mobile ${fmtSize(r.extra["mobile"] as Long)} · Wi‑Fi ${fmtSize(r.extra["wifi"] as Long)}", openApp(r.pkg))
        }
    }

    private fun mah(v: Double) = if (v < 1) "under 1 mAh" else "%,.0f mAh".format(v)

    private fun LinearLayout.battery(b: AppStats.Battery) {
        if (b.source == "none") {
            hero(R.drawable.ic_battery, "Battery use per app", "–", "Not available on this phone right now")
            val (card, _) = ui.emptyCard(R.drawable.ic_battery, b.reason ?: "Android didn't share battery use per app.",
                "Set up privileged access") { host.showSettings("privileged") }
            addView(card, ui.lp(top = 12))
            return
        }
        val total = b.totalMah ?: b.apps.sumOf { it.value }
        hero(R.drawable.ic_battery, "Battery use since the last full charge", mah(total),
            listOfNotNull("Android's estimate", b.since?.let { "since ${ago(it)}" }, b.apps.firstOrNull()?.let { "most: ${it.label}" }).joinToString(" · "))
        val top = b.apps.take(7)
        chart("Top apps", "Numbered as in the list below", top.map { listOf(it.value.toFloat()) }, top.indices.map { "${it + 1}" }, -1,
            listOf(color(R.color.green_dot)), describe = "Battery use of the top apps: " + top.joinToString(", ") { "${it.label} ${mah(it.value)}" })
        list("Apps", b.apps, if (b.source == "batterystats") "From Android's battery statistics, the same estimate as Settings › Battery."
            else "From Android's battery statistics, read through privileged access.") { r ->
            val i = b.apps.indexOf(r)
            ui.shareRow(r.icon, if (r.pkg == null) R.drawable.ic_info else R.drawable.ic_apps,
                (if (i in 0..6) "${i + 1}. " else "") + r.label, mah(r.value), (r.value / total.coerceAtLeast(0.1)).toFloat(),
                "${pct(r.value, total)} of the total", openApp(r.pkg))
        }
    }

    private fun LinearLayout.storage(s: AppStats.Storage) {
        val total = (s.appBytes + s.dataBytes + s.cacheBytes).toDouble()
        hero(R.drawable.ic_storage, "Apps take up", fmtSize(total.toLong()),
            "${plural(s.apps.size, "app")} · apps ${fmtSize(s.appBytes)} · data ${fmtSize(s.dataBytes)} · cache ${fmtSize(s.cacheBytes)}")
        val top = s.apps.take(7)
        val appC = color(R.color.cat_blue); val dataC = color(R.color.green_dot); val cacheC = color(R.color.cat_yellow)
        chart("Biggest apps", "Numbered as in the list below", top.map { r ->
            listOf((r.extra["app"] as Long / 1e6).toFloat(), (r.extra["data"] as Long / 1e6).toFloat(), (r.extra["cache"] as Long / 1e6).toFloat())
        }, top.indices.map { "${it + 1}" }, -1, listOf(appC, dataC, cacheC), listOf("App" to appC, "Data" to dataC, "Cache" to cacheC),
            "Biggest apps: " + top.joinToString(", ") { "${it.label} ${fmtSize(it.value.toLong())}" })
        list("Apps", s.apps, "App, data and cache size as Android counts them. Caches can be cleared from Andromon on your computer (Apps › Clear app caches).") { r ->
            val i = s.apps.indexOf(r)
            ui.shareRow(r.icon, R.drawable.ic_apps, (if (i in 0..6) "${i + 1}. " else "") + r.label, fmtSize(r.value.toLong()),
                (r.value / total.coerceAtLeast(1.0)).toFloat(),
                "App ${fmtSize(r.extra["app"] as Long)} · data ${fmtSize(r.extra["data"] as Long)} · cache ${fmtSize(r.extra["cache"] as Long)}",
                openApp(r.pkg))
        }
    }

    private fun LinearLayout.link(days: List<LinkStats.Day>) {
        val range = if (week) days else days.takeLast(1)
        val linked = range.sumOf { it.linkedMs }
        val calls = HashMap<String, Int>()
        range.forEach { d -> d.calls.forEach { (k, v) -> calls[k] = (calls[k] ?: 0) + v } }
        val callTotal = calls.values.sum()
        hero(R.drawable.ic_desktop, "Linked to a computer " + range(), duration(linked),
            listOf(plural(range.sumOf { it.sessions }, "session"), plural(callTotal, "request"),
                if (LinkStats.linkedNow()) "linked now" else "not linked now").joinToString(" · "))
        chart("Last 7 days", "Time linked per day", days.map { listOf((it.linkedMs / 60_000f)) },
            weekdays(days.map { java.time.LocalDate.parse(it.date) }), 6, listOf(color(R.color.green_dot)),
            describe = "Time linked per day, last 7 days: " + days.joinToString(", ") { "${it.date} ${duration(it.linkedMs)}" })

        // Requests by kind, merged by their label (sms and mms are both "Messages").
        val byLabel = LinkedHashMap<String, Int>()
        calls.entries.sortedByDescending { it.value }.forEach { (k, v) ->
            val label = LinkStats.KINDS[k] ?: k
            byLabel[label] = (byLabel[label] ?: 0) + v
        }
        addView(ui.section("Requests by kind") {
            addView(ui.text(14f, color(R.color.ink_soft)) { text = "%,d".format(callTotal) }, ui.lp(w = WRAP_CONTENT))
        }, ui.lp(top = 26))
        addView(ui.glassCard(6) {
            if (byLabel.isEmpty()) UiRows(host, ui).note(this, "No requests from a computer in this time.")
            byLabel.entries.sortedByDescending { it.value }.forEach { (label, n) ->
                addView(ui.shareRow(null, kindIcon(label), label, "%,d".format(n), n / callTotal.coerceAtLeast(1).toFloat(),
                    pct(n.toDouble(), callTotal.toDouble()) + " of requests", null))
            }
        }, ui.lp(top = 12))

        addView(ui.section("Files and clipboard"), ui.lp(top = 26))
        addView(ui.glassCard(16) {
            val rows = listOf(
                "Files sent over Wi‑Fi" to "${range.sumOf { it.filesSent }} · ${fmtSize(range.sumOf { it.bytesSent })}",
                "Files received over Wi‑Fi" to "${range.sumOf { it.filesReceived }} · ${fmtSize(range.sumOf { it.bytesReceived })}",
                "Clipboard sent to the computer" to "${range.sumOf { it.clipToComputer }}",
                "Clipboard from the computer" to "${range.sumOf { it.calls["clip"] ?: 0 }}",
                "Links and text from the computer" to "${range.sumOf { it.calls["push"] ?: 0 }}",
            )
            rows.forEachIndexed { i, (k, v) ->
                val (row, value) = ui.keyValue(k); value.text = v
                addView(row, ui.lp(top = if (i == 0) 0 else 12))
            }
        }, ui.lp(top = 12))

        val computers = LinkStats.computers(host)
        addView(ui.section("Computers"), ui.lp(top = 26))
        addView(ui.glassCard(6) {
            if (computers.isEmpty()) UiRows(host, ui).note(this, "No computer has said its name yet. Andromon on your computer does when it connects.")
            computers.forEach { c ->
                val r = UiRows(host, ui).value(this, R.drawable.ic_desktop, c.optString("name"),
                    listOf(c.optString("os"), c.optString("app"), "last seen ${ago(c.optLong("last"))}").filter { it.isNotBlank() }.joinToString(" · "), null)
                r.value?.text = ""
            }
            UiRows(host, ui).note(this, "Kept on this phone for 30 days. Settings › PC link › Clear link history removes it.")
        }, ui.lp(top = 12))
    }

    private fun kindIcon(label: String) = when (label) {
        "Messages" -> R.drawable.ic_sms
        "Contacts" -> R.drawable.ic_person
        "Call log", "Calls" -> R.drawable.ic_call
        "Calendar" -> R.drawable.ic_timer
        "Photos and music" -> R.drawable.ic_image
        "Notifications" -> R.drawable.ic_bell
        "App icons" -> R.drawable.ic_apps
        "Clipboard", "Links and text" -> R.drawable.ic_clipboard
        "Find my phone" -> R.drawable.ic_volume
        "Media controls" -> R.drawable.ic_music
        "Battery" -> R.drawable.ic_battery
        "Statistics" -> R.drawable.ic_chart
        else -> R.drawable.ic_desktop
    }

    // ---------- states ----------

    private fun LinearLayout.needsUsageAccess() {
        val (card, _) = ui.emptyCard(R.drawable.ic_lock,
            "Usage access is off. Android needs it before an app can show screen time, data use and app sizes. " +
                "Andromon turns it on when it installs this app; you can also allow it yourself.",
            "Allow usage access") { openUsageAccess() }
        addView(card, ui.lp(top = 20))
    }

    private fun LinearLayout.failed(e: Throwable?) {
        val (card, _) = ui.emptyCard(R.drawable.ic_warning,
            "Couldn't read these figures" + (e?.message?.let { ": $it" } ?: ".") + " Try again with the refresh button.",
            "Try again") { load() }
        addView(card, ui.lp(top = 20))
    }

    private fun openUsageAccess() {
        val pkg = Uri.fromParts("package", host.packageName, null)
        for (i in listOf(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkg), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))) {
            if (runCatching { host.startActivity(i) }.isSuccess) return
        }
    }

}
