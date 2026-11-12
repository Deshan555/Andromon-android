// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors
// Ported from SD Maid SE (https://github.com/d4rken-org/sdmaid-se, commit ecbe81b), GPL-3.0,
// Copyright (C) the SD Maid SE authors: SegmentsExtensions.kt, NameCriterium.kt,
// SegmentCriterium.kt and SystemCrawlerSieve.kt, rewritten without coroutines/DI for this app.

package dev.droiddesk.companion

import java.io.File
import java.util.Collections

/** A path split at "/": "/storage/emulated/0/DCIM" -> ["", "storage", "emulated", "0", "DCIM"]. */
internal typealias Segments = List<String>

internal fun segs(vararg s: String): Segments = s.toList()
internal fun String.toSegs(): Segments = split("/")
internal fun Segments.joinSegs(): String = joinToString("/")

// ---------- segment helpers (SD Maid SE SegmentsExtensions.kt) ----------

internal fun Segments?.matchesSegs(other: Segments?, ignoreCase: Boolean = false): Boolean {
    if (this == null) return other == null
    if (this.size != other?.size) return false
    return indices.all { this[it].equals(other[it], ignoreCase) }
}

internal fun Segments?.isAncestorOf(other: Segments?, ignoreCase: Boolean = false): Boolean {
    if (this == null || other == null) return false
    if (this.size >= other.size) return false
    return other.startsWithSegs(this, ignoreCase = ignoreCase, allowPartial = false)
}

internal fun Segments?.containsSegs(other: Segments?, ignoreCase: Boolean = false, allowPartial: Boolean = false): Boolean {
    if (this == null || other == null) return false
    if (this.size < other.size) return false
    return if (allowPartial) {
        if (ignoreCase) joinSegs().lowercase().contains(other.joinSegs().lowercase()) else joinSegs().contains(other.joinSegs())
    } else {
        if (ignoreCase) Collections.indexOfSubList(map { it.lowercase() }, other.map { it.lowercase() }) != -1
        else Collections.indexOfSubList(this, other) != -1
    }
}

internal fun Segments?.startsWithSegs(other: Segments?, ignoreCase: Boolean = false, allowPartial: Boolean = false): Boolean {
    if (this == null || other == null) return false
    if (this.size < other.size) return false
    val a = if (ignoreCase) map { it.lowercase() } else this
    val b = if (ignoreCase) other.map { it.lowercase() } else other
    return when {
        a.isEmpty() -> b.isEmpty()
        b.size == 1 -> if (allowPartial) a.first().startsWith(b.first()) else a.first() == b.first()
        a.size == b.size -> if (allowPartial) a.dropLast(1) == b.dropLast(1) && a.last().startsWith(b.last()) else a == b
        else -> if (allowPartial) {
            a.dropLast(a.size - b.size + 1) == b.dropLast(1) && a[b.size - 1].startsWith(b.last())
        } else {
            other.indices.all { this[it].equals(other[it], ignoreCase) }
        }
    }
}

internal fun Segments?.endsWithSegs(other: Segments?, ignoreCase: Boolean = false, allowPartial: Boolean = false): Boolean {
    if (this == null || other == null) return false
    if (this.size < other.size) return false
    val a = if (ignoreCase) map { it.lowercase() } else this
    val b = if (ignoreCase) other.map { it.lowercase() } else other
    return when {
        a.isEmpty() -> b.isEmpty()
        b.size == 1 -> if (allowPartial) a.last().endsWith(b.last()) else a.last() == b.last()
        a.size == b.size -> if (allowPartial) a.drop(1) == b.drop(1) && a.first().endsWith(b.first()) else a == b
        else -> if (allowPartial) {
            a.drop(a.size - b.size + 1) == b.drop(1) && a[a.size - b.size].endsWith(b.first())
        } else {
            a.subList(a.size - b.size, a.size) == b
        }
    }
}

internal fun Segments.segmentContains(segment: String, index: Int, backwards: Boolean, ignoreCase: Boolean, allowPartial: Boolean): Boolean {
    val target = getOrNull(if (backwards) lastIndex - index else index) ?: return false
    return if (allowPartial) target.contains(segment, ignoreCase) else target.equals(segment, ignoreCase)
}

// ---------- criteria (SD Maid SE NameCriterium.kt / SegmentCriterium.kt) ----------

internal class NameCriterium(val name: String, val mode: Mode) {
    sealed class Mode(val ignoreCase: Boolean) {
        class Start(ignoreCase: Boolean = true) : Mode(ignoreCase)
        class End(ignoreCase: Boolean = true) : Mode(ignoreCase)
        class Contain(ignoreCase: Boolean = true) : Mode(ignoreCase)
        class Equal(ignoreCase: Boolean = true) : Mode(ignoreCase)
    }

    fun match(target: String): Boolean = when (mode) {
        is Mode.Start -> target.startsWith(name, mode.ignoreCase)
        is Mode.End -> target.endsWith(name, mode.ignoreCase)
        is Mode.Contain -> target.contains(name, mode.ignoreCase)
        is Mode.Equal -> target.equals(name, mode.ignoreCase)
    }
}

internal class SegmentCriterium(val segments: Segments, val mode: Mode) {
    constructor(raw: String, mode: Mode) : this(raw.toSegs(), mode)

    sealed class Mode(val ignoreCase: Boolean, val allowPartial: Boolean) {
        class Ancestor(ignoreCase: Boolean = true) : Mode(ignoreCase, false)
        class Start(ignoreCase: Boolean = true, allowPartial: Boolean = false) : Mode(ignoreCase, allowPartial)
        class Contain(ignoreCase: Boolean = true, allowPartial: Boolean = false) : Mode(ignoreCase, allowPartial)
        class End(ignoreCase: Boolean = true, allowPartial: Boolean = false) : Mode(ignoreCase, allowPartial)
        class Equal(ignoreCase: Boolean = true) : Mode(ignoreCase, false)
        class Specific(val index: Int, val backwards: Boolean = false, ignoreCase: Boolean = true, allowPartial: Boolean = false) :
            Mode(ignoreCase, allowPartial)
    }

    fun match(target: Segments): Boolean = when (mode) {
        is Mode.Ancestor -> segments.isAncestorOf(target, mode.ignoreCase)
        is Mode.Start -> target.startsWithSegs(segments, mode.ignoreCase, mode.allowPartial)
        is Mode.End -> target.endsWithSegs(segments, mode.ignoreCase, mode.allowPartial)
        is Mode.Contain -> target.containsSegs(segments, mode.ignoreCase, mode.allowPartial)
        is Mode.Equal -> target.matchesSegs(segments, mode.ignoreCase)
        is Mode.Specific -> target.segmentContains(segments.single(), mode.index, mode.backwards, mode.ignoreCase, mode.allowPartial)
    }
}

internal fun Collection<NameCriterium>.matchAnyName(name: String) = any { it.match(name) }
internal fun Collection<SegmentCriterium>.matchAnySegs(s: Segments) = any { it.match(s) }

// ---------- storage areas (subset of SD Maid SE DataArea) ----------

/** The parts of storage this app can read without root. */
internal enum class AreaType { SDCARD, PUBLIC_DATA, PUBLIC_MEDIA, PUBLIC_OBB, PORTABLE }

internal class Area(val type: AreaType, val root: File) {
    val rootSegs: Segments = root.path.toSegs()
}

/** One file or folder seen by the crawler. [pfp] is the path relative to its area ("prefix-free path"). */
internal class Item(val file: File, val isDir: Boolean, val area: Area) {
    val path: String = file.path
    val name: String = file.name
    val segments: Segments by lazy { path.toSegs() }
    val pfp: Segments by lazy { segments.drop(area.rootSegs.size) }
    val size: Long get() = if (isDir) 0L else file.length()
    val modified: Long get() = file.lastModified()
}

// ---------- the sieve (SD Maid SE SystemCrawlerSieve.kt) ----------

internal class Sieve(
    val areaTypes: Set<AreaType>? = null,
    val dirs: Boolean? = null,               // true: folders only, false: files only, null: both
    val nameCriteria: Set<NameCriterium>? = null,
    val pathCriteria: Set<SegmentCriterium>? = null,
    val pathExclusions: Set<SegmentCriterium>? = null,
    val pathRegexes: Set<Regex>? = null,
    val pfpCriteria: Set<SegmentCriterium>? = null,
    val pfpExclusions: Set<SegmentCriterium>? = null,
    val minimumAgeMs: Long? = null,
) {
    fun match(item: Item): Boolean {
        if (dirs != null && item.isDir != dirs) return false
        if (minimumAgeMs != null && System.currentTimeMillis() - item.modified < minimumAgeMs) return false
        if (!pathCriteria.isNullOrEmpty() && !pathCriteria.matchAnySegs(item.segments)) return false
        if (!nameCriteria.isNullOrEmpty() && !nameCriteria.matchAnyName(item.name)) return false
        if (!pathExclusions.isNullOrEmpty() && pathExclusions.matchAnySegs(item.segments)) return false
        if (!pathRegexes.isNullOrEmpty() && pathRegexes.none { it.matches(item.path) }) return false
        if (!areaTypes.isNullOrEmpty() && item.area.type !in areaTypes) return false
        if (!pfpExclusions.isNullOrEmpty() && pfpExclusions.matchAnySegs(item.pfp)) return false
        if (!pfpCriteria.isNullOrEmpty() && !pfpCriteria.matchAnySegs(item.pfp)) return false
        return true
    }
}
