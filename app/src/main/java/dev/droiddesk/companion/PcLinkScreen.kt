// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

package dev.droiddesk.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.BaseColumns
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.Switch
import android.widget.TextView
import java.text.NumberFormat

/**
 * The PC companion itself: the USB link to Droid Desk (on/off), the data the PC can read, how to
 * connect, the permissions it needs, and where the data goes.
 */
internal class PcLinkScreen(host: MainActivity) : Screen(host) {

    private companion object { const val ADB_WIFI = "adb_wifi_enabled" } // Settings.Global, hidden constant

    override val name = "PC link"
    override val navIcon = R.drawable.ic_desktop
    override val navColor = R.color.bg

    private val wanted = buildList {
        add(Manifest.permission.READ_SMS); add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.READ_CONTACTS); add(Manifest.permission.WRITE_CONTACTS)
        add(Manifest.permission.READ_CALL_LOG)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val permissionNames = mapOf(
        Manifest.permission.READ_SMS to "Read messages",
        Manifest.permission.SEND_SMS to "Send messages",
        Manifest.permission.READ_CONTACTS to "Read contacts",
        Manifest.permission.WRITE_CONTACTS to "Add contacts",
        Manifest.permission.READ_CALL_LOG to "Read call log",
        "android.permission.POST_NOTIFICATIONS" to "Show the connection notification",
    )

    /** One data-count row in the link card. */
    private class Count(val value: TextView, val uri: Uri, val permission: String)

    private lateinit var linkDot: View
    private lateinit var linkState: TextView
    private lateinit var linkSwitch: Switch
    private lateinit var wifiSub: TextView
    private lateinit var wifiPill: TextView
    private lateinit var permSub: TextView
    private lateinit var permPill: TextView
    private lateinit var permList: LinearLayout
    private val counts = mutableListOf<Count>()
    private val numbers = NumberFormat.getIntegerInstance()

    override fun build(): View = ui.column {
        addView(ui.topBar("PC link", host::goHome).first)
        addView(linkStack(), ui.lp(top = 26))
        addView(ui.section("How to connect"), ui.lp(top = 28))
        addView(stepsCard(), ui.lp(top = 12))
        addView(ui.section("Connect over Wi‑Fi"), ui.lp(top = 28))
        addView(wirelessCard(), ui.lp(top = 12))
        addView(ui.section("Permissions"), ui.lp(top = 28))
        addView(permissionsCard(), ui.lp(top = 12))
        addView(privacyNote(), ui.lp(top = 12))
    }

    override fun onShow() {
        CompanionService.stateListener = ::refresh
        refresh()
    }

    override fun onHide() {
        CompanionService.stateListener = null
    }

    override fun onPermissionsResult() = refresh()

    // ---------- sections ----------

    /** "Events with Friends": an olive card stacked on two tilted cards peeking out behind it. */
    private fun linkStack() = FrameLayout(host).apply {
        clipChildren = false
        fun peek(bg: Int, icon: Int, left: Int, right: Int, top: Int, tilt: Float) = FrameLayout(context).apply {
            background = ui.rounded(color(bg), 24)
            rotation = tilt
            addView(ui.iconView(icon, color(R.color.ink)), FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.TOP or Gravity.END).apply {
                topMargin = ui.dp(12); rightMargin = ui.dp(18)
            })
        }.also {
            addView(it, FrameLayout.LayoutParams(-1, ui.dp(90)).apply { leftMargin = ui.dp(left); rightMargin = ui.dp(right); topMargin = ui.dp(top) })
        }
        peek(R.color.pink, R.drawable.ic_sms, 70, 40, 0, -3f)
        peek(R.color.sky, R.drawable.ic_usb, 24, 12, 22, 2f)

        val card = ui.heroCard(14) {
            addView(ui.row {
                setPadding(ui.dp(6), ui.dp(4), 0, ui.dp(4))
                addView(ui.column {
                    addView(ui.text(22f, color(R.color.ink), ui.light) { text = "Droid Desk link"; letterSpacing = -0.02f })
                    val (r, d, l) = ui.dotLabel()
                    linkDot = d; linkState = l
                    l.setTextColor(color(R.color.ink))
                    addView(r, ui.lp(top = 6))
                }, ui.lp(w = 0, weight = 1f))
                linkSwitch = ui.toggle { togglePcLink() }
                addView(linkSwitch, ui.lp(w = WRAP_CONTENT, start = 8))
            })
            listOf(
                Triple("Messages", R.drawable.ic_sms, Telephony.Sms.CONTENT_URI to Manifest.permission.READ_SMS),
                Triple("Contacts", R.drawable.ic_person, ContactsContract.Contacts.CONTENT_URI to Manifest.permission.READ_CONTACTS),
                Triple("Call log", R.drawable.ic_call, CallLog.Calls.CONTENT_URI to Manifest.permission.READ_CALL_LOG),
            ).forEachIndexed { i, (label, icon, source) ->
                val (row, value) = ui.listPill(icon, label)
                value.text = "–"
                addView(row, ui.lp(top = if (i == 0) 14 else 8))
                counts += Count(value, source.first, source.second)
            }
        }
        addView(card, FrameLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(46) })
    }

    private fun stepsCard() = ui.glassCard(16) {
        listOf(
            "Connect this phone to your computer with a USB cable that carries data.",
            "Turn on Developer options › USB debugging on the phone.",
            "Open Droid Desk on the computer and click Connect.",
            "Allow USB debugging when the phone asks. Tick “Always allow” to skip this next time.",
        ).forEachIndexed { i, step ->
            addView(ui.row {
                gravity = Gravity.TOP
                addView(ui.text(14f, color(R.color.on_fab), ui.medium) {
                    text = "${i + 1}"
                    gravity = Gravity.CENTER
                    background = ui.circle(color(R.color.fab))
                }, ui.lp(w = ui.dp(28), h = ui.dp(28)))
                addView(ui.text(14f, color(R.color.ink)) { text = step; setLineSpacing(ui.dp(2).toFloat(), 1f) },
                    ui.lp(w = 0, weight = 1f, start = 12).apply { topMargin = ui.dp(3) })
            }, ui.lp(top = if (i == 0) 0 else 14))
        }
    }

    /**
     * Droid Desk can also connect without a cable through Android's Wireless debugging
     * (Android 11+): it shows a QR code that the phone scans in Developer options › Wireless
     * debugging › Pair device with QR code. This card shows how far along the phone is.
     */
    private fun wirelessCard() = ui.glassCard(16) {
        val (head, sub) = ui.cardHeader(R.drawable.ic_wifi, "Without a cable")
        wifiSub = sub.apply { maxLines = 8; ellipsize = null; setLineSpacing(ui.dp(2).toFloat(), 1f) }
        addView(head)
        wifiPill = ui.pill("", 0, 0).apply { textSize = 13f; minHeight = ui.dp(36) }
        addView(wifiPill, ui.lp(w = WRAP_CONTENT, top = 12))
    }

    private fun refreshWireless() {
        val cr = host.contentResolver
        val devOn = Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        val wifiOn = Settings.Global.getInt(cr, ADB_WIFI, 0) == 1
        // Granted by Droid Desk during its one-time USB setup ("Use this phone without the cable").
        val canSwitch = host.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED
        fun open(action: String): () -> Unit = { runCatching { host.startActivity(Intent(action)) } }
        wifiPill.visibility = View.VISIBLE
        when {
            Build.VERSION.SDK_INT < 30 -> {
                wifiSub.text = "Needs Android 11 or later. On this phone, use the USB cable."
                wifiPill.visibility = View.GONE
            }
            canSwitch && devOn -> {
                wifiSub.text = if (wifiOn) "On. Open Droid Desk on your computer (same Wi‑Fi) and click this phone to connect. " +
                    "Turn it off when you're done."
                else "Turn on to use Droid Desk without the cable, on the same Wi‑Fi as your computer. " +
                    "If the phone asks “Allow wireless debugging on this network?”, tap Allow."
                if (wifiOn) ui.stylePill(wifiPill, "Turn off Wi‑Fi connection", color(R.color.surface), color(R.color.ink), null) { setAdbWifi(false) }
                else ui.stylePill(wifiPill, "Turn on Wi‑Fi connection", color(R.color.fab), color(R.color.on_fab), R.drawable.ic_wifi) { setAdbWifi(true) }
            }
            else -> {
                wifiSub.text = "One-time setup: connect this phone to your computer with the USB cable, open Droid Desk " +
                    "and click “Use this phone without the cable”. After that, turn it on here with one tap."
                if (devOn) ui.stylePill(wifiPill, "Set up manually instead", color(R.color.surface), color(R.color.ink), null, open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                else wifiPill.visibility = View.GONE
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
        wifiSub.postDelayed({ if (!host.isDestroyed) refreshWireless() }, 6000)
    }

    private fun permissionsCard() = ui.glassCard(16) {
        val (head, sub) = ui.cardHeader(R.drawable.ic_lock, "Data access") {
            permPill = ui.pill("", 0, 0).apply { textSize = 13f; minHeight = ui.dp(34) }
            addView(permPill, ui.lp(w = WRAP_CONTENT, start = 8))
        }
        permSub = sub
        addView(head)
        permList = ui.column { setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2)) }
        addView(permList)
    }

    private fun privacyNote(): View {
        val (row, label) = ui.insight(R.drawable.ic_shield, color(R.color.mint))
        label.text = "Messages, contacts and call log only go to Droid Desk: over the USB cable, or over " +
            "Android's encrypted Wireless debugging to computers you paired. Otherwise the network is used " +
            "only for network tests and, while you have it on, Wi‑Fi receive."
        return row
    }

    // ---------- state ----------

    private fun togglePcLink() {
        if (CompanionService.running) {
            host.startService(Intent(host, CompanionService::class.java).setAction(CompanionService.ACTION_STOP))
        } else {
            CompanionService.start(host)
        }
        // refresh() runs via CompanionService.stateListener once the service has started/stopped.
    }

    private fun refresh() {
        val on = CompanionService.running
        linkState.text = if (on) "Ready · USB or Wireless debugging" else "Paused · turn on to connect"
        refreshWireless()
        linkDot.background = ui.circle(color(if (on) R.color.green_dot else R.color.peach_dot))
        linkSwitch.isChecked = on
        linkSwitch.contentDescription = if (on) "Stop PC connection" else "Start PC connection"

        val missing = wanted.filter { host.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        permSub.text = "${wanted.size - missing.size} of ${wanted.size} allowed"
        if (missing.isEmpty()) {
            ui.stylePill(permPill, "All set", color(R.color.mint), color(R.color.ink), R.drawable.ic_check)
            permList.visibility = View.GONE
        } else {
            ui.stylePill(permPill, "Allow", color(R.color.fab), color(R.color.on_fab)) {
                host.requestPermissions(missing.toTypedArray(), 1)
            }
            permList.visibility = View.VISIBLE
            fillPermissionList(missing)
        }
        loadCounts()
    }

    private fun fillPermissionList(missing: List<String>) {
        permList.removeAllViews()
        for (p in wanted) {
            val ok = p !in missing
            permList.addView(ui.row {
                addView(ui.iconView(if (ok) R.drawable.ic_check else R.drawable.ic_dot_empty,
                    color(if (ok) R.color.good else R.color.ink_faint)), ui.lp(w = ui.dp(18), h = ui.dp(18)))
                addView(ui.text(15f, color(if (ok) R.color.ink else R.color.ink_soft)) {
                    text = permissionNames[p] ?: p.substringAfterLast('.')
                }, ui.lp(w = 0, weight = 1f, start = 12))
            }, ui.lp(top = 12))
        }
        val restricted = listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CALL_LOG)
        if (missing.any { it in restricted }) {
            permList.addView(ui.text(13f, color(R.color.ink_soft)) {
                text = "Android only allows message and call-log access when Droid Desk installs " +
                    "this app over USB, not when the APK is opened on the phone."
                setLineSpacing(ui.dp(2).toFloat(), 1f)
            }, ui.lp(top = 14))
        }
    }

    /** Counts are read off the main thread; rows show "No access" when a permission is missing. */
    private fun loadCounts() {
        val snapshot = counts.toList()
        background({ snapshot.map { count(it.uri, it.permission) } }) { r ->
            val counts = r.getOrNull() ?: return@background
            snapshot.zip(counts).forEach { (row, n) -> row.value.text = n?.let(numbers::format) ?: "No access" }
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
