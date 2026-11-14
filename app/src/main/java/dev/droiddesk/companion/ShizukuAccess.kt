// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

package dev.droiddesk.companion

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Everything that touches the Shizuku library (dev.rikka.shizuku:api 13.1.5, MIT): whether the
 * Shizuku app is there and running, the permission it gives per app, and the UserService
 * ([PrivilegedService]) that runs the companion's fixed commands with Shizuku's rights.
 *
 * Shizuku's binder arrives through ShizukuProvider (manifest) shortly after the process starts,
 * and can die at any time (Shizuku stopped, phone's adb restarted). Every call checks first and
 * reports [ShellHelperClient.Unavailable] instead of crashing.
 */
internal object ShizukuAccess {

    enum class State { NOT_INSTALLED, NOT_RUNNING, TOO_OLD, NEEDS_PERMISSION, DENIED, READY }

    /** [api] is Shizuku's API version (11+), [app] the Shizuku app's version name, [uid] 0 for root or 2000 for adb. */
    class Status(val state: State, val api: Int? = null, val app: String? = null, val uid: Int? = null) {
        val ready get() = state == State.READY
        val asRoot get() = uid == 0
        /** "Ready · Shizuku 13.5.4, running as adb" */
        fun line(): String {
            val what = "Shizuku" + (app?.let { " $it" } ?: api?.let { " (API $it)" } ?: "")
            val who = when (uid) { 0 -> ", running as root"; 2000 -> ", running as adb"; null -> ""; else -> ", running as uid $uid" }
            return when (state) {
                State.NOT_INSTALLED -> "Not installed"
                State.NOT_RUNNING -> "$what is installed but not running"
                State.TOO_OLD -> "$what is too old. Version 11 or later is needed"
                State.NEEDS_PERMISSION -> "Needs your permission · $what$who"
                State.DENIED -> "Permission denied · $what$who"
                State.READY -> "Ready · $what$who"
            }
        }
    }

    private const val REQUEST = 7401
    private val main = Handler(Looper.getMainLooper())
    private val watchers = CopyOnWriteArraySet<() -> Unit>()
    @Volatile private var service: IPrivilegedService? = null
    @Volatile private var latch: CountDownLatch? = null
    private var onPermission: ((Boolean) -> Unit)? = null
    private var hooked = false

    /** Registers the library's listeners once. Safe before Shizuku's binder has arrived. */
    @Synchronized
    private fun hook() {
        if (hooked) return
        hooked = true
        runCatching {
            Shizuku.addBinderReceivedListenerSticky({ changed() }, main)
            Shizuku.addBinderDeadListener({ service = null; latch?.countDown(); changed() }, main)
            Shizuku.addRequestPermissionResultListener({ code, result ->
                if (code == REQUEST) {
                    onPermission?.invoke(result == PackageManager.PERMISSION_GRANTED)
                    onPermission = null
                    changed()
                }
            }, main)
        }
    }

    private fun changed() { for (w in watchers) runCatching { w() } }

    /** [onChange] runs on the main thread when Shizuku starts, stops or answers a permission request. */
    fun watch(onChange: () -> Unit) { hook(); watchers += onChange }
    fun unwatch(onChange: () -> Unit) { watchers -= onChange }

    fun installed(ctx: Context) = appVersion(ctx) != null

    private fun appVersion(ctx: Context): String? =
        runCatching { ctx.packageManager.getPackageInfo(PrivilegedCommands.SHIZUKU_PKG, 0).versionName ?: "" }.getOrNull()

    private fun alive() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun status(ctx: Context): Status {
        hook()
        val app = appVersion(ctx)?.takeIf { it.isNotEmpty() }?.substringBefore(' ')
        // Sui (the root module) has no app of its own: a live binder counts whether or not the app is installed.
        if (!alive()) return Status(if (app != null || installed(ctx)) State.NOT_RUNNING else State.NOT_INSTALLED, app = app)
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) return Status(State.TOO_OLD, app = app)
        val api = runCatching { Shizuku.getVersion() }.getOrNull()
        val uid = runCatching { Shizuku.getUid() }.getOrNull()
        if (api == null || api < 11) return Status(State.TOO_OLD, api, app, uid)
        val granted = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        val state = when {
            granted -> State.READY
            runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false) -> State.DENIED
            else -> State.NEEDS_PERMISSION
        }
        return Status(state, api, app, uid)
    }

    /**
     * Shows Shizuku's own "Allow Droid Desk Companion?" dialog. [onResult] runs on the main thread.
     * False straight away when Shizuku isn't running or the person chose "Deny and don't ask again".
     */
    fun requestPermission(ctx: Context, onResult: (Boolean) -> Unit) {
        hook()
        when (status(ctx).state) {
            State.READY -> onResult(true)
            State.NEEDS_PERMISSION -> {
                onPermission = onResult
                runCatching { Shizuku.requestPermission(REQUEST) }.onFailure { onPermission = null; onResult(false) }
            }
            else -> onResult(false)
        }
    }

    // ---------- the UserService ----------

    private fun args(ctx: Context): Shizuku.UserServiceArgs {
        @Suppress("DEPRECATION")
        val code = runCatching {
            val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) p.longVersionCode.toInt() else p.versionCode
        }.getOrDefault(0)
        return Shizuku.UserServiceArgs(ComponentName(ctx.packageName, PrivilegedService::class.java.name))
            // Daemon: it outlives this app's process, like the PC-started helper, so a forced Doze
            // is still lifted when the screen comes on after Android has closed the app.
            .daemon(true)
            .processNameSuffix("privileged")
            .debuggable(false)
            // The tag names the service (class names change under R8); a new app version or a
            // new command set makes Shizuku replace a copy that is still running.
            .tag("privileged")
            .version(code * 100 + PrivilegedCommands.VERSION)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) service = IPrivilegedService.Stub.asInterface(binder)
            latch?.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    private fun unavailable(why: String) = ShellHelperClient.Unavailable(why)

    @Synchronized
    private fun bound(ctx: Context): IPrivilegedService {
        service?.let { if (it.asBinder().pingBinder()) return it }
        service = null
        val l = CountDownLatch(1)
        latch = l
        try {
            Shizuku.bindUserService(args(ctx), connection)
        } catch (e: Throwable) { // binder died in between, or Shizuku is older than it claims
            throw unavailable("Shizuku couldn't start the helper: ${e.message ?: e.javaClass.simpleName}")
        }
        // Shizuku starts a new process for it: a second or two the first time.
        if (!l.await(12, TimeUnit.SECONDS)) throw unavailable("Shizuku didn't start the helper in time")
        return service ?: throw unavailable("Shizuku stopped while starting the helper")
    }

    /**
     * Runs one of [PrivilegedCommands] through Shizuku. Blocks; never call it on the main thread
     * (the service connection is delivered there). Throws [ShellHelperClient.Unavailable] when
     * Shizuku isn't ready, and IOException with the command's own error otherwise.
     */
    fun call(ctx: Context, method: String, params: JSONObject): Any {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Privileged calls block: use a background thread" }
        hook()
        val app = ctx.applicationContext
        // Started in the background (a tile, a job, the watch service): Shizuku's binder arrives a
        // moment after the process starts.
        if (!alive() && installed(app) && SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime() < 6000) {
            repeat(15) { if (!alive()) Thread.sleep(200) }
        }
        val st = status(app)
        if (!st.ready) throw unavailable("Shizuku: ${st.line()}")
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            try {
                val reply = JSONObject(bound(app).call(method, params.toString()))
                if (!reply.has("error")) return reply.get("result")
                val error = reply.getString("error")
                // A copy from an older version of the app is still running: replace it once.
                if (attempt == 0 && error.startsWith("Unknown method")) {
                    runCatching { Shizuku.unbindUserService(args(app), connection, true) }
                    service = null
                    Thread.sleep(300)
                    lastError = IOException(error)
                } else throw IOException(error)
            } catch (e: IOException) {
                throw e
            } catch (e: Throwable) { // DeadObjectException and friends: Shizuku or the service went away mid-call
                service = null
                lastError = e
                if (!alive()) throw unavailable("Shizuku stopped")
            }
        }
        throw (lastError as? IOException) ?: unavailable("Shizuku's helper stopped answering: ${lastError?.message ?: "no reply"}")
    }
}
