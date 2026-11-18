// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One installed app, with what Android tells us about it. Sizes and usage need usage access. */
internal class AppEntry(
    val pkg: String,
    val label: String,
    val icon: Drawable?,
    val system: Boolean,
    val updatedSystem: Boolean,
    val enabled: Boolean,
    val versionName: String?,
    val versionCode: Long,
    val installedAt: Long,
    val updatedAt: Long,
    val targetSdk: Int,
    val minSdk: Int,
    val installer: String?,
    val appBytes: Long?,
    val dataBytes: Long?,   // includes the cache
    val cacheBytes: Long?,
    val screenTimeMs: Long?,
    val lastUsed: Long?,
    val launchable: Boolean,
    val apkPaths: List<String>,
) {
    /** App + data (Android's data figure already contains the cache). */
    val size: Long? get() = if (appBytes == null || dataBytes == null) null else appBytes + dataBytes
    /** Apps the person can remove: user apps, and updates of system apps. */
    val removable get() = !system || updatedSystem
    var checked = false
}

internal object AppTools {

    const val USAGE_DAYS = 30
    private const val DAY = 24L * 60 * 60 * 1000

    @Suppress("DEPRECATION")
    private fun version(p: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()

    /** Every installed app for this user, including system and disabled ones. */
    fun load(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val stats = ctx.getSystemService(StorageStatsManager::class.java)
        val user = Process.myUserHandle()
        val launchable = pm.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0,
        ).map { it.activityInfo.packageName }.toSet()
        val usage = usage(ctx)
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
        return pm.getInstalledPackages(flags).mapNotNull { p ->
            val ai = p.applicationInfo ?: return@mapNotNull null
            if (p.packageName == ctx.packageName) return@mapNotNull null
            val s = runCatching { stats.queryStatsForPackage(ai.storageUuid, p.packageName, user) }.getOrNull()
            val u = usage[p.packageName]
            AppEntry(
                pkg = p.packageName,
                label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(p.packageName),
                icon = runCatching { ai.loadIcon(pm) }.getOrNull(),
                system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                updatedSystem = ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
                enabled = ai.enabled,
                versionName = p.versionName,
                versionCode = version(p),
                installedAt = p.firstInstallTime,
                updatedAt = p.lastUpdateTime,
                targetSdk = ai.targetSdkVersion,
                minSdk = if (Build.VERSION.SDK_INT >= 24) ai.minSdkVersion else 0,
                installer = installer(pm, p.packageName),
                appBytes = s?.appBytes,
                dataBytes = s?.dataBytes,
                cacheBytes = s?.cacheBytes,
                screenTimeMs = u?.first,
                lastUsed = u?.second,
                launchable = p.packageName in launchable,
                apkPaths = listOfNotNull(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList()),
            )
        }
    }

    /** Screen time and last use over the last [USAGE_DAYS] days, or empty without usage access. */
    private fun usage(ctx: Context): Map<String, Pair<Long, Long>> {
        if (!Cleaner.hasUsageAccess(ctx)) return emptyMap()
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val now = System.currentTimeMillis()
        return runCatching {
            usm.queryAndAggregateUsageStats(now - USAGE_DAYS * DAY, now).mapValues { (_, s) ->
                val time = if (Build.VERSION.SDK_INT >= 29) s.totalTimeVisible else s.totalTimeInForeground
                val last = if (Build.VERSION.SDK_INT >= 29) maxOf(s.lastTimeUsed, s.lastTimeVisible) else s.lastTimeUsed
                time to last
            }
        }.getOrDefault(emptyMap())
    }

    @Suppress("DEPRECATION")
    private fun installer(pm: PackageManager, pkg: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName else pm.getInstallerPackageName(pkg)
    }.getOrNull()

    /** A friendly name for the app store or tool that installed an app. */
    fun installerLabel(ctx: Context, installer: String?): String = when (installer) {
        null -> "Preinstalled or ADB"
        "com.android.vending" -> "Google Play"
        "com.sec.android.app.samsungapps" -> "Galaxy Store"
        "com.amazon.venezia" -> "Amazon Appstore"
        "org.fdroid.fdroid" -> "F-Droid"
        "com.android.shell" -> "ADB"
        "com.google.android.packageinstaller", "com.android.packageinstaller" -> "Package installer (APK)"
        else -> runCatching {
            ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(installer, 0)).toString()
        }.getOrDefault(installer)
    }

    fun androidName(sdk: Int): String = when (sdk) {
        in 0..0 -> "?"
        21, 22 -> "Android 5"; 23 -> "Android 6"; 24, 25 -> "Android 7"; 26, 27 -> "Android 8"; 28 -> "Android 9"
        29 -> "Android 10"; 30 -> "Android 11"; 31, 32 -> "Android 12"; 33 -> "Android 13"; 34 -> "Android 14"
        35 -> "Android 15"; 36 -> "Android 16"
        else -> "API $sdk"
    }

    // ---------- export ----------

    /**
     * Saves the app's installer to Download/Andromon/APKs: a plain .apk, or for apps split into
     * several APKs a .apks bundle (base.apk + splits) that Andromon and split-APK installers read.
     * Returns where it was saved.
     */
    fun export(ctx: Context, app: AppEntry): String {
        val files = app.apkPaths.map(::File).filter { it.canRead() }
        require(files.isNotEmpty()) { "Android doesn't let this app read ${app.label}'s APK" }
        val bundle = files.size > 1
        val safe = "${app.label}_${app.versionName ?: app.versionCode}".replace(Regex("[^A-Za-z0-9._ -]+"), "_").trim()
        val name = if (bundle) "$safe.apks" else "$safe.apk"
        val mime = if (bundle) "application/octet-stream" else "application/vnd.android.package-archive"
        return save(ctx, name, mime) { out ->
            if (!bundle) files.single().inputStream().use { it.copyTo(out) }
            else ZipOutputStream(out).use { zip ->
                files.forEachIndexed { i, f ->
                    zip.putNextEntry(ZipEntry(if (i == 0) "base.apk" else f.name))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    private const val FOLDER = "Andromon/APKs"

    private fun save(ctx: Context, name: String, mime: String, write: (OutputStream) -> Unit): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Couldn't create the file")
            try {
                resolver.openOutputStream(uri)!!.use(write)
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            val saved = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: name
            return "Download/$FOLDER/$saved"
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER).apply { mkdirs() }
        var file = File(dir, name)
        var n = 1
        while (file.exists()) file = File(dir, name.replaceFirst(Regex("(\\.apks?)$"), " (${n++})$1"))
        file.outputStream().use(write)
        MediaScannerConnection.scanFile(ctx, arrayOf(file.path), null, null)
        return "Download/$FOLDER/${file.name}"
    }
}
