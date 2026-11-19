// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.Manifest
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.BaseColumns
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.TextView
import org.json.JSONObject
import java.text.NumberFormat

/**
 * Where the app opens: the link to Andromon on the computer. Whether a computer is linked (over
 * USB or Wi‑Fi, which one, for how long) with one Start/Stop button; quick actions (send files or
 * the clipboard, receive files, scan the computer's code, connect over Wi‑Fi); what the computer
 * may read; recent activity and today's figures; and how to connect.
 */
internal class PcLinkScreen(host: MainActivity) : Screen(host) {

    private companion object {
        const val ADB_WIFI = "adb_wifi_enabled" // Settings.Global, hidden constant
        /** The PC link lock's PIN pad: before Start ([REQ_START]) and to open a locked session ([REQ_UNLOCK]). */
        const val REQ_START = 7301
        const val REQ_UNLOCK = 7302
    }

    override val name = "PC link"
    override val navIcon = R.drawable.ic_desktop
    override val navColor = R.color.bg

    /** The runtime permissions behind what the computer reads (names and asking: [Perms]). */
    private val wanted = buildList {
        add(Manifest.permission.READ_SMS); add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.READ_CONTACTS); add(Manifest.permission.WRITE_CONTACTS)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.READ_PHONE_STATE) // call alerts on the computer
        addAll(Perms.MEDIA.permissions) // Photos and Music tabs
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val permissionNames = mapOf(
        Manifest.permission.READ_PHONE_STATE to "See incoming calls (call alerts)",
        "android.permission.POST_NOTIFICATIONS" to "Show the connection notification",
    )

    /** One data-count figure in the data access card. */
    private class Count(val value: TextView, val uri: Uri, val permission: String)

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private lateinit var heroDetail: TextView
    private lateinit var startStop: TextView
    /** Stop, under Unlock while the link is locked. */
    private lateinit var lockedStop: TextView
    private lateinit var actionNote: TextView
    private lateinit var permSub: TextView
    private lateinit var permPill: TextView
    private lateinit var permList: LinearLayout
    private lateinit var activityList: LinearLayout
    private lateinit var allActivity: TextView
    private lateinit var todayValues: List<TextView>
    private lateinit var wifiCard: View
    private lateinit var wifiSub: TextView
    private lateinit var wifiSteps: LinearLayout
    private lateinit var wifiPill: TextView
    private val counts = mutableListOf<Count>()
    private val numbers = NumberFormat.getIntegerInstance()

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refreshLink(); refreshActivity(); handler.postDelayed(this, 20_000) }
    }
    private val onStats: () -> Unit = { refreshLink(); refreshActivity() }

    override fun build(): View = ui.column {
        addView(ui.topBar("PC link", host::goHome).first)
        addView(ui.subtitle("How Andromon on your computer reaches this phone"), ui.lp(top = 6))
        addView(heroCard(), ui.lp(top = 20))
        addView(actionsCard(), ui.lp(top = 12))
        addView(ui.section("Today"), ui.lp(top = 26))
        addView(todayCard(), ui.lp(top = 12))
        addView(ui.section("Recent activity") {
            allActivity = ui.smallPill("See all") { showAllActivity() }
            addView(allActivity, ui.lp(w = WRAP_CONTENT))
        }, ui.lp(top = 26))
        addView(activityCard(), ui.lp(top = 12))
        addView(ui.section("What the computer can read"), ui.lp(top = 26))
        addView(permissionsCard(), ui.lp(top = 12))
        addView(ui.section("Connect"), ui.lp(top = 26))
        addView(usbCard(), ui.lp(top = 12))
        wifiCard = wirelessCard()
        addView(wifiCard, ui.lp(top = 12))
        addView(ui.text(12f, color(R.color.ink_soft)) {
            text = "Your messages, contacts and call log only go to Andromon, on computers you connect or pair."
            gravity = Gravity.CENTER
            setLineSpacing(ui.dp(2).toFloat(), 1f)
        }, ui.lp(top = 20))
    }

    override fun onShow() {
        CompanionService.stateListener = ::refresh
        LinkStats.listen(onStats)
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 20_000)
        refresh()
    }

    override fun onHide() {
        CompanionService.stateListener = null
        LinkStats.unlisten(onStats)
        handler.removeCallbacks(tick)
    }

    override fun onPermissionsResult() = refresh()

    // ---------- sections ----------

    /** The hero: linked or not, how and for how long, the computer's name, and Start/Stop. */
    private fun heroCard() = ui.heroCard(18) {
        addView(ui.row {
            addView(ui.chipIcon(R.drawable.ic_desktop))
            addView(ui.text(15f, color(R.color.ink), ui.medium) { text = "Andromon link" }, ui.lp(w = 0, weight = 1f, start = 10))
            val (row, dot, label) = ui.dotLabel()
            statusDot = dot; statusText = label.apply { setTextColor(color(R.color.ink)); typeface = ui.medium }
            row.background = ui.rounded(color(R.color.glass), 50)
            row.setPadding(ui.dp(12), ui.dp(7), ui.dp(14), ui.dp(7))
            addView(row, ui.lp(w = WRAP_CONTENT, start = 8))
        })
        heroTitle = ui.text(34f, color(R.color.ink), ui.regular) {
            letterSpacing = -0.03f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        addView(heroTitle, ui.lp(top = 16))
        heroSub = ui.text(14f, color(R.color.ink), ui.medium) { setLineSpacing(ui.dp(2).toFloat(), 1f) }
        addView(heroSub, ui.lp(top = 8))
        // The computer's name (Pii.addKnown in refreshLink()); "Ready" and "Link is off" stay readable.
        PrivacyBlur.ifFound(heroTitle)
        PrivacyBlur.ifFound(heroSub)
        heroDetail = ui.text(13f, color(R.color.ink)) { setLineSpacing(ui.dp(2).toFloat(), 1f) }
        addView(heroDetail, ui.lp(top = 4))
        startStop = ui.action("Start", R.drawable.ic_power) { togglePcLink() }
        addView(startStop, ui.lp(top = 18))
        lockedStop = ui.pill("Stop the link", color(R.color.glass), color(R.color.ink)) { CompanionService.stopByUser(host) }
        lockedStop.visibility = View.GONE
        addView(lockedStop, ui.lp(top = 10))
    }

    /** Five things people come here to do, as a grid of labelled icons. */
    private fun actionsCard() = ui.glassCard(10) {
        val actions = listOf(
            Triple(R.drawable.ic_up, "Send files") { sendFiles() },
            Triple(R.drawable.ic_clipboard, "Send clipboard") { sendClipboard() },
            Triple(R.drawable.ic_down, "Receive files") { TransferScreen.requestMode(send = false); host.show("Transfer"); Unit },
            Triple(R.drawable.ic_qr, "Scan code") { scanCode() },
            Triple(R.drawable.ic_wifi, "Connect over Wi‑Fi") { host.scrollTo(wifiCard) },
        )
        actions.chunked(3).forEachIndexed { r, chunk ->
            addView(ui.row {
                gravity = Gravity.TOP
                chunk.forEach { (icon, label, go) -> addView(actionCell(icon, label, go), ui.lp(w = 0, weight = 1f)) }
                repeat(3 - chunk.size) { addView(ui.spacer(), ui.lp(w = 0, h = 1, weight = 1f)) }
            }, ui.lp(top = if (r == 0) 0 else 4))
        }
        actionNote = ui.text(13f, color(R.color.ink_soft)) {
            visibility = View.GONE; setLineSpacing(ui.dp(2).toFloat(), 1f)
            setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
        }
        PrivacyBlur.ifFound(actionNote) // "Sent the clipboard to <computer>."
        addView(actionNote)
    }

    private fun actionCell(icon: Int, label: String, go: () -> Unit) = ui.column {
        gravity = Gravity.CENTER_HORIZONTAL
        background = ui.pressable(ui.rounded(android.graphics.Color.TRANSPARENT, 20))
        setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10))
        addView(ui.iconTile(icon, color(R.color.ink), color(R.color.glass_strong), 48, 22), ui.lp(w = ui.dp(48), h = ui.dp(48)))
        addView(ui.text(13f, color(R.color.ink)) { text = label; gravity = Gravity.CENTER; maxLines = 2 }, ui.lp(top = 8))
        contentDescription = label
        setOnClickListener { go() }
    }

    private fun todayCard() = ui.glassCard(16) {
        val (nums, values) = ui.numbers(listOf("Linked", "Requests", "Files"))
        todayValues = values
        addView(nums)
        val (row, _) = ui.listPill(R.drawable.ic_chart, "PC link stats") { StatsScreen.pending = "PC link"; host.show("Stats") }
        addView(row, ui.lp(top = 18))
    }

    private fun activityCard() = ui.glassCard(6) {
        activityList = this
    }

    private fun permissionsCard() = ui.glassCard(16) {
        val (head, sub) = ui.cardHeader(R.drawable.ic_lock, "Data access") {
            permPill = ui.smallPill("")
            addView(permPill, ui.lp(w = WRAP_CONTENT, start = 8))
        }
        permSub = sub
        addView(head)
        val (nums, values) = ui.numbers(listOf("Messages", "Contacts", "Calls"))
        listOf(
            Telephony.Sms.CONTENT_URI to Manifest.permission.READ_SMS,
            ContactsContract.Contacts.CONTENT_URI to Manifest.permission.READ_CONTACTS,
            CallLog.Calls.CONTENT_URI to Manifest.permission.READ_CALL_LOG,
        ).forEachIndexed { i, source -> counts += Count(values[i], source.first, source.second) }
        addView(nums, ui.lp(top = 18))
        permList = ui.column()
        addView(permList, ui.lp(top = 8))
    }

    /** USB: one line, with the four steps tucked behind "How". */
    private fun usbCard() = ui.glassCard(16) {
        val steps = ui.column { visibility = View.GONE }
        val how = ui.smallPill("How")
        val (head, sub) = ui.cardHeader(R.drawable.ic_usb, "USB cable") { addView(how, ui.lp(w = WRAP_CONTENT, start = 8)) }
        sub.text = "Plug in, then click Connect in Andromon"
        addView(head)
        stepList(steps, listOf(
            "Use a USB cable that carries data.",
            "Turn on Developer options › USB debugging.",
            "Open Andromon on the computer and click Connect.",
            "Tap Allow when the phone asks.",
        ))
        addView(steps, ui.lp(top = 4))
        fun show(open: Boolean) {
            steps.visibility = if (open) View.VISIBLE else View.GONE
            ui.stylePill(how, if (open) "Hide" else "How", color(R.color.glass_strong), color(R.color.ink)) { show(!open) }
        }
        show(false)
    }

    private fun stepList(into: LinearLayout, steps: List<String>) {
        into.removeAllViews()
        steps.forEachIndexed { i, step ->
            into.addView(ui.row {
                gravity = Gravity.TOP
                addView(ui.text(13f, color(R.color.on_fab), ui.medium) {
                    text = "${i + 1}"
                    gravity = Gravity.CENTER
                    background = ui.circle(color(R.color.fab))
                }, ui.lp(w = ui.dp(24), h = ui.dp(24)))
                addView(ui.text(14f, color(R.color.ink)) { text = step; setLineSpacing(ui.dp(2).toFloat(), 1f) },
                    ui.lp(w = 0, weight = 1f, start = 12).apply { topMargin = ui.dp(2) })
            }, ui.lp(top = 12))
        }
    }

    /**
     * Andromon can also connect without a cable through Android's Wireless debugging
     * (Android 11+). This card shows how far along the phone is, and what to do next, also when
     * Developer options are still off.
     */
    private fun wirelessCard() = ui.glassCard(16) {
        val (head, sub) = ui.cardHeader(R.drawable.ic_wifi, "Wi‑Fi")
        wifiSub = sub.apply { maxLines = 5; ellipsize = null; setLineSpacing(ui.dp(2).toFloat(), 1f) }
        addView(head)
        wifiSteps = ui.column()
        addView(wifiSteps, ui.lp(top = 2))
        wifiPill = ui.smallPill("")
        addView(wifiPill, ui.lp(w = WRAP_CONTENT, top = 14))
    }

    // ---------- quick actions ----------

    private fun say(text: String?) {
        actionNote.text = text.orEmpty()
        actionNote.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun sendFiles() {
        say(null)
        // The picker hands the files to Transfer › Send (ShareActivity), which then opens.
        TransferScreen.requestMode(send = true)
        Prefs.setTransferSend(host, true)
        runCatching { host.startActivity(Intent(host, ShareActivity::class.java).setAction(ShareActivity.ACTION_PICK_FILES)) }
            .onFailure { host.show("Transfer") }
    }

    /** The app is in front, so it may read the clipboard itself (Android 10+ allows only that). */
    private fun sendClipboard() {
        val text = runCatching {
            val clip = host.getSystemService(ClipboardManager::class.java).primaryClip
            if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(host)?.toString() else null
        }.getOrNull()
        if (text.isNullOrEmpty()) { say("Nothing is copied on this phone."); return }
        if (LinkEvents.listening) {
            val cut = text.length > Links.MAX_CLIP
            LinkEvents.emit(JSONObject().put("type", "clip").put("text", if (cut) text.take(Links.MAX_CLIP) else text).put("truncated", cut))
            LinkStats.clipToComputer(host)
            say("Sent the clipboard to ${LinkStats.computerName(host)}.")
        } else {
            // No computer is listening on the link: send it over Wi‑Fi instead, like a file.
            WifiSend.addText(text)
            TransferScreen.requestMode(send = true)
            say(null)
            host.show("Transfer")
        }
    }

    private fun scanCode() {
        say(null)
        // The code is read by Transfer › Send when the scanner closes (QrScanActivity.scanned).
        TransferScreen.requestMode(send = true)
        Prefs.setTransferSend(host, true)
        host.show("Transfer")
        runCatching { host.startActivity(Intent(host, QrScanActivity::class.java)) }
    }

    // ---------- state ----------

    private fun togglePcLink() {
        when {
            // Locked (Settings › PC link › Lock the PC link): the PIN pad opens the session.
            CompanionService.running && CompanionService.locked ->
                host.startActivityForResult(UnlockActivity.intent(host, UnlockActivity.Mode.UNLOCK), REQ_UNLOCK)
            CompanionService.running -> CompanionService.stopByUser(host)
            // The person is here: ask for the PIN first, then start the link already unlocked.
            LinkLock.enabled(host) -> host.startActivityForResult(UnlockActivity.intent(host, UnlockActivity.Mode.VERIFY,
                "Start the PC link", "Enter your PIN. The link then starts unlocked."), REQ_START)
            else -> CompanionService.start(host, byUser = true)
        }
        // refresh() runs via CompanionService.stateListener once the service has started/stopped.
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int) {
        if (requestCode == REQ_START && resultCode == android.app.Activity.RESULT_OK) {
            LinkLock.allowUnlockedStart()
            CompanionService.start(host, byUser = true)
        }
    }

    private fun refresh() {
        refreshLink()
        refreshActivity()
        refreshWireless()
        refreshPermissions()
        loadCounts()
    }

    private fun refreshLink() {
        val on = CompanionService.running
        val locked = on && CompanionService.locked
        val linked = LinkStats.linkedNow()
        val name = LinkStats.computerName(host)
        val hello = LinkStats.hello(host)
        if (hello != null) Pii.addKnown(hello.computer) // not the "Your computer" stand-in
        statusDot.background = ui.circle(color(when { locked -> R.color.ink; linked -> R.color.good; on -> R.color.green_dot; else -> R.color.ink_faint }))
        statusText.text = when { locked -> "Locked"; linked -> "Linked"; on -> "Ready"; else -> "Off" }
        when {
            locked -> {
                // Settings › PC link › Lock the PC link: nothing is read until the person unlocks.
                heroTitle.text = "Locked"
                if (CompanionService.waiting) {
                    heroSub.text = "A computer is waiting. Unlock to let it read your data."
                    heroDetail.text = if (hello != null && name != "Your computer") "$name wants to connect." else ""
                } else {
                    heroSub.text = "Andromon can't read anything until you unlock the link"
                    heroDetail.text = "When Andromon on your computer connects, a notification asks for your PIN."
                }
            }
            linked -> {
                heroTitle.text = name
                val how = when (LinkStats.transport(host)) { "usb" -> "Over USB"; "wifi" -> "Over Wi‑Fi"; else -> "Through adb" }
                val since = LinkStats.sessionStart.takeIf { it > 0 }?.let { System.currentTimeMillis() - it }
                heroSub.text = how + (since?.let { if (it < 60_000) " · just linked" else " · linked for ${duration(it)}" } ?: "")
                heroDetail.text = hello?.let { listOf(it.app, it.os).filter { s -> s.isNotBlank() }.joinToString(" on ") }.orEmpty()
            }
            on -> {
                heroTitle.text = "Ready"
                heroSub.text = "Waiting for ${if (name == "Your computer") "your computer" else name}"
                heroDetail.text = "Connect with the USB cable or over Wi‑Fi, then open Andromon on the computer." +
                    (hello?.let { "\nLast linked ${ago(LinkStats.lastTouch.takeIf { t -> t > 0 } ?: it.time)}." } ?: "")
            }
            else -> {
                heroTitle.text = "Link is off"
                heroSub.text = "Andromon can't reach this phone"
                heroDetail.text = if (Prefs.linkPaused(host)) "You stopped it, so Andromon won't start it again by itself. Tap Start when you want it back."
                    else "Start the link here. Andromon can also start it itself over USB."
            }
        }
        heroDetail.visibility = if (heroDetail.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        ui.stylePill(startStop, when { locked -> "Unlock"; on -> "Stop"; else -> "Start" }, color(R.color.fab), color(R.color.on_fab),
            if (locked) R.drawable.ic_lock else R.drawable.ic_power) { togglePcLink() }
        startStop.contentDescription = when { locked -> "Unlock the PC link"; on -> "Stop the PC link"; else -> "Start the PC link" }
        lockedStop.visibility = if (locked) View.VISIBLE else View.GONE

        val today = LinkStats.days(host, 1).last()
        todayValues[0].text = if (today.linkedMs > 0) duration(today.linkedMs) else "–"
        todayValues[1].text = numbers.format(today.callTotal)
        todayValues[2].text = numbers.format(today.filesSent + today.filesReceived)
        todayValues.forEach { it.textSize = 20f }
    }

    private fun refreshActivity() {
        val items = LinkStats.recent(host, 5)
        Pii.addKnown(*LinkStats.computers(host).map { it.optString("name") }.toTypedArray()) // "<computer> connected"
        activityList.removeAllViews()
        allActivity.visibility = if (items.size >= 5) View.VISIBLE else View.GONE
        if (items.isEmpty()) {
            UiRows(host, ui).note(activityList, "Nothing yet. What Andromon on your computer reads or sends shows up here.")
            return
        }
        items.forEach { activityList.addView(activityRow(it)) }
    }

    private fun activityRow(a: LinkStats.Activity) = ui.row {
        setPadding(ui.dp(10), ui.dp(10), ui.dp(12), ui.dp(10))
        addView(ui.roundIcon(36, color(R.color.glass_strong), kindIcon(a.kind), color(R.color.ink), 17), ui.lp(w = ui.dp(36), h = ui.dp(36)))
        addView(ui.text(14f, color(R.color.ink)) {
            text = a.text + if (a.count > 1) " (${a.count}×)" else ""
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            // Wi‑Fi transfers name the other device, which may not be known any more: blur the whole line.
            if (a.kind == "send" || a.kind == "receive") PrivacyBlur.always(this) else PrivacyBlur.ifFound(this)
        }, ui.lp(w = 0, weight = 1f, start = 12))
        addView(ui.text(12f, color(R.color.ink_soft)) { text = ago(a.time); maxLines = 1 }, ui.lp(w = WRAP_CONTENT, start = 8))
    }

    private fun kindIcon(kind: String) = when (kind) {
        "sms", "mms" -> R.drawable.ic_sms
        "contacts" -> R.drawable.ic_person
        "calls", "call" -> R.drawable.ic_call
        "calendar" -> R.drawable.ic_timer
        "media" -> R.drawable.ic_image
        "notif" -> R.drawable.ic_bell
        "push", "clip" -> R.drawable.ic_clipboard
        "phone" -> R.drawable.ic_volume
        "player" -> R.drawable.ic_music
        "battery" -> R.drawable.ic_battery
        "stats" -> R.drawable.ic_chart
        "send" -> R.drawable.ic_up
        "receive" -> R.drawable.ic_down
        else -> R.drawable.ic_desktop
    }

    private fun showAllActivity() {
        val list = ui.column { setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4)) }
        LinkStats.recent(host).forEach { list.addView(activityRow(it)) }
        AlertDialog.Builder(host)
            .setTitle("Recent activity")
            .setView(android.widget.ScrollView(host).apply { addView(list) })
            .setPositiveButton("Close", null)
            .show()
    }

    // ---------- Wi‑Fi ----------

    private fun refreshWireless() {
        val cr = host.contentResolver
        val devOn = Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        val wifiOn = Settings.Global.getInt(cr, ADB_WIFI, 0) == 1
        // Granted by Andromon during its one-time USB setup ("Use this phone without the cable").
        val canSwitch = host.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED
        fun open(action: String): () -> Unit = { runCatching { host.startActivity(Intent(action)) } }
        wifiPill.visibility = View.VISIBLE
        wifiSteps.removeAllViews()
        when {
            Build.VERSION.SDK_INT < 30 -> {
                wifiSub.text = "Needs Android 11 or newer. Use the USB cable instead."
                wifiPill.visibility = View.GONE
            }
            !devOn -> {
                // Wireless debugging lives in Developer options: say how to get there instead of hiding everything.
                wifiSub.text = "Developer options are off. Wireless debugging is part of them, so turn them on first:"
                stepList(wifiSteps, listOf(
                    "Open Settings › About phone (on some phones: › Software information).",
                    "Tap Build number seven times, until the phone says you are a developer.",
                    "Connect once with the USB cable and click “Use this phone without the cable” in Andromon.",
                ))
                ui.stylePill(wifiPill, "Open About phone", color(R.color.fab), color(R.color.on_fab), null, open(Settings.ACTION_DEVICE_INFO_SETTINGS))
            }
            canSwitch -> {
                wifiSub.text = if (wifiOn) "On · pick this phone in Andromon (same Wi‑Fi)"
                else "Use Andromon without the cable, on the same Wi‑Fi"
                if (wifiOn) ui.stylePill(wifiPill, "Turn off Wi‑Fi connection", color(R.color.glass_strong), color(R.color.ink), null) { setAdbWifi(false) }
                else ui.stylePill(wifiPill, "Turn on Wi‑Fi connection", color(R.color.fab), color(R.color.on_fab), R.drawable.ic_wifi) { setAdbWifi(true) }
            }
            else -> {
                wifiSub.text = "Set up once over USB: in Andromon, click “Use this phone without the cable”. Or pair by hand:"
                stepList(wifiSteps, listOf(
                    "Open Developer options › Wireless debugging and turn it on.",
                    "Tap Pair device with QR code.",
                    "Scan the code Andromon shows under Connect › Wi‑Fi.",
                ))
                ui.stylePill(wifiPill, "Open Developer options", color(R.color.glass_strong), color(R.color.ink), null, open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            }
        }
    }

    private fun setAdbWifi(on: Boolean) {
        try {
            Settings.Global.putInt(host.contentResolver, ADB_WIFI, if (on) 1 else 0)
        } catch (_: SecurityException) {
            // The permission was revoked (e.g. after a reinstall): fall back to the Settings screen.
            runCatching { host.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        }
        // Android asks to trust the network the first time; re-read the result shortly after.
        wifiSub.postDelayed({ if (!host.isDestroyed) refreshWireless() }, 1500)
        wifiSub.postDelayed({
            if (host.isDestroyed) return@postDelayed
            refreshWireless()
            // Android ignores the switch when the phone isn't on a Wi‑Fi network: say so instead
            // of leaving the button looking as if the tap did nothing.
            if (on && Settings.Global.getInt(host.contentResolver, ADB_WIFI, 0) != 1) {
                wifiSub.text = if (WifiServer.wifiLink(host) == null) "Android didn't turn it on: connect this phone to a Wi‑Fi network first"
                    else "Android didn't turn it on. Try again, or use Developer options › Wireless debugging"
            }
        }, 6000)
    }

    // ---------- data access ----------

    /** Asked before and refused with "don't ask again" (or blocked by Android): only App info can allow it now. */
    private fun blocked(p: String) = Perms.blocked(host, p)

    private fun ask(ps: List<String>) {
        ps.forEach { Prefs.setAskedPermission(host, it) }
        host.requestPermissions(ps.toTypedArray(), 1)
    }

    /** Media access is also covered by All files access (see Media.canRead). */
    private fun missingPermissions(): List<String> {
        val media = Perms.granted(host, Perms.MEDIA)
        return wanted.filter { host.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED && !(media && it in Perms.MEDIA.permissions) }
    }

    private fun openAppInfo() {
        runCatching { host.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", host.packageName, null))) }
    }

    private fun refreshPermissions() {
        val missing = missingPermissions()
        val askable = missing.filterNot(::blocked)
        // Phone notifications on the computer: Android's Notification access, a Settings page of its own.
        val notifAccess = Perms.granted(host, Perms.NOTIFICATION_ACCESS)
        val total = wanted.size + 1
        permSub.text = "${total - missing.size - (if (notifAccess) 0 else 1)} of $total allowed"
        // Everything allowed: "N of N allowed" says it, so no badge; the pill is only there to act.
        permPill.visibility = if (missing.isEmpty() && notifAccess) View.GONE else View.VISIBLE
        when {
            missing.isEmpty() && notifAccess -> Unit
            askable.isNotEmpty() -> ui.stylePill(permPill, "Allow", color(R.color.fab), color(R.color.on_fab)) { ask(askable) }
            missing.isEmpty() -> ui.stylePill(permPill, "Allow", color(R.color.fab), color(R.color.on_fab)) {
                Perms.ensure(host, Perms.NOTIFICATION_ACCESS)
            }
            else -> ui.stylePill(permPill, "App info", color(R.color.fab), color(R.color.on_fab)) { openAppInfo() }
        }
        permList.removeAllViews()
        if (missing.isEmpty() && notifAccess) return
        for (p in wanted) {
            val ok = p !in missing
            val stuck = !ok && blocked(p)
            permList.addView(ui.row {
                background = if (ok) null else ui.pressable(ui.rounded(android.graphics.Color.TRANSPARENT, 16))
                setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8))
                addView(ui.iconView(if (ok) R.drawable.ic_check else R.drawable.ic_dot_empty,
                    color(if (ok) R.color.good else R.color.ink_faint)), ui.lp(w = ui.dp(18), h = ui.dp(18)))
                addView(ui.text(14f, color(R.color.ink)) {
                    text = permissionNames[p] ?: Perms.label(p)
                }, ui.lp(w = 0, weight = 1f, start = 12))
                if (!ok) {
                    addView(ui.text(13f, color(R.color.ink), ui.medium) { text = if (stuck) "App info" else "Allow" }, ui.lp(w = WRAP_CONTENT, start = 8))
                    addView(ui.iconView(R.drawable.ic_chevron_right, color(R.color.ink_faint)), ui.lp(w = ui.dp(18), h = ui.dp(18), start = 2))
                    setOnClickListener { if (stuck) openAppInfo() else ask(listOf(p)) }
                }
            })
        }
        permList.addView(ui.row {
            background = if (notifAccess) null else ui.pressable(ui.rounded(android.graphics.Color.TRANSPARENT, 16))
            setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8))
            addView(ui.iconView(if (notifAccess) R.drawable.ic_check else R.drawable.ic_dot_empty,
                color(if (notifAccess) R.color.good else R.color.ink_faint)), ui.lp(w = ui.dp(18), h = ui.dp(18)))
            addView(ui.text(14f, color(R.color.ink)) { text = "Phone notifications (Notification access)" }, ui.lp(w = 0, weight = 1f, start = 12))
            if (!notifAccess) {
                addView(ui.text(13f, color(R.color.ink), ui.medium) { text = "Allow" }, ui.lp(w = WRAP_CONTENT, start = 8))
                addView(ui.iconView(R.drawable.ic_chevron_right, color(R.color.ink_faint)), ui.lp(w = ui.dp(18), h = ui.dp(18), start = 2))
                setOnClickListener { Perms.ensure(host, Perms.NOTIFICATION_ACCESS) }
            }
        })
        val notes = ArrayList<String>()
        if (missing.any(::blocked)) notes += "Android won't ask again for some of these. Allow them in App info › Permissions."
        val restricted = listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CALL_LOG)
        if (missing.any { it in restricted }) notes += "Android only allows message and call-log access when Andromon installs " +
            "this app over USB, not when the APK is opened on the phone."
        if (notes.isNotEmpty()) permList.addView(ui.text(13f, color(R.color.ink_soft)) {
            text = notes.joinToString("\n\n")
            setLineSpacing(ui.dp(2).toFloat(), 1f)
        }, ui.lp(top = 10))
    }

    /** Counts are read off the main thread; "–" when a permission is missing. */
    private fun loadCounts() {
        val snapshot = counts.toList()
        background({ snapshot.map { count(it.uri, it.permission) } }) { r ->
            val counts = r.getOrNull() ?: return@background
            snapshot.zip(counts).forEach { (row, n) -> row.value.text = n?.let(numbers::format) ?: "–" }
        }
    }

    private fun count(uri: Uri, permission: String): Int? {
        if (host.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            host.contentResolver.query(uri, arrayOf(BaseColumns._ID), null, null, null)?.use { it.count }
        } catch (_: Exception) {
            null
        }
    }
}
