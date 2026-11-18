// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.app.ActivityManager
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.TextView

/** Dashboard: a hero card that rotates through the phone's stats, storage at a glance and tiles for every tool and the PC link. */
internal class HomeScreen(host: MainActivity) : Screen(host) {

    override val name = "Home"
    override val navIcon = R.drawable.ic_home
    override val navColor = R.color.bg

    private lateinit var greetSub: TextView
    private lateinit var scoreText: TextView
    private lateinit var scoreSub: TextView
    private lateinit var ring: Ring
    private lateinit var ringText: TextView
    private lateinit var issueList: LinearLayout
    private lateinit var heroIcon: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroDots: TextView
    private lateinit var ringBox: FrameLayout
    private lateinit var ringLabel: TextView
    private var heroIndex = -1
    private var heroScreen = "Cleaner"
    private var appCounts: Pair<Int, Int>? = null // user apps, system apps (counted off the main thread)

    /** One reading the hero card can show: a headline figure, a line under it and, where it helps, a ring. */
    private class HeroStat(
        val icon: Int, val title: String, val value: CharSequence, val sub: String, val screen: String,
        val ring: Float? = null, val ringValue: CharSequence = "", val ringLabel: String = "",
        val issues: List<Phone.Issue> = emptyList(),
    )

    private lateinit var storageSub: TextView
    private lateinit var storageValues: List<TextView>

    private lateinit var memTile: Ui.Tile
    private lateinit var batteryTile: Ui.Tile
    private lateinit var netTile: Ui.Tile
    private lateinit var linkTile: Ui.Tile

    override fun build(): View = ui.column {
        addView(ui.pageTitle("Home"), ui.lp(top = 12))
        greetSub = ui.text(15f, color(R.color.ink_soft))
        addView(greetSub, ui.lp(top = 10))
        addView(healthCard(), ui.lp(top = 18))
        addView(storageCard(), ui.lp(top = 12))
        addView(tiles(), ui.lp(top = 12))
        addView(footer(), ui.lp(top = 24))
    }

    override fun onShow() {
        CompanionService.stateListener = ::refresh // keeps the PC link tile current
        heroIndex = -1 // a different reading on the hero card each time Home opens
        refresh()
        loadStorage()
        if (appCounts == null) background({
            val apps = host.packageManager.getInstalledApplications(0)
            val system = apps.count { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 }
            (apps.size - system) to system
        }) { r -> appCounts = r.getOrNull(); }
    }

    override fun onHide() {
        CompanionService.stateListener = null
    }

    override fun onPermissionsResult() = refresh()

    // ---------- sections ----------

    /**
     * The hero card. It shows one reading at a time from [heroStats], picked at random whenever Home
     * opens; a tap anywhere on it shows another, and the arrow opens the screen behind the reading.
     */
    private fun healthCard() = ui.heroCard(16) {
        addView(ui.row {
            gravity = Gravity.CENTER_VERTICAL
            heroIcon = ui.chipIcon(R.drawable.ic_shield)
            addView(heroIcon)
            heroTitle = ui.text(15f, color(R.color.ink), ui.medium) { maxLines = 1 }
            addView(heroTitle, ui.lp(w = 0, weight = 1f, start = 10))
            addView(ui.circleButton(R.drawable.ic_arrow_up_right, "Open", 36) { host.show(heroScreen) })
        })
        addView(ui.row {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(112) // same height with or without the ring, so the page doesn't jump
            addView(ui.column {
                scoreText = ui.text(50f, color(R.color.ink), ui.regular) { text = "–"; letterSpacing = -0.03f; maxLines = 1 }
                addView(scoreText)
                scoreSub = ui.text(13f, color(R.color.ink), ui.medium) { maxLines = 2 }
                addView(scoreSub, ui.lp(top = 8))
            }, ui.lp(w = 0, weight = 1f))
            ringBox = FrameLayout(context).apply {
                ring = Ring(context).apply {
                    strokeDp = 10f
                    colors(color(R.color.glass_strong), color(R.color.ink), color(R.color.ink))
                }
                addView(ring, FrameLayout.LayoutParams(-1, -1))
                addView(ui.column {
                    gravity = Gravity.CENTER
                    ringText = ui.text(20f, color(R.color.ink), ui.medium) { gravity = Gravity.CENTER; text = "–" }
                    addView(ringText, ui.lp(w = WRAP_CONTENT))
                    ringLabel = ui.text(11f, color(R.color.ink_soft)) { gravity = Gravity.CENTER }
                    addView(ringLabel, ui.lp(w = WRAP_CONTENT, top = 2))
                }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            }
            addView(ringBox, ui.lp(w = ui.dp(112), h = ui.dp(112), start = 8))
        }, ui.lp(top = 14))
        issueList = ui.column()
        addView(issueList)
        heroDots = ui.text(11f, color(R.color.ink_soft)) { gravity = Gravity.CENTER }
        addView(heroDots, ui.lp(top = 12))
        isClickable = true
        contentDescription = "Phone stats. Tap for another."
        setOnClickListener { heroIndex = -1; showHero() }
    }

    /** Every reading the hero card can show, from what the phone reports right now. */
    private fun heroStats(): List<HeroStat> {
        val out = mutableListOf<HeroStat>()
        fun size(bytes: Long) = sizeParts(bytes).let { (n, u) -> ui.withUnit(n, u) }
        val (total, free) = Phone.storage()
        val usedPct = ((total - free) * 100 / total).toInt()
        val (score, issues) = Phone.health(host)
        out += HeroStat(R.drawable.ic_shield, "Phone health", ui.percent(score), when (issues.size) {
            0 -> "All good · no issues found"
            1 -> "1 thing to check"
            else -> "${issues.size} things to check"
        }, issues.firstOrNull()?.screen ?: "Cleaner", usedPct / 100f, ui.percent(usedPct), "Storage used", issues)
        out += HeroStat(R.drawable.ic_sd, "Storage used", ui.percent(usedPct), "${fmtSize(total - free)} of ${fmtSize(total)}",
            "Storage", usedPct / 100f, size(free), "free")
        out += HeroStat(R.drawable.ic_storage, "Free space", size(free), "left on internal storage", "Cleaner",
            free.toFloat() / total, ui.percent(100 - usedPct), "free")

        val mem = ActivityManager.MemoryInfo().also { host.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val memPct = ((mem.totalMem - mem.availMem) * 100 / mem.totalMem).toInt()
        out += HeroStat(R.drawable.ic_memory, "Memory in use", ui.percent(memPct),
            "${fmtSize(mem.totalMem - mem.availMem)} of ${fmtSize(mem.totalMem)}", "Memory", memPct / 100f, size(mem.availMem), "free")
        out += HeroStat(R.drawable.ic_memory, "Free memory", size(mem.availMem),
            if (mem.lowMemory) "Android says memory is low" else "available to apps right now", "Memory",
            mem.availMem.toFloat() / mem.totalMem, ui.percent(100 - memPct), "free")

        val b = Phone.battery(host)
        val power = if (b.charging) "Charging" + (b.plugged?.let { " · $it" } ?: "") else "On battery"
        out += HeroStat(R.drawable.ic_battery, "Battery", ui.percent(b.level), power, "Battery", b.level / 100f,
            b.tempC?.let { ui.withUnit("%.0f".format(it), "°C") } ?: b.healthText, if (b.tempC != null) "temperature" else "condition")
        b.tempC?.let { t ->
            out += HeroStat(R.drawable.ic_thermo, "Battery temperature", ui.withUnit("%.1f".format(t), "°C"),
                if (b.hot) "Hot · let the phone cool down" else "Normal", "Battery", (t / 50f).coerceIn(0f, 1f), ui.percent(b.level), "charge")
        }
        out += HeroStat(R.drawable.ic_leaf, "Battery condition", b.healthText,
            b.cycles?.let { "$it charge cycles" } ?: "as Android reports it", "Battery", b.level / 100f, ui.percent(b.level), "charge")
        b.voltageMv?.let { mv ->
            out += HeroStat(R.drawable.ic_bolt, "Battery voltage", ui.withUnit("%.2f".format(mv / 1000f), "V"),
                listOfNotNull(b.technology, power).joinToString(" · "), "Battery")
        }

        val up = SystemClock.elapsedRealtime() / 60_000
        val upText = when {
            up >= 1440 -> ui.withUnit("${up / 1440}d ${up % 1440 / 60}h", "")
            up >= 60 -> ui.withUnit("${up / 60}h ${up % 60}m", "")
            else -> ui.withUnit("$up", "min")
        }
        out += HeroStat(R.drawable.ic_timer, "Uptime", upText,
            if (up >= 7 * 1440) "since the last restart · a restart can help" else "since the last restart", "Memory")

        val net = NetTools.info(host)
        out += HeroStat(net.icon, "Network", net.kind, if (net.connected) net.detail else "Not connected", "Network")
        out += HeroStat(R.drawable.ic_desktop, "PC link", if (CompanionService.running) "Ready" else "Paused",
            if (CompanionService.running) "Andromon can connect over USB or Wi‑Fi" else "Turn it on to use Andromon", "PC link")
        appCounts?.let { (user, system) ->
            out += HeroStat(R.drawable.ic_apps, "Installed apps", ui.withUnit("$user", "apps"), "plus $system that came with the phone", "Apps")
        }
        @Suppress("DEPRECATION") val mode = host.windowManager.defaultDisplay.mode
        out += HeroStat(R.drawable.ic_image, "Screen", "${mode.physicalWidth}×${mode.physicalHeight}",
            "%.0f Hz · %d dpi".format(mode.refreshRate, host.resources.displayMetrics.densityDpi), "Hardware")
        out += HeroStat(R.drawable.ic_info, "Android", ui.withUnit(Build.VERSION.RELEASE, "API ${Build.VERSION.SDK_INT}"),
            Build.VERSION.SECURITY_PATCH.takeIf { it.isNotBlank() }?.let { "Security patch $it" } ?: Build.MODEL, "Permissions")
        return out
    }

    /** Fills the hero card: keeps the current reading (with fresh numbers), or picks a new one at random. */
    private fun showHero() {
        val stats = heroStats()
        val shown = heroTitle.text.toString()
        var i = stats.indexOfFirst { it.title == shown }.takeIf { heroIndex >= 0 } ?: -1
        if (i < 0) {
            val others = stats.indices.filter { stats[it].title != shown }
            i = others.random()
        }
        heroIndex = i
        val s = stats[i]
        heroScreen = s.screen
        heroIcon.setImageDrawable(ui.icon(s.icon, color(R.color.ink)))
        heroTitle.text = s.title
        scoreText.textSize = when { s.value.length <= 6 -> 50f; s.value.length <= 9 -> 40f; else -> 30f }
        scoreText.text = s.value
        scoreSub.text = s.sub
        ringBox.visibility = if (s.ring == null) View.GONE else View.VISIBLE
        s.ring?.let { ring.fraction = it; ringText.text = s.ringValue; ringLabel.text = s.ringLabel }
        issueList.removeAllViews()
        for (issue in s.issues.take(3)) {
            val (row, value) = ui.listPill(R.drawable.ic_warning, issue.text) { host.show(issue.screen) }
            value.text = if (issue.screen == "Cleaner") "Clean" else "Open"
            issueList.addView(row, ui.lp(top = 10))
        }
        heroDots.text = "${i + 1} of ${stats.size} · tap for another"
    }

    /** Salmon "Breakfast" card: storage split into numbers. */
    private fun storageCard() = ui.card(color(R.color.surface), 16) {
        val (head, sub) = ui.cardHeader(R.drawable.ic_sd, "Storage") {
            addView(ui.circleButton(R.drawable.ic_arrow_right, "Open Junk Cleaner", 36) { host.show("Cleaner") })
        }
        storageSub = sub
        addView(head)
        val (nums, values) = ui.numbers(listOf("Apps", "Media", "Docs", "Free"))
        storageValues = values
        addView(nums, ui.lp(top = 20))
        addView(ui.row {
            addView(ui.text(13f, color(R.color.ink), ui.medium) { text = "Internal storage" }, ui.lp(w = 0, weight = 1f))
            addView(ui.text(12f, color(R.color.ink_soft)) { text = "See details" }, ui.lp(w = WRAP_CONTENT))
        }, ui.lp(top = 20))
    }

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
        linkTile = ui.tile(color(R.color.surface), "PC link", R.drawable.ic_desktop) { host.show("PC link") }
        val freezer = ui.tile(color(R.color.surface), "Freezer", R.drawable.ic_snow) { host.show("Freezer") }
        freezer.sub.text = "Stop apps you aren't using"
        val recover = ui.tile(color(R.color.surface), "Recover", R.drawable.ic_trash) { host.show("Recover") }
        recover.sub.text = "Deleted photos and files still on the phone"
        return ui.column {
            listOf(linkTile, memTile, junk, batteryTile, freezer, netTile, apps, storage, recover, perms, hardware).chunked(2).forEachIndexed { r, pair ->
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
        text = "Andromon Companion" + (version?.let { " · v$it" } ?: "")
    }

    // ---------- state ----------

    private fun refresh() {
        linkTile.sub.text = if (CompanionService.running) "Ready · USB or Wi‑Fi" else "Paused · tap to turn on"

        val (score, issues) = Phone.health(host)
        greetSub.text = when {
            issues.isEmpty() -> "Your phone is in great shape."
            score >= 80 -> "Your phone is in good shape."
            else -> "Your phone could use a tidy-up."
        }
        showHero()

        memTile.sub.text = "${(Phone.memoryUsed(host) * 100).toInt()}% of RAM in use"
        val b = Phone.battery(host)
        batteryTile.sub.text = "${b.level}% · " + if (b.charging) "charging" else b.healthText.lowercase()
        val net = NetTools.info(host)
        netTile.sub.text = if (net.connected) "${net.kind} · connected" else "Not connected"
    }

    /** Apps / media / docs / free, worked out off the main thread. */
    private fun loadStorage() {
        background({
            val main = Cleaner.volumes(host).first()
            main to StorageBreakdown.of(host, main.total, main.free)
        }) { r ->
            val (main, parts) = r.getOrNull() ?: return@background
            fun bytes(name: String) = parts.firstOrNull { it.name == name }?.bytes
            fun show(v: TextView, b: Long?) {
                v.text = if (b == null) "–" else sizeParts(b).let { (n, u) -> ui.withUnit(n, u) }
            }
            show(storageValues[0], bytes("Apps"))
            show(storageValues[1], listOfNotNull(bytes("Photos & videos"), bytes("Audio")).takeIf { it.isNotEmpty() }?.sum())
            show(storageValues[2], bytes("Documents"))
            show(storageValues[3], main.free)
            storageSub.text = "${fmtSize(main.free)} free of ${fmtSize(main.total)}"
        }
    }
}
