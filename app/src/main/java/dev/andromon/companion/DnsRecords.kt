// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.content.Context
import android.net.ConnectivityManager
import org.xbill.DNS.DClass
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Record
import org.xbill.DNS.ReverseMap
import org.xbill.DNS.Section
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.Type
import java.net.InetAddress
import java.time.Duration

/**
 * Network › Look up: the DNS records Android's own resolver doesn't show (mail servers, name
 * servers, text records, aliases…), asked with dnsjava straight from the DNS server of the
 * network the phone is on. For an IP address it asks for the name instead (reverse lookup).
 */
internal object DnsRecords {
    private val TYPES = listOf(Type.CNAME, Type.MX, Type.NS, Type.TXT, Type.SOA, Type.CAA)
    private val IP = Regex("^[0-9.]+$|^[0-9A-Fa-f:]*:[0-9A-Fa-f:.]*$")

    /** One line per record ("MX     10 mail.example.com"), then which server answered. Empty when there is nothing to add. */
    fun lookup(ctx: Context, host: String): List<String> {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val server = cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.firstOrNull() ?: return emptyList()
        val resolver = SimpleResolver(server).apply { timeout = Duration.ofSeconds(3) }
        val clean = host.trim('[', ']')
        val reverse = IP.matches(clean)
        val name = if (reverse) ReverseMap.fromAddress(InetAddress.getByName(clean)) else Name.fromString(clean, Name.root)
        val out = ArrayList<String>()
        for (type in if (reverse) listOf(Type.PTR) else TYPES) {
            val reply = try { resolver.send(Message.newQuery(Record.newRecord(name, type, DClass.IN))) } catch (_: Exception) { continue }
            for (r in reply.getSection(Section.ANSWER)) {
                if (r.type != type) continue // e.g. the alias that led to the answer
                val label = if (type == Type.PTR) "Name" else Type.string(type)
                out += label.padEnd(7) + r.rdataToString().removeSuffix(".").replace(". ", " ")
            }
        }
        if (out.isNotEmpty()) out += "Records from ${server.hostAddress}"
        return out
    }
}
