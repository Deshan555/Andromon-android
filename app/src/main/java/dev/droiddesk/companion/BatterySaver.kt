// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

package dev.droiddesk.companion

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONObject
import java.io.IOException

/**
 * Battery saving beyond what an app may do by itself: Battery Saver as a real switch, Deep sleep
 * (forced Doze), App Standby buckets, background restriction, and who holds wakelocks. Each part
 * says what it needs: [Privileged] (Shizuku, the Droid Desk helper or root) or the
 * WRITE_SECURE_SETTINGS permission Droid Desk grants. Nothing here invents a figure: what the
 * phone doesn't report comes back as null.
 *
 * Calls that go through [Privileged] block: use a background thread.
 */
internal object BatterySaver {

    private fun sp(ctx: Context) = ctx.getSharedPreferences("battery_saver", Context.MODE_PRIVATE)

    fun canWriteSecure(ctx: Context) =
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    // ---------- Battery Saver ----------

    val AUTO_CHOICES = listOf(0, 10, 15, 20, 30, 40, 50)

    fun saverOn(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isPowerSaveMode

    /** Android's own "turn on automatically at X%" (0 = off), or null when it can't be read. */
    fun autoLevel(ctx: Context): Int? =
        runCatching { Settings.Global.getInt(ctx.contentResolver, "low_power_trigger_level") }.getOrNull()

    /** Writes one of the two Battery Saver settings: directly with WRITE_SECURE_SETTINGS, else through [Privileged]. */
    private fun putGlobal(ctx: Context, key: String, value: Int) {
        if (canWriteSecure(ctx) && runCatching { Settings.Global.putInt(ctx.contentResolver, key, value) }.getOrDefault(false)) return
        Privileged.call(ctx, "setGlobal", JSONObject().put("key", key).put("value", value))
    }

    /** Android refuses Battery Saver while the phone is charging; the caller checks [saverOn] afterwards. */
    fun setSaver(ctx: Context, on: Boolean) = putGlobal(ctx, "low_power", if (on) 1 else 0)

    fun setAutoLevel(ctx: Context, level: Int) = putGlobal(ctx, "low_power_trigger_level", level)

    // ---------- Deep sleep (Doze) ----------

    fun dozeOnScreenOff(ctx: Context) = sp(ctx).getBoolean("doze_screen_off", false)
    fun setDozeOnScreenOff(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("doze_screen_off", on).apply()

    /** True from a forced Doze until it was lifted again. */
    fun dozeForced(ctx: Context) = sp(ctx).getBoolean("doze_forced", false)

    /**
     * Puts the phone into Doze now ([on]) or lifts it. Returns Android's own answer
     * ("Now forced in to deep idle mode"). Needs [Privileged].
     */
    fun doze(ctx: Context, on: Boolean): String {
        val out = Privileged.call(ctx, "doze", JSONObject().put("on", on)) as? String ?: ""
        if (on && !out.contains("forced", ignoreCase = true)) throw IOException(out.ifBlank { "Android didn't enter Doze" }.take(200))
        sp(ctx).edit().putBoolean("doze_forced", on).apply()
        return out
    }

    // ---------- App Standby buckets and background restriction ----------

    /** Android's standby buckets, most to least free. RESTRICTED exists from Android 12. */
    enum class Bucket(val code: Int, val arg: String?, val label: String) {
        EXEMPTED(5, null, "Exempt"), ACTIVE(10, "active", "Active"), WORKING_SET(20, "working_set", "Working set"),
        FREQUENT(30, "frequent", "Frequent"), RARE(40, "rare", "Rare"), RESTRICTED(45, "restricted", "Restricted"),
        NEVER(50, null, "Never used");
        companion object { fun of(code: Int) = values().firstOrNull { it.code == code } }
    }

    private val BUCKET_LINE = Regex("""^\s*([A-Za-z][A-Za-z0-9_.]+):\s*(\d+)\s*$""")

    /** Every app's current bucket (`am get-standby-bucket`). Needs [Privileged]; Android 9+. */
    fun buckets(ctx: Context): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (line in (Privileged.call(ctx, "buckets") as String).lineSequence()) {
            val m = BUCKET_LINE.find(line) ?: continue
            out[m.groupValues[1]] = m.groupValues[2].toInt()
        }
        return out
    }

    fun setBucket(ctx: Context, pkg: String, bucket: Bucket): String =
        (Privileged.call(ctx, "setBucket", JSONObject().put("pkg", pkg).put("bucket", bucket.arg ?: error("Can't be set"))) as? String).orEmpty()

    /**
     * Whether "background restricted" is on for an app (what Android's Settings calls Restricted
     * battery use: the RUN_ANY_IN_BACKGROUND app-op). Readable without special rights; null when
     * Android won't say.
     */
    fun restricted(ctx: Context, pkg: String, uid: Int): Boolean? = runCatching {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow("android:run_any_in_background", uid, pkg)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow("android:run_any_in_background", uid, pkg)
        mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED
    }.getOrNull()

    fun setRestricted(ctx: Context, pkg: String, on: Boolean) {
        Privileged.call(ctx, "bgRestrict", JSONObject().put("pkg", pkg).put("on", on))
    }

    // ---------- wakelocks ----------

    class WakeLock(val pkg: String?, val label: String, val tag: String, val type: String)

    // "  PARTIAL_WAKE_LOCK              'AudioMix' ACQ=-3m4s12ms (uid=10123 pid=4567 ws=WorkSource{10200 com.x})"
    private val LOCK_LINE = Regex("""^\s*([A-Z_]+_WAKE_LOCK|[A-Z_]*LOCK)\s+'([^']*)'.*?\(uid=(\d+)""")
    private val WORK_SOURCE = Regex("""ws=WorkSource\{(\d+)""")

    /** Who keeps the phone awake right now (`dumpsys power`). Needs [Privileged]. */
    fun wakeLocks(ctx: Context): List<WakeLock> {
        val pm = ctx.packageManager
        val out = ArrayList<WakeLock>()
        for (line in (Privileged.call(ctx, "wakeLocks") as String).lineSequence()) {
            val m = LOCK_LINE.find(line) ?: continue
            // System services often hold the lock on behalf of an app: the work source names it.
            val uid = WORK_SOURCE.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: m.groupValues[3].toInt()
            val pkg = if (uid >= 10_000) runCatching { pm.getPackagesForUid(uid)?.firstOrNull() }.getOrNull() else null
            val label = when {
                pkg != null -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                uid == 1000 -> "Android system"
                else -> "System (uid $uid)"
            }
            out += WakeLock(pkg, label, m.groupValues[2], m.groupValues[1].removeSuffix("_WAKE_LOCK").lowercase().replace('_', ' '))
        }
        return out
    }

    // ---------- charging ----------

    val LIMIT_CHOICES = listOf(0, 70, 75, 80, 85, 90, 95)
    const val HOT_C = 45f

    /** Notify when charging reaches this level; 0 = off. */
    fun chargeLimit(ctx: Context) = sp(ctx).getInt("charge_limit", 0).takeIf { it in LIMIT_CHOICES } ?: 0
    fun setChargeLimit(ctx: Context, level: Int) = sp(ctx).edit().putInt("charge_limit", level).apply()
    fun fullAlert(ctx: Context) = sp(ctx).getBoolean("full_alert", false)
    fun setFullAlert(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("full_alert", on).apply()
    fun heatAlert(ctx: Context) = sp(ctx).getBoolean("heat_alert", false)
    fun setHeatAlert(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("heat_alert", on).apply()

    /** [milliAmps] flows into the battery when positive. [watts] = current × voltage. Null = not reported. */
    class Charge(val milliAmps: Float?, val watts: Float?)

    /**
     * The battery current as the phone reports it. Android asks for microamps, but some phones
     * report milliamps and some turn the sign round; the unit is told apart by size (no phone
     * charges at more than 20 A), and the sign is taken from whether the phone is charging.
     */
    fun charge(ctx: Context, b: Phone.Battery): Charge {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val raw = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrDefault(0)
        if (raw == 0 || raw == Int.MIN_VALUE || raw == Int.MAX_VALUE) return Charge(null, null)
        val size = Math.abs(raw.toLong()).toFloat()
        val ma = (if (size > 20_000f) size / 1000f else size) * (if (b.charging) 1f else -1f)
        val watts = b.voltageMv?.let { Math.abs(ma) / 1000f * (it / 1000f) }
        return Charge(ma, watts)
    }
}
