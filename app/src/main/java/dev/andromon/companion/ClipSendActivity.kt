// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject

/**
 * Sends the phone's clipboard to the PC. Since Android 10 only the app that has the focus may
 * read the clipboard, so this see-through screen opens, reads it once it has the focus, passes it
 * on as `{type:"clip", text}` on the `events` stream and closes again. When it can't,
 * `{type:"clip", error}` is sent instead, so a PC that asked isn't left waiting.
 *
 * Opened from the "Send clipboard to PC" button on the "Connected to Andromon" notification,
 * from the quick-settings tile ([ClipTile]) and by Andromon over adb ("Get phone's clipboard").
 * Exported only for that last one and guarded by DUMP, which other apps can't hold.
 * The clipboard's contents are never logged or stored.
 */
class ClipSendActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private var done = false
    private val giveUp = Runnable { finishWith("locked", "Unlock the phone to send its clipboard") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!LinkEvents.listening) {
            done = true
            Toast.makeText(applicationContext, "Andromon isn't connected on your computer", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // Behind the lock screen the focus never comes.
        main.postDelayed(giveUp, 4000)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !done) window.decorView.post { if (!done) read() }
    }

    private fun read() {
        val text = try {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString() else null
        } catch (_: Exception) {
            null
        }
        if (text.isNullOrEmpty()) {
            finishWith("empty", "Nothing is copied on the phone")
            return
        }
        done = true
        val cut = text.length > Links.MAX_CLIP
        LinkEvents.emit(JSONObject().put("type", "clip").put("text", if (cut) text.take(Links.MAX_CLIP) else text).put("truncated", cut))
        LinkStats.clipToComputer(this)
        Toast.makeText(applicationContext, "Clipboard sent to your computer", Toast.LENGTH_SHORT).show()
        close()
    }

    private fun finishWith(error: String, message: String) {
        if (done) return
        done = true
        LinkEvents.emit(JSONObject().put("type", "clip").put("error", error))
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        close()
    }

    private fun close() {
        main.removeCallbacks(giveUp)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        main.removeCallbacks(giveUp)
        super.onDestroy()
    }
}
