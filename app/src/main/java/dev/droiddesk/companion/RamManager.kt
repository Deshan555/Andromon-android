// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

package dev.droiddesk.companion

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.provider.Settings
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Receives the helper's loopback port and token when it starts. Protected by
 * android.permission.DUMP in the manifest, so only shell, root or the system can send it.
 */
class HelperReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val port = intent.getStringExtra("port")?.toIntOrNull() ?: return
        val token = intent.getStringExtra("token")?.takeIf { it.length >= 32 } ?: return
        ShellHelperClient.save(ctx, port, token)
    }
}

/** Talks to [ShellHelper] on its loopback port. All calls block: use a background thread. */
internal object ShellHelperClient {

    /** What a user (Droid Desk on the PC, or this app with root) runs to start the helper. */
    fun startCommand(apkPath: String) =
        "CLASSPATH=$apkPath setsid app_process /system/bin --nice-name=${ShellHelper.SOCKET} " +
            "${ShellHelper::class.java.name} </dev/null >/dev/null 2>&1 &"

    /** No privileged backend can be reached (see [Privileged]): neither Shizuku nor this helper. */
    class Unavailable(why: String = "The Droid Desk helper isn't running") : IOException(why)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("shell_helper", Context.MODE_PRIVATE)

    fun save(ctx: Context, port: Int, token: String) {
        prefs(ctx).edit().putInt("port", port).putString("token", token).apply()
    }

    fun call(ctx: Context, method: String, params: JSONObject = JSONObject()): Any {
        val p = prefs(ctx)
        val port = p.getInt("port", 0)
        val token = p.getString("token", null)
        if (port == 0 || token == null) throw Unavailable()
        Socket().use { s ->
            try { s.connect(InetSocketAddress("127.0.0.1", port), 1500) } catch (_: IOException) { throw Unavailable() }
            s.soTimeout = 25_000
            val req = JSONObject().put("token", token).put("method", method).put("params", params)
            s.getOutputStream().apply { write((req.toString() + "\n").toByteArray()); flush() }
            val buf = ByteArrayOutputStream()
            s.getInputStream().copyTo(buf)
            val text = buf.toString("UTF-8").trim()
            if (text.isEmpty()) throw Unavailable() // something else on that port, or an old token
            val resp = JSONObject(text)
            if (resp.has("error")) throw IOException(resp.getString("error"))
            return resp.get("result")
        }
    }

    /** The running helper's protocol version and whether it runs as root, or null if it isn't running. */
    fun ping(ctx: Context): Pair<Int, Boolean>? = try {
        val r = call(ctx, "ping") as JSONObject
        r.getInt("version") to (r.optInt("uid", -1) == 0)
    } catch (_: Exception) { null }

    fun hasSu(): Boolean =
        listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "/system_ext/bin/su")
            .any { File(it).exists() } ||
            (System.getenv("PATH") ?: "").split(':').any { it.isNotEmpty() && File(it, "su").exists() }

    /** Starts the helper with `su` (the root app asks the person to allow it). True once it answers. */
    fun startWithRoot(ctx: Context): Boolean {
        ping(ctx)?.let { (v, _) -> if (v < ShellHelper.VERSION) runCatching { call(ctx, "exit") } else return true }
        val proc = ProcessBuilder("su", "-c", startCommand(ctx.applicationInfo.sourceDir)).redirectErrorStream(true).start()
        proc.outputStream.close()
        if (!proc.waitFor(60, TimeUnit.SECONDS)) { proc.destroy(); return false } // root prompt ignored
        repeat(30) { if (ping(ctx) != null) return true; Thread.sleep(200) } // waits for its broadcast
        return false
    }
}

/** One app with processes in memory. */
internal class RunningApp(
    val pkg: String,
    val label: String,
    val icon: Drawable?,
    val system: Boolean,
    val bytes: Long,
    val processes: Int,
    val state: RamManager.State,
) {
    /** Some apps must keep running for the phone to work; the list shows them but offers no Stop. */
    var keepRunning = false
}

internal object RamManager {

    /** How busy an app is, from Android's own process ranking (most important first). */
    enum class State(val label: String) {
        ACTIVE("In use"), VISIBLE("Visible"), SYSTEM("Always on"), SERVICE("Running"), RECENT("Recent"), CACHED("Cached"),
    }

    private fun stateOf(adj: String): State {
        val a = adj.substringBefore('+').trim()
        return when (a) {
            "fg", "top", "fore" -> State.ACTIVE
            "vis", "prcp", "prcl", "prcm", "percep" -> State.VISIBLE
            "pers", "psvc", "sys", "natv" -> State.SYSTEM
            "svc", "svcb", "svcrst", "bsvc", "bfgs", "hvy", "bkup" -> State.SERVICE
            "prev", "home", "picked" -> State.RECENT
            else -> if (a.startsWith("cch") || a.startsWith("cac") || a == "empty") State.CACHED else State.SERVICE
        }
    }

    // "#18: cch+ 5 CEM  ---- 4513:com.android.statementservice/u0a42"
    private val LRU = Regex("""^\s*#\s*\d+:\s+(\S+(?:\s+\d+)?)\s+\S+\s+\S+\s+(\d+):([^/\s]+)/(\S+)""")
    // "u0a42" (app of user 0), "u0a397i345" (isolated process of that app), "u150a469" (another user)
    private val APP_UID = Regex("""^u(\d+)a(\d+)""")

    /** Apps with processes in memory, biggest first. Needs privileged access ([Privileged]). */
    fun load(ctx: Context): List<RunningApp> {
        val lru = Privileged.call(ctx, "lru") as String
        val rss = HashMap<Int, Long>()
        for (line in (Privileged.call(ctx, "ps") as String).lineSequence().drop(1)) {
            val parts = line.trim().split(Regex("\\s+"))
            val pid = parts.getOrNull(0)?.toIntOrNull() ?: continue
            rss[pid] = (parts.getOrNull(1)?.toLongOrNull() ?: 0L) * 1024
        }

        class Acc(var bytes: Long = 0, var procs: Int = 0, var state: State = State.CACHED)
        val byPkg = LinkedHashMap<String, Acc>()
        val pm = ctx.packageManager
        val pkgsOfUid = HashMap<Int, Array<String>?>()
        for (line in lru.lineSequence()) {
            val m = LRU.find(line) ?: continue
            val (adj, pidStr, procName, uidStr) = m.destructured
            val u = APP_UID.find(uidStr) ?: continue // system UIDs (1000, u0s…): not apps the person manages
            if (u.groupValues[1] != "0") continue    // other users / Secure Folder: --user 0 can't stop those
            val uid = 10_000 + u.groupValues[2].toInt()
            val pkgs = pkgsOfUid.getOrPut(uid) { runCatching { pm.getPackagesForUid(uid) }.getOrNull() } ?: continue
            val base = procName.substringBefore(':')
            val pkg = pkgs.firstOrNull { it == base } ?: pkgs.first()
            val acc = byPkg.getOrPut(pkg) { Acc() }
            acc.bytes += rss[pidStr.toInt()] ?: 0L
            acc.procs++
            val st = stateOf(adj)
            if (st.ordinal < acc.state.ordinal) acc.state = st
        }

        val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        val keyboard = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
        val keep = setOfNotNull(ctx.packageName, home, keyboard, "com.android.systemui", "com.android.phone", "android")
        return byPkg.map { (pkg, a) ->
            val (label, icon, system) = synchronized(looks) {
                looks.getOrPut(pkg) {
                    val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                    Triple(
                        ai?.let { runCatching { it.loadLabel(pm).toString() }.getOrNull() } ?: pkg,
                        ai?.let { runCatching { it.loadIcon(pm) }.getOrNull() },
                        ai != null && ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    )
                }
            }
            RunningApp(pkg, label, icon, system, a.bytes, a.procs, a.state)
                .also { it.keepRunning = pkg in keep || a.state == State.SYSTEM }
        }.sortedByDescending { it.bytes }
    }

    /** Labels and icons, kept between refreshes: loading them is the slow part. */
    private val looks = HashMap<String, Triple<String, Drawable?, Boolean>>()

    fun forceStop(ctx: Context, pkg: String) { Privileged.call(ctx, "forceStop", JSONObject().put("pkg", pkg)) }

    /** Closes every app that isn't in use or needed; they start again when opened. */
    fun freeMemory(ctx: Context) { Privileged.call(ctx, "killAll") }

    fun availMem(ctx: Context): Long =
        ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.availMem
}
