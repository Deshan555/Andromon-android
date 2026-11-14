// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors
// The screen-off freeze (do nothing when no app is pending, at most one run a minute) follows
// SuperFreezZ (https://gitlab.com/SuperFreezZ/SuperFreezZ, commit 267d0f7), GPL-3.0-or-later,
// Copyright (c) 2018 Hocuri: src/superfreeze/tool/android/backend/FreezeOnScreenOff.kt.
// Its way of waking the screen to press buttons is not used; see FreezeAccessibility.kt.

package dev.droiddesk.companion

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.util.Log
import kotlin.concurrent.thread

/**
 * Background triggers for the Freezer and the Battery saver: freeze when the screen turns off or
 * the battery runs low, Deep sleep with the screen off, and the charging alerts.
 *
 * Android tells apps about "screen off" and battery changes only while they run and have asked
 * for it in code; a closed app hears nothing. So while at least one of these options is on, the
 * app keeps [PowerWatchService] running: a foreground service with a visible "Battery watch"
 * notification that names what it watches. It starts when an option is turned on and after a
 * restart of the phone, and stops when the last option is turned off. Nothing runs when all
 * options are off.
 *
 * If Android stops the service anyway, the 15-minute battery job ([BatteryJob]) still checks the
 * battery level and the charging alerts, late by up to 15 minutes; screen-off triggers wait until
 * the service is back (when the app is opened or the phone restarts).
 */
internal object PowerWatch {
    private const val TAG = "DroidDeskWatch"
    const val ACTION_FREEZE = "dev.droiddesk.companion.FREEZE_ALARM"
    private const val CH_WATCH = "battery_watch"
    private const val CH_ALERT = "battery_alerts"
    private const val NOTE_WATCH = 4201
    private const val NOTE_LIMIT = 4202
    private const val NOTE_HEAT = 4203
    private const val NOTE_FREEZE = 4204

    private fun sp(ctx: Context) = ctx.getSharedPreferences("power_watch", Context.MODE_PRIVATE)

    /** What the service has to watch for, in the words of its notification. Empty = no need to run. */
    fun watching(ctx: Context): List<String> = buildList {
        if (Freezer.onScreenOff(ctx)) add("freeze when the screen turns off")
        if (BatterySaver.dozeOnScreenOff(ctx)) add("deep sleep with the screen off")
        if (Freezer.batteryBelow(ctx) > 0) add("freeze below ${Freezer.batteryBelow(ctx)}%")
        if (BatterySaver.chargeLimit(ctx) > 0) add("charged to ${BatterySaver.chargeLimit(ctx)}%")
        if (BatterySaver.fullAlert(ctx)) add("fully charged")
        if (BatterySaver.heatAlert(ctx)) add("battery too hot")
    }

    /**
     * Starts or stops [PowerWatchService] to match the options. Call it after changing one (the
     * app is on screen then, so Android allows the start), at app start and after a restart.
     * Returns false when Android refused to start it from the background.
     */
    fun sync(ctx: Context): Boolean {
        val app = ctx.applicationContext
        val wanted = watching(app).isNotEmpty()
        return try {
            if (wanted) app.startForegroundService(Intent(app, PowerWatchService::class.java))
            else if (PowerWatchService.running) app.stopService(Intent(app, PowerWatchService::class.java))
            true
        } catch (e: Exception) { // ForegroundServiceStartNotAllowedException on Android 12+
            Log.w(TAG, "Couldn't start the watch service: ${e.javaClass.simpleName}")
            false
        }
    }

    fun channels(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_WATCH, "Battery watch", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while the Freezer or the Battery saver waits for the screen to turn off or the battery to change"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Battery alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Charge limit reached, fully charged, battery too hot"
        })
        return nm
    }

    private fun openApp(ctx: Context, screen: String, code: Int): PendingIntent = PendingIntent.getActivity(ctx, code,
        Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, screen)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun watchNotification(ctx: Context): Notification {
        channels(ctx)
        val what = watching(ctx)
        val text = if (what.isEmpty()) "Nothing to watch" else "Waiting for: " + what.joinToString(", ")
        return Notification.Builder(ctx, CH_WATCH)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle("Battery watch")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText("$text. Turn these options off in the Freezer or the Battery saver to remove this notification."))
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openApp(ctx, "Saver", 61))
            .build()
    }

    fun refreshNotification(ctx: Context) {
        if (PowerWatchService.running) runCatching { channels(ctx).notify(NOTE_WATCH, watchNotification(ctx)) }
    }

    const val WATCH_ID = NOTE_WATCH

    private fun alert(ctx: Context, id: Int, title: String, text: String, intent: PendingIntent = openApp(ctx, "Saver", 62)) {
        runCatching {
            channels(ctx).notify(id, Notification.Builder(ctx, CH_ALERT)
                .setSmallIcon(R.drawable.ic_battery)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(intent)
                .build())
        }
    }

    // ---------- screen off / on ----------

    private fun freezeAlarm(ctx: Context) = PendingIntent.getBroadcast(ctx, 63,
        Intent(ctx, PowerReceiver::class.java).setAction(ACTION_FREEZE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Holds the CPU for a short background task after the screen went off. */
    private fun briefly(ctx: Context, work: () -> Unit) {
        val wl = runCatching {
            ctx.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "droiddesk:powerwatch")
                .apply { acquire(60_000) }
        }.getOrNull()
        thread(isDaemon = true) {
            try { work() } catch (e: Exception) { Log.w(TAG, "Background task failed: ${e.message}") }
            finally { runCatching { if (wl?.isHeld == true) wl.release() } }
        }
    }

    fun onScreenOff(ctx: Context) {
        val app = ctx.applicationContext
        if (Freezer.onScreenOff(app)) {
            val delay = Freezer.screenOffDelay(app)
            if (delay == 0) briefly(app) { freezeInBackground(app, "screen off") }
            else runCatching {
                // Not exact: Android may run it a little late while the phone sleeps.
                app.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay * 1000L, freezeAlarm(app))
            }
        }
        if (BatterySaver.dozeOnScreenOff(app) && !Phone.battery(app).charging) briefly(app) {
            try { BatterySaver.doze(app, true) } catch (e: Exception) { Log.w(TAG, "Deep sleep: ${e.message}") }
        }
    }

    fun onScreenOn(ctx: Context) {
        val app = ctx.applicationContext
        runCatching { app.getSystemService(AlarmManager::class.java).cancel(freezeAlarm(app)) }
        // The privileged process lifts a forced Doze by itself as well; this is the faster path.
        if (BatterySaver.dozeForced(app)) briefly(app) { BatterySaver.doze(app, false) }
    }

    /** The delayed screen-off freeze is due. */
    fun onFreezeAlarm(ctx: Context, done: () -> Unit) {
        val app = ctx.applicationContext
        if (app.getSystemService(PowerManager::class.java).isInteractive || !Freezer.onScreenOff(app)) { done(); return }
        briefly(app) { try { freezeInBackground(app, "screen off") } finally { done() } }
    }

    /**
     * Freezes what is pending without any screen. Only works with privileged access; without it
     * this does nothing (the accessibility method needs the screen on and the person present).
     * Returns the number of apps frozen, or null when nothing was attempted.
     */
    private fun freezeInBackground(ctx: Context, why: String): Int? {
        val now = System.currentTimeMillis()
        // Throttle to once a minute:
        val last = sp(ctx).getLong("last_freeze", 0)
        if (last <= now && last + 60 * 1000 > now) return null
        if (Privileged.active(ctx) == null) return null
        val pending = Freezer.pendingPackages(ctx)
        if (pending.isEmpty()) return null
        val out = Freezer.freeze(ctx, pending)
        sp(ctx).edit().putLong("last_freeze", now).putInt("last_count", out.frozen).putString("last_why", why).apply()
        return out.frozen
    }

    /** "12 apps, today 14:02 (screen off)", or null when no automatic freeze has run yet. */
    fun lastRun(ctx: Context): String? {
        val t = sp(ctx).getLong("last_freeze", 0)
        if (t == 0L) return null
        val n = sp(ctx).getInt("last_count", 0)
        val time = android.text.format.DateUtils.getRelativeDateTimeString(ctx, t, android.text.format.DateUtils.MINUTE_IN_MILLIS,
            android.text.format.DateUtils.WEEK_IN_MILLIS, 0)
        return "$n ${if (n == 1) "app" else "apps"}, $time (${sp(ctx).getString("last_why", "")})"
    }

    // ---------- battery level, charging ----------

    /**
     * Looks at the battery: freeze below the chosen level, charge-limit, full and heat alerts.
     * Each fires once and is armed again when the condition has passed. Called for every battery
     * change while the service runs, and by the 15-minute job otherwise.
     */
    @Synchronized
    fun onBattery(ctx: Context, sticky: Intent? = null) {
        val app = ctx.applicationContext
        val b = Phone.battery(app, sticky)
        val status = (sticky ?: app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val p = sp(app)
        val e = p.edit()

        val below = Freezer.batteryBelow(app)
        if (below > 0 && !b.charging && b.level < below) {
            if (!p.getBoolean("fired_low", false)) {
                e.putBoolean("fired_low", true)
                briefly(app) {
                    if (Privileged.active(app) != null) freezeInBackground(app, "battery below $below%")
                    else {
                        val n = Freezer.pendingPackages(app).size
                        if (n > 0) alert(app, NOTE_FREEZE, "Battery below $below%",
                            "$n ${if (n == 1) "app" else "apps"} can be frozen. Tap to freeze now.",
                            PendingIntent.getActivity(app, 64, FreezeActivity.intent(app), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                    }
                }
            }
        } else if (p.getBoolean("fired_low", false) && (b.charging || b.level >= below + 2)) e.putBoolean("fired_low", false)

        val limit = BatterySaver.chargeLimit(app)
        if (limit > 0 && b.charging && b.level >= limit) {
            if (!p.getBoolean("fired_limit", false)) {
                e.putBoolean("fired_limit", true)
                alert(app, NOTE_LIMIT, "Charged to ${b.level}%", "Your charge limit is $limit%. Unplug the charger to spare the battery.")
            }
        } else if (p.getBoolean("fired_limit", false) && (!b.charging || b.level < limit - 2)) e.putBoolean("fired_limit", false)

        val full = b.charging && (status == BatteryManager.BATTERY_STATUS_FULL || b.level >= 100)
        if (BatterySaver.fullAlert(app) && full) {
            if (!p.getBoolean("fired_full", false)) {
                e.putBoolean("fired_full", true)
                alert(app, NOTE_LIMIT, "Fully charged", "The battery is at 100%. You can unplug the charger.")
            }
        } else if (p.getBoolean("fired_full", false) && !b.charging) e.putBoolean("fired_full", false)

        val temp = b.tempC
        if (BatterySaver.heatAlert(app) && temp != null && temp >= BatterySaver.HOT_C) {
            val now = System.currentTimeMillis()
            val last = p.getLong("last_heat", 0)
            if (last > now || now - last > 30 * 60_000L) {
                e.putLong("last_heat", now)
                alert(app, NOTE_HEAT, "Battery is hot: %.0f °C".format(temp),
                    if (b.charging) "Unplug the charger and let the phone cool down." else "Let the phone cool down; close games or navigation for a while.")
            }
        }
        e.apply()
    }

    /** From [BatteryJob], about every 15 minutes: the fallback while the service isn't running. */
    fun onJobTick(ctx: Context) {
        if (watching(ctx).isEmpty()) return
        if (!PowerWatchService.running) {
            sync(ctx) // Android 12+ usually refuses this from the background; the check below still runs
            runCatching { onBattery(ctx) }
        }
    }
}

/**
 * Keeps the app able to hear "screen off", "screen on" and battery changes while one of the
 * background options is on (see [PowerWatch]). Foreground service of type specialUse with a
 * visible notification; not exported, started only by this app.
 */
class PowerWatchService : Service() {

    companion object {
        @Volatile var running = false
            private set
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> PowerWatch.onScreenOff(c)
                Intent.ACTION_SCREEN_ON -> PowerWatch.onScreenOn(c)
                Intent.ACTION_BATTERY_CHANGED -> runCatching { PowerWatch.onBattery(c, i) }
            }
        }
    }
    private var registered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val n = PowerWatch.watchNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(PowerWatch.WATCH_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(PowerWatch.WATCH_ID, n)
        } catch (e: Exception) {
            Log.w("DroidDeskWatch", "Not allowed to run in the foreground: ${e.javaClass.simpleName}")
            stopSelf()
            return
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)
        registered = true
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (PowerWatch.watching(this).isEmpty()) { stopSelf(); return START_NOT_STICKY }
        PowerWatch.refreshNotification(this)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        if (registered) runCatching { unregisterReceiver(receiver) }
        // Don't leave the phone in a forced Doze nobody will lift.
        if (BatterySaver.dozeForced(this)) thread(isDaemon = true) { runCatching { BatterySaver.doze(applicationContext, false) } }
        super.onDestroy()
    }
}

/**
 * Restarts the watch after the phone restarts or the app is updated (Android allows starting a
 * foreground service then), and receives the delayed screen-off freeze alarm. Not exported: only
 * Android itself and this app can send to it.
 */
class PowerReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> PowerWatch.sync(ctx)
            PowerWatch.ACTION_FREEZE -> {
                val pending = goAsync()
                PowerWatch.onFreezeAlarm(ctx) { runCatching { pending.finish() } }
            }
        }
    }
}

/** Quick-settings tile "Freeze now": runs [FreezeActivity], which freezes what is pending. */
class FreezeTile : DeskTile() {
    override fun state() = Tile.STATE_INACTIVE
    override fun subtitle(state: Int) = "Tap to freeze"

    override fun onClick() {
        val go = Runnable { open(FreezeActivity.intent(this), 54) }
        if (isLocked) unlockAndRun(go) else go.run()
    }
}
