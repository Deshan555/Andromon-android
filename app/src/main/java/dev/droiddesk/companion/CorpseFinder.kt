// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors
// Ported from SD Maid SE (https://github.com/d4rken-org/sdmaid-se, commit ecbe81b), GPL-3.0,
// Copyright (C) the SD Maid SE authors: CorpseFinder's SdcardCorpseFilter / PublicMediaCorpseFilter,
// SdcardCSI / PublicMediaCSI, OwnerInfo, ManualMarker and ManualMarkerSource. The marker database
// (assets/clutter/db_clutter_markers.json) is SD Maid SE's clutter database.

package dev.droiddesk.companion

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Leftover folders of apps that are no longer installed. On shared storage a folder only counts
 * when SD Maid SE's marker database names the app that made it; in Android/media (and on Android
 * 10 and older Android/data and obb) the folder name is the app's package name.
 */
internal object CorpseFinder {

    class Corpse(val file: File, val owners: Set<String>)

    private enum class Flag { KEEPER, COMMON, CUSTODIAN }

    private class Owner(val pkg: String, val flags: Set<Flag>)

    private class Marker(
        val pkgs: Set<String>,
        val area: AreaType,
        val path: Segments?,
        val contains: String?,
        regex: String?,
        val flags: Set<Flag>,
    ) {
        private val pattern = regex?.let { Regex(it, RegexOption.IGNORE_CASE) } // shared storage is case-insensitive
        val isDirect get() = pattern == null
        val segments: Segments get() = path ?: emptyList()

        fun match(area: AreaType, s: Segments): Boolean {
            if (area != this.area || s.isEmpty()) return false
            return when {
                path != null && pattern == null -> s.matchesSegs(path, ignoreCase = true)
                pattern != null -> when {
                    path != null && !s.startsWithSegs(path, ignoreCase = true, allowPartial = true) -> false
                    contains != null && !s.joinSegs().contains(contains, ignoreCase = true) -> false
                    else -> pattern.matches(s.joinSegs())
                }
                else -> false
            }
        }
    }

    private class OwnerInfo(val file: File, val pfp: Segments, val owners: Set<Owner>, installed: Set<String>, val blacklist: Boolean) {
        val installedOwners = owners.filter { it.pkg in installed }
        val isKeeper get() = owners.any { Flag.KEEPER in it.flags }
        val isCommon get() = owners.any { Flag.COMMON in it.flags }
        val isOwned get() = installedOwners.isNotEmpty()
        val isCorpse get() = if (blacklist) installedOwners.isEmpty() else owners.isNotEmpty() && installedOwners.isEmpty()
    }

    // ---------- marker database ----------

    /**
     * Loads the clutter database (fresh for each scan: groups with package regexes are tied to the
     * apps installed right now).
     */
    private fun markers(ctx: Context, installed: Set<String>): List<Marker> {
        val text = ctx.assets.open("clutter/db_clutter_markers.json").bufferedReader().use { it.readText() }
        val groups = JSONArray(text)
        val out = ArrayList<Marker>()
        for (i in 0 until groups.length()) {
            val g = groups.getJSONObject(i)
            val pkgs = LinkedHashSet<String>()
            g.optJSONArray("pkgs")?.let { a -> for (j in 0 until a.length()) pkgs += a.getString(j) }
            g.optJSONArray("regexPkgs")?.let { a ->
                val regexes = (0 until a.length()).map { a.getString(it) }
                for (rx in regexes) { val r = Regex(rx); installed.filterTo(pkgs) { r.matches(it) } }
                // No installed app matches: keep the regexes as owners so the folder can still be found as leftover.
                if (pkgs.isEmpty()) pkgs += regexes
            }
            val mrks = g.getJSONArray("mrks")
            for (j in 0 until mrks.length()) {
                val m = mrks.getJSONObject(j)
                val area = when (m.getString("loc")) {
                    "SDCARD" -> AreaType.SDCARD
                    "PUBLIC_DATA" -> AreaType.PUBLIC_DATA
                    "PUBLIC_MEDIA" -> AreaType.PUBLIC_MEDIA
                    "PUBLIC_OBB" -> AreaType.PUBLIC_OBB
                    else -> null // private / system areas: need root
                } ?: continue
                val flags = m.optJSONArray("flags")?.let { f ->
                    (0 until f.length()).mapNotNull { runCatching { Flag.valueOf(f.getString(it).uppercase()) }.getOrNull() }.toSet()
                } ?: emptySet()
                out += Marker(
                    pkgs, area,
                    m.optString("path").takeIf { m.has("path") }?.split("/"),
                    m.optString("contains").takeIf { m.has("contains") },
                    m.optString("regex").takeIf { m.has("regex") },
                    flags,
                )
            }
        }
        return out
    }

    private fun ownersFor(markers: List<Marker>, area: AreaType, s: Segments): Set<Owner> =
        markers.filter { it.match(area, s) }.flatMap { m -> m.pkgs.map { Owner(it, m.flags) } }.toSet()

    // ---------- scan ----------

    /**
     * @param installed every package installed for this user. Callers must make sure the list is
     * complete (package visibility): a missing app would make its folders look like leftovers.
     */
    fun scan(ctx: Context, areas: List<Area>, installed: Set<String>, progress: (String) -> Unit,
             includeKeeper: Boolean = false, includeCommon: Boolean = false): List<Corpse> {
        val markers = markers(ctx, installed)
        val candidates = ArrayList<OwnerInfo>()
        for (area in areas) {
            progress("Looking for leftovers in ${area.root.name}…")
            when (area.type) {
                AreaType.SDCARD, AreaType.PORTABLE -> candidates += sdcard(area, markers, installed)
                else -> candidates += packageFolders(area, markers, installed)
            }
        }
        return check(candidates, includeKeeper, includeCommon).map { c -> Corpse(c.file, c.owners.map { it.pkg }.toSet()) }
    }

    /** SdcardCorpseFilter: top-level items by owner lookup, plus nested folders named by direct markers. */
    private fun sdcard(area: Area, markers: List<Marker>, installed: Set<String>): List<OwnerInfo> {
        val sdMarkers = markers.filter { it.area == AreaType.SDCARD }
        val top = area.root.listFiles()?.toList() ?: return emptyList()
        val result = ArrayList<OwnerInfo>()
        for (f in top) {
            // Chop the path down until a marker matches (SdcardCSI.findOwners).
            var bestBet = listOf(f.name)
            var owners = emptySet<Owner>()
            while (bestBet.isNotEmpty()) {
                owners = ownersFor(sdMarkers, AreaType.SDCARD, bestBet)
                if (owners.isNotEmpty()) break
                bestBet = bestBet.dropLast(1)
            }
            result += OwnerInfo(f, listOf(f.name), owners, installed, blacklist = false)
        }
        val topNames = top.map { it.name.lowercase() }.toSet()
        val nested = HashMap<String, Pair<File, MutableSet<Owner>>>()
        for (m in sdMarkers) {
            if (!m.isDirect || m.segments.size <= 1) continue
            if (m.segments.first().lowercase() !in topNames) continue // its parent doesn't exist
            val raw = File(area.root, m.segments.joinSegs())
            if (!raw.exists()) continue
            // Shared storage is case-insensitive; use the folder's real name.
            val real = raw.parentFile?.listFiles()?.singleOrNull { it.path != raw.path && it.path.equals(raw.path, ignoreCase = true) } ?: raw
            if (!real.canRead()) continue
            val pfp = real.path.toSegs().drop(area.rootSegs.size)
            if (!m.match(AreaType.SDCARD, pfp)) continue
            nested.getOrPut(real.path) { real to HashSet() }.second += m.pkgs.map { Owner(it, m.flags) }
        }
        for ((_, v) in nested) result += OwnerInfo(v.first, v.first.path.toSegs().drop(area.rootSegs.size), v.second, installed, blacklist = false)
        return result
    }

    /** PublicMediaCSI (and PublicData/Obb): the folder name is the package, possibly hidden with "." or "_". */
    private fun packageFolders(area: Area, markers: List<Marker>, installed: Set<String>): List<OwnerInfo> {
        val result = ArrayList<OwnerInfo>()
        for (f in area.root.listFiles() ?: return emptyList()) {
            if (f.name == ".nomedia") continue
            val name = f.name
            val hidden = when {
                name.startsWith(".external.") -> name.substring(10)
                name.startsWith("_") || name.startsWith(".") -> name.substring(1)
                else -> null
            }
            var owners: Set<Owner> = when {
                name in installed -> setOf(Owner(name, emptySet()))
                hidden != null && hidden in installed -> setOf(Owner(hidden, emptySet()))
                else -> emptySet()
            }
            if (owners.isEmpty()) owners = ownersFor(markers, area.type, listOf(name))
            if (owners.isEmpty()) owners = setOf(Owner(hidden ?: name, emptySet()))
            result += OwnerInfo(f, listOf(name), owners, installed, blacklist = true)
        }
        return result
    }

    /** SdcardCorpseFilter.checkPotentialCorpses: alive items protect their ancestors; nested corpses fold into parents. */
    private fun check(candidates: List<OwnerInfo>, includeKeeper: Boolean, includeCommon: Boolean): List<OwnerInfo> {
        val dead = ArrayList<OwnerInfo>()
        val alive = ArrayList<OwnerInfo>()
        for (c in candidates) when {
            c.isOwned -> alive += c
            c.isKeeper && !includeKeeper -> alive += c
            c.isCommon && !includeCommon -> alive += c
            c.isCorpse -> dead += c
        }
        fun File.ancestorOf(o: File) = o.path.startsWith(path + "/")
        dead.removeAll { d -> alive.any { d.file.ancestorOf(it.file) } }
        val covered = dead.filter { d -> dead.any { it !== d && it.file.ancestorOf(d.file) } }
        dead.removeAll(covered.toSet())
        return dead
    }
}
