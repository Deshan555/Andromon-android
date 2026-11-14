// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

package dev.droiddesk.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.TextView

/**
 * Battery level, condition and details as Android reports them. Battery Saver is a real switch
 * when the app may change it ([BatterySaver]); Dark theme shows the current setting and opens it.
 * The Saver and Freezer screens open from here.
 */
internal class BatteryScreen(host: MainActivity) : Screen(host) {

    override val name = "Battery"
    override val navIcon = R.drawable.ic_battery
    override val navColor = R.color.bg

    private lateinit var battery: BatteryView
    private lateinit var level: TextView
    private lateinit var status: TextView
    private lateinit var condIcon: ImageView
    private lateinit var condTitle: TextView
    private lateinit var condSub: TextView
    private lateinit var condCard: View
    private lateinit var details: LinearLayout
    private lateinit var saverSwitch: android.widget.Switch
    private lateinit var darkState: TextView
    private var receiver: BroadcastReceiver? = null
    private val history by lazy { BatteryHistoryPanel(host) }

    override fun build(): View = ui.column {
        addView(ui.topBar("Battery", host::goHome, R.drawable.ic_bolt, "Battery usage") {
            open(Intent.ACTION_POWER_USAGE_SUMMARY)
        }.first)


        addView(ui.heroCard(18) {
            addView(ui.row {
                addView(FrameLayout(context).apply {
                    addView(View(context).apply { background = ui.circle(color(R.color.glass_strong)) },
                        FrameLayout.LayoutParams(ui.dp(130), ui.dp(130), Gravity.CENTER))
                    battery = BatteryView(context).apply {
                        colors(color(R.color.surface), color(R.color.ink_soft), color(R.color.green_dot), color(R.color.good), Color.WHITE)
                    }
                    addView(battery, FrameLayout.LayoutParams(ui.dp(96), ui.dp(118), Gravity.CENTER))
                }, ui.lp(w = ui.dp(136), h = ui.dp(140)))
                addView(ui.column {
                    level = ui.text(52f, color(R.color.ink), ui.regular) { text = "–"; letterSpacing = -0.03f }
                    addView(level)
                    status = ui.text(14f, color(R.color.ink), ui.medium) { setLineSpacing(ui.dp(2).toFloat(), 1f) }
                    addView(status, ui.lp(top = 6))
                }, ui.lp(w = 0, weight = 1f, start = 12))
            })
        }, ui.lp(top = 24))

        condCard = ui.row {
            setPadding(ui.dp(14), ui.dp(14), ui.dp(16), ui.dp(14))
            condIcon = ui.badge(R.drawable.ic_leaf, color(R.color.good), Color.WHITE, 50)
            addView(condIcon)
            addView(ui.column {
                condTitle = ui.text(20f, color(R.color.ink), ui.regular)
                addView(condTitle)
                condSub = ui.text(13f, color(R.color.ink_soft))
                addView(condSub, ui.lp(top = 3))
            }, ui.lp(w = 0, weight = 1f, start = 14))
        }
        addView(condCard, ui.lp(top = 12))

        addView(ui.section("Details"), ui.lp(top = 26))
        details = ui.glassCard(16)
        addView(details, ui.lp(top = 12))

        addView(history.view, ui.lp(top = 26))

        addView(ui.section("Power saving"), ui.lp(top = 26))
        addView(ui.glassCard(6) {
            // A real switch (BatterySaver.kt): needs WRITE_SECURE_SETTINGS or privileged access;
            // without either, the Saver screen opens and says what is missing.
            val rows = UiRows(host, ui)
            saverSwitch = rows.switch(this, R.drawable.ic_leaf, "Battery Saver", "Limits background activity to save power") { on ->
                switchSaver(on)
            }.switch!!
            rows.value(this, R.drawable.ic_moon, "Battery saver tools", "Deep sleep, charging alerts, app limits, what is awake") {
                host.show("Saver")
            }
            rows.value(this, R.drawable.ic_snow, "Freezer", "Stop apps that run without being used") { host.show("Freezer") }
            darkState = settingRow(R.drawable.ic_moon, "Dark theme", "Saves power on OLED screens",
                Settings.ACTION_DISPLAY_SETTINGS)
            settingRow(R.drawable.ic_apps, "App battery use", "See which apps use the most",
                Intent.ACTION_POWER_USAGE_SUMMARY)
        }, ui.lp(top = 12))
    }

    private fun LinearLayout.settingRow(icon: Int, title: String, sub: String, action: String): TextView {
        val state = ui.text(13f, color(R.color.ink_soft), ui.medium)
        addView(ui.row {
            background = ui.pressable(ui.rounded(Color.TRANSPARENT, 20))
            setPadding(ui.dp(10), ui.dp(12), ui.dp(8), ui.dp(12))
            addView(ui.roundIcon(40, color(R.color.glass_strong), icon, color(R.color.ink), 19), ui.lp(w = ui.dp(40), h = ui.dp(40)))
            addView(ui.column {
                addView(ui.text(15f, color(R.color.ink), ui.medium) { text = title })
                addView(ui.text(12f, color(R.color.ink_soft)) { text = sub }, ui.lp(top = 3))
            }, ui.lp(w = 0, weight = 1f, start = 12))
            addView(state, ui.lp(w = WRAP_CONTENT, start = 8))
            addView(ui.iconView(R.drawable.ic_chevron_right, color(R.color.ink_faint)), ui.lp(w = ui.dp(20), h = ui.dp(20), start = 4))
            setOnClickListener { open(action) }
        })
        return state
    }

    override fun onShow() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                show(if (i.action == Intent.ACTION_BATTERY_CHANGED) i else null)
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply { addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) }
        val sticky = if (Build.VERSION.SDK_INT >= 33) host.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
                     else host.registerReceiver(r, filter)
        receiver = r
        show(sticky)
        history.onShow()
    }

    override fun onHide() {
        receiver?.let { runCatching { host.unregisterReceiver(it) } }
        receiver = null
        history.onHide()
    }

    private fun show(sticky: Intent?) {
        val b = Phone.battery(host, sticky)
        battery.level = b.level / 100f
        battery.charging = b.charging
        level.text = ui.percent(b.level)
        status.text = when {
            b.charging && b.level >= 100 -> "Fully charged" + (b.plugged?.let { " · $it" } ?: "")
            b.charging -> "Charging" + (b.plugged?.let { " via $it" } ?: "") +
                (b.fullInMs?.let { " · full in ${duration(it)}" } ?: "")
            else -> "On battery"
        }

        val good = b.healthy && !b.hot
        condCard.background = ui.rounded(color(if (good) R.color.glass_strong else R.color.peach), 24)
        condIcon.background = Scallop(color(if (good) R.color.good else R.color.peach_dot))
        condIcon.setImageDrawable(ui.icon(if (good) R.drawable.ic_leaf else R.drawable.ic_warning, Color.WHITE))
        condTitle.text = when {
            b.hot -> "Running hot"
            b.healthy -> "Good"
            else -> b.healthText
        }
        condSub.text = when {
            b.hot -> "Let the phone cool down; avoid charging in the sun or while gaming."
            b.health == android.os.BatteryManager.BATTERY_HEALTH_UNKNOWN -> "This phone doesn't report battery health."
            b.healthy -> "Battery is in good condition"
            else -> "Android reports a battery problem. Consider having it checked."
        }

        details.removeAllViews()
        val rows = listOfNotNull(
            b.tempC?.let { "Temperature" to "%.1f °C".format(it) },
            b.voltageMv?.let { "Voltage" to "%.2f V".format(it / 1000f) },
            "Power source" to (b.plugged ?: "Battery"),
            "Health" to b.healthText,
            b.technology?.let { "Technology" to it },
            b.cycles?.let { "Charge cycles" to "$it" },
        )
        rows.forEachIndexed { i, (k, v) ->
            val (row, value) = ui.keyValue(k)
            value.text = v
            details.addView(row, ui.lp(top = if (i == 0) 0 else 12))
        }

        saverSwitch.isChecked = b.powerSave
        val night = (host.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        darkState.text = if (night) "On" else "Off"
        darkState.setTextColor(color(if (night) R.color.good else R.color.ink_soft))
    }

    private fun switchSaver(on: Boolean) {
        background({ BatterySaver.setSaver(host, on); Thread.sleep(500) }) { r ->
            // Not allowed, or the phone didn't follow (charging, or a maker's own power saving mode):
            // the Saver screen explains what is needed.
            if (r.isFailure || BatterySaver.saverOn(host) != on) host.show("Saver") else show(null)
        }
    }

    private fun duration(ms: Long): String {
        val min = ms / DateUtils.MINUTE_IN_MILLIS
        return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
    }

    private fun open(action: String) {
        runCatching { host.startActivity(Intent(action)) }
            .onFailure { runCatching { host.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }
}
