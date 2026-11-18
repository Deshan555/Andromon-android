// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors
//
// The list's order and wording (what is pending first, a line that says why an app will or won't
// be frozen, a mode per app) follow SuperFreezZ (https://gitlab.com/SuperFreezZ/SuperFreezZ,
// commit 267d0f7), GPL-3.0-or-later, Copyright (c) 2018 Hocuri:
// userInterface/mainActivity/AppsListAdapter.kt and ListItems.kt. The screen itself is written
// for Andromon's own views.

package dev.andromon.companion

import android.app.AlertDialog
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.TextView

/**
 * The Freezer: stop apps that run in the background without being used. Lists the apps with a
 * freeze mode each, what "Freeze now" would stop first, and the automatic triggers.
 */
internal class FreezerScreen(host: MainActivity) : Screen(host) {

    override val name = "Freezer"
    override val navIcon = R.drawable.ic_snow
    override val navColor = R.color.bg

    private companion object { const val MAX_ROWS = 150 }

    private val rows by lazy { UiRows(host, ui) }
    private var apps: List<Freezer.App> = emptyList()
    private var active: Privileged.Active? = null
    private var loading = false
    private var showSystem = false

    private lateinit var count: TextView
    private lateinit var countSub: TextView
    private lateinit var method: TextView
    private lateinit var freezeBtn: TextView
    private lateinit var methodRow: UiRows.Row
    private lateinit var a11yRow: UiRows.Row
    private lateinit var screenRow: UiRows.Row
    private lateinit var delayRow: UiRows.Row
    private lateinit var levelRow: UiRows.Row
    private lateinit var autoNote: TextView
    private lateinit var daysRow: UiRows.Row
    private lateinit var fdroidRow: UiRows.Row
    private lateinit var usageRow: UiRows.Row
    private lateinit var search: EditText
    private lateinit var chipApps: TextView
    private lateinit var chipSystem: TextView
    private lateinit var listNote: TextView
    private lateinit var list: LinearLayout

    override fun build(): View = ui.column {
        addView(ui.topBar("Freezer", host::goHome, R.drawable.ic_info, "What freezing does") { explain() }.first)

        addView(ui.heroCard(18) {
            addView(ui.row {
                addView(ui.chipIcon(R.drawable.ic_snow))
                addView(ui.text(15f, color(R.color.ink), ui.medium) { text = "Apps to freeze" }, ui.lp(w = WRAP_CONTENT, start = 10))
            })
            count = ui.text(50f, color(R.color.ink), ui.regular) { text = "–"; letterSpacing = -0.03f }
            addView(count, ui.lp(top = 12))
            countSub = ui.text(14f, color(R.color.ink), ui.medium) { setLineSpacing(ui.dp(2).toFloat(), 1f) }
            addView(countSub, ui.lp(top = 4))
            freezeBtn = ui.action("Freeze now", R.drawable.ic_snow) { freezeNow(null) }
            addView(freezeBtn, ui.lp(top = 16))
            method = ui.text(12f, color(R.color.ink)) { gravity = Gravity.CENTER }
            addView(method, ui.lp(top = 10))
        }, ui.lp(top = 24))

        val (note, noteText) = ui.insight(R.drawable.ic_info, color(R.color.yellow)) { explain() }
        noteText.text = "Frozen apps are stopped until you open them again. They show no notifications while frozen. " +
            "Nothing is uninstalled or disabled."
        addView(note, ui.lp(top = 12))

        addView(ui.section("Method"), ui.lp(top = 26))
        addView(ui.glassCard(6) {
            methodRow = rows.value(this, R.drawable.ic_key, "How apps are stopped", "") { host.show("Settings") }
            a11yRow = rows.switch(this, R.drawable.ic_accessibility, "Accessibility method",
                "For phones without Shizuku, the helper or root: presses “Force stop” for you in Android's App info") { on -> toggleAccessibility(on) }
        }, ui.lp(top = 12))

        addView(ui.section("Automatic"), ui.lp(top = 26))
        addView(ui.glassCard(6) {
            screenRow = rows.switch(this, R.drawable.ic_power, "When the screen turns off", "") { on ->
                Freezer.setOnScreenOff(host, on); optionsChanged()
            }
            delayRow = rows.value(this, R.drawable.ic_timer, "Wait before freezing", "Turning the screen back on in time cancels it") {
                rows.pick("Freeze after the screen has been off for", Freezer.DELAY_CHOICES, Freezer.screenOffDelay(host), ::delayLabel) {
                    Freezer.setScreenOffDelay(host, it); optionsChanged()
                }
            }
            levelRow = rows.value(this, R.drawable.ic_battery, "When the battery drops below", "") {
                rows.pick("Freeze when the battery drops below", listOf(0) + Freezer.LEVEL_CHOICES, Freezer.batteryBelow(host),
                    { if (it == 0) "Off" else "$it%" }) { Freezer.setBatteryBelow(host, it); optionsChanged() }
            }
            autoNote = rows.note(this, "")
        }, ui.lp(top = 12))

        addView(ui.section("Rules"), ui.lp(top = 26))
        addView(ui.glassCard(6) {
            daysRow = rows.value(this, R.drawable.ic_timer, "“When unused” means", "Apps not opened for this long are frozen when they run") {
                rows.pick("Freeze apps not used for", Freezer.DAY_CHOICES, Freezer.days(host), ::daysLabel) {
                    Freezer.setDays(host, it); refreshOptions(); load()
                }
            }
            fdroidRow = rows.switch(this, R.drawable.ic_apps, "Also freeze apps from F-Droid",
                "Off: apps installed from F-Droid are left running, as they rarely misbehave in the background") { on ->
                Freezer.setFreezeFdroid(host, on); refreshOptions(); load()
            }
            usageRow = rows.value(this, R.drawable.ic_lock, "Usage access is off",
                "Without it the app can't tell which apps you haven't used, so “When unused” freezes nothing") {
                runCatching { host.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            }
        }, ui.lp(top = 12))

        addView(ui.section("Shortcuts"), ui.lp(top = 26))
        addView(ui.glassCard(6) {
            rows.value(this, R.drawable.ic_home, "Home-screen shortcut", "“Freeze now” as an icon on your home screen") { pinShortcut() }
            rows.value(this, R.drawable.ic_layers, "Quick-settings tile",
                if (Build.VERSION.SDK_INT >= 33) "Adds “Freeze now” to the panel you pull down from the top"
                else "Edit your quick-settings panel and drag “Freeze now” into it") { addTile() }
        }, ui.lp(top = 12))

        addView(ui.section("Apps"), ui.lp(top = 26))
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
                override fun afterTextChanged(s: Editable?) = render()
            })
        }
        addView(search, ui.lp(top = 12))
        addView(ui.row {
            chipApps = ui.pill("Apps", 0, 0).apply { minHeight = ui.dp(36); textSize = 13f }
            chipSystem = ui.pill("System", 0, 0).apply { minHeight = ui.dp(36); textSize = 13f }
            addView(chipApps, ui.lp(w = WRAP_CONTENT))
            addView(chipSystem, ui.lp(w = WRAP_CONTENT, start = 8))
            addView(ui.spacer(), ui.lp(w = 0, weight = 1f))
            addView(ui.circleButton(R.drawable.ic_refresh, "Refresh", 36) { load() }, ui.lp(w = WRAP_CONTENT))
        }, ui.lp(top = 12))
        listNote = ui.text(13f, color(R.color.ink_soft)) { setLineSpacing(ui.dp(2).toFloat(), 1f) }
        addView(listNote, ui.lp(top = 12))
        list = ui.column {}
        addView(list, ui.lp(top = 4))
    }

    override fun onShow() {
        refreshOptions()
        load()
    }

    private fun delayLabel(s: Int) = when {
        s == 0 -> "At once"
        s < 60 -> "$s seconds"
        s == 60 -> "1 minute"
        else -> "${s / 60} minutes"
    }

    private fun daysLabel(d: Int) = if (d == 1) "1 day" else "$d days"

    // ---------- state ----------

    private fun optionsChanged() {
        // The app is on screen now, so Android lets the watch service start.
        PowerWatch.sync(host)
        PowerWatch.refreshNotification(host)
        refreshOptions()
    }

    private fun refreshOptions() {
        val a11y = Freezer.useAccessibility(host)
        val a11yOn = a11y && Freezer.accessibilityOn(host)
        a11yRow.switch?.isChecked = a11y
        a11yRow.sub.text = when {
            a11y && !a11yOn -> "Chosen, but “Andromon: freeze apps” is still off in Android's Accessibility settings. Tap to open them."
            a11yOn -> "On. Used when there is no Shizuku, helper or root. Presses “Force stop” for you in Android's App info."
            else -> "For phones without Shizuku, the helper or root: presses “Force stop” for you in Android's App info"
        }
        val how = active
        methodRow.value?.text = when {
            how != null -> how.backend.label
            a11yOn -> "Accessibility"
            else -> "By hand"
        }
        methodRow.sub.text = when {
            how != null -> "Stopped directly ${how.how()}. No screens open."
            a11yOn -> "App info opens for each app and the accessibility service presses the buttons. Shizuku is faster: tap to set it up."
            else -> "App info opens for each app and you press “Force stop” yourself. Tap to set up Shizuku, or turn on the accessibility method."
        }
        method.text = when {
            how != null -> "Method: ${how.backend.label}"
            a11yOn -> "Method: accessibility service"
            else -> "Method: by hand, one App info screen per app"
        }

        screenRow.switch?.isChecked = Freezer.onScreenOff(host)
        screenRow.sub.text = "Freezes what is pending once you stop using the phone. Needs Shizuku, the helper or root."
        delayRow.value?.text = delayLabel(Freezer.screenOffDelay(host))
        delayRow.view.visibility = if (Freezer.onScreenOff(host)) View.VISIBLE else View.GONE
        val below = Freezer.batteryBelow(host)
        levelRow.value?.text = if (below == 0) "Off" else "$below%"
        levelRow.sub.text = "Once per discharge. Without Shizuku, the helper or root you get a notification to tap instead."
        val watching = PowerWatch.watching(host).isNotEmpty()
        autoNote.text = buildString {
            if (how == null && Freezer.onScreenOff(host))
                append("Not working right now: freezing with the screen off needs Shizuku, the Andromon helper or root. ")
            append("Android only tells running apps that the screen went off, so while one of these is on a small " +
                "“Battery watch” notification stays. ")
            if (watching && !PowerWatchService.running) append("The watch isn't running right now; it starts when you change an option here. ")
            append("If Android stops the watch, it starts again when you open this app or restart the phone.")
            PowerWatch.lastRun(host)?.let { append("\n\nLast automatic freeze: $it.") }
        }

        daysRow.value?.text = daysLabel(Freezer.days(host))
        fdroidRow.switch?.isChecked = Freezer.freezeFdroid(host)
        usageRow.view.visibility = if (Cleaner.hasUsageAccess(host)) View.GONE else View.VISIBLE
    }

    private fun load() {
        if (loading) return
        loading = true
        if (apps.isEmpty()) listNote.text = "Loading apps…"
        background({ Privileged.active(host) to Freezer.load(host) }) { r ->
            loading = false
            val (how, loaded) = r.getOrNull() ?: run {
                listNote.text = "Couldn't load the apps: ${r.exceptionOrNull()?.message}"
                return@background
            }
            active = how
            apps = loaded
            refreshOptions()
            render()
        }
    }

    private fun render() {
        val pending = apps.count { it.pending }
        count.text = "$pending"
        countSub.text = when {
            apps.isEmpty() -> ""
            pending == 0 -> "Nothing to freeze right now"
            pending == 1 -> "1 app is running that you set to freeze or haven't used"
            else -> "$pending apps are running that you set to freeze or haven't used"
        }
        ui.setEnabled(freezeBtn, pending > 0)

        fun chip(tv: TextView, label: String, on: Boolean, system: Boolean) =
            if (on) ui.stylePill(tv, label, color(R.color.fab), color(R.color.on_fab)) { showSystem = system; render() }
            else ui.stylePill(tv, label, color(R.color.surface), color(R.color.ink)) { showSystem = system; render() }
        chip(chipApps, "Apps (${apps.count { !it.system }})", !showSystem, false)
        chip(chipSystem, "System (${apps.count { it.system }})", showSystem, true)

        val q = search.text.toString().trim().lowercase()
        val shown = apps.filter { it.system == showSystem && (q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.contains(q)) }
        list.removeAllViews()
        val days = Freezer.days(host)
        var header: String? = null
        for (app in shown.take(MAX_ROWS)) {
            // Pending first, under its own heading, then everything else (SuperFreezZ's two sections).
            val h = if (app.pending) "Will be frozen" else "Other apps"
            if (h != header) {
                header = h
                list.addView(ui.text(13f, color(R.color.ink_soft), ui.medium) { text = h }, ui.lp(top = 14))
            }
            list.addView(appRow(app, days), ui.lp(top = 8))
        }
        listNote.text = when {
            shown.isEmpty() && apps.isNotEmpty() -> if (q.isNotEmpty()) "No app matches." else "No apps here."
            shown.size > MAX_ROWS -> "Showing the first $MAX_ROWS. Search to find others."
            showSystem -> "System apps are protected: they start as “Never”. Android restarts many of them at once anyway."
            else -> "Tap an app to choose when it is frozen, or to freeze it now."
        }
    }

    private fun appRow(app: Freezer.App, days: Int) = ui.row {
        background = ui.pressable(ui.rounded(color(R.color.surface), 18))
        setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
        addView(ImageView(context).apply {
            setImageDrawable(app.icon?.constantState?.newDrawable(resources) ?: app.icon)
        }, ui.lp(w = ui.dp(42), h = ui.dp(42)))
        addView(ui.column {
            addView(ui.text(15f, color(R.color.ink), ui.medium) { text = app.label; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(ui.text(12f, color(R.color.ink_soft)) { text = app.why(days); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, ui.lp(top = 3))
        }, ui.lp(w = 0, weight = 1f, start = 14))
        addView(ui.pill(app.mode.label, color(if (app.pending) R.color.fab else R.color.glass_strong),
            color(if (app.pending) R.color.on_fab else R.color.ink)).apply {
            minHeight = ui.dp(32); textSize = 12f; setPadding(ui.dp(12), 0, ui.dp(12), 0)
        }, ui.lp(w = WRAP_CONTENT, start = 8))
        contentDescription = "${app.label}, ${app.mode.label}, ${app.why(days)}"
        setOnClickListener { appMenu(app) }
    }

    // ---------- per app ----------

    private fun appMenu(app: Freezer.App) {
        val chosen = Freezer.chosenMode(host, app.pkg)
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (Freezer.isRunning(runCatching { host.packageManager.getApplicationInfo(app.pkg, 0) }.getOrNull() ?: return))
            items += "Freeze now" to { confirmGuarded(app, "Freeze ${app.label} now?") { freezeNow(app.pkg) } }
        for (m in listOf(Freezer.Mode.ALWAYS, Freezer.Mode.WHEN_INACTIVE, Freezer.Mode.NEVER)) {
            val mark = if (chosen == m || (chosen == null && app.mode == m)) "● " else "○ "
            items += (mark + m.label + ": " + m.hint.replaceFirstChar { it.lowercase() }) to {
                if (m == Freezer.Mode.NEVER) setMode(app, m)
                else confirmGuarded(app, "Freeze ${app.label} automatically?") { setMode(app, m) }
            }
        }
        if (chosen != null) items += "Back to the default" to { setMode(app, null) }
        items += (if (app.freezeBusy) "Skip it while it is playing or in use" else "Freeze it even while it is playing or in use") to {
            Freezer.setFreezeBusy(host, app.pkg, !app.freezeBusy); load()
        }
        items += "App info" to {
            runCatching { host.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))) }
            Unit
        }
        AlertDialog.Builder(host)
            .setTitle(app.label)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Protected and system apps ask first: this is the per-app override. */
    private fun confirmGuarded(app: Freezer.App, title: String, go: () -> Unit) {
        val why = when {
            app.protectedAs != null -> "${app.label} is your ${app.protectedAs}. Freezing it can break things until it starts again " +
                "(no keyboard, no calls or messages, no home screen)."
            app.system -> "${app.label} is part of the system. Freezing it can make features misbehave, and Android often restarts it at once."
            else -> { go(); return }
        }
        AlertDialog.Builder(host).setTitle(title).setMessage(why)
            .setPositiveButton("Freeze anyway") { _, _ -> go() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setMode(app: Freezer.App, mode: Freezer.Mode?) {
        Freezer.setMode(host, app.pkg, mode)
        if (mode == Freezer.Mode.WHEN_INACTIVE && !Cleaner.hasUsageAccess(host))
            rows.message("Usage access is off", "“When unused” needs usage access to know which apps you haven't opened. Until it is on, this app is not frozen.")
        load()
    }

    // ---------- actions ----------

    /** [pkg] null: everything pending. Runs in [FreezeActivity], which also serves the shortcut and the tile. */
    private fun freezeNow(pkg: String?) {
        runCatching { host.startActivity(FreezeActivity.intent(host, pkg)) }
    }

    override fun onActivityResult(requestCode: Int) = load()

    private fun toggleAccessibility(on: Boolean) {
        if (!on && !Freezer.accessibilityOn(host)) {
            // Chosen here but not switched on in Android yet: offer the way there.
            AlertDialog.Builder(host).setTitle("Accessibility method")
                .setMessage("Switch on “Andromon: freeze apps” in Android's Accessibility settings to finish, or stop using this method.")
                .setPositiveButton("Open settings") { _, _ -> runCatching { host.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
                .setNegativeButton("Stop using it") { _, _ -> Freezer.setUseAccessibility(host, false); refreshOptions() }
                .show()
            return
        }
        if (!on) {
            Freezer.setUseAccessibility(host, false)
            refreshOptions()
            if (Freezer.accessibilityOn(host)) rows.message("Accessibility method is off",
                "The app no longer uses the service. To remove it from Android as well, switch off “Andromon: freeze apps” in the Accessibility settings.",
                "Open settings") { runCatching { host.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
            return
        }
        AlertDialog.Builder(host)
            .setTitle("Use the accessibility method?")
            .setMessage("With this on, “Freeze now” opens Android's App info for each app, and an accessibility service of this app " +
                "presses “Force stop”, “OK” and Back there. It only sees windows of Android's Settings app, and only acts while " +
                "you are freezing.\n\nYou also have to switch on “Andromon: freeze apps” in Android's Accessibility settings. " +
                "On Android 13 and later Android may first ask you to allow restricted settings for this app.\n\n" +
                "It is slower than Shizuku, shows the screens while it works, and can't run with the screen off. " +
                "It may not find the button on every phone; it then stops and says so.")
            .setPositiveButton("Turn on") { _, _ ->
                Freezer.setUseAccessibility(host, true)
                refreshOptions()
                if (!Freezer.accessibilityOn(host)) runCatching { host.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pinShortcut() {
        val sm = host.getSystemService(ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) {
            rows.message("Shortcut", "Your home screen app doesn't let apps add shortcuts. Use the quick-settings tile instead.")
            return
        }
        val info = ShortcutInfo.Builder(host, "freeze_now")
            .setShortLabel("Freeze now")
            .setLongLabel("Freeze apps now")
            .setIcon(Icon.createWithResource(host, R.drawable.ic_freeze_shortcut))
            .setIntent(FreezeActivity.intent(host))
            .build()
        runCatching { sm.requestPinShortcut(info, null) }
            .onFailure { rows.message("Shortcut", "Couldn't add the shortcut: ${it.message}") }
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            val ok = runCatching {
                host.getSystemService(StatusBarManager::class.java).requestAddTileService(
                    ComponentName(host, FreezeTile::class.java), "Freeze now",
                    Icon.createWithResource(host, R.drawable.ic_snow), host.mainExecutor) { }
            }.isSuccess
            if (ok) return
        }
        rows.message("Quick-settings tile", "Pull down the quick-settings panel, tap the pencil (Edit), and drag “Freeze now” into the panel.")
    }

    private fun explain() = rows.message("What freezing does",
        "Freezing an app is the same as “Force stop” in Android's App info.\n\n" +
            "• The app stops completely and stays stopped until you open it, or until another app calls it.\n\n" +
            "• While it is frozen it gets no notifications, doesn't sync and its alarms don't ring. Don't freeze apps you " +
            "want to hear from, such as messengers or your alarm clock.\n\n" +
            "• Nothing is uninstalled, disabled or deleted. Open the app and it works as before.\n\n" +
            "• Starting a frozen app takes more power than waking a sleeping one. Freezing apps you open many times a day " +
            "can cost battery instead of saving it; freeze the ones you rarely use.\n\n" +
            "• Protected by default: system apps, your home screen, keyboard, phone and SMS apps, accessibility services, " +
            "Shizuku and this app; and apps that are playing music, navigating or on screen right now. Tap an app to change that for it.\n\n" +
            "The freeze modes, the “not used for a while” rule and the accessibility method come from SuperFreezZ (GPL-3.0-or-later).")
}
