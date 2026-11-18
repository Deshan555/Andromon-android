// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Recover: finds deleted things that are in fact still on the phone, and copies them back.
 *
 * It does not undelete. A phone's internal storage encrypts every file and wipes freed space, so a
 * file that is really gone can't be brought back by any app. What often still exists:
 *  - Android's own trash (Android 11+): deleted photos and videos stay for 30 days as hidden
 *    ".trashed-<expiry>-<name>" files;
 *  - recycle folders that gallery and file-manager apps keep (often with scrambled names);
 *  - thumbnail caches: small copies of pictures, sometimes of ones that were deleted.
 * Only names and sizes are read while scanning. Nothing is changed: recovering copies a file to
 * Download/Andromon/Recovered and leaves the original where it was. Needs All files access.
 */
internal object Recovery {

    enum class Kind(val title: String, val about: String) {
        TRASH("Recently deleted", "Android's trash keeps deleted photos and videos for 30 days"),
        BIN("Recycle folders", "Kept by gallery and file-manager apps until they empty their bin"),
        THUMB("Thumbnail copies", "Small, low-quality copies of pictures. Some may be of pictures that are gone"),
    }

    class Found(val file: File, val kind: Kind, val name: String, val size: Long, val modified: Long, val image: Boolean) {
        var checked = false
    }

    private const val MAX_DEPTH = 9
    private const val MAX_FOUND = 6000
    /** Folder names (lower case) that apps use as a recycle bin. */
    private val BINS = setOf(".trash", ".trashes", ".trashbin", ".recycle", ".recyclebin", "\$recycle.bin", ".globaltrash",
        ".deleted", ".dustbin", ".recently-deleted", "recycle bin", ".gallery_trash", ".photos_trash")
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")
    private val TRASHED = Regex("^\\.trashed-\\d+-")

    /** Everything recoverable under [roots] (the storage volumes), newest first. */
    fun scan(roots: List<File>, cancelled: AtomicBoolean): List<Found> {
        val out = ArrayList<Found>()
        for (root in roots) walk(root, 0, null, out, cancelled)
        return out.sortedByDescending { it.modified }
    }

    private fun walk(dir: File, depth: Int, inside: Kind?, out: MutableList<Found>, cancelled: AtomicBoolean) {
        if (cancelled.get() || out.size >= MAX_FOUND || depth > MAX_DEPTH) return
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (out.size >= MAX_FOUND) return
            val lower = f.name.lowercase()
            if (f.isDirectory) {
                // Other apps' private folders can't be read on Android 11+ and hold no user files.
                if (depth == 1 && dir.name == "Android" && (lower == "data" || lower == "obb")) continue
                val kind = inside ?: when {
                    lower in BINS -> Kind.BIN
                    lower == ".thumbnails" -> Kind.THUMB
                    else -> null
                }
                walk(f, depth + 1, kind, out, cancelled)
                continue
            }
            val size = f.length()
            if (size <= 0 || lower == ".nomedia") continue
            val kind = inside ?: if (TRASHED.containsMatchIn(f.name)) Kind.TRASH else continue
            val named = f.name.replace(TRASHED, "").removePrefix(".")
            val ext = named.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..5 }
            // Bins and thumbnail caches often drop the extension: look at the first bytes instead.
            val sniffed = if (ext == null || kind == Kind.THUMB) sniff(f) else null
            if (kind == Kind.THUMB && sniffed !in IMAGE_EXT) continue // index files, not pictures
            val name = when {
                sniffed != null && ext != sniffed && !(ext == "jpeg" && sniffed == "jpg") -> "$named.$sniffed"
                else -> named
            }.ifEmpty { "file" }
            out += Found(f, kind, name, size, f.lastModified(), (sniffed ?: ext) in IMAGE_EXT)
        }
    }

    /** The file type from its first bytes, as an extension; null when it isn't one of the common ones. */
    private fun sniff(f: File): String? {
        val b = ByteArray(12)
        val n = runCatching { f.inputStream().use { it.read(b) } }.getOrDefault(-1)
        if (n < 12) return null
        fun at(i: Int, s: String) = s.indices.all { b[i + it] == s[it].code.toByte() }
        return when {
            b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "jpg"
            b[0] == 0x89.toByte() && at(1, "PNG") -> "png"
            at(0, "GIF8") -> "gif"
            at(0, "RIFF") && at(8, "WEBP") -> "webp"
            at(0, "RIFF") && at(8, "AVI ") -> "avi"
            at(0, "RIFF") && at(8, "WAVE") -> "wav"
            at(4, "ftyp") -> when {
                at(8, "heic") || at(8, "heix") || at(8, "mif1") -> "heic"
                at(8, "M4A ") -> "m4a"
                at(8, "qt  ") -> "mov"
                at(8, "3gp") -> "3gp"
                else -> "mp4"
            }
            at(0, "%PDF") -> "pdf"
            at(0, "PK\u0003\u0004") -> "zip"
            at(0, "ID3") -> "mp3"
            at(0, "OggS") -> "ogg"
            b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte() -> "mkv"
            else -> null
        }
    }

    fun folder(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Andromon/Recovered")

    /** Copies [items] to Download/Andromon/Recovered (the originals stay). Returns how many were copied. */
    fun recover(ctx: Context, items: List<Found>): Int {
        val dest = folder().apply { mkdirs() }
        val made = ArrayList<String>()
        for (item in items) {
            runCatching {
                val target = Archives.free(File(dest, item.name))
                item.file.inputStream().use { input -> target.outputStream().use { input.copyTo(it, 256 * 1024) } }
                target.setLastModified(item.modified)
                made += target.path
            }
        }
        made.chunked(500).forEach { MediaScannerConnection.scanFile(ctx, it.toTypedArray(), null, null) }
        return made.size
    }
}
