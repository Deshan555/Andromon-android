// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

/**
 * Finds personal data in a piece of text, for Settings › Display › Blur personal data
 * ([PrivacyBlur]). Ported from the PC app's detector (pc/src/pii.js) so both apps agree; change
 * them together. Two ways in:
 *  - patterns: IMEI/MEID, SIM number (ICCID), phone numbers, emails, IPv4/IPv6 and MAC addresses,
 *    GPS coordinates;
 *  - known values: names this app has shown (computers, Wi‑Fi devices), registered with [addKnown]
 *    and matched exactly wherever they show up.
 */
internal object Pii {

    class Hit(val start: Int, val end: Int, val kind: String)

    private val known = LinkedHashSet<String>()
    /** Goes up whenever a known value is added, so cached answers can be checked again. */
    @Volatile var version = 0
        private set

    /** Adds exact values (strings of 4+ characters) to find wherever they appear. */
    fun addKnown(vararg values: String?) {
        for (v in values) {
            val s = v?.trim().orEmpty()
            if (s.length < 4 || PLACEHOLDER.matches(s)) continue
            synchronized(known) { if (known.add(s)) version++ }
        }
    }

    private val PLACEHOLDER = Regex("^(unknown|null|none|0+)$", RegexOption.IGNORE_CASE)

    private val PATTERNS = listOf(
        "email" to Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+"""),
        "mac" to Regex("""\b[0-9a-f]{2}(?:[:-][0-9a-f]{2}){5}\b""", RegexOption.IGNORE_CASE),
        "ipv6" to Regex("""(?:\b[0-9a-f]{1,4})?(?:::?[0-9a-f]{1,4}){2,7}\b""", RegexOption.IGNORE_CASE),
        "ipv4" to Regex("""\b(?:25[0-5]|2[0-4]\d|1?\d?\d)(?:\.(?:25[0-5]|2[0-4]\d|1?\d?\d)){3}\b"""),
        "coords" to Regex("""-?\b\d{1,2}\.\d{4,}\s*,\s*-?\d{1,3}\.\d{4,}\b"""),
        "meid" to Regex("""\b[0-9A-F]{14}\b"""), // hex MEID; all-digit 14s are caught as numbers below
        // IMEI (15), ICCID (19–20) and phone numbers: runs of digits with the usual separators.
        "number" to Regex("""(?:\+|\(|\b)\d(?:[\s\-()]{0,2}\d){6,19}\b"""),
    )
    private val DATE = Regex("""^\d{4}-\d{2}-\d{2}$|^\d{2}[-/]\d{2}[-/]\d{4}$""")
    private val UNIT_AFTER = Regex("""^\s?(?:[kKMGT]?B|%|ms|px|dpi|mAh|Hz)\b""")

    /** A run of digits that is a date, a time, a size or a version, not a phone number. */
    private fun notANumber(m: String, text: String, at: Int): Boolean {
        val d = m.count { it in '0'..'9' }
        if (d < 7 || d > 20) return true
        if (DATE.containsMatchIn(m.trim())) return true // dates
        val before = text.substring(maxOf(0, at - 1), at)
        val after = text.substring(at + m.length, minOf(text.length, at + m.length + 3))
        if (before.any { it in ".:," } || Regex("""^[.:]\d""").containsMatchIn(after)) return true // part of a version, decimal or time
        if (UNIT_AFTER.containsMatchIn(after)) return true
        return false
    }

    /** Every piece of personal data in [text], sorted and not overlapping. */
    fun find(text: CharSequence?): List<Hit> {
        val s = text?.toString().orEmpty()
        if (s.length < 4) return emptyList()
        val hits = ArrayList<Hit>()
        for (v in synchronized(known) { known.toList() }) {
            var i = s.indexOf(v)
            while (i >= 0) { hits += Hit(i, i + v.length, "known"); i = s.indexOf(v, i + v.length) }
        }
        for ((kind, re) in PATTERNS) {
            for (m in re.findAll(s)) {
                val v = m.value
                if (kind == "number" && notANumber(v, s, m.range.first)) continue
                if (kind == "meid" && v.none { it in 'A'..'F' }) continue
                if (kind == "ipv6" && "::" !in v && v.split(':').size < 5) continue // 10:45:22 is a time
                hits += Hit(m.range.first, m.range.last + 1, kind)
            }
        }
        hits.sortWith(compareBy<Hit> { it.start }.thenByDescending { it.end })
        val out = ArrayList<Hit>()
        for (h in hits) if (out.isEmpty() || h.start >= out.last().end) out += h
        return out
    }

    fun has(text: CharSequence?) = find(text).isNotEmpty()
}
