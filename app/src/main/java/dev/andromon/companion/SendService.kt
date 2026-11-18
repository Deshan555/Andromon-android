// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Keeps a Wi‑Fi send running when the person leaves the app: started when they tap Send, it
 * shows the progress with a Cancel button and stops itself when the transfer ends ([WifiSend]
 * does the work and holds the Wi‑Fi and wake locks). It opens outbound connections only.
 * Not exported: only this app starts it.
 */
class SendService : Service() {

    companion object {
        private const val ACTION_CANCEL = "dev.andromon.companion.send.CANCEL"
        private const val CH_ON = "wifi_send"
        private const val CH_DONE = "wifi_sent"
        private const val NOTE_ON = 5
        private const val NOTE_DONE = 6
        private const val TAG = "SendService"

        /** Sends [WifiSend]'s queue to its selected device. Call while the app is on screen. */
        internal fun start(ctx: Context) {
            val plain = Intent(ctx, SendService::class.java)
            // Hand the files' read grants to the service, so they last as long as the transfer
            // even if the activity that received them goes away.
            val uris = WifiSend.queue.mapNotNull { it.uri }.take(400)
            val granting = if (uris.isEmpty()) null else Intent(plain).apply {
                clipData = ClipData.newRawUri("files", uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                try {
                    ctx.startForegroundService(granting ?: plain)
                } catch (e: SecurityException) { // a grant that can't be passed on: the open files still work
                    ctx.startForegroundService(plain)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't start", e)
                WifiSend.post { message = "Android didn't allow the transfer to start. Open the app and try again." }
            }
        }
    }

    private var lastOngoing = 0L
    private var lastStage: WifiSend.Stage? = null
    private var shownDone = -1
    private var started = false
    private val listener: () -> Unit = { onState() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ON, "Wi‑Fi send", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Sent files", NotificationManager.IMPORTANCE_DEFAULT))
        val n = ongoing()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ON, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTE_ON, n)
        shownDone = WifiSend.done?.seq ?: 0
        WifiSend.listen(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            WifiSend.cancel()
            if (!WifiSend.active) finish()
        } else if (WifiSend.begin(applicationContext)) {
            started = true
            onState()
        } else {
            finish()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        WifiSend.unlisten(listener)
        if (WifiSend.active) WifiSend.cancel() // the system is stopping the service
        super.onDestroy()
    }

    private fun finish() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    // ---------- notifications ----------

    /** Opens the app on the Send side of the transfer screen (through [ShareActivity]). */
    private fun openApp(code: Int): PendingIntent = PendingIntent.getActivity(
        this, code, Intent(this, ShareActivity::class.java).setAction(ShareActivity.ACTION_OPEN),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ongoing(): Notification {
        val p = WifiSend.progress
        val b = Notification.Builder(this, CH_ON)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(openApp(20))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Cancel", PendingIntent.getService(
                this, 21, Intent(this, SendService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
        when (p?.stage) {
            null, WifiSend.Stage.PREPARING -> b.setContentTitle("Getting files ready…").setProgress(0, 0, true)
            WifiSend.Stage.WAITING -> b.setContentTitle("Waiting for ${p.to} to accept")
                .setContentText("${p.fileCount} ${if (p.fileCount == 1) "item" else "items"} · ${fmtSize(p.total)}")
                .setProgress(0, 0, true)
            WifiSend.Stage.PIN -> b.setContentTitle("${p.to} asks for a PIN").setContentText("Tap to type it")
            WifiSend.Stage.SENDING -> {
                val frac = if (p.total > 0) (p.sent * 1000 / p.total).toInt().coerceIn(0, 1000) else 0
                b.setContentTitle("Sending to ${p.to}")
                    .setContentText("${p.filesDone} of ${p.fileCount} · ${fmtSize(p.sent)} of ${fmtSize(p.total)}")
                    .setProgress(1000, frac, false)
            }
        }
        return b.build()
    }

    private fun onState() {
        val nm = getSystemService(NotificationManager::class.java)
        val p = WifiSend.progress
        if (WifiSend.active) {
            val now = System.currentTimeMillis()
            if (p?.stage != lastStage || now - lastOngoing > 1000) {
                lastStage = p?.stage
                lastOngoing = now
                nm.notify(NOTE_ON, ongoing())
            }
            return
        }
        if (!started) return
        val d = WifiSend.done
        if (d != null && d.seq != shownDone) {
            shownDone = d.seq
            val files = if (d.sent == 1) "1 item" else "${d.sent} items"
            val title = when {
                d.cancelled -> "Transfer to ${d.to} cancelled"
                d.problem == null && d.failed.isEmpty() -> "Sent $files to ${d.to}"
                d.sent > 0 -> "Sent $files to ${d.to}, ${d.count - d.sent} not sent"
                else -> "Nothing was sent to ${d.to}"
            }
            val text = d.problem ?: d.failed.firstOrNull()?.let { "${it.first}: ${it.second}" } ?: fmtSize(d.bytes)
            nm.notify(NOTE_DONE, Notification.Builder(this, CH_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp(22))
                .setAutoCancel(true)
                .build())
        }
        finish()
    }
}
