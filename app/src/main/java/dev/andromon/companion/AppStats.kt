// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * Per-app statistics for the Stats screen and the `stats.*` API: screen time and opens
 * ([usage]), mobile and Wi‑Fi data ([data]), battery use since the last full charge ([battery])
 * and app sizes ([storage]). Everything is read from Android's own records; nothing is
 * collected by this app. Each call blocks: run it off the main thread.
 */
internal object AppStats {

    /** One row of a list: an app (or a group such as "Removed apps") and its figures. */
    class Row(
        val key: String, val pkg: String?, val label: String, val icon: Drawable?,
        /** The figure the list is sorted by: ms, bytes or mAh. */
        val value: Double,
        val extra: Map<String, Any> = emptyMap(),
    )

    /** One day of a 7-day chart; [values] stacks (e.g. mobile, Wi‑Fi). */
    class DayBar(val date: LocalDate, val start: Long, val values: List<Double>)

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private fun dayStart(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()

    // ---------- names and icons ----------

    private class Look(val label: String, val icon: Drawable?, val system: Boolean)
    private val looks = HashMap<String, Look>()

    private fun look(ctx: Context, pkg: String): Look = synchronized(looks) {
        looks.getOrPut(pkg) {
            val pm = ctx.packageManager
            val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
            Look(ai?.let { runCatching { it.loadLabel(pm).toString() }.getOrNull() } ?: pkg,
                ai?.let { runCatching { it.loadIcon(pm) }.getOrNull() },
                ai != null && ai.flags and ApplicationInfo.FLAG_SYSTEM != 0)
        }
    }

    fun hasUsageAccess(ctx: Context) = Cleaner.hasUsageAccess(ctx)

    // ---------- usage ----------

    class Usage(
        val days: Int, val from: Long, val to: Long,
        /** Per app over the chosen days: screen time (value, ms), opens, last used. */
        val apps: List<Row>,
        val totalMs: Long, val opens: Int,
        /** The last 7 days, oldest first: screen time and opens per day. */
        val daily: List<DayBar>, val dailyOpens: List<Int>,
    )

    private var usageCache: Pair<Long, Usage>? = null

    /**
     * Screen time and opens from Android's usage events: an app counts from the moment one of its
     * screens comes to the front until it leaves, the screen turns off or the phone locks. An
     * "open" is the app coming to the front after another app or after the screen was off. Home
     * screens and the system UI aren't counted. Needs usage access.
     */
    fun usage(ctx: Context, days: Int): Usage {
        val n = days.coerceIn(1, 7)
        usageCache?.let { (t, u) -> if (System.currentTimeMillis() - t < 30_000 && u.days == n) return u }
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val today = LocalDate.now()
        val first = today.minusDays(6)
        val start = dayStart(first)
        val now = System.currentTimeMillis()
        val ignore = homeApps(ctx) + setOf("com.android.systemui", "android")

        val perDay = HashMap<String, LongArray>()   // pkg -> ms per day (7)
        val opensDay = HashMap<String, IntArray>()  // pkg -> opens per day
        val last = HashMap<String, Long>()

        fun dayIndex(t: Long) = ((t - start) / 86_400_000L).toInt().let { i ->
            // DST days aren't 24 h long: correct with the real date.
            val d = java.time.Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
            (d.toEpochDay() - first.toEpochDay()).toInt().takeIf { it in 0..6 } ?: i.coerceIn(0, 6)
        }
        fun add(pkg: String, a: Long, b: Long) {
            var from = maxOf(a, start); val to = minOf(b, now)
            while (from < to) {
                val d = dayIndex(from)
                val end = minOf(to, dayStart(first.plusDays(d + 1L)))
                perDay.getOrPut(pkg) { LongArray(7) }[d] += end - from
                if (end <= from) break
                from = end
            }
        }

        var current: String? = null
        var since = 0L
        var lastEvent = 0L // when anything last happened: a phone that went off without saying so stopped then
        var previous: String? = null // the app in front before the current one; null after the screen went off
        val events = usm.queryEvents(start - 3 * 3_600_000L, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val t = e.timeStamp
            val pkg = e.packageName
            if (e.eventType == DEVICE_STARTUP) {
                // Switched off (or the battery ran out) without a shutdown event: the app in front stopped then.
                current?.let { add(it, since, maxOf(since, lastEvent)) }
                current = null; previous = null
                continue
            }
            lastEvent = t
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (pkg in ignore) {
                        current?.let { add(it, since, t) }
                        current = null; previous = null
                        continue
                    }
                    if (current != pkg) {
                        current?.let { add(it, since, t) }
                        if (previous != pkg && t >= start) opensDay.getOrPut(pkg) { IntArray(7) }[dayIndex(t)]++
                        current = pkg; since = t; previous = pkg
                    }
                    last[pkg] = maxOf(last[pkg] ?: 0L, t)
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> if (current == pkg) {
                    add(pkg, since, t); current = null; last[pkg] = maxOf(last[pkg] ?: 0L, t)
                }
                SCREEN_NON_INTERACTIVE, KEYGUARD_SHOWN, DEVICE_SHUTDOWN -> {
                    current?.let { add(it, since, t); last[it] = maxOf(last[it] ?: 0L, t) }
                    current = null; previous = null
                }
            }
        }
        current?.let { add(it, since, now); last[it] = now }

        val range = (7 - n) until 7
        val apps = (perDay.keys + opensDay.keys).toSet().mapNotNull { pkg ->
            val ms = perDay[pkg]?.let { a -> range.sumOf { a[it] } } ?: 0L
            val opens = opensDay[pkg]?.let { a -> range.sumOf { a[it] } } ?: 0
            if (ms < 1000 && opens == 0) return@mapNotNull null
            val l = look(ctx, pkg)
            Row(pkg, pkg, l.label, l.icon, ms.toDouble(), mapOf("opens" to opens, "lastUsed" to (last[pkg] ?: 0L)))
        }.sortedByDescending { it.value }
        val daily = (0..6).map { d -> DayBar(first.plusDays(d.toLong()), dayStart(first.plusDays(d.toLong())), listOf(perDay.values.sumOf { it[d] }.toDouble())) }
        val dailyOpens = (0..6).map { d -> opensDay.values.sumOf { it[d] } }
        val u = Usage(n, dayStart(today.minusDays(n - 1L)), now, apps, apps.sumOf { it.value }.toLong(),
            apps.sumOf { (it.extra["opens"] as Int) }, daily, dailyOpens)
        usageCache = System.currentTimeMillis() to u
        return u
    }

    // Event types added after API 26, by value so older SDK stubs don't matter.
    private const val SCREEN_NON_INTERACTIVE = 16
    private const val KEYGUARD_SHOWN = 17
    private const val DEVICE_SHUTDOWN = 26
    private const val DEVICE_STARTUP = 27
    // NetworkStatsManager still takes the old network type numbers (ConnectivityManager.TYPE_MOBILE / TYPE_WIFI,
    // deprecated as connection types but not as query types).
    private const val NET_MOBILE = 0
    private const val NET_WIFI = 1

    private fun homeApps(ctx: Context): Set<String> = runCatching {
        ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
    }.getOrDefault(emptySet())

    fun usageJson(ctx: Context, days: Int, limit: Int): JSONObject {
        val n = days.coerceIn(1, 7)
        if (!hasUsageAccess(ctx)) return noAccess(n)
        val u = usage(ctx, n)
        return JSONObject().put("days", n).put("from", u.from).put("to", u.to).put("access", true)
            .put("totalMs", u.totalMs).put("opens", u.opens)
            .put("daily", JSONArray(u.daily.mapIndexed { i, d ->
                JSONObject().put("date", d.date.toString()).put("start", d.start).put("screenMs", d.values[0].toLong()).put("opens", u.dailyOpens[i])
            }))
            .put("apps", JSONArray(u.apps.take(limit).map { r ->
                JSONObject().put("pkg", r.pkg).put("label", r.label).put("screenMs", r.value.toLong())
                    .put("opens", r.extra["opens"]).put("lastUsed", r.extra["lastUsed"])
                    .put("share", share(r.value, u.totalMs.toDouble()))
            }))
    }

    private fun noAccess(days: Int?) = JSONObject().apply { if (days != null) put("days", days) }
        .put("access", false).put("missing", "usage").put("apps", JSONArray())

    private fun share(v: Double, total: Double) = if (total > 0) Math.round(v / total * 1000) / 1000.0 else 0.0

    // ---------- data ----------

    class Data(
        val days: Int, val from: Long, val to: Long, val apps: List<Row>,
        val mobile: Long, val wifi: Long,
        /** The last 7 days: mobile, Wi‑Fi bytes. */
        val daily: List<DayBar>,
        /** Set when mobile data couldn't be read (an Android rule on older versions). */
        val mobileProblem: String?,
    )

    private class Totals(var mobile: Long = 0, var wifi: Long = 0)

    /**
     * Bytes sent and received per app, from Android's network statistics (needs usage access).
     * Android 10 and newer give mobile figures for all SIMs without a subscriber id; older versions
     * need the SIM's id, which takes the Phone permission.
     */
    fun data(ctx: Context, days: Int): Data {
        val n = days.coerceIn(1, 7)
        val nsm = ctx.getSystemService(NetworkStatsManager::class.java)
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        val from = dayStart(today.minusDays(n - 1L))
        var problem: String? = null
        val sub: String? = if (Build.VERSION.SDK_INT >= 29) null else runCatching {
            @Suppress("DEPRECATION", "HardwareIds", "MissingPermission")
            ctx.getSystemService(TelephonyManager::class.java).subscriberId
        }.getOrNull()

        val byUid = HashMap<Int, Totals>()
        fun read(type: Int) {
            val stats: NetworkStats = nsm.querySummary(type, if (type == NET_WIFI) "" else sub, from, now) ?: return
            try {
                val b = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(b)
                    val t = byUid.getOrPut(b.uid) { Totals() }
                    val bytes = b.rxBytes + b.txBytes
                    if (type == NET_WIFI) t.wifi += bytes else t.mobile += bytes
                }
            } finally { stats.close() }
        }
        runCatching { read(NET_WIFI) }
        runCatching { read(NET_MOBILE) }.onFailure {
            problem = if (Build.VERSION.SDK_INT < 29) "Android ${Build.VERSION.RELEASE} only gives mobile data figures with the Phone permission."
                else "Android didn't give mobile data figures."
        }

        // Device totals for the range: what the per-app rows have to add up to.
        fun device(type: Int) = runCatching {
            nsm.querySummaryForDevice(type, if (type == NET_WIFI) "" else sub, from, now).let { it.rxBytes + it.txBytes }
        }.getOrNull()
        val deviceMobile = device(NET_MOBILE)
        val deviceWifi = device(NET_WIFI)

        val pm = ctx.packageManager
        val known = ArrayList<Row>()
        val unknown = Totals()
        for ((uid, t) in byUid) {
            if (t.mobile + t.wifi == 0L) continue
            val extra = mapOf<String, Any>("uid" to uid, "mobile" to t.mobile, "wifi" to t.wifi)
            val pkgs = if (uid >= Process.FIRST_APPLICATION_UID) runCatching { pm.getPackagesForUid(uid) }.getOrNull() else null
            val pkg = pkgs?.firstOrNull()
            when {
                uid == NetworkStats.Bucket.UID_TETHERING -> known += Row("tethering", null, "Hotspot and tethering", null, (t.mobile + t.wifi).toDouble(), extra)
                uid == Process.SYSTEM_UID || uid == 0 -> known += Row("system:$uid", null, "Android system", null, (t.mobile + t.wifi).toDouble(), extra)
                pkg != null -> {
                    val l = look(ctx, pkg)
                    known += Row(pkg, pkg, l.label + if (pkgs.size > 1) " +${pkgs.size - 1}" else "", l.icon, (t.mobile + t.wifi).toDouble(), extra)
                }
                uid < Process.FIRST_APPLICATION_UID && uid >= 0 ->
                    known += Row("uid:$uid", null, pm.getNameForUid(uid)?.let { "System process ($it)" } ?: "System process ($uid)", null, (t.mobile + t.wifi).toDouble(), extra)
                // UID_REMOVED, and uids of apps no longer installed. Android can report an uninstalled app's
                // bytes under both, so they are worked out from the device total below rather than added up.
                else -> { unknown.mobile += t.mobile; unknown.wifi += t.wifi }
            }
        }
        val merged = known.groupBy { it.key }.map { (_, rows) -> if (rows.size == 1) rows[0] else merge(rows) }.toMutableList()
        val removedMobile = deviceMobile?.let { (it - merged.sumOf { r -> r.extra["mobile"] as Long }).coerceIn(0, unknown.mobile) } ?: unknown.mobile
        val removedWifi = deviceWifi?.let { (it - merged.sumOf { r -> r.extra["wifi"] as Long }).coerceIn(0, unknown.wifi) } ?: unknown.wifi
        if (removedMobile + removedWifi > 0) merged += Row("removed", null, "Removed apps", null, (removedMobile + removedWifi).toDouble(),
            mapOf("uid" to NetworkStats.Bucket.UID_REMOVED, "mobile" to removedMobile, "wifi" to removedWifi))
        val apps = merged.sortedByDescending { it.value }

        val first = today.minusDays(6)
        val daily = (0..6).map { d ->
            val day = first.plusDays(d.toLong())
            val a = dayStart(day); val b = minOf(dayStart(day.plusDays(1)), now)
            fun dev(type: Int) = runCatching {
                nsm.querySummaryForDevice(type, if (type == NET_WIFI) "" else sub, a, b).let { it.rxBytes + it.txBytes }
            }.getOrDefault(0L)
            DayBar(day, a, listOf(dev(NET_MOBILE).toDouble(), dev(NET_WIFI).toDouble()))
        }
        return Data(n, from, now, apps, apps.sumOf { it.extra["mobile"] as Long }, apps.sumOf { it.extra["wifi"] as Long }, daily, problem)
    }

    private fun merge(rows: List<Row>): Row {
        val m = rows.sumOf { it.extra["mobile"] as Long }; val w = rows.sumOf { it.extra["wifi"] as Long }
        val r = rows[0]
        return Row(r.key, r.pkg, r.label, r.icon, (m + w).toDouble(), r.extra + mapOf("mobile" to m, "wifi" to w))
    }

    fun dataJson(ctx: Context, days: Int, limit: Int): JSONObject {
        val n = days.coerceIn(1, 7)
        if (!hasUsageAccess(ctx)) return noAccess(n)
        val d = data(ctx, n)
        val total = (d.mobile + d.wifi).toDouble()
        return JSONObject().put("days", n).put("from", d.from).put("to", d.to).put("access", true)
            .put("mobileBytes", d.mobile).put("wifiBytes", d.wifi).put("totalBytes", d.mobile + d.wifi)
            .put("mobileProblem", d.mobileProblem ?: JSONObject.NULL)
            .put("daily", JSONArray(d.daily.map { b ->
                JSONObject().put("date", b.date.toString()).put("start", b.start).put("mobileBytes", b.values[0].toLong()).put("wifiBytes", b.values[1].toLong())
            }))
            .put("apps", JSONArray(d.apps.take(limit).map { r ->
                JSONObject().put("uid", r.extra["uid"]).put("pkg", r.pkg ?: JSONObject.NULL).put("label", r.label)
                    .put("mobileBytes", r.extra["mobile"]).put("wifiBytes", r.extra["wifi"]).put("totalBytes", r.value.toLong())
                    .put("share", share(r.value, total))
            }))
    }

    // ---------- battery ----------

    class Battery(
        /** "batterystats" (Android's BatteryUsageStats, Android 12+ with BATTERY_STATS), "helper" (privileged access) or "none". */
        val source: String, val apps: List<Row>, val totalMah: Double?, val since: Long?, val reason: String?,
    )

    /**
     * Battery use per app since the last full charge, as Android estimates it. Tried in order:
     * Android's own BatteryUsageStats (Android 12+, needs BATTERY_STATS, which Andromon grants over
     * adb); `dumpsys batterystats` through privileged access (the Battery screen's source); else
     * nothing, with the reason.
     */
    fun battery(ctx: Context): Battery {
        var why: String? = null
        if (Build.VERSION.SDK_INT >= 31 && ctx.checkSelfPermission("android.permission.BATTERY_STATS") == PackageManager.PERMISSION_GRANTED) {
            try { return fromUsageStats(ctx) } catch (e: Throwable) { why = e.javaClass.simpleName }
        }
        try {
            val r = BatteryUse.load(ctx)
            return Battery("helper", r.apps.mapIndexed { i, a -> Row(a.pkg ?: "row:$i", a.pkg, a.label, a.icon, a.mah.toDouble()) },
                r.computedMah?.toDouble() ?: r.apps.sumOf { it.mah.toDouble() }, null, null)
        } catch (_: Throwable) {}
        val reason = when {
            Build.VERSION.SDK_INT < 31 -> "Android ${Build.VERSION.RELEASE} only shares battery use per app with privileged access (Settings › Privileged access)."
            ctx.checkSelfPermission("android.permission.BATTERY_STATS") != PackageManager.PERMISSION_GRANTED ->
                "Android only shares battery use per app with apps it trusts. Connect the phone to Andromon once over USB, or set up privileged access in Settings."
            // Android 12+ keeps BatteryUsageStats to system apps (the hidden-API list blocks it), even with BATTERY_STATS.
            else -> "Android doesn't let apps read battery use per app. With privileged access (Settings › Privileged access) " +
                "this app can read Android's own estimate, the same one the Battery screen shows."
        }
        if (why != null) android.util.Log.i("AndromonStats", "BatteryUsageStats not available: $why")
        return Battery("none", emptyList(), null, null, reason)
    }

    /** android.os.BatteryStatsManager#getBatteryUsageStats (a system API, Android 12+), by reflection. */
    private fun fromUsageStats(ctx: Context): Battery {
        val mgr = ctx.getSystemService("batterystats") ?: throw IllegalStateException("no service")
        val stats = mgr.javaClass.getMethod("getBatteryUsageStats").invoke(mgr) ?: throw IllegalStateException("no stats")
        try {
            val cls = stats.javaClass
            val total = (cls.getMethod("getConsumedPower").invoke(stats) as Number).toDouble()
            val since = runCatching { (cls.getMethod("getStatsStartTimestamp").invoke(stats) as Number).toLong() }.getOrNull()
            val consumers = cls.getMethod("getUidBatteryConsumers").invoke(stats) as List<*>
            val pm = ctx.packageManager
            val byKey = LinkedHashMap<String, Row>()
            for (c in consumers) {
                c ?: continue
                val uid = (c.javaClass.getMethod("getUid").invoke(c) as Number).toInt()
                val mah = (c.javaClass.getMethod("getConsumedPower").invoke(c) as Number).toDouble()
                if (mah < 0.05) continue
                val appId = uid % 100_000
                val row = when {
                    appId == Process.SYSTEM_UID -> Row("system", null, "Android system", null, mah)
                    appId == 0 -> Row("kernel", null, "Kernel (root)", null, mah)
                    appId < Process.FIRST_APPLICATION_UID -> Row("uid:$appId", null, pm.getNameForUid(uid)?.substringBefore(':')?.let { "System process ($it)" } ?: "System process ($appId)", null, mah)
                    uid / 100_000 != 0 -> Row("user:$uid", null, "App of another user", null, mah)
                    else -> {
                        val pkgs = runCatching { pm.getPackagesForUid(uid) }.getOrNull()
                        val pkg = pkgs?.firstOrNull()
                        if (pkg == null) Row("removed", null, "Removed apps", null, mah)
                        else { val l = look(ctx, pkg); Row(pkg, pkg, l.label + if (pkgs.size > 1) " +${pkgs.size - 1}" else "", l.icon, mah) }
                    }
                }
                val old = byKey[row.key]
                byKey[row.key] = if (old == null) row else Row(old.key, old.pkg, old.label, old.icon, old.value + row.value)
            }
            return Battery("batterystats", byKey.values.sortedByDescending { it.value }, total, since, null)
        } finally {
            runCatching { stats.javaClass.getMethod("close").invoke(stats) }
        }
    }

    fun batteryJson(ctx: Context, limit: Int): JSONObject {
        val b = battery(ctx)
        val total = b.totalMah ?: b.apps.sumOf { it.value }
        return JSONObject().put("available", b.source != "none").put("source", b.source)
            .put("since", b.since ?: JSONObject.NULL).put("totalMah", if (b.source == "none") JSONObject.NULL else round1(total))
            .put("reason", b.reason ?: JSONObject.NULL)
            .put("apps", JSONArray(b.apps.take(limit).map { r ->
                JSONObject().put("pkg", r.pkg ?: JSONObject.NULL).put("label", r.label).put("mah", round1(r.value)).put("share", share(r.value, total))
            }))
    }

    private fun round1(v: Double) = Math.round(v * 10) / 10.0

    // ---------- storage ----------

    class Storage(val apps: List<Row>, val appBytes: Long, val dataBytes: Long, val cacheBytes: Long)

    /** App, data and cache size per installed app (StorageStatsManager, needs usage access). */
    fun storage(ctx: Context): Storage {
        val pm = ctx.packageManager
        val ssm = ctx.getSystemService(StorageStatsManager::class.java)
        val user = Process.myUserHandle()
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
        val rows = pm.getInstalledApplications(flags).mapNotNull { ai ->
            val s = runCatching { ssm.queryStatsForPackage(ai.storageUuid, ai.packageName, user) }.getOrNull() ?: return@mapNotNull null
            val data = (s.dataBytes - s.cacheBytes).coerceAtLeast(0)
            val total = s.appBytes + data + s.cacheBytes
            val l = look(ctx, ai.packageName)
            Row(ai.packageName, ai.packageName, l.label, l.icon, total.toDouble(),
                mapOf("app" to s.appBytes, "data" to data, "cache" to s.cacheBytes, "system" to (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0)))
        }.sortedByDescending { it.value }
        return Storage(rows, rows.sumOf { it.extra["app"] as Long }, rows.sumOf { it.extra["data"] as Long }, rows.sumOf { it.extra["cache"] as Long })
    }

    fun storageJson(ctx: Context, limit: Int): JSONObject {
        if (!hasUsageAccess(ctx)) return noAccess(null)
        val s = storage(ctx)
        val total = (s.appBytes + s.dataBytes + s.cacheBytes).toDouble()
        return JSONObject().put("access", true).put("appBytes", s.appBytes).put("dataBytes", s.dataBytes).put("cacheBytes", s.cacheBytes)
            .put("totalBytes", total.toLong()).put("count", s.apps.size)
            .put("apps", JSONArray(s.apps.take(limit).map { r ->
                JSONObject().put("pkg", r.pkg).put("label", r.label).put("system", r.extra["system"])
                    .put("appBytes", r.extra["app"]).put("dataBytes", r.extra["data"]).put("cacheBytes", r.extra["cache"])
                    .put("totalBytes", r.value.toLong()).put("share", share(r.value, total))
            }))
    }

    // ---------- API ----------

    fun call(ctx: Context, method: String, p: JSONObject): Any {
        val limit = p.optInt("limit", 100).coerceIn(1, 1000)
        return when (method) {
            "stats.usage" -> usageJson(ctx, p.optInt("days", 1), limit)
            "stats.data" -> dataJson(ctx, p.optInt("days", 1), limit)
            "stats.battery" -> batteryJson(ctx, limit)
            "stats.storage" -> storageJson(ctx, limit)
            "stats.link" -> LinkStats.json(ctx, p.optInt("days", 1))
            else -> throw IllegalArgumentException("Unknown method: $method")
        }
    }
}
