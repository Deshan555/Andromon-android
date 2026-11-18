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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlin.concurrent.thread

/**
 * Keeps Wi‑Fi receive running while the app is in the background: owns the [WifiServer] and shows
 * its notifications (ongoing status with progress, Accept/Decline for each incoming transfer, and
 * a summary when a transfer ends). Turns itself off after 30 minutes without transfers.
 * Not exported: only this app starts it.
 */
class ReceiveService : Service() {

    companion object {
        private const val ACTION_STOP = "dev.andromon.companion.receive.STOP"
        private const val ACTION_ACCEPT = "dev.andromon.companion.receive.ACCEPT"
        private const val ACTION_DECLINE = "dev.andromon.companion.receive.DECLINE"
        private const val EXTRA_ID = "id"
        private const val CH_ON = "wifi_receive"
        private const val CH_ASK = "wifi_incoming"
        private const val CH_DONE = "wifi_received"
        private const val NOTE_ON = 2
        private const val NOTE_ASK = 3
        private const val NOTE_DONE = 4
        private const val IDLE_MS = 30 * 60_000L
        private const val TAG = "ReceiveService"

        fun start(ctx: Context) {
            WifiReceive.post { if (!running) { starting = true; message = null } }
            ctx.startForegroundService(Intent(ctx, ReceiveService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ReceiveService::class.java).setAction(ACTION_STOP))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var server: WifiServer? = null
    @Volatile private var destroyed = false
    private var shownRequest: String? = null
    private var shownDone = -1
    private var lastOngoing = 0L
    private val listener: () -> Unit = { onState() }

    private val idleCheck = object : Runnable {
        override fun run() {
            val s = server
            if (s != null && WifiReceive.progress == null && WifiReceive.request == null &&
                System.currentTimeMillis() - s.lastActivity > IDLE_MS) {
                WifiReceive.post { message = "Turned off after 30 minutes without transfers." }
                stopSelf()
            } else {
                main.postDelayed(this, 60_000)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val n = ongoing()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ON, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTE_ON, n)
        WifiReceive.loadRecent(this)
        shownDone = WifiReceive.done?.seq ?: 0
        WifiReceive.listen(listener)
        WifiReceive.post { starting = true; running = false }
        thread(name = "wifi-receive-start", isDaemon = true) {
            val s = WifiServer(applicationContext)
            try {
                s.start()
                if (destroyed) { s.stop(); return@thread }
                server = s
                WifiReceive.server = s
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't start", e)
                s.stop()
                WifiReceive.post { starting = false; running = false; message = e.message ?: "Couldn't start receiving." }
                main.post { stopSelf() }
            }
        }
        main.postDelayed(idleCheck, 60_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_ACCEPT, ACTION_DECLINE -> {
                intent.getStringExtra(EXTRA_ID)?.let { WifiReceive.respond(it, intent.action == ACTION_ACCEPT) }
                getSystemService(NotificationManager::class.java).cancel(NOTE_ASK)
            }
            else -> server?.announce() // turned on again while running: just be visible again
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        WifiReceive.unlisten(listener)
        val s = server
        server = null
        WifiReceive.server = null
        if (s != null) thread(isDaemon = true) { s.stop() }
        WifiReceive.post { starting = false; running = false; request = null; progress = null }
        getSystemService(NotificationManager::class.java).cancel(NOTE_ASK)
        super.onDestroy()
    }

    // ---------- notifications ----------

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ON, "Wi‑Fi receive", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ASK, "Incoming files", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Asks before a computer sends you files"
        })
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Received files", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 10,
        Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, "Receive")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Accept for a Direct link request: Android only starts the hotspot while the app is on screen. */
    private fun acceptInApp(id: String): PendingIntent = PendingIntent.getActivity(
        this, 14,
        Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, "Receive")
            .putExtra(MainActivity.EXTRA_ACCEPT, id)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun serviceIntent(action: String, code: Int, id: String? = null) = PendingIntent.getService(
        this, code, Intent(this, ReceiveService::class.java).setAction(action).apply { id?.let { putExtra(EXTRA_ID, it) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ongoing(): Notification {
        val p = WifiReceive.progress
        val b = Notification.Builder(this, CH_ON)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Turn off", serviceIntent(ACTION_STOP, 11)).build())
        if (p != null) {
            val frac = if (p.total > 0) (p.received * 1000 / p.total).toInt() else 0
            b.setContentTitle("Receiving from ${p.from}" + (p.via?.let { " · $it" } ?: ""))
                .setContentText("${p.filesDone} of ${p.fileCount} files · ${fmtSize(p.received)} of ${fmtSize(p.total)}")
                .setProgress(1000, frac, false)
        } else {
            val addr = WifiServer.lanAddress()
            b.setContentTitle(if (WifiReceive.running) "Ready to receive files" else "Starting Wi‑Fi receive…")
                .setContentText(if (addr != null) "Visible on Wi‑Fi as ${WifiServer.deviceName(this)}" else "Connect to Wi‑Fi to receive files")
        }
        return b.build()
    }

    private fun onState() {
        val nm = getSystemService(NotificationManager::class.java)

        // Ongoing status / progress (at most once a second while bytes are flowing).
        val now = System.currentTimeMillis()
        if (WifiReceive.progress == null || now - lastOngoing > 1000) {
            lastOngoing = now
            nm.notify(NOTE_ON, ongoing())
        }

        // Ask before receiving.
        val r = WifiReceive.request
        if (r != null && r.id != shownRequest) {
            shownRequest = r.id
            val what = if (r.files.size == 1) r.files[0].first else "${r.files.size} files"
            val list = r.files.take(8).joinToString("\n") { "${it.first}  ·  ${fmtSize(it.second)}" } +
                if (r.files.size > 8) "\n+ ${r.files.size - 8} more" else ""
            nm.notify(NOTE_ASK, Notification.Builder(this, CH_ASK)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("${r.from} wants to send $what")
                .setContentText("${fmtSize(r.total)}" + if (r.direct) " · Direct link (faster)" else " · saved to Download/${WifiServer.FOLDER}")
                .setStyle(Notification.BigTextStyle().bigText(list))
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setTimeoutAfter((r.expires - now).coerceAtLeast(1000))
                .addAction(Notification.Action.Builder(null, "Decline", serviceIntent(ACTION_DECLINE, 12, r.id)).build())
                .addAction(Notification.Action.Builder(null, "Accept",
                    if (r.direct) acceptInApp(r.id) else serviceIntent(ACTION_ACCEPT, 13, r.id)).build())
                .build())
        } else if (r == null && shownRequest != null) {
            shownRequest = null
            nm.cancel(NOTE_ASK)
        }

        // Summary when a transfer ends.
        val d = WifiReceive.done
        if (d != null && d.seq != shownDone) {
            shownDone = d.seq
            val title = when {
                d.problem == null -> if (d.count == 1) "Received 1 file" else "Received ${d.count} files"
                d.count > 0 -> "Received ${d.count} files, then the transfer stopped"
                else -> "Transfer from ${d.from} stopped"
            }
            val text = if (d.problem == null) "From ${d.from} · saved to Download/${WifiServer.FOLDER}" else d.problem
            nm.notify(NOTE_DONE, Notification.Builder(this, CH_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build())
        }
    }
}
