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

    private lateinit var linkState: TextView
    private lateinit var linkSub: TextView
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
        addView(linkCard(), ui.lp(top = 18))
        addView(usbCard(), ui.lp(top = 12))
        addView(wirelessCard(), ui.lp(top = 12))
        addView(permissionsCard(), ui.lp(top = 12))
        addView(ui.text(12f, color(R.color.ink_faint)) {
            text = "Your messages, contacts and call log only go to Droid Desk, on computers you connect or pair."
            gravity = Gravity.CENTER
            setLineSpacing(ui.dp(2).toFloat(), 1f)
        }, ui.lp(top = 16))
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

    /** The hero card: is the link on, the switch, and how much the PC can read. */
    private fun linkCard() = ui.heroCard(18) {
        addView(ui.row {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.chipIcon(R.drawable.ic_desktop))
            addView(ui.text(15f, color(R.color.ink), ui.medium) { text = "Droid Desk link" }, ui.lp(w = 0, weight = 1f, start = 10))
            linkSwitch = ui.toggle { togglePcLink() }
            addView(linkSwitch, ui.lp(w = WRAP_CONTENT, start = 8))
        })
        linkState = ui.text(44f, color(R.color.ink), ui.regular) { letterSpacing = -0.03f; maxLines = 1 }
        addView(linkState, ui.lp(top = 14))
        linkSub = ui.text(13f, color(R.color.ink), ui.medium)
        addView(linkSub, ui.lp(top = 6))
        val (nums, values) = ui.numbers(listOf("Messages", "Contacts", "Calls"))
        listOf(
            Telephony.Sms.CONTENT_URI to Manifest.permission.READ_SMS,
            ContactsContract.Contacts.CONTENT_URI to Manifest.permission.READ_CONTACTS,
            CallLog.Calls.CONTENT_URI to Manifest.permission.READ_CALL_LOG,
        ).forEachIndexed { i, source -> counts += Count(values[i], source.first, source.second) }
        addView(nums, ui.lp(top = 20))
    }

    /** USB: one line, with the four steps tucked behind "How". */
    private fun usbCard() = ui.glassCard(16) {
        val steps = ui.column { visibility = View.GONE }
        val how = ui.pill("How", color(R.color.surface), color(R.color.ink)).apply { textSize = 13f; minHeight = ui.dp(34) }
        val (head, sub) = ui.cardHeader(R.drawable.ic_usb, "USB cable") { addView(how, ui.lp(w = WRAP_CONTENT, start = 8)) }
        sub.text = "Plug in, then click Connect in Droid Desk"
        addView(head)
        listOf(
            "Use a USB cable that carries data.",
            "Turn on Developer options › USB debugging.",
            "Open Droid Desk on the computer and click Connect.",
            "Tap Allow when the phone asks.",
        ).forEachIndexed { i, step ->
            steps.addView(ui.row {
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.text(13f, color(R.color.on_fab), ui.medium) {
                    text = "${i + 1}"
                    gravity = Gravity.CENTER
                    background = ui.circle(color(R.color.fab))
                }, ui.lp(w = ui.dp(24), h = ui.dp(24)))
                addView(ui.text(14f, color(R.color.ink)) { text = step }, ui.lp(w = 0, weight = 1f, start = 12))
            }, ui.lp(top = 12))
        }
        addView(steps, ui.lp(top = 4))
        fun show(open: Boolean) {
            steps.visibility = if (open) View.VISIBLE else View.GONE
            ui.stylePill(how, if (open) "Hide" else "How", color(R.color.surface), color(R.color.ink)) { show(!open) }
        }
        show(false)
    }

    /**
     * Droid Desk can also connect without a cable through Android's Wireless debugging
     * (Android 11+): it shows a QR code that the phone scans in Developer options › Wireless
     * debugging › Pair device with QR code. This card shows how far along the phone is.
     */
    private fun wirelessCard() = ui.glassCard(16) {
        val (head, sub) = ui.cardHeader(R.drawable.ic_wifi, "Wi‑Fi")
        wifiSub = sub.apply { maxLines = 3; ellipsize = null; setLineSpacing(ui.dp(2).toFloat(), 1f) }
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
                wifiSub.text = "Needs Android 11 or later"
                wifiPill.visibility = View.GONE
            }
            canSwitch && devOn -> {
                wifiSub.text = if (wifiOn) "On · click this phone in Droid Desk (same Wi‑Fi)"
                else "Use Droid Desk without the cable, on the same Wi‑Fi"
                if (wifiOn) ui.stylePill(wifiPill, "Turn off Wi‑Fi connection", color(R.color.surface), color(R.color.ink), null) { setAdbWifi(false) }
                else ui.stylePill(wifiPill, "Turn on Wi‑Fi connection", color(R.color.fab), color(R.color.on_fab), R.drawable.ic_wifi) { setAdbWifi(true) }
            }
            else -> {
                wifiSub.text = "Set up once over USB: in Droid Desk, click “Use this phone without the cable”"
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
        linkState.text = if (on) "Ready" else "Paused"
        linkSub.text = if (on) "Droid Desk can connect over USB or Wi‑Fi" else "Turn on to let Droid Desk connect"
        refreshWireless()
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
