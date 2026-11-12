// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

package dev.droiddesk.companion

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * The phone's identity for Wi‑Fi receive: an RSA-2048 key and a self-signed certificate.
 *
 * Senders identify this phone by the SHA-256 fingerprint of the certificate (announced on the
 * network) and check it when they connect, so the same key is kept across restarts. It lives in
 * app-private storage. Android has no public API for making certificates, so the X.509 structure
 * is written by hand with a tiny DER encoder; no libraries are needed.
 */
internal object TlsIdentity {

    class Identity(val key: PrivateKey, val cert: X509Certificate) {
        /** Upper-case hex SHA-256 of the certificate, as senders compute it. */
        val fingerprint: String = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02X".format(it) }

        /** TLS server context presenting this certificate. */
        fun sslContext(): SSLContext {
            val pass = CharArray(0)
            val ks = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry("identity", key, pass, arrayOf(cert))
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pass) }
            return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        }
    }

    private const val FILE_NAME = "wifi-identity.json"
    @Volatile private var cached: Identity? = null

    /** Loads the identity, creating it on first use (takes a moment: call off the main thread). */
    fun get(ctx: Context): Identity = synchronized(this) {
        cached ?: (load(ctx) ?: create(ctx)).also { cached = it }
    }

    private fun load(ctx: Context): Identity? = try {
        val j = JSONObject(File(ctx.filesDir, FILE_NAME).readText())
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(j.getString("key"), Base64.NO_WRAP)))
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(Base64.decode(j.getString("cert"), Base64.NO_WRAP))) as X509Certificate
        // Renew a year before it runs out.
        if (cert.notAfter.time - System.currentTimeMillis() < 365L * 86_400_000) null else Identity(key, cert)
    } catch (_: Exception) {
        null
    }

    private fun create(ctx: Context): Identity {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val certDer = selfSigned(pair.public.encoded, pair.private, "Droid Desk Companion")
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
        val json = JSONObject()
            .put("key", Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
            .put("cert", Base64.encodeToString(certDer, Base64.NO_WRAP))
        val tmp = File(ctx.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(File(ctx.filesDir, FILE_NAME))
        return Identity(pair.private, cert)
    }

    // ---------- X.509 ----------

    private fun selfSigned(spki: ByteArray, key: PrivateKey, commonName: String): ByteArray {
        val sha256WithRsa = seq(oid("1.2.840.113549.1.1.11"), der(0x05)) // NULL parameters
        val name = seq(set(seq(oid("2.5.4.3"), der(0x0c, commonName.toByteArray(Charsets.UTF_8)))))
        val now = System.currentTimeMillis()
        val serial = ByteArray(16).also { SecureRandom().nextBytes(it); it[0] = (it[0].toInt() and 0x7f).toByte() }
        val tbs = seq(
            der(0xa0, int(BigInteger.valueOf(2))), // v3
            int(BigInteger(serial)),
            sha256WithRsa,
            name,
            seq(time(Date(now - 86_400_000L)), time(Date(now + 10L * 365 * 86_400_000))),
            name,
            spki, // SubjectPublicKeyInfo, already DER
        )
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(key); update(tbs); sign() }
        return seq(tbs, sha256WithRsa, der(0x03, byteArrayOf(0), sig))
    }

    private fun der(tag: Int, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
        val n = body.size
        val len = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
            else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
        }
        return byteArrayOf(tag.toByte()) + len + body
    }

    private fun seq(vararg parts: ByteArray) = der(0x30, *parts)
    private fun set(vararg parts: ByteArray) = der(0x31, *parts)
    private fun int(v: BigInteger) = der(0x02, v.toByteArray())

    private fun oid(dotted: String): ByteArray {
        val p = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((p[0] * 40 + p[1]).toInt())
        for (v in p.drop(2)) {
            val groups = ArrayList<Int>()
            var x = v
            do { groups.add((x and 0x7f).toInt()); x = x shr 7 } while (x > 0)
            for (i in groups.indices.reversed()) out.write(groups[i] or if (i > 0) 0x80 else 0)
        }
        return der(0x06, out.toByteArray())
    }

    /** UTCTime before 2050, GeneralizedTime after (RFC 5280). */
    private fun time(d: Date): ByteArray {
        val utc = TimeZone.getTimeZone("UTC")
        val year = java.util.Calendar.getInstance(utc).apply { time = d }.get(java.util.Calendar.YEAR)
        val (tag, pattern) = if (year < 2050) 0x17 to "yyMMddHHmmss'Z'" else 0x18 to "yyyyMMddHHmmss'Z'"
        val s = SimpleDateFormat(pattern, Locale.US).apply { timeZone = utc }.format(d)
        return der(tag, s.toByteArray(Charsets.US_ASCII))
    }
}
