// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

package dev.droiddesk.companion

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/**
 * App Manager: every installed app with size, version, dates and usage; sort, filter, search;
 * details per app; open, App info, Play Store, export the APK, and uninstall (one or many).
 * Uninstalling always goes through Android's own confirmation. Force stop and disabling apps need
 * root or a USB shell, so they're not offered here.
 */
internal class AppsScreen(host: MainActivity) : Screen(host) {

    override val name = "Apps"
    override val navIcon = R.drawable.ic_apps
    override val navColor = R.color.bg

    private companion object {
        const val REQ_UNINSTALL = 9
        const val MAX_ROWS = 400
    }

    private enum class Filter(val label: String) { USER("User"), SYSTEM("System"), ALL("All") }

    private enum class Sort(val label: String) {
        SIZE("Size"), NAME("Name"), PACKAGE("Package name"), INSTALLED("Install date"),
        UPDATED("Last update"), SCREEN_TIME("Screen time"), LAST_USED("Least recently used"),
    }

    private var apps: List<AppEntry> = emptyList()
    private var filter = Filter.USER
    private var sort = Sort.SIZE
    private var loaded = false
    private var loading = false
    private val queue = ArrayDeque<String>()
    private val dates = DateFormat.getDateInstance(DateFormat.MEDIUM)

    private lateinit var search: EditText
    private lateinit var chips: Map<Filter, TextView>
    private lateinit var count: TextView
    private lateinit var sortPill: TextView
    private lateinit var list: LinearLayout
    private lateinit var usageRow: View
    private lateinit var actions: LinearLayout
    private lateinit var exportBtn: TextView
    private lateinit var uninstall: TextView

    override fun build(): View = ui.column {
        addView(ui.topBar("Apps", host::goHome, R.drawable.ic_search, "Search") { toggleSearch() }.first)
        search = EditText(context).apply {
            hint = "Search by name or package"
            textSize = 15f
            setTextColor(color(R.color.ink))
            setHintTextColor(color(R.color.ink_faint))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = ui.rounded(color(R.color.surface), 18)
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
            visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = render()
            })
        }
        addView(search, ui.lp(top = 14))
        addView(ui.row {
            chips = Filter.values().associateWith { f ->
                ui.pill(f.label, 0, 0) { filter = f; render() }.apply { textSize = 14f; minHeight = ui.dp(38) }
            }
            chips.values.forEachIndexed { i, chip -> addView(chip, ui.lp(w = 0, weight = 1f, start = if (i == 0) 0 else 8)) }
        }, ui.lp(top = 16))
        addView(ui.row {
            count = ui.text(22f, color(R.color.ink), ui.light) { text = "Loading apps…"; letterSpacing = -0.02f }
            addView(count, ui.lp(w = 0, weight = 1f))
            sortPill = ui.pill("", color(R.color.surface), color(R.color.ink), R.drawable.ic_expand) { pickSort() }
                .apply { textSize = 13f; minHeight = ui.dp(36) }
            addView(sortPill, ui.lp(w = WRAP_CONTENT, start = 8))
        }, ui.lp(top = 18))
        val (row, label) = ui.insight(R.drawable.ic_info, color(R.color.yellow)) { openUsageAccess() }
        label.text = "Allow usage access to see app sizes, screen time and when apps were last used."
        usageRow = row
        addView(row, ui.lp(top = 12))
        list = ui.card(color(R.color.surface), 6)
        addView(list, ui.lp(top = 12))
        actions = ui.row {
            exportBtn = ui.pill("Export", color(R.color.surface), color(R.color.ink), R.drawable.ic_down) { exportSelected() }
                .apply { textSize = 15f; minHeight = ui.dp(54) }
            uninstall = ui.pill("Uninstall", color(R.color.fab), color(R.color.on_fab), R.drawable.ic_trash) { uninstallSelected() }
                .apply { textSize = 15f; minHeight = ui.dp(54) }
            addView(exportBtn, ui.lp(w = 0, weight = 1f))
            addView(uninstall, ui.lp(w = 0, weight = 1f, start = 10))
        }
        addView(actions, ui.lp(top = 16))
    }

    override fun onShow() {
        usageRow.visibility = if (Cleaner.hasUsageAccess(host)) View.GONE else View.VISIBLE
        if (!loaded) load()
    }

    override fun onActivityResult(requestCode: Int) {
        if (requestCode == REQ_UNINSTALL) {
            if (queue.isNotEmpty()) uninstallNext() else load()
        }
    }

    // ---------- data ----------

    private fun load() {
        if (loading) return
        loading = true
        background({ AppTools.load(host) }) { r ->
            loading = false
            loaded = true
            val keep = apps.filter { it.checked }.map { it.pkg }.toSet()
            apps = r.getOrDefault(emptyList()).onEach { it.checked = it.pkg in keep }
            render()
        }
    }

    private fun visible(): List<AppEntry> {
        val q = search.text.toString().trim()
        val shown = apps.filter {
            when (filter) {
                Filter.USER -> !it.system
                Filter.SYSTEM -> it.system
                Filter.ALL -> true
            } && (q.isEmpty() || it.label.contains(q, ignoreCase = true) || it.pkg.contains(q, ignoreCase = true))
        }
        return when (sort) {
            Sort.SIZE -> shown.sortedWith(compareByDescending<AppEntry> { it.size ?: -1L }.thenBy { it.label.lowercase() })
            Sort.NAME -> shown.sortedBy { it.label.lowercase() }
            Sort.PACKAGE -> shown.sortedBy { it.pkg }
            Sort.INSTALLED -> shown.sortedByDescending { it.installedAt }
            Sort.UPDATED -> shown.sortedByDescending { it.updatedAt }
            Sort.SCREEN_TIME -> shown.sortedByDescending { it.screenTimeMs ?: -1L }
            Sort.LAST_USED -> shown.sortedBy { it.lastUsed?.takeIf { t -> t > 0 } ?: 0L }
        }
    }

    // ---------- list ----------

    private fun render() {
        chips.forEach { (f, chip) ->
            if (f == filter) ui.stylePill(chip, f.label, color(R.color.fab), color(R.color.on_fab)) { filter = f; render() }
            else ui.stylePill(chip, f.label, color(R.color.surface), color(R.color.ink)) { filter = f; render() }
        }
        ui.setLabel(sortPill, sort.label)
        val shown = visible()
        count.text = if (loading && apps.isEmpty()) "Loading apps…" else "${shown.size} apps"
        list.removeAllViews()
        for (app in shown.take(MAX_ROWS)) list.addView(appRow(app))
        if (shown.size > MAX_ROWS) list.addView(note("Showing the first $MAX_ROWS. Search to find others."))
        if (shown.isEmpty() && loaded) list.addView(note("No apps match."))
        list.visibility = if (loaded) View.VISIBLE else View.GONE
        updateActions()
    }

    private fun note(t: String) = ui.text(14f, color(R.color.ink_soft)) {
        text = t
        setPadding(ui.dp(12), ui.dp(16), ui.dp(12), ui.dp(16))
    }

    /** What the second line says depends on the sort order. */
    private fun subtitle(app: AppEntry): String {
        val parts = mutableListOf<String>()
        when (sort) {
            Sort.INSTALLED -> parts += "Installed ${dates.format(Date(app.installedAt))}"
            Sort.UPDATED -> parts += "Updated ${dates.format(Date(app.updatedAt))}"
            Sort.SCREEN_TIME -> parts += app.screenTimeMs?.let { "${duration(it)} in ${AppTools.USAGE_DAYS} days" } ?: "No usage data"
            Sort.LAST_USED -> parts += lastUsed(app)
            Sort.PACKAGE -> parts += app.pkg
            else -> {
                app.size?.let { parts += fmtSize(it) }
                app.versionName?.let { parts += "v$it" }
            }
        }
        if (!app.enabled) parts += "Disabled"
        else if (app.system) parts += if (app.updatedSystem) "System (updated)" else "System"
        return parts.joinToString(" · ").ifEmpty { app.pkg }
    }

    private fun lastUsed(app: AppEntry): String {
        if (!Cleaner.hasUsageAccess(host)) return "No usage data"
        val t = app.lastUsed?.takeIf { it > 0 } ?: return "Not used in ${AppTools.USAGE_DAYS} days"
        return "Used " + DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    }

    private fun duration(ms: Long): String {
        val min = ms / 60_000
        return when {
            min < 1 -> "Under a minute"
            min < 60 -> "$min min"
            else -> "${min / 60} h ${min % 60} min"
        }
    }

    private fun appRow(app: AppEntry) = ui.row {
        background = ui.pressable(ui.rounded(color(R.color.surface), 18))
        setPadding(ui.dp(10), ui.dp(10), ui.dp(8), ui.dp(10))
        addView(ImageView(context).apply {
            setImageDrawable(app.icon)
            if (!app.enabled) alpha = 0.4f
        }, ui.lp(w = ui.dp(42), h = ui.dp(42)))
        addView(ui.column {
            addView(ui.text(15f, color(R.color.ink), ui.medium) {
                text = app.label; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            addView(ui.text(12f, color(R.color.ink_soft)) {
                text = subtitle(app); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, ui.lp(top = 3))
        }, ui.lp(w = 0, weight = 1f, start = 14))
        val check = ImageView(context).apply { setPadding(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5)) }
        fun paint() = ui.setCheck(check, app.checked)
        paint()
        check.contentDescription = "Select ${app.label}"
        check.setOnClickListener { app.checked = !app.checked; paint(); updateActions() }
        addView(check, ui.lp(w = ui.dp(36), h = ui.dp(36), start = 8))
        setOnClickListener { showDetails(app) }
    }

    private fun updateActions() {
        val picked = apps.filter { it.checked }
        val removable = picked.count { it.removable }
        actions.visibility = if (picked.isEmpty()) View.GONE else View.VISIBLE
        ui.setLabel(exportBtn, "Export (${picked.size})")
        ui.setLabel(uninstall, "Uninstall ($removable)")
        ui.setEnabled(uninstall, removable > 0 && queue.isEmpty())
    }

    private fun pickSort() {
        val options = Sort.values()
        AlertDialog.Builder(host)
            .setTitle("Sort apps by")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), options.indexOf(sort)) { d, which ->
                sort = options[which]; d.dismiss(); render()
            }
            .show()
    }

    // ---------- details ----------

    private fun showDetails(app: AppEntry) {
        lateinit var dialog: AlertDialog
        val body = ui.column {
            setPadding(ui.dp(22), ui.dp(20), ui.dp(22), ui.dp(8))
            addView(ui.row {
                addView(ImageView(context).apply { setImageDrawable(app.icon) }, ui.lp(w = ui.dp(56), h = ui.dp(56)))
                addView(ui.column {
                    addView(ui.text(19f, color(R.color.ink), ui.medium) { text = app.label })
                    addView(ui.text(12f, color(R.color.ink_soft)) { text = app.pkg; setTextIsSelectable(true) }, ui.lp(top = 3))
                }, ui.lp(w = 0, weight = 1f, start = 14))
            })
            val rows = listOfNotNull(
                "Version" to "${app.versionName ?: "?"} (${app.versionCode})",
                "Status" to when {
                    !app.enabled -> "Disabled"
                    app.updatedSystem -> "System app, updated"
                    app.system -> "System app"
                    else -> "User app"
                },
                "Installed" to dates.format(Date(app.installedAt)),
                "Last update" to dates.format(Date(app.updatedAt)),
                "Installed from" to AppTools.installerLabel(host, app.installer),
                "Built for" to "${AppTools.androidName(app.targetSdk)} (API ${app.targetSdk})",
                app.minSdk.takeIf { it > 0 }?.let { "Needs at least" to "${AppTools.androidName(it)} (API $it)" },
                app.appBytes?.let { "App" to fmtSize(it) },
                app.dataBytes?.let { d -> "Data" to fmtSize(d - (app.cacheBytes ?: 0)) },
                app.cacheBytes?.let { "Cache" to fmtSize(it) },
                app.size?.let { "Total" to fmtSize(it) },
                app.screenTimeMs?.let { "Screen time" to "${duration(it)} in ${AppTools.USAGE_DAYS} days" },
                if (Cleaner.hasUsageAccess(host)) "Last used" to lastUsed(app).removePrefix("Used ") else null,
                "APK files" to if (app.apkPaths.size > 1) "${app.apkPaths.size} (split APK)" else "1",
            )
            addView(ui.column {
                background = ui.rounded(color(R.color.bg), 18)
                setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(14))
                for ((k, v) in rows) {
                    val (r, value) = ui.keyValue(k)
                    value.text = v
                    addView(r, ui.lp(top = 10))
                }
            }, ui.lp(top = 16))

            val buttons = buildList<Triple<String, Int, () -> Unit>> {
                if (app.launchable && app.enabled) add(Triple("Open", R.drawable.ic_arrow_right) {
                    host.packageManager.getLaunchIntentForPackage(app.pkg)?.let { host.startActivity(it) }
                })
                add(Triple("App info", R.drawable.ic_info) {
                    host.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg, null)))
                })
                add(Triple("Export APK", R.drawable.ic_down) { export(listOf(app)) })
                add(Triple("Play Store", R.drawable.ic_globe) {
                    runCatching { host.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${app.pkg}"))) }
                        .onFailure { host.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${app.pkg}"))) }
                })
                if (app.removable) add(Triple(if (app.system) "Uninstall updates" else "Uninstall", R.drawable.ic_trash) {
                    queue.clear(); queue.add(app.pkg); uninstallNext()
                })
            }
            buttons.chunked(2).forEachIndexed { r, pair ->
                addView(ui.row {
                    pair.forEachIndexed { i, (label, icon, action) ->
                        val danger = label.startsWith("Uninstall")
                        addView(ui.pill(label, color(if (danger) R.color.fab else R.color.bg),
                            color(if (danger) R.color.on_fab else R.color.ink), icon) {
                            dialog.dismiss()
                            runCatching { action() }.onFailure { toast("Couldn't do that: ${it.message}") }
                        }.apply { textSize = 13f }, ui.lp(w = 0, weight = 1f, start = if (i == 0) 0 else 8))
                    }
                    if (pair.size == 1) addView(ui.spacer(), ui.lp(w = 0, h = 1, weight = 1f, start = 8))
                }, ui.lp(top = if (r == 0) 16 else 8))
            }
        }
        dialog = AlertDialog.Builder(host)
            .setView(ScrollView(host).apply { addView(body) })
            .setPositiveButton("Close", null)
            .show()
    }

    // ---------- actions ----------

    private fun toast(msg: String) = android.widget.Toast.makeText(host, msg, android.widget.Toast.LENGTH_LONG).show()

    private fun exportSelected() = export(apps.filter { it.checked })

    private fun export(picked: List<AppEntry>) {
        if (picked.isEmpty()) return
        ui.setEnabled(exportBtn, false)
        ui.setLabel(exportBtn, "Exporting…")
        background({ picked.map { app -> app to runCatching { AppTools.export(host, app) } } }) { r ->
            ui.setEnabled(exportBtn, true)
            updateActions()
            val results = r.getOrDefault(emptyList())
            val ok = results.mapNotNull { it.second.getOrNull() }
            val failed = results.filter { it.second.isFailure }
            val msg = buildString {
                if (ok.size == 1) append("Saved to ${ok.single()}")
                else if (ok.isNotEmpty()) append("Saved ${ok.size} files to Download/Droid Desk/APKs")
                if (failed.isNotEmpty()) {
                    if (isNotEmpty()) append(". ")
                    append("Couldn't export ${failed.joinToString { it.first.label }}: ${failed.first().second.exceptionOrNull()?.message}")
                }
            }
            AlertDialog.Builder(host).setTitle("Export").setMessage(msg).setPositiveButton("OK", null).show()
        }
    }

    private fun uninstallSelected() {
        val picked = apps.filter { it.checked && it.removable }
        if (picked.isEmpty()) return
        queue.clear()
        queue.addAll(picked.map { it.pkg })
        picked.forEach { it.checked = false }
        uninstallNext()
    }

    /** Android shows its own "Do you want to uninstall?" dialog for each app; we wait for each one. */
    private fun uninstallNext() {
        val pkg = queue.removeFirstOrNull() ?: return load()
        updateActions()
        runCatching {
            @Suppress("DEPRECATION")
            host.startActivityForResult(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null)), REQ_UNINSTALL)
        }.onFailure { uninstallNext() }
    }

    private fun toggleSearch() {
        val show = search.visibility != View.VISIBLE
        search.visibility = if (show) View.VISIBLE else View.GONE
        val imm = host.getSystemService(InputMethodManager::class.java)
        if (show) { search.requestFocus(); imm?.showSoftInput(search, 0) }
        else { search.setText(""); imm?.hideSoftInputFromWindow(search.windowToken, 0) }
    }

    private fun openUsageAccess() {
        runCatching {
            host.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.fromParts("package", host.packageName, null)))
        }.onFailure { runCatching { host.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } }
    }
}
