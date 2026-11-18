// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the PC link did, kept on the phone for the PC link screen and Stats › PC link: sessions,
 * time linked, requests by kind, files and bytes moved over Wi‑Fi, clipboard shares, the computers
 * that said hello ([hello], API `link.hello`) and a short list of recent activity.
 *
 * One small JSON document in the app's private preferences, 30 days kept. Counters live in memory
 * and are written a few seconds after they change, so a burst of requests (thumbnails) costs one
 * write. Nothing here leaves the phone except through `stats.link`, which only adb can call.
 */
internal object LinkStats {

    private const val PREFS = "link_stats"
    private const val KEEP_DAYS = 30L
    /** Requests or event pings closer together than this belong to one session. */
    const val GAP_MS = 3 * 60_000L
    private const val MAX_RECENT = 40

    /** A request kind (the method's prefix) and how it reads in the app. */
    val KINDS = linkedMapOf(
        "sms" to "Messages", "mms" to "Messages", "contacts" to "Contacts", "calls" to "Call log", "call" to "Calls",
        "calendar" to "Calendar", "media" to "Photos and music", "notif" to "Notifications", "apps" to "App icons",
        "push" to "Links and text", "clip" to "Clipboard", "phone" to "Find my phone", "player" to "Media controls",
        "battery" to "Battery", "stats" to "Statistics", "info" to "Link checks", "link" to "Link checks",
        "events" to "Live updates", "rec" to "Test recording",
    )

    class Hello(val computer: String, val os: String, val app: String, val time: Long)
    class Activity(val time: Long, val kind: String, val text: String, val count: Int)

    /** One day's figures. */
    class Day(
        val date: String, val sessions: Int, val linkedMs: Long, val calls: Map<String, Int>,
        val filesSent: Int, val bytesSent: Long, val filesReceived: Int, val bytesReceived: Long,
        val clipToComputer: Int, val clipFromComputer: Int, val pushes: Int,
    ) {
        val callTotal get() = calls.values.sum()
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var loaded = false
    private var prefs: android.content.SharedPreferences? = null
    private var days = JSONObject()
    private var computers = JSONObject()
    private var recent = JSONArray()
    private var hello: Hello? = null
    @Volatile var lastTouch = 0L; private set
    @Volatile var sessionStart = 0L; private set
    private var dirty = false
    private val listeners = LinkedHashSet<() -> Unit>()
    private var notifyPosted = false

    // ---------- listeners (PC link screen) ----------

    fun listen(l: () -> Unit) { synchronized(listeners) { listeners += l } }
    fun unlisten(l: () -> Unit) { synchronized(listeners) { listeners -= l } }

    private fun changed() {
        synchronized(listeners) { if (notifyPosted || listeners.isEmpty()) return; notifyPosted = true }
        // At most a few redraws a second, however many requests arrive.
        main.postDelayed({
            val ls = synchronized(listeners) { notifyPosted = false; listeners.toList() }
            ls.forEach { it() }
        }, 400)
    }

    // ---------- storage ----------

    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = sp
        val raw = sp.getString("data", null) ?: return
        runCatching {
            val o = JSONObject(raw)
            days = o.optJSONObject("days") ?: JSONObject()
            computers = o.optJSONObject("computers") ?: JSONObject()
            recent = o.optJSONArray("recent") ?: JSONArray()
            lastTouch = o.optLong("lastTouch")
            sessionStart = o.optLong("sessionStart")
            o.optJSONObject("hello")?.let {
                hello = Hello(it.optString("computer"), it.optString("os"), it.optString("app"), it.optLong("time"))
            }
        }
    }

    private fun save() {
        if (dirty) return
        dirty = true
        main.postDelayed({
            val sp = prefs ?: return@postDelayed
            val text = synchronized(lock) {
                dirty = false
                prune()
                JSONObject().put("days", days).put("computers", computers).put("recent", recent)
                    .put("lastTouch", lastTouch).put("sessionStart", sessionStart)
                    .put("hello", hello?.let { JSONObject().put("computer", it.computer).put("os", it.os).put("app", it.app).put("time", it.time) })
                    .toString()
            }
            sp.edit().putString("data", text).apply()
        }, 4000)
    }

    private fun prune() {
        val oldest = LocalDate.now().minusDays(KEEP_DAYS - 1).toString()
        val drop = days.keys().asSequence().filter { it < oldest }.toList()
        drop.forEach { days.remove(it) }
    }

    private fun dateOf(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    private fun today(t: Long = System.currentTimeMillis()): JSONObject {
        val key = dateOf(t)
        return days.optJSONObject(key) ?: JSONObject().also { days.put(key, it) }
    }

    private fun JSONObject.bump(name: String, by: Long = 1) { put(name, optLong(name) + by) }

    /** Forgets everything (Settings › PC link › Clear link history). */
    fun clear(ctx: Context) {
        synchronized(lock) {
            load(ctx)
            days = JSONObject(); computers = JSONObject(); recent = JSONArray(); hello = null
            lastTouch = 0; sessionStart = 0
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        changed()
    }

    // ---------- recording ----------

    /** The computer is there (a request, or a ping it read on the `events` stream). */
    fun touch(ctx: Context) {
        synchronized(lock) {
            load(ctx)
            touchLocked(System.currentTimeMillis())
        }
        save()
    }

    private fun touchLocked(now: Long) {
        if (lastTouch == 0L || now - lastTouch > GAP_MS || now < lastTouch) {
            sessionStart = now
            today(now).bump("sessions")
        } else {
            today(now).bump("linkedMs", now - lastTouch)
        }
        lastTouch = now
    }

    /** One request from the computer. */
    fun call(ctx: Context, method: String) {
        if (method == "stop") return
        val kind = method.substringBefore('.')
        synchronized(lock) {
            load(ctx)
            val now = System.currentTimeMillis()
            touchLocked(now)
            val day = today(now)
            val calls = day.optJSONObject("calls") ?: JSONObject().also { day.put("calls", it) }
            calls.bump(kind)
            describe(method)?.let { addRecentLocked(now, kind, it) }
        }
        save()
        changed()
    }

    /** Something worth listing that isn't a request (files over Wi‑Fi, the clipboard from the phone). */
    fun note(ctx: Context, kind: String, text: String, change: JSONObject.() -> Unit = {}) {
        synchronized(lock) {
            load(ctx)
            val now = System.currentTimeMillis()
            today(now).change()
            addRecentLocked(now, kind, text)
        }
        save()
        changed()
    }

    fun filesSent(ctx: Context, to: String, files: Int, bytes: Long) {
        if (files <= 0) return
        note(ctx, "send", "Sent ${plural(files, "file")} (${fmtSize(bytes)}) to $to") { bump("filesSent", files.toLong()); bump("bytesSent", bytes) }
    }

    fun filesReceived(ctx: Context, from: String, files: Int, bytes: Long) {
        if (files <= 0) return
        note(ctx, "receive", "Received ${plural(files, "file")} (${fmtSize(bytes)}) from $from") { bump("filesReceived", files.toLong()); bump("bytesReceived", bytes) }
    }

    fun clipToComputer(ctx: Context) = note(ctx, "clip", "Sent the clipboard to your computer") { bump("clipToComputer") }

    /** API `link.hello`: the computer says who it is, right after it reaches the companion. */
    fun hello(ctx: Context, computer: String, os: String, app: String): JSONObject {
        val now = System.currentTimeMillis()
        val name = computer.trim().take(80)
        synchronized(lock) {
            load(ctx)
            hello = Hello(name, os.trim().take(40), app.trim().take(60), now)
            val key = name.ifEmpty { "Your computer" }
            val c = computers.optJSONObject(key) ?: JSONObject().put("first", now).also { computers.put(key, it) }
            c.put("os", os.trim().take(40)).put("app", app.trim().take(60)).put("last", now)
            addRecentLocked(now, "link", "${name.ifEmpty { "Your computer" }} connected")
        }
        save()
        changed()
        return JSONObject().put("ok", true)
    }

    private fun addRecentLocked(now: Long, kind: String, text: String) {
        // The same thing again within a few minutes (among the last few lines): one line with a count,
        // moved to the top, not a wall of repeats.
        var same = -1
        for (i in 0 until minOf(recent.length(), 5)) {
            val o = recent.optJSONObject(i) ?: continue
            if (o.optString("text") == text && now - o.optLong("time") < 10 * 60_000L) { same = i; break }
        }
        val entry = if (same >= 0) recent.getJSONObject(same).put("time", now).put("count", recent.getJSONObject(same).optInt("count", 1) + 1)
            else JSONObject().put("time", now).put("kind", kind).put("text", text).put("count", 1)
        val a = JSONArray().put(entry)
        for (i in 0 until recent.length()) { if (i != same && a.length() < MAX_RECENT) a.put(recent.get(i)) }
        recent = a
    }

    /** How a request reads in the activity list; null for the ones too small to mention. */
    private fun describe(method: String): String? = when (method) {
        "info", "link.hello", "notif.status", "notif.icon", "apps.icons", "media.thumbs", "mms.part", "contacts.photo" -> null
        "sms.list" -> "Read your messages"
        "sms.send" -> "Sent a text message"
        "sms.restore" -> "Restored text messages"
        "mms.list" -> "Read picture messages"
        "contacts.list" -> "Read your contacts"
        "contacts.add" -> "Added contacts"
        "contacts.update", "contacts.delete", "contacts.setPhoto" -> "Changed contacts"
        "calls.list" -> "Read the call log"
        "calls.restore" -> "Restored the call log"
        "call.answer" -> "Answered a call"
        "call.end" -> "Ended a call"
        "battery.health" -> "Read battery health"
        "push.send" -> "Received a link or text"
        "clip.set" -> "Received the computer's clipboard"
        "phone.ring" -> "Find my phone"
        "events" -> "Started live updates"
        else -> when (method.substringBefore('.')) {
            "calendar" -> if (method == "calendar.list") "Read the calendar" else "Changed the calendar"
            "media" -> if (method == "media.list") "Browsed photos and music" else if (method == "media.delete") "Deleted photos or music" else null
            "notif" -> if (method == "notif.list") null else "Answered a notification"
            "player" -> if (method == "player.control") "Controlled media playback" else null
            "stats" -> "Read statistics"
            else -> null
        }
    }

    private fun plural(n: Int, noun: String) = "$n $noun" + if (n == 1) "" else "s"

    // ---------- reading ----------

    fun hello(ctx: Context): Hello? = synchronized(lock) { load(ctx); hello }

    /** The computer's name from `link.hello`, or "Your computer" until it has said hello. */
    fun computerName(ctx: Context): String = hello(ctx)?.computer?.takeIf { it.isNotBlank() } ?: "Your computer"

    /** A computer is talking to the phone right now: an open event stream, or a request a moment ago. */
    fun linkedNow(): Boolean = CompanionService.running &&
        (LinkEvents.listening || (lastTouch > 0 && System.currentTimeMillis() - lastTouch < GAP_MS))

    fun recent(ctx: Context, max: Int = MAX_RECENT): List<Activity> = synchronized(lock) {
        load(ctx)
        (0 until minOf(recent.length(), max)).mapNotNull { i ->
            recent.optJSONObject(i)?.let { Activity(it.optLong("time"), it.optString("kind"), it.optString("text"), it.optInt("count", 1)) }
        }
    }

    /** The last [n] days, oldest first, today last; days without a record are zero. */
    fun days(ctx: Context, n: Int): List<Day> = synchronized(lock) {
        load(ctx)
        val today = LocalDate.now()
        (n - 1 downTo 0).map { back ->
            val key = today.minusDays(back.toLong()).toString()
            val o = days.optJSONObject(key) ?: JSONObject()
            val calls = o.optJSONObject("calls")?.let { c -> c.keys().asSequence().associateWith { c.optInt(it) } } ?: emptyMap()
            var linked = o.optLong("linkedMs")
            // The session going on now: count up to this moment, not just to the last request.
            if (back == 0 && linkedNow() && lastTouch > 0) linked += (System.currentTimeMillis() - lastTouch).coerceIn(0, GAP_MS)
            Day(key, o.optInt("sessions"), linked, calls, o.optInt("filesSent"), o.optLong("bytesSent"),
                o.optInt("filesReceived"), o.optLong("bytesReceived"), o.optInt("clipToComputer"), o.optInt("clipFromComputer"),
                o.optInt("pushes"))
        }
    }

    /** Computers that said hello: name, os, app, first and last seen; most recent first. */
    fun computers(ctx: Context): List<JSONObject> = synchronized(lock) {
        load(ctx)
        computers.keys().asSequence().map { k -> JSONObject(computers.getJSONObject(k).toString()).put("name", k) }
            .sortedByDescending { it.optLong("last") }.toList()
    }

    /**
     * How adb reaches the phone: "usb" while a USB cable carries adb, "wifi" while Wireless
     * debugging is on and no cable is, null when neither is known.
     */
    fun transport(ctx: Context): String? {
        val usb = runCatching {
            val f = IntentFilter("android.hardware.usb.action.USB_STATE")
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(null, f, Context.RECEIVER_NOT_EXPORTED) else ctx.registerReceiver(null, f)
        }.getOrNull()
        val cable = usb?.getBooleanExtra("connected", false) == true && usb.getBooleanExtra("adb", false)
        if (cable) return "usb"
        val wifi = runCatching { Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 1 }.getOrDefault(false)
        return if (wifi) "wifi" else null
    }

    // ---------- API ----------

    /** API `stats.link`: see README › API. */
    fun json(ctx: Context, daysWanted: Int): JSONObject {
        val n = daysWanted.coerceIn(1, KEEP_DAYS.toInt())
        val list = days(ctx, n)
        val calls = HashMap<String, Int>()
        list.forEach { d -> d.calls.forEach { (k, v) -> calls[k] = (calls[k] ?: 0) + v } }
        val h = hello(ctx)
        val linked = linkedNow()
        return JSONObject()
            .put("days", n)
            .put("from", LocalDate.now().minusDays(n - 1L).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
            .put("to", System.currentTimeMillis())
            .put("running", CompanionService.running)
            .put("linkedNow", linked)
            .put("transport", if (linked) transport(ctx) ?: JSONObject.NULL else JSONObject.NULL)
            .put("sessionStart", if (linked && sessionStart > 0) sessionStart else JSONObject.NULL)
            .put("computer", h?.let { JSONObject().put("name", it.computer).put("os", it.os).put("app", it.app).put("time", it.time) } ?: JSONObject.NULL)
            .put("sessions", list.sumOf { it.sessions })
            .put("linkedMs", list.sumOf { it.linkedMs })
            .put("callsTotal", calls.values.sum())
            .put("calls", JSONObject(calls as Map<*, *>))
            .put("filesSent", list.sumOf { it.filesSent })
            .put("bytesSent", list.sumOf { it.bytesSent })
            .put("filesReceived", list.sumOf { it.filesReceived })
            .put("bytesReceived", list.sumOf { it.bytesReceived })
            .put("clipToComputer", list.sumOf { it.clipToComputer })
            .put("clipFromComputer", list.sumOf { it.calls["clip"] ?: 0 })
            .put("pushes", list.sumOf { it.calls["push"] ?: 0 })
            .put("daily", JSONArray(list.map { d ->
                JSONObject().put("date", d.date).put("sessions", d.sessions).put("linkedMs", d.linkedMs).put("calls", d.callTotal)
                    .put("filesSent", d.filesSent).put("bytesSent", d.bytesSent)
                    .put("filesReceived", d.filesReceived).put("bytesReceived", d.bytesReceived)
            }))
            .put("computers", JSONArray(computers(ctx).map { c ->
                JSONObject().put("name", c.optString("name")).put("os", c.optString("os")).put("app", c.optString("app"))
                    .put("firstSeen", c.optLong("first")).put("lastSeen", c.optLong("last"))
            }))
    }
}
