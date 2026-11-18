// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * MMS for Andromon's Messages tab: picture and group messages, read-only.
 * `mms.list` gives the messages with their text and a description of each attachment;
 * `mms.part` fetches one attachment (or a small JPEG preview of a picture) when the PC asks.
 */
internal class Mms(private val ctx: Context) {

    private val cr = ctx.contentResolver

    fun call(method: String, p: JSONObject): Any = when (method) {
        "mms.list" -> list(p.optInt("limit", 0))
        "mms.part" -> part(p.getLong("partId"), p.optLong("maxBytes", DEFAULT_MAX), p.optInt("thumb", 0))
        else -> throw IllegalArgumentException("Unknown method: $method")
    }

    private fun requireRead() {
        if (ctx.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("no access to messages")
        }
    }

    // ---------- list ----------

    private class Part(val id: Long, val mime: String, val name: String, val text: String?, val hasFile: Boolean)

    private fun list(limit: Int): JSONArray {
        requireRead()
        val parts = allParts()
        val threadPeople = HashMap<Long, JSONArray>()
        val canonical = canonicalAddresses()
        val recipientIds = threadRecipientIds()
        val out = JSONArray()
        val cols = arrayOf("_id", "thread_id", "date", "date_sent", "msg_box", "read", "sub", "sub_cs")
        cr.query(MMS, cols, null, null, "date DESC")?.use { c ->
            while (c.moveToNext()) {
                try {
                    val id = c.getLong(0)
                    val thread = c.getLong(1)
                    val o = JSONObject()
                        .put("id", id)
                        .put("thread", thread)
                        .put("date", c.getLong(2) * 1000)
                        .put("dateSent", c.getLong(3) * 1000)
                        .put("type", c.getInt(4))
                        .put("read", c.getInt(5) == 1)
                        .put("subject", subject(c.text(6), if (c.isNull(7)) 0 else c.getInt(7)))
                    addresses(id, o)
                    o.put("recipients", threadPeople.getOrPut(thread) {
                        JSONArray().also { arr ->
                            for (rid in recipientIds[thread].orEmpty()) {
                                val a = canonical[rid] ?: canonicalAddress(rid)
                                if (!a.isNullOrBlank()) arr.put(a)
                            }
                        }
                    })
                    val text = StringBuilder()
                    val files = JSONArray()
                    for (pt in parts[id].orEmpty()) {
                        when {
                            pt.mime == "application/smil" -> {}
                            pt.mime == "text/plain" -> {
                                val t = if (pt.hasFile) readText(pt.id) else pt.text
                                if (!t.isNullOrEmpty()) { if (text.isNotEmpty()) text.append('\n'); text.append(t) }
                            }
                            else -> files.put(
                                JSONObject().put("partId", pt.id).put("mime", pt.mime).put("name", pt.name).put("size", partSize(pt.id))
                            )
                        }
                    }
                    o.put("text", text.toString()).put("parts", files)
                    out.put(o)
                } catch (_: Exception) {
                    // One unreadable message must not hide the rest.
                }
                if (limit > 0 && out.length() >= limit) break
            }
        }
        return out
    }

    /** Every part of every message in one query, grouped by message id. */
    private fun allParts(): Map<Long, List<Part>> {
        val map = HashMap<Long, MutableList<Part>>()
        try {
            cr.query(PART, arrayOf("_id", "mid", "ct", "name", "cl", "fn", "text", "_data"), null, null, "_id ASC")?.use { c ->
                while (c.moveToNext()) {
                    val name = sequenceOf(c.text(4), c.text(3), c.text(5)).firstOrNull { it.isNotBlank() } ?: ""
                    map.getOrPut(c.getLong(1)) { ArrayList() } +=
                        Part(c.getLong(0), c.text(2).lowercase().ifBlank { "application/octet-stream" }, name,
                            if (c.isNull(6)) null else c.getString(6), !c.isNull(7))
                }
            }
        } catch (_: Exception) {
        }
        return map
    }

    private fun addresses(id: Long, o: JSONObject) {
        var from = ""
        val to = JSONArray()
        try {
            cr.query(Uri.parse("content://mms/$id/addr"), arrayOf("address", "type"), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val a = c.text(0).trim()
                    if (a.isEmpty() || a == TOKEN) continue
                    when (c.getInt(1)) {
                        ADDR_FROM -> from = a
                        ADDR_TO, ADDR_CC, ADDR_BCC -> to.put(a)
                    }
                }
            }
        } catch (_: Exception) {
        }
        o.put("from", from).put("to", to)
    }

    /** thread id -> the ids of its participants in the canonical-address table. */
    private fun threadRecipientIds(): Map<Long, List<Long>> {
        val map = HashMap<Long, List<Long>>()
        try {
            cr.query(Uri.parse("content://mms-sms/conversations?simple=true"), arrayOf("_id", "recipient_ids"), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    map[c.getLong(0)] = c.text(1).trim().split(' ').mapNotNull { it.toLongOrNull() }
                }
            }
        } catch (_: Exception) {
        }
        return map
    }

    private fun canonicalAddresses(): HashMap<Long, String> {
        val map = HashMap<Long, String>()
        try {
            cr.query(Uri.parse("content://mms-sms/canonical-addresses"), arrayOf("_id", "address"), null, null, null)?.use { c ->
                while (c.moveToNext()) map[c.getLong(0)] = c.text(1)
            }
        } catch (_: Exception) {
        }
        return map
    }

    private fun canonicalAddress(id: Long): String? = try {
        cr.query(Uri.parse("content://mms-sms/canonical-address/$id"), null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.text(0) else null
        }
    } catch (_: Exception) {
        null
    }

    /** Subjects are stored as the raw bytes of their charset (106 = UTF-8) packed into a Latin-1 string. */
    private fun subject(raw: String, charset: Int): String {
        if (raw.isEmpty()) return ""
        return try {
            val bytes = raw.toByteArray(Charsets.ISO_8859_1)
            when (charset) {
                106 -> String(bytes, Charsets.UTF_8)
                1000, 1015 -> String(bytes, Charsets.UTF_16)
                else -> raw
            }
        } catch (_: Exception) {
            raw
        }
    }

    private fun partUri(id: Long): Uri = Uri.parse("content://mms/part/$id")

    private fun readText(id: Long): String? = try {
        cr.openInputStream(partUri(id))?.use { String(it.readBytes(), Charsets.UTF_8) }
    } catch (_: Exception) {
        null
    }

    private fun partSize(id: Long): Long = try {
        cr.openFileDescriptor(partUri(id), "r")?.use { it.statSize } ?: -1L
    } catch (_: Exception) {
        -1L
    }

    // ---------- one attachment ----------

    private fun part(id: Long, maxBytes: Long, thumb: Int): JSONObject {
        requireRead()
        val mime = cr.query(partUri(id), arrayOf("ct"), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.text(0).lowercase() else null
        } ?: throw IllegalArgumentException("That attachment is no longer on the phone")
        val wantThumb = thumb > 0 && mime.startsWith("image/")
        val cap = if (wantThumb) THUMB_SOURCE_MAX else maxBytes.coerceIn(1, HARD_MAX)
        val known = partSize(id)
        if (!wantThumb && known > cap) throw tooLarge(known)
        val bytes = cr.openInputStream(partUri(id))?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > cap) throw tooLarge(maxOf(known, out.size().toLong()))
            }
            out.toByteArray()
        } ?: throw IllegalArgumentException("That attachment can't be read")
        val o = JSONObject().put("partId", id)
        if (wantThumb) {
            val jpeg = Pictures.toJpeg(bytes, thumb.coerceIn(32, 2048), 80)
                ?: throw IllegalArgumentException("That picture can't be shown")
            return o.put("mime", "image/jpeg").put("thumb", true).put("size", bytes.size)
                .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
        }
        return o.put("mime", mime).put("size", bytes.size).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private fun tooLarge(size: Long) =
        IllegalArgumentException("Attachment is too large to transfer (${"%.1f".format(size / 1048576.0)} MB)")

    private companion object {
        val MMS: Uri = Uri.parse("content://mms")
        val PART: Uri = Uri.parse("content://mms/part")
        const val TOKEN = "insert-address-token"
        const val ADDR_BCC = 129
        const val ADDR_CC = 130
        const val ADDR_FROM = 137
        const val ADDR_TO = 151
        const val DEFAULT_MAX = 20L * 1024 * 1024
        const val HARD_MAX = 22L * 1024 * 1024
        const val THUMB_SOURCE_MAX = 40L * 1024 * 1024
    }
}

/** Decoding and shrinking pictures (MMS previews, contact photos). */
internal object Pictures {
    /** Decodes [bytes] at a reduced size, turns it upright, and scales it so the longer side is at most [maxSide]. */
    fun decode(bytes: ByteArray, maxSide: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        if (bmp != null) {
            val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
            val m = Matrix()
            if (scale < 1f) m.postScale(scale, scale)
            val turn = rotation(bytes)
            if (turn != 0) m.postRotate(turn.toFloat())
            if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        bmp
    } catch (_: Throwable) { // includes OutOfMemoryError on a broken or huge picture
        null
    }

    fun toJpeg(bytes: ByteArray, maxSide: Int, quality: Int): ByteArray? = decode(bytes, maxSide)?.let { bmp ->
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.toByteArray()
    }

    fun isJpeg(b: ByteArray) = b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    private fun rotation(bytes: ByteArray): Int = try {
        when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: Exception) {
        0
    }
}

private fun Cursor.text(i: Int): String = if (isNull(i)) "" else getString(i) ?: ""
