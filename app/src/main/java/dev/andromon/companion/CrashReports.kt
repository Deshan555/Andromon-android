// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Only here to switch the crash reports on before anything else runs. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReports.install(this)
    }
}

/**
 * Crash reports the person decides about. When the app crashes, the technical details (app and
 * Android version, phone model, the error and where it happened; no personal data) are written to
 * a file in the app's private storage. Nothing is sent. The next time the app opens it says so
 * once and offers to share the report through any app (e-mail, a chat) or to throw it away.
 */
internal object CrashReports {
    private const val FILE = "last-crash.txt"
    private const val MAX = 24_000

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)
    private fun sp(ctx: Context) = ctx.getSharedPreferences("crash", Context.MODE_PRIVATE)

    fun install(app: Application) {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
                file(app).writeText(buildString {
                    append("Andromon Companion ").append(version ?: "?").append('\n')
                    append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                    append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                    append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())).append('\n')
                    append("Thread: ").append(thread.name).append("\n\n")
                    append(trace.take(MAX))
                })
                sp(app).edit().putBoolean("offered", false).commit()
            }
            before?.uncaughtException(thread, error) // Android's own handling: closes the app
        }
    }

    /** The saved report, or null when the app hasn't crashed (or the report was thrown away). */
    fun last(ctx: Context): String? = runCatching { file(ctx).takeIf { it.isFile }?.readText() }.getOrNull()

    fun discard(ctx: Context) { file(ctx).delete() }

    /** Once per crash, when the app opens again: tell the person and let them decide. */
    fun offer(host: Activity) {
        val report = last(host) ?: return
        if (sp(host).getBoolean("offered", false)) return
        sp(host).edit().putBoolean("offered", true).apply()
        AlertDialog.Builder(host)
            .setTitle("The app closed unexpectedly")
            .setMessage("A report with the technical details was saved on this phone. Nothing has been sent.\n\n" +
                "Sharing it with the developers helps them fix the problem. You can read it first.")
            .setPositiveButton("Share…") { _, _ -> share(host, report) }
            .setNeutralButton("Read") { _, _ -> show(host) }
            .setNegativeButton("Delete") { _, _ -> discard(host) }
            .show()
    }

    /** The report in full, with Share and Delete (also opened from Settings › About). */
    fun show(host: Activity) {
        val report = last(host)
        if (report == null) {
            AlertDialog.Builder(host).setTitle("Crash reports")
                .setMessage("No crash has been recorded. If the app ever closes unexpectedly, the details are kept on this " +
                    "phone and you decide whether to share them.")
                .setPositiveButton("Close", null).show()
            return
        }
        val d = host.resources.displayMetrics.density
        val text = TextView(host).apply {
            this.text = report
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), (8 * d).toInt())
        }
        AlertDialog.Builder(host)
            .setTitle("Crash report")
            .setView(ScrollView(host).apply { addView(text) })
            .setPositiveButton("Share…") { _, _ -> share(host, report) }
            .setNeutralButton("Delete") { _, _ -> discard(host) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun share(host: Activity, report: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Andromon Companion crash report")
            .putExtra(Intent.EXTRA_TEXT, report)
        runCatching { host.startActivity(Intent.createChooser(send, "Share the crash report")) }
    }
}
