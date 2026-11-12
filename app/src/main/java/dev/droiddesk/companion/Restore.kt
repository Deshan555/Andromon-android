// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

package dev.droiddesk.companion

import android.Manifest
import android.app.AppOpsManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.CallLog
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject

/**
 * Putting a backup back on the phone: call log entries and text messages.
 * Both skip what is already there, so restoring the same backup twice adds nothing.
 */
internal class Restore(private val ctx: Context) {

    private val cr = ctx.contentResolver

    fun call(method: String, p: JSONObject): Any = when (method) {
        "calls.restore" -> calls(p.getJSONArray("calls"))
        "sms.restore" -> sms(p.getJSONArray("messages"))
        else -> throw IllegalArgumentException("Unknown method: $method")
    }

    private fun granted(perm: String) = ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun result(added: Int, skipped: Int, failed: Int) =
        JSONObject().put("added", added).put("skipped", skipped).put("failed", failed)

    // ---------- call log ----------

    private fun calls(list: JSONArray): JSONObject {
        if (!granted(Manifest.permission.WRITE_CALL_LOG) || !granted(Manifest.permission.READ_CALL_LOG)) {
            throw SecurityException("no access to change the call log")
        }
        val have = HashSet<String>()
        cr.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE), null, null, null,
        )?.use { c ->
            while (c.moveToNext()) have += callKey(if (c.isNull(0)) "" else c.getString(0), c.getLong(1), c.getLong(2), c.getInt(3))
        }
        var added = 0
        var skipped = 0
        var failed = 0
        for (i in 0 until list.length()) {
            try {
                val o = list.getJSONObject(i)
                val number = o.optString("number")
                val date = o.getLong("date")
                val duration = o.optLong("duration", 0).coerceAtLeast(0)
                val type = o.getInt("type")
                if (type !in CallLog.Calls.INCOMING_TYPE..CallLog.Calls.ANSWERED_EXTERNALLY_TYPE || date <= 0) { failed++; continue }
                if (!have.add(callKey(number, date, duration, type))) { skipped++; continue }
                val v = ContentValues().apply {
                    put(CallLog.Calls.NUMBER, number)
                    put(CallLog.Calls.DATE, date)
                    put(CallLog.Calls.DURATION, duration)
                    put(CallLog.Calls.TYPE, type)
                    put(CallLog.Calls.NEW, 0) // no "missed call" notification for old calls
                    put(CallLog.Calls.IS_READ, 1)
                    o.optString("name").takeIf { it.isNotBlank() }?.let { put(CallLog.Calls.CACHED_NAME, it) }
                }
                val uri = cr.insert(CallLog.Calls.CONTENT_URI, v)
                if (uri != null && rowId(uri) > 0) added++ else failed++
            } catch (_: Exception) {
                failed++ // e.g. voicemail entries, which only the phone app may write
            }
        }
        return result(added, skipped, failed)
    }

    private fun callKey(number: String, date: Long, duration: Long, type: Int) = "$type|$date|$duration|$number"

    // ---------- SMS ----------

    private fun sms(list: JSONArray): JSONObject {
        if (!granted(Manifest.permission.READ_SMS)) throw SecurityException("no access to messages")
        val isDefault = Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName
        if (!isDefault && writeSmsAllowed(ctx) == false) {
            throw IllegalStateException("Writing SMS isn't allowed yet. Droid Desk turns it on over USB (appops … WRITE_SMS allow); reconnect and try again.")
        }
        val have = HashSet<String>()
        cr.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.BODY, Telephony.Sms.TYPE), null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                have += smsKey(if (c.isNull(0)) "" else c.getString(0), c.getLong(1), if (c.isNull(2)) "" else c.getString(2), c.getInt(3))
            }
        }
        var skipped = 0
        var failed = 0
        var tried = 0
        val inserted = ArrayList<Long>()
        for (i in 0 until list.length()) {
            try {
                val o = list.getJSONObject(i)
                val address = o.optString("address")
                val body = o.optString("body")
                val date = o.getLong("date")
                val given = o.getInt("type")
                if (given !in 1..6 || date <= 0) { failed++; continue }
                // "Outbox" and "queued" would make the phone's SMS app send the message again: keep
                // those as "failed" instead.
                val type = if (given == Telephony.Sms.MESSAGE_TYPE_OUTBOX || given == Telephony.Sms.MESSAGE_TYPE_QUEUED) Telephony.Sms.MESSAGE_TYPE_FAILED else given
                val fresh = !have.contains(smsKey(address, date, body, given)) && have.add(smsKey(address, date, body, type))
                if (!fresh) { skipped++; continue }
                have += smsKey(address, date, body, given)
                tried++
                val v = ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, address)
                    put(Telephony.Sms.BODY, body)
                    put(Telephony.Sms.DATE, date)
                    put(Telephony.Sms.DATE_SENT, o.optLong("dateSent", 0))
                    put(Telephony.Sms.TYPE, type)
                    put(Telephony.Sms.READ, if (o.optBoolean("read", true)) 1 else 0)
                    put(Telephony.Sms.SEEN, 1)
                }
                val id = cr.insert(Telephony.Sms.CONTENT_URI, v)?.let { rowId(it) } ?: 0
                if (id > 0) inserted += id else failed++ // a dropped insert comes back as null or …/0
            } catch (_: Exception) {
                failed++
            }
        }
        // Don't trust the insert's answer alone: read the rows back.
        var stored = 0
        for (chunk in inserted.chunked(400)) {
            cr.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID), "${Telephony.Sms._ID} IN (${chunk.joinToString(",")})", null, null)
                ?.use { stored += it.count }
        }
        failed += inserted.size - stored
        if (stored == 0 && tried > 0 && !isDefault) {
            throw IllegalStateException("This phone didn't store the messages. Android only lets the default SMS app write messages, and this phone ignores the permission Droid Desk sets over USB, so restoring SMS isn't possible here.")
        }
        return result(stored, skipped, failed)
    }

    private fun smsKey(address: String, date: Long, body: String, type: Int) = "$type|$date|$address|$body"

    private fun rowId(uri: android.net.Uri): Long = try { ContentUris.parseId(uri) } catch (_: Exception) { 0 }

    companion object {
        /**
         * Whether the app-op WRITE_SMS is allowed for the companion (Droid Desk sets it over adb).
         * null when this Android version doesn't tell.
         */
        fun writeSmsAllowed(ctx: Context): Boolean? = try {
            val ops = ctx.getSystemService(AppOpsManager::class.java)
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(OP_WRITE_SMS, Process.myUid(), ctx.packageName)
            else ops.checkOpNoThrow(OP_WRITE_SMS, Process.myUid(), ctx.packageName)
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            null
        }

        private const val OP_WRITE_SMS = "android:write_sms"
    }
}
