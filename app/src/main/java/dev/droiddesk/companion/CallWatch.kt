// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

package dev.droiddesk.companion

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.CallLog
import android.provider.ContactsContract.PhoneLookup
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Call alerts for the PC: watches the phone's call state while Droid Desk is listening and turns
 * it into simple events, each a JSON object with `"type": "call"`:
 *
 * - `ringing`: an incoming call (sent again if the number arrives a moment later)
 * - `answered`: that call was picked up (on the phone or from the PC)
 * - `missed` / `declined` / `blocked`: it stopped ringing without being answered
 *   (told apart from the call log a moment later; `missed` when unsure)
 * - `ended`: a call that was answered has hung up
 *
 * Every event carries `id` (one per call), `number`, `name` (from contacts, may be empty) and
 * `time`. Uses the PHONE_STATE broadcast, which needs READ_PHONE_STATE; the number also needs
 * READ_CALL_LOG. Nothing is watched while no PC is listening.
 */
internal object CallWatch {

    private val listeners = CopyOnWriteArrayList<(JSONObject) -> Unit>()
    private var receiver: BroadcastReceiver? = null
    private var appCtx: Context? = null
    private val worker by lazy { Handler(HandlerThread("call-watch").apply { start() }.looper) }

    // State of the current call, touched only on the worker thread.
    private var last = TelephonyManager.EXTRA_STATE_IDLE
    private var callId = 0L
    private var number = ""
    private var name = ""
    private var ringStart = 0L
    private var incoming = false

    fun permissions(ctx: Context) = JSONObject()
        .put("READ_PHONE_STATE", granted(ctx, Manifest.permission.READ_PHONE_STATE))
        .put("READ_CALL_LOG", granted(ctx, Manifest.permission.READ_CALL_LOG))
        .put("READ_CONTACTS", granted(ctx, Manifest.permission.READ_CONTACTS))
        .put("ANSWER_PHONE_CALLS", granted(ctx, Manifest.permission.ANSWER_PHONE_CALLS))

    @Synchronized
    fun add(ctx: Context, listener: (JSONObject) -> Unit) {
        listeners.add(listener)
        if (receiver != null) return
        val app = ctx.applicationContext
        appCtx = app
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
                @Suppress("DEPRECATION")
                val num = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
                worker.post { onState(state, num) }
            }
        }
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(r, filter)
        receiver = r
    }

    @Synchronized
    fun remove(listener: (JSONObject) -> Unit) {
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            receiver?.let { r -> try { appCtx?.unregisterReceiver(r) } catch (_: IllegalArgumentException) {} }
            receiver = null
        }
    }

    // Android sends each state twice to apps holding READ_CALL_LOG: once without the number and
    // once with it. Repeats of the same state only fill in the number.
    private fun onState(state: String, num: String) {
        val ctx = appCtx ?: return
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (last != state) {
                    callId++; number = num; name = lookupName(ctx, num); ringStart = System.currentTimeMillis(); incoming = true
                    emit("ringing")
                } else if (num.isNotEmpty() && number.isEmpty()) {
                    number = num; name = lookupName(ctx, num)
                    emit("ringing")
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (last == TelephonyManager.EXTRA_STATE_RINGING) emit("answered")
                else if (last == TelephonyManager.EXTRA_STATE_IDLE) { callId++; number = num; name = ""; incoming = false }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (last == TelephonyManager.EXTRA_STATE_RINGING) {
                    // Missed, declined or blocked: the call log knows which, once it has been written.
                    val id = callId; val n = number; val nm = name; val since = ringStart
                    worker.postDelayed({ emit(unansweredKind(ctx, n, since), id, n, nm) }, 1500)
                } else if (last == TelephonyManager.EXTRA_STATE_OFFHOOK && incoming) emit("ended")
                incoming = false
            }
            else -> return
        }
        last = state
    }

    private fun emit(kind: String, id: Long = callId, num: String = number, nm: String = name) {
        val ev = JSONObject().put("type", "call").put("state", kind).put("id", id)
            .put("number", num).put("name", nm).put("time", System.currentTimeMillis())
        for (l in listeners) l(ev)
    }

    private fun unansweredKind(ctx: Context, num: String, since: Long): String {
        if (!granted(ctx, Manifest.permission.READ_CALL_LOG)) return "missed"
        return try {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.TYPE, CallLog.Calls.NUMBER),
                "${CallLog.Calls.DATE} >= ?", arrayOf((since - 10_000).toString()), "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(1).orEmpty()
                    if (num.isNotEmpty() && n.isNotEmpty() && digits(n) != digits(num)) continue
                    return when (c.getInt(0)) {
                        CallLog.Calls.REJECTED_TYPE -> "declined"
                        CallLog.Calls.BLOCKED_TYPE -> "blocked"
                        CallLog.Calls.INCOMING_TYPE -> "answered"
                        else -> "missed"
                    }
                }
                "missed"
            } ?: "missed"
        } catch (_: Exception) {
            "missed"
        }
    }

    private fun digits(s: String) = s.filter { it.isDigit() }.takeLast(9)

    private fun lookupName(ctx: Context, num: String): String {
        if (num.isEmpty() || !granted(ctx, Manifest.permission.READ_CONTACTS)) return ""
        return try {
            ctx.contentResolver.query(
                Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num)),
                arrayOf(PhoneLookup.DISPLAY_NAME), null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() else "" } ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    // ---------- actions from the PC ----------

    /** Picks up the ringing call. Needs ANSWER_PHONE_CALLS. */
    @Suppress("DEPRECATION", "MissingPermission")
    fun answer(ctx: Context): Boolean {
        require(granted(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) { "No permission to answer calls" }
        ctx.getSystemService(TelecomManager::class.java).acceptRingingCall()
        return true
    }

    /** Declines a ringing call or hangs up the current one. Needs ANSWER_PHONE_CALLS, Android 9+. */
    @Suppress("DEPRECATION", "MissingPermission")
    fun end(ctx: Context): Boolean {
        require(Build.VERSION.SDK_INT >= 28) { "Needs Android 9" }
        require(granted(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) { "No permission to end calls" }
        return ctx.getSystemService(TelecomManager::class.java).endCall()
    }

    private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
}
