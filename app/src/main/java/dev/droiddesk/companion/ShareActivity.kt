// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

package dev.droiddesk.companion

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.MediaStore
import android.widget.Toast

/**
 * The way files get into the Wi‑Fi send queue. It has no screen of its own:
 *
 *  - **Share target** ("Share › Droid Desk" in Gallery, Files and other apps): takes the shared
 *    files, or shared text and links, queues them in [WifiSend] and opens the app's Send side.
 *  - **Picker** for the Send screen's Files and Photos buttons (Android's own pickers).
 *  - **Open** from [SendService]'s notifications.
 *
 * Exported because other apps must be able to share to it. It only ever queues what was shared;
 * nothing is sent until the person picks a device and taps Send. Only `content://` items are
 * accepted (see [WifiSend.addUris]).
 */
class ShareActivity : Activity() {

    companion object {
        internal const val ACTION_PICK_FILES = "dev.droiddesk.companion.send.PICK_FILES"
        internal const val ACTION_PICK_PHOTOS = "dev.droiddesk.companion.send.PICK_PHOTOS"
        internal const val ACTION_OPEN = "dev.droiddesk.companion.send.OPEN"
        private const val REQ_PICK = 41
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action
        if (savedInstanceState != null) {
            // Recreated while a picker is open: its result still arrives. Anything else is done.
            if (action != ACTION_PICK_FILES && action != ACTION_PICK_PHOTOS) close()
            return
        }
        when (action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> shared(intent)
            ACTION_PICK_FILES -> pick(photos = false)
            ACTION_PICK_PHOTOS -> pick(photos = true)
            else -> toApp(emptyList())
        }
    }

    private fun shared(intent: Intent) {
        // Extras come from another app: anything unexpected in them is simply ignored.
        val uris = ArrayList<Uri>()
        var text: String? = null
        try {
            @Suppress("DEPRECATION")
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)?.forEach { (it as? Uri)?.let(uris::add) }
            } else {
                (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let(uris::add)
            }
            if (uris.isEmpty()) uris += urisOf(intent.clipData)
            text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        } catch (_: Exception) {
        }
        when {
            uris.isNotEmpty() -> add(uris)
            !text.isNullOrBlank() -> { WifiSend.addText(text); toApp(emptyList()) }
            else -> {
                Toast.makeText(this, "Nothing to send", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun urisOf(clip: ClipData?): List<Uri> =
        if (clip == null) emptyList() else (0 until clip.itemCount).mapNotNull { clip.getItemAt(it)?.uri }

    private fun pick(photos: Boolean) {
        val documents = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .apply { if (photos) putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*")) }
        val first = if (photos && Build.VERSION.SDK_INT >= 33) {
            // Android's photo picker: needs no permission and shows only what the person picks.
            Intent(MediaStore.ACTION_PICK_IMAGES)
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, minOf(MediaStore.getPickImagesMaxLimit(), 100))
        } else documents
        try {
            startActivityForResult(first, REQ_PICK)
        } catch (_: ActivityNotFoundException) {
            try {
                if (first === documents) throw ActivityNotFoundException()
                startActivityForResult(documents, REQ_PICK)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "This phone has no file picker", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    @Deprecated("Framework callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uris = if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            (urisOf(data.clipData) + listOfNotNull(data.data)).distinct()
        } else emptyList()
        if (uris.isEmpty()) close() else add(uris)
    }

    /** Reads the names and sizes while this activity (which holds the read grants) is still alive. */
    private fun add(uris: List<Uri>) {
        if (uris.size > 20) Toast.makeText(this, "Adding ${uris.size} items…", Toast.LENGTH_SHORT).show()
        WifiSend.addUris(this, uris) { toApp(uris) }
    }

    /** Opens the app on the Send side, passing the read grants on to it. */
    private fun toApp(uris: List<Uri>) {
        WifiSend.openRequested = true
        val plain = Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, "Receive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val content = uris.filter { it.scheme == "content" }.take(400)
        val granting = if (content.isEmpty()) null else Intent(plain).apply {
            clipData = ClipData.newRawUri("files", content[0]).also { c -> content.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(granting ?: plain)
        } catch (_: SecurityException) {
            runCatching { startActivity(plain) }
        } catch (_: Exception) {
        }
        close()
    }

    private fun close() {
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
