// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.TextView

/**
 * Transfer ("Receive" in earlier versions): files between this phone and a computer over Wi‑Fi,
 * with a Receive | Send switch that remembers the side used last.
 *
 * Receive makes this phone visible to computers on the same Wi‑Fi (Andromon's Wi‑Fi transfer, or
 * another app that speaks the same open protocol) and saves what they send, after the person
 * accepts, to Download/Andromon. The work happens in [ReceiveService]. Send is [SendPanel].
 */
internal class TransferScreen(host: MainActivity) : Screen(host) {

    companion object {
        /** Side to show next time Transfer opens ("Receive" / "Send" from a notification, tile or another screen). */
        private var wanted: Boolean? = null
        fun requestMode(send: Boolean) { wanted = send }
    }

    override val name = "Transfer"
    override val navIcon = R.drawable.ic_transfer
    override val navColor = R.color.bg

    private lateinit var statusPill: TextView
    private lateinit var hero: LinearLayout
    private lateinit var heroDot: View
    private lateinit var heroLabel: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private lateinit var addressRow: View
    private lateinit var addressValue: TextView
    private lateinit var linkValue: TextView
    private lateinit var linkRow: View
    private lateinit var directPill: TextView
    private lateinit var directNote: TextView
    private lateinit var askDirect: TextView
    private lateinit var progVia: TextView
    private lateinit var toggle: TextView
    private lateinit var messageText: TextView

    private lateinit var askCard: LinearLayout
    private lateinit var askTitle: TextView
    private lateinit var askMeta: TextView
    private lateinit var askList: LinearLayout
    private lateinit var askTimer: TextView
    private var askShown: String? = null

    private lateinit var progCard: LinearLayout
    private lateinit var progTitle: TextView
    private lateinit var progBig: TextView
    private lateinit var progMeter: Meter
    private lateinit var progText: TextView

    private lateinit var recentList: LinearLayout
    private lateinit var recentEmpty: TextView
    private lateinit var clearPill: TextView
    private var recentShown = -1

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { updateTimer(); handler.postDelayed(this, 1000) }
    }
    private val listener: () -> Unit = { refresh() }
    private var startAfterPermission = false

    private lateinit var pageSub: TextView
    private lateinit var receiveBody: LinearLayout
    private var selectMode: (Int) -> Unit = {}
    private var sendMode = false
    private val sendPanel by lazy { SendPanel(host) }

    override fun build(): View = ui.column {
        val (bar, _) = ui.topBar("Transfer", host::goHome)
        statusPill = ui.smallPill("")
        bar.removeViewAt(bar.childCount - 1) // the empty action slot: the status pill takes its place
        bar.addView(statusPill, ui.lp(w = WRAP_CONTENT, start = 8))
        addView(bar)
        pageSub = ui.subtitle("Files between this phone and your computer over Wi‑Fi")
        addView(pageSub, ui.lp(top = 6))
        // Receive | Send: the Send side lives in SendPanel (SendScreen.kt).
        val (modeSwitch, select) = segmented(ui, listOf("Receive", "Send")) { setMode(it == 1, remember = true) }
        selectMode = select
        addView(modeSwitch, ui.lp(top = 20))
        receiveBody = ui.column {
            // Requests and progress go first, so Accept is on screen without scrolling.
            addView(askCard(), ui.lp(top = 12))
            addView(progressCard(), ui.lp(top = 12))
            addView(heroCard(), ui.lp(top = 12))
            addView(recentCard(), ui.lp(top = 12))
            val (row, label) = ui.insight(R.drawable.ic_lock, color(R.color.green_tile))
            label.text = "Nothing is saved until you tap Accept. Transfers are encrypted, and only " +
                "devices on the same Wi‑Fi can see this phone while receiving is on."
            addView(row, ui.lp(top = 12))
        }
        addView(receiveBody)
        addView(sendPanel.view.apply { visibility = View.GONE })
    }

    private var modeSet = false

    /** Switches between the Receive side (this class) and the Send side ([SendPanel]); [remember] keeps it for next time. */
    private fun setMode(send: Boolean, remember: Boolean = false) {
        view // make sure the screen is built
        if (remember) Prefs.setTransferSend(host, send)
        if (send == sendMode && modeSet) { host.setDock(if (send) sendPanel.dockButton else null); return }
        val first = !modeSet
        modeSet = true
        sendMode = send
        selectMode(if (send) 1 else 0)
        statusPill.visibility = if (send) View.INVISIBLE else View.VISIBLE
        receiveBody.visibility = if (send) View.GONE else View.VISIBLE
        sendPanel.view.visibility = if (send) View.VISIBLE else View.GONE
        if (send) sendPanel.onShow() else sendPanel.onHide()
        // Send's main button stays above the bottom bar, so it is never hidden under it.
        host.setDock(if (send) sendPanel.dockButton else null)
        if (!first) host.scrollToTop()
    }

    override fun onShow() {
        WifiReceive.loadRecent(host)
        WifiReceive.listen(listener)
        WifiReceive.server?.announce()
        handler.removeCallbacks(tick)
        handler.post(tick)
        // Opened from Share › Andromon, a picker or a send notification: the Send side. Else the side
        // a link asked for, else the one used last.
        val send = when {
            WifiSend.takeOpenRequest() -> true
            wanted != null -> wanted!!
            !modeSet -> Prefs.transferSend(host)
            else -> sendMode
        }
        wanted = null
        modeSet = modeSet && send == sendMode
        setMode(send)
        if (sendMode) sendPanel.onShow()
        refresh()
    }

    override fun onHide() {
        WifiReceive.unlisten(listener)
        handler.removeCallbacks(tick)
        sendPanel.onHide()
    }

    override fun onPermissionsResult() {
        if (sendMode) sendPanel.onPermissionsResult()
        refresh()
        if (!startAfterPermission) return
        startAfterPermission = false
        if (Build.VERSION.SDK_INT < 29 &&
            host.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            WifiReceive.post { message = "Allow storage access so received files can be saved to Download." }
            return
        }
        ReceiveService.start(host)
    }

    // ---------- sections ----------

    private fun heroCard(): View {
        hero = ui.column { setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18)) }
        hero.addView(ui.row {
            heroDot = View(context)
            addView(heroDot, ui.lp(w = ui.dp(8), h = ui.dp(8)))
            heroLabel = ui.text(15f, color(R.color.ink), ui.medium)
            addView(heroLabel, ui.lp(w = 0, weight = 1f, start = 10))
            addView(ui.chipIcon(R.drawable.ic_wifi, 40))
        })
        heroTitle = ui.text(34f, color(R.color.ink), ui.light) {
            letterSpacing = -0.02f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        PrivacyBlur.always(heroTitle) // this phone's name
        hero.addView(heroTitle, ui.lp(top = 4))
        heroSub = ui.text(14f, color(R.color.ink)) { setLineSpacing(ui.dp(2).toFloat(), 1f) }
        hero.addView(heroSub, ui.lp(top = 10))

        val details = ui.column { setPadding(0, ui.dp(6), ui.dp(6), 0) }
        val (aRow, aValue) = ui.keyValue("Address")
        addressRow = aRow; addressValue = aValue
        PrivacyBlur.always(aValue)
        details.addView(aRow, ui.lp(top = 12))
        val (fRow, fValue) = ui.keyValue("Saved to")
        fValue.text = "Download/${WifiServer.FOLDER}"
        details.addView(fRow, ui.lp(top = 10))
        val (lRow, lValue) = ui.keyValue("Wi‑Fi link")
        linkRow = lRow; linkValue = lValue
        details.addView(lRow, ui.lp(top = 10))
        hero.addView(details)

        // Direct link setting
        hero.addView(ui.row {
            addView(ui.column {
                addView(ui.text(16f, color(R.color.ink), ui.medium) { text = "Direct link" })
                directNote = ui.text(13f, color(R.color.ink)) { setLineSpacing(ui.dp(2).toFloat(), 1f) }
                addView(directNote, ui.lp(top = 4))
            }, ui.lp(w = 0, weight = 1f))
            directPill = ui.pill("", 0, 0).apply { textSize = 14f; minHeight = ui.dp(36) }
            addView(directPill, ui.lp(w = WRAP_CONTENT, start = 12))
        }, ui.lp(top = 16))

        toggle = ui.action("", R.drawable.ic_power) { toggle() }
        hero.addView(toggle, ui.lp(top = 18))
        messageText = ui.text(14f, color(R.color.ink)) { setLineSpacing(ui.dp(2).toFloat(), 1f) }
        PrivacyBlur.ifFound(messageText)
        hero.addView(messageText, ui.lp(top = 12))
        return hero
    }

    private fun askCard() = ui.card(color(R.color.good_bg), 18) {
        askCard = this
        addView(ui.row {
            addView(ui.chipIcon(R.drawable.ic_down, 40))
            addView(ui.column {
                askTitle = ui.text(18f, color(R.color.ink), ui.medium) { setLineSpacing(ui.dp(2).toFloat(), 1f) }
                addView(askTitle)
                askMeta = ui.text(14f, color(R.color.ink_soft))
                addView(askMeta, ui.lp(top = 4))
                PrivacyBlur.ifFound(askTitle) // the sender's name
                PrivacyBlur.ifFound(askMeta) // its IP address
            }, ui.lp(w = 0, weight = 1f, start = 14))
        })
        askList = ui.column { setPadding(ui.dp(4), 0, ui.dp(4), 0) }
        addView(askList, ui.lp(top = 8))
        askDirect = ui.text(14f, color(R.color.ink)) { setLineSpacing(ui.dp(2).toFloat(), 1f) }
        addView(askDirect, ui.lp(top = 12))
        askTimer = ui.text(13f, color(R.color.ink_soft))
        addView(askTimer, ui.lp(top = 12))
        addView(ui.row {
            addView(ui.pill("Decline", color(R.color.surface), color(R.color.ink)) { answer(false) }, ui.lp(w = 0, weight = 1f))
            addView(ui.pill("Accept", color(R.color.fab), color(R.color.on_fab), R.drawable.ic_check) { answer(true) },
                ui.lp(w = 0, weight = 1f, start = 10))
        }, ui.lp(top = 12))
    }

    private fun progressCard() = ui.glassCard(18) {
        progCard = this
        addView(ui.row {
            progTitle = ui.text(16f, color(R.color.ink_soft)) { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            PrivacyBlur.ifFound(progTitle)
            addView(progTitle, ui.lp(w = 0, weight = 1f))
            addView(ui.pill("Stop", color(R.color.surface), color(R.color.ink)) { WifiReceive.stopTransfer() }.apply {
                textSize = 14f; minHeight = ui.dp(34)
            }, ui.lp(w = WRAP_CONTENT))
        })
        progBig = ui.text(48f, color(R.color.ink), ui.light) { letterSpacing = -0.03f }
        addView(progBig, ui.lp(top = 6))
        progMeter = Meter(context).apply { colors(color(R.color.chip), color(R.color.green_dot)) }
        addView(progMeter, ui.lp(h = ui.dp(10), top = 12))
        progText = ui.text(14f, color(R.color.ink_soft))
        addView(progText, ui.lp(top = 10))
        progVia = ui.text(14f, color(R.color.ink))
        addView(progVia, ui.lp(top = 6))
    }

    private fun recentCard() = ui.glassCard(16) {
        val (head, headSub) = ui.cardHeader(R.drawable.ic_folder, "Received") {
            clearPill = ui.smallPill("Clear") { WifiReceive.recent.clear(); WifiReceive.saveRecent(host); refresh() }
            addView(clearPill, ui.lp(w = WRAP_CONTENT, start = 8))
            addView(ui.smallPill("Downloads") { openDownloads() }, ui.lp(w = WRAP_CONTENT, start = 8))
        }
        headSub.text = "In Download/${WifiServer.FOLDER}"
        addView(head)
        recentEmpty = ui.text(14f, color(R.color.ink_soft)) { text = "Files you receive appear here."; setLineSpacing(ui.dp(2).toFloat(), 1f) }
        addView(recentEmpty, ui.lp(top = 12))
        recentList = ui.column()
        addView(recentList, ui.lp(top = 4))
    }

    // ---------- actions ----------

    private fun toggle() {
        if (WifiReceive.running || WifiReceive.starting) {
            ReceiveService.stop(host)
            return
        }
        val ask = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) // Accept/Decline while in the background
            if (Build.VERSION.SDK_INT < 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filter { host.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (ask.isNotEmpty()) {
            startAfterPermission = true
            host.requestPermissions(ask.toTypedArray(), 2)
        } else {
            ReceiveService.start(host)
        }
    }

    private fun answer(accept: Boolean) {
        WifiReceive.request?.let { WifiReceive.respond(it.id, accept) }
    }

    private fun openDownloads() {
        try {
            host.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (_: ActivityNotFoundException) {
            WifiReceive.post { message = "No Downloads app found. Look in Download/${WifiServer.FOLDER} with a file manager." }
        }
    }

    private fun open(item: WifiReceive.Saved) {
        val uri = Uri.parse(item.uri)
        if (uri.scheme == "file") { openDownloads(); return } // not indexed yet (Android 8–9)
        try {
            host.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: ActivityNotFoundException) {
            WifiReceive.post { message = "No app on this phone can open ${item.name}." }
        } catch (_: SecurityException) {
            WifiReceive.post { message = "${item.name} was moved or deleted." }
        }
    }

    // ---------- state ----------

    private fun onLan(): Boolean {
        val cm = host.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return true
        return WifiServer.lanAddress() != null // e.g. this phone's own hotspot
    }

    private fun refresh() {
        val on = WifiReceive.running
        val starting = WifiReceive.starting && !on
        val lan = onLan()

        // The hero gradient while receiving; a plain card while off.
        hero.background = if (on) ui.hero() else ui.rounded(color(R.color.surface), 26)
        heroDot.background = ui.circle(color(if (on) R.color.green_dot else R.color.peach_dot))
        heroLabel.text = when {
            on -> "Visible on Wi‑Fi as"
            starting -> "Starting…"
            else -> "Receiving is off"
        }
        heroTitle.text = if (on) WifiReceive.alias else WifiServer.deviceName(host)
        heroSub.text = when {
            !lan -> "Connect to the same Wi‑Fi as your computer. You can also turn on this phone's hotspot and connect the computer to it."
            on -> "On your computer, open Andromon › Wi‑Fi transfer and send files to this phone."
            else -> "Turn on to receive files from computers on the same Wi‑Fi."
        }
        val addr = WifiServer.lanAddress()
        addressRow.visibility = if (on && addr != null) View.VISIBLE else View.GONE
        addressValue.text = if (WifiReceive.port == WifiServer.PORT) "$addr" else "$addr:${WifiReceive.port}"
        ui.setLabel(toggle, if (on || starting) "Turn off receiving" else "Turn on receiving")
        if (on) ui.stylePill(statusPill, "On", color(R.color.good_bg), color(R.color.good), R.drawable.ic_check)
        else ui.stylePill(statusPill, if (starting) "Starting" else "Off", color(R.color.surface), color(R.color.ink_soft))
        val link = WifiServer.wifiLink(host)
        linkRow.visibility = if (link != null) View.VISIBLE else View.GONE
        if (link != null) {
            val rssi = link.optInt("rssi", -127)
            val signal = when { rssi >= -60 -> "strong"; rssi >= -70 -> "fair"; rssi > -127 -> "weak"; else -> null }
            linkValue.text = listOfNotNull(link.optString("band").ifEmpty { null },
                link.optInt("linkMbps").takeIf { it > 0 }?.let { "$it Mbit/s" }, signal?.let { "$it signal" }).joinToString(" · ")
        }
        refreshDirect()
        messageText.text = WifiReceive.message.orEmpty()
        messageText.visibility = if (WifiReceive.message.isNullOrEmpty()) View.GONE else View.VISIBLE

        // Incoming request
        val r = WifiReceive.request
        askCard.visibility = if (r != null) View.VISIBLE else View.GONE
        if (r != null && r.id != askShown) {
            askShown = r.id
            Pii.addKnown(r.from) // Blur personal data finds the name in the lines below
            askTitle.text = "${r.from} wants to send you " + if (r.files.size == 1) "a file" else "${r.files.size} files"
            askMeta.text = listOfNotNull(fmtSize(r.total), r.model, r.ip).joinToString(" · ")
            askList.removeAllViews()
            for ((name, size) in r.files.take(5)) {
                askList.addView(ui.row {
                    addView(ui.iconView(R.drawable.ic_file, color(R.color.ink_soft)), ui.lp(w = ui.dp(18), h = ui.dp(18)))
                    addView(ui.text(15f, color(R.color.ink)) {
                        text = name; maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
                    }, ui.lp(w = 0, weight = 1f, start = 10))
                    addView(ui.text(14f, color(R.color.ink_soft)) { text = fmtSize(size) }, ui.lp(w = WRAP_CONTENT, start = 10))
                }, ui.lp(top = 10))
            }
            if (r.files.size > 5) askList.addView(ui.text(14f, color(R.color.ink_soft)) {
                text = "+ ${r.files.size - 5} more"
            }, ui.lp(top = 10, start = 28))
        }
        if (r != null) {
            askDirect.visibility = if (r.direct) View.VISIBLE else View.GONE
            askDirect.text = "Direct link: for this transfer your computer joins a private Wi‑Fi network from this " +
                "phone, which is faster. Android needs this app open to start it."
        }
        if (r != null && askCard.tag != r.id) {
            askCard.tag = r.id
            setMode(false) // a new request while on the Send side: bring Accept into view
            host.scrollToTop()
        }
        if (r == null) askShown = null
        updateTimer()

        // Progress
        val p = WifiReceive.progress
        progCard.visibility = if (p != null) View.VISIBLE else View.GONE
        if (p != null) {
            Pii.addKnown(p.from)
            progTitle.text = "Receiving from ${p.from}"
            val pct = if (p.total > 0) (p.received * 100 / p.total).toInt() else 100
            progBig.text = ui.withUnit("$pct", "%")
            progMeter.fraction = if (p.total > 0) p.received.toFloat() / p.total else 1f
            progText.text = listOfNotNull(
                "${p.filesDone} of ${p.fileCount} files",
                "${fmtSize(p.received)} of ${fmtSize(p.total)}",
                if (p.bytesPerSec > 0) "${fmtSize(p.bytesPerSec)}/s" else null,
            ).joinToString(" · ")
            progVia.text = p.via ?: "Over your Wi‑Fi network"
        }

        // Received files
        val items = WifiReceive.recent
        val stamp = items.size * 31 + (items.firstOrNull()?.time?.hashCode() ?: 0) + (items.firstOrNull()?.uri?.hashCode() ?: 0)
        if (stamp != recentShown) {
            recentShown = stamp
            recentList.removeAllViews()
            for (item in items.take(20)) recentList.addView(recentRow(item), ui.lp(top = 8))
        }
        recentEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        clearPill.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun recentRow(item: WifiReceive.Saved) = ui.row {
        background = ui.pressable(ui.rounded(android.graphics.Color.TRANSPARENT, 20))
        setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8))
        val icon = when {
            item.mime.startsWith("image/") || item.mime.startsWith("video/") -> R.drawable.ic_image
            item.mime == "application/vnd.android.package-archive" -> R.drawable.ic_apk
            else -> R.drawable.ic_file
        }
        addView(ui.roundIcon(40, color(R.color.glass_strong), icon, color(R.color.ink), 19))
        addView(ui.column {
            addView(ui.text(15f, color(R.color.ink)) {
                text = item.name; maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            Pii.addKnown(item.from)
            addView(ui.text(13f, color(R.color.ink_soft)) {
                text = listOf(fmtSize(item.size), item.from, ago(item.time)).joinToString(" · ")
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                PrivacyBlur.ifFound(this) // the sender's name
            }, ui.lp(top = 4))
        }, ui.lp(w = 0, weight = 1f, start = 12))
        setOnClickListener { open(item) }
    }

    private fun refreshDirect() {
        val bg = color(R.color.surface)
        when (DirectLink.state(host)) {
            DirectLink.State.READY -> {
                directNote.text = "Your computer can join a private Wi‑Fi from this phone for big transfers. Faster than going through the router."
                ui.stylePill(directPill, "On", color(R.color.good_bg), color(R.color.good), R.drawable.ic_check) {
                    DirectLink.setEnabled(host, false); refresh()
                }
            }
            DirectLink.State.OFF -> {
                directNote.text = "Off: transfers always go through your Wi‑Fi router."
                ui.stylePill(directPill, "Off", bg, color(R.color.ink)) { DirectLink.setEnabled(host, true); refresh() }
            }
            DirectLink.State.NEEDS_PERMISSION -> {
                directNote.text = if (Build.VERSION.SDK_INT >= 33) "Needs the Nearby devices permission to start a private Wi‑Fi."
                    else "Needs location access to start a private Wi‑Fi (an Android rule; your location isn't used)."
                ui.stylePill(directPill, "Allow", color(R.color.fab), color(R.color.on_fab)) {
                    host.requestPermissions(arrayOf(DirectLink.permission), 3)
                }
            }
            DirectLink.State.NEEDS_LOCATION -> {
                directNote.text = "Turn on Location: Android 12 and older need it to start a private Wi‑Fi."
                ui.stylePill(directPill, "Settings", color(R.color.fab), color(R.color.on_fab)) {
                    runCatching { host.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                }
            }
            DirectLink.State.UNSUPPORTED -> {
                directNote.text = "This phone can't host a private Wi‑Fi while it's connected to Wi‑Fi."
                ui.stylePill(directPill, "Not available", bg, color(R.color.ink_soft))
            }
        }
    }

    private fun updateTimer() {
        val r = WifiReceive.request ?: return
        val left = ((r.expires - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        askTimer.text = "Declines automatically in $left s"
        askTimer.gravity = Gravity.START
    }
}
