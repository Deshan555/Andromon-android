// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/**
 * Quick-settings tiles: "PC link" ([LinkTile]), "Receive files" ([ReceiveTile]) and
 * "Send clipboard to PC" ([ClipTile]). The person adds them by editing the quick-settings panel.
 * Only the system can bind them (BIND_QUICK_SETTINGS_TILE in the manifest).
 */
abstract class DeskTile : TileService() {

    protected val main = Handler(Looper.getMainLooper())
    private val refresh = Runnable { update() }

    /** Sets the tile's state from what is really running. */
    protected abstract fun state(): Int
    protected open fun subtitle(state: Int): String? = when (state) {
        Tile.STATE_ACTIVE -> "On"
        Tile.STATE_INACTIVE -> "Off"
        else -> null
    }

    protected fun update() {
        val tile = qsTile ?: return
        val s = state()
        tile.state = s
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = subtitle(s)
        tile.updateTile()
    }

    /** Services take a moment to start or stop: look again shortly after a tap. */
    protected fun updateSoon() {
        update()
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, 500)
        main.postDelayed(refresh, 2000)
    }

    override fun onStartListening() { update() }
    override fun onStopListening() { main.removeCallbacks(refresh) }

    /** Closes the panel and opens one of our screens, the way each Android version wants it. */
    protected fun open(intent: Intent, requestCode: Int) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 14 no longer lets tiles start activities with a plain Intent.
                startActivityAndCollapse(PendingIntent.getActivity(
                    this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ))
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {
            Log.w("AndromonTile", "Couldn't open: ${e.javaClass.simpleName}")
        }
    }

    protected fun openApp(screen: String? = null, autostart: Boolean = false) {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (screen != null) i.putExtra(MainActivity.EXTRA_SCREEN, screen)
        if (autostart) i.putExtra("autostart", true)
        open(i, if (autostart) 51 else 52)
    }
}

/** "PC link": starts or stops [CompanionService], the link Andromon talks to. */
class LinkTile : DeskTile() {
    override fun state() = if (CompanionService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    override fun subtitle(state: Int) = when {
        state != Tile.STATE_ACTIVE -> "Off"
        CompanionService.locked -> "Locked"
        LinkEvents.listening -> "Connected"
        else -> "On"
    }

    override fun onClick() {
        if (CompanionService.running) {
            try {
                CompanionService.stopByUser(this)
            } catch (_: Exception) {
            }
        } else {
            try {
                CompanionService.start(this, byUser = true)
            } catch (_: Exception) {
                // Android 12+ may refuse a foreground service from the background: the app's
                // screen starts it instead and steps aside (as when Andromon starts it).
                openApp(autostart = true)
            }
        }
        updateSoon()
    }
}

/** "Receive files": turns Wi‑Fi receive ([ReceiveService]) on or off. */
class ReceiveTile : DeskTile() {
    private val listener: () -> Unit = { update() }

    override fun state() = if (WifiReceive.running || WifiReceive.starting) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    override fun subtitle(state: Int) = when {
        WifiReceive.running -> "Visible on Wi‑Fi"
        WifiReceive.starting -> "Starting…"
        else -> "Off"
    }

    override fun onStartListening() {
        WifiReceive.listen(listener)
        super.onStartListening()
    }

    override fun onStopListening() {
        WifiReceive.unlisten(listener)
        super.onStopListening()
    }

    override fun onClick() {
        if (WifiReceive.running || WifiReceive.starting) {
            try { ReceiveService.stop(this) } catch (_: Exception) {}
            updateSoon()
            return
        }
        // Same checks as Transfer › Receive: without notifications nobody could accept a transfer
        // while the app is in the background, and Android 8–9 needs storage access to save files.
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT < 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing) {
            openApp(screen = "Receive")
            return
        }
        try {
            ReceiveService.start(this)
        } catch (_: Exception) {
            openApp(screen = "Receive")
        }
        updateSoon()
    }
}

/**
 * "Send clipboard to PC": opens [ClipSendActivity], which reads the clipboard and passes it on.
 * Unavailable while no computer is listening.
 */
class ClipTile : DeskTile() {
    override fun state() = if (LinkEvents.listening) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
    override fun subtitle(state: Int) = if (state == Tile.STATE_UNAVAILABLE) "No computer connected" else "Tap to send"

    override fun onClick() {
        if (!LinkEvents.listening) { update(); return }
        val go = Runnable { open(Intent(this, ClipSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION), 53) }
        // The clipboard can only be read with the phone unlocked.
        if (isLocked) unlockAndRun(go) else go.run()
    }
}
