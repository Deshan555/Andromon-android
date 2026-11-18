// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Media remote: what the phone is playing (any app with a media session: music, podcasts,
 * video) and play / pause / next / previous / seek from the PC.
 *
 * Android only shows other apps' media sessions to a notification listener, so this needs the
 * same "Notification access" as phone notifications ([NotifListener]).
 *
 * - `player.now` → `{app, pkg, title, artist, album, state, position, duration, canSeek, art?}`
 *   or null when nothing is playing. `state`: playing, paused, buffering or stopped; `position`
 *   and `duration` in ms; `art`: a small JPEG, base64.
 * - `player.control {action: play|pause|toggle|next|prev|seek, position?, pkg?}` → true
 * - On the `events` stream, when the PC asked with `media: true`: `{type:"media", ...same}` when
 *   the track or the playback state changes or the position jumps, and
 *   `{type:"media", state:"none"}` when nothing is playing any more. The current state is sent
 *   once at the start.
 *
 * (The names are `player.*` because `media.*` is the photo and music index, see [Media].)
 */
internal object Player {

    private const val NO_ACCESS = "Notification access is off for Andromon Companion"
    private const val ART_PX = 160

    private val listeners = CopyOnWriteArrayList<(JSONObject) -> Unit>()
    private val handler by lazy { Handler(HandlerThread("player-watch").apply { start() }.looper) }

    // Everything below is touched only on the handler thread.
    private var appCtx: Context? = null
    private var watching = false
    private var controllers: List<MediaController> = emptyList()
    private val callbacks = HashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var lastKey: String? = null
    private var lastPos = 0L
    private var lastAt = 0L
    private var lastMoving = false
    private var artKey: String? = null
    private var artData: String? = null

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        attach(list.orEmpty())
        soon()
    }
    private val report = Runnable { emit(force = false) }

    private fun component(ctx: Context) = ComponentName(ctx, NotifListener::class.java)

    private fun sessions(ctx: Context): List<MediaController> = try {
        ctx.getSystemService(MediaSessionManager::class.java).getActiveSessions(component(ctx))
    } catch (_: SecurityException) {
        throw IllegalStateException(NO_ACCESS)
    }

    // ---------- PC requests ----------

    fun call(ctx: Context, method: String, p: JSONObject): Any = when (method) {
        "player.now" -> {
            val c = pick(sessions(ctx))
            // Notification access may have been given after the PC started listening.
            if (listeners.isNotEmpty()) handler.post { startWatching() }
            if (c == null) JSONObject.NULL else json(ctx, c, cacheArt = false)
        }
        "player.control" -> control(ctx, p)
        else -> throw IllegalArgumentException("Unknown method: $method")
    }

    private fun control(ctx: Context, p: JSONObject): Boolean {
        val all = sessions(ctx)
        val pkg = p.optString("pkg")
        val c = (if (pkg.isNotEmpty()) all.firstOrNull { it.packageName == pkg } else null) ?: pick(all)
            ?: throw IllegalStateException("Nothing is playing on the phone")
        val t = c.transportControls
        when (val action = p.getString("action")) {
            "play" -> t.play()
            "pause" -> t.pause()
            "toggle" -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            "next" -> t.skipToNext()
            "prev" -> t.skipToPrevious()
            "seek" -> t.seekTo(p.getLong("position").coerceAtLeast(0))
            else -> throw IllegalArgumentException("Unknown action: $action")
        }
        return true
    }

    // ---------- events ----------

    fun add(ctx: Context, listener: (JSONObject) -> Unit) {
        listeners.add(listener)
        val app = ctx.applicationContext
        handler.post {
            appCtx = app
            startWatching()
            if (watching) {
                // Tell the newcomer where things stand.
                val c = pick(controllers)
                listener(if (c == null) none() else json(app, c, cacheArt = true).put("type", "media"))
            }
        }
    }

    fun remove(listener: (JSONObject) -> Unit) {
        if (!listeners.remove(listener)) return
        handler.post { if (listeners.isEmpty()) stopWatching() }
    }

    private fun startWatching() {
        val ctx = appCtx ?: return
        if (watching || listeners.isEmpty()) return
        try {
            val msm = ctx.getSystemService(MediaSessionManager::class.java)
            msm.addOnActiveSessionsChangedListener(sessionsChanged, component(ctx), handler)
            watching = true
            attach(msm.getActiveSessions(component(ctx)))
            lastKey = null
        } catch (_: SecurityException) {
            // No notification access yet. `player.now` tries again once the PC has turned it on.
            stopWatching()
        }
    }

    private fun stopWatching() {
        handler.removeCallbacks(report)
        val ctx = appCtx
        if (ctx != null) {
            try { ctx.getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessionsChanged) } catch (_: Exception) {}
        }
        attach(emptyList())
        watching = false
        lastKey = null
        artKey = null
        artData = null
    }

    private fun attach(list: List<MediaController>) {
        val tokens = list.map { it.sessionToken }.toSet()
        val gone = callbacks.keys.filter { it !in tokens }
        for (t in gone) callbacks.remove(t)?.let { (c, cb) -> try { c.unregisterCallback(cb) } catch (_: Exception) {} }
        for (c in list) {
            if (callbacks.containsKey(c.sessionToken)) continue
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = soon()
                override fun onMetadataChanged(metadata: MediaMetadata?) = soon()
                override fun onSessionDestroyed() = soon()
            }
            try {
                c.registerCallback(cb, handler)
                callbacks[c.sessionToken] = c to cb
            } catch (_: Exception) {
            }
        }
        // Keep Android's order (most relevant first), with the controllers our callbacks are on.
        controllers = list.mapNotNull { callbacks[it.sessionToken]?.first }
    }

    /** A track change arrives as several callbacks in a row: report once they have settled. */
    private fun soon() {
        handler.removeCallbacks(report)
        handler.postDelayed(report, 150)
    }

    private fun emit(force: Boolean) {
        val ctx = appCtx ?: return
        if (!watching || listeners.isEmpty()) return
        val c = pick(controllers)
        val now = SystemClock.elapsedRealtime()
        if (c == null) {
            if (lastKey == "none" && !force) return
            lastKey = "none"
            lastMoving = false
            val o = none()
            for (l in listeners) l(o)
            return
        }
        val o = json(ctx, c, cacheArt = true).put("type", "media")
        val key = listOf(o.optString("pkg"), o.optString("title"), o.optString("artist"), o.optString("album"),
            o.optString("state"), o.optLong("duration"), o.has("art")).joinToString("\u0001")
        val pos = o.optLong("position")
        val expected = if (lastMoving) lastPos + (now - lastAt) else lastPos
        val jumped = abs(pos - expected) > 1500
        if (!force && key == lastKey && !jumped) return
        lastKey = key
        lastPos = pos
        lastAt = now
        lastMoving = o.optString("state") == "playing"
        for (l in listeners) l(o)
    }

    // ---------- JSON ----------

    private fun none() = JSONObject().put("type", "media").put("state", "none")

    /** The session that is playing, else the first one that has something loaded. */
    private fun pick(list: List<MediaController>): MediaController? =
        list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull {
                val s = it.playbackState?.state ?: PlaybackState.STATE_NONE
                it.metadata != null && s != PlaybackState.STATE_NONE && s != PlaybackState.STATE_STOPPED && s != PlaybackState.STATE_ERROR
            }

    private fun json(ctx: Context, c: MediaController, cacheArt: Boolean): JSONObject {
        val md = c.metadata
        val st = c.playbackState
        fun text(vararg keys: String): String {
            for (k in keys) md?.getText(k)?.toString()?.takeIf { it.isNotBlank() }?.let { return clip(it) }
            return ""
        }
        val state = when (st?.state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING, PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING, PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "buffering"
            else -> "stopped"
        }
        val duration = (md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0)
        var position = st?.position?.coerceAtLeast(0) ?: 0L
        if (st != null && st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0) {
            position += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong().coerceAtLeast(0)
        }
        if (duration > 0) position = position.coerceAtMost(duration)
        val o = JSONObject()
            .put("app", appLabel(ctx, c.packageName))
            .put("pkg", c.packageName)
            .put("title", text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
            .put("artist", text(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
            .put("album", text(MediaMetadata.METADATA_KEY_ALBUM))
            .put("state", state)
            .put("position", position)
            .put("duration", duration)
            .put("canSeek", st != null && st.actions and PlaybackState.ACTION_SEEK_TO != 0L)
        art(md, o, cacheArt)?.let { o.put("art", it) }
        return o
    }

    private fun clip(s: String) = if (s.length > 300) s.take(300) + "…" else s

    private fun appLabel(ctx: Context, pkg: String): String =
        try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }

    /** The cover as a small JPEG. On the watch thread the last one is kept, as most events are for the same track. */
    private fun art(md: MediaMetadata?, o: JSONObject, cache: Boolean): String? {
        val bmp = try {
            md?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        } catch (_: Exception) { null } ?: return null
        val key = listOf(o.optString("pkg"), o.optString("title"), o.optString("album"), bmp.width, bmp.height).joinToString("\u0001")
        if (cache && key == artKey) return artData
        val data = try {
            val w = bmp.width
            val h = bmp.height
            if (w <= 0 || h <= 0 || bmp.isRecycled) return null
            val scale = ART_PX.toFloat() / maxOf(w, h)
            // Hardware bitmaps can't be drawn by the software scaler: copy them first.
            val soft = if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) ?: return null else bmp
            val small = if (scale < 1f) Bitmap.createScaledBitmap(soft, maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()), true) else soft
            val out = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 80, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
        if (cache) { artKey = key; artData = data }
        return data
    }
}
