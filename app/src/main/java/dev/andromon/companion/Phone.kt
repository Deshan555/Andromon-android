// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs

/** Quick readings of the phone's battery, memory and storage, for Home and the Battery screen. */
internal object Phone {

    class Battery(
        val level: Int,               // 0..100
        val charging: Boolean,
        val plugged: String?,         // "USB", "Charger", "Wireless", "Dock" or null
        val health: Int,              // BatteryManager.BATTERY_HEALTH_*
        val tempC: Float?,
        val voltageMv: Int?,
        val technology: String?,
        val fullInMs: Long?,          // time to full while charging, if Android knows it
        val cycles: Int?,             // Android 14+
        val powerSave: Boolean,
    ) {
        val healthy get() = health == BatteryManager.BATTERY_HEALTH_GOOD || health == BatteryManager.BATTERY_HEALTH_UNKNOWN
        val healthText get() = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Worn out"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "Too cold"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
            else -> "Unknown"
        }
        val hot get() = (tempC ?: 0f) >= 42f
    }

    fun battery(ctx: Context, sticky: Intent? = null): Battery {
        val i = sticky ?: ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val raw = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.takeIf { it > 0 } ?: 100
        val level = if (raw >= 0) raw * 100 / scale else bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val plugged = when (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) {
            BatteryManager.BATTERY_PLUGGED_AC -> "Charger"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            8 -> "Dock" // BATTERY_PLUGGED_DOCK, Android 14
            else -> null
        }
        val temp = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.div(10f)
        val volt = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }
        val full = if (Build.VERSION.SDK_INT >= 28 && charging) bm.computeChargeTimeRemaining().takeIf { it > 0 } else null
        val cycles = if (Build.VERSION.SDK_INT >= 34) i?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)?.takeIf { it >= 0 } else null
        return Battery(
            level = level.coerceIn(0, 100),
            charging = charging,
            plugged = plugged,
            health = i?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN) ?: BatteryManager.BATTERY_HEALTH_UNKNOWN,
            tempC = temp,
            voltageMv = volt,
            technology = i?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() },
            fullInMs = full,
            cycles = cycles,
            powerSave = ctx.getSystemService(PowerManager::class.java).isPowerSaveMode,
        )
    }

    /** Fraction of RAM in use. */
    fun memoryUsed(ctx: Context): Float {
        val m = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        return (m.totalMem - m.availMem).toFloat() / m.totalMem
    }

    /** Internal storage: total and free bytes. */
    fun storage(): Pair<Long, Long> {
        val s = StatFs(Environment.getDataDirectory().path)
        return s.totalBytes to s.availableBytes
    }

    /** One thing that pulls the health score down, and the screen that helps. */
    class Issue(val text: String, val penalty: Int, val screen: String)

    /**
     * A simple 0–100 score from what the phone itself reports: free storage, free memory and
     * battery condition. Each problem found costs points and is listed so the score is explainable.
     */
    fun health(ctx: Context): Pair<Int, List<Issue>> {
        val issues = mutableListOf<Issue>()
        val (total, free) = storage()
        val usedStorage = 1f - free.toFloat() / total
        when {
            usedStorage > 0.95f -> issues += Issue("Storage is almost full", 30, "Cleaner")
            usedStorage > 0.85f -> issues += Issue("Storage is getting full", 15, "Cleaner")
            usedStorage > 0.75f -> issues += Issue("Storage is ${(usedStorage * 100).toInt()}% used", 5, "Cleaner")
        }
        val mem = memoryUsed(ctx)
        when {
            mem > 0.9f -> issues += Issue("Memory is nearly full", 15, "Memory")
            mem > 0.8f -> issues += Issue("Memory is busy", 6, "Memory")
        }
        val b = battery(ctx)
        if (!b.healthy) issues += Issue("Battery: ${b.healthText.lowercase()}", 20, "Battery")
        if (b.hot) issues += Issue("Battery is hot (${b.tempC?.toInt()}°C)", 10, "Battery")
        if (b.level <= 15 && !b.charging) issues += Issue("Battery is low", 5, "Battery")
        return (100 - issues.sumOf { it.penalty }).coerceIn(0, 100) to issues.sortedByDescending { it.penalty }
    }
}
