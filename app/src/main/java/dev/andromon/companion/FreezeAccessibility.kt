// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors
//
// Ported from SuperFreezZ (https://gitlab.com/SuperFreezZ/SuperFreezZ, commit 267d0f7):
// backend/FreezerService.kt (the accessibility service) and
// userInterface/FreezeShortcutActivity.kt (the invisible activity that walks through the apps).
// Original notice:
//
//   Copyright (c) 2018 Hocuri
//
//   This file is part of SuperFreezZ.
//
//   SuperFreezZ is free software: you can redistribute it and/or modify
//   it under the terms of the GNU General Public License as published by
//   the Free Software Foundation, either version 3 of the License, or
//   (at your option) any later version.
//
//   SuperFreezZ is distributed in the hope that it will be useful,
//   but WITHOUT ANY WARRANTY; without even the implied warranty of
//   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//   GNU General Public License for more details.
//
//   You should have received a copy of the GNU General Public License
//   along with SuperFreezZ.  If not, see <http://www.gnu.org/licenses/>.
//
// Andromon uses it under GPL version 3. Changed for Andromon:
//  - the service does nothing unless the person turned the method on in the Freezer (SuperFreezZ
//    switches the preference on by itself when the service connects);
//  - SuperFreezZ's last resort for finding the button (two unnamed buttons, pick the one whose
//    label has a space) is left out: a wrong guess could press "Uninstall";
//  - an app that couldn't be frozen is skipped instead of tried again, and three failures end the run;
//  - Android's newer App info screen (SpaActivity) is accepted next to InstalledAppDetailsTop;
//  - freezing when the screen turns off is not done through this service (it would have to wake
//    the screen and switch off the lock screen); that trigger needs Shizuku, the helper or root;
//  - with privileged access the activity force-stops directly and never opens App info.

package dev.andromon.companion

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * The Freezer's fallback for phones without Shizuku, the Andromon helper or root: it presses
 * "Force stop", "OK" and Back in Android's App info screen, which [FreezeActivity] opens for one
 * app after the other. It only sees windows of the Settings app (res/xml/freeze_accessibility.xml),
 * only reacts while a freeze run is in progress, and only when the person turned this method on.
 */
class FreezeAccessibilityService : AccessibilityService() {

    private val forceStopButtonName: String? by lazy {
        try {
            // Try to find out what it says on the Force Stop button (different in different languages)
            val resourcesPackageName = "com.android.settings"
            val resources = applicationContext.packageManager.getResourcesForApplication(resourcesPackageName)
            val resourceId = resources.getIdentifier("force_stop", "string", resourcesPackageName)
            if (resourceId > 0) {
                resources.getString(resourceId)
            } else {
                Log.e(TAG, "Label for the force stop button in settings could not be found")
                null
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Settings activity's resources not found")
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    private enum class NextAction {
        PRESS_FORCE_STOP, PRESS_OK, PRESS_BACK, DO_NOTHING
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Never act unless the person chose this method (Andromon).
        if (!Freezer.useAccessibility(this)) {
            if (nextAction != NextAction.DO_NOTHING) stopAnyCurrentFreezing()
            return
        }

        //We are only interested in WINDOW_STATE_CHANGED events
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }
        val className = event.className?.toString() ?: ""

        when (nextAction) {
            NextAction.DO_NOTHING -> { }

            NextAction.PRESS_FORCE_STOP -> {
                if (className == "com.android.settings.applications.InstalledAppDetailsTop" ||
                    className == "com.android.settings.spa.SpaActivity") {
                    pressForceStopButton(event)
                } else {
                    Log.w(TAG, "awaited InstalledAppDetailsTop to be the next screen but it was $className")
                    wrongScreenShown()
                }
            }

            NextAction.PRESS_OK -> {
                if (className.endsWith(".app.AlertDialog") || className.endsWith(".app.COUIAlertDialog")) {
                    pressOkButton(event)
                } else {
                    Log.w(TAG, "awaited AlertDialog to be the next screen but it was $className")
                    wrongScreenShown()
                }
            }

            NextAction.PRESS_BACK -> {
                pressBackButton()
            }
        }
    }

    private fun wrongScreenShown() {
        // If the last action was more than 8 seconds ago, something went wrong and we should stop not to destroy anything.
        if (System.currentTimeMillis() - lastActionTimestamp > 8000) {
            Log.e(TAG, "An unexpected screen turned up and the last action was more than 8 seconds ago. Aborted not to destroy anything")
            stopAnyCurrentFreezing() // Stop everything, it is to late to do anything :-(
        }
        // else do nothing and simply wait for the next screen to show up.
    }

    override fun onInterrupt() {
    }

    private fun pressForceStopButton(event: AccessibilityEvent) {
        val node = event.source ?: return // the 4-second timeout reports the failure

        var nodesToClick = node.findAccessibilityNodeInfosByText("FORCE STOP")

        if (nodesToClick.isNullOrEmpty())
            nodesToClick = node.findAccessibilityNodeInfosByViewId("com.android.settings:id/force_stop_button")

        if (nodesToClick.isNullOrEmpty())
            nodesToClick = forceStopButtonName?.let { node.findAccessibilityNodeInfosByText(it) }

        val success = clickAll(nodesToClick ?: emptyList(), "force stop")
        if (success) nextAction = NextAction.PRESS_OK

        @Suppress("DEPRECATION") node.recycle()
    }

    private fun pressOkButton(event: AccessibilityEvent) {
        val node = event.source ?: return

        var nodesToClick = node.findAccessibilityNodeInfosByText(getString(android.R.string.ok))

        if (nodesToClick.isNullOrEmpty())
            nodesToClick = forceStopButtonName?.let { node.findAccessibilityNodeInfosByText(it) }
            // Apparently necessary sometimes, see https://gitlab.com/SuperFreezZ/SuperFreezZ/issues/43

        val success = clickAll(nodesToClick ?: emptyList(), "OK")
        if (success) nextAction = NextAction.PRESS_BACK

        @Suppress("DEPRECATION") node.recycle()
    }

    private fun pressBackButton() {
        nextAction = NextAction.DO_NOTHING

        performGlobalAction(GLOBAL_ACTION_BACK)

        Log.i(TAG, "We arrived at pressBackButton(), this means that one app was successfully frozen.")
    }

    /**
     * Clicks all nodes. If it succeeds, it returns true. If it does not succeed, it handles this by
     * itself, sets the nextAction to what makes sense and returns false.
     */
    private fun clickAll(nodes: List<AccessibilityNodeInfo>, buttonName: String): Boolean {

        if (nodes.isEmpty()) {
            Log.e(TAG, "Could not find the $buttonName button.")
            stopAnyCurrentFreezing()
            doOnAppCouldNotBeFrozen?.invoke(this)
            return false
        } else if (nodes.size > 1) {
            Log.w(TAG, "Found more than one $buttonName button, clicking them all.")
        }

        val clickableNodes = nodes.filter { it.isClickable && it.isEnabled }

        if (clickableNodes.isEmpty()) {
            Log.e(TAG, "The button(s) is/are not clickable, aborting.")
            // Just do not press the button but immediately press Back and act as if the app was successfully frozen:
            // A disabled or not clickable button probably means that the app already is frozen.
            pressBackButton()
            return false
        }

        for (node in clickableNodes) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        notifyThereIsStillMovement(this)

        return true
    }

    override fun onServiceConnected() {
        isEnabled = true
    }

    override fun onDestroy() {
        Log.i(TAG, "FreezeAccessibilityService was destroyed.")
        isEnabled = false
        stopAnyCurrentFreezing()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AndromonFreezer"

        var doOnAppCouldNotBeFrozen: ((Context) -> Unit)? = null
        private var nextAction = NextAction.DO_NOTHING
        /**
         * The timestamp of the last action. This can be clicking a button or clickFreezeButtons() being called.
         */
        private var lastActionTimestamp = 0L
        /** True while Android has the service connected. */
        var isEnabled = false
            private set

        private val timeoutHandler = Handler(Looper.getMainLooper())

        /**
         * Clicks the "Force Stop", the "OK" and the "Back" button when the corresponding screen turns up.
         * Call this BEFORE starting the settings about the app you want to freeze!
         */
        fun clickFreezeButtons(context: Context) {
            if (nextAction == NextAction.DO_NOTHING) {
                nextAction = NextAction.PRESS_FORCE_STOP
                notifyThereIsStillMovement(context)
            } else {
                throw IllegalStateException("Attempted to freeze, but was still busy (nextAction was $nextAction)")
            }
        }

        fun finishedFreezing() {
            timeoutHandler.removeCallbacksAndMessages(null)
        }

        fun isBusy() = (nextAction != NextAction.DO_NOTHING)

        private fun notifyThereIsStillMovement(context: Context) {
            timeoutHandler.removeCallbacksAndMessages(null)

            // After 4 seconds, assume that something went wrong
            val app = context.applicationContext
            timeoutHandler.postDelayed({
                Log.w(TAG, "timeout")
                stopAnyCurrentFreezing()
                doOnAppCouldNotBeFrozen?.invoke(app)
            }, 4000)

            lastActionTimestamp = System.currentTimeMillis()
        }

        /**
         * Cleans up when no more apps shall be frozen or before onAppCouldNotBeFrozen() is called
         */
        fun stopAnyCurrentFreezing() {
            if (nextAction != NextAction.DO_NOTHING)
                Log.i(TAG, "Stopping current freeze process (stopAnyCurrentFreezing()), nextAction was $nextAction")
            nextAction = NextAction.DO_NOTHING
            timeoutHandler.removeCallbacksAndMessages(null)
        }
    }
}

/**
 * Performs "Freeze now": for the Freezer screen's buttons, the home-screen shortcut and the
 * quick-settings tile. It has no screen of its own (translucent) and is not exported.
 *
 * With privileged access it force-stops the apps directly and closes. Otherwise it stays in the
 * background and opens App info for one app after the other: [FreezeAccessibilityService] presses
 * the buttons when the person turned that method on; if not, the person presses them.
 */
class FreezeActivity : Activity() {

    private var appsToBeFrozenIter: ListIterator<String>? = null
    private var prepared = false
    private var resumed = false
    private var dialogShown = false
    private var frozen = 0
    private var current: String? = null
    private val failed = ArrayList<String>()
    var isWorking = false
        private set

    /** If the power button is pressed during freezing, freezing is aborted. */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (isWorking) {
                FreezeAccessibilityService.stopAnyCurrentFreezing()
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activity = this
        // Might be that there still was a previous (failed) freeze process, in this case stop it
        FreezeAccessibilityService.stopAnyCurrentFreezing()
        isWorking = true
        FreezeAccessibilityService.doOnAppCouldNotBeFrozen = ::onAppCouldNotBeFrozen
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOff, filter)
        performFreeze()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Brought back to the front after an app couldn't be frozen: onResume carries on with the next one.
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(screenOff) }
        // If activity != this, another instance might already have been started and we must not
        // clean the "static" things up here!
        if (activity == this) {
            activity = null
            FreezeAccessibilityService.doOnAppCouldNotBeFrozen = null
            FreezeAccessibilityService.finishedFreezing()
        }
        isWorking = false
    }

    private fun performFreeze() {
        val single = intent?.getStringExtra(EXTRA_PKG)
        thread(isDaemon = true) {
            val pending = runCatching { if (single != null) listOf(single) else Freezer.pendingPackages(applicationContext) }
                .getOrDefault(emptyList())
            // Shizuku, the helper or root: stop them directly, no screens.
            val direct = if (pending.isEmpty()) null else runCatching { Freezer.freeze(applicationContext, pending) }.getOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when {
                    pending.isEmpty() -> { toast("Nothing to freeze"); finish() }
                    direct != null && !direct.unavailable -> {
                        toast(summary(direct.frozen, direct.failed.size))
                        finish()
                    }
                    else -> {
                        // The actual freezing work will be done in onResume(). Here we just create this iterator.
                        if (FreezeAccessibilityService.isEnabled && Freezer.useAccessibility(this))
                            toast("Freezing… press the power button to stop")
                        appsToBeFrozenIter = pending.listIterator()
                        prepared = true
                        if (resumed) doNextFreezingStep()
                    }
                }
            }
        }
    }

    private fun summary(done: Int, failures: Int) =
        (if (done == 1) "Froze 1 app" else "Froze $done apps") + (if (failures > 0) " · $failures couldn't be stopped" else "")

    override fun onResume() {
        super.onResume()
        resumed = true
        if (prepared) doNextFreezingStep()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    private fun doNextFreezingStep() {
        if (dialogShown) return
        val auto = Freezer.useAccessibility(this)
        if (!(auto && FreezeAccessibilityService.isEnabled)) {
            // Sometimes the accessibility service is disabled for some reason.
            // In this case, tell the user to re-enable it:
            if (auto) {
                promptForAccessibility()
                return
            }

            if (Freezer.showManualHint(this)) {
                dialogShown = true
                AlertDialog.Builder(this)
                    .setTitle("Freeze by hand")
                    .setMessage("No automatic method is on, so Android's App info opens for each app. " +
                        "Press “Force stop”, “OK”, and then Back. Press Home to stop.")
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        Freezer.setShowManualHint(this, false)
                        dialogShown = false
                        doNextFreezingStep()
                    }
                    .setNegativeButton("Cancel") { _, _ -> finish() }
                    .show()
                return
            }
        }

        val iter = appsToBeFrozenIter ?: return
        current?.let { if (it !in failed) frozen++ } // we came back from its App info screen
        current = null
        if (iter.hasNext()) {
            val next = iter.next()
            current = next
            val settingsScreenLaunched = freezeApp(next, this)
            if (!settingsScreenLaunched) { failed += next; doNextFreezingStep() } // just go on
        } else {
            if (auto) toast(summary(frozen, failed.size))
            finish()
        }
    }

    private fun promptForAccessibility() {
        dialogShown = true
        AlertDialog.Builder(this)
            .setTitle("Freeze service is off")
            .setMessage("Freezing with the accessibility method needs “Andromon: freeze apps” switched on in " +
                "Android's Accessibility settings. On Android 13 and later you may first have to allow restricted " +
                "settings for this app (App info › ⋮).")
            .setCancelable(false)
            .setPositiveButton("Open settings") { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                finish()
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .show()
    }

    /** Called after one app could not be frozen: skip it, and give up after three. */
    private fun onAppCouldNotBeFrozen(context: Context) {
        Log.w("AndromonFreezer", "An app could not be frozen")
        current?.let { failed += it }
        if (failed.size >= 3) {
            toast("Stopped: Android's App info screen didn't look as expected. ${summary(frozen, failed.size)}")
            finish()
            return
        }
        // Come back in front of the App info screen that is still open; onResume goes on.
        context.startActivity(Intent(context, FreezeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()

    companion object {
        /** Intent extra: freeze only this package (the per-app "Freeze now"). */
        const val EXTRA_PKG = "pkg"

        var activity: FreezeActivity? = null
            private set

        fun intent(ctx: Context, pkg: String? = null): Intent =
            Intent(ctx, FreezeActivity::class.java).setAction(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .apply { if (pkg != null) putExtra(EXTRA_PKG, pkg) }

        /**
         * Freeze a package through App info.
         * @return true if the settings screen has been launched and you have to wait with freezing the next app.
         */
        fun freezeApp(packageName: String, context: Context): Boolean {
            if (FreezeAccessibilityService.isEnabled && Freezer.useAccessibility(context)) {
                // clickFreezeButtons will wait for the Force stop button to appear and then click Force stop, Ok, Back.
                try {
                    FreezeAccessibilityService.clickFreezeButtons(context)
                } catch (e: IllegalStateException) {
                    Log.e("AndromonFreezer", e.toString())
                    return false
                }
            }

            val intent = Intent()
            intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            intent.data = Uri.fromParts("package", packageName, null)
            return try {
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                FreezeAccessibilityService.stopAnyCurrentFreezing()
                false
            }
        }
    }
}
