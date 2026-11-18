// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * Wi‑Fi receive: state shared by [ReceiveService] (which owns the [WifiServer]) and
 * [ReceiveScreen]. Fields are read and written on the main thread only; [post] changes them there
 * and notifies the listeners.
 */
internal object WifiReceive {

    /** [direct]: the computer asked for a Direct link (see [DirectLink]). */
    class Request(val id: String, val from: String, val model: String?, val ip: String,
                  val files: List<Pair<String, Long>>, val total: Long, val expires: Long, val direct: Boolean)

    /** [via]: "Direct link (5 GHz)" while the computer is on this phone's hotspot, else null. */
    class Progress(val from: String, val received: Long, val total: Long,
                   val filesDone: Int, val fileCount: Int, val bytesPerSec: Long, val via: String?)

    class Saved(val name: String, var uri: String, val size: Long, val mime: String,
                val time: Long, val from: String, val path: String)

    /** A transfer that ended; [seq] increases each time. [problem] is null when it completed. */
    class Done(val seq: Int, val from: String, val count: Int, val problem: String?)

    var starting = false
    var running = false
    var alias = ""
    var port = 0
    /** Why receiving stopped or couldn't start, or a warning while running. */
    var message: String? = null
    var request: Request? = null
    var progress: Progress? = null
    var done: Done? = null
    val recent = ArrayList<Saved>()
    private var recentLoaded = false

    @Volatile internal var server: WifiServer? = null

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()

    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }

    /** Applies [change] on the main thread, then tells the listeners. */
    fun post(change: WifiReceive.() -> Unit) {
        main.post { change(); listeners.toList().forEach { it() } }
    }

    fun respond(id: String, accept: Boolean) { server?.respond(id, accept) }
    fun stopTransfer() { server?.dropSession("You stopped the transfer") }

    // ---------- received-files list (kept in app preferences) ----------

    fun loadRecent(ctx: Context) {
        if (recentLoaded) return
        recentLoaded = true
        val raw = ctx.getSharedPreferences("wifi_receive", Context.MODE_PRIVATE).getString("recent", null) ?: return
        runCatching {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) a.getJSONObject(i).run {
                recent += Saved(getString("name"), getString("uri"), getLong("size"), getString("mime"),
                    getLong("time"), getString("from"), optString("path"))
            }
        }
    }

    fun saveRecent(ctx: Context) {
        while (recent.size > 50) recent.removeAt(recent.size - 1)
        val a = JSONArray()
        recent.forEach {
            a.put(JSONObject().put("name", it.name).put("uri", it.uri).put("size", it.size).put("mime", it.mime)
                .put("time", it.time).put("from", it.from).put("path", it.path))
        }
        ctx.getSharedPreferences("wifi_receive", Context.MODE_PRIVATE).edit().putString("recent", a.toString()).apply()
    }
}

/**
 * Receives files over Wi‑Fi using an open, documented file-sharing protocol (v2), so computers
 * running Andromon, and other apps that speak the same protocol, can send to this phone:
 *
 *  - Discovery: JSON announcements over UDP multicast 224.0.0.167:53317. When a sender announces
 *    itself, we answer with our own details (alias, port, certificate fingerprint).
 *  - Transfer: HTTPS on TCP 53317 (or the next free port, which we announce). `prepare-upload`
 *    lists the files, the person accepts or declines on the phone, then each file is sent to
 *    `upload` with a per-file token.
 *
 * Security: nothing listens until receiving is turned on. HTTPS only, with this phone's own
 * certificate, which senders pin by fingerprint. Every transfer needs the person's consent.
 * Uploads are bound to the session, a random per-file token and the sender's IP address, may
 * not exceed the announced size, are checked against the sender's SHA-256 when given, and are
 * saved only under Download/Andromon with sanitised names.
 */
internal class WifiServer(private val ctx: Context) {

    companion object {
        const val PORT = 53317
        const val FOLDER = "Andromon"
        private const val GROUP = "224.0.0.167"
        private const val API = "/api/localsend/v2" // URL paths are fixed by the wire protocol
        private const val PROTOCOL_VERSION = "2.1"
        private const val MAX_FILES = 10_000
        private const val CONSENT_MS = 60_000L
        private const val SESSION_IDLE_MS = 5 * 60_000L
        private const val TAG = "WifiReceive"
        private val random = SecureRandom()

        fun randomHex(bytes: Int) = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

        /** Wi‑Fi, hotspot and Ethernet interfaces (not mobile data or VPNs). */
        fun lanInterfaces(): List<NetworkInterface> {
            val all = try { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() } catch (_: Exception) { emptyList() }
            val usable = all.filter {
                runCatching { it.isUp && !it.isLoopback && it.supportsMulticast() }.getOrDefault(false) &&
                    it.inetAddresses.toList().any { a -> a is Inet4Address }
            }
            val lan = Regex("^(wlan|swlan|ap|softap|eth|wigig|p2p|rndis|usb|br)", RegexOption.IGNORE_CASE)
            val notLan = Regex("^(rmnet|ccmni|ccemni|pdp|v4-|dummy|tun|ppp|clat|ip6tnl|sit|epdg|seth|vti)", RegexOption.IGNORE_CASE)
            return usable.filter { lan.containsMatchIn(it.name) }.ifEmpty { usable.filterNot { notLan.containsMatchIn(it.name) } }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
        }

        fun lanAddress(): String? = lanInterfaces().firstNotNullOfOrNull { ni ->
            ni.inetAddresses.toList().firstOrNull { it is Inet4Address }?.hostAddress
        }

        /** This phone's own Wi‑Fi link, e.g. {band:"5 GHz", mhz:5180, linkMbps:433, rssi:-58}; null if not on Wi‑Fi. */
        @Suppress("DEPRECATION")
        fun wifiLink(ctx: Context): JSONObject? {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
            val wi = (if (Build.VERSION.SDK_INT >= 29) caps.transportInfo as? WifiInfo else null)
                ?: ctx.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo ?: return null
            val band = when (wi.frequency) {
                in 2400..2500 -> "2.4 GHz"; in 4900..5900 -> "5 GHz"; in 5925..7125 -> "6 GHz"; else -> null
            }
            return JSONObject().put("band", band).put("mhz", wi.frequency)
                .put("linkMbps", wi.linkSpeed.takeIf { it > 0 })
                .put("txMbps", if (Build.VERSION.SDK_INT >= 29) wi.txLinkSpeedMbps.takeIf { it > 0 } else null)
                .put("rssi", wi.rssi.takeIf { it > -127 })
        }

        fun deviceName(ctx: Context): String =
            (runCatching { Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: Build.MODEL).trim().take(60)
    }

    private class FileEntry(val id: String, val name: String, val size: Long, val sha256: String?, val type: String?) {
        val token = randomHex(24)
        var done = false
        var busy = false
    }

    private class Session(val id: String, val ip: String, val from: String, val files: Map<String, FileEntry>, val total: Long) {
        @Volatile var direct: DirectLink.Network? = null
        @Volatile var viaDirect = false
        val received = AtomicLong()
        @Volatile var active = System.currentTimeMillis()
        val saved: MutableList<WifiReceive.Saved> = Collections.synchronizedList(ArrayList())
        var lastEmit = 0L
        var speedAt = System.currentTimeMillis()
        var speedBytes = 0L
        var speed = 0L
    }

    private class Sink(val out: OutputStream, val commit: () -> WifiReceive.Saved, val abort: () -> Unit)

    private val lock = Any()
    @Volatile private var session: Session? = null
    private val pending = HashMap<String, CompletableFuture<Boolean>>()
    @Volatile private var stopped = false
    @Volatile var lastActivity = System.currentTimeMillis()
        private set

    private lateinit var identity: TlsIdentity.Identity
    private val alias = deviceName(ctx)
    private val model = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }.take(40)
    private var port = PORT
    private var serverSocket: SSLServerSocket? = null
    private var udp: MulticastSocket? = null
    private var udpInterfaces = emptyList<NetworkInterface>()
    private var lastReply = 0L
    private val sockets: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap())
    private val pool = ThreadPoolExecutor(0, 32, 30, TimeUnit.SECONDS, SynchronousQueue())
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // ---------- lifecycle ----------

    /** Opens the HTTPS port and joins discovery. Blocking; throws with a message for the person. */
    fun start() {
        identity = TlsIdentity.get(ctx)
        val factory = identity.sslContext().serverSocketFactory
        var bound: SSLServerSocket? = null
        for (p in PORT until PORT + 10) {
            bound = try { factory.createServerSocket(p, 50) as SSLServerSocket } catch (_: IOException) { null }
            if (bound != null) { port = p; break }
        }
        val server = bound ?: throw IOException("No free network port. Close other file-sharing apps on this phone and try again.")
        server.needClientAuth = false
        serverSocket = server
        thread(name = "wifi-receive-accept", isDaemon = true) { acceptLoop(server) }

        val warning = try { startDiscovery(); null } catch (e: Exception) {
            Log.w(TAG, "Discovery unavailable", e)
            "Computers may not find this phone automatically: ${e.message ?: "multicast is blocked"}."
        }
        timer.scheduleWithFixedDelay({ checkIdle() }, 30, 30, TimeUnit.SECONDS)
        WifiReceive.post {
            starting = false; running = true; alias = this@WifiServer.alias; port = this@WifiServer.port; message = warning
        }
    }

    fun stop() {
        stopped = true
        synchronized(lock) { pending.values.toList() }.forEach { it.complete(false) }
        dropSession("Receiving was turned off")
        DirectLink.stop()
        runCatching { serverSocket?.close() }
        runCatching { udp?.close() }
        sockets.toList().forEach { runCatching { it.close() } }
        pool.shutdownNow()
        timer.shutdownNow()
        runCatching { multicastLock?.release() }
        releaseTransferLocks()
    }

    private fun acceptLoop(server: SSLServerSocket) {
        while (!stopped) {
            val s = try { server.accept() } catch (_: IOException) { break }
            try {
                pool.execute { serve(s) }
            } catch (_: RejectedExecutionException) {
                runCatching { s.close() }
            }
        }
    }

    private fun serve(s: Socket) {
        sockets += s
        try {
            s.soTimeout = 30_000
            s.tcpNoDelay = true
            (s as SSLSocket).startHandshake()
            MiniHttp.serve(s, ::handle)
        } catch (_: Exception) {
        } finally {
            sockets -= s
            runCatching { s.close() }
        }
    }

    private fun checkIdle() {
        val s = session ?: return
        if (System.currentTimeMillis() - s.active > SESSION_IDLE_MS) dropSession("The sender stopped responding")
    }

    // ---------- discovery ----------

    /**
     * Our details. The "andromon" object is an extension other apps ignore: it tells Andromon
     * this phone can do a Direct link, and (over HTTPS only) how good its Wi‑Fi link is.
     */
    private fun info(withNetwork: Boolean, withLink: Boolean = false): JSONObject = JSONObject()
        .put("alias", alias).put("version", PROTOCOL_VERSION).put("deviceModel", model)
        .put("deviceType", "mobile").put("fingerprint", identity.fingerprint).put("download", false)
        .apply { if (withNetwork) put("port", port).put("protocol", "https") }
        .put("andromon", JSONObject().put("v", 1)
            .put("directLink", DirectLink.state(ctx) == DirectLink.State.READY)
            .apply { if (withLink) put("wifi", wifiLink(ctx)) })

    private fun startDiscovery() {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        // Without this lock many phones drop multicast packets to save power.
        multicastLock = wm?.createMulticastLock("andromon-receive")?.apply { setReferenceCounted(false); acquire() }
        val sock = MulticastSocket(null as SocketAddress?).apply {
            reuseAddress = true
            bind(InetSocketAddress(PORT))
            timeToLive = 4
        }
        val group = InetSocketAddress(InetAddress.getByName(GROUP), 0)
        udpInterfaces = lanInterfaces().filter { runCatching { sock.joinGroup(group, it) }.isSuccess }
        if (udpInterfaces.isEmpty()) {
            @Suppress("DEPRECATION") sock.joinGroup(InetAddress.getByName(GROUP))
        }
        udp = sock
        thread(name = "wifi-receive-udp", isDaemon = true) { udpLoop(sock) }
        announce()
    }

    /** Tells senders on the network that this phone is here (repeated: UDP can be lost). */
    fun announce() {
        if (udp == null || stopped) return
        for (delay in longArrayOf(0, 1500, 4000)) {
            runCatching { timer.schedule({ sendUdp(info(true).put("announce", true)) }, delay, TimeUnit.MILLISECONDS) }
        }
    }

    private fun udpLoop(sock: MulticastSocket) {
        val buf = ByteArray(16 * 1024)
        while (!stopped) {
            val p = DatagramPacket(buf, buf.size)
            try { sock.receive(p) } catch (_: IOException) { break }
            val msg = runCatching { JSONObject(String(p.data, p.offset, p.length, Charsets.UTF_8)) }.getOrNull() ?: continue
            if (msg.optString("fingerprint") == identity.fingerprint) continue
            WifiSend.onPeer(msg, p.address?.hostAddress) // Wi‑Fi send lists the devices it hears
            // A sender announced itself: reply so it lists this phone (the protocol's UDP answer).
            if (msg.optBoolean("announce") || msg.optBoolean("announcement")) {
                val now = System.currentTimeMillis()
                if (now - lastReply > 500) {
                    lastReply = now
                    sendUdp(info(true).put("announce", false))
                }
            }
        }
    }

    private fun sendUdp(json: JSONObject) {
        val sock = udp ?: return
        val data = json.toString().toByteArray(Charsets.UTF_8)
        val target = InetSocketAddress(GROUP, PORT)
        synchronized(sock) {
            if (udpInterfaces.isEmpty()) runCatching { sock.send(DatagramPacket(data, data.size, target)) }
            for (ni in udpInterfaces) runCatching {
                sock.networkInterface = ni
                sock.send(DatagramPacket(data, data.size, target))
            }
        }
    }

    // ---------- HTTP API ----------

    private fun json(status: Int, body: JSONObject) = MiniHttp.Reply(status, body.toString())

    private fun handle(req: MiniHttp.Request): MiniHttp.Reply {
        if (stopped) return MiniHttp.Reply(503, close = true)
        val p = req.path
        return when {
            req.method == "GET" && (p == "$API/info" || p == "/api/localsend/v1/info") -> json(200, info(false, withLink = true))
            req.method == "POST" && p == "$API/register" -> {
                // A device answering our own announcement: Wi‑Fi send lists it.
                runCatching { WifiSend.onPeer(JSONObject(req.body.text(64 * 1024)), req.ip) }
                json(200, info(false))
            }
            req.method == "POST" && p == "$API/prepare-upload" -> prepareUpload(req)
            req.method == "POST" && p == "$API/upload" -> upload(req)
            req.method == "POST" && p == "$API/cancel" -> {
                val s = session
                if (s != null && s.id == req.query["sessionId"] && s.ip == req.ip) dropSession("The sender cancelled the transfer")
                MiniHttp.Reply(200)
            }
            else -> MiniHttp.Reply(404)
        }
    }

    private fun prepareUpload(req: MiniHttp.Request): MiniHttp.Reply {
        val body = try { JSONObject(req.body.text(4 * 1024 * 1024)) } catch (_: Exception) { return MiniHttp.Reply(400, close = true) }
        val filesObj = body.optJSONObject("files") ?: return MiniHttp.Reply(400)
        val keys = filesObj.keys().asSequence().take(MAX_FILES + 1).toList()
        if (keys.isEmpty()) return MiniHttp.Reply(204)
        if (keys.size > MAX_FILES) return MiniHttp.Reply(400)
        val files = ArrayList<FileEntry>(keys.size)
        for (k in keys) {
            val o = filesObj.optJSONObject(k) ?: return MiniHttp.Reply(400)
            val size = (o.opt("size") as? Number)?.takeIf { it.toDouble() == it.toLong().toDouble() }?.toLong()
            if (size == null || size < 0) return MiniHttp.Reply(400)
            val sha = o.optString("sha256").lowercase().takeIf { Regex("^[0-9a-f]{64}$").matches(it) }
            files += FileEntry(k.take(200), o.optString("fileName").take(1000).ifBlank { "file" }, size, sha,
                o.optString("fileType").take(100).ifBlank { null })
        }
        val info = body.optJSONObject("info") ?: JSONObject()
        val from = info.optString("alias").trim().take(80).ifBlank { "Unknown device" }
        val fromModel = info.optString("deviceModel").trim().take(80).ifBlank { null }
        val total = files.sumOf { it.size }
        // "droiddesk" is what computers with the app's old name (Droid Desk) send
        val ext = body.optJSONObject("andromon") ?: body.optJSONObject("droiddesk")
        val wantsDirect = ext?.optBoolean("directLink") == true &&
            DirectLink.state(ctx) == DirectLink.State.READY

        val free = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (total > free - 50_000_000) {
            WifiReceive.post { message = "Declined files from $from: they need ${fmtSize(total)}, and only ${fmtSize(free)} is free." }
            return MiniHttp.Reply(403)
        }

        val requestId = UUID.randomUUID().toString()
        val decision = CompletableFuture<Boolean>()
        synchronized(lock) {
            if (session != null || pending.isNotEmpty()) return MiniHttp.Reply(409)
            pending[requestId] = decision
        }
        lastActivity = System.currentTimeMillis()
        val expires = System.currentTimeMillis() + CONSENT_MS
        val summary = files.map { it.name.substringAfterLast('/') to it.size }
        WifiReceive.post { request = WifiReceive.Request(requestId, from, fromModel, req.ip, summary, total, expires, wantsDirect) }
        val accepted = try {
            decision.get(CONSENT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            false
        } catch (_: Exception) {
            false
        }
        synchronized(lock) { pending.remove(requestId) }
        WifiReceive.post { if (request?.id == requestId) request = null }
        if (!accepted || stopped) return MiniHttp.Reply(403)

        val s = Session(UUID.randomUUID().toString(), req.ip, from, files.associateBy { it.id }, total)
        synchronized(lock) {
            if (session != null) return MiniHttp.Reply(409)
            session = s
        }
        acquireTransferLocks()
        lastActivity = System.currentTimeMillis()
        WifiReceive.post { message = null }
        progress(s, true)
        val tokens = JSONObject()
        files.forEach { tokens.put(it.id, it.token) }
        val reply = JSONObject().put("sessionId", s.id).put("files", tokens)
        if (wantsDirect) {
            // Hotspot details travel only inside this encrypted reply, to the computer that asked.
            // The computer may still upload over the normal Wi‑Fi if it can't join.
            val extra = JSONObject()
            try {
                val net = DirectLink.start(ctx)
                s.direct = net
                extra.put("directLink", JSONObject().put("ssid", net.ssid).put("passphrase", net.passphrase)
                    .put("security", net.security).put("band", net.band).put("port", port)
                    .put("addresses", JSONArray(net.addresses)))
            } catch (e: Exception) {
                Log.w(TAG, "Direct link failed", e)
                extra.put("directLinkError", e.message ?: "The phone couldn't start a Direct link")
                WifiReceive.post { message = "Direct link: ${e.message}. Receiving over the normal Wi‑Fi instead." }
            }
            reply.put("andromon", extra)
        }
        return json(200, reply)
    }

    private fun upload(req: MiniHttp.Request): MiniHttp.Reply {
        val sid = req.query["sessionId"]
        val fid = req.query["fileId"]
        val token = req.query["token"]
        if (sid == null || fid == null || token == null) return MiniHttp.Reply(400, close = true)
        val s = session
        if (s == null || s.id != sid) return MiniHttp.Reply(if (s != null) 409 else 403, close = true)
        val f = s.files[fid]
        val tokenOk = f != null && MessageDigest.isEqual(f.token.toByteArray(), token.toByteArray())
        // Same computer: either the address that asked, or (Direct link) a client of our hotspot,
        // which only that computer knows the password for.
        val viaDirect = s.direct?.addresses?.contains(req.localIp) == true
        if (!tokenOk || (s.ip != req.ip && !viaDirect)) return MiniHttp.Reply(403, close = true)
        if (viaDirect) s.viaDirect = true
        synchronized(f!!) {
            if (f.done || f.busy) return MiniHttp.Reply(403, close = true)
            f.busy = true
        }
        lastActivity = System.currentTimeMillis()
        s.active = lastActivity

        val sink = try {
            openSink(s, f)
        } catch (e: Exception) {
            Log.w(TAG, "Can't create ${f.name}", e)
            synchronized(f) { f.busy = false }
            WifiReceive.post { message = "Couldn't save ${f.name.substringAfterLast('/')}: ${e.message}" }
            return MiniHttp.Reply(500, close = true)
        }
        val digest = f.sha256?.let { MessageDigest.getInstance("SHA-256") }
        val buf = ByteArray(256 * 1024)
        var received = 0L
        var fail: Int? = null
        try {
            while (true) {
                val n = req.body.read(buf)
                if (n < 0) break
                received += n
                if (received > f.size) { fail = 400; break }
                digest?.update(buf, 0, n)
                sink.out.write(buf, 0, n)
                s.received.addAndGet(n.toLong())
                s.active = System.currentTimeMillis()
                if (session !== s) { fail = 409; break }
                progress(s, false)
            }
            sink.out.close()
        } catch (_: IOException) {
            if (fail == null) fail = 500
            runCatching { sink.out.close() }
        }
        if (fail == null && received != f.size) fail = 400
        if (fail == null && digest != null && digest.digest().joinToString("") { "%02x".format(it) } != f.sha256) fail = 422
        if (fail == null && session !== s) fail = 409
        val saved = if (fail != null) null else try { sink.commit() } catch (e: Exception) { Log.w(TAG, "Commit failed", e); null }
        if (saved == null) {
            sink.abort()
            s.received.addAndGet(-received)
            synchronized(f) { f.busy = false }
            progress(s, true)
            return MiniHttp.Reply(fail ?: 500, close = true)
        }
        synchronized(f) { f.done = true; f.busy = false }
        s.saved += saved
        WifiReceive.post { recent.add(0, saved); saveRecent(ctx) }
        progress(s, true)
        if (s.files.values.all { synchronized(it) { it.done } }) finish(s, null)
        return MiniHttp.Reply(200)
    }

    fun respond(id: String, accept: Boolean) {
        synchronized(lock) { pending[id] }?.complete(accept)
    }

    fun dropSession(reason: String) = session?.let { finish(it, reason) }

    private fun finish(s: Session, problem: String?) {
        synchronized(lock) {
            if (session !== s) return
            session = null
        }
        releaseTransferLocks()
        if (s.direct != null) DirectLink.stop()
        val count = s.saved.size
        LinkStats.filesReceived(ctx, s.from, count, synchronized(s.saved) { s.saved.sumOf { it.size } }) // Stats › PC link
        WifiReceive.post {
            progress = null
            done = WifiReceive.Done((done?.seq ?: 0) + 1, s.from, count, problem)
        }
    }

    private fun progress(s: Session, force: Boolean) {
        val now = System.currentTimeMillis()
        synchronized(s) {
            if (!force && now - s.lastEmit < 250) return
            s.lastEmit = now
            val dt = now - s.speedAt
            if (dt >= 500) {
                val bytes = s.received.get()
                val instant = (bytes - s.speedBytes) * 1000 / dt
                s.speed = if (s.speed == 0L) instant else (s.speed * 0.6 + instant * 0.4).toLong()
                s.speedAt = now; s.speedBytes = bytes
            }
        }
        val via = if (s.viaDirect) "Direct link (${s.direct?.band ?: "Wi‑Fi"})" else null
        val p = WifiReceive.Progress(s.from, s.received.get(), s.total,
            s.files.values.count { synchronized(it) { it.done } }, s.files.size, s.speed, via)
        WifiReceive.post { if (session === s) progress = p }
    }

    // ---------- keeping Wi‑Fi fast and the CPU awake during a transfer ----------

    @Suppress("DEPRECATION")
    private fun acquireTransferLocks() {
        runCatching {
            val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "andromon-receive").apply {
                setReferenceCounted(false); acquire()
            }
        }
        runCatching {
            val pm = ctx.getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "andromon:receive").apply {
                setReferenceCounted(false); acquire(3 * 60 * 60_000L)
            }
        }
    }

    private fun releaseTransferLocks() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null; wakeLock = null
    }

    // ---------- saving ----------

    /** Splits a sender's path ("photos/2024/a.jpg") into safe folder names and a file name. */
    private fun safePath(raw: String): Pair<List<String>, String> {
        fun clean(p: String) = p.replace(Regex("[\\u0000-\\u001f\\u007f<>:\"|?*]"), "_").trim().trimEnd('.', ' ').take(180)
        val parts = raw.replace('\\', '/').split('/').map(::clean).filter { it.isNotEmpty() && it != "." && it != ".." }
        if (parts.isEmpty()) return emptyList<String>() to "file"
        return parts.dropLast(1).takeLast(6) to parts.last()
    }

    private fun mimeOf(name: String, senderType: String?): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: senderType?.takeIf { Regex("^[\\w.+-]+/[\\w.+-]+$").matches(it) }
            ?: "application/octet-stream"
    }

    private fun openSink(s: Session, f: FileEntry): Sink {
        val (dirs, name) = safePath(f.name)
        val mime = mimeOf(name, f.type)
        if (Build.VERSION.SDK_INT >= 29) {
            // MediaStore: no storage permission needed, and the file shows up in Downloads/Gallery.
            // It stays hidden (IS_PENDING) until it's complete; name clashes get " (1)" added.
            val rel = (listOf(Environment.DIRECTORY_DOWNLOADS, FOLDER) + dirs).joinToString("/") + "/"
            val cr = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Storage refused the file")
            val os = try {
                cr.openOutputStream(uri, "w") ?: throw IOException("Storage refused the file")
            } catch (e: Exception) {
                runCatching { cr.delete(uri, null, null) }
                throw e
            }
            return Sink(BufferedOutputStream(os, 1 shl 20), commit = {
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                val finalName = cr.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: name
                WifiReceive.Saved(finalName, uri.toString(), f.size, mime, System.currentTimeMillis(), s.from, rel + finalName)
            }, abort = { runCatching { cr.delete(uri, null, null) } })
        }
        // Android 8–9: write the file directly (needs the storage permission), then index it.
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            (listOf(FOLDER) + dirs).joinToString(File.separator))
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Can't create ${dir.path}")
        val part = File(dir, "$name.${randomHex(4)}.part")
        val os = FileOutputStream(part)
        return Sink(BufferedOutputStream(os, 1 shl 20), commit = {
            val target = uniqueFile(dir, name)
            if (!part.renameTo(target)) throw IOException("Couldn't rename ${part.name}")
            MediaScannerConnection.scanFile(ctx, arrayOf(target.path), arrayOf(mime)) { path, uri ->
                if (uri != null) WifiReceive.post { recent.find { it.path == path }?.uri = uri.toString(); saveRecent(ctx) }
            }
            WifiReceive.Saved(target.name, Uri.fromFile(target).toString(), f.size, mime, System.currentTimeMillis(), s.from, target.path)
        }, abort = { part.delete() })
    }

    private fun uniqueFile(dir: File, name: String): File {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val ext = name.substring(dot)
        var i = 0
        while (true) {
            val f = File(dir, if (i == 0) name else "$base ($i)$ext")
            if (!f.exists()) return f
            i++
        }
    }
}
