// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import org.json.JSONObject
import java.io.IOException

/**
 * Privileged access: the one door to everything that needs shell (adb) or root rights. Stopping
 * and freezing apps, the RAM manager's process list, per-app battery use, app-op records, Doze,
 * standby buckets. Three ways lead through it, all serving the same fixed commands
 * ([PrivilegedCommands]):
 *
 *  - **Shizuku** ([ShizukuAccess], [PrivilegedService]): no computer needed once Shizuku runs;
 *  - **Andromon helper** ([ShellHelper]): started from the PC over adb, until the phone restarts;
 *  - **Root**: the same helper, started with `su`.
 *
 * Callers pass a method name and checked parameters, never a command line. All calls block: use a
 * background thread. When no backend answers they throw [ShellHelperClient.Unavailable].
 */
internal object Privileged {

    enum class Backend(val label: String) { SHIZUKU("Shizuku"), HELPER("Andromon helper"), ROOT("Root") }

    /** Which backend to try first when more than one is there. */
    enum class Prefer(val label: String) {
        AUTO("Automatic (Shizuku first)"), SHIZUKU("Shizuku"), HELPER("Andromon helper or root"),
    }

    class Active(val backend: Backend, val root: Boolean) {
        /** "through Shizuku (adb rights)": for "Full access is on …" sentences. */
        fun how() = when (backend) {
            Backend.SHIZUKU -> "through Shizuku (${if (root) "root" else "adb"} rights)"
            Backend.HELPER -> "through the helper Andromon started, until the phone restarts"
            Backend.ROOT -> "with root"
        }
    }

    /** Short pointer for "this isn't available" messages. */
    const val HOW_TO = "Turn it on with Shizuku (Settings › Privileged access, no computer needed) or from Andromon on " +
        "your computer (Shizuku › Phone RAM manager)."

    private fun sp(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun prefer(ctx: Context): Prefer =
        runCatching { Prefer.valueOf(sp(ctx).getString("privileged_prefer", null) ?: "AUTO") }.getOrDefault(Prefer.AUTO)
    fun setPrefer(ctx: Context, p: Prefer) = sp(ctx).edit().putString("privileged_prefer", p.name).apply()

    private fun order(ctx: Context) =
        if (prefer(ctx) == Prefer.HELPER) listOf(Backend.HELPER, Backend.SHIZUKU) else listOf(Backend.SHIZUKU, Backend.HELPER)

    /** The backend calls would go through right now, or null when there is none. Blocks briefly. */
    fun active(ctx: Context): Active? {
        for (b in order(ctx)) {
            if (b == Backend.SHIZUKU) {
                val st = ShizukuAccess.status(ctx)
                if (st.ready) return Active(Backend.SHIZUKU, st.asRoot)
            } else {
                val h = ShellHelperClient.ping(ctx)
                if (h != null) return Active(if (h.second) Backend.ROOT else Backend.HELPER, h.second)
            }
        }
        return null
    }

    /** Why [active] chose what it chose, for Settings. */
    fun reason(ctx: Context, active: Active?, shizuku: ShizukuAccess.Status, helper: Boolean): String = when {
        active == null -> "None. Features that stop apps or read system statistics are off."
        active.backend == Backend.SHIZUKU && helper && prefer(ctx) != Prefer.HELPER -> "Shizuku is ready and comes first."
        active.backend == Backend.SHIZUKU && helper -> "Shizuku is ready." // can't happen: the helper would have been chosen
        active.backend == Backend.SHIZUKU -> "Shizuku is ready; the Andromon helper isn't running."
        shizuku.ready -> "You chose to prefer the helper; Shizuku is ready as well."
        active.backend == Backend.ROOT -> "The helper is running with root; Shizuku isn't ready."
        else -> "The helper Andromon started is running; Shizuku isn't ready."
    }

    /** Runs one allow-listed command on the first backend that answers. */
    fun call(ctx: Context, method: String, params: JSONObject = JSONObject()): Any {
        var unavailable: ShellHelperClient.Unavailable? = null
        var old: IOException? = null
        for (b in order(ctx)) {
            try {
                return if (b == Backend.SHIZUKU) ShizukuAccess.call(ctx, method, params) else ShellHelperClient.call(ctx, method, params)
            } catch (e: ShellHelperClient.Unavailable) {
                if (unavailable == null || b == Backend.SHIZUKU) unavailable = e
            } catch (e: IOException) {
                // A helper from an older app version doesn't know newer commands: the other backend may.
                if (e.message?.startsWith("Unknown method") == true) old = e else throw e
            }
        }
        throw old ?: unavailable ?: ShellHelperClient.Unavailable()
    }

    fun forceStop(ctx: Context, pkg: String) { call(ctx, "forceStop", JSONObject().put("pkg", pkg)) }

    /** Force-stops [pkg] the way the Freezer does; an older running helper only knows the plain force stop. */
    fun freeze(ctx: Context, pkg: String) {
        try {
            call(ctx, "freeze", JSONObject().put("pkg", pkg))
        } catch (e: ShellHelperClient.Unavailable) {
            throw e
        } catch (e: IOException) {
            if (e.message?.startsWith("Unknown method") == true) forceStop(ctx, pkg) else throw e
        }
    }

    // ---------- Finish setup without a computer ----------

    /** One access Andromon normally gives the companion over adb. */
    class Grant(val title: String, val why: String, val granted: (Context) -> Boolean?, val run: (Context) -> String?)

    private fun opAllowed(ctx: Context, op: String): Boolean? = runCatching {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(op, Process.myUid(), ctx.packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(op, Process.myUid(), ctx.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrNull()

    /** The runtime permissions this build asks for on this Android version. */
    private fun runtimePermissions(ctx: Context): List<String> {
        @Suppress("DEPRECATION")
        val asked = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList()
        }.getOrNull() ?: emptyList()
        return asked.filter { it in PrivilegedCommands.SELF_PERMISSIONS && it != SECURE }
    }

    private const val SECURE = "android.permission.WRITE_SECURE_SETTINGS"

    private fun held(ctx: Context, perm: String) = ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    /** Runs [method]; null when it worked, otherwise what went wrong (the command's own words). */
    private fun attempt(ctx: Context, method: String, params: JSONObject, ok: () -> Boolean?): String? {
        val out = try { call(ctx, method, params) as? String ?: "" } catch (e: Exception) { return e.message ?: e.javaClass.simpleName }
        return if (ok() == false) out.lineSequence().firstOrNull { it.isNotBlank() }?.take(160) ?: "Android didn't apply it" else null
    }

    fun grants(ctx: Context): List<Grant> = buildList {
        val perms = runtimePermissions(ctx)
        add(Grant("App permissions", "Messages, contacts, call log, calendar, photos and music for Andromon",
            granted = { c -> perms.all { held(c, it) } },
            run = { c ->
                val failed = perms.filter { !held(c, it) }.mapNotNull { p ->
                    attempt(c, "grantSelf", JSONObject().put("perm", p)) { held(c, p) }?.let { p.substringAfterLast('.') }
                }
                if (failed.isEmpty()) null else "Android refused: ${failed.joinToString(", ")}"
            }))
        add(Grant("Change secure settings", "The Wi‑Fi connection switch and Battery Saver (WRITE_SECURE_SETTINGS)",
            granted = { c -> held(c, SECURE) },
            run = { c -> attempt(c, "grantSelf", JSONObject().put("perm", SECURE)) { held(c, SECURE) } }))
        add(Grant("Usage access", "App sizes, last use, and the Freezer's “not used for a while”",
            granted = { c -> Cleaner.hasUsageAccess(c) },
            run = { c -> attempt(c, "appOpSelf", JSONObject().put("op", "GET_USAGE_STATS")) { Cleaner.hasUsageAccess(c) } }))
        if (Build.VERSION.SDK_INT >= 30) add(Grant("All files access", "The Cleaner and the Storage analyzer",
            granted = { c -> Cleaner.hasFileAccess(c) },
            run = { c -> attempt(c, "appOpSelf", JSONObject().put("op", "MANAGE_EXTERNAL_STORAGE")) { Cleaner.hasFileAccess(c) } }))
        add(Grant("Display over other apps", "The “Recording” badge while Andromon records a test",
            granted = { c -> Settings.canDrawOverlays(c) },
            // canDrawOverlays can lag behind (see RecOverlay): trust the command unless it printed an error.
            run = { c -> attempt(c, "appOpSelf", JSONObject().put("op", "SYSTEM_ALERT_WINDOW")) { null } }))
        add(Grant("Write text messages", "Restoring an SMS backup from Andromon (the WRITE_SMS app-op)",
            granted = { c -> opAllowed(c, "android:write_sms") },
            run = { c -> attempt(c, "appOpSelf", JSONObject().put("op", "WRITE_SMS")) { opAllowed(c, "android:write_sms") } }))
        add(Grant("Notification access", "Phone notifications and the media remote in Andromon",
            granted = { c -> notificationAccess(c) },
            run = { c -> attempt(c, "notifAccessSelf", JSONObject()) { notificationAccess(c) } }))
    }

    private fun notificationAccess(ctx: Context): Boolean {
        val me = ComponentName(ctx, NotifListener::class.java)
        val on = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return on.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

}
