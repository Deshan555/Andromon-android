// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

package dev.droiddesk.companion

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.TextView
import java.io.File

/**
 * RAM usage with a live one-minute chart, plus the RAM manager: the apps in memory, how much each
 * uses and whether it's in use, with Stop per app and "Free memory" for everything in the background.
 *
 * Seeing and stopping other apps needs shell or root rights, which [ShellHelper] provides once
 * Droid Desk (PC) or `su` has started it. Without it the screen explains how to turn it on, and on
 * Android 13 and older still offers the old "Boost" (killBackgroundProcesses).
 */
internal class MemoryScreen(host: MainActivity) : Screen(host) {

    override val name = "Memory"
    override val navIcon = R.drawable.ic_memory
    override val navColor = R.color.bg

    private val am = host.getSystemService(ActivityManager::class.java)
    private val canBoost = Build.VERSION.SDK_INT < 34
    private val samples = ArrayList<Float>()
    private val handler = Handler(Looper.getMainLooper())
    private var ticks = 0
    private val tick = object : Runnable {
        override fun run() {
            sample()
            // Look for the helper every 3 s until it's there (Droid Desk may turn it on any moment),
            // then refresh the app list every 10 s.
            if (helper == null && ticks % 3 == 0) checkHelper()
            else if (helper != null && ticks % 10 == 0) loadApps()
            ticks++
            handler.postDelayed(this, 1000)
        }
    }

    private lateinit var ring: Ring
    private lateinit var pct: TextView
    private lateinit var big: TextView
    private lateinit var statusPill: TextView
    private lateinit var chart: LineChart
    private lateinit var available: Ui.Stat
    private lateinit var swap: Ui.Stat

    // RAM manager
    /** The running helper (protocol version, runs as root), or null when it isn't running. */
    private var helper: Pair<Int, Boolean>? = null
    /** Which backend that is (Shizuku, Droid Desk helper, root), for the note under the list. */
    @Volatile private var access: Privileged.Active? = null
    private var checking = false
    private var loading = false
    private var busy = false
    private var running: List<RunningApp> = emptyList()
    private var showSystem = false
    private lateinit var manager: LinearLayout
    private lateinit var freeBtn: TextView
    private lateinit var chipApps: TextView
    private lateinit var chipAll: TextView
    private lateinit var appsNote: TextView
    private lateinit var appsList: LinearLayout
    private lateinit var unlock: LinearLayout
    private lateinit var unlockNote: TextView
    private var rootBtn: TextView? = null
    private var boost: TextView? = null
    private lateinit var insight: TextView

    override fun build(): View = ui.column {
        addView(ui.topBar("Memory", host::goHome).first)

        // "Your Progress" style: big percentage on the left, ring on the right.
        addView(ui.heroCard(18) {
            addView(ui.row {
                addView(ui.column {
                    addView(ui.row {
                        addView(ui.chipIcon(R.drawable.ic_memory))
                        addView(ui.text(15f, color(R.color.ink), ui.medium) { text = "RAM" }, ui.lp(w = WRAP_CONTENT, start = 10))
                    })
                    pct = ui.text(50f, color(R.color.ink), ui.regular) { text = "–"; letterSpacing = -0.03f }
                    addView(pct, ui.lp(top = 14))
                    big = ui.text(14f, color(R.color.ink), ui.medium)
                    addView(big, ui.lp(top = 6))
                }, ui.lp(w = 0, weight = 1f))
                addView(FrameLayout(context).apply {
                    ring = Ring(context).apply {
                        strokeDp = 11f
                        colors(color(R.color.glass_strong), color(R.color.ink), color(R.color.ink))
                    }
                    addView(ring, FrameLayout.LayoutParams(-1, -1))
                    addView(ui.iconView(R.drawable.ic_memory, color(R.color.ink)),
                        FrameLayout.LayoutParams(ui.dp(30), ui.dp(30), Gravity.CENTER))
                }, ui.lp(w = ui.dp(118), h = ui.dp(118), start = 8))
            })
            statusPill = ui.pill("", 0, 0).apply { textSize = 13f; minHeight = ui.dp(34); typeface = ui.medium }
            addView(statusPill, ui.lp(w = WRAP_CONTENT, top = 14))
        }, ui.lp(top = 24))

        addView(ui.row {
            available = ui.stat(color(R.color.glass), R.drawable.ic_check_circle, "Available")
            swap = ui.stat(color(R.color.glass), R.drawable.ic_swap, "Swap used")
            addView(available.box, ui.lp(w = 0, weight = 1f))
            addView(swap.box, ui.lp(w = 0, weight = 1f, start = 12))
        }, ui.lp(top = 12))

        addView(ui.section("Last minute") {
            addView(ui.text(13f, color(R.color.ink_soft)) { text = "% in use ›" }, ui.lp(w = WRAP_CONTENT))
        }, ui.lp(top = 26))
        addView(ui.glassCard(16) {
            chart = LineChart(context).apply {
                minSpan = 6f; fill = true
                marker = color(R.color.marker)
                colors(color(R.color.chart), color(R.color.glass_strong), color(R.color.surface))
            }
            addView(chart, ui.lp(h = ui.dp(130)))
            addView(ui.row {
                addView(ui.text(12f, color(R.color.ink_soft)) { text = "1 min ago" }, ui.lp(w = 0, weight = 1f))
                addView(ui.text(12f, color(R.color.ink_soft)) { text = "now" }, ui.lp(w = WRAP_CONTENT))
            }, ui.lp(top = 6))
        }, ui.lp(top = 12))

        addView(ui.section("Running apps"), ui.lp(top = 26))
        manager = buildManager()
        addView(manager, ui.lp(top = 12))
        unlock = buildUnlock()
        addView(unlock, ui.lp(top = 12))
        showManager(false)
    }

    // ---------- RAM manager (helper running) ----------

    private fun buildManager() = ui.column {
        freeBtn = ui.action("Free memory", R.drawable.ic_bolt) { freeMemory() }
        addView(freeBtn)
        addView(ui.row {
            chipApps = ui.pill("Apps", 0, 0).apply { minHeight = ui.dp(36); textSize = 13f }
            chipAll = ui.pill("All", 0, 0).apply { minHeight = ui.dp(36); textSize = 13f }
            addView(chipApps, ui.lp(w = WRAP_CONTENT))
            addView(chipAll, ui.lp(w = WRAP_CONTENT, start = 8))
            addView(ui.spacer(), ui.lp(w = 0, weight = 1f))
            addView(ui.circleButton(R.drawable.ic_refresh, "Refresh", 36) { loadApps() }, ui.lp(w = WRAP_CONTENT))
        }, ui.lp(top = 16))
        appsNote = ui.text(13f, color(R.color.ink_soft)) { setLineSpacing(ui.dp(2).toFloat(), 1f) }
        addView(appsNote, ui.lp(top = 12))
        appsList = ui.column {}
        addView(appsList, ui.lp(top = 4))
    }

    private fun renderApps() {
        val system = running.count { it.system }
        val apps = running.size - system
        fun chip(tv: TextView, label: String, on: Boolean, all: Boolean) =
            if (on) ui.stylePill(tv, label, color(R.color.fab), color(R.color.on_fab)) { showSystem = all; renderApps() }
            else ui.stylePill(tv, label, color(R.color.surface), color(R.color.ink)) { showSystem = all; renderApps() }
        chip(chipApps, "Apps ($apps)", !showSystem, false)
        chip(chipAll, "All (${running.size})", showSystem, true)

        val shown = if (showSystem) running else running.filter { !it.system }
        appsList.removeAllViews()
        for (app in shown) appsList.addView(appRow(app), ui.lp(top = 8))
        if (shown.isEmpty() && !loading) appsList.addView(ui.text(14f, color(R.color.ink_soft)) {
            text = if (showSystem) "Nothing is running." else "No downloaded apps are in memory right now."
            setPadding(ui.dp(12), ui.dp(16), ui.dp(12), ui.dp(16))
        })
    }

    private fun appRow(app: RunningApp) = ui.row {
        gravity = Gravity.CENTER_VERTICAL
        background = ui.pressable(ui.rounded(color(R.color.surface), 18))
        setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
        addView(ImageView(context).apply {
            setImageDrawable(app.icon?.constantState?.newDrawable(resources) ?: app.icon)
        }, ui.lp(w = ui.dp(42), h = ui.dp(42)))
        addView(ui.column {
            addView(ui.text(15f, color(R.color.ink), ui.medium) {
                text = app.label; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            val parts = mutableListOf(fmtSize(app.bytes), app.state.label)
            if (app.processes > 1) parts += "${app.processes} processes"
            addView(ui.text(12f, color(R.color.ink_soft)) {
                text = parts.joinToString(" · "); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, ui.lp(top = 3))
        }, ui.lp(w = 0, weight = 1f, start = 14))
        if (app.keepRunning) {
            addView(ui.text(12f, color(R.color.ink_soft)) { text = "Needed" }, ui.lp(w = WRAP_CONTENT, start = 8))
        } else {
            addView(ui.pill("Stop", color(R.color.glass_strong), color(R.color.danger)) { confirmStop(app) }.apply {
                minHeight = ui.dp(36); textSize = 13f
                contentDescription = "Stop ${app.label}"
            }, ui.lp(w = WRAP_CONTENT, start = 8))
        }
        setOnClickListener { openAppInfo(app.pkg) }
    }

    private fun confirmStop(app: RunningApp) {
        if (busy) return
        if (!app.system && app.state != RamManager.State.ACTIVE && app.state != RamManager.State.VISIBLE) { stop(app); return }
        val why = if (app.system) "${app.label} is part of the system. Stopping it can make some features misbehave until it starts again."
        else "${app.label} is open right now. Anything unsaved in it will be lost."
        AlertDialog.Builder(host)
            .setTitle("Stop ${app.label}?")
            .setMessage(why)
            .setPositiveButton("Stop") { _, _ -> stop(app) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Force stop: like App info › Force stop. The app starts again only when it's opened or called. */
    private fun stop(app: RunningApp) {
        busy = true
        appsNote.text = "Stopping ${app.label}…"
        background({
            val before = RamManager.availMem(host)
            RamManager.forceStop(host, app.pkg)
            Thread.sleep(700)
            RamManager.availMem(host) - before
        }) { r ->
            busy = false
            val freed = r.getOrNull()
            appsNote.text = if (freed == null) "Couldn't stop ${app.label}: ${r.exceptionOrNull()?.message}"
            else "Stopped ${app.label}" + (if (freed > 10_000_000) ", freed ${fmtSize(freed)}." else ".")
            loadApps(keepNote = true)
        }
    }

    /** Closes every app in the background (not the one in use, not the keyboard or home screen). */
    private fun freeMemory() {
        if (busy) return
        busy = true
        ui.setEnabled(freeBtn, false)
        ui.setLabel(freeBtn, "Freeing memory…")
        background({
            val before = RamManager.availMem(host)
            RamManager.freeMemory(host)
            Thread.sleep(1500) // let the system reclaim the memory
            RamManager.availMem(host) - before
        }) { r ->
            busy = false
            ui.setLabel(freeBtn, "Free memory")
            ui.setEnabled(freeBtn, true)
            val freed = r.getOrNull()
            appsNote.text = when {
                freed == null -> "Couldn't free memory: ${r.exceptionOrNull()?.message}"
                freed < 20_000_000 -> "Memory was already in good shape. Nothing more to free right now."
                else -> "Freed ${fmtSize(freed)} by closing apps in the background. They start again when you open them."
            }
            sample()
            loadApps(keepNote = true)
        }
    }

    private fun loadApps(keepNote: Boolean = false) {
        if (loading || helper == null) return
        loading = true
        background({ RamManager.load(host) }) { r ->
            loading = false
            val list = r.getOrNull()
            if (list == null) {
                // The helper went away (phone restarted it, or it was stopped): back to the explanation.
                helper = null; showManager(false)
                return@background
            }
            running = list
            if (!keepNote && !busy) appsNote.text = helperNote()
            renderApps()
        }
    }

    private fun helperNote(): String {
        val total = running.sumOf { it.bytes }
        return "${running.size} apps use ${fmtSize(total)}. Full access is on ${access?.how() ?: "through the helper"}. Tap an app for its App info."
    }

    // ---------- turning it on (no helper) ----------

    private fun buildUnlock() = ui.column {
        addView(ui.glassCard(16) {
            addView(ui.cardHeader(R.drawable.ic_lock, "See and stop running apps").also {
                it.second.text = "Needs full access, like App Manager"
            }.first)
            addView(ui.text(14f, color(R.color.ink)) {
                setLineSpacing(ui.dp(2).toFloat(), 1f)
                text = "Android only lets an app see its own processes. To list the apps in memory and stop them, " +
                    "this app borrows the rights of ADB (or root).\n\n" +
                    "Without a computer: with the Shizuku app (Settings › Privileged access).\n\n" +
                    "With a computer: connect this phone to Droid Desk and click “Turn on” next to " +
                    "“Phone RAM manager” on its Overview page. It stays on until the phone restarts, " +
                    "even after you unplug."
            }, ui.lp(top = 14))
            unlockNote = ui.text(13f, color(R.color.ink_soft)) { visibility = View.GONE }
            addView(unlockNote, ui.lp(top = 10))
            addView(ui.pill("Use Shizuku", color(R.color.fab), color(R.color.on_fab), R.drawable.ic_key) { host.show("Settings") },
                ui.lp(w = WRAP_CONTENT, top = 12))
            if (ShellHelperClient.hasSu()) {
                rootBtn = ui.pill("Use root instead", color(R.color.fab), color(R.color.on_fab), R.drawable.ic_shield) { startRoot() }
                addView(rootBtn, ui.lp(w = WRAP_CONTENT, top = 12))
            }
        })

        if (canBoost) {
            boost = ui.action("Boost memory", R.drawable.ic_bolt) { boost() }
            addView(boost, ui.lp(top = 18))
        }
        val (row, label) = ui.insight(R.drawable.ic_info, color(R.color.yellow), if (canBoost) null else ::openDeveloperOptions)
        insight = label
        insight.text = if (canBoost)
            "Without full access, Boost closes apps that are running in the background. They start again when you open them."
        else
            "Android 14 and later don't let apps close other apps without full access. " +
                "Tap to stop apps yourself in Developer options › Running services."
        addView(row, ui.lp(top = 14))
    }

    private fun startRoot() {
        val btn = rootBtn ?: return
        ui.setEnabled(btn, false)
        ui.setLabel(btn, "Waiting for root…")
        setUnlockNote("Allow Droid Desk Companion in your root app if it asks.")
        background({ ShellHelperClient.startWithRoot(host) }) { r ->
            ui.setEnabled(btn, true)
            ui.setLabel(btn, "Use root instead")
            if (r.getOrNull() == true) { setUnlockNote(""); checkHelper() }
            else setUnlockNote("Root wasn't granted" + (r.exceptionOrNull()?.message?.let { ": $it" } ?: "."))
        }
    }

    private fun setUnlockNote(t: String) {
        unlockNote.text = t
        unlockNote.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun checkHelper() {
        if (checking) return
        checking = true
        background({
            // Any privileged backend will do (Shizuku, the PC-started helper, root): see Privileged.
            Privileged.active(host).also { access = it }?.let { PrivilegedCommands.VERSION to it.root }
        }) { r ->
            checking = false
            val h = r.getOrNull()?.takeIf { it.first >= 1 }
            val was = helper
            helper = h
            if ((h != null) != (was != null)) showManager(h != null)
            if (h != null && was == null) loadApps()
        }
    }

    private fun showManager(on: Boolean) {
        manager.visibility = if (on) View.VISIBLE else View.GONE
        unlock.visibility = if (on) View.GONE else View.VISIBLE
        if (on) { appsNote.text = "Loading running apps…"; renderApps() }
    }

    private fun openAppInfo(pkg: String) {
        runCatching {
            host.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        }
    }

    // ---------- memory figures ----------

    override fun onShow() {
        handler.removeCallbacks(tick)
        ticks = 0
        handler.post(tick)
    }

    override fun onHide() {
        handler.removeCallbacks(tick)
    }

    private fun memInfo() = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

    private fun sample() {
        val m = memInfo()
        val used = m.totalMem - m.availMem
        val fraction = used.toFloat() / m.totalMem
        samples += fraction * 100
        if (samples.size > 60) samples.removeAt(0)
        chart.values = samples

        ring.fraction = fraction
        pct.text = ui.percent((fraction * 100).toInt())
        big.text = "${String.format("%.1f", used / 1e9)} GB of ${String.format("%.0f", Math.ceil(m.totalMem / 1e9))} GB"
        available.value.text = sizeParts(m.availMem).let { ui.withUnit(it.first, it.second) }
        val (swapTotal, swapFree) = readSwap()
        swap.value.text = if (swapTotal > 0) sizeParts(swapTotal - swapFree).let { ui.withUnit(it.first, it.second) } else "Off"

        val (note, _, fg) = when {
            m.lowMemory -> Triple("Low · Android is closing apps", R.color.peach_tile, R.color.danger)
            fraction < 0.7f -> Triple("Normal · plenty of free memory", R.color.good_bg, R.color.good)
            fraction < 0.85f -> Triple("Busy · memory is fairly full", R.color.yellow_tile, R.color.ink)
            else -> Triple("Full · Android may close background apps", R.color.peach_tile, R.color.danger)
        }
        ui.stylePill(statusPill, note, color(R.color.glass_strong), color(fg))
    }

    /** SwapTotal / SwapFree from /proc/meminfo, in bytes (zRAM on most phones). */
    private fun readSwap(): Pair<Long, Long> = try {
        val lines = File("/proc/meminfo").readLines()
        fun kb(key: String) = lines.firstOrNull { it.startsWith("$key:") }
            ?.filter { it.isDigit() }?.toLongOrNull()?.times(1024) ?: 0L
        kb("SwapTotal") to kb("SwapFree")
    } catch (_: Exception) {
        0L to 0L
    }

    /** Without the helper, on Android 13 and older: close background apps the old way. */
    @Suppress("DEPRECATION")
    private fun boost() {
        val b = boost ?: return
        ui.setEnabled(b, false)
        ui.setLabel(b, "Boosting…")
        background({
            val before = memInfo().availMem
            val pm = host.packageManager
            val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                ?.activityInfo?.packageName
            val keyboard = Settings.Secure.getString(host.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
            // Apps with a launcher icon, minus ourselves, the home screen and the keyboard.
            val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName }.toSet() - setOfNotNull(host.packageName, home, keyboard)
            apps.forEach { runCatching { am.killBackgroundProcesses(it) } }
            Thread.sleep(1500)
            memInfo().availMem - before
        }) { r ->
            ui.setLabel(b, "Boost memory")
            ui.setEnabled(b, true)
            val freed = r.getOrNull()
            insight.text = when {
                freed == null -> "Boost failed: ${r.exceptionOrNull()?.message}"
                freed < 20_000_000 -> "Memory was already in good shape. Nothing more to free right now."
                else -> "Freed ${fmtSize(freed)} by closing apps in the background."
            }
            sample()
        }
    }

    private fun openDeveloperOptions() {
        runCatching { host.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
    }
}
