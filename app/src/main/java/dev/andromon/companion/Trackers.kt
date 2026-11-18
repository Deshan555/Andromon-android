// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Apps › Trackers: which known tracking and advertising libraries an installed app contains.
 *
 * Works like Exodus Privacy's own analysis, on the phone: the class names in the app's code
 * (classes*.dex inside its APK files) are compared with the code signatures Exodus publishes
 * (res/raw/exodus_trackers.tsv). Only names are read; nothing is run, changed or sent. A tracker
 * found in the code isn't proof that it is switched on, and code that hides its names isn't found.
 */
internal object Trackers {

    class Tracker(val name: String, val categories: String)

    private var byPrefix: Map<String, Tracker>? = null

    private fun signatures(ctx: Context): Map<String, Tracker> = synchronized(this) {
        byPrefix ?: HashMap<String, Tracker>().also { map ->
            ctx.resources.openRawResource(R.raw.exodus_trackers).bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("#")) continue
                    val p = line.split('\t')
                    if (p.size < 3) continue
                    val t = Tracker(p[0], p[1].replace(",", ", "))
                    for (prefix in p[2].split('|')) map[prefix] = t
                }
            }
            byPrefix = map
        }
    }

    /** How many trackers the list knows. */
    fun known(ctx: Context) = signatures(ctx).values.distinctBy { it.name }.size

    /** The trackers found in the app's [apks] (base and split APKs), by name. Slow for big apps: call off the main thread. */
    fun scan(ctx: Context, apks: List<String>): List<Tracker> {
        val sigs = signatures(ctx)
        val found = LinkedHashMap<String, Tracker>()
        for (path in apks) {
            runCatching {
                ZipFile(File(path)).use { zip ->
                    for (e in zip.entries()) {
                        if (!e.name.startsWith("classes") || !e.name.endsWith(".dex") || e.name.contains('/')) continue
                        val dex = zip.getInputStream(e).use { it.readBytes() }
                        classNames(dex) { name -> match(name, sigs)?.let { found[it.name] = it } }
                    }
                }
            }
        }
        return found.values.sortedBy { it.name.lowercase() }
    }

    /** A signature is the start of a package or class name: try every cut at a dot, with and without it. */
    private fun match(cls: String, sigs: Map<String, Tracker>): Tracker? {
        var i = cls.indexOf('.')
        while (i >= 0) {
            sigs[cls.substring(0, i + 1)]?.let { return it }
            sigs[cls.substring(0, i)]?.let { return it }
            i = cls.indexOf('.', i + 1)
        }
        return sigs[cls]
    }

    /**
     * Every class or interface name a dex file mentions, dotted ("com.example.Foo"). Reads the
     * type table of the dex format (header: type_ids at 0x40, string_ids at 0x38).
     */
    private fun classNames(dex: ByteArray, each: (String) -> Unit) {
        if (dex.size < 0x70 || dex[0] != 'd'.code.toByte() || dex[1] != 'e'.code.toByte() || dex[2] != 'x'.code.toByte()) return
        val b = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
        val stringCount = b.getInt(0x38); val stringOff = b.getInt(0x3C)
        val typeCount = b.getInt(0x40); val typeOff = b.getInt(0x44)
        if (stringCount < 0 || typeCount < 0 || stringOff < 0 || typeOff < 0) return
        if (typeOff.toLong() + typeCount * 4L > dex.size || stringOff.toLong() + stringCount * 4L > dex.size) return
        val sb = StringBuilder(96)
        for (t in 0 until typeCount) {
            val s = b.getInt(typeOff + t * 4)
            if (s < 0 || s >= stringCount) continue
            var p = b.getInt(stringOff + s * 4)
            if (p <= 0 || p >= dex.size) continue
            while (p < dex.size && dex[p].toInt() and 0x80 != 0) p++ // skip the length (uleb128)
            p++
            if (p >= dex.size || dex[p] != 'L'.code.toByte()) continue // only classes, not int, arrays…
            p++
            sb.setLength(0)
            while (p < dex.size) {
                val c = dex[p].toInt()
                if (c == 0 || c == ';'.code) break
                sb.append(if (c == '/'.code) '.' else (c and 0xff).toChar())
                p++
            }
            each(sb.toString())
        }
    }
}
