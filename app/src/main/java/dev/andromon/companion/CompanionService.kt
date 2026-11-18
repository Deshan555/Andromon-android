// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Foreground service hosting the PC link.
 *
 * Transport: an abstract-namespace Unix socket ([SOCKET_NAME]). The PC reaches it through the
 * USB ADB connection (`localabstract:andromon_companion`), so nothing listens on Wi-Fi.
 *
 * Security: abstract sockets are visible to every app on the phone, so each connection's peer
 * UID is checked. Only adbd (runs as the shell user, uid 2000) or root may talk to us.
 *
 * Protocol: one request per connection. The client sends a single JSON line
 * `{"id":1,"method":"sms.list","params":{...}}`; the service replies with one JSON line
 * `{"id":1,"result":...}` or `{"id":1,"error":"..."}` and closes the socket.
 *
 * The one exception is `events`: the connection stays open and the service writes one JSON line
 * per event until the PC closes it: call alerts from [CallWatch] (params `calls`, default on),
 * phone notifications from [NotifWatch] (`notifications`, default off), and a `ping` every 20 s.
 * While a PC listens, the service doesn't stop for being idle. `rec.session` also stays open:
 * see [recSession].
 */
class CompanionService : Service() {

    companion object {
        const val SOCKET_NAME = "andromon_companion"
        const val ACTION_STOP = "dev.andromon.companion.STOP"
        private const val CHANNEL_ID = "pc_link"
        private const val NOTIFICATION_ID = 1
        private const val SHELL_UID = 2000
        private const val ROOT_UID = 0
        private const val IDLE_TIMEOUT_MS = 15 * 60 * 1000L
        private const val TAG = "AndromonCompanion"

        @Volatile var running = false
            private set

        /** Called on the main thread when [running] changes; the status screen sets it while visible. */
        var stateListener: (() -> Unit)? = null

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, CompanionService::class.java))
        }
    }

    private var server: LocalServerSocket? = null
    private val workers: ExecutorService = Executors.newFixedThreadPool(6)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastActivity = System.currentTimeMillis()

    private val idleCheck = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() - lastActivity > IDLE_TIMEOUT_MS) {
                Log.i(TAG, "Idle for 15 minutes, stopping")
                stopSelf()
            } else {
                main.postDelayed(this, 60_000)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        startServer()
        running = true
        stateListener?.invoke()
        main.postDelayed(idleCheck, 60_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        lastActivity = System.currentTimeMillis()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        RecOverlay.endAll()
        Ringer.stop(applicationContext, "link") // Find my phone can't outlive the link: nothing would time it out
        stateListener?.invoke()
        main.removeCallbacks(idleCheck)
        try { server?.close() } catch (_: IOException) {}
        server = null
        workers.shutdownNow()
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "PC connection", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, CompanionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Connected to Andromon")
            .setContentText("Your PC can read messages, contacts and call log over USB")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .addAction(Links.clipAction(this))
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startServer() {
        if (server != null) return
        val socket = try {
            LocalServerSocket(SOCKET_NAME) // abstract namespace
        } catch (e: IOException) {
            Log.e(TAG, "Cannot open socket", e)
            stopSelf()
            return
        }
        server = socket
        thread(name = "pc-link-accept", isDaemon = true) {
            while (true) {
                val client = try { socket.accept() } catch (_: IOException) { break }
                try {
                    workers.execute { handle(client) }
                } catch (_: Exception) {
                    client.close()
                }
            }
        }
    }

    private fun handle(client: LocalSocket) {
        client.use { sock ->
            val uid = try { sock.peerCredentials.uid } catch (_: IOException) { -1 }
            if (uid != SHELL_UID && uid != ROOT_UID) {
                Log.w(TAG, "Rejected connection from uid $uid")
                return
            }
            lastActivity = System.currentTimeMillis()

            val line = readLine(sock) ?: return
            val response = JSONObject()
            var stopAfter = false
            try {
                val request = JSONObject(line)
                response.put("id", request.opt("id"))
                val method = request.getString("method")
                if (method == "events") {
                    streamEvents(sock, request.optJSONObject("params") ?: JSONObject())
                    return
                }
                if (method == "rec.session") {
                    recSession(sock, request.opt("id"), request.optJSONObject("params") ?: JSONObject())
                    return
                }
                if (method == "stop") {
                    stopAfter = true
                    response.put("result", true)
                } else {
                    response.put("result", Api(this).call(method, request.optJSONObject("params") ?: JSONObject()))
                }
            } catch (e: SecurityException) {
                response.put("error", "Permission denied on the phone: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "Request failed", e)
                response.put("error", e.message ?: e.javaClass.simpleName)
            }

            val out = BufferedOutputStream(sock.outputStream, 64 * 1024)
            out.write(response.toString().toByteArray(Charsets.UTF_8))
            out.write('\n'.code)
            out.flush()
            if (stopAfter) main.post { stopSelf() }
        }
    }

    /**
     * Test recording on the PC: shows the floating "Recording" badge ([RecOverlay]) for as long as
     * this connection stays open. After the reply, the PC sends one JSON line per change
     * (`{"steps":3,"last":"Tap “Log in”"}`) and a `{"ping":true}` now and then.
     */
    private fun recSession(sock: LocalSocket, id: Any?, params: JSONObject) {
        val token = RecOverlay.begin(this, params.optInt("steps", 0), params.optString("last", ""))
        val out = BufferedOutputStream(sock.outputStream, 1024)
        out.write(JSONObject().put("id", id).put("result", JSONObject().put("overlay", token != 0).put("canDraw", RecOverlay.allowed(this))).toString().toByteArray(Charsets.UTF_8))
        out.write('\n'.code)
        out.flush()
        if (token == 0) return
        try {
            val reader = sock.inputStream.bufferedReader(Charsets.UTF_8)
            while (true) {
                val line = reader.readLine() ?: break
                lastActivity = System.currentTimeMillis()
                val o = try { JSONObject(line) } catch (_: Exception) { continue }
                if (o.optBoolean("ping")) continue
                RecOverlay.update(token, o.optInt("steps", 0), o.optString("last", ""))
            }
        } catch (_: IOException) {
        } finally {
            RecOverlay.end(token)
        }
    }

    /** Keeps the connection open and writes events to it until the PC goes away. */
    private fun streamEvents(sock: LocalSocket, params: JSONObject) {
        val calls = params.optBoolean("calls", true)
        val notifications = params.optBoolean("notifications", false)
        val queue = LinkedBlockingQueue<JSONObject>()
        val listener: (JSONObject) -> Unit = { queue.offer(it) }
        val out = BufferedOutputStream(sock.outputStream, 8 * 1024)
        fun send(o: JSONObject) {
            out.write(o.toString().toByteArray(Charsets.UTF_8))
            out.write('\n'.code)
            out.flush()
        }
        if (calls) CallWatch.add(this, listener)
        if (notifications) NotifWatch.add(listener)
        LinkEvents.add(listener) // clipboard sent from the phone, Find my phone stopped (Links.kt)
        if (params.optBoolean("media", false)) Player.add(this, listener) // now playing (Player.kt)
        try {
            send(JSONObject().put("type", "hello").put("permissions", CallWatch.permissions(this))
                .put("notifications", NotifWatch.status(this)))
            while (true) {
                // A ping both keeps us from stopping as idle and finds out when the PC has gone
                // (writing to a closed socket fails).
                val ev = queue.poll(20, TimeUnit.SECONDS) ?: JSONObject().put("type", "ping")
                lastActivity = System.currentTimeMillis()
                send(ev)
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        } finally {
            CallWatch.remove(listener)
            NotifWatch.remove(listener)
            LinkEvents.remove(listener)
            Player.remove(listener)
        }
    }

    /** Reads one '\n'-terminated UTF-8 line (max 32 MB, e.g. a big contacts import). */
    private fun readLine(sock: LocalSocket): String? {
        val input = sock.inputStream
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            val nl = chunk.indexOf('\n'.code.toByte()).takeIf { it in 0 until n }
            if (nl != null) {
                buf.write(chunk, 0, nl)
                return buf.toString(Charsets.UTF_8.name())
            }
            buf.write(chunk, 0, n)
            if (buf.size() > 32 * 1024 * 1024) throw IOException("Request too large")
        }
        return if (buf.size() > 0) buf.toString(Charsets.UTF_8.name()) else null
    }
}
