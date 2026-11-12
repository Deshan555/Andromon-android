// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

package dev.droiddesk.companion

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Photos, videos and music for Droid Desk's Photos and Music tabs: Android's media index
 * (MediaStore), thumbnails, deleting, indexing files the PC pushed, and finding duplicates.
 * File contents never go through here; the PC copies them over adb directly using `path`.
 */
internal class Media(private val ctx: Context) {

    private val cr = ctx.contentResolver

    fun call(method: String, p: JSONObject): Any = when (method) {
        "media.list" -> list(kind(p), p.optBoolean("allAudio"))
        "media.thumbs" -> thumbs(kind(p), p.getJSONArray("ids"), p.optInt("size", 256).coerceIn(64, 1024))
        "media.delete" -> delete(kind(p), p.getJSONArray("ids"))
        "media.scan" -> scan(p.getJSONArray("paths").let { a -> List(a.length()) { a.getString(it) } })
        "media.duplicates" -> duplicates(kind(p))
        else -> throw IllegalArgumentException("Unknown method: $method")
    }

    private fun kind(p: JSONObject): String = p.optString("kind").also {
        require(it == "image" || it == "video" || it == "audio") { "kind must be image, video or audio" }
    }

    private fun collection(kind: String): Uri {
        val all = Build.VERSION.SDK_INT >= 29 // every volume, SD cards included
        return when (kind) {
            "image" -> if (all) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "video" -> if (all) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> if (all) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
    }

    private fun uri(kind: String, id: Long) = ContentUris.withAppendedId(collection(kind), id)

    /** Whether the companion may read photos/videos ([kind] image or video) or music. */
    fun canRead(kind: String): Boolean {
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) return true
        val perm = when {
            Build.VERSION.SDK_INT < 33 -> Manifest.permission.READ_EXTERNAL_STORAGE
            kind == "image" -> Manifest.permission.READ_MEDIA_IMAGES
            kind == "video" -> Manifest.permission.READ_MEDIA_VIDEO
            else -> Manifest.permission.READ_MEDIA_AUDIO
        }
        return ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun requireRead(kind: String) {
        if (!canRead(kind)) throw SecurityException("no access to ${if (kind == "audio") "music" else "photos and videos"}")
    }

    // ---------- list ----------

    @Suppress("DEPRECATION") // DATA (the file path) is deprecated but still filled in, and the PC needs it for adb
    private fun list(kind: String, allAudio: Boolean): JSONArray {
        requireRead(kind)
        val cols = mutableListOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATA,
        )
        when (kind) {
            "image" -> cols += listOf(MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.ORIENTATION)
            "video" -> cols += listOf(MediaStore.Video.Media.DATE_TAKEN, MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.DURATION)
            else -> cols += listOf(MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.IS_MUSIC)
        }
        val selection = if (kind == "audio" && !allAudio) "${MediaStore.Audio.Media.IS_MUSIC} != 0" else null
        val out = JSONArray()
        cr.query(collection(kind), cols.toTypedArray(), selection, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
            val ix = cols.associateWith { c.getColumnIndex(it) }
            fun str(col: String) = ix[col]?.takeIf { it >= 0 && !c.isNull(it) }?.let { c.getString(it) }
            fun num(col: String) = ix[col]?.takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
            while (c.moveToNext()) {
                val o = JSONObject()
                    .put("id", num(MediaStore.MediaColumns._ID))
                    .put("name", str(MediaStore.MediaColumns.DISPLAY_NAME))
                    .put("size", num(MediaStore.MediaColumns.SIZE) ?: 0)
                    .put("mime", str(MediaStore.MediaColumns.MIME_TYPE))
                    .put("path", str(MediaStore.MediaColumns.DATA))
                val modified = (num(MediaStore.MediaColumns.DATE_MODIFIED) ?: 0) * 1000
                if (kind == "audio") {
                    o.put("title", str(MediaStore.Audio.Media.TITLE)).put("artist", str(MediaStore.Audio.Media.ARTIST)?.takeUnless { it == "<unknown>" })
                        .put("album", str(MediaStore.Audio.Media.ALBUM)).put("albumId", num(MediaStore.Audio.Media.ALBUM_ID))
                        .put("duration", num(MediaStore.Audio.Media.DURATION)).put("track", num(MediaStore.Audio.Media.TRACK))
                        .put("year", num(MediaStore.Audio.Media.YEAR)?.takeIf { it > 0 })
                        .put("music", num(MediaStore.Audio.Media.IS_MUSIC)?.let { it != 0L } ?: true)
                        .put("date", modified)
                } else {
                    val taken = num(MediaStore.Images.Media.DATE_TAKEN)?.takeIf { it > 0 }
                    o.put("date", taken ?: modified)
                        .put("album", str(MediaStore.Images.Media.BUCKET_DISPLAY_NAME))
                        .put("width", num(MediaStore.Images.Media.WIDTH)).put("height", num(MediaStore.Images.Media.HEIGHT))
                    if (kind == "image") o.put("orientation", num(MediaStore.Images.Media.ORIENTATION))
                    else o.put("duration", num(MediaStore.Video.Media.DURATION))
                }
                out.put(o)
            }
        }
        return out
    }

    // ---------- thumbnails ----------

    /** {id: base64 JPEG} for up to 60 items; missing ids have no thumbnail (e.g. no album art). */
    private fun thumbs(kind: String, ids: JSONArray, size: Int): JSONObject {
        requireRead(kind)
        val list = List(minOf(ids.length(), 60)) { ids.getLong(it) }
        val result = ConcurrentHashMap<Long, String>()
        val pool = Executors.newFixedThreadPool(4)
        try {
            for (id in list) pool.execute { thumb(kind, id, size)?.let { result[id] = it } }
            pool.shutdown()
            pool.awaitTermination(60, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
        return JSONObject().apply { result.forEach { (k, v) -> put(k.toString(), v) } }
    }

    @Suppress("DEPRECATION")
    private fun thumb(kind: String, id: Long, size: Int): String? = try {
        val bmp: Bitmap? = if (Build.VERSION.SDK_INT >= 29) {
            cr.loadThumbnail(uri(kind, id), Size(size, size), null)
        } else when (kind) {
            "image" -> MediaStore.Images.Thumbnails.getThumbnail(cr, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
            "video" -> MediaStore.Video.Thumbnails.getThumbnail(cr, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
            else -> null
        }
        bmp?.let {
            val scale = size.toFloat() / maxOf(it.width, it.height)
            val b = if (scale < 1f) Bitmap.createScaledBitmap(it, (it.width * scale).toInt().coerceAtLeast(1), (it.height * scale).toInt().coerceAtLeast(1), true) else it
            val out = ByteArrayOutputStream()
            b.compress(Bitmap.CompressFormat.JPEG, 78, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    } catch (_: Exception) {
        null
    }

    // ---------- delete / index ----------

    /** Deletes items. Returns {deleted, failed:[{id, path}]}; the PC can remove failed paths itself. */
    @Suppress("DEPRECATION")
    private fun delete(kind: String, ids: JSONArray): JSONObject {
        requireRead(kind)
        var deleted = 0
        val failed = JSONArray()
        val rescan = ArrayList<String>()
        for (i in 0 until ids.length()) {
            val id = ids.getLong(i)
            val u = uri(kind, id)
            val path = cr.query(u, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            val ok = try {
                cr.delete(u, null, null) > 0
            } catch (_: SecurityException) {
                // Another app's file without "All files access": delete the file itself if allowed.
                path != null && File(path).let { f -> f.delete() || !f.exists() }.also { if (it) rescan += path }
            } catch (_: Exception) {
                false
            }
            if (ok) deleted++ else failed.put(JSONObject().put("id", id).put("path", path))
        }
        if (rescan.isNotEmpty()) scan(rescan)
        return JSONObject().put("deleted", deleted).put("failed", failed)
    }

    /** Indexes (or un-indexes, if gone) files so they show up in galleries and music apps. {path: id or null} */
    private fun scan(paths: List<String>): JSONObject {
        val result = JSONObject()
        if (paths.isEmpty()) return result
        val done = CountDownLatch(paths.size)
        MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null) { path, uri ->
            synchronized(result) { result.put(path, uri?.let { runCatching { ContentUris.parseId(it) }.getOrNull() } ?: JSONObject.NULL) }
            done.countDown()
        }
        done.await(60, TimeUnit.SECONDS)
        return result
    }

    // ---------- duplicates ----------

    /**
     * Groups of identical files: same size, then same first and last 64 KB, then same SHA-1.
     * Each group lists ids oldest first (the one to keep).
     */
    private fun duplicates(kind: String): JSONArray {
        requireRead(kind)
        class F(val id: Long, val size: Long, val date: Long)
        val all = ArrayList<F>()
        cr.query(collection(kind), arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED),
            "${MediaStore.MediaColumns.SIZE} > 0", null, null)?.use { c ->
            while (c.moveToNext()) all += F(c.getLong(0), c.getLong(1), c.getLong(2))
        }
        val out = JSONArray()
        for ((size, same) in all.groupBy { it.size }) {
            if (same.size < 2) continue
            for (quick in same.groupBy { hash(kind, it.id, quick = true) }.filterKeys { it != null }.values) {
                if (quick.size < 2) continue
                for (full in quick.groupBy { hash(kind, it.id, quick = false) }.filterKeys { it != null }.values) {
                    if (full.size < 2) continue
                    out.put(JSONObject().put("size", size).put("ids", JSONArray(full.sortedBy { it.date }.map { it.id })))
                }
            }
        }
        return out
    }

    private fun hash(kind: String, id: Long, quick: Boolean): String? = try {
        cr.openFileDescriptor(uri(kind, id), "r")?.use { pfd ->
            val md = MessageDigest.getInstance(if (quick) "MD5" else "SHA-1")
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val buf = ByteBuffer.allocate(64 * 1024)
                fun readAt(pos: Long) { buf.clear(); ch.position(pos); while (buf.hasRemaining() && ch.read(buf) > 0) {}; buf.flip(); md.update(buf) }
                if (quick) {
                    readAt(0)
                    if (ch.size() > buf.capacity()) readAt(ch.size() - buf.capacity())
                } else {
                    ch.position(0)
                    while (true) { buf.clear(); if (ch.read(buf) < 0) break; buf.flip(); md.update(buf) }
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (_: Exception) {
        null
    }
}
