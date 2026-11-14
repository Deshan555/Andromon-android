// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors
// The "freeze" command's sequence (am set-inactive, am force-stop, am kill) is ported from
// SuperFreezZ (https://gitlab.com/SuperFreezZ/SuperFreezZ, commit 267d0f7), GPL-3.0-or-later,
// Copyright (c) 2018 Hocuri: src/superfreeze/tool/android/backend/FreezeUsingRoot.kt.

package dev.droiddesk.companion

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Everything the privileged process may do, whichever way it was started: by Droid Desk over adb
 * or by `su` ([ShellHelper]), or by Shizuku ([PrivilegedService]). It runs with shell or root
 * rights, so it is kept deliberately small:
 *
 *  - a fixed list of commands, each with a fixed argument list, started directly (no shell), so
 *    nothing can be injected;
 *  - package names are checked against a strict pattern, and every other argument against a
 *    fixed set of allowed values;
 *  - grants only ever go to the companion itself.
 *
 * Code here runs outside a normal app process: no Context, no app resources.
 */
internal object PrivilegedCommands {
    /** Bump when commands are added; callers tell an older running helper by its "Unknown method". */
    const val VERSION = 3

    const val PKG = "dev.droiddesk.companion"
    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    /** Stopping these would crash the phone's UI or calls, this app, or the app that lends it its rights. */
    private val NEVER_STOP = setOf("android", "com.android.systemui", "com.android.phone", PKG, SHIZUKU_PKG)

    fun call(method: String, p: JSONObject): Any = when (method) {
        "ping" -> JSONObject().put("version", VERSION).put("uid", android.os.Process.myUid())
        // RAM manager
        "lru" -> run("dumpsys", "activity", "lru")
        "ps" -> run("ps", "-A", "-o", "PID,RSS")
        "forceStop" -> run("am", "force-stop", "--user", "0", pkg(p))
        "kill" -> run("am", "kill", "--user", "0", pkg(p))
        "killAll" -> run("am", "kill-all")
        // Freezer
        "freeze" -> freeze(pkg(p))
        // Battery and Permission audit (read-only)
        "batteryUse" -> batteryUse()
        "appOps" -> appOps(p)
        "wakeLocks" -> wakeLocks()
        "buckets" -> run("am", "get-standby-bucket", "--user", "0").take(200_000)
        // Battery saver
        "setBucket" -> run("am", "set-standby-bucket", "--user", "0", pkg(p), oneOf(p, "bucket", BUCKETS)).trim()
        "bgRestrict" -> run("cmd", "appops", "set", "--user", "0", pkg(p), "RUN_ANY_IN_BACKGROUND",
            if (p.getBoolean("on")) "ignore" else "allow").trim()
        "setGlobal" -> setGlobal(p)
        "doze" -> doze(p.getBoolean("on"))
        // Finish setup without a computer: grants to the companion only
        "grantSelf" -> run("pm", "grant", "--user", "0", PKG, oneOf(p, "perm", SELF_PERMISSIONS)).trim()
        "appOpSelf" -> appOpSelf(oneOf(p, "op", SELF_OPS))
        "notifAccessSelf" -> run("cmd", "notification", "allow_listener", "$PKG/$PKG.NotifListener").trim()
        else -> throw IllegalArgumentException("Unknown method: $method")
    }

    private fun pkg(p: JSONObject): String {
        val pkg = p.getString("pkg")
        require(PACKAGE_NAME.matches(pkg) && pkg.length < 256) { "Not a package name" }
        require(pkg !in NEVER_STOP) { "$pkg can't be stopped" }
        return pkg
    }

    private fun oneOf(p: JSONObject, key: String, allowed: Set<String>): String {
        val v = p.getString(key)
        require(v in allowed) { "Not an allowed $key" }
        return v
    }

    // ---------- Freezer ----------

    /**
     * SuperFreezZ's root freeze: mark the app idle (App Standby), force-stop it and kill what is
     * left. The app stays stopped until it is opened or another app calls it.
     */
    private fun freeze(pkg: String): String {
        runCatching { run("am", "set-inactive", "--user", "0", pkg, "true") }
        val out = run("am", "force-stop", "--user", "0", pkg)
        runCatching { run("am", "kill", "--user", "0", pkg) }
        return out.trim()
    }

    // ---------- Battery ----------

    /**
     * Battery screen: Android's own per-app estimate since the last full charge. Only the
     * "Estimated power use" part of `dumpsys batterystats` is passed on.
     */
    private fun batteryUse(): String {
        val out = StringBuilder()
        var inside = false
        var lines = 0
        for (line in run("dumpsys", "batterystats", "--charged").lineSequence()) {
            if (!inside) {
                if (line.contains("Estimated power use")) { inside = true; out.append(line).append('\n') }
            } else {
                if (line.isBlank() || ++lines > 600) break
                out.append(line).append('\n')
            }
        }
        return out.toString()
    }

    /** Battery saver: the "Wake Locks: size=N" part of `dumpsys power` (who keeps the phone awake right now). */
    private fun wakeLocks(): String {
        val out = StringBuilder()
        var inside = false
        var lines = 0
        for (line in run("dumpsys", "power").lineSequence()) {
            if (!inside) {
                if (line.trimStart().startsWith("Wake Locks:")) { inside = true; out.append(line).append('\n') }
            } else {
                if (line.isBlank() || ++lines > 200) break
                out.append(line).append('\n')
            }
        }
        return out.toString()
    }

    private val BUCKETS = setOf("active", "working_set", "frequent", "rare", "restricted")

    /** The only system settings this may write: Battery Saver on/off and its automatic level. */
    private fun setGlobal(p: JSONObject): String {
        val value = p.getInt("value")
        val key = when (p.getString("key")) {
            "low_power" -> { require(value == 0 || value == 1) { "Not an allowed value" }; "low_power" }
            "low_power_trigger_level" -> { require(value in 0..100) { "Not an allowed value" }; "low_power_trigger_level" }
            else -> throw IllegalArgumentException("Not an allowed key")
        }
        return run("settings", "put", "global", key, value.toString()).trim()
    }

    @Volatile private var dozeWatch = 0

    /**
     * Deep sleep: forces Doze (`dumpsys deviceidle force-idle`), or lifts it again (`unforce`).
     * A forced Doze doesn't end by itself when the screen comes on, so this process also watches
     * the screen and lifts it then; the phone can't stay in Doze because the app was closed.
     */
    private fun doze(on: Boolean): String {
        val id = ++dozeWatch // ends an earlier watch
        if (!on) return run("dumpsys", "deviceidle", "unforce").trim()
        val out = run("dumpsys", "deviceidle", "force-idle").trim()
        thread(isDaemon = true, name = "doze-watch") {
            fun screenOn() = runCatching { run("dumpsys", "deviceidle", "get", "screen").trim() == "true" }.getOrDefault(false)
            // The screen may still be on when this is asked for: wait for it to go off first, but
            // not for longer than ten minutes.
            val giveUp = System.currentTimeMillis() + 10 * 60_000L
            var wasOff = false
            while (dozeWatch == id) {
                Thread.sleep(4000)
                val nowOn = screenOn()
                if (!nowOn) wasOff = true
                if ((wasOff && nowOn) || (!wasOff && System.currentTimeMillis() > giveUp)) {
                    if (dozeWatch == id) runCatching { run("dumpsys", "deviceidle", "unforce") }
                    break
                }
            }
        }
        return out
    }

    // ---------- Permission audit ----------

    /** The app-ops the Permission audit may ask about (when an app last used a permission). */
    private val AUDIT_OPS = setOf("CAMERA", "RECORD_AUDIO", "FINE_LOCATION", "COARSE_LOCATION", "READ_CONTACTS",
        "READ_SMS", "READ_CALL_LOG")

    /** Permission audit: `appops get <pkg> <op>` for up to 80 apps; `{pkg: output}`. Read-only. */
    private fun appOps(p: JSONObject): JSONObject {
        val op = p.getString("op")
        require(op in AUDIT_OPS) { "Not an allowed op" }
        val pkgs = p.getJSONArray("pkgs")
        val out = JSONObject()
        val stopAt = System.currentTimeMillis() + 15_000
        for (i in 0 until minOf(pkgs.length(), 80)) {
            val pkg = pkgs.optString(i)
            if (!PACKAGE_NAME.matches(pkg) || pkg.length >= 256) continue
            if (System.currentTimeMillis() > stopAt) break
            runCatching { out.put(pkg, run("cmd", "appops", "get", "--user", "0", pkg, op).take(2000)) }
        }
        return out
    }

    // ---------- Finish setup without a computer ----------

    /** What Droid Desk grants over adb at install: the companion's runtime permissions and WRITE_SECURE_SETTINGS. */
    val SELF_PERMISSIONS = setOf(
        "READ_SMS", "SEND_SMS", "READ_CONTACTS", "WRITE_CONTACTS", "READ_CALL_LOG", "WRITE_CALL_LOG",
        "READ_CALENDAR", "WRITE_CALENDAR", "READ_PHONE_STATE", "ANSWER_PHONE_CALLS",
        "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO", "POST_NOTIFICATIONS", "NEARBY_WIFI_DEVICES",
        "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE",
        "RECORD_AUDIO", "WRITE_SECURE_SETTINGS",
    ).map { "android.permission.$it" }.toSet()

    val SELF_OPS = setOf("GET_USAGE_STATS", "MANAGE_EXTERNAL_STORAGE", "SYSTEM_ALERT_WINDOW", "WRITE_SMS", "USE_FULL_SCREEN_INTENT")

    /** `appops set` for the companion. Two of them are per-uid ops, set both ways as Droid Desk does. */
    private fun appOpSelf(op: String): String {
        val out = StringBuilder()
        if (op == "MANAGE_EXTERNAL_STORAGE" || op == "SYSTEM_ALERT_WINDOW")
            out.append(runCatching { run("cmd", "appops", "set", "--user", "0", "--uid", PKG, op, "allow") }.getOrDefault(""))
        out.append(run("cmd", "appops", "set", "--user", "0", PKG, op, "allow"))
        return out.toString().trim()
    }

    // ---------- running commands ----------

    /** Runs a command directly (no shell) and returns what it printed. */
    fun run(vararg cmd: String): String {
        val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        proc.outputStream.close()
        val out = ByteArrayOutputStream()
        val reader = thread(isDaemon = true) { proc.inputStream.use { it.copyTo(out) } }
        if (!proc.waitFor(20, TimeUnit.SECONDS)) { proc.destroy(); throw IOException("${cmd[0]} timed out") }
        reader.join(2000)
        return out.toString("UTF-8")
    }
}
