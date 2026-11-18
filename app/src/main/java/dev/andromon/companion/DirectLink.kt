// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Direct link: a temporary, private Wi‑Fi network hosted by this phone (Android's
 * LocalOnlyHotspot) that the computer joins for one transfer. Data then goes phone ↔ computer
 * in a single hop instead of through the router twice, which is much faster on busy or 2.4 GHz
 * networks. Android picks a random network name and password; they are sent to the computer only
 * inside the already-encrypted, consented transfer. The network has no internet and is closed
 * when the transfer ends.
 *
 * Android only lets an app start it while the app is on screen, so the transfer has to be
 * accepted in the app (the notification's Accept opens the app for that reason).
 */
internal object DirectLink {

    class Network(val ssid: String, val passphrase: String, val security: String, val band: String, val addresses: List<String>)

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    @Volatile var active: Network? = null
        private set

    private const val PREFS = "wifi_receive"
    private const val KEY_ENABLED = "direct_link"

    fun enabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
    fun setEnabled(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()

    /** Permission Android needs for a local-only hotspot. */
    val permission: String get() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION

    enum class State { READY, OFF, NEEDS_PERMISSION, NEEDS_LOCATION, UNSUPPORTED }

    fun state(ctx: Context): State {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return State.UNSUPPORTED
        // Without station + hotspot concurrency the phone would drop off the router when it starts
        // (fine when it isn't on Wi‑Fi at all, e.g. on mobile data).
        if (Build.VERSION.SDK_INT >= 30 && !wm.isStaApConcurrencySupported && WifiServer.wifiLink(ctx) != null) return State.UNSUPPORTED
        if (!enabled(ctx)) return State.OFF
        if (ctx.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return State.NEEDS_PERMISSION
        if (Build.VERSION.SDK_INT < 33 && !locationOn(ctx)) return State.NEEDS_LOCATION
        return State.READY
    }

    private fun locationOn(ctx: Context): Boolean {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    /** Starts the hotspot and returns its details. Blocking: call off the main thread. */
    fun start(ctx: Context, timeoutMs: Long = 15_000): Network {
        active?.let { return it }
        when (state(ctx)) {
            State.READY -> {}
            State.UNSUPPORTED -> throw IOException("This phone can't host a Direct link while connected to Wi‑Fi")
            State.OFF -> throw IOException("Direct link is turned off on the phone")
            State.NEEDS_PERMISSION -> throw IOException("Direct link needs the Nearby devices permission on the phone")
            State.NEEDS_LOCATION -> throw IOException("Direct link needs Location turned on on the phone (Android 12 and older)")
        }
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val before = ipv4Addresses().map { it.second }.toSet()
        val started = CompletableFuture<WifiManager.LocalOnlyHotspotReservation>()
        main.post {
            try {
                wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                        if (!started.complete(r)) r.close() // too late: we gave up already
                    }
                    override fun onStopped() { reservation = null; active = null }
                    override fun onFailed(reason: Int) { started.completeExceptionally(IOException(failure(reason))) }
                }, main)
            } catch (e: Exception) {
                started.completeExceptionally(IOException(e.message ?: "Android refused to start the hotspot"))
            }
        }
        val r = try {
            started.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? IOException) ?: IOException(e.cause?.message ?: "Couldn't start the hotspot")
        } catch (e: java.util.concurrent.TimeoutException) {
            started.cancel(false)
            throw IOException("The phone took too long to start the hotspot")
        }
        reservation = r

        val net = describe(r)
        // The hotspot's own address appears a moment after it starts.
        var addrs = emptyList<String>()
        val until = System.currentTimeMillis() + 6000
        while (addrs.isEmpty() && System.currentTimeMillis() < until) {
            addrs = ipv4Addresses().filter { (name, a) -> a !in before && !name.startsWith("rmnet") }.map { it.second }
            if (addrs.isEmpty()) Thread.sleep(250)
        }
        if (addrs.isEmpty()) addrs = ipv4Addresses().filter { (name, _) -> Regex("^(swlan|wlan[1-9]|ap|softap)").containsMatchIn(name) }.map { it.second }
        return Network(net.ssid, net.passphrase, net.security, net.band, addrs).also { active = it }
    }

    fun stop() {
        active = null
        main.post {
            runCatching { reservation?.close() }
            reservation = null
        }
    }

    @Suppress("DEPRECATION")
    private fun describe(r: WifiManager.LocalOnlyHotspotReservation): Network {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = r.softApConfiguration
            val ssid = (if (Build.VERSION.SDK_INT >= 33) c.wifiSsid?.toString() else c.ssid).orEmpty().removeSurrounding("\"")
            val security = when (c.securityType) {
                SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> "wpa2"
                SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION -> "wpa3-transition"
                SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> "wpa3"
                else -> "wpa2"
            }
            // Bands the hotspot may use (Android picks the actual one). Only readable on Android 12+.
            val bands = buildList {
                if (Build.VERSION.SDK_INT >= 31) {
                    val ch = c.channels
                    var mask = 0
                    for (i in 0 until ch.size()) mask = mask or ch.keyAt(i)
                    if (mask and SoftApConfiguration.BAND_2GHZ != 0) add("2.4")
                    if (mask and SoftApConfiguration.BAND_5GHZ != 0) add("5")
                    if (mask and SoftApConfiguration.BAND_6GHZ != 0) add("6")
                }
            }
            return Network(ssid, c.passphrase.orEmpty(), security, bands.joinToString("/").ifEmpty { "?" } + " GHz", emptyList())
        }
        val w = r.wifiConfiguration ?: throw IOException("The hotspot didn't report its settings")
        return Network(w.SSID.orEmpty().removeSurrounding("\""), w.preSharedKey.orEmpty().removeSurrounding("\""), "wpa2", "2.4 GHz", emptyList())
    }

    private fun failure(reason: Int) = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "No free Wi‑Fi channel for the hotspot"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
            "Android didn't allow the hotspot. Keep Andromon Companion open, and turn off the phone's regular Mobile hotspot"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "Hotspots are blocked on this phone (work profile or carrier policy)"
        else -> "The phone couldn't start the hotspot (error $reason)"
    }

    private fun ipv4Addresses(): List<Pair<String, String>> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ni.name to it.hostAddress!! } }
    } catch (_: Exception) {
        emptyList()
    }
}
