// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/**
 * The phone's calendars for Andromon's Calendar tab: read calendars and events, and add events
 * from an .ics file or a backup. Attendees are read but never written, so nothing here can make
 * a calendar account send invitations.
 */
internal class CalendarApi(private val ctx: Context) {

    private val cr = ctx.contentResolver

    fun call(method: String, p: JSONObject): Any = when (method) {
        "calendar.list" -> list(if (p.has("from") && p.has("to")) p.getLong("from") to p.getLong("to") else null)
        "calendar.add" -> add(p.getLong("calendarId"), p.getJSONArray("events"))
        "calendar.createLocal" -> createLocal(p.optString("name").ifBlank { "Andromon" })
        else -> throw IllegalArgumentException("Unknown method: $method")
    }

    private fun requireRead() {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("no access to the calendar")
        }
    }

    private fun requireWrite() {
        requireRead()
        if (ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("no access to change the calendar")
        }
    }

    // ---------- list ----------

    private fun list(range: Pair<Long, Long>?): JSONObject {
        requireRead()
        val out = JSONObject().put("calendars", calendars())

        var wanted: HashSet<Long>? = null
        if (range != null) {
            val ids = HashSet<Long>()
            val instances = JSONArray()
            val uri = Instances.CONTENT_URI.buildUpon().also {
                ContentUris.appendId(it, range.first)
                ContentUris.appendId(it, range.second)
            }.build()
            cr.query(uri, arrayOf(Instances.EVENT_ID, Instances.BEGIN, Instances.END), null, null, "${Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    ids += c.getLong(0)
                    instances.put(JSONObject().put("eventId", c.getLong(0)).put("begin", c.getLong(1)).put("end", c.getLong(2)))
                }
            }
            wanted = ids
            out.put("instances", instances)
        }

        val cols = arrayOf(
            Events._ID, Events.CALENDAR_ID, Events.TITLE, Events.EVENT_LOCATION, Events.DESCRIPTION,
            Events.DTSTART, Events.DTEND, Events.DURATION, Events.ALL_DAY, Events.EVENT_TIMEZONE,
            Events.RRULE, Events.RDATE, Events.EXDATE, Events.EXRULE,
            Events.ORIGINAL_ID, Events.ORIGINAL_INSTANCE_TIME, Events.STATUS, Events.UID_2445,
        )
        val events = JSONArray()
        val byId = HashMap<Long, JSONObject>()
        cr.query(Events.CONTENT_URI, cols, "${Events.DELETED} != 1", null, "${Events.DTSTART} ASC")?.use { c ->
            while (c.moveToNext()) {
                try {
                    val id = c.getLong(0)
                    if (wanted != null && id !in wanted) continue
                    val start = c.getLong(5)
                    val duration = c.text(7)
                    val end = if (!c.isNull(6)) c.getLong(6) else start + durationMs(duration)
                    val o = JSONObject()
                        .put("id", id).put("calendarId", c.getLong(1))
                        .put("title", c.text(2)).put("location", c.text(3)).put("description", c.text(4))
                        .put("start", start).put("end", end)
                        .put("allDay", c.getInt(8) == 1)
                        .put("timezone", c.text(9))
                        .put("rrule", c.text(10))
                    c.text(11).takeIf { it.isNotEmpty() }?.let { o.put("rdate", it) }
                    c.text(12).takeIf { it.isNotEmpty() }?.let { o.put("exdate", it) }
                    c.text(13).takeIf { it.isNotEmpty() }?.let { o.put("exrule", it) }
                    if (duration.isNotEmpty()) o.put("duration", duration)
                    if (!c.isNull(14)) {
                        o.put("originalId", c.getLong(14))
                        if (!c.isNull(15)) o.put("originalInstanceTime", c.getLong(15))
                    }
                    if (!c.isNull(16)) o.put("status", c.getInt(16))
                    c.text(17).takeIf { it.isNotEmpty() }?.let { o.put("uid", it) }
                    events.put(o)
                    byId[id] = o
                } catch (_: Exception) {
                    // skip one unreadable event
                }
            }
        }
        try {
            cr.query(
                Attendees.CONTENT_URI,
                arrayOf(Attendees.EVENT_ID, Attendees.ATTENDEE_NAME, Attendees.ATTENDEE_EMAIL, Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_TYPE),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val ev = byId[c.getLong(0)] ?: continue
                    val arr = ev.optJSONArray("attendees") ?: JSONArray().also { ev.put("attendees", it) }
                    arr.put(JSONObject().put("name", c.text(1)).put("email", c.text(2))
                        .put("status", if (c.isNull(3)) 0 else c.getInt(3)).put("type", if (c.isNull(4)) 0 else c.getInt(4)))
                }
            }
        } catch (_: Exception) {
        }
        return out.put("events", events)
    }

    private fun calendars(): JSONArray {
        val out = JSONArray()
        val cols = arrayOf(
            Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE,
            Calendars.CALENDAR_COLOR, Calendars.VISIBLE, Calendars.CALENDAR_ACCESS_LEVEL, Calendars.OWNER_ACCOUNT, Calendars.IS_PRIMARY,
        )
        fun read(c: Cursor, withPrimary: Boolean) {
            while (c.moveToNext()) {
                val account = c.text(2)
                val primary = if (withPrimary && !c.isNull(8)) c.getInt(8) == 1 else account.isNotEmpty() && c.text(7) == account
                out.put(
                    JSONObject().put("id", c.getLong(0)).put("name", c.text(1)).put("account", account)
                        .put("accountType", c.text(3))
                        .put("color", "#%06x".format((if (c.isNull(4)) 0 else c.getInt(4)) and 0xFFFFFF))
                        .put("visible", c.getInt(5) == 1)
                        .put("writable", c.getInt(6) >= Calendars.CAL_ACCESS_CONTRIBUTOR)
                        .put("primary", primary)
                )
            }
        }
        try {
            cr.query(Calendars.CONTENT_URI, cols, null, null, "${Calendars._ID} ASC")?.use { read(it, true) }
        } catch (_: IllegalArgumentException) {
            // A phone whose calendar storage has no "isPrimary" column.
            cr.query(Calendars.CONTENT_URI, cols.copyOf(8).requireNoNulls(), null, null, "${Calendars._ID} ASC")?.use { read(it, false) }
        }
        return out
    }

    // ---------- add ----------

    private fun add(calendarId: Long, list: JSONArray): JSONObject {
        requireWrite()
        val access = cr.query(
            ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId), arrayOf(Calendars.CALENDAR_ACCESS_LEVEL), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getInt(0) else null }
            ?: throw IllegalArgumentException("That calendar is no longer on the phone")
        require(access >= Calendars.CAL_ACCESS_CONTRIBUTOR) { "That calendar is read-only. Choose another calendar." }

        val have = HashSet<String>()
        cr.query(
            Events.CONTENT_URI, arrayOf(Events.TITLE, Events.DTSTART, Events.RRULE),
            "${Events.CALENDAR_ID}=? AND ${Events.DELETED} != 1", arrayOf(calendarId.toString()), null,
        )?.use { c -> while (c.moveToNext()) have += key(c.text(0), c.getLong(1), c.text(2)) }

        var added = 0
        var skipped = 0
        var failed = 0
        for (i in 0 until list.length()) {
            try {
                val o = list.getJSONObject(i)
                val title = o.optString("title")
                val start = o.getLong("start")
                val allDay = o.optBoolean("allDay")
                val rrule = o.optString("rrule").trim()
                val rdate = o.optString("rdate").trim()
                if (!have.add(key(title, start, rrule))) { skipped++; continue }
                var end = if (o.has("end") && !o.isNull("end")) o.getLong("end") else start
                if (end <= start && allDay) end = start + DAY
                if (end < start) end = start
                val v = ContentValues().apply {
                    put(Events.CALENDAR_ID, calendarId)
                    put(Events.TITLE, title)
                    o.optString("location").takeIf { it.isNotEmpty() }?.let { put(Events.EVENT_LOCATION, it) }
                    o.optString("description").takeIf { it.isNotEmpty() }?.let { put(Events.DESCRIPTION, it) }
                    put(Events.DTSTART, start)
                    put(Events.ALL_DAY, if (allDay) 1 else 0)
                    put(Events.EVENT_TIMEZONE, if (allDay) "UTC" else o.optString("timezone").ifBlank { TimeZone.getDefault().id })
                    if (rrule.isNotEmpty() || rdate.isNotEmpty()) {
                        // Android wants a duration instead of an end for repeating events.
                        put(Events.DURATION, o.optString("duration").trim().ifEmpty {
                            if (allDay) "P${((end - start + DAY - 1) / DAY).coerceAtLeast(1)}D" else "P${(end - start) / 1000}S"
                        })
                        if (rrule.isNotEmpty()) put(Events.RRULE, rrule)
                        if (rdate.isNotEmpty()) put(Events.RDATE, rdate)
                        o.optString("exdate").trim().takeIf { it.isNotEmpty() }?.let { put(Events.EXDATE, it) }
                    } else {
                        put(Events.DTEND, end)
                    }
                }
                val uri = cr.insert(Events.CONTENT_URI, v)
                if (uri != null && ContentUris.parseId(uri) > 0) added++ else failed++
            } catch (e: SecurityException) {
                throw e
            } catch (_: Exception) {
                failed++
            }
        }
        return JSONObject().put("added", added).put("skipped", skipped).put("failed", failed)
    }

    private fun key(title: String, start: Long, rrule: String) = "$start|$rrule|$title"

    /** A calendar kept only on the phone, for phones that have no calendar events can be added to. */
    private fun createLocal(name: String): JSONObject {
        requireWrite()
        cr.query(
            Calendars.CONTENT_URI, arrayOf(Calendars._ID),
            "${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.ACCOUNT_NAME}=?", arrayOf(CalendarContract.ACCOUNT_TYPE_LOCAL, LOCAL_ACCOUNT), null,
        )?.use { c -> if (c.moveToFirst()) return JSONObject().put("id", c.getLong(0)) }
        val uri = Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val v = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, name)
            put(Calendars.CALENDAR_DISPLAY_NAME, name)
            put(Calendars.CALENDAR_COLOR, 0xFF3F7DE0.toInt())
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }
        val created = cr.insert(uri, v) ?: throw IllegalStateException("The phone didn't create the calendar")
        return JSONObject().put("id", ContentUris.parseId(created))
    }

    /** Milliseconds of an RFC 5545 duration such as P1D, PT1H30M, P2W or Android's own P3600S. */
    private fun durationMs(s: String): Long {
        if (s.isEmpty()) return 0
        var total = 0L
        var n = 0L
        var digits = false
        for (ch in s.uppercase()) {
            when {
                ch in '0'..'9' -> { n = n * 10 + (ch - '0'); digits = true }
                ch == 'W' -> { total += n * 7 * DAY; n = 0; digits = false }
                ch == 'D' -> { total += n * DAY; n = 0; digits = false }
                ch == 'H' -> { total += n * 3_600_000; n = 0; digits = false }
                ch == 'M' -> { total += n * 60_000; n = 0; digits = false }
                ch == 'S' -> { total += n * 1000; n = 0; digits = false }
                else -> { if (digits) { n = 0; digits = false } } // P, T, +, -
            }
        }
        return if (s.trimStart().startsWith("-")) -total else total
    }

    private companion object {
        const val DAY = 86_400_000L
        const val LOCAL_ACCOUNT = "Andromon"
    }
}

private fun Cursor.text(i: Int): String = if (isNull(i)) "" else getString(i) ?: ""
