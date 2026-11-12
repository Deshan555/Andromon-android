// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

package dev.droiddesk.companion

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Receives the phone's notifications once the person allows "Notification access" for the
 * companion (Droid Desk can turn that on over adb). They are only passed on while a PC is
 * listening on the `events` stream with notifications turned on; nothing is stored.
 */
class NotifListener : NotificationListenerService() {

    companion object {
        @Volatile var instance: NotifListener? = null
            private set
    }

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        NotifWatch.posted(this, sbn, rankingMap)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        NotifWatch.removed(sbn)
    }
}

/**
 * Turns notifications into JSON for the PC and carries out what the PC asks: reply, press one of
 * the notification's buttons, or dismiss it.
 *
 * Event: `{type:"notif", op:"posted"|"removed", key, pkg, app, title, text, lines[], messages[{sender,text,time}],
 * conversation, time, actions[{i,title,reply}], clearable, alert, importance, largeIcon?}`.
 * Skipped: ongoing ones (music, downloads, navigation), group summaries, calls (call alerts cover
 * those), and the companion's own.
 */
internal object NotifWatch {

    private val listeners = CopyOnWriteArrayList<(JSONObject) -> Unit>()
    private val seen = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 500
    }

    fun add(listener: (JSONObject) -> Unit) { listeners.add(listener) }
    fun remove(listener: (JSONObject) -> Unit) { listeners.remove(listener) }

    fun access(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
        val me = ComponentName(ctx, NotifListener::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun status(ctx: Context): JSONObject = JSONObject()
        .put("access", access(ctx))
        .put("connected", NotifListener.instance != null)
        .put("component", ComponentName(ctx, NotifListener::class.java).flattenToString())

    // Keys the PC has been sent, so a notification that stops qualifying (say it became a group
    // summary) can be taken back, without a "removed" for every music-player update.
    private val sent = HashSet<String>()

    // What each notification last said, when that changed, and when each group's summary last
    // made a sound: apps re-post every conversation on each new message, and many let only the
    // group summary alert, so a pop-up is due for a conversation whose content changed while its
    // group (or itself) alerted.
    private val lastHash = HashMap<String, Int>()
    private val changedAt = HashMap<String, Long>()
    private val groupAlert = HashMap<String, Pair<Long, Int>>()
    private const val PAIR_MS = 3000L

    fun posted(svc: NotifListener, sbn: StatusBarNotification, ranking: NotificationListenerService.RankingMap?) {
        val first = synchronized(seen) { seen.put(sbn.key, true) == null }
        if (listeners.isEmpty()) return
        val n = sbn.notification
        val alerts = first || n.flags and Notification.FLAG_ONLY_ALERT_ONCE == 0
        val imp = importance(sbn, ranking)
        val now = System.currentTimeMillis()
        if (!wanted(svc, sbn)) {
            if (synchronized(sent) { sent.remove(sbn.key) }) emitRemoved(sbn)
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 && alerts && imp >= 3) {
                synchronized(groupAlert) { groupAlert[sbn.groupKey] = now to imp }
                // Conversations that changed just before their summary alerted.
                (svc.activeNotifications ?: emptyArray())
                    .filter { it.groupKey == sbn.groupKey && it.key != sbn.key && wanted(svc, it) }
                    .filter { synchronized(changedAt) { now - (changedAt[it.key] ?: 0) < PAIR_MS } }
                    .forEach { emitPosted(svc, it, ranking, alert = true, importance = imp) }
            }
            return
        }
        val hash = hashOf(sbn)
        val changed = synchronized(changedAt) {
            val prev = lastHash.put(sbn.key, hash)
            // Never seen (e.g. the companion just restarted): new only if it is about something recent.
            (if (prev == null) n.`when` <= 0 || now - n.`when` < 60_000 else prev != hash)
                .also { if (it) changedAt[sbn.key] = now }
        }
        val group = synchronized(groupAlert) { groupAlert[sbn.groupKey]?.takeIf { now - it.first < PAIR_MS } }
        val effective = if (imp < 3 && group != null) group.second else imp
        emitPosted(svc, sbn, ranking, alert = changed && alerts, importance = effective)
    }

    private fun hashOf(sbn: StatusBarNotification): Int {
        val e = sbn.notification.extras
        @Suppress("DEPRECATION")
        val lastMsg = e.getParcelableArray(Notification.EXTRA_MESSAGES)?.lastOrNull() as? Bundle
        return listOf(e.getCharSequence(Notification.EXTRA_TITLE), e.getCharSequence(Notification.EXTRA_TEXT),
            lastMsg?.getCharSequence("text"), lastMsg?.getLong("time")).joinToString("\u0001").hashCode()
    }

    private fun emitPosted(svc: NotifListener, sbn: StatusBarNotification, ranking: NotificationListenerService.RankingMap?, alert: Boolean, importance: Int) {
        synchronized(sent) { sent.add(sbn.key) }
        emit(toJson(svc, sbn, ranking, withIcon = true).put("op", "posted").put("alert", alert).put("importance", importance))
    }

    fun removed(sbn: StatusBarNotification) {
        synchronized(seen) { seen.remove(sbn.key) }
        synchronized(changedAt) { lastHash.remove(sbn.key); changedAt.remove(sbn.key) }
        val was = synchronized(sent) { sent.remove(sbn.key) }
        if (listeners.isEmpty() || !was) return
        emitRemoved(sbn)
    }

    private fun emitRemoved(sbn: StatusBarNotification) =
        emit(JSONObject().put("type", "notif").put("op", "removed").put("key", sbn.key).put("pkg", sbn.packageName))

    private fun importance(sbn: StatusBarNotification, ranking: NotificationListenerService.RankingMap?): Int {
        val r = NotificationListenerService.Ranking()
        return if (ranking != null && ranking.getRanking(sbn.key, r)) r.importance else 3
    }

    private fun emit(o: JSONObject) { for (l in listeners) l(o) }

    private fun wanted(ctx: Context, sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (sbn.packageName == ctx.packageName) return false
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY) != 0) return false
        if (n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_TRANSPORT) return false
        val e = n.extras
        return !e.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank() || !e.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank()
    }

    // ---------- PC requests ----------

    fun call(ctx: Context, method: String, p: JSONObject): Any = when (method) {
        "notif.status" -> status(ctx)
        "notif.list" -> list(ctx)
        "notif.reply" -> reply(ctx, p.getString("key"), p.getInt("action"), p.getString("text"))
        "notif.action" -> action(ctx, p.getString("key"), p.getInt("action"))
        "notif.dismiss" -> dismiss(p.optJSONArray("keys"))
        "notif.icon" -> appIcon(ctx, p.getString("pkg"))
        else -> throw IllegalArgumentException("Unknown method: $method")
    }

    private fun svc(): NotifListener = NotifListener.instance
        ?: throw IllegalStateException("Notification access is off for Droid Desk Companion")

    private fun list(ctx: Context): JSONArray {
        val s = svc()
        val ranking = try { s.currentRanking } catch (_: Exception) { null }
        val out = JSONArray()
        (s.activeNotifications ?: emptyArray())
            .filter { wanted(ctx, it) }
            .sortedByDescending { it.notification.`when`.takeIf { w -> w > 0 } ?: it.postTime }
            .take(150)
            .forEach {
                synchronized(sent) { sent.add(it.key) }
                synchronized(changedAt) { lastHash[it.key] = hashOf(it) }
                out.put(toJson(s, it, ranking, withIcon = true))
            }
        return out
    }

    private fun find(key: String): StatusBarNotification =
        svc().getActiveNotifications(arrayOf(key))?.firstOrNull() ?: throw IllegalStateException("That notification is no longer on the phone")

    private fun reply(ctx: Context, key: String, index: Int, text: String): JSONObject {
        require(text.isNotBlank()) { "Empty reply" }
        val sbn = find(key)
        // SMS: send it ourselves to the sender's number. Many SMS apps answer by opening a screen,
        // which Android blocks from the background (and behind the lock screen).
        if (sbn.packageName == Telephony.Sms.getDefaultSmsPackage(ctx)) {
            smsAddressFor(ctx, sbn.notification)?.let { to ->
                Api(ctx).call("sms.send", JSONObject().put("to", to).put("body", text))
                return JSONObject().put("via", "sms").put("to", to)
            }
        }
        val a = actionAt(sbn.notification, index) ?: throw IllegalArgumentException("No such button")
        val inputs = a.remoteInputs?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("This notification can't be answered")
        val results = Bundle().apply { for (r in inputs) putCharSequence(r.resultKey, text) }
        val fill = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(inputs, fill, results)
        if (Build.VERSION.SDK_INT >= 28) RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT)
        send(ctx, a.actionIntent, fill)
        return JSONObject().put("via", "app")
            .put("opensScreen", Build.VERSION.SDK_INT >= 31 && a.actionIntent.isActivity)
    }

    /** The number of the SMS a notification shows, found by its text in the inbox. */
    private fun smsAddressFor(ctx: Context, n: Notification): String? {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        val e = n.extras
        @Suppress("DEPRECATION")
        val lastMsg = (e.getParcelableArray(Notification.EXTRA_MESSAGES)?.lastOrNull() as? Bundle)?.getCharSequence("text")
        val texts = listOfNotNull(lastMsg, e.getCharSequence(Notification.EXTRA_BIG_TEXT), e.getCharSequence(Notification.EXTRA_TEXT))
            .map { it.toString() }.filter { it.isNotBlank() }.distinct()
        for (t in texts) {
            ctx.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS),
                "${Telephony.Sms.BODY} = ?", arrayOf(t), "${Telephony.Sms.DATE} DESC")?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return null
    }

    private fun action(ctx: Context, key: String, index: Int): Boolean {
        val a = actionAt(find(key).notification, index) ?: throw IllegalArgumentException("No such button")
        require(a.remoteInputs.isNullOrEmpty()) { "This button needs a reply" }
        send(ctx, a.actionIntent, null)
        return true
    }

    // Buttons are numbered as in the notification; 100+ are the smartwatch ("wearable") ones, where
    // some apps (AOSP Messaging, older chat apps) keep their only Reply.
    private const val WEAR = 100

    @Suppress("DEPRECATION")
    private fun wearActions(n: Notification): List<Notification.Action> =
        try { Notification.WearableExtender(n).actions } catch (_: Exception) { emptyList() }

    private fun actionAt(n: Notification, i: Int): Notification.Action? =
        if (i >= WEAR) wearActions(n).getOrNull(i - WEAR) else n.actions?.getOrNull(i)

    private fun send(ctx: Context, pi: PendingIntent, fill: Intent?) {
        val opts = if (Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle() else null
        try {
            pi.send(ctx, 0, fill, null, null, null, opts)
        } catch (_: PendingIntent.CanceledException) {
            throw IllegalStateException("The app no longer accepts this (the notification changed)")
        }
    }

    private fun dismiss(keys: JSONArray?): JSONObject {
        val s = svc()
        if (keys == null) s.cancelAllNotifications()
        else s.cancelNotifications(Array(keys.length()) { keys.getString(it) })
        return JSONObject().put("ok", true)
    }

    // ---------- JSON ----------

    private fun toJson(ctx: Context, sbn: StatusBarNotification, ranking: NotificationListenerService.RankingMap?, withIcon: Boolean): JSONObject {
        val n = sbn.notification
        val e = n.extras
        val o = JSONObject().put("type", "notif").put("key", sbn.key).put("pkg", sbn.packageName)
            .put("app", appLabel(ctx, sbn.packageName))
            .put("time", n.`when`.takeIf { it > 0 } ?: sbn.postTime)
            .put("clearable", sbn.isClearable)
            .put("category", n.category ?: "")
        val title = e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: e.getCharSequence(Notification.EXTRA_TITLE)
        o.put("title", clip(title, 200))
        o.put("text", clip(e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT), 2000))
        o.put("sub", clip(e.getCharSequence(Notification.EXTRA_SUB_TEXT), 200))
        e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.let { lines ->
            o.put("lines", JSONArray().apply { lines.takeLast(8).forEach { put(clip(it, 500)) } })
        }
        messages(e)?.let { o.put("messages", it) }
        o.put("conversation", Build.VERSION.SDK_INT >= 28 && n.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false))
        val acts = JSONArray()
        n.actions?.forEachIndexed { i, a ->
            val reply = a.remoteInputs?.any { it.allowFreeFormInput } == true
            if (a.title.isNullOrBlank() && !reply) return@forEachIndexed
            acts.put(JSONObject().put("i", i).put("title", clip(a.title, 40)).put("reply", reply))
        }
        if ((0 until acts.length()).none { acts.getJSONObject(it).getBoolean("reply") }) {
            wearActions(n).forEachIndexed { i, a ->
                if (a.remoteInputs?.any { it.allowFreeFormInput } == true)
                    acts.put(JSONObject().put("i", WEAR + i).put("title", clip(a.title ?: "Reply", 40)).put("reply", true))
            }
        }
        o.put("actions", acts)
        if (ranking != null) {
            val r = NotificationListenerService.Ranking()
            if (ranking.getRanking(sbn.key, r)) o.put("importance", r.importance)
        }
        if (withIcon) largeIcon(ctx, n)?.let { o.put("largeIcon", it) }
        return o
    }

    @Suppress("DEPRECATION")
    private fun messages(e: Bundle): JSONArray? {
        val arr = e.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val self = e.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)
            ?: (if (Build.VERSION.SDK_INT >= 28) e.getParcelable<Person>(Notification.EXTRA_MESSAGING_PERSON)?.name else null)
        val out = JSONArray()
        for (p in arr.takeLast(6)) {
            val b = p as? Bundle ?: continue
            val sender = b.getCharSequence("sender")
                ?: (if (Build.VERSION.SDK_INT >= 28) b.getParcelable<Person>("sender_person")?.name else null)
            out.put(JSONObject().put("text", clip(b.getCharSequence("text"), 1000))
                .put("sender", clip(sender ?: self ?: "", 100)).put("me", sender == null)
                .put("time", b.getLong("time")))
        }
        return out.takeIf { it.length() > 0 }
    }

    private fun clip(s: CharSequence?, max: Int): String = s?.toString()?.let { if (it.length > max) it.take(max) + "…" else it } ?: ""

    private val labels = HashMap<String, String>()
    private fun appLabel(ctx: Context, pkg: String): String = synchronized(labels) {
        labels.getOrPut(pkg) {
            try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
        }
    }

    private fun largeIcon(ctx: Context, n: Notification): String? = try {
        n.getLargeIcon()?.loadDrawable(ctx)?.let { png(it, 96) }
    } catch (_: Exception) { null }

    private fun appIcon(ctx: Context, pkg: String): JSONObject = JSONObject()
        .put("pkg", pkg).put("app", appLabel(ctx, pkg))
        .put("icon", try { png(ctx.packageManager.getApplicationIcon(pkg), 96) } catch (_: Exception) { JSONObject.NULL })

    private fun png(d: Drawable, size: Int): String {
        val bmp = if (d is BitmapDrawable && d.bitmap != null) Bitmap.createScaledBitmap(d.bitmap, size, size, true)
        else Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { b ->
            d.setBounds(0, 0, size, size); d.draw(Canvas(b))
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
