// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

package dev.droiddesk.companion

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Battery history: level, charging state, temperature and voltage, sampled about every 15 minutes
 * by [BatteryJob] (also while the app is closed) and whenever the Battery screen is open. Seven
 * days are kept in a small app-private file (10 bytes per sample); nothing leaves the phone.
 */
internal object BatteryLog {

    class Sample(val time: Long, val level: Int, val charging: Boolean, val tempC: Float?, val voltageMv: Int?)

    const val HOUR = 3_600_000L
    const val DAY = 24 * HOUR
    const val KEEP_MS = 7 * DAY
    /** Two samples further apart than this have "no samples" between them (phone asleep or off). */
    const val GAP_MS = 40 * 60_000L

    private const val REC = 10
    private const val MAX_RECORDS = 6000
    private const val JOB_ID = 4101
    private const val NO_TEMP = Short.MIN_VALUE

    private fun file(ctx: Context) = File(ctx.filesDir, "battery_history.bin")

    /** Asks Android to run [BatteryJob] every 15 minutes (its shortest period). Safe to call often. */
    fun schedule(ctx: Context) {
        runCatching {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            fun info(persist: Boolean) = JobInfo.Builder(JOB_ID, ComponentName(ctx, BatteryJob::class.java))
                .setPeriodic(15 * 60_000L)
                .setPersisted(persist) // keep the schedule across restarts (RECEIVE_BOOT_COMPLETED)
                .build()
            runCatching { js.schedule(info(true)) }.onFailure { js.schedule(info(false)) }
        }
    }

    /** Appends the current reading, unless the last one is under a minute old and says the same. */
    @Synchronized
    fun record(ctx: Context) {
        runCatching {
            val b = Phone.battery(ctx)
            val now = System.currentTimeMillis()
            val f = file(ctx)
            val last = last(f)
            if (last != null && now >= last.time && now - last.time < 50_000 &&
                last.level == b.level && last.charging == b.charging) return
            val rec = ByteBuffer.allocate(REC)
                .putInt((now / 1000).toInt())
                .put(b.level.toByte())
                .put((if (b.charging) 1 else 0).toByte())
                .putShort(b.tempC?.let { (it * 10).toInt().coerceIn(-1000, 2000).toShort() } ?: NO_TEMP)
                .putShort((b.voltageMv ?: 0).coerceIn(0, Short.MAX_VALUE.toInt()).toShort())
                .array()
            RandomAccessFile(f, "rw").use { raf ->
                raf.seek(raf.length() - raf.length() % REC) // never start in the middle of a torn record
                raf.write(rec)
            }
            trim(f, now)
        }
    }

    private fun last(f: File): Sample? {
        val n = f.length() / REC
        if (n < 1) return null
        val buf = ByteArray(REC)
        RandomAccessFile(f, "r").use { it.seek((n - 1) * REC); it.readFully(buf) }
        return parse(ByteBuffer.wrap(buf))
    }

    private fun parse(b: ByteBuffer): Sample {
        val time = (b.int.toLong() and 0xFFFFFFFFL) * 1000
        val level = b.get().toInt()
        val flags = b.get().toInt()
        val temp = b.short
        val volt = b.short.toInt()
        return Sample(time, level, flags and 1 != 0, if (temp == NO_TEMP) null else temp / 10f, volt.takeIf { it > 0 })
    }

    private fun readAll(f: File): List<Sample> {
        if (!f.exists()) return emptyList()
        val bytes = f.readBytes()
        val buf = ByteBuffer.wrap(bytes)
        return List(bytes.size / REC) { parse(buf) }
    }

    /** Drops samples older than a week once the oldest is a day past that, or the file grows too long. */
    private fun trim(f: File, now: Long) {
        val n = f.length() / REC
        val first = ByteArray(REC).also { buf -> RandomAccessFile(f, "r").use { it.readFully(buf) } }.let { parse(ByteBuffer.wrap(it)) }
        if (n <= MAX_RECORDS && now - first.time < KEEP_MS + DAY) return
        val keep = readAll(f).filter { now - it.time < KEEP_MS }.takeLast(MAX_RECORDS * 3 / 4)
        val out = ByteBuffer.allocate(keep.size * REC)
        for (s in keep) {
            out.putInt((s.time / 1000).toInt()).put(s.level.toByte()).put((if (s.charging) 1 else 0).toByte())
                .putShort(s.tempC?.let { (it * 10).toInt().toShort() } ?: NO_TEMP).putShort((s.voltageMv ?: 0).toShort())
        }
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(out.array())
        if (!tmp.renameTo(f)) { f.writeBytes(out.array()); tmp.delete() }
    }

    /** Samples of the last [windowMs], oldest first. Readings "from the future" (clock was changed) are left out. */
    @Synchronized
    fun read(ctx: Context, windowMs: Long = KEEP_MS): List<Sample> {
        val now = System.currentTimeMillis()
        return runCatching { readAll(file(ctx)) }.getOrDefault(emptyList())
            .filter { it.level in 0..100 && it.time <= now + 60_000 && now - it.time <= windowMs }
            .sortedBy { it.time }
    }

    /**
     * Figures worked out from the samples. Each is null when there isn't enough data for an honest
     * answer; nothing is guessed.
     */
    class Stats(
        val drainPerHour: Float?,     // % per hour while on battery, over the chosen window
        val drainMs: Long,            // how much on-battery time that average covers
        val lastFull: Long?,          // when the phone was last seen at 100%
        val leftMs: Long?,            // estimate: level / recent drain
        val leftBasis: String?,       // what the estimate is based on
        val tempMin: Float?, val tempMax: Float?, val tempAvg: Float?,
    )

    /** Percent lost and time covered, over pairs of neighbouring samples that are both on battery. */
    private fun drain(samples: List<Sample>, since: Long): Pair<Float, Long> {
        var pct = 0f
        var ms = 0L
        for (i in 1 until samples.size) {
            val a = samples[i - 1]; val b = samples[i]
            if (a.time < since || a.charging || b.charging) continue
            val dt = b.time - a.time
            // A rise means it was charged in between; a very long gap means the phone was probably off.
            if (dt <= 0 || dt > 6 * HOUR || b.level > a.level) continue
            pct += a.level - b.level
            ms += dt
        }
        return pct to ms
    }

    /** [week]: every kept sample; [windowMs]: the range the chart shows. */
    fun stats(week: List<Sample>, windowMs: Long, levelNow: Int, chargingNow: Boolean): Stats {
        val now = System.currentTimeMillis()
        val (pct, ms) = drain(week, now - windowMs)
        val rate = if (ms >= 30 * 60_000L) pct / (ms / HOUR.toFloat()) else null

        var left: Long? = null
        var basis: String? = null
        if (!chargingNow) {
            val (p3, m3) = drain(week, now - 3 * HOUR)
            val (p24, m24) = drain(week, now - DAY)
            when {
                m3 >= 30 * 60_000L && p3 >= 1f -> { left = (levelNow / (p3 / m3) ).toLong(); basis = "the last 3 hours" }
                m24 >= HOUR && p24 >= 1f -> { left = (levelNow / (p24 / m24)).toLong(); basis = "the last 24 hours" }
            }
        }
        val temps = week.filter { now - it.time <= windowMs }.mapNotNull { it.tempC }
        return Stats(
            drainPerHour = rate, drainMs = ms,
            lastFull = week.lastOrNull { it.level >= 100 }?.time,
            leftMs = left, leftBasis = basis,
            tempMin = temps.minOrNull(), tempMax = temps.maxOrNull(),
            tempAvg = if (temps.isEmpty()) null else temps.sum() / temps.size,
        )
    }
}

/** Runs about every 15 minutes (Android decides exactly when, and waits while the phone dozes). */
class BatteryJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        thread(isDaemon = true) {
            BatteryLog.record(applicationContext)
            runCatching { jobFinished(params, false) }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?) = false
}

/**
 * Per-app battery use since the last full charge, as Android itself estimates it. Apps can't read
 * this; it comes from `dumpsys batterystats` through the shell helper ([ShellHelper]) when that is
 * running. No helper, no figures.
 */
internal object BatteryUse {

    class App(val label: String, val pkg: String?, val icon: Drawable?, val mah: Float)
    class Result(val apps: List<App>, val computedMah: Float?)

    /** The helper is running but is an older copy that doesn't know this request. */
    class OldHelper : IOException("The helper needs a restart")

    private val UID_LINE = Regex("""^\s*U(?:ID|id) (\S+): ([0-9]+(?:\.[0-9]+)?)""")
    private val COMPUTED = Regex("""Computed drain: ([0-9]+(?:\.[0-9]+)?)""")
    private val APP_UID = Regex("""^u(\d+)a(\d+)$""")

    /** Blocks. Throws [ShellHelperClient.Unavailable] without the helper, [OldHelper] for an old one. */
    fun load(ctx: Context): Result {
        val text = try {
            Privileged.call(ctx, "batteryUse") as String
        } catch (e: ShellHelperClient.Unavailable) {
            throw e
        } catch (e: IOException) {
            if (e.message?.startsWith("Unknown method") == true) throw OldHelper() else throw e
        }
        val pm = ctx.packageManager
        var computed: Float? = null
        val apps = ArrayList<App>()
        for (line in text.lineSequence()) {
            if (computed == null) COMPUTED.find(line)?.let { computed = it.groupValues[1].toFloatOrNull() }
            val m = UID_LINE.find(line) ?: continue
            val mah = m.groupValues[2].toFloatOrNull() ?: continue
            if (mah < 0.05f) continue
            val id = m.groupValues[1]
            val app = APP_UID.find(id)
            when {
                app != null && app.groupValues[1] == "0" -> {
                    val pkgs = runCatching { pm.getPackagesForUid(10_000 + app.groupValues[2].toInt()) }.getOrNull()
                    val pkg = pkgs?.firstOrNull()
                    val ai = pkg?.let { runCatching { pm.getApplicationInfo(it, 0) }.getOrNull() }
                    val name = ai?.let { runCatching { it.loadLabel(pm).toString() }.getOrNull() } ?: pkg ?: "Removed app ($id)"
                    apps += App(name + if ((pkgs?.size ?: 0) > 1) " +${pkgs!!.size - 1}" else "", pkg,
                        ai?.let { runCatching { it.loadIcon(pm) }.getOrNull() }, mah)
                }
                app != null -> apps += App("App of another user ($id)", null, null, mah)
                id == "1000" -> apps += App("Android system", null, null, mah)
                id == "0" -> apps += App("Kernel (root)", null, null, mah)
                else -> apps += App("System process ($id)", null, null, mah)
            }
        }
        return Result(apps.sortedByDescending { it.mah }, computed?.takeIf { it > 0f })
    }
}
