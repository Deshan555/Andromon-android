// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.TextView

/**
 * Battery saver: Battery Saver as a switch, Deep sleep (Doze), the charging helper, the apps that
 * are awake right now, and per-app limits (App Standby bucket, background restriction). Opened
 * from the Battery screen. Every part says what it needs and is greyed out, with the reason, when
 * that isn't there. Figures the phone doesn't report are shown as "Not reported", never guessed.
 */
internal class SaverScreen(host: MainActivity) : Screen(host) {

    override val name = "Saver"
    override val navIcon = R.drawable.ic_leaf
    override val navColor = R.color.bg

    private companion object {
        const val NEEDS = "Needs Shizuku, the Andromon helper or root"
        const val MAX_LIMIT_ROWS = 60
        const val MAX_AWAKE_ROWS = 25
    }

    private val rows by lazy { UiRows(host, ui) }
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { live(); handler.postDelayed(this, 2000) }
    }

    private var active: Privileged.Active? = null
    private var loading = false
    private var busy = false

    private lateinit var saverBig: TextView
    private lateinit var saverSub: TextView
    private lateinit var saverBtn: TextView
    private lateinit var autoRow: UiRows.Row
    private lateinit var dozeRow: UiRows.Row
    private lateinit var dozeOffRow: UiRows.Row
    private lateinit var dozeNote: TextView
    private lateinit var current: Ui.Stat
    private lateinit var power: Ui.Stat
    private lateinit var chargeNote: TextView
    private lateinit var limitRow: UiRows.Row
    private lateinit var fullRow: UiRows.Row
    private lateinit var heatRow: UiRows.Row
    private lateinit var awakeNote: TextView
    private lateinit var awakeList: LinearLayout
    private lateinit var limitsNote: TextView
    private lateinit var limitsList: LinearLayout
    private lateinit var setupPill: TextView

    override fun build(): View = ui.column {
        addView(ui.topBar("Saver", { host.show("Battery") }, R.drawable.ic_snow, "Freezer") { host.show("Freezer") }.first)

        addView(ui.heroCard(18) {
            addView(ui.row {
                addView(ui.chipIcon(R.drawable.ic_leaf))
                addView(ui.text(15f, color(R.color.ink), ui.medium) { text = "Battery Saver" }, ui.lp(w = WRAP_CONTENT, start = 10))
            })
            saverBig = ui.text(50f, color(R.color.ink), ui.regular) { text = "–"; letterSpacing = -0.03f }
            addView(saverBig, ui.lp(top = 12))
            saverSub = ui.text(14f, color(R.color.ink), ui.medium) { setLineSpacing(ui.dp(2).toFloat(), 1f) }
            addView(saverSub, ui.lp(top = 4))
            saverBtn = ui.action("Turn on", R.drawable.ic_leaf) { toggleSaver() }
            addView(saverBtn, ui.lp(top = 16))
        }, ui.lp(top = 24))

        addView(ui.glassCard(6) {
            autoRow = rows.value(this, R.drawable.ic_battery, "Turn on automatically", "") {
                val now = BatterySaver.autoLevel(host) ?: 0
                rows.pick("Turn Battery Saver on below", (BatterySaver.AUTO_CHOICES + now).distinct().sorted(), now,
                    { if (it == 0) "Never" else "$it%" }) { level ->
                    work({ BatterySaver.setAutoLevel(host, level) }) { e ->
                        if (e != null) rows.message("Battery Saver", "Couldn't change it: ${e.message}")
                    }
                }
            }
        }, ui.lp(top = 12))
        setupPill = ui.pill("Set up privileged access", color(R.color.fab), color(R.color.on_fab), R.drawable.ic_key) { host.show("Settings") }
        addView(setupPill, ui.lp(w = WRAP_CONTENT, top = 12))

        addView(ui.section("Deep sleep"), ui.lp(top = 26))
        addView(ui.glassCard(6) {
            dozeRow = rows.value(this, R.drawable.ic_moon, "Deep sleep now", "") { dozeNow() }
            dozeOffRow = rows.switch(this, R.drawable.ic_power, "When the screen turns off", "") { on ->
                BatterySaver.setDozeOnScreenOff(host, on)
                PowerWatch.sync(host); PowerWatch.refreshNotification(host)
                refresh()
            }
            dozeNote = rows.note(this, "")
        }, ui.lp(top = 12))

        addView(ui.section("Charging"), ui.lp(top = 26))
        addView(ui.row {
            current = ui.stat(color(R.color.glass), R.drawable.ic_bolt, "Current")
            power = ui.stat(color(R.color.glass), R.drawable.ic_plug, "Power")
            addView(current.box, ui.lp(w = 0, weight = 1f))
            addView(power.box, ui.lp(w = 0, weight = 1f, start = 12))
        }, ui.lp(top = 12))
        chargeNote = ui.text(12f, color(R.color.ink_soft)) { setLineSpacing(ui.dp(2).toFloat(), 1f) }
        addView(chargeNote, ui.lp(top = 8))
        addView(ui.glassCard(6) {
            limitRow = rows.value(this, R.drawable.ic_battery, "Charge limit reminder",
                "A notification when charging reaches this level. The app can't stop the charging itself.") {
                rows.pick("Remind me when charging reaches", BatterySaver.LIMIT_CHOICES, BatterySaver.chargeLimit(host),
                    { if (it == 0) "Off" else "$it%" }) { BatterySaver.setChargeLimit(host, it); alertsChanged(it > 0) }
            }
            fullRow = rows.switch(this, R.drawable.ic_check_circle, "Fully charged alert", "A notification at 100%") { on ->
                BatterySaver.setFullAlert(host, on); alertsChanged(on)
            }
            heatRow = rows.switch(this, R.drawable.ic_thermo, "Overheating alert",
                "A notification when the battery reaches ${BatterySaver.HOT_C.toInt()} °C, at most every 30 minutes") { on ->
                BatterySaver.setHeatAlert(host, on); alertsChanged(on)
            }
            rows.note(this, "These need the “Battery watch” notification to be exact. If Android stops the watch, the level is " +
                "still checked about every 15 minutes.")
        }, ui.lp(top = 12))

        addView(ui.section("Awake now") {
            addView(ui.circleButton(R.drawable.ic_refresh, "Refresh", 36) { load() }, ui.lp(w = WRAP_CONTENT))
        }, ui.lp(top = 26))
        awakeNote = ui.text(13f, color(R.color.ink_soft)) { setLineSpacing(ui.dp(2).toFloat(), 1f) }
        addView(awakeNote, ui.lp(top = 12))
        awakeList = ui.column {}
        addView(awakeList, ui.lp(top = 4))

        addView(ui.section("App limits"), ui.lp(top = 26))
        limitsNote = ui.text(13f, color(R.color.ink_soft)) { setLineSpacing(ui.dp(2).toFloat(), 1f) }
        addView(limitsNote, ui.lp(top = 12))
        limitsList = ui.column {}
        addView(limitsList, ui.lp(top = 4))
    }

    override fun onShow() {
        handler.removeCallbacks(tick)
        handler.post(tick)
        refresh()
        load()
    }

    override fun onHide() = handler.removeCallbacks(tick)

    override fun onBack(): Boolean { host.show("Battery"); return true }

    override fun onPermissionsResult() = refresh()

    // ---------- Battery Saver, Deep sleep, charging ----------

    /** Runs [task] off the main thread, then [done] with its error (null = fine) and refreshes. */
    private fun work(task: () -> Unit, done: (Throwable?) -> Unit) {
        if (busy) return
        busy = true
        background({ task(); Thread.sleep(500) }) { r ->
            busy = false
            done(r.exceptionOrNull())
            refresh()
        }
    }

    private fun canSwitchSaver() = BatterySaver.canWriteSecure(host) || active != null

    private fun toggleSaver() {
        val want = !BatterySaver.saverOn(host)
        ui.setEnabled(saverBtn, false)
        work({ BatterySaver.setSaver(host, want) }) { e ->
            when {
                e != null -> rows.message("Battery Saver", "Couldn't switch it: ${e.message}")
                BatterySaver.saverOn(host) != want -> rows.message("Battery Saver",
                    if (want && Phone.battery(host).charging) "Android keeps Battery Saver off while the phone is charging. Unplug it and try again."
                    else "This phone didn't follow the setting. Some makers use their own power saving mode: use Android's own switch.",
                    "Open Android's setting") { runCatching { host.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) } }
            }
        }
    }

    private fun dozeNow() {
        val lift = BatterySaver.dozeForced(host)
        fun go() = work({ BatterySaver.doze(host, !lift) }) { e ->
            if (e != null) rows.message("Deep sleep", if (e is ShellHelperClient.Unavailable) "$NEEDS. ${Privileged.HOW_TO}" else "Android answered: ${e.message}")
        }
        if (lift) { go(); return }
        AlertDialog.Builder(host)
            .setTitle("Deep sleep now?")
            .setMessage("The phone goes into Doze at once instead of after an hour or so of lying still: apps lose network " +
                "access and can't wake the phone, even while the screen is still on.\n\nThe price: notifications, messages and " +
                "syncing arrive late, in the short windows Android opens now and then, or when the phone wakes fully. Calls, " +
                "SMS and alarms still come through.\n\nIt ends when you turn the screen on again (within a few seconds), " +
                "or after ten minutes if the screen never goes off.")
            .setPositiveButton("Deep sleep") { _, _ -> go() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun alertsChanged(turnedOn: Boolean) {
        if (turnedOn && Build.VERSION.SDK_INT >= 33 &&
            host.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            host.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 71)
        PowerWatch.sync(host); PowerWatch.refreshNotification(host)
        refresh()
    }

    private fun refresh() {
        val on = BatterySaver.saverOn(host)
        val can = canSwitchSaver()
        saverBig.text = if (on) "On" else "Off"
        ui.setLabel(saverBtn, if (on) "Turn off" else "Turn on")
        ui.setEnabled(saverBtn, can && !busy)
        saverSub.text = when {
            !can -> "Apps normally can't switch this. It needs the WRITE_SECURE_SETTINGS permission (Andromon grants it, or " +
                "Settings › Privileged access › Finish setup), or Shizuku, the helper or root."
            on -> "Android is limiting background activity, syncing and some visual effects"
            Phone.battery(host).charging -> "Off. Android keeps it off while the phone is charging"
            else -> "Limits background activity to make the charge last longer"
        }
        val level = BatterySaver.autoLevel(host)
        autoRow.value?.text = when (level) { null -> "Not readable"; 0 -> "Never"; else -> "Below $level%" }
        autoRow.available(can, ui, "Needs WRITE_SECURE_SETTINGS, or Shizuku, the helper or root",
            "Android's own setting. Phones with their own power saving mode may ignore it.")
        setupPill.visibility = if (active == null) View.VISIBLE else View.GONE

        val privileged = active != null
        val forced = BatterySaver.dozeForced(host)
        dozeRow.value?.text = if (forced) "Lift" else "Start"
        dozeRow.available(privileged, ui, NEEDS,
            if (forced) "Doze is forced right now. Tap to lift it; it also ends when the screen turns on."
            else "Puts the phone into Doze at once. Ends when the screen turns on.")
        dozeOffRow.switch?.isChecked = BatterySaver.dozeOnScreenOff(host)
        dozeOffRow.available(privileged || BatterySaver.dozeOnScreenOff(host), ui, NEEDS,
            "Doze starts each time the screen goes off (not while charging) and ends when it comes on")
        dozeNote.text = (if (!privileged && BatterySaver.dozeOnScreenOff(host)) "Not working right now: $NEEDS. " else "") +
            "The trade-off: in Doze, notifications and syncing are delayed until Android's next maintenance window or until " +
            "you wake the phone. Calls, SMS and alarms still come through. The screen-off option keeps the “Battery watch” notification."

        val limit = BatterySaver.chargeLimit(host)
        limitRow.value?.text = if (limit == 0) "Off" else "$limit%"
        fullRow.switch?.isChecked = BatterySaver.fullAlert(host)
        heatRow.switch?.isChecked = BatterySaver.heatAlert(host)
        live()
    }

    /** Current and estimated power, every two seconds while the screen is open. */
    private fun live() {
        val b = Phone.battery(host)
        val c = BatterySaver.charge(host, b)
        val ma = c.milliAmps
        current.caption.text = if (b.charging) "Charging current" else "Battery current"
        current.value.text = if (ma == null) "Not reported" else ui.withUnit("%+.0f".format(ma), "mA")
        current.value.textSize = if (ma == null) 16f else 24f
        power.caption.text = if (b.charging) "Charging power" else "Power draw"
        power.value.text = c.watts?.let { ui.withUnit("%.1f".format(it), "W") } ?: "Not reported"
        power.value.textSize = if (c.watts == null) 16f else 24f
        chargeNote.text = when {
            ma == null -> "This phone doesn't report the battery current to apps, so charging speed can't be shown."
            b.charging -> "Estimate: current into the battery × battery voltage" +
                (b.voltageMv?.let { " (%.2f V)".format(it / 1000f) } ?: "") + ". The charger delivers a little more; the rest runs the phone."
            else -> "Estimate: current out of the battery × battery voltage. Plug in a charger to see the charging speed."
        }
        val on = BatterySaver.saverOn(host)
        if ((saverBig.text == "On") != on && !busy) refresh()
    }

    // ---------- lists ----------

    private class Awake(val pkg: String, val label: String, val icon: Drawable?, val what: String, val locked: Boolean, val keep: Boolean)
    private class Limit(val pkg: String, val label: String, val icon: Drawable?, val bucket: Int?, val restricted: Boolean?, val mah: Float?)
    private class Loaded(val active: Privileged.Active?, val awake: List<Awake>, val awakeError: String?,
                         val limits: List<Limit>, val haveUse: Boolean, val haveBuckets: Boolean)

    private fun load() {
        if (loading) return
        loading = true
        background({
            val how = Privileged.active(host)
            val pm = host.packageManager
            var awake = emptyList<Awake>()
            var awakeError: String? = null
            if (how != null) try {
                val running = RamManager.load(host)
                val locks = runCatching { BatterySaver.wakeLocks(host) }.getOrDefault(emptyList()).filter { it.pkg != null }.groupBy { it.pkg!! }
                awake = running.filter { !it.system || it.pkg in locks }
                    .filter { it.pkg in locks || (it.state != RamManager.State.ACTIVE && it.state != RamManager.State.CACHED) }
                    .map { app ->
                        val held = locks[app.pkg]
                        val what = if (held != null) "Keeps the phone awake: " + held.take(2).joinToString(", ") { it.tag.take(40) }
                        else "${app.state.label} in the background · ${fmtSize(app.bytes)}"
                        Awake(app.pkg, app.label, app.icon, what, held != null, app.keepRunning)
                    }.sortedByDescending { it.locked }
            } catch (e: Exception) { awakeError = e.message ?: e.javaClass.simpleName }

            val use = if (how != null) runCatching { BatteryUse.load(host) }.getOrNull() else null
            val mah = use?.apps?.mapNotNull { a -> a.pkg?.let { it to a.mah } }?.toMap() ?: emptyMap()
            val buckets = if (how != null && Build.VERSION.SDK_INT >= 28) runCatching { BatterySaver.buckets(host) }.getOrNull() else null
            val limits = pm.getInstalledApplications(0)
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != host.packageName && it.enabled }
                .map { ai ->
                    Triple(ai, mah[ai.packageName], runCatching { ai.loadLabel(pm).toString() }.getOrDefault(ai.packageName))
                }
                .sortedWith(compareByDescending<Triple<ApplicationInfo, Float?, String>> { it.second ?: -1f }.thenBy { it.third.lowercase() })
                .take(MAX_LIMIT_ROWS)
                .map { (ai, m, label) ->
                    Limit(ai.packageName, label, runCatching { ai.loadIcon(pm) }.getOrNull(), buckets?.get(ai.packageName),
                        BatterySaver.restricted(host, ai.packageName, ai.uid), m)
                }
            Loaded(how, awake, awakeError, limits, use != null && mah.isNotEmpty(), buckets != null)
        }) { r ->
            loading = false
            val d = r.getOrNull()
            if (d == null) { awakeNote.text = "Couldn't load: ${r.exceptionOrNull()?.message}"; return@background }
            active = d.active
            refresh()
            showAwake(d)
            showLimits(d)
        }
    }

    private fun appRow(icon: Drawable?, label: String, sub: String, trailing: LinearLayout.() -> Unit, onClick: () -> Unit) = ui.row {
        background = ui.pressable(ui.rounded(color(R.color.surface), 18))
        setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
        addView(ImageView(context).apply { setImageDrawable(icon?.constantState?.newDrawable(resources) ?: icon) }, ui.lp(w = ui.dp(42), h = ui.dp(42)))
        addView(ui.column {
            addView(ui.text(15f, color(R.color.ink), ui.medium) { text = label; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(ui.text(12f, color(R.color.ink_soft)) { text = sub; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, ui.lp(top = 3))
        }, ui.lp(w = 0, weight = 1f, start = 14))
        trailing()
        setOnClickListener { onClick() }
    }

    private fun showAwake(d: Loaded) {
        awakeList.removeAllViews()
        awakeNote.text = when {
            d.active == null -> "Which apps hold wakelocks or run in the background can't be seen by an app. $NEEDS. ${Privileged.HOW_TO}"
            d.awakeError != null -> "Couldn't read it: ${d.awakeError}"
            d.awake.isEmpty() -> "No downloaded app is holding a wakelock or running in the background right now."
            else -> "Apps that keep the phone awake (wakelocks) come first, then apps running in the background. " +
                "Stop is a force stop: the app stays stopped until you open it."
        }
        for (a in d.awake.take(MAX_AWAKE_ROWS)) awakeList.addView(appRow(a.icon, a.label, a.what, {
            if (a.keep) addView(ui.text(12f, color(R.color.ink_soft)) { text = "Needed" }, ui.lp(w = WRAP_CONTENT, start = 8))
            else addView(ui.pill("Stop", color(R.color.glass_strong), color(R.color.danger)) { stop(a) }.apply {
                minHeight = ui.dp(36); textSize = 13f; contentDescription = "Stop ${a.label}"
            }, ui.lp(w = WRAP_CONTENT, start = 8))
        }) { awakeMenu(a) }, ui.lp(top = 8))
    }

    private fun stop(a: Awake) {
        awakeNote.text = "Stopping ${a.label}…"
        work({ Privileged.freeze(host, a.pkg) }) { e ->
            if (e != null) rows.message("Stop", "Couldn't stop ${a.label}: ${e.message}")
            load()
        }
    }

    private fun awakeMenu(a: Awake) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (!a.keep) {
            items += "Stop now" to { stop(a) }
            items += "Always freeze it (Freezer)" to {
                Freezer.setMode(host, a.pkg, Freezer.Mode.ALWAYS)
                rows.message("Freezer", "${a.label} is now set to “Always” in the Freezer: it is stopped each time you freeze.",
                    "Open Freezer") { host.show("Freezer") }
            }
        }
        items += "App info" to { appInfo(a.pkg) }
        AlertDialog.Builder(host).setTitle(a.label)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton("Close", null).show()
    }

    private fun appInfo(pkg: String) {
        runCatching { host.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))) }
    }

    private fun showLimits(d: Loaded) {
        limitsList.removeAllViews()
        limitsNote.text = buildString {
            append(if (d.haveUse) "Biggest battery users since the last full charge first (Android's own estimate). "
                else if (d.active != null) "Android reported no per-app battery figures, so apps are listed by name. "
                else "Listed by name: per-app battery figures and standby buckets need privileged access. ")
            append("The bucket is how much Android lets an app do in the background: Active, Working set, Frequent, Rare" +
                (if (Build.VERSION.SDK_INT >= 31) ", Restricted" else "") + ". Android moves apps between buckets by itself as you use them. ")
            if (d.active == null) append("Changing them: $NEEDS.")
            else if (Build.VERSION.SDK_INT < 28) append("Buckets need Android 9 or later.")
        }
        for (l in d.limits) {
            val parts = ArrayList<String>()
            parts += l.bucket?.let { BatterySaver.Bucket.of(it)?.label ?: "Bucket $it" } ?: if (d.haveBuckets) "No bucket" else "Bucket not readable"
            when (l.restricted) { true -> parts += "background restricted"; null -> parts += "restriction not readable"; else -> {} }
            l.mah?.let { parts += (if (it >= 10f) "%.0f mAh" else "%.1f mAh").format(it) }
            limitsList.addView(appRow(l.icon, l.label, parts.joinToString(" · "), {
                addView(ui.iconView(R.drawable.ic_chevron_right, color(R.color.ink_faint)), ui.lp(w = ui.dp(20), h = ui.dp(20), start = 4))
            }) { limitMenu(l) }, ui.lp(top = 8))
        }
    }

    private fun limitMenu(l: Limit) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        fun change(what: String, task: () -> Unit) {
            if (active == null) { rows.message(what, "$NEEDS. ${Privileged.HOW_TO}", "Open Settings") { host.show("Settings") }; return }
            work(task) { e ->
                if (e != null) rows.message(what, "Android answered: ${e.message}")
                load()
            }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val choices = buildList {
                add(BatterySaver.Bucket.ACTIVE); add(BatterySaver.Bucket.RARE)
                if (Build.VERSION.SDK_INT >= 31) add(BatterySaver.Bucket.RESTRICTED)
            }
            for (b in choices) items += ((if (l.bucket == b.code) "● " else "○ ") + "Bucket: ${b.label}") to {
                change("Standby bucket") {
                    val out = BatterySaver.setBucket(host, l.pkg, b)
                    if (out.contains("Error", ignoreCase = true) || out.contains("Exception")) throw java.io.IOException(out.take(200))
                }
            }
        }
        if (l.restricted != null) items += (if (l.restricted) "Allow background activity again" else "Restrict background activity") to {
            change("Background restriction") { BatterySaver.setRestricted(host, l.pkg, !l.restricted) }
        }
        items += "App info" to { appInfo(l.pkg) }
        AlertDialog.Builder(host).setTitle(l.label)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton("Close", null).show()
    }

}
