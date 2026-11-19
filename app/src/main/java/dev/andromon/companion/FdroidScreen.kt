// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * F-Droid: browse the catalogue of free and open-source apps, and install or update them on this
 * phone. The downloading and checking is in [Fdroid]. Opened from Tools.
 */
internal class FdroidScreen(host: MainActivity) : Screen(host) {

    override val name = "F-Droid"
    override val navIcon = R.drawable.ic_bag
    override val navColor = R.color.bg

    private companion object {
        const val PAGE = 30
        const val REQ_UNINSTALL = 4410
    }

    private enum class Tab(val label: String) { BROWSE("All apps"), MINE("On this phone") }

    // ----- the list page -----
    private lateinit var listPage: LinearLayout
    private lateinit var setup: View
    private lateinit var jobCard: View
    private lateinit var batchCard: View
    private lateinit var batchText: TextView
    private lateinit var batchSub: TextView
    private lateinit var updateAll: TextView
    private lateinit var jobText: TextView
    private lateinit var jobMeter: Meter
    private lateinit var notice: TextView
    private lateinit var browse: LinearLayout
    private lateinit var search: EditText
    private lateinit var chips: Map<Tab, TextView>
    private lateinit var count: TextView
    private lateinit var filters: View
    private lateinit var categoryPill: TextView
    private lateinit var sortPill: TextView
    private lateinit var list: LinearLayout
    private lateinit var more: TextView
    private lateinit var foot: TextView

    private var tab = Tab.BROWSE
    private var category = "" // id; "" = all
    private var sort = Fdroid.Sort.UPDATED
    /** The person chose an order while a search word was typed; otherwise a search sorts by best match. */
    private var sortPickedInSearch = false

    /** The order in use: best match while searching (unless another was picked), else the chosen one. */
    private fun sortNow(q: String) = when {
        q.isNotEmpty() && !sortPickedInSearch -> Fdroid.Sort.MATCH
        q.isEmpty() && sort == Fdroid.Sort.MATCH -> Fdroid.Sort.UPDATED
        else -> sort
    }
    private var shown = 0
    private var total = 0
    private var token = 0
    private var loaded = false
    private var message: String? = null
    private var listScroll = 0
    /** The apps with an update, from the last "On this phone" list: what "Update all" goes through. */
    private var updatable: List<Fdroid.Item> = emptyList()

    // ----- the app page -----
    private lateinit var appPage: LinearLayout
    private var app: Fdroid.Item? = null
    private var appAction: LinearLayout? = null
    private var appMessage: String? = null

    private val dates = DateFormat.getDateInstance(DateFormat.MEDIUM)
    private val iconPool = Executors.newFixedThreadPool(3) { r -> Thread(r, "fdroid-icons").apply { isDaemon = true } }
    private val reload = Runnable { load(true) }

    override fun build(): View = ui.column {
        listPage = ui.column {
            addView(ui.topBar("F-Droid", host::goHome, R.drawable.ic_refresh, "Check for new apps") { refresh() }.first)
            addView(ui.text(14f, color(R.color.ink_soft)) {
                text = "Free and open-source apps from f-droid.org. Every download is checked against F-Droid's signed catalogue before it is installed."
                setLineSpacing(ui.dp(3).toFloat(), 1f)
            }, ui.lp(top = 10))
            setup = ui.glassCard(16) {
                addView(ui.text(16f, color(R.color.ink), ui.medium) { text = "Get the catalogue" })
                addView(ui.text(13f, color(R.color.ink_soft)) {
                    text = "The list of apps is downloaded from f-droid.org and kept on this phone. It is a large download, so Wi‑Fi is best."
                    setLineSpacing(ui.dp(2).toFloat(), 1f)
                }, ui.lp(top = 4))
                addView(ui.pill("Download", color(R.color.fab), color(R.color.on_fab), R.drawable.ic_down) { refresh() }, ui.lp(top = 14))
            }
            addView(setup, ui.lp(top = 16))
            batchCard = ui.glassCard(16) {
                batchText = ui.text(16f, color(R.color.ink), ui.medium)
                addView(batchText)
                batchSub = ui.text(13f, color(R.color.ink_soft)) { setLineSpacing(ui.dp(2).toFloat(), 1f) }
                addView(batchSub, ui.lp(top = 4))
                addView(ui.pill("Stop", color(R.color.glass_strong), color(R.color.ink)) { Fdroid.stopUpdates() },
                    ui.lp(w = WRAP_CONTENT, top = 14))
            }
            addView(batchCard, ui.lp(top = 16))
            jobCard = ui.glassCard(16) {
                jobText = ui.text(14f, color(R.color.ink), ui.medium)
                addView(jobText)
                jobMeter = Meter(context).apply { colors(color(R.color.glass_strong), color(R.color.ink)) }
                addView(jobMeter, ui.lp(h = ui.dp(8), top = 12))
                addView(ui.pill("Cancel", color(R.color.glass_strong), color(R.color.ink)) { Fdroid.cancel() }, ui.lp(w = WRAP_CONTENT, top = 14))
            }
            addView(jobCard, ui.lp(top = 16))
            notice = ui.text(13f, color(R.color.ink)) { setLineSpacing(ui.dp(2).toFloat(), 1f) }
            addView(notice, ui.lp(top = 14))
            browse = ui.column { buildBrowse() }
            addView(browse, ui.lp(top = 16))
        }
        appPage = ui.column { visibility = View.GONE }
        addView(listPage)
        addView(appPage)
    }

    private fun LinearLayout.buildBrowse() {
        search = EditText(context).apply {
            hint = "Search apps"
            textSize = 15f
            setTextColor(color(R.color.ink))
            setHintTextColor(color(R.color.ink_faint))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = ui.rounded(color(R.color.surface), 18)
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { removeCallbacks(reload); postDelayed(reload, 300) }
            })
            setOnEditorActionListener { _, _, _ -> hideKeyboard(); true }
        }
        addView(search)
        addView(ui.row {
            chips = Tab.values().associateWith { t ->
                ui.pill(t.label, 0, 0) { if (tab != t) { tab = t; renderChips(); load(true) } }.apply { textSize = 14f; minHeight = ui.dp(38) }
            }
            chips.values.forEachIndexed { i, chip -> addView(chip, ui.lp(w = 0, weight = 1f, start = if (i == 0) 0 else 8)) }
        }, ui.lp(top = 14))
        count = ui.text(22f, color(R.color.ink), ui.light) { text = "–"; letterSpacing = -0.02f }
        addView(count, ui.lp(top = 18))
        updateAll = ui.pill("Update all", color(R.color.fab), color(R.color.on_fab), R.drawable.ic_down) { startUpdateAll() }
            .apply { visibility = View.GONE }
        addView(updateAll, ui.lp(w = WRAP_CONTENT, top = 12))
        filters = ui.row {
            categoryPill = ui.pill("", color(R.color.surface), color(R.color.ink), R.drawable.ic_expand) { pickCategory() }
                .apply { textSize = 13f; minHeight = ui.dp(36); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            sortPill = ui.pill("", color(R.color.surface), color(R.color.ink), R.drawable.ic_expand) { pickSort() }
                .apply { textSize = 13f; minHeight = ui.dp(36); maxLines = 1 }
            addView(categoryPill, ui.lp(w = 0, weight = 1f))
            addView(sortPill, ui.lp(w = 0, weight = 1f, start = 8))
        }
        addView(filters, ui.lp(top = 12))
        list = ui.column()
        addView(list, ui.lp(top = 4))
        more = ui.pill("Show more", color(R.color.surface), color(R.color.ink), R.drawable.ic_down) { load(false) }
        addView(more, ui.lp(top = 14))
        foot = ui.text(12f, color(R.color.ink_soft)) { setLineSpacing(ui.dp(2).toFloat(), 1f) }
        addView(foot, ui.lp(top = 22))
        addView(ui.pill("Remove the catalogue", color(R.color.glass_strong), color(R.color.ink), R.drawable.ic_trash) { confirmClear() }
            .apply { textSize = 13f; minHeight = ui.dp(36) }, ui.lp(w = WRAP_CONTENT, top = 12))
    }

    // ---------- lifecycle ----------

    override fun onShow() {
        Fdroid.listener = ::onEvent
        Fdroid.dropStaleInstall(host)
        render()
        if (app != null) reloadApp()
        else if (!loaded && Fdroid.status(host) != null) load(true)
    }

    override fun onHide() {
        Fdroid.listener = null
        if (::search.isInitialized) search.removeCallbacks(reload)
    }

    override fun onLeave() { if (app != null) closeApp(restoreScroll = false) }

    override fun onBack(): Boolean {
        if (app == null) return false
        closeApp(restoreScroll = true)
        return true
    }

    override fun onActivityResult(requestCode: Int) {
        if (requestCode == REQ_UNINSTALL) { loaded = false; reloadApp() }
    }

    private fun onEvent(e: Fdroid.Event) {
        when (e) {
            is Fdroid.Event.Progress -> { renderJob(); renderBatch(); renderAppAction() }
            is Fdroid.Event.CatalogueDone -> {
                message = if (e.changed) null else "The catalogue is already up to date."
                render()
                if (e.changed || !loaded) load(true)
            }
            is Fdroid.Event.Failed -> {
                val text = if (e.cancelled) null else e.message
                if (e.pkg != null && e.pkg == app?.app?.pkg) appMessage = text else message = text
                render()
            }
            is Fdroid.Event.Confirm -> runCatching { host.startActivity(e.intent) }
            is Fdroid.Event.InstallDone -> {
                loaded = false
                if (e.pkg == app?.app?.pkg) { appMessage = if (e.ok) null else e.message; reloadApp() }
                else { if (!e.ok) message = e.message; render(); if (app == null) load(true) }
            }
            is Fdroid.Event.UpdatesDone -> {
                message = when {
                    e.updated + e.notUpdated == 0 && e.stopped -> "Stopped. No apps were updated."
                    else -> (if (e.stopped) "Stopped. " else "") + plural(e.updated, "app") + " updated" +
                        if (e.notUpdated > 0) ", ${e.notUpdated} not (cancelled or failed)." else "."
                }
                loaded = false
                render()
                if (app == null) load(true)
            }
        }
    }

    // ---------- the list page ----------

    private fun refresh() {
        message = null
        if (!Fdroid.update(host)) message = "A download is running. Wait for it to finish."
        render()
    }

    private fun render() {
        val st = Fdroid.status(host)
        val j = Fdroid.job
        setup.visibility = if (st == null && j?.pkg != null || st == null && j == null) View.VISIBLE else View.GONE
        browse.visibility = if (st != null) View.VISIBLE else View.GONE
        notice.text = message.orEmpty()
        notice.visibility = if (message == null) View.GONE else View.VISIBLE
        if (st != null) {
            foot.text = "Catalogue from ${dates.format(Date(st.timestamp))} · %,d apps".format(st.apps) +
                (if (st.fetchedAt > 0) " · checked ${ago(st.fetchedAt)}" else "") + "\nTap the refresh button to look for new apps and updates."
            ui.setLabel(categoryPill, st.categories.firstOrNull { it.first == category }?.second ?: "All categories")
            ui.setLabel(sortPill, sortNow(search.text.toString().trim()).label)
            renderChips()
        }
        renderJob()
        renderBatch()
        renderAppAction()
    }

    /** "Update all": the button on the "On this phone" tab, and the progress while it runs. */
    private fun renderBatch() {
        val b = Fdroid.batch
        updateAll.visibility = if (tab == Tab.MINE && b == null && updatable.isNotEmpty()) View.VISIBLE else View.GONE
        ui.setEnabled(updateAll, Fdroid.job == null && Fdroid.installing == null)
        batchCard.visibility = if (b == null) View.GONE else View.VISIBLE
        val item = b?.current ?: return
        batchText.text = if (b.stopping) "Stopping after ${item.app.name}…" else "Updating ${b.index + 1} of ${b.items.size}…"
        batchSub.text = item.app.name + when {
            Fdroid.installing?.first == item.app.pkg -> " · confirm the update in Android's prompt"
            else -> ""
        } + "\nAndroid asks before each app is updated. Cancelling one goes on with the next."
    }

    private fun startUpdateAll() {
        if (!mayInstall("Update all")) return
        message = if (Fdroid.updateAll(host, updatable)) null else "A download is running. Wait for it to finish."
        render()
    }

    /** Android asks once before the companion may install apps; until then, say how. */
    private fun mayInstall(button: String): Boolean {
        if (host.packageManager.canRequestPackageInstalls()) return true
        UiRows(host, ui).message("Allow installing apps",
            "Android asks once before Andromon Companion may install apps. Turn on “Allow from this source” on the next page, " +
                "then come back and tap $button again.", "Open settings") {
            runCatching { host.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${host.packageName}"))) }
        }
        return false
    }

    private fun renderChips() {
        for ((t, chip) in chips) {
            val on = t == tab
            ui.stylePill(chip, t.label, color(if (on) R.color.fab else R.color.surface), color(if (on) R.color.on_fab else R.color.ink)) {
                if (tab != t) { tab = t; renderChips(); load(true) }
            }
        }
        filters.visibility = if (tab == Tab.BROWSE) View.VISIBLE else View.GONE
        renderBatch()
    }

    private fun jobLine(j: Fdroid.Job): String {
        val what = if (j.pkg == null) "the catalogue" else j.label
        return when (j.phase) {
            Fdroid.Phase.DOWNLOAD -> "Downloading $what" + when {
                j.total > 0 && j.done > 0 -> " · ${fmtSize(j.done)} of ${fmtSize(j.total)}"
                j.done > 0 -> " · ${fmtSize(j.done)}"
                else -> "…"
            }
            Fdroid.Phase.CHECK -> "Checking the download…"
            Fdroid.Phase.READ -> "Reading the catalogue…"
            Fdroid.Phase.INSTALL -> "Getting ${j.label} ready to install…"
        }
    }

    private fun jobFraction(j: Fdroid.Job) = if (j.phase != Fdroid.Phase.DOWNLOAD) 1f else if (j.total > 0) j.done.toFloat() / j.total else 0f

    private fun renderJob() {
        val j = Fdroid.job
        jobCard.visibility = if (j == null) View.GONE else View.VISIBLE
        if (j == null) return
        jobText.text = jobLine(j)
        jobMeter.fraction = jobFraction(j)
    }

    private fun pickCategory() {
        val st = Fdroid.status(host) ?: return
        val choices = listOf("" to "All categories") + st.categories
        UiRows(host, ui).pick("Category", choices, choices.firstOrNull { it.first == category } ?: choices[0], { it.second }) {
            category = it.first
            ui.setLabel(categoryPill, it.second)
            load(true)
        }
    }

    private fun pickSort() {
        val q = search.text.toString().trim()
        val choices = Fdroid.Sort.values().filter { q.isNotEmpty() || it != Fdroid.Sort.MATCH }
        UiRows(host, ui).pick("Sort by", choices, sortNow(q), { it.label }) {
            sort = it
            sortPickedInSearch = q.isNotEmpty()
            ui.setLabel(sortPill, it.label)
            load(true)
        }
    }

    private fun load(reset: Boolean) {
        if (Fdroid.status(host) == null) return
        val my = ++token
        if (reset) shown = 0
        val q = search.text.toString().trim()
        val t = tab
        val cat = category
        if (q.isEmpty()) sortPickedInSearch = false
        val s = sortNow(q)
        ui.setLabel(sortPill, s.label)
        val offset = shown
        ui.setEnabled(more, false)
        if (reset) {
            list.removeAllViews()
            list.addView(ui.skeletonList(6, 48, bg = color(R.color.surface), gapDp = 8), ui.lp(top = 8))
            ui.loading(count, true)
        }
        background({
            if (t == Tab.MINE) {
                val mine = Fdroid.onPhone(host).filter { q.isEmpty() || it.app.name.contains(q, ignoreCase = true) || it.app.pkg.contains(q, ignoreCase = true) }
                    .let { list -> if (q.isEmpty()) list else list.sortedBy { when {
                        it.app.name.equals(q, ignoreCase = true) -> 0
                        it.app.name.startsWith(q, ignoreCase = true) -> 1
                        it.app.name.contains(q, ignoreCase = true) -> 2
                        else -> 3
                    } } }
                Fdroid.Page(mine.size, mine)
            } else Fdroid.search(host, q, cat, s, offset, PAGE)
        }) { r ->
            if (my != token) return@background
            ui.setEnabled(more, true)
            ui.loading(count, false)
            loaded = true
            if (reset) list.removeAllViews()
            val page = r.getOrNull()
            if (page == null) {
                count.text = "–"
                more.visibility = View.GONE
                list.addView(note(r.exceptionOrNull()?.let { (it as? Fdroid.Problem)?.message } ?: "Couldn't read the catalogue. Tap the refresh button to download it again."), ui.lp(top = 8))
                return@background
            }
            total = page.total
            shown += page.items.size
            for (item in page.items) list.addView(row(item, my), ui.lp(top = 8))
            val updates = page.items.count { it.canUpdate }
            // Only a list without a search word has every update in it.
            if (t == Tab.MINE) updatable = if (q.isEmpty()) page.items.filter { it.canUpdate } else updatable
            renderBatch()
            count.text = when {
                t == Tab.MINE -> plural(total, "app") + " from F-Droid" + if (updates > 0) " · " + plural(updates, "update") else ""
                else -> plural(total, "app")
            }
            if (total == 0) list.addView(note(when {
                t == Tab.MINE && q.isEmpty() -> "None of the apps on this phone are in F-Droid's catalogue."
                else -> "No apps match. Try another word or category."
            }), ui.lp(top = 8))
            more.visibility = if (t == Tab.BROWSE && shown < total) View.VISIBLE else View.GONE
        }
    }

    private fun plural(n: Int, noun: String) = "%,d %s".format(n, noun) + if (n == 1) "" else "s"

    private fun note(text: String) = ui.glassCard(16) {
        addView(ui.text(14f, color(R.color.ink)) { this.text = text; setLineSpacing(ui.dp(2).toFloat(), 1f) })
    }

    private fun iconInto(view: ImageView, a: Fdroid.App, forToken: Int? = null) {
        fun set(b: Bitmap) { view.setPadding(0, 0, 0, 0); view.scaleType = ImageView.ScaleType.CENTER_CROP; view.setImageBitmap(b) }
        Fdroid.cachedIcon(a)?.let { set(it); return }
        if (a.iconSha.isEmpty()) return
        iconPool.execute {
            if (forToken != null && forToken != token) return@execute // the list moved on before this one's turn
            val b = Fdroid.icon(host, a) ?: return@execute
            host.runOnUiThread { if (!host.isDestroyed) set(b) }
        }
    }

    private fun iconView(sizeDp: Int) = ImageView(host).apply {
        background = ui.rounded(color(R.color.glass_strong), sizeDp / 4)
        clipToOutline = true
        val p = ui.dp(sizeDp / 4)
        setPadding(p, p, p, p)
        setImageDrawable(ui.icon(R.drawable.ic_apps, color(R.color.ink_soft)))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun row(item: Fdroid.Item, forToken: Int) = ui.row {
        val a = item.app
        background = ui.pressable(ui.rounded(color(R.color.surface), 18))
        setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
        val picture = iconView(48)
        iconInto(picture, a, forToken)
        addView(picture, ui.lp(w = ui.dp(48), h = ui.dp(48)))
        addView(ui.column {
            addView(ui.text(15f, color(R.color.ink), ui.medium) { text = a.name; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(ui.text(12f, color(R.color.ink_soft)) {
                text = a.summary.ifEmpty { a.pkg }; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            }, ui.lp(top = 3))
        }, ui.lp(w = 0, weight = 1f, start = 12))
        val state = when {
            item.canUpdate -> "Update"
            item.installed != null -> "Installed"
            item.suggested == null -> "Not for this phone"
            else -> ""
        }
        if (state.isNotEmpty()) addView(ui.text(12f, color(if (item.canUpdate) R.color.ink else R.color.ink_soft), ui.medium) { text = state },
            ui.lp(w = WRAP_CONTENT, start = 8))
        addView(ui.iconView(R.drawable.ic_chevron_right, color(R.color.ink_faint)), ui.lp(w = ui.dp(20), h = ui.dp(20), start = 4))
        setOnClickListener { openApp(a.pkg) }
    }

    private fun confirmClear() {
        background({ Fdroid.diskUse(host) }) { r ->
            AlertDialog.Builder(host)
                .setTitle("Remove the catalogue?")
                .setMessage("The list of apps and their icons (${fmtSize(r.getOrDefault(0))}) will be removed from this phone. Apps you installed stay. You can download the catalogue again at any time.")
                .setPositiveButton("Remove") { _, _ ->
                    runCatching { Fdroid.clear(host) }.onFailure { message = it.message }.onSuccess { message = null; loaded = false; list.removeAllViews() }
                    render()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ---------- the app page ----------

    private val scroller: ScrollView? get() = view.parent as? ScrollView

    /** Puts the keyboard away: the search box keeps the focus otherwise, also once it is out of sight. */
    private fun hideKeyboard() {
        search.clearFocus()
        host.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(search.windowToken, 0)
    }

    private fun openApp(pkg: String) {
        hideKeyboard()
        background({ Fdroid.details(host, pkg) }) { r ->
            val item = r.getOrNull() ?: return@background
            listScroll = scroller?.scrollY ?: 0
            app = item
            appMessage = null
            buildApp(item)
            listPage.visibility = View.GONE
            appPage.visibility = View.VISIBLE
            scroller?.scrollTo(0, 0)
        }
    }

    private fun closeApp(restoreScroll: Boolean) {
        app = null
        appAction = null
        appPage.removeAllViews()
        appPage.visibility = View.GONE
        listPage.visibility = View.VISIBLE
        if (!loaded) load(true)
        else if (restoreScroll) scroller?.let { s -> s.post { s.scrollTo(0, listScroll) } }
    }

    /** Reads the open app again: what is on the phone may have changed (installed, updated, uninstalled). */
    private fun reloadApp() {
        val pkg = app?.app?.pkg ?: return
        background({ Fdroid.details(host, pkg) }) { r ->
            val item = r.getOrNull()
            if (app?.app?.pkg != pkg) return@background
            if (item == null) { closeApp(restoreScroll = false); return@background }
            app = item
            renderAppAction()
        }
    }

    private fun buildApp(item: Fdroid.Item) {
        val a = item.app
        val v = item.suggested ?: a.versions.firstOrNull()
        val st = Fdroid.status(host)
        appPage.removeAllViews()
        appPage.addView(ui.pill("All apps", color(R.color.glass_strong), color(R.color.ink), R.drawable.ic_back) { onBack() }, ui.lp(w = WRAP_CONTENT))
        appPage.addView(ui.row {
            val picture = iconView(64)
            iconInto(picture, a)
            addView(picture, ui.lp(w = ui.dp(64), h = ui.dp(64)))
            addView(ui.column {
                addView(ui.text(24f, color(R.color.ink), ui.light) { text = a.name; letterSpacing = -0.02f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                if (a.author.isNotEmpty()) addView(ui.text(13f, color(R.color.ink_soft)) { text = a.author; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, ui.lp(top = 3))
            }, ui.lp(w = 0, weight = 1f, start = 14))
        }, ui.lp(top = 18))
        if (a.summary.isNotEmpty()) appPage.addView(ui.text(14f, color(R.color.ink)) { text = a.summary; setLineSpacing(ui.dp(2).toFloat(), 1f) }, ui.lp(top = 14))

        appAction = ui.column()
        appPage.addView(appAction, ui.lp(top = 16))
        renderAppAction()

        val anti = v?.anti.orEmpty()
        if (anti.isNotEmpty()) {
            val names = anti.map { st?.antiFeatures?.get(it)?.first ?: it }
            val (box, label) = ui.insight(R.drawable.ic_warning, color(R.color.yellow)) {
                UiRows(host, ui).message("Good to know", anti.joinToString("\n\n") { id ->
                    val (n, d) = st?.antiFeatures?.get(id) ?: (id to "")
                    if (d.isEmpty()) n else "$n\n$d"
                })
            }
            label.text = "Good to know: " + names.joinToString(", ")
            appPage.addView(box, ui.lp(top = 14))
        }

        if (a.description.isNotEmpty()) {
            appPage.addView(ui.section("About"), ui.lp(top = 26))
            appPage.addView(ui.glassCard(16) { collapsible(a.description, 8) }, ui.lp(top = 12))
        }
        if (!v?.whatsNew.isNullOrBlank()) {
            appPage.addView(ui.section("What's new"), ui.lp(top = 26))
            appPage.addView(ui.glassCard(16) { collapsible(v!!.whatsNew, 8) }, ui.lp(top = 12))
        }

        appPage.addView(ui.section("Details"), ui.lp(top = 26))
        appPage.addView(ui.glassCard(16) {
            var first = true
            fun kv(key: String, value: String) {
                if (value.isEmpty()) return
                val (r, tv) = ui.keyValue(key)
                tv.text = value
                addView(r, ui.lp(top = if (first) 0 else 12))
                first = false
            }
            if (v != null) {
                kv("Version", v.name + if (v.beta) " (beta)" else "")
                kv("Download size", fmtSize(v.size))
                kv("Works on", "Android ${Fdroid.androidName(v.minSdk)} or newer")
            }
            if (a.lastUpdated > 0) kv("Updated", dates.format(Date(a.lastUpdated)))
            kv("Licence", a.license)
            kv("Categories", a.categories.joinToString(", ") { id -> st?.categories?.firstOrNull { it.first == id }?.second ?: id })
            kv("Package", a.pkg)
        }, ui.lp(top = 12))

        if (v != null) {
            appPage.addView(ui.section("Permissions"), ui.lp(top = 26))
            appPage.addView(ui.glassCard(16) {
                if (v.perms.isEmpty()) addView(ui.text(14f, color(R.color.ink)) { text = "This app asks for no permissions." })
                else collapsible(v.perms.map(::permName).distinct().joinToString("\n") { "• $it" }, 6)
            }, ui.lp(top = 12))
        }

        val links = listOf("Website" to a.webSite, "Source code" to a.sourceCode, "Report a problem" to a.issueTracker, "Changes" to a.changelog)
            .filter { it.second.isNotEmpty() }
        if (links.isNotEmpty()) {
            appPage.addView(ui.section("Links"), ui.lp(top = 26))
            appPage.addView(ui.glassCard(6) {
                val rows = UiRows(host, ui)
                for ((title, url) in links) rows.value(this, R.drawable.ic_globe, title, Uri.parse(url).host ?: url) {
                    runCatching { host.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }
            }, ui.lp(top = 12))
        }

        if (a.versions.size > 1) appPage.addView(ui.pill("Other versions", color(R.color.surface), color(R.color.ink), R.drawable.ic_expand) { pickVersion() },
            ui.lp(w = WRAP_CONTENT, top = 20))
    }

    /** "android.permission.ACCESS_FINE_LOCATION" as "Access fine location". */
    private fun permName(p: String) = p.substringAfterLast('.').replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }

    /** Long text, cut after [lines] lines with a "Show all" button. */
    private fun LinearLayout.collapsible(body: String, lines: Int) {
        val tv = ui.text(14f, color(R.color.ink)) {
            text = body
            setLineSpacing(ui.dp(3).toFloat(), 1f)
            setTextIsSelectable(false)
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
        }
        addView(tv)
        val all = ui.pill("Show all", color(R.color.glass_strong), color(R.color.ink)).apply { textSize = 13f; minHeight = ui.dp(34); visibility = View.GONE }
        ui.stylePill(all, "Show all", color(R.color.glass_strong), color(R.color.ink)) {
            tv.maxLines = Int.MAX_VALUE
            tv.ellipsize = null
            all.visibility = View.GONE
        }
        addView(all, ui.lp(w = WRAP_CONTENT, top = 12))
        // Whether it was cut is only known once it has been laid out.
        tv.post { if (tv.layout?.let { it.lineCount > lines || it.getEllipsisCount(it.lineCount - 1) > 0 } == true) all.visibility = View.VISIBLE }
    }

    /** The buttons under the app's name: install, update, open, uninstall, or the running download. */
    private fun renderAppAction() {
        val box = appAction ?: return
        val item = app ?: return
        val a = item.app
        box.removeAllViews()
        val j = Fdroid.job
        val waiting = Fdroid.installing?.first == a.pkg
        fun line(text: String) = box.addView(ui.text(13f, color(R.color.ink_soft)) {
            this.text = text; setLineSpacing(ui.dp(2).toFloat(), 1f)
        }, ui.lp(top = if (box.childCount == 0) 0 else 10))

        if (j != null && j.pkg == a.pkg) {
            box.addView(ui.text(14f, color(R.color.ink), ui.medium) { text = jobLine(j) })
            box.addView(Meter(host).apply { colors(color(R.color.glass_strong), color(R.color.ink)); fraction = jobFraction(j) }, ui.lp(h = ui.dp(8), top = 12))
            if (j.phase == Fdroid.Phase.DOWNLOAD) box.addView(ui.pill("Cancel", color(R.color.glass_strong), color(R.color.ink)) { Fdroid.cancel() }, ui.lp(w = WRAP_CONTENT, top = 14))
            return
        }
        val v = item.suggested
        val inst = item.installed
        val busy = j != null || waiting
        if (v != null && (inst == null || item.canUpdate)) {
            val label = if (inst == null) "Install · ${fmtSize(v.size)}" else "Update to ${v.name} · ${fmtSize(v.size)}"
            val button = ui.action(label, R.drawable.ic_down) { startInstall(item, v) }
            ui.setEnabled(button, !busy)
            box.addView(button)
        }
        if (inst != null) {
            box.addView(ui.row {
                val launch = host.packageManager.getLaunchIntentForPackage(a.pkg)
                val open = ui.pill("Open", color(if (item.canUpdate) R.color.surface else R.color.fab), color(if (item.canUpdate) R.color.ink else R.color.on_fab)) {
                    launch?.let { runCatching { host.startActivity(it) } }
                }.apply { textSize = 15f; minHeight = ui.dp(50) }
                ui.setEnabled(open, launch != null)
                addView(open, ui.lp(w = 0, weight = 1f))
                addView(ui.pill("Uninstall", color(R.color.surface), color(R.color.ink), R.drawable.ic_trash) {
                    @Suppress("DEPRECATION")
                    host.startActivityForResult(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", a.pkg, null)), REQ_UNINSTALL)
                }.apply { textSize = 15f; minHeight = ui.dp(50) }, ui.lp(w = 0, weight = 1f, start = 10))
            }, ui.lp(top = if (box.childCount == 0) 0 else 10))
        }
        when {
            waiting -> line("Waiting for Android's install prompt. Answer it to go on.")
            j != null -> line("Another download is running. Wait for it to finish.")
        }
        when {
            inst == null && v == null -> line(item.why.ifEmpty { "F-Droid has no version for this phone." } + ".")
            inst != null && !item.sameSigner -> line("Version ${inst.versionName} on this phone comes from somewhere else, so F-Droid's can't replace it. " +
                "To switch to F-Droid's, uninstall it first. That also removes the app's data.")
            inst != null && !item.canUpdate -> line("Version ${inst.versionName} is installed and up to date.")
            inst != null && v != null -> line("Version ${inst.versionName} is installed.")
        }
        appMessage?.let { box.addView(ui.text(13f, color(R.color.danger), ui.medium) { text = it; setLineSpacing(ui.dp(2).toFloat(), 1f) }, ui.lp(top = 10)) }
    }

    private fun startInstall(item: Fdroid.Item, v: Fdroid.Version) {
        if (!mayInstall(if (item.installed == null) "Install" else "Update")) return
        appMessage = if (Fdroid.install(host, item.app, v)) null else "Another download is running. Wait for it to finish."
        renderJob()
        renderAppAction()
    }

    private fun pickVersion() {
        val item = app ?: return
        val versions = item.app.versions
        AlertDialog.Builder(host)
            .setTitle("Versions of ${item.app.name}")
            .setItems(versions.map { v ->
                val why = Fdroid.fits(v)
                "${v.name}${if (v.beta) " (beta)" else ""} · ${fmtSize(v.size)}" + when {
                    why.isNotEmpty() -> "\n$why"
                    v.code == item.installed?.code -> "\nOn this phone"
                    else -> ""
                }
            }.toTypedArray()) { _, which ->
                val v = versions[which]
                val why = Fdroid.fits(v)
                val inst = item.installed
                when {
                    why.isNotEmpty() -> { appMessage = "Version ${v.name} can't be installed: ${why.replaceFirstChar { it.lowercase() }}."; renderAppAction() }
                    inst != null && v.code == inst.code -> {}
                    inst != null && v.code < inst.code -> {
                        appMessage = "Android can't go back to an older version over a newer one. Uninstall the app first, then pick the version again."
                        renderAppAction()
                    }
                    Fdroid.job != null || Fdroid.installing != null -> {}
                    else -> startInstall(item, v)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
