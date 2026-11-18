// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

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
 * Photos, videos and music for Andromon's Photos and Music tabs: Android's media index
 * (MediaStore), thumbnails, deleting, indexing files the PC pushed, and finding duplicates.
 * File contents never go through here; the PC copies them over adb directly using `path`.
 */
internal class Media(private val ctx: Context) {

    private companion object {
        /** Most images compared in one go (the newest); more would keep the PC waiting too long. */
        const val SIMILAR_MAX = 20_000
        /** Perceptual hashes by id, kept while the companion runs so a second search is quick. */
        val hashes = ConcurrentHashMap<Long, PHash>()
    }

    /** [hash] null: the thumbnail couldn't be read. Valid while the file's [modified] time is the same. */
    private class PHash(val modified: Long, val hash: Long?)

    private val cr = ctx.contentResolver

    fun call(method: String, p: JSONObject): Any = when (method) {
        "media.list" -> list(kind(p), p.optBoolean("allAudio"))
        "media.thumbs" -> thumbs(kind(p), p.getJSONArray("ids"), p.optInt("size", 256).coerceIn(64, 1024))
        "media.delete" -> delete(kind(p), p.getJSONArray("ids"))
        "media.scan" -> scan(p.getJSONArray("paths").let { a -> List(a.length()) { a.getString(it) } })
        "media.duplicates" -> duplicates(kind(p))
        "media.similar" -> similar(kind(p), p.optInt("threshold", 10).coerceIn(0, 32))
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

    // ---------- similar photos ----------

    /**
     * Groups of photos that look alike: the same picture saved again, resized, re-compressed or
     * slightly edited, and burst shots. Each photo gets a 64-bit "difference hash" of its small
     * thumbnail (see [dHash]); photos whose hashes differ in at most [threshold] bits are joined,
     * and so are chains of them. Exact copies are found too.
     *
     * Each group: {ids (oldest first, like media.duplicates), totalSize, items:[{id, size, date,
     * width, height}]}, the groups with the most bytes first. Only the newest [SIMILAR_MAX]
     * photos are compared.
     */
    private fun similar(kind: String, threshold: Int): JSONArray {
        require(kind == "image") { "only photos (kind image) can be compared" }
        requireRead(kind)
        class F(val id: Long, val size: Long, val modified: Long, val date: Long, val width: Long, val height: Long)
        val all = ArrayList<F>()
        cr.query(collection(kind), arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT),
            "${MediaStore.MediaColumns.SIZE} > 0", null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
            while (c.moveToNext() && all.size < SIMILAR_MAX) {
                val modified = c.getLong(2)
                // Same "date" as media.list: when it was taken, else when the file last changed.
                val taken = if (c.isNull(3)) 0 else c.getLong(3)
                all += F(c.getLong(0), c.getLong(1), modified, if (taken > 0) taken else modified * 1000, c.getLong(4), c.getLong(5))
            }
        }

        // Thumbnails come from MediaStore's cache, so most of the time goes to waiting on it:
        // a few at once is quicker. Hashes already worked out for an unchanged file are reused.
        val pool = Executors.newFixedThreadPool(4)
        try {
            for (f in all) {
                if (hashes[f.id]?.modified == f.modified) continue
                pool.execute { hashes[f.id] = PHash(f.modified, dHash(f.id)) }
            }
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.MINUTES)
        } finally {
            pool.shutdownNow()
        }

        val items = all.filter { hashes[it.id]?.modified == it.modified && hashes[it.id]?.hash != null }
        val h = LongArray(items.size) { hashes[items[it].id]!!.hash!! }
        // Long side over short side (0 when unknown), the same however the photo is turned. Plain or
        // smooth pictures (a gradient, a white page) have nearly empty hashes and matched pictures of
        // another shape; copies, resized photos and burst shots keep their shape.
        val shape = FloatArray(items.size) { i ->
            val a = maxOf(items[i].width, items[i].height); val b = minOf(items[i].width, items[i].height)
            if (b > 0) a.toFloat() / b else 0f
        }
        fun sameShape(i: Int, j: Int) = shape[i] == 0f || shape[j] == 0f ||
            kotlin.math.abs(shape[i] - shape[j]) <= 0.1f * minOf(shape[i], shape[j])
        // Union-find over every pair: 20 000 photos are 200 million cheap bit counts, a few seconds.
        val parent = IntArray(items.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }
            return r
        }
        for (i in h.indices) for (j in i + 1 until h.size) {
            if (java.lang.Long.bitCount(h[i] xor h[j]) <= threshold && sameShape(i, j)) {
                val a = root(i); val b = root(j)
                if (a != b) parent[b] = a
            }
        }
        val out = JSONArray()
        items.indices.groupBy(::root).values.filter { it.size > 1 }
            .map { g -> g.map { items[it] }.sortedBy { it.date } }
            .sortedByDescending { g -> g.sumOf { it.size } }
            .forEach { g ->
                out.put(JSONObject()
                    .put("ids", JSONArray(g.map { it.id }))
                    .put("totalSize", g.sumOf { it.size })
                    .put("items", JSONArray(g.map {
                        JSONObject().put("id", it.id).put("size", it.size).put("date", it.date).put("width", it.width).put("height", it.height)
                    })))
            }
        return out
    }

    /**
     * A 64-bit difference hash of a photo: its thumbnail shrunk to 9×8 grey squares, one bit per
     * pair of neighbours in a row (is the left one brighter?). Resizing, re-compressing and small
     * colour changes keep most bits; a different picture changes about half of them.
     */
    @Suppress("DEPRECATION")
    private fun dHash(id: Long): Long? = try {
        val bmp: Bitmap? = if (Build.VERSION.SDK_INT >= 29) cr.loadThumbnail(uri("image", id), Size(64, 64), null)
            else MediaStore.Images.Thumbnails.getThumbnail(cr, id, MediaStore.Images.Thumbnails.MICRO_KIND, null)
        bmp?.let { b ->
            val w = b.width; val hgt = b.height
            val px = IntArray(w * hgt)
            b.getPixels(px, 0, w, 0, 0, w, hgt)
            b.recycle()
            // Average the brightness over each of the 9×8 areas (more even than scaling the bitmap).
            val grey = DoubleArray(72)
            for (cy in 0 until 8) for (cx in 0 until 9) {
                val x0 = cx * w / 9; val x1 = maxOf(x0 + 1, (cx + 1) * w / 9)
                val y0 = cy * hgt / 8; val y1 = maxOf(y0 + 1, (cy + 1) * hgt / 8)
                var sum = 0.0
                for (y in y0 until minOf(y1, hgt)) for (x in x0 until minOf(x1, w)) {
                    val c = px[y * w + x]
                    sum += 0.299 * (c shr 16 and 0xff) + 0.587 * (c shr 8 and 0xff) + 0.114 * (c and 0xff)
                }
                grey[cy * 9 + cx] = sum / ((minOf(x1, w) - x0) * (minOf(y1, hgt) - y0)).coerceAtLeast(1)
            }
            var bits = 0L
            for (cy in 0 until 8) for (cx in 0 until 8) {
                bits = bits shl 1
                if (grey[cy * 9 + cx] > grey[cy * 9 + cx + 1]) bits = bits or 1L
            }
            bits
        }
    } catch (_: Exception) {
        null
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
