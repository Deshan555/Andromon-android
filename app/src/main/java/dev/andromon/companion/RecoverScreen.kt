// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Recover: deleted photos, videos and files that are still on the phone (see [Recovery]). Opened from Home. */
internal class RecoverScreen(host: MainActivity) : Screen(host) {

    override val name = "Recover"
    override val navIcon = R.drawable.ic_trash
    override val navColor = R.color.bg

    private companion object {
        const val SHOWN_PER_KIND = 150
    }

    private lateinit var access: View
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var recoverPill: TextView
    private var found: List<Recovery.Found> = emptyList()
    private var scanning: AtomicBoolean? = null
    private var scanned = false
    private val dates = DateFormat.getDateInstance(DateFormat.MEDIUM)
    private val thumbs = Executors.newSingleThreadExecutor { r -> Thread(r, "recover-thumbs").apply { isDaemon = true } }

    override fun build(): View = ui.column {
        addView(ui.topBar("Recover", host::goHome, R.drawable.ic_refresh, "Look again") { scan() }.first)
        addView(ui.text(14f, color(R.color.ink_soft)) {
            text = "Finds deleted photos, videos and files that are still on this phone: Android's 30-day trash, the recycle " +
                "folders of gallery and file apps, and small thumbnail copies."
            setLineSpacing(ui.dp(3).toFloat(), 1f)
        }, ui.lp(top = 10))
        access = accessCard()
        addView(access, ui.lp(top = 16))
        status = ui.text(14f, color(R.color.ink), ui.medium)
        addView(status, ui.lp(top = 18))
        recoverPill = ui.pill("Recover", color(R.color.fab), color(R.color.on_fab), R.drawable.ic_down) { recover() }
        addView(recoverPill, ui.lp(top = 12))
        list = ui.column()
        addView(list, ui.lp(top = 4))
        addView(ui.glassCard(16) {
            addView(ui.text(14f, color(R.color.ink), ui.medium) { text = "What can't be recovered" })
            addView(ui.text(13f, color(R.color.ink_soft)) {
                text = "A phone encrypts its internal storage and wipes the space of deleted files, so a file that is no longer " +
                    "in a trash or recycle folder is gone for good; no app can bring it back.\n\n" +
                    "A memory card is different. Take it out, put it in your computer and use SD card recovery in Andromon. " +
                    "Stop using the card until then, so the deleted files aren't overwritten."
                setLineSpacing(ui.dp(3).toFloat(), 1f)
            }, ui.lp(top = 6))
        }, ui.lp(top = 20))
    }

    override fun onShow() {
        val ok = Cleaner.hasFileAccess(host)
        access.visibility = if (ok) View.GONE else View.VISIBLE
        if (!ok) {
            status.text = "Allow access to files to look for deleted items."
            recoverPill.visibility = View.GONE
            list.removeAllViews()
            scanned = false
        } else if (!scanned) scan() else render()
    }

    override fun onHide() { scanning?.set(true) }

    override fun onPermissionsResult() = onShow()

    private fun accessCard() = ui.glassCard(16) {
        addView(ui.text(16f, color(R.color.ink), ui.medium) { text = "Allow access to files" })
        addView(ui.text(13f, color(R.color.ink_soft)) {
            text = "Trash and recycle folders are hidden files, which Android only shows to apps with this access. " +
                "Nothing is changed: recovering makes a copy."
            setLineSpacing(ui.dp(2).toFloat(), 1f)
        }, ui.lp(top = 4))
        addView(ui.pill("Allow", color(R.color.fab), color(R.color.on_fab)) {
            if (Build.VERSION.SDK_INT >= 30) {
                val pkg = Uri.fromParts("package", host.packageName, null)
                runCatching { host.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg)) }
                    .onFailure { runCatching { host.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
            } else {
                host.requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 71)
            }
        }, ui.lp(top = 14))
    }

    private fun scan() {
        if (!Cleaner.hasFileAccess(host)) return
        scanning?.set(true)
        val stop = AtomicBoolean(false)
        scanning = stop
        status.text = "Looking through your storage…"
        recoverPill.visibility = View.GONE
        list.removeAllViews()
        val own = Recovery.folder().path
        background({
            val roots = Cleaner.volumes(host).mapNotNull { it.dir }
            Recovery.scan(roots, stop).filter { !it.file.path.startsWith(own) }
        }) { r ->
            if (stop.get() || scanning !== stop) return@background
            scanning = null
            scanned = true
            found = r.getOrDefault(emptyList())
            render()
        }
    }

    private fun render() {
        list.removeAllViews()
        if (found.isEmpty()) {
            status.text = "Nothing recoverable was found."
            recoverPill.visibility = View.GONE
            return
        }
        status.text = "${count(found.size)} found · ${fmtSize(found.sumOf { it.size })}"
        for (kind in Recovery.Kind.values()) {
            val items = found.filter { it.kind == kind }
            if (items.isEmpty()) continue
            list.addView(ui.section(kind.title) {
                addView(ui.pill("Select all", color(R.color.glass_strong), color(R.color.ink)) {
                    val all = items.all { it.checked }
                    items.forEach { it.checked = !all }
                    render()
                }.apply { textSize = 13f; minHeight = ui.dp(32) }, ui.lp(w = WRAP_CONTENT))
            }, ui.lp(top = 22))
            list.addView(ui.text(12f, color(R.color.ink_soft)) { text = "${count(items.size)} · ${kind.about}" }, ui.lp(top = 4))
            for (item in items.take(SHOWN_PER_KIND)) list.addView(row(item), ui.lp(top = 8))
            if (items.size > SHOWN_PER_KIND) list.addView(ui.text(12f, color(R.color.ink_soft)) {
                text = "And ${items.size - SHOWN_PER_KIND} older ones. “Select all” includes them."
            }, ui.lp(top = 8))
        }
        updatePill()
    }

    private fun count(n: Int) = "%,d item".format(n) + if (n == 1) "" else "s"

    private fun updatePill() {
        val picked = found.filter { it.checked }
        recoverPill.visibility = View.VISIBLE
        ui.setEnabled(recoverPill, picked.isNotEmpty())
        ui.setLabel(recoverPill, if (picked.isEmpty()) "Select items to recover" else "Recover ${count(picked.size)} (${fmtSize(picked.sumOf { it.size })})")
    }

    private fun row(item: Recovery.Found) = ui.row {
        background = ui.pressable(ui.rounded(color(R.color.surface), 18))
        setPadding(ui.dp(10), ui.dp(10), ui.dp(8), ui.dp(10))
        val picture = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ui.rounded(color(R.color.glass_strong), 12)
            clipToOutline = true
            setImageDrawable(ui.icon(if (item.image) R.drawable.ic_image else R.drawable.ic_file, color(R.color.ink_soft)))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        if (item.image) thumbs.execute {
            val bmp = thumbnail(item) ?: return@execute
            host.runOnUiThread { if (!host.isDestroyed) { picture.scaleType = ImageView.ScaleType.CENTER_CROP; picture.setImageBitmap(bmp) } }
        }
        addView(picture, ui.lp(w = ui.dp(48), h = ui.dp(48)))
        addView(ui.column {
            addView(ui.text(15f, color(R.color.ink), ui.medium) { text = item.name; maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE })
            addView(ui.text(12f, color(R.color.ink_soft)) {
                text = "${fmtSize(item.size)} · ${dates.format(Date(item.modified))} · ${item.file.parentFile?.name ?: ""}"
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, ui.lp(top = 3))
        }, ui.lp(w = 0, weight = 1f, start = 12))
        val check = ImageView(context).apply { setPadding(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5)) }
        fun toggle() { item.checked = !item.checked; ui.setCheck(check, item.checked); updatePill() }
        ui.setCheck(check, item.checked)
        check.contentDescription = "Select ${item.name}"
        check.setOnClickListener { toggle() }
        addView(check, ui.lp(w = ui.dp(36), h = ui.dp(36), start = 8))
        setOnClickListener { toggle() }
    }

    /** A small picture of [item], read at a reduced size so big photos don't fill the memory. */
    private fun thumbnail(item: Recovery.Found): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(item.file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 160 && bounds.outHeight / (sample * 2) >= 160) sample *= 2
        BitmapFactory.decodeFile(item.file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun recover() {
        val picked = found.filter { it.checked }
        if (picked.isEmpty()) return
        ui.setEnabled(recoverPill, false)
        ui.setLabel(recoverPill, "Copying…")
        background({ Recovery.recover(host, picked) }) { r ->
            val n = r.getOrDefault(0)
            picked.forEach { it.checked = false }
            render()
            AlertDialog.Builder(host)
                .setTitle(if (n == picked.size) "Recovered ${count(n)}" else "Recovered $n of ${picked.size}")
                .setMessage("The copies are in Download › Andromon › Recovered. The originals were left where they were." +
                    if (n < picked.size) "\n\nSome couldn't be copied: there may not be enough free space." else "")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    @Suppress("unused") private fun toast(msg: String) = Toast.makeText(host, msg, Toast.LENGTH_LONG).show()
}
