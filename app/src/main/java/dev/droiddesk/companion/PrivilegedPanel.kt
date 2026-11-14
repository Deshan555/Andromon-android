// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

package dev.droiddesk.companion

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import kotlin.concurrent.thread

/**
 * Settings › Privileged access: which backend ([Privileged]) is in use and why, Shizuku's state
 * with its buttons, which backend to prefer, and "Finish setup without a computer".
 */
internal class PrivilegedPanel(private val host: MainActivity) {

    private val ui get() = host.ui
    private val rows by lazy { UiRows(host, ui) }

    private lateinit var inUse: UiRows.Row
    private lateinit var shizuku: UiRows.Row
    private lateinit var grant: UiRows.Row
    private lateinit var open: UiRows.Row
    private lateinit var prefer: UiRows.Row
    private lateinit var finish: UiRows.Row
    private var status = ShizukuAccess.Status(ShizukuAccess.State.NOT_INSTALLED)
    private var active: Privileged.Active? = null
    private var shown = false
    private val onShizuku: () -> Unit = { refresh() }

    fun addTo(parent: LinearLayout) {
        parent.addView(ui.section("Privileged access"), ui.lp(top = 26))
        parent.addView(ui.glassCard(6) {
            inUse = rows.value(this, R.drawable.ic_key, "In use", "", null)
            shizuku = rows.value(this, R.drawable.ic_shield, "Shizuku", "", null)
            grant = rows.value(this, R.drawable.ic_check_circle, "Grant permission", "Shizuku asks whether this app may use it") { requestPermission() }
            open = rows.value(this, R.drawable.ic_arrow_up_right, "Open Shizuku", "") { openShizuku() }
            rows.value(this, R.drawable.ic_info, "How to start Shizuku", "Wireless debugging on the phone, or from a computer") { howTo() }
            prefer = rows.value(this, R.drawable.ic_swap, "Prefer", "Which one to use when more than one is available") {
                rows.pick("Prefer", Privileged.Prefer.values().toList(), Privileged.prefer(host), { it.label }) {
                    Privileged.setPrefer(host, it); refresh()
                }
            }
            finish = rows.value(this, R.drawable.ic_check, "Finish setup without a computer", "") { finishSetup() }
            rows.note(this, "Stopping and freezing apps, the RAM manager, per-app battery use, Deep sleep and app limits need the " +
                "rights of adb (or root). Shizuku lends them on the phone itself; Droid Desk on your computer can start a helper " +
                "for the same purpose (Overview › Phone RAM manager). Either way only fixed, built-in commands are run, and only " +
                "by this app.")
        }, ui.lp(top = 12))
    }

    fun onShow() {
        shown = true
        ShizukuAccess.watch(onShizuku)
        refresh()
    }

    fun onHide() {
        shown = false
        ShizukuAccess.unwatch(onShizuku)
    }

    private fun refresh() {
        if (!shown) return
        thread(isDaemon = true) {
            val st = ShizukuAccess.status(host)
            val helper = ShellHelperClient.ping(host)
            val how = Privileged.active(host)
            host.runOnUiThread { if (!host.isDestroyed && shown) show(st, helper != null, how) }
        }
    }

    private fun show(st: ShizukuAccess.Status, helper: Boolean, how: Privileged.Active?) {
        status = st
        active = how
        inUse.value?.text = how?.backend?.label ?: "None"
        inUse.value?.setTextColor(ui.color(if (how != null) R.color.good else R.color.danger))
        inUse.sub.text = Privileged.reason(host, how, st, helper) +
            (how?.let { " Commands run ${it.how()}." } ?: " ${Privileged.HOW_TO.replace("Settings › Privileged access", "below")}")

        shizuku.value?.text = when (st.state) {
            ShizukuAccess.State.NOT_INSTALLED -> "Not installed"
            ShizukuAccess.State.NOT_RUNNING -> "Not running"
            ShizukuAccess.State.TOO_OLD -> "Too old"
            ShizukuAccess.State.NEEDS_PERMISSION -> "Needs permission"
            ShizukuAccess.State.DENIED -> "Denied"
            ShizukuAccess.State.READY -> "Ready"
        }
        shizuku.value?.setTextColor(ui.color(if (st.ready) R.color.good else R.color.ink_soft))
        shizuku.sub.text = when (st.state) {
            ShizukuAccess.State.NOT_INSTALLED -> "A free app that lends other apps the rights of adb, without a computer on Android 11 and later."
            ShizukuAccess.State.NOT_RUNNING -> st.line() + ". It has to be started again after every restart of the phone (unless the phone is rooted)."
            ShizukuAccess.State.DENIED -> st.line() + ". You chose “Deny and don't ask again”: open Shizuku and allow Droid Desk Companion under its authorized apps."
            ShizukuAccess.State.TOO_OLD -> st.line() + ". Update the Shizuku app."
            else -> st.line()
        }
        grant.view.visibility = if (st.state == ShizukuAccess.State.NEEDS_PERMISSION) View.VISIBLE else View.GONE
        val installed = st.state != ShizukuAccess.State.NOT_INSTALLED
        open.title.text = if (installed) "Open Shizuku" else "Get Shizuku"
        open.sub.text = if (installed) "Start it, pair it, or manage which apps may use it" else "Opens shizuku.rikka.app/download in your browser"
        prefer.value?.text = when (Privileged.prefer(host)) {
            Privileged.Prefer.AUTO -> "Automatic"
            Privileged.Prefer.SHIZUKU -> "Shizuku"
            Privileged.Prefer.HELPER -> "Helper"
        }
        finish.available(how != null, ui,
            "Needs Shizuku ready (or the helper running). Gives this app the accesses Droid Desk normally grants over USB.",
            "Gives this app the accesses Droid Desk normally grants over USB. You choose which.")
    }

    private fun requestPermission() {
        ShizukuAccess.requestPermission(host) { ok ->
            if (!ok && !host.isDestroyed) rows.message("Shizuku", "Permission wasn't given. You can allow Droid Desk Companion later in the Shizuku app.")
            refresh()
        }
    }

    private fun openShizuku() {
        val launch = host.packageManager.getLaunchIntentForPackage(PrivilegedCommands.SHIZUKU_PKG)
        runCatching {
            host.startActivity(launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
        }.onFailure { rows.message("Shizuku", "Couldn't open it: ${it.message}") }
    }

    private fun howTo() {
        val wireless = if (Build.VERSION.SDK_INT >= 30)
            "On this phone (Android 11 or later), without a computer:\n" +
                "1. Turn on Developer options (Settings › About phone › tap “Build number” seven times) and connect to Wi‑Fi.\n" +
                "2. In Shizuku, tap “Pairing”. It shows a notification that waits for a code.\n" +
                "3. In Developer options › Wireless debugging, turn it on and tap “Pair device with pairing code”.\n" +
                "4. Type that code into Shizuku's notification.\n" +
                "5. Back in Shizuku, tap “Start”. Pairing is needed only once; starting is needed after every restart.\n" +
                "If starting fails, turn Wireless debugging off and on again.\n\n"
        else "This phone runs Android ${Build.VERSION.RELEASE}: starting Shizuku on the phone itself needs Android 11 or later. Use a computer:\n\n"
        AlertDialog.Builder(host)
            .setTitle("How to start Shizuku")
            .setMessage(wireless +
                "From a computer:\n" +
                "Connect the phone to Droid Desk and click “Start Shizuku” on its Overview page. Or, with adb installed, run:\n" +
                "adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh\n\n" +
                "Rooted phones: tap “Start” in Shizuku; it then also starts by itself after a restart.\n\n" +
                "After starting, come back here and tap “Grant permission”.")
            .setPositiveButton("Close", null)
            .setNeutralButton("Developer options") { _, _ ->
                runCatching { host.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            }
            .show()
    }

    // ---------- Finish setup without a computer ----------

    private fun finishSetup() {
        val grants = Privileged.grants(host)
        val state = grants.map { it.granted(host) }
        val picked = BooleanArray(grants.size) { state[it] != true }
        val labels = grants.mapIndexed { i, g ->
            g.title + " · " + when (state[i]) { true -> "already allowed"; false -> "off"; null -> "state not readable" }
        }.toTypedArray()
        AlertDialog.Builder(host)
            .setTitle("Finish setup without a computer")
            .setMultiChoiceItems(labels, picked) { _, which, on -> picked[which] = on }
            .setPositiveButton("Allow the ticked ones") { _, _ ->
                val results = arrayOfNulls<String>(grants.size)
                val progress = AlertDialog.Builder(host).setTitle("Finish setup").setMessage("Working…").setCancelable(false).show()
                thread(isDaemon = true) {
                    for ((i, g) in grants.withIndex()) {
                        results[i] = if (!picked[i]) "Skipped"
                        else runCatching { g.run(host) }.fold({ err -> if (err == null) "Allowed" else "Failed: $err" }, { "Failed: ${it.message}" })
                    }
                    host.runOnUiThread {
                        runCatching { progress.dismiss() }
                        if (host.isDestroyed) return@runOnUiThread
                        rows.message("Finish setup", grants.mapIndexed { i, g -> "${g.title}: ${results[i]}\n${g.why}" }.joinToString("\n\n") +
                            "\n\nAndroid may refuse SMS and call log for apps that weren't installed over adb; installing through " +
                            "Droid Desk avoids that. Changing file access can make Android restart this app.")
                        refresh()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
