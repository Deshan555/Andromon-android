// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

package dev.droiddesk.companion

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.MediaStore
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest

/** One file or folder the cleaner can delete. */
/** [recursive]: a folder deleted with its contents (an empty-folder match is not, so it stays if it gains files). */
internal class JunkItem(val file: File, val size: Long, val note: String = "", val recursive: Boolean = false) {
    var checked = false
}

/** A kind of junk, e.g. "Thumbnail cache". [listItems] groups show their files for picking one by one. */
internal class JunkGroup(
    val key: String,
    val name: String,
    val hint: String,
    val icon: Int,
    val listItems: Boolean,
    val checkedByDefault: Boolean,
) {
    val items = ArrayList<JunkItem>()
    var expanded = false
    val size get() = items.sumOf { it.size }
    val checkedSize get() = items.filter { it.checked }.sumOf { it.size }
}

internal class StorageVolumeInfo(val name: String, val dir: File?, val total: Long, val free: Long, val removable: Boolean)

/** Storage scanning and cleaning on shared storage (internal + SD card). */
internal object Cleaner {

    private const val DUP_MIN = 1_000_000L


    fun hasFileAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * What Android's "Clear temporary app files" screen (ACTION_CLEAR_APP_CACHE) can free: apps'
     * caches on shared storage (Android/data/<app>/cache). Apps' private caches, usually most of
     * the total, can't be cleared by an app. Android reports this split from Android 12; null when
     * unknown (Android 11, or no usage access).
     */
    fun clearableCacheBytes(ctx: Context): Long? = if (Build.VERSION.SDK_INT < 31) null else try {
        ctx.getSystemService(StorageStatsManager::class.java)
            .queryStatsForUser(StorageManager.UUID_DEFAULT, Process.myUserHandle()).externalCacheBytes
    } catch (_: Exception) {
        null
    }

    /** Internal storage first, then any SD card / USB drive. */
    fun volumes(ctx: Context): List<StorageVolumeInfo> {
        val list = ArrayList<StorageVolumeInfo>()
        val stats = ctx.getSystemService(StorageStatsManager::class.java)
        val (total, free) = try {
            stats.getTotalBytes(StorageManager.UUID_DEFAULT) to stats.getFreeBytes(StorageManager.UUID_DEFAULT)
        } catch (_: Exception) {
            StatFs(Environment.getDataDirectory().path).let { it.totalBytes to it.availableBytes }
        }
        list += StorageVolumeInfo("Internal storage", Environment.getExternalStorageDirectory(), total, free, false)

        val primary = Environment.getExternalStorageDirectory().canonicalPath
        val others: List<Pair<String, File>> = if (Build.VERSION.SDK_INT >= 30) {
            ctx.getSystemService(StorageManager::class.java).storageVolumes
                .filter { !it.isPrimary && it.state == Environment.MEDIA_MOUNTED }
                .mapNotNull { v -> v.directory?.let { v.getDescription(ctx) to it } }
        } else {
            ctx.getExternalFilesDirs(null).filterNotNull()
                .map { File(it.path.substringBefore("/Android/data/")) }
                .filter { it.canonicalPath != primary }
                .map { "SD card" to it }
        }
        for ((name, dir) in others) {
            runCatching { StatFs(dir.path) }.getOrNull()?.let {
                list += StorageVolumeInfo(name, dir, it.totalBytes, it.availableBytes, true)
            }
        }
        return list
    }

    /**
     * One pass over shared storage with SD Maid SE's System Cleaner filters, then SD Maid SE's
     * CorpseFinder for leftovers of removed apps, then duplicates and large files among the rest.
     */
    fun scan(ctx: Context, progress: (String) -> Unit): List<JunkGroup> {
        val roots = volumes(ctx).mapNotNull { it.dir }
        val areas = SystemCleaner.areas(roots)
        val filters = SystemCleaner.filters(ctx, areas).filter { Prefs.ruleOn(ctx, it.key) }
        val findEmpty = Prefs.ruleOn(ctx, "sdm_empty")
        val findDups = Prefs.ruleOn(ctx, "dups")
        val findLarge = Prefs.ruleOn(ctx, "large")
        fun rootOf(f: File) = roots.firstOrNull { f.path.startsWith(it.path + "/") || f.path == it.path }
        fun where(f: File) = rootOf(f)?.let { rel(it, f.parentFile) } ?: (f.parent ?: "")

        val dups = JunkGroup("dups", "Duplicate files", "Extra copies; the oldest copy is kept", R.drawable.ic_copy, true, false)
        val largeMb = Prefs.largeFileMb(ctx)
        val largeBytes = largeMb * 1_000_000L
        val large = JunkGroup("large", "Large files", "Over ${if (largeMb >= 1000) "${largeMb / 1000} GB" else "$largeMb MB"}. Check before deleting",
            R.drawable.ic_big_file, true, false)
        val bySize = HashMap<Long, MutableList<File>>()
        val bigFiles = ArrayList<File>()

        val hits = SystemCleaner.crawl(areas, filters, findEmpty, progress) { f, len ->
            if (findDups && len >= DUP_MIN) bySize.getOrPut(len) { ArrayList(2) } += f
            if (findLarge && len >= largeBytes) bigFiles += f
        }
        val sdm = (filters + listOfNotNull(SystemCleaner.emptyFilter.takeIf { findEmpty })).associateWith { f ->
            JunkGroup(f.key, f.label, f.hint, f.icon, true, f.checked)
        }
        progress("Measuring…")
        for (h in hits) {
            val g = sdm.getValue(h.filter)
            val empty = h.filter === SystemCleaner.emptyFilter
            g.items += JunkItem(h.file, if (h.isDir) treeSize(h.file) else h.file.length(), where(h.file), recursive = h.isDir && !empty)
        }

        // CorpseFinder needs the full list of installed apps; without it every folder would look abandoned.
        val corpses = JunkGroup("corpses", "Leftover app folders", "Left behind by apps that are no longer installed",
            R.drawable.ic_folder, true, false)
        if (Prefs.ruleOn(ctx, "corpses")) installedPackages(ctx)?.let { installed ->
            progress("Looking for leftovers of removed apps…")
            runCatching { CorpseFinder.scan(ctx, areas, installed, progress) }.getOrNull()?.forEach { c ->
                val owner = c.owners.sorted().take(2).joinToString(", ") + if (c.owners.size > 2) " +${c.owners.size - 2}" else ""
                corpses.items += JunkItem(c.file, if (c.file.isDirectory) treeSize(c.file) else c.file.length(),
                    "${where(c.file)} · from $owner", recursive = c.file.isDirectory)
            }
        }

        // Duplicates: same size, then same first/last 64 KB, then same full SHA-1.
        progress("Comparing possible duplicates…")
        val dupPaths = HashSet<String>()
        for ((size, files) in bySize) {
            if (files.size < 2) continue
            for (quick in files.groupBy { hashOrNull(it, quick = true) }.filterKeys { it != null }.values) {
                if (quick.size < 2) continue
                for (same in quick.groupBy { hashOrNull(it, quick = false) }.filterKeys { it != null }.values) {
                    if (same.size < 2) continue
                    val keep = same.minBy { it.lastModified() }
                    val root = rootOf(keep)
                    for (copy in same) if (copy != keep) {
                        dups.items += JunkItem(copy, size, "Same as " + (root?.let { rel(it, keep) } ?: keep.path))
                        dupPaths += copy.path
                    }
                }
            }
        }
        for (f in bigFiles) if (f.path !in dupPaths) large.items += JunkItem(f, f.length(), where(f))

        val groups = sdm.values.toList() + listOfNotNull(
            corpses.takeIf { Prefs.ruleOn(ctx, "corpses") }, dups.takeIf { findDups }, large.takeIf { findLarge })
        groups.forEach { g ->
            g.items.sortByDescending { it.size }
            g.items.forEach { it.checked = g.checkedByDefault }
        }
        return groups
    }

    /** Every installed package, or null when this app can't see them all (then CorpseFinder is skipped). */
    fun installedPackages(ctx: Context): Set<String>? {
        val canSeeAll = Build.VERSION.SDK_INT < 30 ||
            ctx.checkSelfPermission("android.permission.QUERY_ALL_PACKAGES") == PackageManager.PERMISSION_GRANTED
        if (!canSeeAll) return null
        val pkgs = runCatching { ctx.packageManager.getInstalledPackages(0).map { it.packageName }.toSet() }.getOrNull() ?: return null
        return pkgs.takeIf { "android" in it && it.size > 20 }
    }

    /** Size of a folder's contents; symlinks are not followed. */
    fun treeSize(f: File): Long {
        if (Files.isSymbolicLink(f.toPath())) return 0
        if (!f.isDirectory) return f.length()
        return f.listFiles()?.sumOf { treeSize(it) } ?: 0
    }

    /** Deletes a folder that holds only (nested) empty folders; stops at the first file or link. */
    private fun deleteEmptyTree(f: File): Boolean {
        if (Files.isSymbolicLink(f.toPath()) || !f.isDirectory) return false
        val kids = f.listFiles() ?: return false
        if (!kids.all { deleteEmptyTree(it) }) return false
        return f.delete()
    }

    /** Deletes [f] and, for folders, everything inside; symlinks are removed, never followed. */
    private fun deleteTree(f: File, removed: MutableList<String>): Boolean {
        if (!Files.isSymbolicLink(f.toPath()) && f.isDirectory) f.listFiles()?.forEach { deleteTree(it, removed) }
        val wasFile = !f.isDirectory
        val ok = f.delete() || !f.exists()
        if (ok && wasFile) removed += f.path
        return ok
    }

    private fun rel(root: File, f: File?): String {
        if (f == null) return ""
        val r = f.path.removePrefix(root.path).trimStart('/')
        return if (r.isEmpty()) "Main folder" else r
    }

    private fun hashOrNull(f: File, quick: Boolean): String? = try {
        val md = MessageDigest.getInstance(if (quick) "MD5" else "SHA-1")
        val buf = ByteArray(64 * 1024)
        if (quick) {
            RandomAccessFile(f, "r").use { raf ->
                md.update(buf, 0, raf.read(buf).coerceAtLeast(0))
                if (raf.length() > buf.size) {
                    raf.seek(raf.length() - buf.size)
                    md.update(buf, 0, raf.read(buf).coerceAtLeast(0))
                }
            }
        } else {
            f.inputStream().use { input ->
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }

    /** Deletes [items] (folders after their contents). Returns bytes freed and how many failed. */
    fun delete(ctx: Context, items: List<JunkItem>): Pair<Long, Int> {
        var freed = 0L
        var failed = 0
        val removed = ArrayList<String>()
        for (item in items.distinctBy { it.file.path }.sortedByDescending { it.file.path.length }) {
            if (!item.file.exists()) { freed += item.size; continue } // e.g. inside a folder deleted just before
            val ok = if (item.recursive) {
                deleteTree(item.file, removed)
            } else if (item.file.isDirectory) {
                deleteEmptyTree(item.file) // an empty-folder match: never removes a file that appeared since the scan
            } else {
                item.file.delete().also { if (it) removed += item.file.path }
            }
            if (ok) freed += item.size else failed++
        }
        ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        // Tell the media database the files are gone so galleries don't show broken entries.
        if (removed.isNotEmpty()) MediaScannerConnection.scanFile(ctx, removed.toTypedArray(), null, null)
        return freed to failed
    }
}

/** What fills internal storage, for the donut on the Cleaner screen. */
internal class StoragePart(val name: String, val bytes: Long, val color: Int)

internal object StorageBreakdown {
    /**
     * Apps (with usage access), media and documents from Android's media index, and everything
     * else (system, app files the index doesn't see) as "System & other".
     */
    fun of(ctx: Context, total: Long, free: Long): List<StoragePart> {
        val used = (total - free).coerceAtLeast(0)
        var photos = 0L; var audio = 0L; var docs = 0L
        if (Cleaner.hasFileAccess(ctx)) try {
            val uri = if (Build.VERSION.SDK_INT >= 29) MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                      else MediaStore.Files.getContentUri("external")
            val cols = arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.MIME_TYPE)
            ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val size = c.getLong(0)
                    if (size <= 0) continue
                    when (c.getInt(1)) {
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE, MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> photos += size
                        MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> audio += size
                        else -> {
                            val mime = c.getString(2).orEmpty()
                            if (mime.startsWith("text/") || (mime.startsWith("application/") && !mime.endsWith("package-archive"))) docs += size
                        }
                    }
                }
            }
        } catch (_: Exception) { }
        val apps = if (Cleaner.hasUsageAccess(ctx)) try {
            ctx.getSystemService(StorageStatsManager::class.java)
                .queryStatsForUser(StorageManager.UUID_DEFAULT, Process.myUserHandle()).let { it.appBytes + it.dataBytes }
        } catch (_: Exception) { 0L } else 0L
        val parts = listOf(
            StoragePart("Apps", apps, R.color.cat_blue),
            StoragePart("Photos & videos", photos, R.color.cat_salmon),
            StoragePart("Audio", audio, R.color.cat_yellow),
            StoragePart("Documents", docs, R.color.cat_teal),
        ).filter { it.bytes > 0 }
        val other = (used - parts.sumOf { it.bytes }).coerceAtLeast(0)
        return parts + StoragePart("System & other", other, R.color.cat_green)
    }
}
