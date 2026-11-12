// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

package dev.droiddesk.companion

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * A tiny server that runs with shell (ADB) or root rights, for the RAM manager.
 *
 * Android only lets an app see and stop its own processes. Like Shizuku and App Manager, this
 * borrows the shell user's rights instead: Droid Desk on the PC (over ADB), or `su` on a rooted
 * phone, starts this class from the companion's own APK with `app_process`. It keeps running until
 * the phone restarts, so the RAM manager works without the PC afterwards.
 *
 * How the app reaches it: SELinux doesn't let an app and a shell process connect to each other's
 * Unix sockets, so this listens on a loopback TCP port instead. Any app could connect to that, so
 * every request must carry a random token. The token goes to the companion alone, in an explicit
 * broadcast to [HelperReceiver], which only senders holding android.permission.DUMP (shell, root,
 * system) may send to.
 *
 * Kept deliberately small: a fixed list of commands, each with fixed arguments and no shell, so
 * nothing can be injected; package names are checked before use.
 *
 * Start command: see [ShellHelperClient.startCommand].
 */
object ShellHelper {
    /** Abstract Unix socket: makes sure only one copy runs, and lets shell/root ask it to re-announce or exit. */
    const val SOCKET = "droiddesk_helper"
    /** Bump when the protocol changes; the app restarts an older helper when it can. */
    const val VERSION = 2
    const val ACTION_READY = "dev.droiddesk.companion.HELPER_READY"

    private const val PKG = "dev.droiddesk.companion"
    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    /** Stopping these would crash the phone's UI or calls, or this app itself. */
    private val NEVER_STOP = setOf("android", "com.android.systemui", "com.android.phone", PKG)

    private lateinit var token: String
    private var port = 0

    @JvmStatic
    fun main(args: Array<String>) {
        val control = try {
            LocalServerSocket(SOCKET)
        } catch (_: IOException) {
            // Already running: ask that copy to send its token again (the app may have lost it).
            println("droiddesk_helper: already running")
            runCatching { LocalSocket().use { it.connect(LocalSocketAddress(SOCKET)); it.outputStream.write("announce\n".toByteArray()) } }
            return
        }
        token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        port = server.localPort
        println("droiddesk_helper: running as uid ${android.os.Process.myUid()} on 127.0.0.1:$port")

        thread(isDaemon = true) { controlLoop(control) }
        announce()
        while (true) {
            val s = try { server.accept() } catch (_: IOException) { continue }
            thread(isDaemon = true) { s.use { handle(it) } }
        }
    }

    /** Hands the port and token to the companion app. */
    private fun announce() {
        runCatching {
            run("am", "broadcast", "--user", "0", "-f", "32" /* include stopped packages */,
                "-a", ACTION_READY, "-n", "$PKG/.HelperReceiver",
                "--es", "port", port.toString(), "--es", "token", token, "--ei", "version", VERSION.toString())
        }
    }

    /** Shell and root only (same SELinux domain): "announce" or "exit". */
    private fun controlLoop(control: LocalServerSocket) {
        while (true) {
            val s = try { control.accept() } catch (_: IOException) { continue }
            s.use {
                val uid = runCatching { it.peerCredentials.uid }.getOrDefault(-1)
                if (uid != 0 && uid != 2000) return@use
                when (readLine(it.inputStream)) {
                    "announce" -> announce()
                    "exit" -> exitProcess(0)
                }
            }
        }
    }

    private fun handle(s: Socket) {
        s.soTimeout = 15_000
        val line = readLine(s.getInputStream()) ?: return
        var method = ""
        val reply = try {
            val req = JSONObject(line)
            val given = req.optString("token").toByteArray()
            if (!MessageDigest.isEqual(given, token.toByteArray())) return // wrong token: say nothing
            method = req.getString("method")
            JSONObject().put("result", call(method, req.optJSONObject("params") ?: JSONObject()))
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: e.javaClass.simpleName)
        }
        s.getOutputStream().apply { write((reply.toString() + "\n").toByteArray()); flush() }
        if (method == "exit") { Thread.sleep(100); exitProcess(0) }
    }

    private fun call(method: String, p: JSONObject): Any = when (method) {
        "ping" -> JSONObject().put("version", VERSION).put("uid", android.os.Process.myUid())
        "lru" -> run("dumpsys", "activity", "lru")
        "ps" -> run("ps", "-A", "-o", "PID,RSS")
        "forceStop" -> run("am", "force-stop", "--user", "0", pkg(p))
        "kill" -> run("am", "kill", "--user", "0", pkg(p))
        "killAll" -> run("am", "kill-all")
        "batteryUse" -> batteryUse()
        "appOps" -> appOps(p)
        "exit" -> "bye"
        else -> throw IllegalArgumentException("Unknown method: $method")
    }

    private fun pkg(p: JSONObject): String {
        val pkg = p.getString("pkg")
        require(PACKAGE_NAME.matches(pkg) && pkg.length < 256) { "Not a package name" }
        require(pkg !in NEVER_STOP) { "$pkg can't be stopped" }
        return pkg
    }

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

    /** Runs a command directly (no shell) and returns what it printed. */
    private fun run(vararg cmd: String): String {
        val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        proc.outputStream.close()
        val out = ByteArrayOutputStream()
        val reader = thread(isDaemon = true) { proc.inputStream.use { it.copyTo(out) } }
        if (!proc.waitFor(20, TimeUnit.SECONDS)) { proc.destroy(); throw IOException("${cmd[0]} timed out") }
        reader.join(2000)
        return out.toString("UTF-8")
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (buf.size() < 4096) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) return buf.toString("UTF-8")
            buf.write(b)
        }
        return null
    }
}
