// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
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
 * Storage overview and cleaner: app caches (Android 11+, via the system's own confirmation),
 * thumbnails, temp/log files, empty folders, old APKs, duplicates and large files, on internal
 * storage and SD cards. Nothing is deleted until the user confirms.
 */
internal class CleanerScreen(host: MainActivity) : Screen(host) {

    override val name = "Cleaner"
    override val navIcon = R.drawable.ic_broom
    override val navColor = R.color.bg

    private companion object {
        const val REQ_STORAGE = 2
        const val REQ_CLEAR_CACHE = 7
        const val MAX_LISTED = 100
    }

    private var groups: List<JunkGroup> = emptyList()
    private var cacheGroup: JunkGroup? = null
    private var scanning = false
    private var autoScanned = false

    private lateinit var ringValue: TextView
    private lateinit var ringLabel: TextView
    private lateinit var accessCard: View
    private lateinit var resultsCard: LinearLayout
    private lateinit var resultsTitle: TextView
    private lateinit var resultsSub: TextView
    private lateinit var groupList: LinearLayout
    private lateinit var cleanButton: TextView
    private lateinit var scanButton: TextView
    private lateinit var freedCard: View
    private lateinit var freedValue: TextView
    private lateinit var insightRow: View
    private lateinit var insight: TextView

    private lateinit var donut: Ring
    private lateinit var donutValue: TextView
    private lateinit var totalText: TextView
    private lateinit var usedText: TextView
    private lateinit var freeText: TextView
    private lateinit var legend: LinearLayout
    private lateinit var sdBox: LinearLayout

    override fun build(): View = ui.column {
        addView(ui.topBar("Cleaner", host::goHome, R.drawable.ic_refresh, "Scan again") { scan() }.first)
        addView(summaryRow(), ui.lp(top = 24))
        scanButton = ui.action("Scan now", R.drawable.ic_search) { scan() }
        addView(scanButton, ui.lp(top = 24))
        cleanButton = ui.action("Clean now", R.drawable.ic_broom) { confirmClean() }.apply { visibility = View.GONE }
        addView(cleanButton, ui.lp(top = 24))
        accessCard = accessCard()
        addView(accessCard, ui.lp(top = 20))
        addView(resultsCard(), ui.lp(top = 20))
        addView(freedCard(), ui.lp(top = 14))
        val (row, label) = ui.insight(R.drawable.ic_info, color(R.color.green_tile))
        insightRow = row; insight = label
        addView(row, ui.lp(top = 14))
        addView(ui.section("Storage"), ui.lp(top = 26))
        addView(storageCard(), ui.lp(top = 12))
        addView(ui.listPill(R.drawable.ic_folder, "Storage analyzer: folder sizes") { host.show("Storage") }.first, ui.lp(top = 12))
    }

    override fun onShow() {
        refreshStorage()
        val access = Cleaner.hasFileAccess(host)
        accessCard.visibility = if (access) View.GONE else View.VISIBLE
        ui.setEnabled(scanButton, access && !scanning)
        if (!access) { ringValue.text = "Needs file\naccess"; ringLabel.text = "–" }
        updateInsight()
        if (access && !autoScanned && !scanning) { autoScanned = true; scan() }
    }

    override fun onPermissionsResult() = onShow()

    override fun onActivityResult(requestCode: Int) {
        if (requestCode == REQ_CLEAR_CACHE) {
            refreshStorage()
            scan()
        }
    }

    // ---------- big ring ----------

    /** "Date / Time" style pair: what was found and what is ticked, each on a scalloped badge. */
    private fun summaryRow() = ui.row {
        gravity = Gravity.TOP
        fun block(title: String, icon: Int, bg: Int, fg: Int): TextView {
            val value = ui.text(14f, color(R.color.ink)) { setLineSpacing(ui.dp(2).toFloat(), 1f); text = "–" }
            addView(ui.column {
                addView(ui.text(24f, color(R.color.ink), ui.light) { text = title; letterSpacing = -0.02f })
                addView(ui.row {
                    addView(ui.badge(icon, color(bg), fg))
                    addView(value, ui.lp(w = 0, weight = 1f, start = 10))
                }, ui.lp(top = 12))
            }, ui.lp(w = 0, weight = 1f, start = if (childCount == 0) 0 else 12))
            return value
        }
        ringValue = block("Junk found", R.drawable.ic_broom, R.color.magenta, android.graphics.Color.WHITE)
        ringLabel = block("Selected", R.drawable.ic_check, R.color.yellow, color(R.color.ink))
    }

    private fun showRing() {
        val total = groups.sumOf { it.size }
        val (files, cache, selected) = selection()
        val n = files.size + (if (cache) 1 else 0)
        ringValue.text = if (groups.isEmpty()) "0 B\nAll tidy" else "${fmtSize(total)}\n${plural(groups.size, "group")}"
        ringLabel.text = if (groups.isEmpty()) "–" else "${fmtSize(selected)}\n${plural(n, "item")}"
    }

    // ---------- storage ----------

    private fun storageCard() = ui.heroCard(18) {
        addView(ui.row {
            addView(FrameLayout(context).apply {
                donut = Ring(context).apply { strokeDp = 16f; colors(color(R.color.glass_strong), 0, 0) }
                addView(donut, FrameLayout.LayoutParams(-1, -1))
                addView(ui.column {
                    gravity = Gravity.CENTER
                    donutValue = ui.text(28f, color(R.color.ink), ui.regular) { gravity = Gravity.CENTER; text = "–" }
                    addView(donutValue, ui.lp(w = WRAP_CONTENT))
                    addView(ui.text(13f, color(R.color.ink_soft)) { text = "Used" }, ui.lp(w = WRAP_CONTENT, top = 2))
                }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            }, ui.lp(w = ui.dp(140), h = ui.dp(140)))
            addView(ui.column {
                fun figure(label: String): TextView {
                    val v = ui.text(22f, color(R.color.ink), ui.regular) { text = "–" }
                    addView(v, ui.lp(top = if (childCount == 0) 0 else 12))
                    addView(ui.text(12f, color(R.color.ink_soft)) { text = label }, ui.lp(top = 2))
                    return v
                }
                totalText = figure("Total")
                usedText = figure("Used")
                freeText = figure("Free")
            }, ui.lp(w = 0, weight = 1f, start = 24))
        })
        legend = ui.column()
        addView(legend, ui.lp(top = 14))
        sdBox = ui.column()
        addView(sdBox)
    }

    private fun refreshStorage() {
        background({
            val vols = Cleaner.volumes(host)
            val main = vols.first()
            Triple(vols, StorageBreakdown.of(host, main.total, main.free), main)
        }) { r ->
            val (vols, parts, main) = r.getOrNull() ?: return@background
            val used = main.total - main.free
            donutValue.text = ui.percent((used * 100 / main.total).toInt())
            totalText.text = fmtSize(main.total)
            usedText.text = fmtSize(used)
            freeText.text = fmtSize(main.free)
            donut.segments = parts.map { it.bytes.toFloat() / main.total to color(it.color) }
            legend.removeAllViews()
            for (part in parts) {
                legend.addView(ui.row {
                    addView(View(context).apply { background = ui.rounded(color(part.color), 3) }, ui.lp(w = ui.dp(10), h = ui.dp(10)))
                    addView(ui.text(14f, color(R.color.ink)) { text = part.name }, ui.lp(w = 0, weight = 1f, start = 12))
                    addView(ui.text(14f, color(R.color.ink)) { text = fmtSize(part.bytes) }, ui.lp(w = WRAP_CONTENT))
                }, ui.lp(top = 10))
            }
            if (!Cleaner.hasUsageAccess(host)) legend.addView(ui.text(12f, color(R.color.ink_soft)) {
                text = "Apps are counted in “System & other” until usage access is allowed."
            }, ui.lp(top = 10))
            sdBox.removeAllViews()
            for (v in vols.drop(1)) {
                sdBox.addView(ui.column {
                    background = ui.rounded(color(R.color.glass_strong), 18)
                    setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(14))
                    addView(ui.row {
                        addView(ui.iconTile(R.drawable.ic_sd, color(R.color.cat_purple), color(R.color.purple_strong), 32, 18),
                            ui.lp(w = ui.dp(32), h = ui.dp(32)))
                        addView(ui.text(15f, color(R.color.ink), ui.medium) { text = v.name }, ui.lp(w = 0, weight = 1f, start = 10))
                        addView(ui.text(13f, color(R.color.ink_soft)) { text = "${fmtSize(v.free)} free of ${fmtSize(v.total)}" },
                            ui.lp(w = WRAP_CONTENT))
                    })
                    addView(Meter(context).apply {
                        colors(color(R.color.glass), color(R.color.magenta))
                        fraction = (v.total - v.free).toFloat() / v.total
                    }, ui.lp(h = ui.dp(8), top = 12))
                }, ui.lp(top = 14))
            }
        }
    }

    // ---------- access ----------

    private fun accessCard() = ui.glassCard(16) {
        addView(ui.row {
            addView(ui.iconTile(R.drawable.ic_folder, color(R.color.cat_yellow), color(R.color.yellow_tile), 46, 22),
                ui.lp(w = ui.dp(46), h = ui.dp(46)))
            addView(ui.column {
                addView(ui.text(16f, color(R.color.ink), ui.medium) { text = "Allow access to files" })
                addView(ui.text(13f, color(R.color.ink_soft)) {
                    text = "Needed to find junk on your storage and SD card. Nothing is deleted until you confirm."
                    setLineSpacing(ui.dp(2).toFloat(), 1f)
                }, ui.lp(top = 4))
            }, ui.lp(w = 0, weight = 1f, start = 14))
        })
        addView(ui.pill("Allow", color(R.color.fab), color(R.color.on_fab)) { requestFileAccess() }, ui.lp(top = 14))
    }

    private fun requestFileAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            val pkg = Uri.fromParts("package", host.packageName, null)
            runCatching { host.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg)) }
                .onFailure { host.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else {
            host.requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
        }
    }

    // ---------- scan results ----------

    private fun resultsCard(): View {
        resultsCard = ui.glassCard(8) {
            visibility = View.GONE
            addView(ui.column {
                setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(2))
                resultsTitle = ui.text(22f, color(R.color.ink), ui.light) { letterSpacing = -0.02f }
                addView(resultsTitle)
                resultsSub = ui.text(13f, color(R.color.ink_soft)) { setLineSpacing(ui.dp(2).toFloat(), 1f) }
                addView(resultsSub, ui.lp(top = 4))
            })
            groupList = ui.column()
            addView(groupList, ui.lp(top = 6))
        }
        return resultsCard
    }

    private fun freedCard(): View {
        val card = ui.card(color(R.color.yellow), 16) {
            visibility = View.GONE
            addView(ui.row {
                addView(ui.badge(R.drawable.ic_bolt, color(R.color.magenta), android.graphics.Color.WHITE))
                addView(ui.column {
                    addView(ui.text(13f, color(R.color.ink_soft)) { text = "Freed up space" })
                    freedValue = ui.text(28f, color(R.color.ink), ui.regular)
                    addView(freedValue, ui.lp(top = 4))
                }, ui.lp(w = 0, weight = 1f, start = 14))
            })
        }
        freedCard = card
        return card
    }

    private fun scan() {
        if (scanning || !Cleaner.hasFileAccess(host)) return
        scanning = true
        ui.setEnabled(scanButton, false)
        ui.setLabel(scanButton, "Scanning…")
        scanButton.visibility = View.VISIBLE
        cleanButton.visibility = View.GONE
        ringValue.text = "Scanning…"
        ringLabel.text = "–"
        resultsCard.visibility = View.VISIBLE
        groupList.removeAllViews()
        resultsTitle.text = "Scanning…"
        resultsSub.text = ""
        background({
            val found = Cleaner.scan(host) { where ->
                host.runOnUiThread { if (scanning) resultsSub.text = where }
            }
            // Only the part Android's clear-cache screen can free; hidden when that's nothing.
            val cacheBytes = Cleaner.clearableCacheBytes(host)
            val cache = if (Build.VERSION.SDK_INT >= 30 && Prefs.ruleOn(host, "cache") && cacheBytes != 0L) {
                JunkGroup("cache", "App caches on shared storage",
                    "Android asks you to confirm. Apps' private caches can only be cleared from Andromon on your computer",
                    R.drawable.ic_cache, false, false).apply {
                    items += JunkItem(File(""), cacheBytes ?: 0L)
                }
            } else null
            cache to found
        }) { r ->
            scanning = false
            ui.setLabel(scanButton, "Scan again")
            ui.setEnabled(scanButton, true)
            r.onSuccess { (cache, found) ->
                cacheGroup = cache
                groups = listOfNotNull(cache) + found.filter { it.items.isNotEmpty() }
                showResults()
            }.onFailure {
                resultsTitle.text = "Scan failed"
                resultsSub.text = it.message ?: it.javaClass.simpleName
                ringValue.text = "Scan failed"; ringLabel.text = "–"
            }
            updateInsight()
        }
    }

    private fun showResults() {
        val total = groups.sumOf { it.size }
        resultsTitle.text = if (groups.isEmpty()) "No junk found" else "Found ${fmtSize(total)}"
        resultsSub.text = if (groups.isEmpty()) "Your storage is already tidy."
            else "Cleaning rules from SD Maid SE. Tap a group to see its files; leftovers, duplicates, large " +
                "files and the groups SD Maid SE leaves off by default start unticked."
        resultsCard.visibility = if (groups.isEmpty()) View.GONE else View.VISIBLE
        groupList.removeAllViews()
        for (g in groups) groupList.addView(groupView(g), ui.lp(top = 8))
        updateCleanButton()
    }

    private fun groupView(g: JunkGroup): View = ui.column {
        val check = ImageView(context)
        val sizeText = ui.text(14f, color(R.color.ink), ui.medium) { gravity = Gravity.END }
        val items = ui.column { visibility = if (g.expanded) View.VISIBLE else View.GONE }
        val chevron = if (g.listItems) ui.iconView(R.drawable.ic_expand, color(R.color.ink_soft)) else null

        fun refreshHeader() {
            val checked = g.items.count { it.checked }
            check.setImageDrawable(when {
                checked == 0 -> ui.icon(R.drawable.ic_dot_empty, color(R.color.ink_faint))
                checked == g.items.size -> ui.icon(R.drawable.ic_check_circle, color(R.color.magenta))
                else -> ui.icon(R.drawable.ic_check_circle, color(R.color.ink_faint)) // some ticked
            })
            sizeText.text = when {
                g.key == "cache" && g.size == 0L -> "–"
                g.key == "sdm_empty" -> "${g.items.size}"
                else -> fmtSize(g.size)
            }
            chevron?.rotation = if (g.expanded) 180f else 0f
        }

        val header = ui.row {
            background = ui.pressable(ui.rounded(color(R.color.glass_strong), 22))
            setPadding(ui.dp(8), ui.dp(8), ui.dp(6), ui.dp(8))
            check.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
            check.setOnClickListener {
                val on = g.items.any { !it.checked }
                g.items.forEach { it.checked = on }
                refreshHeader(); fillItems(g, items); updateCleanButton()
            }
            val (fg, bg) = groupColors(g.key)
            addView(ui.roundIcon(40, color(bg), g.icon, color(fg), 19), ui.lp(w = ui.dp(40), h = ui.dp(40)))
            addView(ui.column {
                addView(ui.text(15f, color(R.color.ink), ui.medium) { text = g.name; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                addView(ui.text(13f, color(R.color.ink_soft)) {
                    text = when {
                        g.key == "cache" && !Cleaner.hasUsageAccess(host) -> "Allow usage access to see the size"
                        g.key == "cache" && g.size == 0L -> "Android 11 doesn't report this size. ${g.hint}"
                        g.key == "cache" -> g.hint
                        g.key == "sdm_empty" -> "${plural(g.items.size, "folder")} · ${g.hint.lowercase()}"
                        g.key == "corpses" -> "${plural(g.items.size, "folder")} · ${g.hint.replaceFirstChar { it.lowercase() }}"
                        else -> "${plural(g.items.size, when {
                            g.items.all { it.recursive } -> "folder"
                            g.items.any { it.recursive } -> "item"
                            else -> "file"
                        })} · ${g.hint}"
                    }
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                }, ui.lp(top = 3))
            }, ui.lp(w = 0, weight = 1f, start = 12))
            addView(sizeText, ui.lp(w = WRAP_CONTENT, start = 8))
            chevron?.let { addView(it, ui.lp(w = ui.dp(20), h = ui.dp(20), start = 4)) }
            addView(check, ui.lp(w = ui.dp(34), h = ui.dp(34), start = 4))
            setOnClickListener {
                if (g.listItems) {
                    g.expanded = !g.expanded
                    items.visibility = if (g.expanded) View.VISIBLE else View.GONE
                    if (g.expanded) fillItems(g, items)
                    refreshHeader()
                } else check.performClick()
            }
        }
        addView(header)
        addView(items)
        if (g.expanded) fillItems(g, items)
        refreshHeader()
        // Keep the header in sync when single files are ticked.
        items.tag = Runnable { refreshHeader() }
    }

    private fun fillItems(g: JunkGroup, box: LinearLayout) {
        if (!g.listItems || !g.expanded) return
        box.removeAllViews()
        for (item in g.items.take(MAX_LISTED)) {
            val check = ImageView(context()).apply { setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) }
            ui.setCheck(check, item.checked)
            box.addView(ui.row {
                background = ui.pressable(ui.rounded(android.graphics.Color.TRANSPARENT, 16))
                setPadding(ui.dp(60), ui.dp(8), ui.dp(12), ui.dp(8))
                addView(check, ui.lp(w = ui.dp(28), h = ui.dp(28)))
                addView(ui.column {
                    addView(ui.text(14f, color(R.color.ink)) {
                        text = item.file.name; maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
                    })
                    addView(ui.text(12f, color(R.color.ink_soft)) {
                        text = item.note; maxLines = 1; ellipsize = TextUtils.TruncateAt.START
                    }, ui.lp(top = 2))
                }, ui.lp(w = 0, weight = 1f, start = 10))
                addView(ui.text(13f, color(R.color.ink_soft)) { text = fmtSize(item.size) }, ui.lp(w = WRAP_CONTENT, start = 8))
                setOnClickListener {
                    item.checked = !item.checked
                    ui.setCheck(check, item.checked)
                    (box.tag as? Runnable)?.run()
                    updateCleanButton()
                }
            })
        }
        if (g.items.size > MAX_LISTED) {
            box.addView(ui.text(13f, color(R.color.ink_soft)) {
                text = "+ ${g.items.size - MAX_LISTED} smaller files (ticked with the group)"
                setPadding(ui.dp(90), ui.dp(8), ui.dp(12), ui.dp(8))
            })
        }
    }

    private fun context() = host

    /** Icon colour and tile colour per junk group. */
    private fun groupColors(key: String) = when (key) {
        "cache" -> R.color.cat_blue to R.color.blue
        "sdm_thumbs", "sdm_screenshots" -> R.color.cat_teal to R.color.green
        "sdm_temp", "sdm_logs" -> R.color.danger to R.color.peach
        "sdm_empty" -> R.color.cat_yellow to R.color.yellow
        "sdm_apks" -> R.color.cat_green to R.color.green_tile
        "sdm_ads", "sdm_analytics" -> R.color.magenta to R.color.pink
        "sdm_linux", "sdm_mac", "sdm_windows", "sdm_trashed", "sdm_lostdir" -> R.color.cat_blue to R.color.sky
        "corpses" -> R.color.cat_salmon to R.color.salmon
        "dups" -> R.color.cat_purple to R.color.purple
        else -> R.color.cat_salmon to R.color.peach_tile
    }

    private fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

    private fun selection(): Triple<List<JunkItem>, Boolean, Long> {
        val files = groups.filter { it.key != "cache" }.flatMap { g -> g.items.filter { it.checked } }
        val cache = cacheGroup?.items?.firstOrNull()?.checked == true && groups.contains(cacheGroup)
        val bytes = files.distinctBy { it.file.path }.sumOf { it.size } + (if (cache) cacheGroup!!.size else 0L)
        return Triple(files, cache, bytes)
    }

    private fun updateCleanButton() {
        val (files, cache, bytes) = selection()
        cleanButton.visibility = if (groups.isEmpty()) View.GONE else View.VISIBLE
        scanButton.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
        ui.setLabel(cleanButton, if (bytes > 0) "Clean now · ${fmtSize(bytes)}" else "Clean now")
        ui.setEnabled(cleanButton, files.isNotEmpty() || cache)
        showRing()
    }

    private fun confirmClean() {
        val (files, cache, bytes) = selection()
        if (files.isEmpty() && !cache) return
        val message = buildString {
            if (files.size == 1) append("1 item will be deleted. It can't be recovered.")
            else if (files.isNotEmpty()) append("${files.size} files and folders will be deleted. They can't be recovered.")
            if (cache) {
                if (isNotEmpty()) append("\n\n")
                append("Android will then ask you to confirm clearing the cache of all apps.")
            }
        }
        AlertDialog.Builder(host)
            .setTitle(if (bytes > 0) "Clean ${fmtSize(bytes)}?" else "Clean now?")
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clean") { _, _ -> clean(files, cache) }
            .show()
    }

    private fun clean(files: List<JunkItem>, cache: Boolean) {
        ui.setEnabled(cleanButton, false)
        ui.setLabel(cleanButton, "Cleaning…")
        background({ Cleaner.delete(host, files) }) { r ->
            val (freed, failed) = r.getOrDefault(0L to files.size)
            freedValue.text = fmtSize(freed)
            freedCard.visibility = View.VISIBLE
            insight.text = if (failed > 0)
                "${plural(failed, "item")} couldn't be deleted (they may be in use or on a read-only SD card)." else ""
            insightRow.visibility = if (failed > 0) View.VISIBLE else View.GONE
            if (cache && Build.VERSION.SDK_INT >= 30) {
                // The system shows its own confirmation, then clears every app's cache.
                runCatching { host.startActivityForResult(Intent(StorageManager.ACTION_CLEAR_APP_CACHE), REQ_CLEAR_CACHE) }
                    .onFailure { insight.append(" App caches couldn't be cleared: ${it.message}"); refreshStorage(); scan() }
            } else {
                refreshStorage()
                scan()
            }
        }
    }

    // ---------- insight ----------

    private val usageHint = "Allow usage access to see how much app cache can be cleared."

    private fun updateInsight() {
        val needUsage = Build.VERSION.SDK_INT >= 30 && Cleaner.hasFileAccess(host) && !Cleaner.hasUsageAccess(host)
        val showingResult = !insight.text.isNullOrEmpty() && insight.text.toString() != usageHint
        when {
            showingResult -> insightRow.visibility = View.VISIBLE // keep "couldn't delete" visible
            needUsage -> { insight.text = usageHint; insightRow.visibility = View.VISIBLE }
            else -> { insight.text = ""; insightRow.visibility = View.GONE }
        }
        // Only the usage-access hint is tappable (it opens the setting).
        if (insight.text.toString() == usageHint) insightRow.setOnClickListener { onInsightTap() }
        else { insightRow.setOnClickListener(null); insightRow.isClickable = false }
    }

    private fun onInsightTap() {
        if (Build.VERSION.SDK_INT >= 30 && !Cleaner.hasUsageAccess(host)) {
            runCatching {
                host.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,
                    Uri.fromParts("package", host.packageName, null)))
            }.onFailure { runCatching { host.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } }
        }
    }
}
