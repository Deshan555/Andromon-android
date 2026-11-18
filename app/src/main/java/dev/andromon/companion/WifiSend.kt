// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * Wi‑Fi send: the sending half of the open file-sharing protocol (v2) that [WifiServer] receives
 * with, so this phone can send files to a computer running Andromon's Wi‑Fi transfer, or to
 * another app that speaks the same protocol.
 *
 *  - Discovery: announces this phone over UDP multicast 224.0.0.167:53317 and lists the devices
 *    that answer (by multicast, or by calling `register` on [WifiServer] while receive is on).
 *    Where multicast is filtered it asks each address of the local /24 directly (`register`),
 *    and an address can be typed by hand.
 *  - Transfer: `prepare-upload` lists the files, the other device accepts (or asks for a PIN),
 *    then each file is streamed to `upload` with the token it was given.
 *
 * Security: sending opens outbound connections only; nothing listens. Receivers use self-signed
 * certificates, so instead of a certificate authority the certificate's SHA-256 is checked against
 * the fingerprint the device announced (or, for a typed address, the one seen when it was added).
 * File names are sent as plain names without paths, and the receiver's reply is validated before
 * anything is uploaded.
 *
 * State is shared by [SendService] (which keeps a transfer alive in the background) and
 * [SendPanel]. Like [WifiReceive], the public fields are read and written on the main thread only.
 */
internal object WifiSend {

    const val MAX_ITEMS = 500
    /** Shared text longer than this is cut (it travels inside the JSON request as well). */
    const val MAX_TEXT = 200_000
    private const val MAX_HELD = 300
    private const val PORT = WifiServer.PORT
    private const val GROUP = "224.0.0.167"
    private const val API = "/api/localsend/v2" // URL paths are fixed by the wire protocol
    private const val PROTOCOL_VERSION = "2.1"
    private const val PARALLEL = 2
    /** Checksums cost a second read of the file, so only small files get one. */
    private const val HASH_MAX_FILE = 32L * 1024 * 1024
    private const val HASH_BUDGET = 128L * 1024 * 1024
    private const val REPLY_LIMIT = 1024 * 1024
    private const val TAG = "WifiSend"
    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private const val JSON = "application/json"

    enum class ItemState { QUEUED, SENDING, DONE, FAILED, SKIPPED }

    /** One thing to send: a file behind a content URI, or shared text ([text] set, sent as a .txt). */
    class Item(val name: String, size: Long, val mime: String, val uri: Uri?, val text: ByteArray?,
               afd: AssetFileDescriptor?) {
        val id: String = UUID.randomUUID().toString()
        /** -1 until measured (some apps don't say how big a shared file is). */
        @Volatile var size = size
        @Volatile var sent = 0L
        @Volatile var state = ItemState.QUEUED
        @Volatile var error: String? = null
        @Volatile var sha256: String? = null
        // The open file is kept while the item is queued, so it stays readable even if Android
        // takes the sharing app's read grant back (e.g. when the app is closed mid-transfer).
        private var held = afd
        private var heldUsed = false

        @Synchronized
        fun open(ctx: Context): InputStream {
            text?.let { return ByteArrayInputStream(it) }
            val h = held
            if (h != null) {
                val fis = FileInputStream(h.fileDescriptor)
                val rewound = runCatching { fis.channel.position(h.startOffset) }.isSuccess
                if (rewound || !heldUsed) { // a pipe can't be rewound: it is good for one read
                    heldUsed = true
                    return HeldStream(fis, h.declaredLength)
                }
            }
            return ctx.contentResolver.openInputStream(uri ?: throw IOException("No file"))
                ?: throw IOException("The file can't be opened")
        }

        @Synchronized
        fun close() {
            runCatching { held?.close() }
            held = null
        }
    }

    /** Reads a kept-open file without closing it, stopping at its declared length if it has one. */
    private class HeldStream(private val src: FileInputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left == 0L) return -1
            val n = src.read(b, off, if (left < 0) len else minOf(len.toLong(), left).toInt())
            if (n > 0 && left > 0) left -= n
            return n
        }
        override fun close() {} // the descriptor belongs to the Item
    }

    /**
     * A device that can receive. [fingerprint] is the SHA-256 of its certificate (what it
     * announced, or what was seen when asking it directly); with [https] off it is only a name.
     */
    class Device(val alias: String, val model: String, val type: String, val ip: String, val port: Int,
                 val https: Boolean, val fingerprint: String, val manual: Boolean, val seen: Long) {
        val key: String get() = fingerprint
        /** True when uploads will be checked against this device's certificate. */
        val pinned: Boolean get() = https && HEX64.matches(fingerprint)
    }

    enum class Stage { PREPARING, WAITING, PIN, SENDING }

    class Progress(val to: String, val stage: Stage, val sent: Long, val total: Long, val filesDone: Int,
                   val fileCount: Int, val bytesPerSec: Long, val pinWrong: Boolean)

    /** A transfer that ended; [seq] increases each time. [problem] is null when nothing went wrong. */
    class Done(val seq: Int, val to: String, val sent: Int, val count: Int, val bytes: Long,
               val problem: String?, val cancelled: Boolean, val failed: List<Pair<String, String>>)

    val queue = ArrayList<Item>()
    var devices: List<Device> = emptyList()
    var selected: String? = null
    var scanning = false
    var lastScan = 0L
    /** Asking a typed address who it is. */
    var probing = false
    /** Reading the names and sizes of newly added files. */
    var adding = false
    var progress: Progress? = null
    var done: Done? = null
    var message: String? = null
    /** Set by the share target and notifications: the transfer screen should show its Send side. */
    var openRequested = false
    val active: Boolean get() = job != null

    private var job: Job? = null
    @Volatile private var ownFingerprint: String? = null
    private val recentPeers = ConcurrentHashMap<String, Long>()

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()

    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }

    /** Applies [change] on the main thread, then tells the listeners. */
    fun post(change: WifiSend.() -> Unit) {
        main.post { change(); listeners.toList().forEach { it() } }
    }

    private fun changed() = listeners.toList().forEach { it() }

    fun takeOpenRequest(): Boolean = openRequested.also { openRequested = false }

    fun selectedDevice(): Device? = devices.firstOrNull { it.key == selected }

    // ---------- the queue ----------

    /**
     * Adds shared or picked files. Their names and sizes are read on a background thread;
     * [after] then runs on the main thread. Only `content://` items are taken: a `file://` link
     * from another app could point at this app's own private files.
     */
    fun addUris(ctx: Context, uris: List<Uri>, after: () -> Unit = {}) {
        val app = ctx.applicationContext
        adding = true
        changed()
        val known = queue.mapNotNull { it.uri }.toHashSet()
        val room = MAX_ITEMS - queue.size
        var heldNow = queue.count { it.uri != null }
        thread(name = "wifi-send-add", isDaemon = true) {
            val items = ArrayList<Item>()
            var unreadable = 0
            var skipped = 0
            for (uri in uris.distinct()) {
                if (uri in known) continue
                if (items.size >= room) { skipped++; continue }
                val item = if (uri.scheme == "content") runCatching { resolve(app, uri, heldNow < MAX_HELD) }.getOrNull() else null
                if (item == null) { unreadable++; continue }
                heldNow++
                items += item
            }
            post {
                adding = false
                if (job != null) {
                    items.forEach { it.close() }
                    message = "Wait for the running transfer to finish, then add more."
                } else {
                    queue += items
                    message = listOfNotNull(
                        if (unreadable > 0) "$unreadable ${if (unreadable == 1) "item" else "items"} couldn't be read and ${if (unreadable == 1) "was" else "were"} left out." else null,
                        if (skipped > 0) "Only $MAX_ITEMS items fit in one transfer; $skipped were left out." else null,
                    ).joinToString(" ").ifEmpty { null }
                    if (items.isNotEmpty()) done = null
                }
                after()
            }
        }
    }

    /** Shared text or a link: sent as a small text file, the way the protocol carries messages. */
    fun addText(text: String) {
        val clean = text.trim().take(MAX_TEXT)
        if (clean.isEmpty()) return
        if (job != null) { message = "Wait for the running transfer to finish, then add more."; changed(); return }
        if (queue.size >= MAX_ITEMS) { message = "Only $MAX_ITEMS items fit in one transfer."; changed(); return }
        val bytes = clean.toByteArray(Charsets.UTF_8)
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.US).format(java.util.Date())
        queue += Item("Text $stamp.txt", bytes.size.toLong(), "text/plain", null, bytes, null)
        done = null
        changed()
    }

    fun remove(item: Item) {
        if (job != null) return
        if (queue.remove(item)) item.close()
        changed()
    }

    fun clearQueue() {
        if (job != null) return
        queue.forEach { it.close() }
        queue.clear()
        changed()
    }

    private fun resolve(ctx: Context, uri: Uri, hold: Boolean): Item? {
        val cr = ctx.contentResolver
        var name: String? = null
        var size = -1L
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        }
        val mime = runCatching { cr.getType(uri) }.getOrNull()?.takeIf { Regex("^[\\w.+-]+/[\\w.+*-]+$").matches(it) && !it.contains('*') }
        // Opening it now also proves the file is readable before it is offered to anyone.
        val afd = try { cr.openAssetFileDescriptor(uri, "r") } catch (_: Exception) { null } ?: return null
        val length = runCatching { afd.length }.getOrDefault(-1L) // what will really be read
        if (length >= 0) size = length
        val kept = if (hold) afd else { runCatching { afd.close() }; null }
        return Item(plainName(name ?: uri.lastPathSegment, mime), size, mime ?: "application/octet-stream", uri, null, kept)
    }

    /** A bare file name: no folders, no control characters; an extension is added when the type is known. */
    private fun plainName(raw: String?, mime: String?): String {
        var n = (raw ?: "").replace('\\', '/').substringAfterLast('/')
            .replace(Regex("[\\u0000-\\u001f\\u007f]"), "").trim().take(200)
        if (n.isEmpty() || n == "." || n == "..") n = "file"
        if (!n.contains('.') && mime != null) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { n = "$n.$it" }
        }
        return n
    }

    // ---------- devices ----------

    /**
     * Records a device from its own description [m] (an announcement, a `register` call or a
     * `register` reply) received from [ip]. Safe to call from any thread.
     */
    fun onPeer(m: JSONObject, ip: String?, certFingerprint: String? = null, manual: Boolean = false,
               port: Int? = null, https: Boolean? = null): Device? {
        val addr = ip?.removePrefix("::ffff:")?.takeIf { validIp(it) } ?: return null
        val secure = https ?: (m.optString("protocol") != "http")
        val fp = (certFingerprint ?: m.optString("fingerprint")).trim().take(128)
        if (fp.isEmpty() || fp.equals(ownFingerprint, ignoreCase = true)) return null
        val p = port ?: m.optInt("port", PORT)
        if (p !in 1..65535) return null
        val now = System.currentTimeMillis()
        val d = Device(
            m.optString("alias").trim().take(80).ifBlank { "Unknown device" },
            if (m.isNull("deviceModel")) "" else m.optString("deviceModel").trim().take(80),
            if (m.isNull("deviceType")) "" else m.optString("deviceType").trim().take(20),
            addr, p, secure, fp, manual, now)
        // Devices repeat their announcements: don't redraw for every copy.
        val stamp = "$fp|$addr|$p|$secure|${d.alias}"
        val last = recentPeers[stamp]
        if (!manual && last != null && now - last < 5_000) return d
        if (recentPeers.size > 2000) recentPeers.clear()
        recentPeers[stamp] = now
        post {
            val wasManual = devices.any { it.key == d.key && it.manual }
            val entry = if (wasManual && !manual) Device(d.alias, d.model, d.type, d.ip, d.port, d.https, d.fingerprint, true, d.seen) else d
            devices = (devices.filterNot { it.key == d.key || (it.ip == d.ip && it.port == d.port) } + entry)
                .sortedBy { it.alias.lowercase() }.takeLast(200)
            if (manual || selectedDevice() == null && devices.size == 1) selected = d.key
        }
        return d
    }

    private fun validIp(s: String) = IPV4.matches(s) && s.split('.').all { it.toInt() <= 255 }

    fun select(key: String) {
        if (job != null) return
        selected = key
        changed()
    }

    /**
     * Looks for receivers. Multicast first; the direct sweep of the local /24 runs when multicast
     * found nothing (some networks filter it) or when [sweep] asks for it (the Refresh button).
     */
    fun scan(ctx: Context, sweep: Boolean) {
        if (scanning) return
        scanning = true
        lastScan = System.currentTimeMillis()
        val cutoff = lastScan - 90_000
        devices = devices.filter { it.manual || it.seen > cutoff || it.key == selected }
        changed()
        val app = ctx.applicationContext
        thread(name = "wifi-send-scan", isDaemon = true) {
            val note = try { discover(app, sweep) } catch (e: Exception) { Log.w(TAG, "Scan failed", e); null }
            post { scanning = false; if (note != null && job == null) message = note }
        }
    }

    /** Adds the device at a typed address ("192.168.1.20" or "192.168.1.20:53317") and selects it. */
    fun addAddress(ctx: Context, typed: String) {
        val m = Regex("^(\\d{1,3}(?:\\.\\d{1,3}){3})(?::(\\d{1,5}))?$").matchEntire(typed.trim())
        val ip = m?.groupValues?.get(1)?.takeIf { validIp(it) }
        val port = m?.groupValues?.get(2)?.ifEmpty { null }?.toIntOrNull() ?: PORT
        if (ip == null || port !in 1..65535) {
            message = "Type the other device's address as numbers, for example 192.168.1.20"
            changed()
            return
        }
        if (probing) return
        probing = true
        message = null
        changed()
        val app = ctx.applicationContext
        thread(name = "wifi-send-probe", isDaemon = true) {
            val found = try {
                val id = identity(app)
                val binder = NetBinder(app)
                val body = info(app, id).toString().toByteArray(Charsets.UTF_8)
                // Encrypted first; a device with encryption turned off only answers plain HTTP.
                runCatching { probe(Tls(id, null), binder, ip, port, body, 3000, 5000, true) }.getOrNull()
                    ?: runCatching { probe(null, binder, ip, port, body, 3000, 5000, true) }.getOrNull()
            } catch (e: Exception) { null }
            post {
                probing = false
                if (found == null) message = "No device answered at $ip${if (port != PORT) ":$port" else ""}. " +
                    "Check the address, that both devices are on the same Wi‑Fi, and that the other one is ready to receive."
            }
        }
    }

    private fun identity(ctx: Context): TlsIdentity.Identity =
        TlsIdentity.get(ctx).also { ownFingerprint = it.fingerprint }

    /** This phone's details, the same ones [WifiServer] gives, so both sides show one device. */
    private fun info(ctx: Context, id: TlsIdentity.Identity): JSONObject = JSONObject()
        .put("alias", WifiServer.deviceName(ctx)).put("version", PROTOCOL_VERSION)
        .put("deviceModel", Build.MANUFACTURER.replaceFirstChar { it.uppercase() }.take(40))
        .put("deviceType", "mobile").put("fingerprint", id.fingerprint)
        .put("port", if (WifiReceive.running && WifiReceive.port > 0) WifiReceive.port else PORT)
        .put("protocol", "https").put("download", false)

    private fun discover(ctx: Context, forceSweep: Boolean): String? {
        val id = identity(ctx)
        val nis = WifiServer.lanInterfaces()
        if (nis.isEmpty()) return "Connect to the same Wi‑Fi as the other device, then tap Refresh."
        val wm = ctx.getSystemService(WifiManager::class.java)
        val lock = runCatching { wm?.createMulticastLock("andromon-send")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        // The shared port, so answers sent to the group are heard (also next to WifiServer).
        val bound: MulticastSocket? = try {
            MulticastSocket(null as SocketAddress?).apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
        } catch (_: Exception) { null }
        // If another app owns the port, announce only: answers then come through the sweep.
        val sock: MulticastSocket? = bound ?: runCatching { MulticastSocket() }.getOrNull()
        var sweeper: Thread? = null
        try {
            val group = InetSocketAddress(InetAddress.getByName(GROUP), 0)
            val joined: List<NetworkInterface> =
                if (bound == null) emptyList() else nis.filter { ni -> runCatching { bound.joinGroup(group, ni) }.isSuccess }
            var listening = joined.isNotEmpty()
            runCatching { sock?.timeToLive = 4; sock?.soTimeout = 250 }
            val announce = info(ctx, id).put("announce", true).toString().toByteArray(Charsets.UTF_8)
            val target = InetSocketAddress(GROUP, PORT)
            val sendAt = longArrayOf(0, 600, 1800)
            var sends = 0
            var found = 0
            val start = System.currentTimeMillis()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val t = System.currentTimeMillis() - start
                if (t > 20_000 || (t > 4500 && sweeper?.isAlive != true)) break
                if (sock != null && sends < sendAt.size && t >= sendAt[sends]) {
                    sends++
                    for (ni in if (joined.isEmpty()) nis else joined) runCatching {
                        sock.networkInterface = ni
                        sock.send(DatagramPacket(announce, announce.size, target))
                    }
                }
                if (sweeper == null && ((forceSweep && t >= 300) || (t >= 1500 && found == 0))) {
                    sweeper = thread(name = "wifi-send-sweep", isDaemon = true) { runCatching { sweep(ctx, id, nis) } }
                }
                if (sock == null || !listening) { Thread.sleep(200); continue }
                val p = DatagramPacket(buf, buf.size)
                try { sock.receive(p) } catch (_: SocketTimeoutException) { continue } catch (_: IOException) { listening = false; continue }
                val msg = runCatching { JSONObject(String(p.data, p.offset, p.length, Charsets.UTF_8)) }.getOrNull() ?: continue
                if (onPeer(msg, p.address?.hostAddress) != null) found++
            }
        } finally {
            runCatching { sock?.close() }
            runCatching { lock?.takeIf { it.isHeld }?.release() }
        }
        return null
    }

    /** Asks every address of the phone's own /24 networks whether it receives (the protocol's fallback). */
    private fun sweep(ctx: Context, id: TlsIdentity.Identity, nis: List<NetworkInterface>) {
        val own = HashSet<String>()
        val targets = LinkedHashSet<String>()
        for (ni in nis) for (a in ni.inetAddresses.toList()) {
            if (a !is Inet4Address) continue
            a.hostAddress?.let { own += it }
            if (!a.isSiteLocalAddress && !a.isLinkLocalAddress) continue // private home/office ranges only
            val b = a.address
            val prefix = "${b[0].toInt() and 0xff}.${b[1].toInt() and 0xff}.${b[2].toInt() and 0xff}."
            for (i in 1..254) targets += prefix + i
        }
        targets -= own
        if (targets.isEmpty()) return
        val tls = Tls(id, null)
        val binder = NetBinder(ctx)
        val body = info(ctx, id).toString().toByteArray(Charsets.UTF_8)
        val pool = Executors.newFixedThreadPool(48)
        for (ip in targets.take(254 * 3)) pool.execute { runCatching { probe(tls, binder, ip, PORT, body, 700, 2500, false) } }
        pool.shutdown()
        if (!pool.awaitTermination(12, TimeUnit.SECONDS)) pool.shutdownNow()
    }

    /** `register` with one address. With [tls] the device's certificate fingerprint is what gets remembered. */
    private fun probe(tls: Tls?, binder: NetBinder, ip: String, port: Int, body: ByteArray,
                      connectMs: Int, readMs: Int, manual: Boolean): Device? {
        Conn.open(ip, port, tls, binder, connectMs).use { c ->
            val r = c.request("POST", "$API/register", JSON, body.size.toLong(), readMs) { it.write(body) }
            if (r.status != 200) return null
            val m = JSONObject(r.body)
            return onPeer(m, ip, certFingerprint = c.fingerprint, manual = manual, port = port, https = tls != null)
        }
    }

    // ---------- sending ----------

    /** Starts sending the queue to the selected device. Main thread; called by [SendService]. */
    fun begin(ctx: Context): Boolean {
        if (job != null) return true
        val dev = selectedDevice()
        if (dev == null || queue.isEmpty()) {
            message = if (queue.isEmpty()) "Add something to send first." else "Pick a device to send to."
            changed()
            return false
        }
        done = null
        message = null
        val j = Job(ctx.applicationContext, dev, queue.toList())
        job = j
        progress = Progress(dev.alias, Stage.PREPARING, 0, 0, 0, queue.size, 0, false)
        changed()
        thread(name = "wifi-send", isDaemon = true) { j.run() }
        return true
    }

    fun cancel() { job?.cancel() }

    fun submitPin(pin: String) { job?.pin(pin) }

    fun dismissDone() { done = null; changed() }

    private class Stop(message: String) : Exception(message)
    private class LocalRead(message: String) : RuntimeException(message)
    private class PinMismatch : IOException("certificate mismatch")

    private class Job(val ctx: Context, val dev: Device, val items: List<Item>) {
        @Volatile var cancelled = false
        @Volatile private var finished = false
        @Volatile private var sessionId: String? = null
        @Volatile private var stage = Stage.PREPARING
        @Volatile private var pinWrong = false
        @Volatile private var total = 0L
        @Volatile private var fileCount = items.size
        @Volatile private var lastByteAt = 0L
        @Volatile private var stalled = false
        private val sent = AtomicLong()
        private val filesDone = AtomicInteger()
        private val conns: MutableSet<Conn> = ConcurrentHashMap.newKeySet()
        private val pinWait = AtomicReference<CompletableFuture<String?>?>()
        private var tls: Tls? = null
        private lateinit var binder: NetBinder
        private var wifiLock: WifiManager.WifiLock? = null
        private var wakeLock: PowerManager.WakeLock? = null
        private val emitLock = Any()
        private var lastEmit = 0L
        private var speedAt = System.currentTimeMillis()
        private var speedBytes = 0L
        private var speed = 0L

        fun cancel() {
            cancelled = true
            pinWait.get()?.complete(null)
            closeAll()
        }

        fun pin(code: String) { pinWait.get()?.complete(code) }

        private fun closeAll() = conns.toList().forEach { runCatching { it.close() } }
        private fun check() { if (cancelled) throw Stop("Cancelled") }

        fun run() {
            acquireLocks()
            var problem: String? = null
            try {
                problem = transfer()
            } catch (e: Stop) {
                problem = e.message
            } catch (e: Exception) {
                Log.w(TAG, "Transfer failed", e)
                problem = describe(e)
            } finally {
                finished = true
                closeAll()
                releaseLocks()
            }
            val wasCancelled = cancelled
            if (wasCancelled) problem = "You cancelled the transfer."
            for (it in items) if (it.state == ItemState.SENDING) { it.state = ItemState.QUEUED; it.sent = 0 }
            val failed = items.filter { it.state == ItemState.FAILED }
            // Free the receiver: it keeps the session open until every file arrived or it is told to stop.
            if (wasCancelled || (sessionId != null && (problem != null || failed.isNotEmpty()))) tellCancel()
            val ok = items.filter { it.state == ItemState.DONE }
            val summary = Done(0, dev.alias, ok.size, items.size, ok.sumOf { it.size.coerceAtLeast(0) }, problem, wasCancelled,
                failed.map { it.name to (it.error ?: "Not sent") })
            WifiSend.post {
                if (job !== this@Job) return@post
                job = null
                progress = null
                done = Done((done?.seq ?: 0) + 1, summary.to, summary.sent, summary.count, summary.bytes,
                    summary.problem, summary.cancelled, summary.failed)
                // Sent items leave the queue; the rest stay so they can be tried again.
                for (it in ok) { it.close(); queue.remove(it) }
            }
        }

        private fun describe(e: Exception): String = when {
            stalled -> "${dev.alias} stopped responding."
            e is PinMismatch -> "Security check failed: ${dev.alias}'s certificate doesn't match the one it announced. Nothing more was sent."
            e is LocalRead -> e.message ?: "A file couldn't be read."
            e is ConnectException || e is SocketTimeoutException || e is NoRouteToHostException ->
                "Couldn't reach ${dev.alias} at ${dev.ip}. Check that both devices are on the same Wi‑Fi and that it is ready to receive."
            e is SSLException -> "Couldn't set up an encrypted connection to ${dev.alias}."
            e is IOException -> "Lost the connection to ${dev.alias}."
            else -> "Sending failed (${e.javaClass.simpleName})."
        }

        private fun connect(connectMs: Int = 8000): Conn {
            val c = Conn.open(dev.ip, dev.port, tls, binder, connectMs)
            conns += c
            if (cancelled) { drop(c); throw Stop("Cancelled") }
            return c
        }

        private fun drop(c: Conn?) {
            c ?: return
            conns -= c
            runCatching { c.close() }
        }

        /** Runs the whole transfer. Returns a note for the summary, or null when everything went. */
        private fun transfer(): String? {
            val id = identity(ctx)
            binder = NetBinder(ctx)
            // Pinned to the announced fingerprint; a plain-HTTP receiver has no certificate to check.
            tls = if (dev.https) Tls(id, dev.fingerprint.takeIf { HEX64.matches(it) }) else null

            // 1. Sizes that weren't known, and checksums for small files.
            emit(true)
            val ready = ArrayList<Item>()
            var hashed = 0L
            for (it in items) {
                check()
                it.state = ItemState.QUEUED; it.sent = 0; it.error = null
                try {
                    val wantHash = it.sha256 == null && it.size in 0..HASH_MAX_FILE && hashed + it.size <= HASH_BUDGET
                    if (it.size < 0 || wantHash) {
                        measure(it)
                        if (it.sha256 != null) hashed += it.size
                    }
                    ready += it
                } catch (e: Exception) {
                    if (cancelled) throw Stop("Cancelled")
                    it.state = ItemState.FAILED
                    it.error = "Couldn't be read. Pick it again."
                }
            }
            if (ready.isEmpty()) throw Stop("None of the files could be read. Pick them again.")

            // 2. Offer the files and wait for the other side to accept.
            fileCount = ready.size
            total = ready.sumOf { it.size }
            val files = JSONObject()
            for (it in ready) {
                files.put(it.id, JSONObject().put("id", it.id).put("fileName", it.name).put("size", it.size)
                    .put("fileType", it.mime)
                    .apply { it.sha256?.let { h -> put("sha256", h) } }
                    // Text travels as a file whose content is also given as a preview, so apps that
                    // show messages can display it without saving anything.
                    .apply { it.text?.let { t -> put("preview", String(t, Charsets.UTF_8)) } })
            }
            val body = JSONObject().put("info", info(ctx, id)).put("files", files).toString().toByteArray(Charsets.UTF_8)
            var code: String? = null
            var reply: Response
            while (true) {
                check()
                stage = Stage.WAITING
                emit(true)
                val c = connect()
                reply = try {
                    c.request("POST", "$API/prepare-upload" + (code?.let { "?pin=" + enc(it) } ?: ""), JSON,
                        body.size.toLong(), 6 * 60_000) { it.write(body) }
                } finally {
                    drop(c)
                }
                check()
                if (reply.status != 401) break
                // The receiver wants its PIN: ask, then offer the files again with it.
                val f = CompletableFuture<String?>()
                pinWait.set(f)
                pinWrong = code != null
                stage = Stage.PIN
                emit(true)
                if (cancelled) throw Stop("Cancelled")
                code = try { f.get(3, TimeUnit.MINUTES) } catch (_: TimeoutException) {
                    throw Stop("${dev.alias} asked for a PIN and none was entered.")
                }
                pinWait.set(null)
                if (code == null) throw Stop("Cancelled")
            }
            when (reply.status) {
                200 -> {}
                204 -> { // the receiver has all it needs (e.g. it showed the text as a message)
                    val textOnly = ready.all { it.text != null }
                    ready.forEach { it.state = if (textOnly) ItemState.DONE else ItemState.SKIPPED }
                    if (textOnly) filesDone.set(ready.size)
                    return if (textOnly) null else "${dev.alias} answered that it doesn't need these files."
                }
                403 -> throw Stop("${dev.alias} declined the files.")
                409 -> throw Stop("${dev.alias} is busy with another transfer. Try again in a moment.")
                429 -> throw Stop("Too many wrong PINs. Wait a little, then try again.")
                400 -> throw Stop("${dev.alias} couldn't read the request.")
                else -> throw Stop("${dev.alias} refused the transfer (HTTP ${reply.status}).")
            }

            // 3. Check the reply: a session and a token for each file the receiver wants.
            val json = runCatching { JSONObject(reply.body) }.getOrNull()
                ?: throw Stop("${dev.alias} sent a reply this app doesn't understand.")
            val sid = (json.opt("sessionId") as? String)?.takeIf { it.isNotEmpty() && it.length <= 200 }
                ?: throw Stop("${dev.alias} sent a reply this app doesn't understand.")
            sessionId = sid
            val tokens = json.optJSONObject("files") ?: JSONObject()
            val accepted = ArrayList<Pair<Item, String>>()
            for (it in ready) {
                val t = (tokens.opt(it.id) as? String)?.takeIf { s -> s.isNotEmpty() && s.length <= 2000 }
                if (t == null) it.state = ItemState.SKIPPED else accepted += it to t
            }
            if (accepted.isEmpty()) throw Stop("${dev.alias} accepted none of the files.")
            fileCount = accepted.size
            total = accepted.sumOf { it.first.size }

            // 4. Upload, a couple of files at a time, each worker on its own kept-alive connection.
            stage = Stage.SENDING
            lastByteAt = System.currentTimeMillis()
            emit(true)
            val next = AtomicInteger()
            val fatal = AtomicReference<String?>()
            val watchdog = thread(name = "wifi-send-watch", isDaemon = true) {
                while (!finished) {
                    try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
                    if (!finished && System.currentTimeMillis() - lastByteAt > 120_000) { stalled = true; closeAll() }
                }
            }
            val workers = (0 until minOf(PARALLEL, accepted.size)).map { n ->
                thread(name = "wifi-send-$n", isDaemon = true) { worker(sid, accepted, next, fatal) }
            }
            workers.forEach { it.join() }
            watchdog.interrupt()
            check()
            fatal.get()?.let { throw Stop(it) }
            emit(true)
            val skipped = ready.size - accepted.size
            return if (skipped > 0) "${dev.alias} didn't take $skipped of the files." else null
        }

        private fun worker(sid: String, accepted: List<Pair<Item, String>>, next: AtomicInteger, fatal: AtomicReference<String?>) {
            var c: Conn? = null
            try {
                while (fatal.get() == null && !cancelled) {
                    val i = next.getAndIncrement()
                    if (i >= accepted.size) break
                    val (item, token) = accepted[i]
                    item.state = ItemState.SENDING
                    var attempt = 0
                    while (true) {
                        attempt++
                        val reused = c != null && c.reusable
                        try {
                            if (!reused) { drop(c); c = null; c = connect() }
                            val r = upload(c!!, sid, item, token)
                            when (r.status) {
                                200 -> { item.state = ItemState.DONE; filesDone.incrementAndGet() }
                                // The session is gone: the other side stopped it (or was restarted).
                                403, 409 -> { failFile(item, "Not sent"); fatal.compareAndSet(null, "${dev.alias} stopped the transfer."); closeAll() }
                                422 -> failFile(item, "Arrived damaged (checksum mismatch)")
                                400 -> failFile(item, "${dev.alias} rejected it (the size didn't match)")
                                else -> failFile(item, "${dev.alias} couldn't save it (HTTP ${r.status})")
                            }
                            emit(true)
                            break
                        } catch (e: LocalRead) {
                            drop(c); c = null
                            failFile(item, e.message ?: "Couldn't be read")
                            emit(true)
                            break
                        } catch (e: Exception) {
                            drop(c); c = null
                            sent.addAndGet(-item.sent); item.sent = 0
                            if (cancelled || fatal.get() != null) { item.state = ItemState.QUEUED; break }
                            // A kept-alive connection can be closed by the receiver between files: try once more.
                            if (reused && attempt == 1 && !stalled) continue
                            failFile(item, "Not sent")
                            fatal.compareAndSet(null, if (e is Stop) e.message else describe(e))
                            closeAll()
                            break
                        }
                    }
                }
            } finally {
                drop(c)
            }
        }

        private fun failFile(item: Item, why: String) {
            sent.addAndGet(-item.sent)
            item.sent = 0
            item.error = why
            item.state = ItemState.FAILED
        }

        private fun upload(c: Conn, sid: String, item: Item, token: String): Response {
            val input = try { item.open(ctx) } catch (e: Exception) { throw LocalRead("Couldn't be read. Pick it again.") }
            try {
                sent.addAndGet(-item.sent); item.sent = 0
                val target = "$API/upload?sessionId=${enc(sid)}&fileId=${enc(item.id)}&token=${enc(token)}"
                return c.request("POST", target, "application/octet-stream", item.size, 90_000) { out ->
                    val buf = ByteArray(256 * 1024)
                    var left = item.size
                    while (left > 0) {
                        val n = try { input.read(buf, 0, minOf(buf.size.toLong(), left).toInt()) } catch (e: IOException) {
                            throw LocalRead("Couldn't be read to the end")
                        }
                        if (n < 0) throw LocalRead("Changed while it was being sent")
                        out.write(buf, 0, n)
                        left -= n
                        item.sent += n
                        sent.addAndGet(n.toLong())
                        lastByteAt = System.currentTimeMillis()
                        if (cancelled) throw IOException("cancelled")
                        emit(false)
                    }
                    lastByteAt = System.currentTimeMillis()
                }
            } finally {
                runCatching { input.close() }
            }
        }

        /** Reads a file once to learn its size (when unknown) and, for small files, its SHA-256. */
        private fun measure(item: Item) {
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            item.open(ctx).use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    count += n
                    if (count <= HASH_MAX_FILE) digest.update(buf, 0, n)
                    if (cancelled) throw IOException("cancelled")
                }
            }
            item.size = count
            item.sha256 = if (count <= HASH_MAX_FILE) digest.digest().joinToString("") { "%02x".format(it) } else null
        }

        private fun tellCancel() {
            val sid = sessionId
            thread(name = "wifi-send-cancel", isDaemon = true) {
                runCatching {
                    Conn.open(dev.ip, dev.port, tls, binder, 3000).use {
                        it.request("POST", "$API/cancel" + (sid?.let { s -> "?sessionId=" + enc(s) } ?: ""), null, 0, 3000, null)
                    }
                }
            }
        }

        private fun emit(force: Boolean) {
            val now = System.currentTimeMillis()
            synchronized(emitLock) {
                if (!force && now - lastEmit < 200) return
                lastEmit = now
                val dt = now - speedAt
                if (dt >= 500) {
                    val bytes = sent.get()
                    val instant = ((bytes - speedBytes) * 1000 / dt).coerceAtLeast(0)
                    speed = if (speed == 0L) instant else (speed * 0.6 + instant * 0.4).toLong()
                    speedAt = now; speedBytes = bytes
                }
            }
            val p = Progress(dev.alias, stage, sent.get().coerceAtLeast(0), total, filesDone.get(), fileCount,
                if (stage == Stage.SENDING) speed else 0, pinWrong)
            WifiSend.post { if (job === this@Job) progress = p }
        }

        @Suppress("DEPRECATION")
        private fun acquireLocks() {
            runCatching {
                val wm = ctx.getSystemService(WifiManager::class.java)
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "andromon-send").apply {
                    setReferenceCounted(false); acquire()
                }
            }
            runCatching {
                val pm = ctx.getSystemService(PowerManager::class.java)
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "andromon:send").apply {
                    setReferenceCounted(false); acquire(3 * 60 * 60_000L)
                }
            }
        }

        private fun releaseLocks() {
            runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            wifiLock = null; wakeLock = null
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ---------- a small HTTP/1.1 client over plain sockets (no library) ----------

    /**
     * TLS settings for one receiver. Receivers present self-signed certificates; with [pin] set
     * the handshake fails unless the certificate's SHA-256 is that fingerprint. Without a pin
     * (asking an address who it is) any certificate is accepted and its fingerprint recorded, and
     * that fingerprint is what a later transfer is pinned to.
     */
    private class Tls(id: TlsIdentity.Identity, val pin: String?) {
        @Volatile var mismatch = false
        val factory: SSLSocketFactory

        init {
            val pass = CharArray(0)
            val ks = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry("identity", id.key, pass, arrayOf(id.cert))
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pass) }
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    throw CertificateException("Not a server")
                }
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    val cert = chain?.firstOrNull() ?: throw CertificateException("No certificate")
                    if (pin != null && !fingerprintOf(cert.encoded).equals(pin, ignoreCase = true)) {
                        mismatch = true
                        throw CertificateException("The certificate doesn't match the announced fingerprint")
                    }
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            factory = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(tm), null) }.socketFactory
        }
    }

    private fun fingerprintOf(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02X".format(it) }

    /**
     * Sends connections out over the Wi‑Fi (or Ethernet) network that owns the target address.
     * Without this, a phone whose Wi‑Fi has no internet would try to reach the LAN over mobile data.
     */
    private class NetBinder(ctx: Context) {
        private class Lan(val net: Network, val addr: Int, val bits: Int)
        private val lans = ArrayList<Lan>()

        init {
            runCatching {
                val cm = ctx.getSystemService(ConnectivityManager::class.java)
                @Suppress("DEPRECATION")
                for (n in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(n) ?: continue
                    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
                    for (la in cm.getLinkProperties(n)?.linkAddresses.orEmpty()) {
                        val a = la.address as? Inet4Address ?: continue
                        lans += Lan(n, toInt(a.address), la.prefixLength.coerceIn(0, 32))
                    }
                }
            }
        }

        private fun toInt(b: ByteArray) = ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or
            ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)

        fun bind(sock: Socket, target: InetAddress) {
            val t = toInt((target as? Inet4Address ?: return).address)
            val lan = lans.firstOrNull { l ->
                val mask = if (l.bits == 0) 0 else -1 shl (32 - l.bits)
                (t and mask) == (l.addr and mask)
            } ?: return
            runCatching { lan.net.bindSocket(sock) }
        }
    }

    private class Response(val status: Int, val body: String)

    /** One connection to a receiver; requests go one after another and it is kept alive between them. */
    private class Conn(private val sock: Socket, val fingerprint: String?, private val host: String) : Closeable {
        private val input = BufferedInputStream(sock.getInputStream(), 16 * 1024)
        private val out: OutputStream = sock.getOutputStream()
        @Volatile var reusable = true
            private set

        companion object {
            fun open(ip: String, port: Int, tls: Tls?, binder: NetBinder, connectMs: Int): Conn {
                val addr = InetAddress.getByName(ip) // always a numeric address: no DNS lookup
                val sock = if (tls == null) Socket() else tls.factory.createSocket()
                try {
                    binder.bind(sock, addr)
                    sock.connect(InetSocketAddress(addr, port), connectMs)
                    sock.tcpNoDelay = true
                    var fp: String? = null
                    if (tls != null) {
                        sock.soTimeout = 10_000
                        val ssl = sock as SSLSocket
                        try { ssl.startHandshake() } catch (e: IOException) { throw if (tls.mismatch) PinMismatch() else e }
                        // Checked again here, before any request is written (also covers resumed sessions).
                        fp = fingerprintOf(ssl.session.peerCertificates[0].encoded)
                        if (tls.pin != null && !fp.equals(tls.pin, ignoreCase = true)) throw PinMismatch()
                    }
                    return Conn(sock, fp, "$ip:$port")
                } catch (e: Exception) {
                    runCatching { sock.close() }
                    throw e
                }
            }
        }

        /**
         * Sends one request and reads the reply. [body] writes exactly [length] bytes. If the
         * receiver answers early and closes (an upload it refuses), the answer is still returned.
         */
        fun request(method: String, target: String, type: String?, length: Long, readMs: Int,
                    body: ((OutputStream) -> Unit)?): Response {
            var complete = false
            try {
                sock.soTimeout = readMs
                val head = StringBuilder().append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(host).append("\r\n")
                    .append("User-Agent: AndromonCompanion\r\nAccept: */*\r\n")
                    .apply { if (type != null) append("Content-Type: ").append(type).append("\r\n") }
                    .append("Content-Length: ").append(length).append("\r\n")
                    .append("Connection: keep-alive\r\n\r\n")
                var writeError: IOException? = null
                try {
                    out.write(head.toString().toByteArray(Charsets.US_ASCII))
                    body?.invoke(out)
                    out.flush()
                } catch (e: IOException) {
                    writeError = e
                }
                if (writeError != null) {
                    reusable = false
                    runCatching { sock.soTimeout = 2000 }
                    return try { read(method) } catch (_: Exception) { throw writeError }
                }
                val r = read(method)
                complete = true
                return r
            } finally {
                if (!complete) reusable = false
            }
        }

        private fun read(method: String): Response {
            while (true) {
                val line = MiniHttp.readLine(input) ?: throw EOFException("The connection closed")
                val parts = line.split(' ', limit = 3)
                if (parts.size < 2 || !parts[0].startsWith("HTTP/1.")) throw IOException("Not an HTTP reply")
                val status = parts[1].toIntOrNull() ?: throw IOException("Not an HTTP reply")
                val headers = HashMap<String, String>()
                var lines = 0
                while (true) {
                    val h = MiniHttp.readLine(input) ?: throw EOFException("The connection closed")
                    if (h.isEmpty()) break
                    if (++lines > 100) throw IOException("Header too large")
                    val i = h.indexOf(':')
                    if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
                }
                if (status in 100..199) continue // e.g. 100 Continue
                val chunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
                val length = headers["content-length"]?.toLongOrNull()
                val text = when {
                    method == "HEAD" || status == 204 || status == 304 -> ""
                    chunked -> MiniHttp.Body(input, 0, true).text(REPLY_LIMIT)
                    length != null -> {
                        if (length < 0 || length > REPLY_LIMIT) throw IOException("Reply too large")
                        MiniHttp.Body(input, length, false).text(REPLY_LIMIT)
                    }
                    else -> { // no length given: the reply ends when the connection closes
                        reusable = false
                        val bytes = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            bytes.write(buf, 0, n)
                            if (bytes.size() > REPLY_LIMIT) throw IOException("Reply too large")
                        }
                        bytes.toString("UTF-8")
                    }
                }
                if (parts[0] == "HTTP/1.0" || headers["connection"].equals("close", ignoreCase = true)) reusable = false
                return Response(status, text)
            }
        }

        override fun close() {
            reusable = false
            runCatching { sock.close() }
        }
    }
}
