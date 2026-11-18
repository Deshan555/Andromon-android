// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors
// Filter definitions ported from SD Maid SE (https://github.com/d4rken-org/sdmaid-se, commit ecbe81b),
// GPL-3.0, Copyright (C) the SD Maid SE authors: app-tool-systemcleaner/.../filter/stock/*.kt.
// Only the filters that work on shared storage without root are included.

package dev.andromon.companion

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * SD Maid SE's "System Cleaner" for shared storage: walks internal storage, SD cards and the
 * readable Android/ folders once, and runs every filter on each file and folder it meets.
 */
internal object SystemCleaner {

    /** One stock filter: [checked] mirrors whether SD Maid SE enables it by default. */
    class Filter(
        val key: String,
        val label: String,
        val hint: String,
        val icon: Int,
        val checked: Boolean,
        val match: (Item) -> Boolean,
    )

    class Hit(val filter: Filter, val file: File, val isDir: Boolean, val note: String = "")

    private const val DAY = 24L * 60 * 60 * 1000

    // ---------- areas ----------

    /** Storage roots plus the Android/ subfolders this app may read (Android/data and obb only up to Android 10). */
    fun areas(roots: List<File>): List<Area> = buildList {
        for (root in roots) {
            add(Area(AreaType.SDCARD, root))
            val android = File(root, "Android")
            File(android, "media").takeIf { it.canRead() && it.list() != null }?.let { add(Area(AreaType.PUBLIC_MEDIA, it)) }
            if (Build.VERSION.SDK_INT < 30) {
                File(android, "data").takeIf { it.list() != null }?.let { add(Area(AreaType.PUBLIC_DATA, it)) }
                File(android, "obb").takeIf { it.list() != null }?.let { add(Area(AreaType.PUBLIC_OBB, it)) }
            }
        }
    }

    // ---------- the stock filters ----------

    fun filters(ctx: Context, areas: List<Area>): List<Filter> {
        val sd = setOf(AreaType.SDCARD, AreaType.PORTABLE)

        val logs = Sieve(
            areaTypes = setOf(AreaType.SDCARD), dirs = false,
            nameCriteria = setOf(NameCriterium(".log", NameCriterium.Mode.End())),
            pathExclusions = setOf(
                SegmentCriterium(segs(".indexeddb.leveldb"), SegmentCriterium.Mode.Contain(allowPartial = true)),
                SegmentCriterium(segs("t", "Paths"), SegmentCriterium.Mode.Contain()),
                SegmentCriterium(segs("app_chrome"), SegmentCriterium.Mode.Contain()),
                SegmentCriterium(segs("app_webview"), SegmentCriterium.Mode.Contain()),
                SegmentCriterium(segs("leveldb"), SegmentCriterium.Mode.Contain()),
                SegmentCriterium(segs("shared_proto_db"), SegmentCriterium.Mode.Contain()),
            ),
        )

        // Advertisement: known ad-SDK cache folders at the top of shared storage.
        val adNames = listOf(
            "ppy_cross" to "ppy_cross", ".mologiq" to "\\.mologiq", ".Adcenix" to "\\.Adcenix",
            "ApplifierVideoCache" to "ApplifierVideoCache", "burstlyVideoCache" to "burstlyVideoCache",
            "UnityAdsVideoCache" to "UnityAdsVideoCache", "ApplifierImageCache" to "ApplifierImageCache",
            "burstlyImageCache" to "burstlyImageCache", "UnityAdsImageCache" to "UnityAdsImageCache",
            "__chartboost" to "__chartboost", ".chartboost" to "\\.chartboost", "adhub" to "adhub",
            ".mobvista" to "\\.mobvista\\d+", ".goadsdk" to "\\.goadsdk", ".goproduct" to "\\.goproduct",
        )
        val adRoots = areas.filter { it.type == AreaType.SDCARD }.map { Regex.escape(it.root.path) }
        val ads = Sieve(
            areaTypes = setOf(AreaType.SDCARD),
            pfpCriteria = adNames.map { (name, _) ->
                when (name) {
                    "ppy_cross" -> SegmentCriterium(segs(name), SegmentCriterium.Mode.Equal())
                    ".mologiq", ".mobvista" -> SegmentCriterium(segs(name), SegmentCriterium.Mode.Start(allowPartial = true))
                    else -> SegmentCriterium(segs(name), SegmentCriterium.Mode.Start())
                }
            }.toSet(),
            pathRegexes = adRoots.flatMap { root ->
                adNames.map { (name, rx) ->
                    if (name == "ppy_cross") Regex("^(?:$root/ppy_cross)$") else Regex("^(?:$root/)(?:$rx|$rx/.+)$")
                }
            }.toSet(),
        )

        val bugsense = Sieve(
            areaTypes = setOf(AreaType.SDCARD, AreaType.PUBLIC_DATA), dirs = false,
            pathCriteria = setOf(SegmentCriterium(segs(".bugsense"), SegmentCriterium.Mode.Contain())),
            pathRegexes = setOf(Regex("^(?:[\\W\\w]+/\\.(?:bugsense))$")),
        )
        val tracking = Sieve(
            areaTypes = setOf(AreaType.SDCARD, AreaType.PUBLIC_DATA),
            pfpCriteria = (listOf(
                ".tlocalcookieid", ".INSTALLATION", ".wps_preloaded_2.txt", ".UTSystemConfig/Global/Alvin2.xml",
                ".DataStorage/ContextData.xml", "com.snssdk.api.embed/cache/clientudid.dat", "Tencent/ams/cache/meta.dat",
                "com.tencent.ams/cache/meta.dat", "backups/.SystemConfig/.cuid", "backups/.SystemConfig/.cuid2",
                "backups/.adiu", "Mob/comm/dbs/.duid", ".mn_1006862472", ".imei.txt", ".DC4278477faeb9.txt",
                "Android/obj/.um/sysid.dat", ".um/sysid.dat", ".oukdtft", "libs/com.igexin.sdk.deviceId.db",
                "data/.push_deviceid", "msc/.2F6E2C5B63F0F83B", ".lm_device/.lm_device_id", "LMDevice/lm_device_id",
            ).map { SegmentCriterium(it, SegmentCriterium.Mode.Equal()) } +
                SegmentCriterium(".pns/.uniqueId", SegmentCriterium.Mode.Ancestor())).toSet(),
        )

        val lostDir = Sieve(areaTypes = sd, dirs = false, pathRegexes = setOf(Regex("^(?:[\\W\\w]+/LOST\\.DIR/[\\W\\w]+)$")))

        val trashDir = Regex("^\\.Trash(-\\d+)?$", RegexOption.IGNORE_CASE)
        val linux = Sieve(
            areaTypes = sd, dirs = true,
            pfpCriteria = setOf(
                SegmentCriterium(segs(".Trash"), SegmentCriterium.Mode.Ancestor()),
                SegmentCriterium(segs(".Trash-"), SegmentCriterium.Mode.Start(allowPartial = true)),
            ),
            pfpExclusions = setOf(SegmentCriterium(segs("Android"), SegmentCriterium.Mode.Ancestor())),
        )

        val macFiles = Sieve(
            areaTypes = setOf(AreaType.SDCARD, AreaType.PUBLIC_MEDIA, AreaType.PORTABLE), dirs = false,
            nameCriteria = setOf(NameCriterium("._", NameCriterium.Mode.Start()), NameCriterium(".DS_Store", NameCriterium.Mode.Equal())),
        )
        val macDirs = Sieve(
            areaTypes = sd, dirs = true,
            pfpCriteria = listOf(".Trashes", ".spotlight", ".Spotlight-V100", ".fseventsd", ".TemporaryItems")
                .map { SegmentCriterium(segs(it), SegmentCriterium.Mode.Equal()) }.toSet(),
        )

        val windows = Sieve(
            areaTypes = setOf(AreaType.SDCARD, AreaType.PUBLIC_DATA, AreaType.PUBLIC_MEDIA, AreaType.PUBLIC_OBB, AreaType.PORTABLE),
            dirs = false,
            nameCriteria = setOf(NameCriterium("desktop.ini", NameCriterium.Mode.Equal()), NameCriterium("thumbs.db", NameCriterium.Mode.Equal())),
        )

        val temp = Sieve(
            areaTypes = setOf(AreaType.SDCARD), dirs = false,
            nameCriteria = setOf(
                NameCriterium(".tmp", NameCriterium.Mode.End()),
                NameCriterium(".temp", NameCriterium.Mode.End()),
                NameCriterium(".mmsyscache", NameCriterium.Mode.Equal()),
                NameCriterium("sdm_write_test-", NameCriterium.Mode.Start()),
            ),
            pathExclusions = setOf(
                SegmentCriterium(segs("backup", "pending"), SegmentCriterium.Mode.Ancestor()),
                SegmentCriterium(segs("cache", "recovery"), SegmentCriterium.Mode.Ancestor()),
            ),
        )

        val thumbs = Sieve(
            areaTypes = setOf(AreaType.SDCARD, AreaType.PUBLIC_MEDIA, AreaType.PORTABLE),
            pfpCriteria = setOf(SegmentCriterium(".thumbnails", SegmentCriterium.Mode.Contain())),
        )

        val screenshots = Sieve(
            areaTypes = sd, dirs = false,
            pfpCriteria = setOf(SegmentCriterium(segs("Pictures", "Screenshots"), SegmentCriterium.Mode.Ancestor())),
            minimumAgeMs = 14 * DAY,
        )

        val trashed = Sieve(areaTypes = sd, dirs = false, nameCriteria = setOf(NameCriterium(".trashed-", NameCriterium.Mode.Start())))

        val apks = Sieve(
            areaTypes = sd, dirs = false,
            nameCriteria = setOf(NameCriterium(".apk", NameCriterium.Mode.End()), NameCriterium(".apks", NameCriterium.Mode.End())),
            pathExclusions = listOf("Backup", "Backups", "Recover", "Recovery", "TWRP")
                .map { SegmentCriterium(segs(it), SegmentCriterium.Mode.Contain()) }.toSet(),
        )
        val superfluous = SuperfluousApks(ctx)

        return listOf(
            Filter("sdm_logs", "Log files", ".log files that apps leave on shared storage", R.drawable.ic_file, true) { logs.match(it) },
            Filter("sdm_ads", "Advertisement caches", "Cached ads from ad libraries", R.drawable.ic_image, true) {
                ads.match(it) && !(it.name.endsWith("chartboost") && !it.isDir)
            },
            Filter("sdm_analytics", "Tracking & analytics", "Device IDs and crash-report files left by trackers", R.drawable.ic_shield, true) {
                tracking.match(it) || bugsense.match(it)
            },
            Filter("sdm_lostdir", "LOST.DIR", "File fragments recovered after storage errors", R.drawable.ic_sd, true) { lostDir.match(it) },
            Filter("sdm_linux", "Linux trash", ".Trash folders made by Linux computers", R.drawable.ic_trash, true) { item ->
                // `.Trash-1000` is Linux trash; Android's own `.trash-storage` holds restorable files.
                linux.match(item) && item.pfp.firstOrNull()?.let(trashDir::matches) == true
            },
            Filter("sdm_mac", "macOS files", "._ files, .DS_Store and Spotlight folders", R.drawable.ic_file, true) {
                macFiles.match(it) || macDirs.match(it)
            },
            Filter("sdm_windows", "Windows files", "Thumbs.db and desktop.ini", R.drawable.ic_file, true) { windows.match(it) },
            Filter("sdm_temp", "Temporary files", ".tmp and .temp files", R.drawable.ic_file, true) { temp.match(it) },
            Filter("sdm_thumbs", "Thumbnail caches", ".thumbnails folders; rebuilt when needed", R.drawable.ic_image, false) { thumbs.match(it) },
            Filter("sdm_apks", "Superfluous APKs", "Installers for apps already installed at this or a newer version", R.drawable.ic_apk, false) {
                apks.match(it) && superfluous.check(it.file)
            },
            Filter("sdm_screenshots", "Old screenshots", "Screenshots older than 14 days", R.drawable.ic_image, false) { screenshots.match(it) },
            Filter("sdm_trashed", "Android trash", "Files waiting in the system trash (.trashed-…)", R.drawable.ic_trash, false) { trashed.match(it) },
        )
    }

    // ---------- empty directories (EmptyDirectoryFilter) ----------

    val emptyFilter = Filter("sdm_empty", "Empty folders", "Folders with nothing in them", R.drawable.ic_folder, true) { false }

    private val emptySieve = Sieve(
        areaTypes = setOf(AreaType.SDCARD, AreaType.PUBLIC_DATA, AreaType.PUBLIC_MEDIA, AreaType.PORTABLE), dirs = true,
        pathExclusions = setOf(
            SegmentCriterium(segs("mnt", "asec"), SegmentCriterium.Mode.Contain()),
            SegmentCriterium(segs("mnt", "obb"), SegmentCriterium.Mode.Contain()),
            SegmentCriterium(segs("mnt", "secure"), SegmentCriterium.Mode.Contain()),
            SegmentCriterium(segs("mnt", "shell"), SegmentCriterium.Mode.Contain()),
            SegmentCriterium(segs("Android", "obb"), SegmentCriterium.Mode.Contain()),
            SegmentCriterium(segs(".stfolder"), SegmentCriterium.Mode.Contain()),
        ),
    )
    private val protectedBaseDirs = listOf(
        "Camera", "Photos", "Music", "DCIM", "Pictures", "Movies", "Recordings", "Video", "Download", "Audiobooks",
        "Documents", "Alarms", "Ringtones", "Notifications", "Podcasts", "Android/data", "Android/media", "Android/obb",
    ).map { it.toSegs() }
    private val pkgAreas = setOf(AreaType.PUBLIC_DATA, AreaType.PUBLIC_MEDIA, AreaType.PUBLIC_OBB)

    /** Everything about a folder except "is its whole tree free of files", which the crawler knows. */
    private fun emptyCandidate(item: Item): Boolean {
        if (!emptySieve.match(item)) return false
        val pfp = item.pfp
        if (pfp.isEmpty()) return false
        if (protectedBaseDirs.any { it.matchesSegs(pfp, ignoreCase = true) }) return false
        if (item.area.type in pkgAreas && pfp.size == 1) return false
        if (item.area.type in pkgAreas && pfp.size == 2 && pfp[1] in setOf("files", "cache")) return false
        return true
    }

    // ---------- crawler ----------

    /**
     * Walks every area once. Folders a filter claims are not entered (deleting them removes their
     * contents). Files no filter claims go to [onOther] (duplicate / large-file search).
     */
    fun crawl(areas: List<Area>, filters: List<Filter>, findEmpty: Boolean, progress: (String) -> Unit,
              onOther: (File, Long) -> Unit): List<Hit> {
        val hits = ArrayList<Hit>()
        var lastReport = 0L
        // Android/data, media and obb are separate areas; don't walk them again as part of SDCARD.
        val areaRoots = areas.filter { it.type != AreaType.SDCARD }.map { it.root.path }.toSet()
        val skip = areas.filter { it.type == AreaType.SDCARD }
            .flatMap { a -> listOf("data", "obb", "media").map { File(a.root, "Android/$it").path } }.toSet()

        /** Returns true when [item] is a folder whose whole tree is empty and it was claimed as such. */
        fun visit(item: Item): Boolean {
            for (f in filters) if (f.match(item)) {
                hits += Hit(f, item.file, item.isDir)
                return false
            }
            if (!item.isDir) {
                onOther(item.file, item.file.length())
                return false
            }
            val now = System.currentTimeMillis()
            if (now - lastReport > 120) { lastReport = now; progress(item.pfp.joinSegs()) }
            val kids = item.file.listFiles() ?: return false
            var treeEmpty = true
            for (k in kids) {
                if (k.path in skip || k.path in areaRoots) { treeEmpty = false; continue }
                // Symlinks count as files (only the link itself would ever be deleted).
                val link = Files.isSymbolicLink(k.toPath())
                val child = Item(k, !link && k.isDirectory, item.area)
                if (!visit(child) ) treeEmpty = false
            }
            if (findEmpty && treeEmpty && emptyCandidate(item)) {
                // Keep only the topmost empty folder of an empty tree.
                val prefix = item.path + "/"
                hits.removeAll { it.filter === emptyFilter && it.file.path.startsWith(prefix) }
                hits += Hit(emptyFilter, item.file, true)
                return true
            }
            return false
        }

        for (area in areas) {
            val kids = area.root.listFiles() ?: continue
            for (k in kids) {
                if (k.path in skip) continue
                val link = Files.isSymbolicLink(k.toPath())
                visit(Item(k, !link && k.isDirectory, area))
            }
        }
        return hits
    }

    // ---------- superfluous APKs (SuperfluousApksFilter) ----------

    /** An APK is superfluous when its app is installed at the same or a newer version. */
    private class SuperfluousApks(private val ctx: Context) {
        private val pm = ctx.packageManager

        @Suppress("DEPRECATION")
        private fun version(info: android.content.pm.PackageInfo) =
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

        fun check(file: File): Boolean = try {
            val archive = when {
                file.name.endsWith(".apk", ignoreCase = true) -> pm.getPackageArchiveInfo(file.path, 0)
                file.name.endsWith(".apks", ignoreCase = true) -> baseOfApks(file)
                else -> null
            }
            if (archive == null) false else {
                val installed = try { pm.getPackageInfo(archive.packageName, 0) } catch (_: PackageManager.NameNotFoundException) { null }
                installed != null && version(installed) >= version(archive)
            }
        } catch (_: Exception) {
            false
        }

        /** .apks bundles: read the version from their base.apk (extracted to the cache, then removed). */
        private fun baseOfApks(file: File): android.content.pm.PackageInfo? {
            val dir = File(ctx.cacheDir, "sdm_apks").apply { mkdirs() }
            val base = File(dir, "base.apk")
            try {
                ZipFile(file).use { zip ->
                    val entry = zip.getEntry("base.apk") ?: return null
                    if (entry.size > Environment.getDataDirectory().usableSpace / 4) return null
                    zip.getInputStream(entry).use { input -> base.outputStream().use { input.copyTo(it) } }
                }
                return pm.getPackageArchiveInfo(base.path, 0)
            } finally {
                base.delete()
            }
        }
    }
}
