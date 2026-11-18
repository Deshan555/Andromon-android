// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.TextView

/** Connection details, a speed test, and ping / DNS lookup for any host. */
internal class NetworkScreen(host: MainActivity) : Screen(host) {

    override val name = "Network"
    override val navIcon = R.drawable.ic_wifi
    override val navColor = R.color.bg

    private val cm = host.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var metered = false
    private var testing = false

    private lateinit var dot: View
    private lateinit var dotText: TextView
    private lateinit var badge: ImageView
    private lateinit var gauge: Ring
    private lateinit var kind: TextView
    private lateinit var detail: TextView
    private lateinit var internetPill: TextView
    private lateinit var rows: LinearLayout

    private lateinit var speedValue: TextView
    private lateinit var speedPhase: TextView
    private lateinit var chart: LineChart
    private lateinit var pingStat: Ui.Stat
    private lateinit var downStat: Ui.Stat
    private lateinit var upStat: Ui.Stat
    private lateinit var speedButton: TextView
    private lateinit var speedNote: TextView

    private lateinit var hostInput: EditText
    private lateinit var pingButton: TextView
    private lateinit var lookupButton: TextView
    private lateinit var output: TextView

    override fun build(): View = ui.column {
        addView(ui.topBar("Network", host::goHome).first)
        addView(connectionCard(), ui.lp(top = 24))
        addView(ui.section("Speed test") {
            speedPhase = ui.text(13f, color(R.color.ink_soft)) { text = "Ready" }
            addView(speedPhase, ui.lp(w = WRAP_CONTENT))
        }, ui.lp(top = 26))
        addView(speedCard(), ui.lp(top = 12))
        addView(ui.section("Ping & DNS lookup"), ui.lp(top = 26))
        addView(hostCard(), ui.lp(top = 12))
    }

    override fun onShow() {
        refreshInfo()
        if (callback == null) {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: android.net.NetworkCapabilities) = post()
                override fun onLost(network: Network) = post()
                private fun post() { host.runOnUiThread { if (!host.isDestroyed) refreshInfo() } }
            }
            runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { callback = cb }
        }
    }

    override fun onHide() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }

    // ---------- connection ----------

    private fun connectionCard() = ui.heroCard(18) {
        val (labelRow, d, label) = ui.dotLabel()
        dot = d; dotText = label
        label.textSize = 13f
        badge = ui.badge(R.drawable.ic_wifi, color(R.color.cat_blue), android.graphics.Color.WHITE, 52)
        kind = ui.text(28f, color(R.color.ink), ui.light) { maxLines = 1; letterSpacing = -0.02f }
        addView(ui.row {
            addView(badge, ui.lp(w = ui.dp(50), h = ui.dp(50)))
            addView(ui.column {
                addView(kind)
                addView(labelRow, ui.lp(top = 6))
            }, ui.lp(w = 0, weight = 1f, start = 14))
        })
        detail = ui.text(15f, color(R.color.ink))
        addView(detail, ui.lp(top = 14))
        internetPill = ui.pill("", 0, 0).apply { textSize = 13f; minHeight = ui.dp(32); typeface = ui.medium }
        addView(internetPill, ui.lp(w = WRAP_CONTENT, top = 12))
        rows = ui.column {
            background = ui.rounded(color(R.color.glass_strong), 18)
            setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(16))
        }
        addView(rows, ui.lp(top = 16))
    }

    private fun refreshInfo() {
        val info = NetTools.info(host)
        metered = info.metered
        dot.background = ui.circle(color(if (info.connected) R.color.green_dot else R.color.peach_dot))
        dotText.text = if (info.connected) "Connected" else "Not connected"
        badge.setImageDrawable(ui.icon(info.icon, android.graphics.Color.WHITE))
        kind.text = info.kind
        detail.text = info.detail
        detail.visibility = if (info.detail.isEmpty()) View.GONE else View.VISIBLE
        when {
            !info.connected -> internetPill.visibility = View.GONE
            info.validated -> {
                internetPill.visibility = View.VISIBLE
                ui.stylePill(internetPill, "Internet works", color(R.color.good_bg), color(R.color.good), R.drawable.ic_check)
            }
            else -> {
                internetPill.visibility = View.VISIBLE
                ui.stylePill(internetPill, "No internet access", color(R.color.peach_tile), color(R.color.danger))
            }
        }
        rows.removeAllViews()
        for ((k, v) in info.rows) {
            val (row, value) = ui.keyValue(k)
            value.text = v
            rows.addView(row, ui.lp(top = 10))
        }
        rows.visibility = if (info.rows.isEmpty()) View.GONE else View.VISIBLE
        if (!testing) updateSpeedNote()
    }

    // ---------- speed test ----------

    private fun speedCard() = ui.glassCard(18) {
        addView(FrameLayout(context).apply {
            gauge = Ring(context).apply {
                strokeDp = 14f
                colors(color(R.color.glass_strong), color(R.color.ink), color(R.color.ink))
            }
            addView(gauge, FrameLayout.LayoutParams(ui.dp(180), ui.dp(180), Gravity.CENTER))
            addView(ui.column {
                gravity = Gravity.CENTER
                addView(ui.iconView(R.drawable.ic_speed, color(R.color.ink)), ui.lp(w = ui.dp(26), h = ui.dp(26)))
                speedValue = ui.text(38f, color(R.color.ink), ui.regular) { gravity = Gravity.CENTER; text = "–" }
                addView(speedValue, ui.lp(w = WRAP_CONTENT, top = 6))
                addView(ui.text(13f, color(R.color.ink_soft)) { text = "Mbps" }, ui.lp(w = WRAP_CONTENT, top = 2))
            }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }, ui.lp(top = 12))
        chart = LineChart(context).apply {
            floorZero = true; minSpan = 10f; slots = 40; fill = true
            visibility = View.GONE // shown once a test is running
            marker = color(R.color.marker)
            colors(color(R.color.chart), color(R.color.glass_strong), color(R.color.surface))
        }
        addView(chart, ui.lp(h = ui.dp(70), top = 12))
        addView(ui.row {
            pingStat = ui.stat(color(R.color.yellow_tile), R.drawable.ic_timer, "Ping", 18f)
            downStat = ui.stat(color(R.color.blue), R.drawable.ic_down, "Down", 18f)
            upStat = ui.stat(color(R.color.purple_strong), R.drawable.ic_up, "Up", 18f)
            listOf(pingStat, downStat, upStat).forEachIndexed { i, s ->
                // Inside a white card: flat tinted tiles instead of floating white ones.
                s.box.background = ui.rounded(color(R.color.glass_strong), 18)
                s.box.elevation = 0f
                s.box.setPadding(ui.dp(10), ui.dp(12), ui.dp(6), ui.dp(12))
                addView(s.box, ui.lp(w = 0, weight = 1f, start = if (i == 0) 0 else 8))
            }
        }, ui.lp(top = 14))
        speedButton = ui.action("Run speed test", R.drawable.ic_speed) { runSpeedTest() }
        addView(speedButton, ui.lp(top = 14))
        speedNote = ui.text(12f, color(R.color.ink_soft)) { gravity = Gravity.CENTER; setLineSpacing(ui.dp(2).toFloat(), 1f) }
        addView(speedNote, ui.lp(top = 10))
    }

    private fun updateSpeedNote() {
        speedNote.text = if (metered || Prefs.saveData(host))
            "Uses ${NetTools.SPEED_HOST} and up to about 35 MB of mobile data."
        else
            "Uses ${NetTools.SPEED_HOST} and up to about 125 MB of data."
    }

    private fun runSpeedTest() {
        if (testing) return
        testing = true
        ui.setEnabled(speedButton, false)
        ui.setLabel(speedButton, "Testing…")
        listOf(pingStat, downStat, upStat).forEach { it.value.text = "–" }
        gauge.fraction = 0f
        val samples = ArrayList<Float>()
        fun phase(name: String) = host.runOnUiThread {
            speedPhase.text = name; samples.clear(); chart.values = samples; chart.visibility = View.VISIBLE
        }
        fun live(mbps: Float) = host.runOnUiThread {
            samples += mbps; chart.values = samples
            showSpeed(mbps)
        }
        val mobile = metered || Prefs.saveData(host) // smaller test on mobile data, or when Save data is on
        background({
            phase("Measuring ping…")
            val ping = NetTools.latency()
            host.runOnUiThread { pingStat.value.text = ui.withUnit(ping?.toString() ?: "–", "ms") }
            phase("Downloading…")
            val down = NetTools.download(if (mobile) 25_000_000 else 100_000_000, 8000, ::live)
            host.runOnUiThread { downStat.value.text = ui.withUnit(fmtMbps(down), "Mbps") }
            phase("Uploading…")
            val up = NetTools.upload(if (mobile) 10_000_000 else 25_000_000, 8000, ::live)
            Triple(ping, down, up)
        }) { r ->
            testing = false
            ui.setEnabled(speedButton, true)
            ui.setLabel(speedButton, "Run again")
            r.onSuccess { (_, down, up) ->
                upStat.value.text = ui.withUnit(fmtMbps(up), "Mbps")
                showSpeed(down)
                speedPhase.text = "Download speed"
            }.onFailure {
                speedPhase.text = "Test failed"
                speedValue.text = "–"; gauge.fraction = 0f
                speedNote.text = "Couldn't reach ${NetTools.SPEED_HOST}: ${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    /** Gauge scale steps up with the speed so the ring stays readable on slow and fast links. */
    private fun showSpeed(mbps: Float) {
        speedValue.text = fmtMbps(mbps)
        val scale = listOf(10f, 50f, 100f, 250f, 500f, 1000f, 2500f).firstOrNull { mbps <= it } ?: mbps
        gauge.fraction = mbps / scale
    }

    private fun fmtMbps(v: Float) = if (v >= 100) "%.0f".format(v) else "%.1f".format(v)

    // ---------- ping & DNS ----------

    private fun hostCard() = ui.glassCard(18) {
        hostInput = EditText(context).apply {
            setText("google.com")
            hint = "Host name or IP address"
            textSize = 16f
            setTextColor(color(R.color.ink))
            setHintTextColor(color(R.color.ink_faint))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            background = ui.rounded(color(R.color.glass_strong), 18)
            setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_GO) { runPing(); true } else false }
        }
        addView(hostInput)
        addView(ui.row {
            pingButton = ui.pill("Ping", color(R.color.fab), color(R.color.on_fab), R.drawable.ic_timer) { runPing() }
            lookupButton = ui.pill("Look up", color(R.color.glass_strong), color(R.color.ink), R.drawable.ic_globe) { runLookup() }
            addView(pingButton, ui.lp(w = 0, weight = 1f))
            addView(lookupButton, ui.lp(w = 0, weight = 1f, start = 8))
        }, ui.lp(top = 10))
        output = ui.text(13f, color(R.color.ink)) {
            typeface = Typeface.MONOSPACE
            setLineSpacing(ui.dp(4).toFloat(), 1f)
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        addView(output, ui.lp(top = 14))
    }

    private fun targetHost(): String? {
        val h = hostInput.text.toString().trim().removePrefix("https://").removePrefix("http://").substringBefore('/')
        host.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(hostInput.windowToken, 0)
        if (!NetTools.validHost(h)) {
            output.visibility = View.VISIBLE
            output.text = "Enter a host name like example.com or an IP address."
            return null
        }
        return h
    }

    private fun busy(on: Boolean) {
        ui.setEnabled(pingButton, !on)
        ui.setEnabled(lookupButton, !on)
    }

    private fun runPing() {
        val h = targetHost() ?: return
        busy(true)
        output.visibility = View.VISIBLE
        output.text = ""
        background({
            NetTools.ping(h) { line -> host.runOnUiThread { output.append(if (output.text.isEmpty()) line else "\n$line") } }
        }) { r ->
            busy(false)
            r.exceptionOrNull()?.let { output.append("\nPing failed: ${it.message}") }
        }
    }

    private fun runLookup() {
        val h = targetHost() ?: return
        busy(true)
        output.visibility = View.VISIBLE
        output.text = "Looking up $h…"
        background({ NetTools.lookup(h) }) { r ->
            busy(false)
            output.text = r.fold({ it.joinToString("\n") }, { "No DNS answer for $h (${it.message ?: "not found"})" })
        }
    }
}
