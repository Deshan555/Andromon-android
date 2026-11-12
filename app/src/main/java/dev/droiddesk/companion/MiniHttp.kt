// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

package dev.droiddesk.companion

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * Just enough HTTP/1.1 for the Wi‑Fi receive API, so the app needs no server library: keep-alive,
 * Content-Length and chunked request bodies, `Expect: 100-continue`, and small JSON replies.
 * One connection is served on the calling thread until it closes.
 */
internal object MiniHttp {

    class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val ip: String,
        /** Our address the request arrived on (tells LAN from Direct link). */
        val localIp: String,
        val body: Body,
    )

    class Reply(val status: Int, val json: String? = null, val close: Boolean = false)

    private const val MAX_HEAD = 32 * 1024

    fun serve(sock: Socket, handler: (Request) -> Reply) {
        val input = BufferedInputStream(sock.getInputStream(), 64 * 1024)
        val out = BufferedOutputStream(sock.getOutputStream(), 16 * 1024)
        val ip = sock.inetAddress?.hostAddress?.removePrefix("::ffff:") ?: ""
        val localIp = sock.localAddress?.hostAddress?.removePrefix("::ffff:") ?: ""
        while (!sock.isClosed) {
            val head = try { readHead(input) } catch (_: SocketTimeoutException) { null } ?: return
            val parts = head[0].split(' ')
            if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) { write(out, Reply(400), false); return }
            val headers = HashMap<String, String>()
            for (line in head.drop(1)) {
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val chunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
            val length = if (chunked) 0L else headers["content-length"]?.let { it.toLongOrNull() ?: -1L } ?: 0L
            if (length < 0) { write(out, Reply(400), false); return }
            if (headers["expect"].equals("100-continue", ignoreCase = true)) {
                out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); out.flush()
            }

            val target = parts[1]
            val q = target.indexOf('?')
            val path = if (q >= 0) target.substring(0, q) else target
            val query = if (q < 0) emptyMap() else target.substring(q + 1).split('&').filter { it.isNotEmpty() }.associate {
                val e = it.indexOf('=')
                fun dec(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
                if (e < 0) dec(it) to "" else dec(it.substring(0, e)) to dec(it.substring(e + 1))
            }

            val body = Body(input, length, chunked)
            val reply = try {
                handler(Request(parts[0], path, query, headers, ip, localIp, body))
            } catch (e: IOException) {
                return // the connection broke
            } catch (e: Exception) {
                Reply(500, close = true)
            }
            // Keep the connection only if the request body was read to its end (or nearly).
            val keep = parts[2] == "HTTP/1.1" && !reply.close &&
                !headers["connection"].equals("close", ignoreCase = true) &&
                try { body.drain(256 * 1024) } catch (_: IOException) { false }
            try { write(out, reply, keep) } catch (_: IOException) { return }
            if (!keep) return
        }
    }

    private fun write(out: OutputStream, r: Reply, keepAlive: Boolean) {
        val bytes = r.json?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val head = StringBuilder()
            .append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n")
            .append("Content-Length: ").append(bytes.size).append("\r\n")
            .apply { if (r.json != null) append("Content-Type: application/json\r\n") }
            .append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
        404 -> "Not Found"; 409 -> "Conflict"; 413 -> "Payload Too Large"; 422 -> "Unprocessable Entity"
        429 -> "Too Many Requests"; else -> if (code >= 500) "Server Error" else "Status"
    }

    /** Request line + headers; null if the peer closed (or went idle) before sending anything. */
    private fun readHead(input: InputStream): List<String>? {
        val lines = ArrayList<String>()
        var size = 0
        while (true) {
            val line = readLine(input) ?: if (lines.isEmpty()) return null else throw EOFException()
            if (line.isEmpty()) { if (lines.isEmpty()) continue else return lines }
            size += line.length
            if (size > MAX_HEAD || lines.size > 100) throw IOException("Header too large")
            lines += line
        }
    }

    internal fun readLine(input: InputStream, max: Int = 8192): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return if (sb.endsWith('\r')) sb.substring(0, sb.length - 1) else sb.toString()
            if (sb.length >= max) throw IOException("Line too long")
            sb.append(c.toChar())
        }
    }

    /** The request body: ends after Content-Length bytes, or at the last chunk. */
    class Body(private val src: InputStream, length: Long, private val chunked: Boolean) : InputStream() {
        private var left = if (chunked) 0L else length
        var done = !chunked && length == 0L
            private set

        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (done || len == 0) return if (done) -1 else 0
            if (left == 0L && !nextChunk()) { done = true; return -1 }
            val n = src.read(b, off, minOf(len.toLong(), left).toInt())
            if (n < 0) throw EOFException("Connection closed during upload")
            left -= n
            if (left == 0L) {
                if (chunked) readLine(src) // CRLF after the chunk data
                else done = true
            }
            return n
        }

        private fun nextChunk(): Boolean {
            val line = readLine(src) ?: throw EOFException()
            val size = line.substringBefore(';').trim().toLongOrNull(16) ?: throw IOException("Bad chunk")
            if (size < 0) throw IOException("Bad chunk")
            if (size == 0L) {
                while (true) { val t = readLine(src) ?: break; if (t.isEmpty()) break } // trailers
                return false
            }
            left = size
            return true
        }

        /** Discards up to [limit] remaining bytes. True if the body ended. */
        fun drain(limit: Long): Boolean {
            val buf = ByteArray(16 * 1024)
            var n = 0L
            while (!done && n <= limit) {
                val r = read(buf)
                if (r < 0) break
                n += r
            }
            return done
        }

        /** Reads the whole body as text, refusing more than [limit] bytes. */
        fun text(limit: Int): String {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val r = read(buf)
                if (r < 0) break
                out.write(buf, 0, r)
                if (out.size() > limit) throw IOException("Body too large")
            }
            return out.toString("UTF-8")
        }
    }
}
