// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.TextView
import kotlin.math.abs

/**
 * Tools (the bottom bar's middle item; "Home" in earlier versions): a hero card that rotates
 * through the phone's readings, storage at a glance and a tile for every tool.
 */
internal class ToolsScreen(host: MainActivity) : Screen(host) {

    private companion object {
        /** How long the hero card shows one reading before moving on to the next. */
        const val HERO_STEP_MS = 10_000L
    }

    override val name = "Tools"
    override val navIcon = R.drawable.ic_grid
    override val navColor = R.color.bg

    private lateinit var greetSub: TextView
    private lateinit var heroCard: View
    private lateinit var heroStage: FrameLayout
    private lateinit var heroDots: LinearLayout
    /** The reading on the hero card, by position and by name (the list can grow or shrink between refreshes). */
    private var heroIndex = 0
    private var heroTitle: String? = null
    private var heroAnim: ValueAnimator? = null
    private var heroVisible = false
    private var heroTouched = false
    private var appCounts: Pair<Int, Int>? = null // user apps, system apps (counted off the main thread)

    /** One reading the hero card can show: a headline figure, a line under it and, where it helps, a ring. */
    private class HeroStat(
        /** The screen the arrow opens, or null when no screen says more about this reading. */
        val icon: Int, val title: String, val value: CharSequence, val sub: String, val screen: String?,
        val ring: Float? = null, val ringValue: CharSequence = "", val ringLabel: String = "",
        val issues: List<Phone.Issue> = emptyList(),
    )

    private lateinit var storageSub: TextView
    private lateinit var storageValues: List<TextView>

    private lateinit var memTile: Ui.Tile
    private lateinit var batteryTile: Ui.Tile
    private lateinit var netTile: Ui.Tile

    override fun build(): View = ui.column {
        addView(ui.topBar("Tools", host::goHome).first)
        greetSub = ui.subtitle()
        addView(greetSub, ui.lp(top = 6))
        heroCard = healthCard()
        addView(heroCard, ui.lp(top = 20))
        addView(storageCard(), ui.lp(top = 12))
        addView(ui.section("All tools"), ui.lp(top = 26))
        addView(tiles(), ui.lp(top = 12))
        addView(footer(), ui.lp(top = 24))
    }

    override fun onShow() {
        heroVisible = true
        refresh()
        loadStorage()
        // Counted like the Apps screen counts them, so both say the same number.
        if (appCounts == null) background({ AppTools.countApps(host) }) { r -> appCounts = r.getOrNull() }
    }

    override fun onPermissionsResult() = refresh()

    // ---------- sections ----------

    /**
     * The hero card. It shows one reading at a time from [heroStats], moving on to the next every
     * [HERO_STEP_MS] like a carousel: the reading slides out to the left and the next one in from the
     * right while the card stays put. A swipe goes forward or back, a tap goes forward, and the arrow
     * opens the screen behind the reading. It holds still while touched, while Tools is hidden and
     * while a screen reader is exploring by touch; with reduce motion on, readings change in place.
     */
    private fun healthCard(): View = HeroFrame().apply {
        background = ui.hero(26)
        clipToOutline = true // pages slide out under the rounded corners
        addView(ui.column {
            heroStage = FrameLayout(context)
            addView(heroStage)
            heroDots = ui.row { gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            addView(heroDots, ui.lp(h = ui.dp(6), top = 14))
            addView(ui.spacer(), ui.lp(h = ui.dp(16)))
        })
        isClickable = true
        setOnClickListener { stepHero(+1) }
    }

    /** The hero card's frame: watches every touch to hold the carousel still, and turns a sideways drag into a swipe. */
    private inner class HeroFrame : FrameLayout(host) {
        private val slop = ViewConfiguration.get(host).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var dragging = false

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { heroTouched = true; scheduleHero() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { heroTouched = false; scheduleHero() }
            }
            return super.dispatchTouchEvent(e)
        }

        /** True once the finger has moved sideways more than it has up or down. */
        private fun track(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragging = false }
                MotionEvent.ACTION_MOVE -> if (!dragging) {
                    val dx = abs(e.x - downX); val dy = abs(e.y - downY)
                    if (dx > slop && dx > dy) { dragging = true; parent?.requestDisallowInterceptTouchEvent(true) }
                }
            }
            return dragging
        }

        override fun onInterceptTouchEvent(e: MotionEvent) = track(e)

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val wasDragging = dragging
            if (!track(e)) return super.onTouchEvent(e)
            if (!wasDragging) super.onTouchEvent(MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL })
            val dx = e.x - downX
            val page = heroStage.getChildAt(heroStage.childCount - 1)
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (!Prefs.reduceMotion(host) && heroAnim == null) page?.translationX = dx
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    val far = e.actionMasked == MotionEvent.ACTION_UP && abs(dx) > ui.dp(48)
                    if (far) stepHero(if (dx < 0) +1 else -1)
                    else page?.animate()?.translationX(0f)?.setDuration(180)?.start()
                }
            }
            return true
        }
    }

    private val heroHandler = Handler(Looper.getMainLooper())
    private val heroTick = Runnable { stepHero(+1) }

    /** (Re)starts the 10-second wait for the next reading, or stops it while the carousel should hold still. */
    private fun scheduleHero() {
        heroHandler.removeCallbacks(heroTick)
        val exploring = host.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        if (heroVisible && !heroTouched && !exploring) heroHandler.postDelayed(heroTick, HERO_STEP_MS)
    }

    override fun onHide() {
        heroVisible = false
        scheduleHero()
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
        }, issues.firstOrNull()?.screen ?: "Cleaner", usedPct / 100f, ui.percent(usedPct), "storage used", issues)
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
            if (up >= 7 * 1440) "since the last restart · a restart can help" else "since the last restart", null)

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
            Build.VERSION.SECURITY_PATCH.takeIf { it.isNotBlank() }?.let { "Security patch $it" } ?: Build.MODEL, null)
        return out
    }

    /** Shows the reading [step] places on from the current one (0 refreshes its numbers in place), sliding unless reduce motion is on. */
    private fun stepHero(step: Int) {
        val stats = heroStats()
        val at = stats.indexOfFirst { it.title == heroTitle }.takeIf { it >= 0 } ?: heroIndex.coerceIn(0, stats.size - 1)
        val i = Math.floorMod(at + step, stats.size)
        heroIndex = i
        val s = stats[i]
        heroTitle = s.title
        val page = heroPage(s, animateRing = step != 0)
        val old = heroStage.getChildAt(heroStage.childCount - 1)
        heroAnim?.end()
        if (old == null || step == 0 || Prefs.reduceMotion(host) || heroStage.width == 0) {
            heroStage.removeAllViews()
            heroStage.addView(page, FrameLayout.LayoutParams(-1, -2))
        } else slideHero(old, page, if (step > 0) 1 else -1)
        heroDots.removeAllViews()
        for (k in stats.indices) heroDots.addView(ui.spacer().apply {
            background = ui.rounded(color(R.color.ink), 3)
            alpha = if (k == i) 1f else 0.25f
        }, ui.lp(w = ui.dp(if (k == i) 16 else 6), h = ui.dp(6), start = if (k == 0) 0 else 6))
        heroCard.contentDescription = "${s.title}, ${s.value}, ${s.sub}. Reading ${i + 1} of ${stats.size}. Tap for the next."
        scheduleHero()
    }

    /** [old] slides out one way while [page] slides in from the other; the card's height follows smoothly. */
    private fun slideHero(old: View, page: View, dir: Int) {
        val w = heroStage.width.toFloat()
        val fromH = heroStage.height
        page.measure(View.MeasureSpec.makeMeasureSpec(heroStage.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val toH = page.measuredHeight
        val startX = old.translationX
        page.translationX = dir * w
        heroStage.addView(page, FrameLayout.LayoutParams(-1, -2))
        heroStage.layoutParams = heroStage.layoutParams.apply { height = fromH }
        heroAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val t = it.animatedValue as Float
                old.translationX = startX + (-dir * w - startX) * t
                page.translationX = dir * w * (1 - t)
                if (fromH != toH) heroStage.layoutParams = heroStage.layoutParams.apply { height = (fromH + (toH - fromH) * t).toInt() }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    heroStage.removeView(old)
                    page.translationX = 0f
                    heroStage.layoutParams = heroStage.layoutParams.apply { height = FrameLayout.LayoutParams.WRAP_CONTENT }
                    if (heroAnim === a) heroAnim = null
                }
            })
            start()
        }
    }

    /** One reading, as the hero card shows it: icon and name, the figure, a line under it and, where it helps, a ring. */
    private fun heroPage(s: HeroStat, animateRing: Boolean): View = ui.column {
        setPadding(ui.dp(16), ui.dp(16), ui.dp(16), 0)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(ui.row {
            addView(ui.chipIcon(s.icon))
            addView(ui.text(15f, color(R.color.ink), ui.medium) { text = s.title; maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                ui.lp(w = 0, weight = 1f, start = 10))
            addView(ui.circleButton(R.drawable.ic_arrow_up_right, s.screen?.let { "Open $it" } ?: "Open", 36) {
                s.screen?.let { host.show(it) }
            }.apply { if (s.screen == null) visibility = View.INVISIBLE })
        })
        addView(ui.row {
            minimumHeight = ui.dp(128) // same height with or without the ring, so the page doesn't jump
            addView(ui.column {
                addView(ui.text(when { s.value.length <= 6 -> 50f; s.value.length <= 9 -> 40f; else -> 30f },
                    color(R.color.ink), ui.regular) { text = s.value; letterSpacing = -0.03f; maxLines = 1 })
                addView(ui.text(13f, color(R.color.ink), ui.medium) { text = s.sub; maxLines = 2 }, ui.lp(top = 8))
            }, ui.lp(w = 0, weight = 1f))
            s.ring?.let { f ->
                addView(FrameLayout(context).apply {
                    addView(Ring(context).apply {
                        strokeDp = 16f
                        colors(color(R.color.glass_strong), color(R.color.ink), color(R.color.ink))
                        if (animateRing) fraction = f else jumpTo(f)
                    }, FrameLayout.LayoutParams(-1, -1))
                    addView(ui.column {
                        gravity = Gravity.CENTER
                        addView(ui.text(20f, color(R.color.ink), ui.medium) { gravity = Gravity.CENTER; text = s.ringValue }, ui.lp(w = WRAP_CONTENT))
                        addView(ui.text(12f, color(R.color.ink)) { gravity = Gravity.CENTER; text = s.ringLabel; maxWidth = ui.dp(84); maxLines = 2 },
                            ui.lp(w = WRAP_CONTENT, top = 2))
                    }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                }, ui.lp(w = ui.dp(128), h = ui.dp(128), start = 8))
            }
        }, ui.lp(top = 14))
        for (issue in s.issues.take(3)) {
            val (row, value) = ui.listPill(R.drawable.ic_warning, issue.text) { host.show(issue.screen) }
            value.text = if (issue.screen == "Cleaner") "Clean" else "Open"
            addView(row, ui.lp(top = 10))
        }
    }

    /** Storage split into numbers; the arrow opens the folder sizes, the line below the Cleaner. */
    private fun storageCard() = ui.card(color(R.color.surface), 16) {
        val (head, sub) = ui.cardHeader(R.drawable.ic_sd, "Storage") {
            addView(ui.circleButton(R.drawable.ic_arrow_right, "Open Storage: folder sizes", 36) { host.show("Storage") })
        }
        storageSub = sub
        addView(head)
        val (nums, values) = ui.numbers(listOf("Apps", "Media", "Docs", "Free"))
        storageValues = values
        addView(nums, ui.lp(top = 20))
        val (free, _) = ui.listPill(R.drawable.ic_broom, "Free up space with the Cleaner") { host.show("Cleaner") }
        addView(free, ui.lp(top = 18))
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
        text = "Andromon Companion" + (version?.let { " · v$it" } ?: "")
    }

    // ---------- state ----------

    private fun refresh() {
        val (score, issues) = Phone.health(host)
        greetSub.text = when {
            issues.isEmpty() -> "Your phone is in great shape."
            score >= 80 -> "Your phone is in good shape."
            else -> "Your phone could use a tidy-up."
        }
        stepHero(0)

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
