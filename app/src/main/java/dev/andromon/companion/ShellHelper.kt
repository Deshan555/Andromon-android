// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

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
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * A tiny server that runs with shell (ADB) or root rights, for the RAM manager.
 *
 * Android only lets an app see and stop its own processes. Like Shizuku and App Manager, this
 * borrows the shell user's rights instead: Andromon on the PC (over ADB), or `su` on a rooted
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
 * nothing can be injected; package names are checked before use ([PrivilegedCommands]). The app
 * reaches it through [Privileged], next to the Shizuku backend.
 *
 * Start command: see [ShellHelperClient.startCommand].
 */
object ShellHelper {
    /** Abstract Unix socket: makes sure only one copy runs, and lets shell/root ask it to re-announce or exit. */
    const val SOCKET = "andromon_helper"
    /** Bump when the protocol changes; the app restarts an older helper when it can. */
    const val VERSION = PrivilegedCommands.VERSION
    const val ACTION_READY = "dev.andromon.companion.HELPER_READY"

    private const val PKG = PrivilegedCommands.PKG

    private lateinit var token: String
    private var port = 0

    @JvmStatic
    fun main(args: Array<String>) {
        val control = try {
            LocalServerSocket(SOCKET)
        } catch (_: IOException) {
            // Already running: ask that copy to send its token again (the app may have lost it).
            println("andromon_helper: already running")
            runCatching { LocalSocket().use { it.connect(LocalSocketAddress(SOCKET)); it.outputStream.write("announce\n".toByteArray()) } }
            return
        }
        token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        port = server.localPort
        println("andromon_helper: running as uid ${android.os.Process.myUid()} on 127.0.0.1:$port")

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

    /** The commands themselves live in [PrivilegedCommands], shared with the Shizuku backend ([PrivilegedService]). */
    private fun call(method: String, p: JSONObject): Any =
        if (method == "exit") "bye" else PrivilegedCommands.call(method, p)

    private fun run(vararg cmd: String) = PrivilegedCommands.run(*cmd)

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
