// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Lets automation apps (Tasker, Automate, MacroDroid, a home-screen shortcut…) switch things in
 * this app: they start this activity with one of the actions below ("Send intent", target
 * Activity, package dev.andromon.companion). It has no screen of its own and closes at once.
 *
 * Because any app on the phone can start an exported activity, nothing happens unless the person
 * turned on Settings › Tools › Automation, and the actions are limited to what the app's own
 * quick-settings tiles already do: nothing is read, deleted or sent.
 */
class AutomationActivity : Activity() {

    companion object {
        private const val P = "dev.andromon.companion.action."
        const val LINK_ON = P + "LINK_ON"
        const val LINK_OFF = P + "LINK_OFF"
        const val RECEIVE_ON = P + "RECEIVE_ON"
        const val RECEIVE_OFF = P + "RECEIVE_OFF"
        const val FREEZE = P + "FREEZE"
        /** Action and what it does, for the list in Settings. */
        val ACTIONS = listOf(
            LINK_ON to "Turn the PC link on",
            LINK_OFF to "Turn the PC link off",
            RECEIVE_ON to "Turn Wi‑Fi receive on",
            RECEIVE_OFF to "Turn Wi‑Fi receive off",
            FREEZE to "Freeze the apps on your Freezer list",
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Prefs.automation(this)) {
            Toast.makeText(this, "Automation is off in Andromon Companion (Settings › Tools)", Toast.LENGTH_LONG).show()
        } else runCatching { run(intent?.action) }
        finish()
    }

    private fun run(action: String?) {
        when (action) {
            LINK_ON -> CompanionService.start(this, byUser = true)
            LINK_OFF -> CompanionService.stopByUser(this)
            RECEIVE_ON -> {
                // Same checks as Transfer › Receive and its tile: without them a transfer couldn't be accepted or saved.
                val missing = buildList {
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    if (Build.VERSION.SDK_INT < 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                if (missing) startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, "Receive"))
                else if (!WifiReceive.running && !WifiReceive.starting) ReceiveService.start(this)
            }
            RECEIVE_OFF -> if (WifiReceive.running || WifiReceive.starting) ReceiveService.stop(this)
            FREEZE -> startActivity(Intent(this, FreezeActivity::class.java))
        }
    }
}
