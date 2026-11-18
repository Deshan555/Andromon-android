// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Small events the phone sends to every PC on the `events` stream, whatever it asked for:
 * `{type:"clip", text}` (the phone's clipboard, see [ClipSendActivity]) and
 * `{type:"ring", on:false, reason}` (Find my phone stopped, see [Ringer]).
 */
internal object LinkEvents {
    private val listeners = CopyOnWriteArrayList<(JSONObject) -> Unit>()

    fun add(listener: (JSONObject) -> Unit) { listeners.add(listener) }
    fun remove(listener: (JSONObject) -> Unit) { listeners.remove(listener) }

    /** True while at least one PC has the `events` stream open. */
    val listening: Boolean get() = listeners.isNotEmpty()

    fun emit(o: JSONObject) { for (l in listeners) l(o) }
}

/**
 * "Phone links" the PC can use: send a link or text to the phone (`push.send`), set the phone's
 * clipboard (`clip.set`) and ring the phone to find it (`phone.ring`).
 *
 * Clipboard contents and pushed text are never logged.
 */
internal object Links {

    const val ACTION_COPY = "dev.andromon.companion.link.COPY"
    const val ACTION_FOUND = "dev.andromon.companion.link.FOUND"
    const val EXTRA_TEXT = "text"

    /** Longest text taken in one go: clipboards and intents travel through Binder (1 MB in all). */
    const val MAX_CLIP = 200_000
    private const val MAX_PUSH = 20_000
    private const val CH_PUSH = "from_pc"
    private const val CH_PUSH_QUIET = "from_pc_opened"
    private const val ID_BASE = 1000
    private val seq = AtomicInteger((System.currentTimeMillis() / 1000 % 500_000_000L).toInt())
    private val main = Handler(Looper.getMainLooper())
    private val link = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

    fun call(ctx: Context, method: String, p: JSONObject): Any = when (method) {
        "push.send" -> push(ctx.applicationContext, p.getString("text"))
        "clip.set" -> clipSet(ctx.applicationContext, p.getString("text"))
        "phone.ring" -> Ringer.set(ctx.applicationContext, p.optBoolean("on", true))
        else -> throw IllegalArgumentException("Unknown method: $method")
    }

    /** Runs [block] on the main thread (clipboard, players and toasts want a Looper) and waits. */
    fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask(block)
        main.post(task)
        return try { task.get(8, TimeUnit.SECONDS) } catch (e: ExecutionException) { throw e.cause ?: e }
    }

    // ---------- send a link or text to the phone ----------

    private fun push(ctx: Context, raw: String): JSONObject {
        val text = raw.trim()
        require(text.isNotEmpty()) { "Nothing to send" }
        require(text.length <= MAX_PUSH) { "Too long to send (the limit is $MAX_PUSH characters)" }
        val url = if (link.matches(text)) Uri.parse(text) else null

        // A link opens straight away when Android lets a background app do that: the phone is
        // unlocked and the companion is on screen or may "display over other apps" (Andromon
        // allows that over adb). Android drops the request silently otherwise, so the
        // notification below is always posted too.
        var opened = false
        if (url != null && !ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            val allowed = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(ctx)
            opened = try { ctx.startActivity(view(url)); allowed } catch (_: Exception) { false }
        }

        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PUSH, "From your computer", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Links and text sent from Andromon"
        })
        nm.createNotificationChannel(NotificationChannel(CH_PUSH_QUIET, "From your computer (already opened)", NotificationManager.IMPORTANCE_LOW))
        val id = ID_BASE + seq.incrementAndGet() % 1_000_000_000
        // A data URI of its own keeps each notification's Copy apart from the others'.
        val copy = PendingIntent.getBroadcast(
            ctx, id,
            Intent(ctx, LinkReceiver::class.java).setAction(ACTION_COPY).setData(Uri.parse("andromon-push:$id")).putExtra(EXTRA_TEXT, text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val tap = if (url != null) PendingIntent.getActivity(ctx, id, view(url), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) else copy
        nm.notify(id, Notification.Builder(ctx, if (opened) CH_PUSH_QUIET else CH_PUSH)
            .setSmallIcon(R.drawable.ic_desktop)
            .setContentTitle(if (url != null) "Link from your computer" else "Text from your computer")
            .setContentText(text.take(300))
            .setSubText(if (url != null) "Tap to open" else "Tap to copy")
            .setStyle(Notification.BigTextStyle().bigText(text.take(3000)))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Copy", copy).build())
            .build())
        return JSONObject().put("link", url != null).put("opened", opened).put("notified", nm.areNotificationsEnabled())
    }

    private fun view(url: Uri) = Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ---------- clipboard ----------

    private fun clipSet(ctx: Context, text: String): JSONObject {
        require(text.length <= MAX_CLIP) { "Too long for the phone's clipboard (the limit is $MAX_CLIP characters)" }
        setClipboard(ctx, text)
        return JSONObject().put("length", text.length)
    }

    /** Apps may always write the clipboard, also from the background. */
    fun setClipboard(ctx: Context, text: String) = onMain {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Andromon", text))
    }

    private fun clipIntent(ctx: Context) = Intent(ctx, ClipSendActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

    /** Opens [ClipSendActivity]; for notification buttons and quick-settings tiles. */
    fun clipPending(ctx: Context): PendingIntent =
        PendingIntent.getActivity(ctx, 20, clipIntent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** The "Send clipboard to PC" button on the "Connected to Andromon" notification. */
    fun clipAction(ctx: Context): Notification.Action =
        Notification.Action.Builder(null, "Send clipboard to PC", clipPending(ctx)).build()
}

/** Buttons on this file's notifications. Not exported: only our own PendingIntents reach it. */
class LinkReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Links.ACTION_COPY -> {
                val text = intent.getStringExtra(Links.EXTRA_TEXT) ?: return
                try {
                    Links.setClipboard(ctx.applicationContext, text)
                    // Android 13+ shows its own "copied" preview.
                    if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx.applicationContext, "Copied", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                }
            }
            Links.ACTION_FOUND -> Ringer.stop(ctx.applicationContext, "found")
        }
    }
}

/**
 * Find my phone: plays the ringtone on the alarm stream at full volume (alarms also sound in
 * silent mode and in Do Not Disturb, unless that is set to total silence), vibrates and shows a
 * "Found it" notification that opens [RingActivity] over the lock screen. Stops on "Found it",
 * when the PC says so, when the PC link stops, or after two minutes; the alarm volume is then put
 * back. Everything here runs on the main thread.
 */
internal object Ringer {
    private const val CHANNEL = "find_phone"
    private const val NOTE = 40
    private const val MAX_MS = 2 * 60_000L
    private const val TAG = "AndromonRing"

    @Volatile var ringing = false
        private set

    /** Set by [RingActivity] while it is open, so it closes when the ringing stops. */
    var onStop: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    private var appCtx: Context? = null
    private val timeout = Runnable { appCtx?.let { stopNow(it, "timeout") } }

    fun set(ctx: Context, on: Boolean): JSONObject {
        var sound = true
        Links.onMain {
            if (on) { sound = start(ctx) } else { stopNow(ctx, "pc") }
        }
        return JSONObject().put("ringing", ringing).apply { if (on) put("sound", sound).put("seconds", MAX_MS / 1000) }
    }

    fun stop(ctx: Context, reason: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) stopNow(ctx, reason) else main.post { stopNow(ctx, reason) }
    }

    /** Returns false when no sound could be played (it still vibrates and shows the notification). */
    private fun start(ctx: Context): Boolean {
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, MAX_MS)
        if (ringing) return player != null
        appCtx = ctx
        val am = ctx.getSystemService(AudioManager::class.java)
        savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        try {
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: SecurityException) { // Do Not Disturb doesn't let us; ring at the current volume
        }
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        player = startSound(ctx, attrs)
        try {
            val v = ctx.getSystemService(Vibrator::class.java)
            if (v != null && v.hasVibrator()) {
                @Suppress("DEPRECATION")
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0), attrs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't vibrate: ${e.javaClass.simpleName}")
        }
        ringing = true
        showNotification(ctx)
        return player != null
    }

    private fun startSound(ctx: Context, attrs: AudioAttributes): MediaPlayer? {
        val sounds = listOf(
            try { RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_RINGTONE) } catch (_: Exception) { null },
            try { RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM) } catch (_: Exception) { null },
            Settings.System.DEFAULT_RINGTONE_URI,
            Settings.System.DEFAULT_ALARM_ALERT_URI,
            Settings.System.DEFAULT_NOTIFICATION_URI,
        ).filterNotNull().distinct()
        for (uri in sounds) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(attrs)
                mp.setDataSource(ctx, uri)
                mp.isLooping = true
                mp.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK) // keeps the two-minute limit ticking with the screen off
                mp.prepare()
                mp.start()
                return mp
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't play a sound: ${e.javaClass.simpleName}")
                try { mp.release() } catch (_: Exception) {}
            }
        }
        return null
    }

    private fun stopNow(ctx: Context, reason: String) {
        main.removeCallbacks(timeout)
        if (!ringing) return
        ringing = false
        player?.let { mp -> try { mp.stop() } catch (_: Exception) {}; try { mp.release() } catch (_: Exception) {} }
        player = null
        try { ctx.getSystemService(Vibrator::class.java)?.cancel() } catch (_: Exception) {}
        if (savedVolume >= 0) {
            try { ctx.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) } catch (_: Exception) {}
            savedVolume = -1
        }
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTE)
        onStop?.invoke()
        LinkEvents.emit(JSONObject().put("type", "ring").put("on", false).put("reason", reason))
    }

    private fun showNotification(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Find my phone", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Shown while Andromon rings this phone"
            setSound(null, null) // the ringing is played on the alarm stream instead
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        val open = PendingIntent.getActivity(
            ctx, 30,
            Intent(ctx, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val found = PendingIntent.getBroadcast(
            ctx, 31, Intent(ctx, LinkReceiver::class.java).setAction(Links.ACTION_FOUND), PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(NOTE, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_desktop)
            .setContentTitle("Andromon is ringing this phone")
            .setContentText("Tap “Found it” to stop")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setTimeoutAfter(MAX_MS + 10_000)
            .addAction(Notification.Action.Builder(null, "Found it", found).build())
            .build())
    }
}

/** The screen behind the Find my phone notification: one big "Found it" button, also over the lock screen. */
class RingActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Ringer.ringing) { finish(); return }
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
        val title = TextView(this).apply {
            text = "Andromon is\nlooking for this phone"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTextColor(getColor(R.color.ink))
            gravity = Gravity.CENTER
        }
        val note = TextView(this).apply {
            text = "Someone clicked “Ring my phone” on your computer. It stops by itself after two minutes."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(getColor(R.color.ink_soft))
            gravity = Gravity.CENTER
        }
        val button = TextView(this).apply {
            text = "Found it"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.on_fab))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(48).toFloat(); setColor(getColor(R.color.fab)) }
            isClickable = true
            isFocusable = true
            contentDescription = "Found it: stop ringing"
            setOnClickListener { Ringer.stop(applicationContext, "found"); finish() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(getColor(R.color.lavender))
            setPadding(dp(28), dp(48), dp(28), dp(48))
            addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(note, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
            addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(96)).apply { topMargin = dp(48) })
        })
        Ringer.onStop = { if (!isFinishing) finish() }
    }

    override fun onDestroy() {
        Ringer.onStop = null
        super.onDestroy()
    }
}
