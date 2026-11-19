// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * The accesses the app uses, in one place: what each is for, whether it is allowed, and how to
 * ask for it (Android's permission dialog, or the right Settings page for special access). The
 * welcome tour's permissions page lists [TOUR]; screens use [ensure] to explain and ask at the point
 * of use. Nothing here is required: every feature still opens without its access and says why it
 * can't do more.
 *
 * Asked for elsewhere, only when the feature is used: the microphone (Hardware test), the camera
 * (Send › Scan code), Nearby devices or location (Direct link), installing apps (F-Droid) and the
 * Freezer's accessibility service.
 */
internal object Perms {

    enum class Kind { RUNTIME, NOTIFICATIONS, ALL_FILES, USAGE, NOTIFICATION_ACCESS, OVERLAY }

    class Access(
        val key: String,
        val icon: Int,
        val title: String,
        /** What it is for, in one sentence. */
        val why: String,
        val kind: Kind,
        /** The runtime permissions behind it on this Android version (empty for special access). */
        val permissions: List<String> = emptyList(),
    )

    /** Request code for [ask]'s runtime permission dialog. */
    const val REQ = 0x5045

    val NOTIFICATIONS = Access("notifications", R.drawable.ic_bell, "Notifications",
        "The PC link's status, Wi‑Fi transfers you accept or decline, and charging alerts.",
        Kind.NOTIFICATIONS, if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())

    /** Read by Andromon on the computer. Android only allows messages and the call log when Andromon installed the app over USB. */
    val PC_DATA = Access("pc_data", R.drawable.ic_sms, "Messages, contacts, calls and calendar",
        "So Andromon on your computer can show them and alert you to calls. Usually already allowed when Andromon installs this app over USB.",
        Kind.RUNTIME, listOf(
            Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS,
        ))

    val MEDIA = Access("media", R.drawable.ic_image, "Photos, videos and music",
        "For the Photos and Music tabs in Andromon on your computer.",
        Kind.RUNTIME, if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE))

    val FILES = Access("files", R.drawable.ic_folder, "All files",
        "Lets the Cleaner, Storage and Recover look through your files. Nothing is deleted until you confirm.",
        Kind.ALL_FILES, if (Build.VERSION.SDK_INT >= 30) emptyList()
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))

    val USAGE = Access("usage", R.drawable.ic_chart, "Usage access",
        "Screen time and app sizes in Stats and Apps, the Freezer's “When unused”, and the Cleaner's app caches.",
        Kind.USAGE)

    val NOTIFICATION_ACCESS = Access("notif_access", R.drawable.ic_desktop, "Phone notifications on your computer",
        "Shows your phone's notifications in Andromon on your computer (Android calls it Notification access). Nothing is passed on unless a computer is linked.",
        Kind.NOTIFICATION_ACCESS)

    val OVERLAY = Access("overlay", R.drawable.ic_layers, "Display over other apps",
        "Opens links your computer sends straight away, and shows the Recording badge during Andromon's test automation.",
        Kind.OVERLAY)

    /** The welcome tour's permissions page, in this order. */
    val TOUR = listOf(NOTIFICATIONS, PC_DATA, MEDIA, FILES, USAGE, NOTIFICATION_ACCESS, OVERLAY)

    /** Short names for the runtime permissions, for lists of what is missing. */
    private val names = mapOf(
        Manifest.permission.READ_SMS to "Read messages",
        Manifest.permission.SEND_SMS to "Send messages",
        Manifest.permission.READ_CONTACTS to "Read contacts",
        Manifest.permission.WRITE_CONTACTS to "Add contacts",
        Manifest.permission.READ_CALL_LOG to "Read call log",
        Manifest.permission.WRITE_CALL_LOG to "Restore call log",
        Manifest.permission.READ_CALENDAR to "Read calendar",
        Manifest.permission.WRITE_CALENDAR to "Add calendar events",
        Manifest.permission.READ_PHONE_STATE to "See incoming calls",
        Manifest.permission.ANSWER_PHONE_CALLS to "Answer or decline calls",
        Manifest.permission.READ_MEDIA_IMAGES to "Photos",
        Manifest.permission.READ_MEDIA_VIDEO to "Videos",
        Manifest.permission.READ_MEDIA_AUDIO to "Music",
        Manifest.permission.READ_EXTERNAL_STORAGE to "Photos, videos and music",
        Manifest.permission.WRITE_EXTERNAL_STORAGE to "Save files",
        "android.permission.POST_NOTIFICATIONS" to "Show notifications",
    )

    fun label(p: String) = names[p] ?: p.substringAfterLast('.')

    // ---------- state ----------

    private fun held(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** The runtime permissions of [a] that aren't granted. */
    fun missing(ctx: Context, a: Access): List<String> = a.permissions.filterNot { held(ctx, it) }

    /** Whether everything [a] stands for is allowed. */
    fun granted(ctx: Context, a: Access): Boolean = when (a.kind) {
        Kind.RUNTIME -> missing(ctx, a).isEmpty() ||
            // All files access also covers photos, videos and music (see Media.canRead).
            (a === MEDIA && Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager())
        Kind.NOTIFICATIONS -> ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled() && missing(ctx, a).isEmpty()
        Kind.ALL_FILES -> Cleaner.hasFileAccess(ctx)
        Kind.USAGE -> Cleaner.hasUsageAccess(ctx)
        Kind.NOTIFICATION_ACCESS -> NotifWatch.access(ctx)
        Kind.OVERLAY -> Settings.canDrawOverlays(ctx)
    }

    /** Some of [a]'s runtime permissions are allowed, not all (shown as "Partly allowed"). */
    fun partly(ctx: Context, a: Access): Boolean =
        a.kind == Kind.RUNTIME && a.permissions.size > 1 && !granted(ctx, a) && missing(ctx, a).size < a.permissions.size

    /** Asked before and refused for good (or blocked by Android): only App info can allow it now. */
    fun blocked(act: Activity, p: String) = Prefs.askedPermission(act, p) && !act.shouldShowRequestPermissionRationale(p)

    /** Whether asking opens a Settings page rather than Android's permission dialog. */
    fun opensSettings(act: Activity, a: Access): Boolean {
        val m = missing(act, a)
        return m.isEmpty() || m.all { blocked(act, it) }
    }

    // ---------- asking ----------

    /**
     * Asks for [a]: Android's permission dialog for the runtime permissions it still asks for (the
     * answer arrives in the activity's onRequestPermissionsResult), otherwise the right Settings page
     * (the caller re-reads the state in onResume).
     */
    fun ask(act: Activity, a: Access, requestCode: Int = REQ) {
        val askable = missing(act, a).filterNot { blocked(act, it) }
        if (askable.isNotEmpty()) {
            askable.forEach { Prefs.setAskedPermission(act, it) }
            act.requestPermissions(askable.toTypedArray(), requestCode)
            return
        }
        val pkg = Uri.fromParts("package", act.packageName, null)
        val tries = when (a.kind) {
            Kind.NOTIFICATIONS -> listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, act.packageName))
            Kind.ALL_FILES -> if (Build.VERSION.SDK_INT >= 30) listOf(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) else emptyList()
            Kind.USAGE -> listOf(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkg), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            Kind.NOTIFICATION_ACCESS -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        ComponentName(act, NotifListener::class.java).flattenToString()) else null,
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            Kind.OVERLAY -> listOf(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${act.packageName}")),
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            Kind.RUNTIME -> emptyList()
        } + Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg) // refused for good: App info › Permissions
        for (i in tries) if (runCatching { act.startActivity(i) }.isSuccess) return
    }

    /**
     * At the point of use: true when [a] is allowed. Otherwise explains in a dialog what it is for
     * ([why]) with Allow (which asks, after [onAsk]) and Not now ([onSkip]; dismissing the dialog does neither),
     * and returns false.
     */
    fun ensure(act: Activity, a: Access, why: String = a.why, onAsk: () -> Unit = {}, onSkip: () -> Unit = {}): Boolean {
        if (granted(act, a)) return true
        val settings = opensSettings(act, a)
        val m = missing(act, a)
        val refused = m.isNotEmpty() && m.all { blocked(act, it) }
        AlertDialog.Builder(act)
            .setTitle(a.title)
            .setMessage(if (refused) "$why\n\nAndroid won't ask again, so allow it in App info › Permissions." else why)
            .setPositiveButton(if (settings) "Open settings" else "Allow") { _, _ -> onAsk(); ask(act, a) }
            .setNegativeButton("Not now") { _, _ -> onSkip() }
            .show()
        return false
    }
}
