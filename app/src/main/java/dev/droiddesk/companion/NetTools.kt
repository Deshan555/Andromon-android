// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

package dev.droiddesk.companion

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import kotlin.math.min

/** Network information and tests. Everything here blocks, except [info]; call it off the main thread. */
internal object NetTools {

    const val SPEED_HOST = "speed.cloudflare.com"
    private const val DNS_TIMEOUT_MS = 8000L
    private const val PING_TIMEOUT_S = 20L

    /** DNS lookup with a time limit; the system resolver can otherwise hang for minutes. */
    fun resolve(host: String): Array<InetAddress> {
        val task = FutureTask { InetAddress.getAllByName(host) }
        thread(isDaemon = true, name = "dns") { task.run() }
        return try {
            task.get(DNS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            throw IOException("DNS didn't answer within ${DNS_TIMEOUT_MS / 1000} s")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    class Info(
        val connected: Boolean,
        val kind: String,
        val icon: Int,
        val detail: String,
        val validated: Boolean,
        val metered: Boolean,
        val rows: List<Pair<String, String>>,
    )

    /** What the phone is connected to right now. Needs only ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE. */
    @Suppress("DEPRECATION")
    fun info(ctx: Context): Info {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        if (net == null || caps == null) {
            return Info(false, "Offline", R.drawable.ic_wifi_off, "No Wi‑Fi or mobile data connection", false, false, emptyList())
        }
        val lp = cm.getLinkProperties(net)
        val rows = mutableListOf<Pair<String, String>>()
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val cell = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val kind = when {
            wifi -> "Wi‑Fi"
            cell -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Connected"
        }
        var detail = ""
        if (wifi) {
            val wi = (if (Build.VERSION.SDK_INT >= 29) caps.transportInfo as? WifiInfo else null)
                ?: ctx.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
            if (wi != null) {
                val band = when (wi.frequency) {
                    in 2400..2500 -> "2.4 GHz"
                    in 4900..5900 -> "5 GHz"
                    in 5925..7125 -> "6 GHz"
                    else -> null
                }
                detail = listOfNotNull(band, wi.linkSpeed.takeIf { it > 0 }?.let { "$it Mbps link" }).joinToString(" · ")
                if (wi.rssi > -127) rows += "Signal" to "${signalWord(wi.rssi)} (${wi.rssi} dBm)"
            }
        } else if (cell) {
            val tm = ctx.getSystemService(TelephonyManager::class.java)
            tm?.networkOperatorName?.takeIf { it.isNotBlank() }?.let { detail = it }
            if (Build.VERSION.SDK_INT >= 28) {
                runCatching { tm?.signalStrength?.level }.getOrNull()?.let {
                    rows += "Signal" to listOf("None", "Poor", "Fair", "Good", "Great").getOrElse(it) { "–" }
                }
            }
        }
        val down = caps.linkDownstreamBandwidthKbps
        if (detail.isEmpty() && down > 0) detail = "About ${down / 1000} Mbps"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && kind != "VPN") rows += "VPN" to "On"

        lp?.let { p ->
            val addrs = p.linkAddresses.map { it.address }.filter { !it.isLinkLocalAddress && !it.isLoopbackAddress }
            val v4 = addrs.filterIsInstance<Inet4Address>().map { it.hostAddress }
            val v6 = addrs.filterIsInstance<Inet6Address>().map { it.hostAddress?.substringBefore('%') }
            if (v4.isNotEmpty()) rows += "IP address" to v4.joinToString("\n")
            if (v6.isNotEmpty()) rows += "IPv6" to v6.first().orEmpty() + if (v6.size > 1) "\n+${v6.size - 1} more" else ""
            p.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
                ?.let { rows += "Gateway" to it }
            val dns = p.dnsServers.mapNotNull { it.hostAddress?.substringBefore('%') }
            if (dns.isNotEmpty()) rows += "DNS" to dns.take(3).joinToString("\n")
            if (Build.VERSION.SDK_INT >= 28) p.privateDnsServerName?.let { rows += "Private DNS" to it }
            p.interfaceName?.let { rows += "Interface" to it }
        }
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        rows += "Metered" to if (metered) "Yes" else "No"

        val icon = if (wifi) R.drawable.ic_wifi else if (cell) R.drawable.ic_cell else R.drawable.ic_globe
        return Info(true, kind, icon, detail, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), metered, rows)
    }

    private fun signalWord(rssi: Int) = when {
        rssi >= -55 -> "Excellent"
        rssi >= -67 -> "Good"
        rssi >= -75 -> "Fair"
        else -> "Weak"
    }

    /** Hostnames and IP literals only; no spaces and no leading '-' (it would read as a ping flag). */
    fun validHost(host: String) = host.length in 1..253 && Regex("^[A-Za-z0-9.:\\[\\]_-]+$").matches(host) && !host.startsWith("-")

    /**
     * Pings [host] 4 times with the system ping tool, reporting each line in plain words.
     * Falls back to timing TCP connections if ICMP isn't available.
     */
    fun ping(host: String, out: (String) -> Unit) {
        val addr = try { resolve(host).first() } catch (e: Exception) {
            out("Couldn't find $host: ${e.message ?: "unknown host"}"); return
        }
        val tool = if (addr is Inet6Address) "/system/bin/ping6" else "/system/bin/ping"
        val ip = addr.hostAddress?.substringBefore('%') ?: return
        val proc = try {
            ProcessBuilder(tool, "-c", "4", "-W", "3", ip).redirectErrorStream(true).start()
        } catch (_: IOException) {
            null
        }
        if (proc != null) {
            // Watchdog: never let a stuck ping keep the screen busy.
            var timedOut = false
            thread(isDaemon = true) { if (!proc.waitFor(PING_TIMEOUT_S, TimeUnit.SECONDS)) { timedOut = true; proc.destroy() } }
            var replies = 0
            out("Pinging $host ($ip)")
            proc.inputStream.bufferedReader().forEachLine { line ->
                if (!line.startsWith("PING ")) prettyPing(line)?.let { out(it) }
                if ("time=" in line) replies++
            }
            val code = proc.waitFor()
            if (timedOut) { out("Stopped after $PING_TIMEOUT_S s without finishing."); return }
            if (code == 0 || replies > 0) return
            if (code == 1) { out("No replies. The host may block ping."); return }
        }
        out("Ping isn't available here, timing connections instead…")
        tcpPing(addr, out)
    }

    private fun prettyPing(line: String): String? {
        Regex("^PING (\\S+) \\(([^)]+)\\)").find(line)?.let { return "Pinging ${it.groupValues[1]} (${it.groupValues[2]})" }
        Regex("icmp_seq=(\\d+).*time=([\\d.]+) ms").find(line)?.let { return "Reply ${it.groupValues[1]}:  ${it.groupValues[2]} ms" }
        Regex("(\\d+) packets transmitted, (\\d+) received.*?([\\d.]+)% packet loss").find(line)?.let {
            return "${it.groupValues[2]} of ${it.groupValues[1]} replies (${it.groupValues[3]}% lost)"
        }
        Regex("= ([\\d.]+)/([\\d.]+)/([\\d.]+)").find(line)?.let {
            return "Average ${it.groupValues[2]} ms (fastest ${it.groupValues[1]}, slowest ${it.groupValues[3]})"
        }
        return when {
            line.isBlank() || line.startsWith("---") -> null
            "unknown host" in line.lowercase() -> "Unknown host: that name doesn't resolve."
            else -> line.trim()
        }
    }

    private fun tcpPing(addr: InetAddress, out: (String) -> Unit) {
        val times = mutableListOf<Long>()
        for (i in 1..4) {
            val ms = connectMs(addr, 443) ?: connectMs(addr, 80)
            out(if (ms != null) "Connection $i:  $ms ms" else "Connection $i:  no answer")
            ms?.let { times += it }
        }
        if (times.isNotEmpty()) out("Average ${times.average().toLong()} ms")
    }

    private fun connectMs(addr: InetAddress, port: Int): Long? = try {
        Socket().use { s ->
            val t = System.nanoTime()
            s.connect(InetSocketAddress(addr, port), 3000)
            (System.nanoTime() - t) / 1_000_000
        }
    } catch (_: IOException) { null }

    /** DNS lookup: every address the name resolves to, and how long it took. */
    fun lookup(host: String): List<String> {
        val t = System.nanoTime()
        val addrs = resolve(host)
        val ms = (System.nanoTime() - t) / 1_000_000
        return addrs.map { a ->
            val type = if (a is Inet6Address) "IPv6" else "IPv4"
            "$type   ${a.hostAddress?.substringBefore('%')}"
        } + "Answered in $ms ms"
    }

    /** Round-trip time to the speed-test server: median of 5 TCP connects. */
    fun latency(): Long? {
        val addr = resolve(SPEED_HOST).first()
        val times = (1..5).mapNotNull { connectMs(addr, 443) }.sorted()
        return times.getOrNull(times.size / 2)
    }

    /** Downloads up to [maxBytes] for at most [maxMs]; reports live Mbps samples; returns average Mbps. */
    fun download(maxBytes: Long, maxMs: Long, sample: (Float) -> Unit): Float {
        resolve(SPEED_HOST) // fail fast if DNS is down; the connection then reuses the cached answer
        val c = URL("https://$SPEED_HOST/__down?bytes=$maxBytes").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("Accept-Encoding", "identity")
        try {
            val input = c.inputStream
            val buf = ByteArray(64 * 1024)
            val start = System.nanoTime()
            var total = 0L; var lastT = start; var lastB = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                val now = System.nanoTime()
                if (now - lastT >= 250_000_000) {
                    sample(mbps(total - lastB, now - lastT)); lastT = now; lastB = total
                }
                if (now - start > maxMs * 1_000_000) break
            }
            return mbps(total, System.nanoTime() - start)
        } finally {
            c.disconnect()
        }
    }

    /** Uploads up to [maxBytes] for at most [maxMs]; reports live Mbps samples; returns average Mbps. */
    fun upload(maxBytes: Long, maxMs: Long, sample: (Float) -> Unit): Float {
        val c = URL("https://$SPEED_HOST/__up").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setFixedLengthStreamingMode(maxBytes)
        val buf = ByteArray(64 * 1024)
        var sent = 0L
        var start = 0L
        var end = 0L
        try {
            val output = c.outputStream
            start = System.nanoTime()
            var lastT = start; var lastB = 0L
            while (sent < maxBytes) {
                val n = min(buf.size.toLong(), maxBytes - sent).toInt()
                output.write(buf, 0, n)
                sent += n
                val now = System.nanoTime()
                if (now - lastT >= 250_000_000) {
                    sample(mbps(sent - lastB, now - lastT)); lastT = now; lastB = sent
                }
                if (now - start > maxMs * 1_000_000) break
            }
            if (sent >= maxBytes) {
                output.close()
                c.responseCode // wait until the server has everything
            }
            end = System.nanoTime()
        } catch (e: IOException) {
            if (sent == 0L) throw e
            end = System.nanoTime() // stopped early on the time limit: count what was sent
        } finally {
            c.disconnect()
        }
        return mbps(sent, end - start)
    }

    private fun mbps(bytes: Long, nanos: Long): Float =
        if (nanos <= 0) 0f else (bytes * 8 / (nanos / 1e9) / 1e6).toFloat()
}
