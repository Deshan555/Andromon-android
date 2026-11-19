// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Update check for the companion itself. Every Andromon release is published in
 * Deshan555/Andromon_Releases with a latest.json (written by the PC repository's
 * scripts/release-manifest.js): its "companion" part has the phone app's version and version code,
 * and its files list the APK with its size and SHA-256. A release is newer when its version code is
 * higher than the installed one. The APK is only taken from that repository's releases, is checked
 * against latest.json, and is handed to Android's installer, which asks the person to confirm.
 * Nothing is installed by itself.
 */
object Updates {
    private const val REPO = "Deshan555/Andromon_Releases"
    private const val MANIFEST_URL = "https://raw.githubusercontent.com/$REPO/HEAD/latest.json"
    private const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
    const val RELEASES_URL = "https://github.com/$REPO/releases"
    private const val MAX_APK = 200L * 1024 * 1024
    private const val DAY = 24L * 60 * 60 * 1000
    private const val ACTION_STATUS = "dev.andromon.companion.UPDATE_STATUS"

    /** The phone app in the newest release. */
    class Release(
        val version: String, val versionCode: Long, val build: Int, val date: String,
        val changes: List<String>, val url: String, val sha256: String, val size: Long, val page: String,
    )

    /** What the last check found: [latest] is null when the installed app is the newest. */
    class Result(val latest: Release?, val error: String?)

    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)

    /** Check once a day when the app opens (Settings › About). On by default. */
    fun auto(ctx: Context) = sp(ctx).getBoolean("auto", true)
    fun setAuto(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("auto", on).apply()

    fun installedCode(ctx: Context): Long {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
    }

    /** The result of the last check this run of the app, for Settings' value text. */
    @Volatile var last: Result? = null
        private set

    // ---------- checking ----------

    /** Reads latest.json on a background thread; [done] gets the result on the main thread. */
    fun check(ctx: Context, done: (Result) -> Unit) {
        val app = ctx.applicationContext
        thread(isDaemon = true, name = "update-check") {
            val r = try {
                Result(newer(app, fetch()), null)
            } catch (e: Exception) {
                Result(null, friendly(e))
            }
            if (r.error == null) sp(app).edit().putLong("checked", System.currentTimeMillis()).apply()
            last = r
            main.post { done(r) }
        }
    }

    private fun fetch(): JSONObject {
        val c = open(MANIFEST_URL)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val text = c.inputStream.use { it.readBytes() }
            if (text.size > 1024 * 1024) throw IOException("The update information is too large.")
            return JSONObject(String(text, Charsets.UTF_8))
        } finally {
            c.disconnect()
        }
    }

    /** The release in [m] when it is newer than the installed app, else null. Throws when [m] is unusable. */
    private fun newer(ctx: Context, m: JSONObject): Release? {
        val c = m.optJSONObject("companion") ?: throw IOException("The update information has no phone app.")
        val code = c.optLong("versionCode", 0)
        if (code <= installedCode(ctx)) return null
        val files = m.optJSONArray("files") ?: throw IOException("The update information lists no files.")
        val apk = (0 until files.length()).mapNotNull { files.optJSONObject(it) }
            .firstOrNull { it.optString("platform") == "android" && it.optString("kind") == "apk" }
            ?: throw IOException("The newest release has no phone app to download.")
        val url = apk.optString("url")
        val sha = apk.optString("sha256").lowercase()
        val size = apk.optLong("size", 0)
        if (!url.startsWith(DOWNLOAD_PREFIX) || !Regex("[0-9a-f]{64}").matches(sha) || size <= 0 || size > MAX_APK)
            throw IOException("The update information has an unexpected format.")
        val changes = c.optJSONArray("changes")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
        return Release(
            version = c.optString("version").ifBlank { m.optString("version") },
            versionCode = code, build = c.optInt("build", 0), date = m.optString("date").take(10),
            changes = changes, url = url, sha256 = sha, size = size,
            page = m.optString("release").takeIf { it.startsWith("https://github.com/$REPO/") } ?: RELEASES_URL,
        )
    }

    private fun open(address: String): HttpURLConnection {
        val url = URL(address)
        // A broken Private DNS can hold a plain lookup for minutes; look the name up with a limit first.
        try { NetTools.resolve(url.host) } catch (e: java.net.UnknownHostException) { throw e } catch (e: IOException) { throw java.net.UnknownHostException(e.message) }
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.useCaches = false
        c.setRequestProperty("User-Agent", "Andromon")
        c.setRequestProperty("Cache-Control", "no-cache")
        return c
    }

    private fun friendly(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "No internet connection, or GitHub can't be reached."
        is java.net.SocketTimeoutException -> "GitHub didn't answer in time. Try again later."
        is org.json.JSONException -> "The update information couldn't be read."
        else -> e.message?.takeIf { it.isNotBlank() } ?: "The check didn't work."
    }

    /** Once a day when the app opens: asks about a newer release, once per version. */
    fun autoCheck(activity: MainActivity) {
        val ctx = activity.applicationContext
        if (!auto(ctx) || System.currentTimeMillis() - sp(ctx).getLong("checked", 0) < DAY) return
        check(ctx) { r ->
            val rel = r.latest ?: return@check
            if (sp(ctx).getLong("asked", 0) == rel.versionCode || activity.isFinishing || activity.isDestroyed) return@check
            sp(ctx).edit().putLong("asked", rel.versionCode).apply()
            offer(activity, rel)
        }
    }

    // ---------- offering and installing ----------

    /** "Version 1.0.5 is available": what changed, and Update / Later. */
    fun offer(activity: MainActivity, rel: Release) {
        val shown = rel.changes.take(12)
        val more = rel.changes.size - shown.size
        val text = buildString {
            append("Andromon Companion ${rel.version}")
            if (rel.build > 0) append(" (build ${rel.build})")
            append(" is available")
            if (rel.date.isNotEmpty()) append(", released ${rel.date}")
            append(". You have ${currentName(activity)}.\n\nDownload: ${"%.1f".format(rel.size / 1e6)} MB")
            if (shown.isNotEmpty()) {
                append("\n\nWhat's new:\n")
                shown.forEach { append("• ").append(it).append('\n') }
                if (more > 0) append("…and $more more")
            }
        }.trimEnd()
        AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage(text)
            .setPositiveButton("Update") { _, _ -> install(activity, rel) }
            .setNeutralButton("Release page") { _, _ ->
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.page))) }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun currentName(ctx: Context): String {
        val name = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        return "$name (build ${BuildConfig.BUILD_NUMBER})"
    }

    /** Downloads [rel], checks it against latest.json and hands it to Android's installer. */
    fun install(activity: MainActivity, rel: Release) {
        // Android asks once before the companion may install apps (as for F-Droid's).
        if (!activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("Allow installing the update")
                .setMessage("Android asks once before Andromon Companion may install apps. Turn on “Allow from this source” on the next page, then come back and tap Update again.")
                .setPositiveButton("Open settings") { _, _ ->
                    runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))) }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        if (!busy.compareAndSet(false, true)) return
        val status = TextView(activity).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            text = "Starting the download…"
        }
        val cancelled = AtomicBoolean(false)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Updating to ${rel.version}")
            .setView(status)
            .setNegativeButton("Cancel") { _, _ -> cancelled.set(true) }
            .setCancelable(false)
            .show()
        val app = activity.applicationContext
        thread(isDaemon = true, name = "update-download") {
            try {
                val apk = download(app, rel, cancelled) { done ->
                    main.post { status.text = "Downloading… ${done * 100 / rel.size}% of ${"%.1f".format(rel.size / 1e6)} MB" }
                }
                main.post { status.text = "Getting the update ready to install…" }
                commit(app, apk)
                main.post { runCatching { dialog.dismiss() } }
            } catch (e: Exception) {
                main.post {
                    runCatching { dialog.dismiss() }
                    if (!cancelled.get() && !activity.isFinishing) {
                        AlertDialog.Builder(activity).setTitle("Update not installed")
                            .setMessage(if (e is IOException && e.message == "cancelled") "The download was cancelled." else friendly(e))
                            .setPositiveButton("OK", null).show()
                    }
                }
            } finally {
                busy.set(false)
            }
        }
    }

    private fun download(ctx: Context, rel: Release, cancelled: AtomicBoolean, progress: (Long) -> Unit): File {
        val dir = File(ctx.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val apk = File(dir, "Andromon-Companion-${rel.versionCode}.apk")
        val c = open(rel.url) // GitHub sends it on to its file servers; HttpURLConnection follows https → https
        try {
            if (c.responseCode != 200) throw IOException("The download failed (HTTP ${c.responseCode}).")
            val hash = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var last = 0L
            c.inputStream.use { input ->
                apk.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (cancelled.get()) throw IOException("cancelled")
                        done += n
                        if (done > rel.size) throw IOException("The download is larger than the release says.")
                        hash.update(buf, 0, n)
                        out.write(buf, 0, n)
                        val now = System.currentTimeMillis()
                        if (now - last > 250) { last = now; progress(done) }
                    }
                }
            }
            val sha = hash.digest().joinToString("") { "%02x".format(it) }
            if (done != rel.size || sha != rel.sha256) throw IOException("The download doesn't match the release. Nothing was installed.")
            return apk
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        } finally {
            c.disconnect()
        }
    }

    /** Copies [apk] into an installer session for this app; Android's answer comes to [UpdateInstallReceiver]. */
    private fun commit(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
        }
        val id = try { installer.createSession(params) } catch (e: Exception) {
            throw IOException("Android couldn't start the install. Check the free space on the phone.")
        }
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 256 * 1024) }
                    session.fsync(out)
                }
                val intent = Intent(ctx, UpdateInstallReceiver::class.java).setAction(ACTION_STATUS)
                // Mutable: Android adds the result to the intent.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw IOException("Android couldn't read the download. Check the free space on the phone.")
        }
    }

    /** Android's answer to [commit]. On success Android replaces and restarts the app, so there is nothing to do. */
    fun onInstallStatus(ctx: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                runCatching { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> File(ctx.cacheDir, "update").deleteRecursively()
            else -> {
                File(ctx.cacheDir, "update").deleteRecursively()
                val why = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> null // the person said no
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "The update is signed with a different key than the app on this phone. Uninstall Andromon Companion, then install the new version from the release page."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "There isn't enough free space on the phone."
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This version isn't made for this phone."
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "The phone's settings don't allow this install."
                    else -> "Android couldn't install the update."
                }
                if (why != null) main.post { android.widget.Toast.makeText(ctx, why, android.widget.Toast.LENGTH_LONG).show() }
            }
        }
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Updates.onInstallStatus(context.applicationContext, intent)
}
