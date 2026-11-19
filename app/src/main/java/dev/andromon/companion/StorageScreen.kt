// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.nio.file.Files
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Storage analyzer: a folder-size browser for internal storage and SD cards. Folder sizes are
 * added up in the background (symlinks are not followed), shown largest first with proportional
 * bars; tap a folder to go into it, a file to open it. Deleting always asks first.
 */
internal class StorageScreen(host: MainActivity) : Screen(host) {

    override val name = "Storage"
    override val navIcon = R.drawable.ic_storage
    override val navColor = R.color.bg

    private companion object {
        const val REQ_STORAGE = 12
        const val MAX_ROWS = 150
    }

    /** One folder in the tree. Only folders are kept in memory; files are listed when shown. */
    private class Node(val file: File, val parent: Node?) {
        @Volatile var dirs: List<Node>? = null
        @Volatile var fileBytes = 0L
        @Volatile var fileCount = 0
        @Volatile var size = 0L
        @Volatile var files = 0
        @Volatile var done = false
        @Volatile var unreadable = false
        val claimed = AtomicBoolean(false)
    }

    private class Cancelled : RuntimeException()

    /** One measuring run over a volume; a newer run (refresh, other volume) cancels it. */
    private inner class Scan(val volume: StorageVolumeInfo) {
        val root = Node(volume.dir!!, null)
        val measured = AtomicLong()
        @Volatile var cancelled = false

        fun list(n: Node) = synchronized(n) {
            if (n.dirs != null) return@synchronized
            val kids = n.file.listFiles()
            if (kids == null) { n.unreadable = true; n.dirs = emptyList(); return@synchronized }
            val d = ArrayList<Node>()
            var bytes = 0L
            var count = 0
            for (k in kids) {
                if (Files.isSymbolicLink(k.toPath())) continue
                if (k.isDirectory) d += Node(k, n) else { bytes += k.length(); count++ }
            }
            n.fileBytes = bytes; n.fileCount = count
            measured.addAndGet(bytes)
            n.dirs = d
        }

        /** Adds up [n]. If another thread already works on it, waits for that instead. */
        fun measure(n: Node) {
            if (cancelled) throw Cancelled()
            if (!n.claimed.compareAndSet(false, true)) {
                while (!n.done) { if (cancelled) throw Cancelled(); Thread.sleep(40) }
                return
            }
            list(n)
            val kids = n.dirs!!
            for (k in kids) measure(k)
            n.size = n.fileBytes + kids.sumOf { it.size }
            n.files = n.fileCount + kids.sumOf { it.files }
            n.done = true
        }

        fun start(n: Node) = thread(isDaemon = true) { try { measure(n) } catch (_: Cancelled) { } }
    }

    private class FileEntry(val file: File, val size: Long, val modified: Long)

    private var volumes: List<StorageVolumeInfo> = emptyList()
    private var scan: Scan? = null
    private var cur: Node? = null
    private var curFiles: List<FileEntry> = emptyList()
    private var shownState = -1
    private var visible = false
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            val s = scan ?: return
            if (!visible) return
            updateSummary()
            if (state() != shownState) renderList()
            if (!s.root.done) handler.postDelayed(this, 500)
        }
    }

    private lateinit var accessCard: View
    private lateinit var content: LinearLayout
    private lateinit var volumeRow: LinearLayout
    private lateinit var crumbs: LinearLayout
    private lateinit var crumbScroll: HorizontalScrollView
    private lateinit var title: TextView
    private lateinit var summary: TextView
    private lateinit var hint: TextView
    private lateinit var list: LinearLayout

    override fun build(): View = ui.column {
        addView(ui.topBar("Storage", { if (!onBack()) host.goHome() }, R.drawable.ic_refresh, "Measure again") {
            scan?.let { open(it.volume, cur?.file) }
        }.first)
        accessCard = accessCard()
        addView(accessCard, ui.lp(top = 20))
        content = ui.column {
            volumeRow = ui.row()
            addView(volumeRow, ui.lp(top = 18))
            crumbs = ui.row()
            crumbScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(crumbs)
            }
            addView(crumbScroll, ui.lp(top = 14))
            addView(ui.heroCard(16) {
                title = ui.text(22f, color(R.color.ink), ui.light) { letterSpacing = -0.02f; maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE }
                addView(title)
                summary = ui.text(14f, color(R.color.ink), ui.medium)
                addView(summary, ui.lp(top = 6))
                hint = ui.text(12f, color(R.color.ink_soft)) { setLineSpacing(ui.dp(2).toFloat(), 1f) }
                addView(hint, ui.lp(top = 6))
            }, ui.lp(top = 12))
            list = ui.card(color(R.color.surface), 6)
            list.addView(ui.skeletonList(6, 40, lines = 3, squareIcon = true))
            addView(list, ui.lp(top = 12))
        }
        addView(content)
    }

    override fun onShow() {
        visible = true
        val access = Cleaner.hasFileAccess(host)
        accessCard.visibility = if (access) View.GONE else View.VISIBLE
        content.visibility = if (access) View.VISIBLE else View.GONE
        if (!access) return
        if (scan == null) {
            background({ Cleaner.volumes(host).filter { it.dir != null } }) { r ->
                volumes = r.getOrDefault(emptyList())
                if (scan == null) volumes.firstOrNull()?.let { open(it, null) }
            }
        } else {
            handler.removeCallbacks(poll)
            poll.run()
        }
    }

    override fun onHide() {
        visible = false
        handler.removeCallbacks(poll)
    }

    override fun onPermissionsResult() = onShow()

    /** Back goes up one folder first; from the top of a volume it leaves the screen. */
    override fun onBack(): Boolean {
        val up = cur?.parent ?: return false
        show(up)
        return true
    }

    // ---------- access ----------

    private fun accessCard() = ui.glassCard(16) {
        addView(ui.row {
            addView(ui.iconTile(R.drawable.ic_folder, color(R.color.cat_yellow), color(R.color.yellow_tile), 46, 22),
                ui.lp(w = ui.dp(46), h = ui.dp(46)))
            addView(ui.column {
                addView(ui.text(16f, color(R.color.ink), ui.medium) { text = "Allow access to files" })
                addView(ui.text(13f, color(R.color.ink_soft)) {
                    text = "Needed to measure the folders on your storage and SD card. Nothing is deleted unless you choose it and confirm."
                    setLineSpacing(ui.dp(2).toFloat(), 1f)
                }, ui.lp(top = 4))
            }, ui.lp(w = 0, weight = 1f, start = 14))
        })
        addView(ui.pill("Allow", color(R.color.fab), color(R.color.on_fab)) {
            if (Build.VERSION.SDK_INT >= 30) {
                val pkg = Uri.fromParts("package", host.packageName, null)
                runCatching { host.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg)) }
                    .onFailure { runCatching { host.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
            } else {
                host.requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            }
        }, ui.lp(top = 14))
    }

    // ---------- navigation ----------

    /** Starts measuring [volume] from scratch and shows [target] (or the nearest folder that still exists). */
    private fun open(volume: StorageVolumeInfo, target: File?) {
        scan?.cancelled = true
        val s = Scan(volume)
        scan = s
        cur = null
        s.start(s.root)
        renderVolumes()
        background({
            var node = s.root
            val rootPath = s.root.file.path
            if (target != null && target.path.startsWith("$rootPath/")) {
                for (part in target.path.removePrefix("$rootPath/").split('/')) {
                    s.list(node)
                    node = node.dirs?.firstOrNull { it.file.name == part } ?: break
                }
            }
            node
        }) { r -> if (scan === s) show(r.getOrDefault(s.root)) }
    }

    private fun show(node: Node) {
        val s = scan ?: return
        cur = node
        curFiles = emptyList()
        shownState = -1
        renderCrumbs()
        title.text = if (node.parent == null) s.volume.name else node.file.name
        list.removeAllViews()
        list.addView(ui.skeletonList(6, 40, lines = 3, squareIcon = true))
        updateSummary()
        host.scrollToTop()
        if (!node.claimed.get()) s.start(node) // measure what's on screen first
        background({
            s.list(node)
            node.file.listFiles()?.filter { !it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
                ?.map { FileEntry(it, it.length(), it.lastModified()) } ?: emptyList()
        }) { r ->
            if (scan !== s || cur !== node) return@background
            curFiles = r.getOrDefault(emptyList())
            renderList()
            handler.removeCallbacks(poll)
            poll.run()
        }
    }

    /** Changes whenever the list needs redrawing: how many of the shown folders are measured. */
    private fun state(): Int {
        val n = cur ?: return -1
        val dirs = n.dirs ?: return -1
        return dirs.count { it.done } * 2 + (if (n.done) 1 else 0)
    }

    private fun renderVolumes() {
        volumeRow.removeAllViews()
        volumeRow.visibility = if (volumes.size > 1) View.VISIBLE else View.GONE
        for ((i, v) in volumes.withIndex()) {
            val on = v.dir?.path == scan?.volume?.dir?.path
            volumeRow.addView(ui.pill(v.name, color(if (on) R.color.fab else R.color.glass_strong),
                color(if (on) R.color.on_fab else R.color.ink), if (v.removable) R.drawable.ic_sd else null) {
                if (!on) open(v, null)
            }.apply { textSize = 13f; minHeight = ui.dp(38); maxLines = 1 }, ui.lp(w = 0, weight = 1f, start = if (i == 0) 0 else 8))
        }
    }

    private fun renderCrumbs() {
        crumbs.removeAllViews()
        val chain = generateSequence(cur) { it.parent }.toList().reversed()
        for ((i, n) in chain.withIndex()) {
            if (i > 0) crumbs.addView(ui.iconView(R.drawable.ic_chevron_right, color(R.color.ink_soft)),
                ui.lp(w = ui.dp(18), h = ui.dp(18)))
            val last = i == chain.lastIndex
            val label = if (n.parent == null) scan?.volume?.name ?: "Storage" else n.file.name
            crumbs.addView(ui.pill(label, color(if (last) R.color.glass_strong else R.color.glass), color(R.color.ink)) {
                if (!last) show(n)
            }.apply { textSize = 13f; minHeight = ui.dp(34); setPadding(ui.dp(14), 0, ui.dp(14), 0); maxLines = 1 },
                ui.lp(w = WRAP_CONTENT))
        }
        crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun updateSummary() {
        val s = scan ?: return
        val n = cur ?: return
        summary.text = when {
            n.unreadable -> "Can't be read"
            n.done -> "${fmtSize(n.size)} · ${count(n.files, "file")}"
            else -> "Measuring… ${fmtSize(s.measured.get())} found on this storage so far"
        }
        hint.text = when {
            n.unreadable -> "Android doesn't let apps look into this folder" +
                (if (Build.VERSION.SDK_INT >= 30) " (Android/data and Android/obb are closed to apps since Android 11)." else ".")
            n.parent == null -> "${fmtSize(s.volume.total - s.volume.free)} of ${fmtSize(s.volume.total)} is in use on this storage. " +
                "Folders here add up to less: installed apps, their private data and Android itself aren't visible to a file browser."
            else -> "Tap a folder to go in, a file to open it. The three dots have more."
        }
        hint.visibility = View.VISIBLE
    }

    // ---------- list ----------

    private class Row(val name: String, val size: Long?, val dir: Node?, val file: FileEntry?)

    private fun renderList() {
        val n = cur ?: return
        shownState = state()
        list.removeAllViews()
        n.parent?.let { up ->
            list.addView(ui.row {
                background = ui.pressable(ui.rounded(color(R.color.surface), 18))
                setPadding(ui.dp(10), ui.dp(10), ui.dp(8), ui.dp(10))
                addView(ui.roundIcon(40, color(R.color.bg), R.drawable.ic_up, color(R.color.ink), 18), ui.lp(w = ui.dp(40), h = ui.dp(40)))
                addView(ui.text(14f, color(R.color.ink_soft)) {
                    text = "Up to " + if (up.parent == null) scan?.volume?.name else up.file.name
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
                }, ui.lp(w = 0, weight = 1f, start = 12))
                setOnClickListener { show(up) }
            })
        }
        val rows = (n.dirs.orEmpty().map { Row(it.file.name, if (it.done) it.size else null, it, null) } +
            curFiles.map { Row(it.file.name, it.size, null, it) })
            .sortedWith(compareByDescending<Row> { it.size ?: -1L }.thenBy { it.name.lowercase() })
        // Bars show each item's share of this folder; while it's still being measured, of the biggest so far.
        val whole = (if (n.done) n.size else rows.maxOfOrNull { it.size ?: 0L } ?: 0L).coerceAtLeast(1L)
        for (r in rows.take(MAX_ROWS)) list.addView(rowView(r, whole, n.done))
        if (rows.size > MAX_ROWS) list.addView(note("+ ${rows.size - MAX_ROWS} smaller items not listed"))
        if (rows.isEmpty()) list.addView(note(if (n.unreadable) "Nothing to show." else "This folder is empty."))
    }

    private fun rowView(r: Row, whole: Long, exact: Boolean) = ui.row {
        background = ui.pressable(ui.rounded(color(R.color.surface), 18))
        setPadding(ui.dp(10), ui.dp(10), ui.dp(2), ui.dp(10))
        val isDir = r.dir != null
        addView(ui.iconTile(if (isDir) R.drawable.ic_folder else R.drawable.ic_file,
            color(if (isDir) R.color.cat_yellow else R.color.cat_blue),
            color(if (isDir) R.color.yellow_tile else R.color.blue_tile), 40, 20), ui.lp(w = ui.dp(40), h = ui.dp(40)))
        addView(ui.column {
            addView(ui.row {
                addView(ui.text(14f, color(R.color.ink), ui.medium) {
                    text = r.name; maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
                }, ui.lp(w = 0, weight = 1f))
                if (r.size == null && r.dir?.unreadable != true) // still being measured
                    addView(ui.spinner(16), ui.lp(w = ui.dp(16), h = ui.dp(16), start = 8))
                else addView(ui.text(13f, color(R.color.ink), ui.medium) {
                    text = if (r.dir?.unreadable == true) "–" else fmtSize(r.size ?: 0L)
                }, ui.lp(w = WRAP_CONTENT, start = 8))
            })
            addView(Meter(context).apply {
                colors(color(R.color.bg), color(if (isDir) R.color.cat_yellow else R.color.cat_blue))
                fraction = if (r.size == null) 0f else r.size.toFloat() / whole
            }, ui.lp(h = ui.dp(6), top = 6))
            addView(ui.text(12f, color(R.color.ink_soft)) {
                val share = if (exact && r.size != null) "${(r.size * 100 / whole).coerceAtMost(100)}% · " else ""
                text = when {
                    r.dir?.unreadable == true -> "Closed to apps by Android"
                    r.dir != null -> if (r.dir.done) share + count(r.dir.files, "file") else "Folder"
                    else -> share + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(r.file!!.modified))
                }
                maxLines = 1
            }, ui.lp(top = 4))
        }, ui.lp(w = 0, weight = 1f, start = 12))
        addView(ui.iconView(R.drawable.ic_more, color(R.color.ink_soft)).apply {
            val p = ui.dp(8); setPadding(p, p, p, p)
            background = ui.pressable(ui.circle(android.graphics.Color.TRANSPARENT))
            contentDescription = "More for ${r.name}"
            setOnClickListener { menu(r) }
        }, ui.lp(w = ui.dp(38), h = ui.dp(38), start = 2))
        setOnClickListener { if (r.dir != null) show(r.dir) else openFile(r.file!!.file) }
    }

    private fun note(t: String) = ui.text(14f, color(R.color.ink_soft)) {
        text = t
        setPadding(ui.dp(12), ui.dp(16), ui.dp(12), ui.dp(16))
    }

    private fun count(n: Int, word: String) = "%,d %s".format(n, word) + if (n == 1) "" else "s"

    // ---------- actions ----------

    private fun menu(r: Row) {
        val target = r.dir?.file ?: r.file!!.file
        val options = buildList<Pair<String, () -> Unit>> {
            if (r.dir != null) add("Open folder" to { show(r.dir) }) else add("Open" to { openFile(target) })
            if (Archives.isZip(target)) add("Extract here" to { extract(target, "") })
            add("Compress to zip…" to { askCompress(target) })
            add("Delete…" to { confirmDelete(r, target) })
        }
        AlertDialog.Builder(host)
            .setTitle(r.name)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- zip archives (Archives.kt) ----------

    private fun passwordBox(hintText: String) = android.widget.EditText(host).apply {
        hint = hintText
        isSingleLine = true
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun boxed(v: View) = android.widget.FrameLayout(host).apply {
        setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
        addView(v)
    }

    private fun askCompress(target: File) {
        val box = passwordBox("Password (leave empty for none)")
        AlertDialog.Builder(host)
            .setTitle("Compress “${target.name}”")
            .setMessage("Makes a zip file next to it. The original stays where it is.")
            .setView(boxed(box))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Compress") { _, _ -> archiveJob("Compressing…") { Archives.compress(target, box.text.toString()) } }
            .show()
    }

    private fun extract(zip: File, password: String) {
        if (password.isEmpty() && Archives.encrypted(zip)) { askPassword(zip, false); return }
        archiveJob("Extracting…", onWrongPassword = { askPassword(zip, true) }) { Archives.extract(zip, password) }
    }

    private fun askPassword(zip: File, wrong: Boolean) {
        val box = passwordBox("Password")
        AlertDialog.Builder(host)
            .setTitle(if (wrong) "Wrong password" else "Password needed")
            .setMessage("“${zip.name}” is protected with a password.")
            .setView(boxed(box))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Extract") { _, _ -> box.text.toString().takeIf { it.isNotEmpty() }?.let { extract(zip, it) } }
            .show()
    }

    /** Runs [work] off the main thread, then measures the folder again so the new zip or folder shows up. */
    private fun archiveJob(label: String, onWrongPassword: (() -> Unit)? = null, work: () -> File) {
        val s = scan ?: return
        val here = cur?.file
        list.removeAllViews()
        list.addView(note(label))
        background({
            work().also { made ->
                val paths = if (made.isDirectory) made.walkTopDown().filter { it.isFile }.map { it.path }.take(2000).toList() else listOf(made.path)
                paths.chunked(500).forEach { MediaScannerConnection.scanFile(host, it.toTypedArray(), null, null) }
            }
        }) { res ->
            val err = res.exceptionOrNull()
            when {
                err is Archives.NeedsPassword && onWrongPassword != null -> onWrongPassword()
                err != null -> toast("That didn't work: ${err.message ?: "the archive is damaged or there is no space left"}")
                else -> toast("Made “${res.getOrNull()?.name}”")
            }
            if (scan === s) open(s.volume, here)
        }
    }

    /** Hands the file to another app through Android's media index (apps may not share file paths directly). */
    private fun openFile(f: File) {
        val ext = f.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        MediaScannerConnection.scanFile(host, arrayOf(f.path), arrayOf(mime).takeIf { mime != "*/*" }) { _, uri ->
            host.runOnUiThread {
                if (host.isDestroyed) return@runOnUiThread
                if (uri == null) { toast("Android couldn't hand this file to another app."); return@runOnUiThread }
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { host.startActivity(Intent.createChooser(view, "Open ${f.name}")) }
                    .onFailure { toast("No app on this phone opens this kind of file.") }
            }
        }
    }

    private fun confirmDelete(r: Row, target: File) {
        val s = scan ?: return
        if (r.dir != null && r.dir.parent?.parent == null && target.name == "Android") {
            AlertDialog.Builder(host).setTitle("Not this one")
                .setMessage("Android keeps apps' files in this folder. Open it and delete single folders inside instead.")
                .setPositiveButton("OK", null).show()
            return
        }
        val what = when {
            r.dir == null -> "This file (${fmtSize(r.size ?: 0)})"
            r.dir.done -> "This folder and everything in it (${fmtSize(r.dir.size)}, ${count(r.dir.files, "file")})"
            else -> "This folder and everything in it (still being measured)"
        }
        AlertDialog.Builder(host)
            .setTitle("Delete “${r.name}”?")
            .setMessage("$what will be deleted for good. It doesn't go to a recycle bin and can't be recovered.\n\n${target.path}")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val here = cur?.file
                list.removeAllViews()
                list.addView(note("Deleting…"))
                background({
                    val removed = ArrayList<String>()
                    val ok = deleteTree(target, removed)
                    // Tell the media index, so galleries and music apps don't keep broken entries.
                    removed.chunked(500).forEach { MediaScannerConnection.scanFile(host, it.toTypedArray(), null, null) }
                    ok
                }) { res ->
                    if (res.getOrDefault(false) != true)
                        toast("Some of it couldn't be deleted (in use, or on a read-only SD card).")
                    if (scan === s) open(s.volume, here) // sizes changed: measure again, stay in this folder
                }
            }
            .show()
    }

    /** Deletes [f] and, for folders, what's inside. Symlinks are removed, never followed. */
    private fun deleteTree(f: File, removed: MutableList<String>): Boolean {
        val link = Files.isSymbolicLink(f.toPath())
        if (!link && f.isDirectory) f.listFiles()?.forEach { deleteTree(it, removed) }
        val wasFile = link || !f.isDirectory
        val ok = f.delete() || !f.exists()
        if (ok && wasFile) removed += f.path
        return ok
    }

    private fun toast(msg: String) = Toast.makeText(host, msg, Toast.LENGTH_LONG).show()
}
