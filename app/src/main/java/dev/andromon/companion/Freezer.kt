// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors
//
// Freeze modes and the "pending freeze" decision are ported from SuperFreezZ
// (https://gitlab.com/SuperFreezZ/SuperFreezZ, commit 267d0f7): backend/AppInformationGetter.kt
// and database/Database.kt. Original notice:
//
//   Copyright (c) 2018 Hocuri
//
//   This file is part of SuperFreezZ.
//
//   SuperFreezZ is free software: you can redistribute it and/or modify
//   it under the terms of the GNU General Public License as published by
//   the Free Software Foundation, either version 3 of the License, or
//   (at your option) any later version.
//
//   SuperFreezZ is distributed in the hope that it will be useful,
//   but WITHOUT ANY WARRANTY; without even the implied warranty of
//   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//   GNU General Public License for more details.
//
//   You should have received a copy of the GNU General Public License
//   along with SuperFreezZ.  If not, see <http://www.gnu.org/licenses/>.
//
// Andromon uses it under GPL version 3. Changed for Andromon: Kotlin object instead of
// top-level functions, the protected-app and "in use right now" rules, the installer check that
// replaces SuperFreezZ's built-in list of F-Droid package names, and freezing through
// Privileged (Shizuku / helper / root) next to the accessibility method.

package dev.andromon.companion

import android.app.admin.DevicePolicyManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityManager

/**
 * The Freezer: which apps to stop, and stopping them. "Freezing" an app is a force stop, as in
 * Android's App info: the app stays installed and enabled, but runs nothing (and so shows no
 * notifications) until it is opened again or another app calls it.
 */
internal object Freezer {

    /** SuperFreezZ's freeze modes, in its order (the ordinal is what gets stored). */
    enum class Mode(val label: String, val hint: String) {
        /** Always frozen if it is running, regardless of when it was used last. */
        ALWAYS("Always", "Freeze whenever it is running"),
        /** Never frozen, however long it has been running in the background. */
        NEVER("Never", "Leave this app alone"),
        /** Frozen if it was not used for a while but is running in the background. */
        WHEN_INACTIVE("When unused", "Freeze when it hasn't been used for a while"),
    }

    /** SuperFreezZ's pending-freeze explanations (its strings pending_freeze, frozen, freeze_off, …). */
    enum class Info(val label: String) {
        PENDING("Will be frozen"),
        FROZEN("Frozen"),
        OFF("Not frozen"),
        USED_RECENTLY("Used recently"),
        USED_RECENTLY_FROZEN("Used recently · frozen"),
        STORE_EXEMPT("From F-Droid · not frozen"),
    }

    class App(
        val pkg: String, val label: String, val icon: Drawable?, val system: Boolean,
        val mode: Mode, val info: Info,
        /** Why the app is protected by default ("Keyboard"), or null. */
        val protectedAs: String?,
        /** It has a foreground service running or is on screen: playing, navigating, in a call. */
        val busy: Boolean,
        /** The person allowed freezing it even while [busy]. */
        val freezeBusy: Boolean,
    ) {
        /** What "Freeze now" would stop. */
        val pending get() = info == Info.PENDING && (!busy || freezeBusy)
        /** One line that says why this app will or won't be frozen. */
        fun why(days: Int): String = when {
            info == Info.PENDING && busy && !freezeBusy -> "In use right now (playing, navigating or on screen): skipped"
            info == Info.PENDING && mode == Mode.ALWAYS -> "Running · set to always freeze"
            info == Info.PENDING -> "Running · not used for $days ${if (days == 1) "day" else "days"}"
            info == Info.FROZEN -> "Stopped until you open it"
            info == Info.USED_RECENTLY -> "Used in the last $days ${if (days == 1) "day" else "days"}: left running"
            info == Info.USED_RECENTLY_FROZEN -> "Used recently · stopped right now"
            info == Info.STORE_EXEMPT -> "Installed from F-Droid: left running"
            protectedAs != null -> "Protected: $protectedAs"
            else -> "Never frozen"
        }
    }

    // ---------- settings (SuperFreezZ: Database.kt and pref_freezing.xml) ----------

    val DAY_CHOICES = listOf(1, 3, 7, 14, 30)
    val DELAY_CHOICES = listOf(0, 30, 60, 300, 900) // seconds after the screen turns off
    val LEVEL_CHOICES = listOf(15, 20, 30, 40, 50)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("freezer", Context.MODE_PRIVATE)
    private fun modes(ctx: Context) = ctx.getSharedPreferences("freezer_modes", Context.MODE_PRIVATE)

    /** SuperFreezZ's "autofreeze_delay": days without use before "When unused" freezes. Default 7. */
    fun days(ctx: Context) = sp(ctx).getInt("autofreeze_delay", 7).coerceIn(1, 365)
    fun setDays(ctx: Context, d: Int) = sp(ctx).edit().putInt("autofreeze_delay", d).apply()

    /** SuperFreezZ's "autofreeze_freeze_fdroid": also freeze apps installed from F-Droid. Default off. */
    fun freezeFdroid(ctx: Context) = sp(ctx).getBoolean("autofreeze_freeze_fdroid", false)
    fun setFreezeFdroid(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("autofreeze_freeze_fdroid", on).apply()

    /** SuperFreezZ's "use_accessibility_service". Off until the person turns the method on in the Freezer. */
    fun useAccessibility(ctx: Context) = sp(ctx).getBoolean("use_accessibility_service", false)
    fun setUseAccessibility(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("use_accessibility_service", on).apply()

    /** SuperFreezZ's "dialog-how-to-freeze-without-accessibility-service". */
    fun showManualHint(ctx: Context) = sp(ctx).getBoolean("manual_hint", true)
    fun setShowManualHint(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("manual_hint", on).apply()

    /** SuperFreezZ's "freeze_on_screen_off", plus a delay. */
    fun onScreenOff(ctx: Context) = sp(ctx).getBoolean("freeze_on_screen_off", false)
    fun setOnScreenOff(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("freeze_on_screen_off", on).apply()
    fun screenOffDelay(ctx: Context) = sp(ctx).getInt("screen_off_delay", 60).takeIf { it in DELAY_CHOICES } ?: 60
    fun setScreenOffDelay(ctx: Context, s: Int) = sp(ctx).edit().putInt("screen_off_delay", s).apply()

    /** Freeze when the battery drops below this level; 0 = off. */
    fun batteryBelow(ctx: Context) = sp(ctx).getInt("battery_below", 0).takeIf { it in LEVEL_CHOICES } ?: 0
    fun setBatteryBelow(ctx: Context, level: Int) = sp(ctx).edit().putInt("battery_below", level).apply()

    fun freezeBusy(ctx: Context, pkg: String) = sp(ctx).getBoolean("busy_ok_$pkg", false)
    fun setFreezeBusy(ctx: Context, pkg: String, on: Boolean) = sp(ctx).edit().putBoolean("busy_ok_$pkg", on).apply()

    /** The mode the person chose for [pkg], or null when it still has its default. */
    fun chosenMode(ctx: Context, pkg: String): Mode? =
        modes(ctx).getInt(pkg, -1).let { Mode.values().getOrNull(it) }

    fun setMode(ctx: Context, pkg: String, mode: Mode?) =
        modes(ctx).edit().apply { if (mode == null) remove(pkg) else putInt(pkg, mode.ordinal) }.apply()

    /**
     * SuperFreezZ's getFreezeMode: the stored mode, else the standard one. Its standard is
     * "when unused" for every app; here system apps and protected apps start as "never" instead.
     * Without usage access "when unused" can't be told, so it counts as "never".
     */
    fun mode(ctx: Context, pkg: String, system: Boolean, protectedAs: String?, usageAvailable: Boolean): Mode {
        val result = chosenMode(ctx, pkg) ?: if (system || protectedAs != null) Mode.NEVER else Mode.WHEN_INACTIVE
        return if (result == Mode.WHEN_INACTIVE && !usageAvailable) Mode.NEVER else result
    }

    // ---------- the pending-freeze decision (SuperFreezZ: AppInformationGetter.kt) ----------

    /** SuperFreezZ's isRunning: Android clears FLAG_STOPPED when an app is started, and sets it on force stop. */
    fun isRunning(ai: ApplicationInfo) = ai.flags and ApplicationInfo.FLAG_STOPPED == 0

    /** SuperFreezZ's getRecentAggregatedUsageStats: usage over the last [days] days. Slow; null without usage access. */
    fun recentUsage(ctx: Context): Map<String, UsageStats>? {
        if (!Cleaner.hasUsageAccess(ctx)) return null
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        return runCatching { usm.queryAndAggregateUsageStats(now - 1000L * 60 * 60 * 24 * days(ctx), now) }.getOrNull()
    }

    /**
     * SuperFreezZ's unusedRecently: an app only counts as used when it was on screen for at least
     * two seconds within the window. No record at all means it wasn't used.
     */
    private fun unusedRecently(ctx: Context, stats: UsageStats?): Boolean = when {
        stats == null -> true
        stats.packageName == ctx.packageName -> false // the person is just using this app
        else -> stats.totalTimeInForeground < 1000L * 2
    }

    /** SuperFreezZ's getPendingFreezeInfo. */
    fun info(ctx: Context, mode: Mode, ai: ApplicationInfo, stats: UsageStats?, fromFdroid: Boolean): Info {
        val running = isRunning(ai)
        return when (mode) {
            Mode.ALWAYS -> if (running) Info.PENDING else Info.FROZEN
            Mode.NEVER -> if (running) Info.OFF else Info.FROZEN
            Mode.WHEN_INACTIVE ->
                if (fromFdroid && !freezeFdroid(ctx)) Info.STORE_EXEMPT
                else if (unusedRecently(ctx, stats)) { if (running) Info.PENDING else Info.FROZEN }
                else { if (running) Info.USED_RECENTLY else Info.USED_RECENTLY_FROZEN }
        }
    }

    /** SuperFreezZ's sort by freeze state, turned round so what will be frozen comes first. */
    private fun rank(a: App) = when {
        a.pending -> 0
        a.info == Info.PENDING -> 1
        a.info == Info.USED_RECENTLY -> 2
        a.info == Info.STORE_EXEMPT -> 3
        a.info == Info.USED_RECENTLY_FROZEN -> 4
        a.info == Info.FROZEN -> 5
        else -> 6
    }

    // ---------- protected apps (Andromon) ----------

    private val FDROID = setOf("org.fdroid.fdroid", "org.fdroid.basic", "org.fdroid.fdroid.privileged")

    @Suppress("DEPRECATION")
    private fun installer(pm: PackageManager, pkg: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName else pm.getInstallerPackageName(pkg)
    }.getOrNull()

    /** Apps that are left alone unless the person says otherwise: package -> what it is. */
    fun protectedApps(ctx: Context): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun add(pkg: String?, what: String) { if (!pkg.isNullOrEmpty() && pkg !in out) out[pkg] = what }
        val pm = ctx.packageManager
        add(ctx.packageName, "this app")
        add(PrivilegedCommands.SHIZUKU_PKG, "Shizuku")
        add(pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.takeIf { it != "android" }, "home screen")
        add(Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/'), "keyboard")
        add(runCatching { ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull(), "phone app")
        add(runCatching { Telephony.Sms.getDefaultSmsPackage(ctx) }.getOrNull(), "SMS app")
        runCatching {
            ctx.getSystemService(AccessibilityManager::class.java)
                ?.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.forEach { add(it.resolveInfo?.serviceInfo?.packageName, "accessibility service") }
        }
        runCatching {
            ctx.getSystemService(DevicePolicyManager::class.java)?.activeAdmins?.forEach { add(it.packageName, "device admin app") }
        }
        return out
    }

    /**
     * Apps that are doing something for the person right now: with privileged access, what
     * Android ranks as in use or perceptible (on screen, playing music, navigating); otherwise
     * apps whose foreground service started and hasn't stopped since (usage events, Android 10+).
     */
    fun busyApps(ctx: Context, privileged: Boolean): Set<String> {
        if (privileged) runCatching {
            return RamManager.load(ctx)
                .filter { it.state == RamManager.State.ACTIVE || it.state == RamManager.State.VISIBLE }.map { it.pkg }.toSet()
        }
        if (Build.VERSION.SDK_INT < 29 || !Cleaner.hasUsageAccess(ctx)) return emptySet()
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return emptySet()
        val now = System.currentTimeMillis()
        val since = maxOf(now - SystemClock.elapsedRealtime(), now - 7L * 24 * 60 * 60 * 1000) // services don't outlive a restart
        val open = HashMap<String, HashSet<String>>()
        var onScreen: String? = null
        runCatching {
            val events = usm.queryEvents(since, now)
            val e = UsageEvents.Event()
            while (events.getNextEvent(e)) {
                when (e.eventType) {
                    UsageEvents.Event.FOREGROUND_SERVICE_START -> open.getOrPut(e.packageName) { HashSet() }.add(e.className ?: "")
                    UsageEvents.Event.FOREGROUND_SERVICE_STOP -> open[e.packageName]?.remove(e.className ?: "")
                    UsageEvents.Event.ACTIVITY_RESUMED -> onScreen = e.packageName
                }
            }
        }
        return open.filterValues { it.isNotEmpty() }.keys + setOfNotNull(onScreen)
    }

    // ---------- the list ----------

    /** Labels and icons, kept between refreshes: loading them is the slow part. */
    private val looks = HashMap<String, Pair<String, Drawable?>>()

    /**
     * Every app the Freezer knows, what will be frozen first. Slow (usage statistics): call it on
     * a background thread. [withLooks] false skips labels and icons (background triggers).
     */
    fun load(ctx: Context, withLooks: Boolean = true): List<App> {
        val pm = ctx.packageManager
        val usage = recentUsage(ctx)
        val guarded = protectedApps(ctx)
        val privileged = runCatching { Privileged.active(ctx) != null }.getOrDefault(false)
        val busy = busyApps(ctx, privileged)
        val fdroidInstalled = FDROID.any { p -> runCatching { pm.getApplicationInfo(p, 0) }.isSuccess }
        return pm.getInstalledApplications(0).mapNotNull { ai ->
            val pkg = ai.packageName
            if (pkg == ctx.packageName || !ai.enabled) return@mapNotNull null
            val system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val mode = mode(ctx, pkg, system, guarded[pkg], usage != null)
            // Asking who installed every app is slow; only needed where the F-Droid rule can apply.
            val fromFdroid = fdroidInstalled && mode == Mode.WHEN_INACTIVE && installer(pm, pkg) in FDROID
            val info = info(ctx, mode, ai, usage?.get(pkg), fromFdroid)
            val (label, icon) = if (!withLooks) pkg to null else synchronized(looks) {
                looks.getOrPut(pkg) {
                    (runCatching { ai.loadLabel(pm).toString() }.getOrNull() ?: pkg) to runCatching { ai.loadIcon(pm) }.getOrNull()
                }
            }
            App(pkg, label, icon, system, mode, info, guarded[pkg], pkg in busy, freezeBusy(ctx, pkg))
        }.sortedWith(compareBy<App> { rank(it) }.thenBy { it.label.lowercase() })
    }

    /** SuperFreezZ's getAppsPendingFreeze: the packages "Freeze now" would stop. */
    fun pendingPackages(ctx: Context): List<String> = load(ctx, withLooks = false).filter { it.pending }.map { it.pkg }

    class Outcome(val frozen: Int, val failed: List<String>, val unavailable: Boolean)

    /** Freezes [pkgs] through [Privileged]. Blocks. [Outcome.unavailable] when there is no backend. */
    fun freeze(ctx: Context, pkgs: List<String>): Outcome {
        var done = 0
        val failed = ArrayList<String>()
        for (pkg in pkgs) {
            try {
                Privileged.freeze(ctx, pkg)
                done++
            } catch (_: ShellHelperClient.Unavailable) {
                return Outcome(done, failed, true)
            } catch (_: Exception) {
                failed += pkg
            }
        }
        return Outcome(done, failed, false)
    }

    /** Whether Android has this app's accessibility service switched on. */
    fun accessibilityOn(ctx: Context): Boolean {
        val on = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = android.content.ComponentName(ctx, FreezeAccessibilityService::class.java)
        return on.split(':').any { android.content.ComponentName.unflattenFromString(it) == me }
    }
}
