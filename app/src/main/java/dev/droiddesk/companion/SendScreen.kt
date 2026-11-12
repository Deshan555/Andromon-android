// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

package dev.droiddesk.companion

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.InputType
import android.text.TextUtils
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.TextView

/** "Receive | Send" style switch: a pill-shaped track with one filled segment. Returns the view and a setter. */
internal fun segmented(ui: Ui, labels: List<String>, onPick: (Int) -> Unit): Pair<View, (Int) -> Unit> {
    val parts = ArrayList<TextView>()
    val select: (Int) -> Unit = { chosen ->
        parts.forEachIndexed { i, tv ->
            val on = i == chosen
            tv.background = if (on) ui.rounded(ui.color(R.color.fab), 50) else ui.pressable(ui.rounded(0, 50))
            tv.setTextColor(ui.color(if (on) R.color.on_fab else R.color.ink))
            tv.isSelected = on
        }
    }
    val track = ui.row {
        background = ui.rounded(ui.color(R.color.glass), 50)
        val p = ui.dp(4)
        setPadding(p, p, p, p)
        labels.forEachIndexed { i, label ->
            val tv = ui.text(15f, ui.color(R.color.ink), ui.medium) {
                text = label
                gravity = android.view.Gravity.CENTER
                minHeight = ui.dp(40)
                setOnClickListener { onPick(i) }
            }
            parts += tv
            addView(tv, ui.lp(w = 0, weight = 1f))
        }
    }
    select(0)
    return track to select
}

/**
 * The Send side of the transfer screen (shown by [ReceiveScreen] under its "Receive | Send"
 * switch, and opened by the share target): the queue of things to send, the devices found on
 * the Wi‑Fi, and the running transfer. The work happens in [WifiSend] and [SendService].
 */
internal class SendPanel(private val host: MainActivity) {
    private val ui: Ui get() = host.ui
    private fun color(id: Int) = ui.color(id)

    private lateinit var progCard: LinearLayout
    private lateinit var progTitle: TextView
    private lateinit var progBig: TextView
    private lateinit var progMeter: Meter
    private lateinit var progText: TextView

    private lateinit var pinCard: LinearLayout
    private lateinit var pinTitle: TextView
    private lateinit var pinInput: EditText

    private lateinit var doneCard: LinearLayout
    private lateinit var doneTitle: TextView
    private lateinit var doneText: TextView

    private lateinit var queueCount: TextView
    private lateinit var queueList: LinearLayout
    private lateinit var queueEmpty: TextView
    private lateinit var queueButtons: View
    private lateinit var clearPill: TextView
    private var queueShown = ""
    private val queueSubs = ArrayList<Pair<WifiSend.Item, TextView>>()

    private lateinit var refreshPill: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var deviceEmpty: TextView
    private lateinit var addressRow: LinearLayout
    private lateinit var addressInput: EditText
    private lateinit var addressPill: TextView
    private var devicesShown = ""

    private lateinit var sendButton: TextView
    private lateinit var messageText: TextView

    private val listener: () -> Unit = { refresh() }
    private var shown = false
    private var sendAfterPermission = false
    private var askedNotifications = false

    val view: View by lazy { build() }

    private fun build(): View = ui.column {
        addView(progressCard(), ui.lp(top = 12))
        addView(pinCard(), ui.lp(top = 12))
        addView(doneCard(), ui.lp(top = 12))
        addView(queueCard(), ui.lp(top = 12))
        addView(deviceCard(), ui.lp(top = 12))
        sendButton = ui.action("Send", R.drawable.ic_up) { send() }
        addView(sendButton, ui.lp(top = 16))
        messageText = ui.text(14f, color(R.color.ink)) { setLineSpacing(ui.dp(2).toFloat(), 1f) }
        addView(messageText, ui.lp(top = 12))
        val (row, label) = ui.insight(R.drawable.ic_lock, color(R.color.peach))
        label.text = "Nothing is sent until you tap Send and the other device accepts. Transfers are " +
            "encrypted, and the other device is checked against the identity it announced."
        addView(row, ui.lp(top = 12))
    }

    // ---------- lifecycle (driven by ReceiveScreen) ----------

    fun onShow() {
        if (!shown) { shown = true; WifiSend.listen(listener) }
        val stale = System.currentTimeMillis() - WifiSend.lastScan > 20_000
        if (!WifiSend.active && (WifiSend.devices.isEmpty() || stale)) WifiSend.scan(host, sweep = false)
        refresh()
    }

    fun onHide() {
        if (shown) { shown = false; WifiSend.unlisten(listener) }
    }

    fun onPermissionsResult() {
        if (!sendAfterPermission) return
        sendAfterPermission = false
        SendService.start(host) // with or without notifications: the transfer itself doesn't need them
    }

    // ---------- sections ----------

    private fun progressCard() = ui.card(color(R.color.blue), 20) {
        progCard = this
        addView(ui.row {
            progTitle = ui.text(16f, color(R.color.ink_soft)) { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
            addView(progTitle, ui.lp(w = 0, weight = 1f))
            addView(ui.pill("Cancel", color(R.color.surface), color(R.color.ink)) { WifiSend.cancel() }.apply {
                textSize = 14f; minHeight = ui.dp(34)
            }, ui.lp(w = WRAP_CONTENT, start = 10))
        })
        progBig = ui.text(48f, color(R.color.ink), ui.light) { letterSpacing = -0.03f }
        addView(progBig, ui.lp(top = 6))
        progMeter = Meter(context).apply { colors(color(R.color.blue_tile), color(R.color.blue_line)) }
        addView(progMeter, ui.lp(h = ui.dp(10), top = 12))
        progText = ui.text(14f, color(R.color.ink_soft)) { setLineSpacing(ui.dp(2).toFloat(), 1f) }
        addView(progText, ui.lp(top = 10))
    }

    private fun pinCard() = ui.card(color(R.color.yellow), 20) {
        pinCard = this
        pinTitle = ui.text(18f, color(R.color.ink), ui.medium) { setLineSpacing(ui.dp(2).toFloat(), 1f) }
        addView(pinTitle)
        addView(ui.text(14f, color(R.color.ink_soft)) {
            text = "It is shown in the receiving app's settings on that device."
        }, ui.lp(top = 4))
        pinInput = input("PIN", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) { submitPin() }
        addView(pinInput, ui.lp(top = 12))
        addView(ui.row {
            addView(ui.pill("Cancel", color(R.color.surface), color(R.color.ink)) { WifiSend.cancel() }, ui.lp(w = 0, weight = 1f))
            addView(ui.pill("Continue", color(R.color.fab), color(R.color.on_fab), R.drawable.ic_check) { submitPin() },
                ui.lp(w = 0, weight = 1f, start = 10))
        }, ui.lp(top = 12))
    }

    private fun doneCard() = ui.card(color(R.color.green), 20) {
        doneCard = this
        addView(ui.row {
            doneTitle = ui.text(18f, color(R.color.ink), ui.medium) { setLineSpacing(ui.dp(2).toFloat(), 1f) }
            addView(doneTitle, ui.lp(w = 0, weight = 1f))
            addView(ui.pill("OK", color(R.color.surface), color(R.color.ink)) { WifiSend.dismissDone() }.apply {
                textSize = 14f; minHeight = ui.dp(34)
            }, ui.lp(w = WRAP_CONTENT, start = 10))
        })
        doneText = ui.text(14f, color(R.color.ink)) { setLineSpacing(ui.dp(2).toFloat(), 1f) }
        addView(doneText, ui.lp(top = 8))
    }

    private fun queueCard() = ui.card(color(R.color.surface), 20) {
        addView(ui.row {
            addView(ui.text(17f, color(R.color.ink), ui.medium) { text = "To send" }, ui.lp(w = WRAP_CONTENT))
            queueCount = ui.text(14f, color(R.color.ink_soft)) { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            addView(queueCount, ui.lp(w = 0, weight = 1f, start = 10))
            clearPill = ui.pill("Clear", color(R.color.bg), color(R.color.ink)) { WifiSend.clearQueue() }.apply {
                textSize = 14f; minHeight = ui.dp(34)
            }
            addView(clearPill, ui.lp(w = WRAP_CONTENT))
        })
        queueEmpty = ui.text(14f, color(R.color.ink_soft)) {
            setLineSpacing(ui.dp(2).toFloat(), 1f)
            text = "Pick files or photos here, or use Share › Droid Desk in Gallery, Files or another app."
        }
        addView(queueEmpty, ui.lp(top = 12))
        queueList = ui.column()
        addView(queueList, ui.lp(top = 4))
        queueButtons = ui.row {
            addView(ui.pill("Files", color(R.color.bg), color(R.color.ink), R.drawable.ic_file) { pick(ShareActivity.ACTION_PICK_FILES) },
                ui.lp(w = 0, weight = 1f))
            addView(ui.pill("Photos", color(R.color.bg), color(R.color.ink), R.drawable.ic_image) { pick(ShareActivity.ACTION_PICK_PHOTOS) },
                ui.lp(w = 0, weight = 1f, start = 10))
        }
        addView(queueButtons, ui.lp(top = 14))
    }

    private fun deviceCard() = ui.card(color(R.color.surface), 20) {
        addView(ui.row {
            addView(ui.text(17f, color(R.color.ink), ui.medium) { text = "Send to" }, ui.lp(w = 0, weight = 1f))
            refreshPill = ui.pill("Refresh", color(R.color.bg), color(R.color.ink), R.drawable.ic_refresh).apply {
                textSize = 14f; minHeight = ui.dp(34)
            }
            addView(refreshPill, ui.lp(w = WRAP_CONTENT))
        })
        deviceEmpty = ui.text(14f, color(R.color.ink_soft)) { setLineSpacing(ui.dp(2).toFloat(), 1f) }
        addView(deviceEmpty, ui.lp(top = 12))
        deviceList = ui.column()
        addView(deviceList, ui.lp(top = 4))
        addressPill = ui.pill("Enter address", color(R.color.bg), color(R.color.ink), R.drawable.ic_globe) {
            addressRow.visibility = View.VISIBLE
            addressPill.visibility = View.GONE
            addressInput.requestFocus()
            host.getSystemService(InputMethodManager::class.java)?.showSoftInput(addressInput, 0)
        }
        addView(addressPill, ui.lp(top = 14))
        addressRow = ui.row {
            visibility = View.GONE
            addressInput = input("192.168.1.20", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) { addAddress() }
            addView(addressInput, ui.lp(w = 0, weight = 1f))
            addView(ui.pill("Add", color(R.color.fab), color(R.color.on_fab)) { addAddress() }, ui.lp(w = WRAP_CONTENT, start = 8))
        }
        addView(addressRow, ui.lp(top = 14))
    }

    private fun input(hintText: String, type: Int, onGo: () -> Unit) = EditText(host).apply {
        hint = hintText
        textSize = 16f
        setTextColor(color(R.color.ink))
        setHintTextColor(color(R.color.ink_faint))
        isSingleLine = true
        inputType = type
        imeOptions = EditorInfo.IME_ACTION_GO
        background = ui.rounded(color(R.color.bg), 18)
        setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
        setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_GO) { onGo(); true } else false }
    }

    // ---------- actions ----------

    private fun pick(action: String) {
        if (WifiSend.active) return
        runCatching { host.startActivity(Intent(host, ShareActivity::class.java).setAction(action)) }
    }

    private fun hideKeyboard(v: View) {
        (host.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    private fun addAddress() {
        val typed = addressInput.text.toString().trim()
        if (typed.isEmpty()) return
        hideKeyboard(addressInput)
        WifiSend.addAddress(host, typed)
    }

    private fun submitPin() {
        val pin = pinInput.text.toString().trim()
        if (pin.isEmpty()) return
        hideKeyboard(pinInput)
        pinInput.setText("")
        WifiSend.submitPin(pin)
    }

    private fun send() {
        if (WifiSend.active || WifiSend.queue.isEmpty() || WifiSend.selectedDevice() == null) return
        // Notifications show the progress and Cancel while the app is in the background: ask once.
        if (Build.VERSION.SDK_INT >= 33 && !askedNotifications &&
            host.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askedNotifications = true
            sendAfterPermission = true
            host.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4)
            return
        }
        SendService.start(host)
    }

    // ---------- state ----------

    private fun refresh() {
        val active = WifiSend.active
        val p = WifiSend.progress
        val dev = WifiSend.selectedDevice()

        // Progress
        val showProg = p != null && p.stage != WifiSend.Stage.PIN
        progCard.visibility = if (showProg) View.VISIBLE else View.GONE
        if (p != null && showProg) {
            val items = if (p.fileCount == 1) "1 item" else "${p.fileCount} items"
            when (p.stage) {
                WifiSend.Stage.PREPARING -> {
                    progTitle.text = "Getting ready to send to ${p.to}"
                    progBig.text = "…"
                    progMeter.fraction = 0f
                    progText.text = "Reading $items"
                }
                WifiSend.Stage.WAITING -> {
                    progTitle.text = "Waiting for ${p.to}"
                    progBig.text = "…"
                    progMeter.fraction = 0f
                    progText.text = "Accept the transfer on ${p.to} · $items · ${fmtSize(p.total)}"
                }
                else -> {
                    progTitle.text = "Sending to ${p.to}"
                    val pct = if (p.total > 0) (p.sent * 100 / p.total).toInt().coerceIn(0, 100) else 100
                    progBig.text = ui.withUnit("$pct", "%")
                    progMeter.fraction = if (p.total > 0) (p.sent.toFloat() / p.total).coerceIn(0f, 1f) else 1f
                    progText.text = listOfNotNull(
                        "${p.filesDone} of ${p.fileCount} sent",
                        "${fmtSize(p.sent)} of ${fmtSize(p.total)}",
                        if (p.bytesPerSec > 0) "${fmtSize(p.bytesPerSec)}/s" else null,
                    ).joinToString(" · ")
                }
            }
        }

        // PIN
        val needPin = p?.stage == WifiSend.Stage.PIN
        if (needPin && pinCard.visibility != View.VISIBLE) host.scrollToTop()
        pinCard.visibility = if (needPin) View.VISIBLE else View.GONE
        if (p != null && needPin) {
            pinTitle.text = if (p.pinWrong) "That PIN wasn't right. Type ${p.to}'s PIN again." else "${p.to} asks for its PIN"
        }

        // Summary of the last transfer
        val d = WifiSend.done
        doneCard.visibility = if (d != null && !active) View.VISIBLE else View.GONE
        if (d != null) {
            val clean = d.problem == null && d.failed.isEmpty()
            doneCard.background = ui.rounded(color(if (clean) R.color.green else R.color.peach), 26)
            val files = if (d.sent == 1) "1 item" else "${d.sent} items"
            doneTitle.text = when {
                d.cancelled -> "Transfer cancelled"
                clean -> "Sent $files to ${d.to}"
                d.sent > 0 -> "Sent $files to ${d.to}; ${d.count - d.sent} not sent"
                else -> "Nothing was sent to ${d.to}"
            }
            val lines = ArrayList<String>()
            d.problem?.let { lines += it }
            if (d.cancelled && d.sent > 0) lines += "$files arrived before you cancelled."
            if (clean) lines += fmtSize(d.bytes)
            d.failed.take(5).forEach { lines += "${it.first}: ${it.second}" }
            if (d.failed.size > 5) lines += "+ ${d.failed.size - 5} more not sent"
            if (!clean && d.count - d.sent > 0 && WifiSend.queue.isNotEmpty()) lines += "What wasn't sent is still in the list below, so you can try again."
            doneText.text = lines.joinToString("\n")
        }

        // Queue
        val q = WifiSend.queue
        val known = q.filter { it.size >= 0 }.sumOf { it.size }
        queueCount.text = when {
            WifiSend.adding -> "Adding…"
            q.isEmpty() -> ""
            else -> "${q.size} · ${fmtSize(known)}"
        }
        val stamp = q.joinToString(",") { "${it.id}:${it.state.ordinal}" } + "|$active"
        if (stamp != queueShown) {
            queueShown = stamp
            queueList.removeAllViews()
            queueSubs.clear()
            for (item in q.take(60)) queueList.addView(queueRow(item, active), ui.lp(top = 8))
            if (q.size > 60) queueList.addView(ui.text(14f, color(R.color.ink_soft)) { text = "+ ${q.size - 60} more" },
                ui.lp(top = 10, start = 56))
        }
        for ((item, sub) in queueSubs) sub.text = describe(item)
        queueEmpty.visibility = if (q.isEmpty()) View.VISIBLE else View.GONE
        clearPill.visibility = if (q.isEmpty() || active) View.GONE else View.VISIBLE
        queueButtons.visibility = if (active) View.GONE else View.VISIBLE

        // Devices
        val scanning = WifiSend.scanning
        val rescan: (() -> Unit)? = if (scanning) null else ({ WifiSend.scan(host, sweep = true) })
        ui.stylePill(refreshPill, if (scanning) "Looking…" else "Refresh", color(R.color.bg),
            color(if (scanning) R.color.ink_soft else R.color.ink), R.drawable.ic_refresh,
            rescan)
        val devs = WifiSend.devices
        val dStamp = devs.joinToString(",") { "${it.key}:${it.ip}:${it.port}:${it.alias}" } + "|${WifiSend.selected}|$active"
        if (dStamp != devicesShown) {
            devicesShown = dStamp
            deviceList.removeAllViews()
            for (x in devs) deviceList.addView(deviceRow(x, x.key == WifiSend.selected, active), ui.lp(top = 8))
        }
        deviceEmpty.visibility = if (devs.isEmpty()) View.VISIBLE else View.GONE
        deviceEmpty.text = when {
            scanning -> "Looking for devices on this Wi‑Fi…"
            WifiServer.lanAddress() == null -> "Connect to the same Wi‑Fi as the other device."
            else -> "No devices found. On your computer, open Droid Desk › Wi‑Fi transfer and turn it on (same Wi‑Fi as " +
                "this phone), then tap Refresh. If it still doesn't show up, enter its address."
        }
        if (WifiSend.probing) ui.setLabel(addressPill, "Checking…") else ui.setLabel(addressPill, "Enter address")
        if (active) { addressRow.visibility = View.GONE; addressPill.visibility = View.GONE }
        else if (addressRow.visibility != View.VISIBLE) addressPill.visibility = View.VISIBLE

        // Send button
        val count = if (q.size == 1) "1 item" else "${q.size} items"
        ui.setLabel(sendButton, when {
            active -> "Sending…"
            q.isEmpty() -> "Send"
            dev == null -> "Send $count"
            else -> "Send $count to ${dev.alias.take(24)}"
        })
        ui.setEnabled(sendButton, !active && q.isNotEmpty() && dev != null && !WifiSend.adding)

        val note = WifiSend.message ?: when {
            active -> null
            dev != null && !dev.https -> "${dev.alias} has encryption turned off: files sent to it can be read by others on this Wi‑Fi."
            q.isNotEmpty() && dev == null && devs.isNotEmpty() -> "Pick the device to send to."
            else -> null
        }
        messageText.text = note.orEmpty()
        messageText.visibility = if (note.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun describe(item: WifiSend.Item): String = when (item.state) {
        WifiSend.ItemState.QUEUED -> if (item.size >= 0) fmtSize(item.size) else "Size not known yet"
        WifiSend.ItemState.SENDING -> "Sending · ${fmtSize(item.sent)} of ${fmtSize(item.size.coerceAtLeast(0))}"
        WifiSend.ItemState.DONE -> "Sent"
        WifiSend.ItemState.FAILED -> item.error ?: "Not sent"
        WifiSend.ItemState.SKIPPED -> "Not taken by the other device"
    }

    private fun queueRow(item: WifiSend.Item, active: Boolean) = ui.row {
        setPadding(ui.dp(4), ui.dp(4), 0, ui.dp(4))
        val icon = when {
            item.text != null -> R.drawable.ic_sms
            item.mime.startsWith("image/") || item.mime.startsWith("video/") -> R.drawable.ic_image
            item.mime.startsWith("audio/") -> R.drawable.ic_music
            item.mime == "application/vnd.android.package-archive" -> R.drawable.ic_apk
            else -> R.drawable.ic_file
        }
        addView(ui.roundIcon(44, color(R.color.bg), icon, color(R.color.ink), 20))
        addView(ui.column {
            addView(ui.text(15f, color(R.color.ink)) {
                text = if (item.text != null) String(item.text, Charsets.UTF_8).take(80).replace('\n', ' ') else item.name
                maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            val sub = ui.text(13f, color(if (item.state == WifiSend.ItemState.FAILED) R.color.danger else R.color.ink_soft)) {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            }
            queueSubs += item to sub
            addView(sub, ui.lp(top = 4))
        }, ui.lp(w = 0, weight = 1f, start = 12))
        if (!active) addView(ui.circleButton(R.drawable.ic_trash, "Remove ${item.name}", 38, color(R.color.bg)) { WifiSend.remove(item) },
            ui.lp(w = ui.dp(38), h = ui.dp(38), start = 8))
    }

    private fun deviceRow(d: WifiSend.Device, chosen: Boolean, active: Boolean) = ui.row {
        background = ui.pressable(ui.rounded(color(if (chosen) R.color.good_bg else R.color.surface), 22))
        setPadding(ui.dp(4), ui.dp(8), ui.dp(10), ui.dp(8))
        val icon = when (d.type.lowercase()) {
            "mobile" -> R.drawable.ic_cell
            "web" -> R.drawable.ic_globe
            else -> R.drawable.ic_desktop
        }
        addView(ui.roundIcon(44, color(if (chosen) R.color.surface else R.color.bg), icon, color(R.color.ink), 20))
        addView(ui.column {
            addView(ui.text(15f, color(R.color.ink), ui.medium) {
                text = d.alias; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            addView(ui.text(13f, color(R.color.ink_soft)) {
                text = listOfNotNull(
                    d.model.ifEmpty { null },
                    when (d.type.lowercase()) { "mobile" -> "Phone"; "desktop" -> "Computer"; "web" -> "Browser"; "server" -> "Server"; "headless" -> "Server"; else -> null },
                    if (d.port == WifiServer.PORT) d.ip else "${d.ip}:${d.port}",
                    if (d.pinned) "ID ${d.fingerprint.take(8).uppercase()}" else if (!d.https) "not encrypted" else null,
                ).joinToString(" · ")
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            }, ui.lp(top = 4))
        }, ui.lp(w = 0, weight = 1f, start = 12))
        if (chosen) addView(ui.iconView(R.drawable.ic_check_circle, color(R.color.good)), ui.lp(w = ui.dp(22), h = ui.dp(22), start = 8))
        contentDescription = "Send to ${d.alias}"
        if (!active) setOnClickListener { WifiSend.select(d.key) }
    }
}
