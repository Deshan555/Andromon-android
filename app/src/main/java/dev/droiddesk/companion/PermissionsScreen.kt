// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

package dev.droiddesk.companion

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Which installed apps hold (or ask for) the sensitive permissions, read from Android's package data. */
internal object PermissionAudit {

    /** [granted]: true = allowed now, false = asked for but not allowed, null = Android doesn't say. */
    class Holder(val pkg: String, val label: String, val system: Boolean, val granted: Boolean?, val detail: String)

    class Group(
        val title: String,
        val icon: Int,
        val about: String,
        /** App-op names for "last used" through the shell helper; empty when Android keeps no such record. */
        val ops: List<String>,
        val holders: List<Holder>,
    )

    private class Def(val title: String, val icon: Int, val about: String, val ops: List<String>, val perms: List<Pair<String, String>>)

    private const val P = "android.permission."

    private val RUNTIME = listOf(
        Def("Camera", R.drawable.ic_camera, "Take photos and record video.", listOf("CAMERA"),
            listOf("CAMERA" to "Camera")),
        Def("Microphone", R.drawable.ic_mic, "Record sound.", listOf("RECORD_AUDIO"),
            listOf("RECORD_AUDIO" to "Microphone")),
        Def("Location", R.drawable.ic_location, "See where the phone is.", listOf("FINE_LOCATION", "COARSE_LOCATION"),
            listOf("ACCESS_FINE_LOCATION" to "Precise", "ACCESS_COARSE_LOCATION" to "Approximate",
                "ACCESS_BACKGROUND_LOCATION" to "In the background")),
        Def("Contacts", R.drawable.ic_person, "Read or change your contacts and see the accounts on the phone.", listOf("READ_CONTACTS"),
            listOf("READ_CONTACTS" to "Read", "WRITE_CONTACTS" to "Change", "GET_ACCOUNTS" to "Accounts")),
        Def("SMS", R.drawable.ic_sms, "Read, receive or send text messages.", listOf("READ_SMS"),
            listOf("READ_SMS" to "Read", "SEND_SMS" to "Send", "RECEIVE_SMS" to "Receive", "RECEIVE_MMS" to "Receive MMS",
                "RECEIVE_WAP_PUSH" to "Receive WAP push")),
        Def("Call log", R.drawable.ic_call, "Read or change the list of calls.", listOf("READ_CALL_LOG"),
            listOf("READ_CALL_LOG" to "Read", "WRITE_CALL_LOG" to "Change", "PROCESS_OUTGOING_CALLS" to "See outgoing calls")),
        Def("Phone", R.drawable.ic_cell, "See the phone's status and number, make and answer calls.", emptyList(),
            listOf("READ_PHONE_STATE" to "Phone status", "READ_PHONE_NUMBERS" to "Phone number", "CALL_PHONE" to "Make calls",
                "ANSWER_PHONE_CALLS" to "Answer calls", "ADD_VOICEMAIL" to "Voicemail", "USE_SIP" to "Internet calls")),
        Def("Files and media", R.drawable.ic_folder, "Read photos, videos, music or files on shared storage.", emptyList(),
            listOf("READ_EXTERNAL_STORAGE" to "Read storage", "WRITE_EXTERNAL_STORAGE" to "Write storage",
                "READ_MEDIA_IMAGES" to "Photos", "READ_MEDIA_VIDEO" to "Videos", "READ_MEDIA_AUDIO" to "Music",
                "READ_MEDIA_VISUAL_USER_SELECTED" to "Selected photos", "ACCESS_MEDIA_LOCATION" to "Photo locations")),
    )

    /** True when this app is allowed to see every installed app; otherwise the lists would be incomplete. */
    fun seesAllApps(ctx: Context) = Cleaner.installedPackages(ctx) != null

    /** Blocks (reads every package's permissions). */
    fun load(ctx: Context): List<Group> {
        val pm = ctx.packageManager
        val pkgs = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        val byName = pkgs.associateBy { it.packageName }
        val labels = HashMap<String, String>()
        fun label(pkg: String) = labels.getOrPut(pkg) {
            byName[pkg]?.applicationInfo?.let { runCatching { it.loadLabel(pm).toString() }.getOrNull() } ?: pkg
        }
        fun system(pkg: String) = (byName[pkg]?.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
        fun holder(pkg: String, granted: Boolean?, detail: String) = Holder(pkg, label(pkg), system(pkg), granted, detail)
        /** Requested permissions of [p] with their granted state. */
        fun perms(p: PackageInfo): Map<String, Boolean> {
            val names = p.requestedPermissions ?: return emptyMap()
            val flags = p.requestedPermissionsFlags
            return names.indices.associate { i ->
                names[i] to (flags != null && i < flags.size && flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0)
            }
        }
        val permsOf = pkgs.associate { it.packageName to perms(it) }

        val groups = ArrayList<Group>()
        for (def in RUNTIME) {
            val holders = pkgs.mapNotNull { p ->
                val mine = permsOf.getValue(p.packageName)
                val asked = def.perms.filter { (name, _) -> P + name in mine }
                if (asked.isEmpty()) return@mapNotNull null
                val granted = asked.filter { (name, _) -> mine[P + name] == true }
                holder(p.packageName, granted.isNotEmpty(),
                    if (def.perms.size == 1) "" else (if (granted.isNotEmpty()) granted else asked).joinToString(", ") { it.second })
            }
            groups += Group(def.title, def.icon, def.about, def.ops, holders)
        }

        // Special access: not ordinary permissions, each kept in its own place by Android.
        fun special(title: String, icon: Int, about: String, on: Set<String>?, candidates: Set<String>) {
            val all = candidates + (on ?: emptySet())
            groups += Group(title, icon, about, emptyList(), all.filter { it in byName }.map { holder(it, on?.contains(it), "") })
        }

        val listenersOn = runCatching {
            Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
                ?.split(':')?.mapNotNull { ComponentName.unflattenFromString(it)?.packageName }?.toSet() ?: emptySet()
        }.getOrNull()
        val listeners = runCatching {
            pm.queryIntentServices(Intent("android.service.notification.NotificationListenerService"), PackageManager.MATCH_DISABLED_COMPONENTS)
                .map { it.serviceInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
        special("Notification access", R.drawable.ic_bell, "Read every notification, including message texts and codes.", listenersOn, listeners)

        val am = ctx.getSystemService(AccessibilityManager::class.java)
        val a11yOn = runCatching {
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).map { it.resolveInfo.serviceInfo.packageName }.toSet()
        }.getOrNull()
        val a11y = runCatching { am.installedAccessibilityServiceList.map { it.resolveInfo.serviceInfo.packageName }.toSet() }
            .getOrDefault(emptySet())
        special("Accessibility services", R.drawable.ic_accessibility, "See what's on screen and tap or type for you.", a11yOn, a11y)

        val adminsOn = runCatching {
            ctx.getSystemService(DevicePolicyManager::class.java).activeAdmins?.map { it.packageName }?.toSet() ?: emptySet()
        }.getOrNull()
        val admins = runCatching {
            pm.queryBroadcastReceivers(Intent("android.app.action.DEVICE_ADMIN_ENABLED"), PackageManager.MATCH_DISABLED_COMPONENTS)
                .map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
        special("Device admin", R.drawable.ic_shield, "Lock or wipe the phone and set password rules.", adminsOn, admins)

        // Switches Android keeps as app-ops. The state is read the way Android's Settings does:
        // "allowed" when the op is allowed, or left at its default while the permission is granted.
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        fun appOp(title: String, icon: Int, about: String, perm: String, op: String) {
            val holders = pkgs.mapNotNull { p ->
                val granted = permsOf.getValue(p.packageName)[P + perm] ?: return@mapNotNull null
                val uid = p.applicationInfo?.uid ?: return@mapNotNull null
                val state: Boolean? = try {
                    @Suppress("DEPRECATION")
                    val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(op, uid, p.packageName)
                               else ops.checkOpNoThrow(op, uid, p.packageName)
                    when (mode) {
                        AppOpsManager.MODE_ALLOWED -> true
                        AppOpsManager.MODE_DEFAULT -> granted
                        else -> false
                    }
                } catch (_: Exception) { null }
                holder(p.packageName, state, "")
            }
            groups += Group(title, icon, about, emptyList(), holders)
        }
        appOp("Display over other apps", R.drawable.ic_layers, "Draw on top of whatever else is on screen.",
            "SYSTEM_ALERT_WINDOW", AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW)
        appOp("Install unknown apps", R.drawable.ic_apk, "Install apps from files, outside the app store.",
            "REQUEST_INSTALL_PACKAGES", "android:request_install_packages")
        if (Build.VERSION.SDK_INT >= 30) appOp("All files access", R.drawable.ic_sd, "Read and change every file on shared storage.",
            "MANAGE_EXTERNAL_STORAGE", "android:manage_external_storage")
        return groups
    }

    class OldHelper : IOException("The helper needs a restart")

    private val TIME = Regex("""(?<![A-Za-z])time=\+(\S+?) ago""")
    private val PART = Regex("""(\d+)(ms|d|h|m|s)""")

    /**
     * When each of [pkgs] last used one of [ops], as "how long ago" in ms (null = no use on record),
     * from `appops get` through the shell helper. Blocks; throws [ShellHelperClient.Unavailable]
     * without the helper. Packages the helper didn't get to are missing from the result.
     */
    fun lastUse(ctx: Context, ops: List<String>, pkgs: List<String>): Map<String, Long?> {
        val out = HashMap<String, Long?>()
        for (op in ops) {
            val res = try {
                ShellHelperClient.call(ctx, "appOps", JSONObject().put("op", op).put("pkgs", JSONArray(pkgs))) as JSONObject
            } catch (e: ShellHelperClient.Unavailable) {
                throw e
            } catch (e: IOException) {
                if (e.message?.startsWith("Unknown method") == true) throw OldHelper() else throw e
            }
            for (pkg in res.keys()) {
                val ago = TIME.findAll(res.optString(pkg)).mapNotNull { m ->
                    var ms = 0L
                    var any = false
                    for (part in PART.findAll(m.groupValues[1])) {
                        any = true
                        val n = part.groupValues[1].toLong()
                        ms += when (part.groupValues[2]) { "d" -> n * 86_400_000; "h" -> n * 3_600_000; "m" -> n * 60_000; "s" -> n * 1000; else -> n }
                    }
                    ms.takeIf { any }
                }.minOrNull()
                val before = out[pkg]
                out[pkg] = if (ago != null && before != null) minOf(ago, before) else ago ?: before
            }
        }
        return out
    }
}

/**
 * Permission audit: per sensitive permission, the apps that have it and the apps that only asked.
 * Tapping an app opens Android's own "App info" page, where the permission can be changed.
 */
internal class PermissionsScreen(host: MainActivity) : Screen(host) {

    override val name = "Permissions"
    override val navIcon = R.drawable.ic_key
    override val navColor = R.color.bg

    private companion object { const val MAX_ROWS = 200 }

    private enum class Filter(val label: String) { USER("User apps"), SYSTEM("System"), ALL("All") }

    private var groups: List<PermissionAudit.Group> = emptyList()
    private var filter = Filter.USER
    private var open: PermissionAudit.Group? = null
    private var loaded = false
    private var loading = false
    private val icons = HashMap<String, Drawable?>()
    /** Per open group: package -> how long ago it used the permission (null = nothing on record). */
    private var lastUse: Map<String, Long?>? = null
    private var lastUseNote = ""

    private lateinit var chips: Map<Filter, TextView>
    private lateinit var body: LinearLayout

    override fun build(): View = ui.column {
        addView(ui.topBar("Permissions", { if (!onBack()) host.goHome() }, R.drawable.ic_refresh, "Check again") { load() }.first)
        addView(ui.row {
            chips = Filter.values().associateWith { f -> ui.pill(f.label, 0, 0).apply { textSize = 14f; minHeight = ui.dp(38) } }
            chips.values.forEachIndexed { i, chip -> addView(chip, ui.lp(w = 0, weight = 1f, start = if (i == 0) 0 else 8)) }
        }, ui.lp(top = 18))
        body = ui.column()
        addView(body, ui.lp(top = 14))
    }

    override fun onShow() {
        if (loaded) render()
        load() // again on every return: states may have changed in App info
    }

    override fun onBack(): Boolean {
        if (open == null) return false
        open = null
        render()
        host.scrollToTop()
        return true
    }

    private fun load() {
        if (loading) return
        loading = true
        if (!loaded) render()
        background({ PermissionAudit.load(host) }) { r ->
            loading = false
            loaded = true
            r.onSuccess { g ->
                groups = g
                open = open?.let { o -> g.firstOrNull { it.title == o.title } }
                render()
            }.onFailure {
                body.removeAllViews()
                body.addView(note("Couldn't read the app list: ${it.message ?: it.javaClass.simpleName}"))
            }
        }
    }

    private fun shown(g: PermissionAudit.Group) = g.holders.filter {
        when (filter) { Filter.USER -> !it.system; Filter.SYSTEM -> it.system; Filter.ALL -> true }
    }

    private fun render() {
        chips.forEach { (f, chip) ->
            val on = f == filter
            ui.stylePill(chip, f.label, color(if (on) R.color.fab else R.color.glass_strong), color(if (on) R.color.on_fab else R.color.ink)) {
                filter = f; render()
                open?.let(::loadDetails) // icons and last use for the rows now shown
            }
        }
        body.removeAllViews()
        if (!loaded) { body.addView(note("Reading app permissions…")); return }
        val g = open
        if (g == null) renderOverview() else renderGroup(g)
    }

    private fun note(t: String) = ui.text(13f, color(R.color.ink_soft)) {
        text = t
        setLineSpacing(ui.dp(2).toFloat(), 1f)
        setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6))
    }

    // ---------- overview ----------

    private fun renderOverview() {
        if (!PermissionAudit.seesAllApps(host)) body.addView(note(
            "This app isn't allowed to see every installed app on this phone, so the lists below may be incomplete."))
        body.addView(ui.glassCard(6) {
            for (g in groups) {
                val holders = shown(g)
                val on = holders.count { it.granted == true }
                val off = holders.count { it.granted == false }
                val unknown = holders.count { it.granted == null }
                addView(ui.row {
                    background = ui.pressable(ui.rounded(android.graphics.Color.TRANSPARENT, 20))
                    setPadding(ui.dp(10), ui.dp(12), ui.dp(8), ui.dp(12))
                    addView(ui.roundIcon(40, color(R.color.glass_strong), g.icon, color(R.color.ink), 19), ui.lp(w = ui.dp(40), h = ui.dp(40)))
                    addView(ui.column {
                        addView(ui.text(15f, color(R.color.ink), ui.medium) { text = g.title })
                        addView(ui.text(12f, color(R.color.ink_soft)) {
                            text = listOfNotNull(
                                if (off > 0) "$off only asked" else null,
                                if (unknown > 0) "$unknown ask for it (state not readable)" else null,
                            ).joinToString(" · ").ifEmpty { if (on == 0) "No apps" else "Nothing else asked" }
                        }, ui.lp(top = 3))
                    }, ui.lp(w = 0, weight = 1f, start = 12))
                    addView(ui.text(20f, color(R.color.ink), ui.regular) { text = "$on" }, ui.lp(w = WRAP_CONTENT, start = 8))
                    addView(ui.iconView(R.drawable.ic_chevron_right, color(R.color.ink_faint)), ui.lp(w = ui.dp(20), h = ui.dp(20), start = 4))
                    contentDescription = "${g.title}: $on allowed"
                    setOnClickListener { openGroup(g) }
                })
            }
        })
        body.addView(note("The number is how many apps are allowed right now. System apps are part of Android or the phone maker's " +
            "software; switch to System or All to include them. This lists what Android reports. It can't tell whether an app misuses a permission."))
    }

    // ---------- one group ----------

    private fun openGroup(g: PermissionAudit.Group) {
        open = g
        lastUse = null
        lastUseNote = ""
        render()
        host.scrollToTop()
        loadDetails(g)
    }

    /** Icons for the rows, and last-use times when the helper can give them. */
    private fun loadDetails(g: PermissionAudit.Group) {
        val rows = shown(g).take(MAX_ROWS)
        background({
            val pm = host.packageManager
            for (h in rows) synchronized(icons) {
                if (h.pkg !in icons) icons[h.pkg] = runCatching { pm.getApplicationIcon(h.pkg) }.getOrNull()
            }
            if (g.ops.isEmpty()) null
            else runCatching { PermissionAudit.lastUse(host, g.ops, rows.filter { it.granted == true }.map { it.pkg }.take(80)) }
        }) { r ->
            if (open?.title != g.title) return@background // closed, or another group opened, meanwhile
            val use = r.getOrNull()
            if (use != null) {
                lastUse = use.getOrNull()
                lastUseNote = when (val e = use.exceptionOrNull()) {
                    null -> "Last use comes from Android's app-ops record (appops get, through the Droid Desk helper). Android only " +
                        "keeps the most recent use, and not forever."
                    is ShellHelperClient.Unavailable -> "When each app last used this isn't shown: apps can't read that. It appears " +
                        "while the Droid Desk helper is on (Droid Desk on your computer: Overview › Phone RAM manager)."
                    is PermissionAudit.OldHelper -> "Last-use times need a newer helper: turn it off and on again in Droid Desk " +
                        "(Overview › Phone RAM manager)."
                    else -> "Last-use times couldn't be read: ${e.message}"
                }
            }
            render()
        }
    }

    private fun renderGroup(g: PermissionAudit.Group) {
        val holders = shown(g).sortedWith(compareBy<PermissionAudit.Holder> { it.system }.thenBy { it.label.lowercase() })
        body.addView(ui.glassCard(16) {
            addView(ui.row {
                addView(ui.chipIcon(g.icon))
                addView(ui.column {
                    addView(ui.text(18f, color(R.color.ink), ui.medium) { text = g.title })
                    addView(ui.text(13f, color(R.color.ink_soft)) { text = g.about; setLineSpacing(ui.dp(2).toFloat(), 1f) }, ui.lp(top = 3))
                }, ui.lp(w = 0, weight = 1f, start = 12))
            })
            addView(ui.pill("All permissions", color(R.color.glass_strong), color(R.color.ink), R.drawable.ic_back) { onBack() }
                .apply { textSize = 13f; minHeight = ui.dp(36) }, ui.lp(w = WRAP_CONTENT, top = 12))
        })
        if (lastUseNote.isNotEmpty()) body.addView(note(lastUseNote), ui.lp(top = 6))

        fun section(title: String, list: List<PermissionAudit.Holder>, empty: String?) {
            if (list.isEmpty() && empty == null) return
            body.addView(ui.section("$title (${list.size})"), ui.lp(top = 20))
            if (list.isEmpty()) { body.addView(note(empty!!), ui.lp(top = 6)); return }
            body.addView(ui.card(color(R.color.surface), 6) {
                for (h in list.take(MAX_ROWS)) addView(appRow(h))
                if (list.size > MAX_ROWS) addView(note("+ ${list.size - MAX_ROWS} more not listed"))
            }, ui.lp(top = 10))
        }
        section("Allowed", holders.filter { it.granted == true }, "None among the apps shown.")
        section("Asked for, not allowed", holders.filter { it.granted == false }, null)
        section("Ask for it, state not readable", holders.filter { it.granted == null }, null)
        body.addView(note("Tap an app to open its App info page in Android's Settings, where you can change this."), ui.lp(top = 10))
    }

    private fun appRow(h: PermissionAudit.Holder) = ui.row {
        background = ui.pressable(ui.rounded(color(R.color.surface), 18))
        setPadding(ui.dp(10), ui.dp(10), ui.dp(8), ui.dp(10))
        val icon = synchronized(icons) { icons[h.pkg] }
        addView(ImageView(context).apply { setImageDrawable(icon) }, ui.lp(w = ui.dp(40), h = ui.dp(40)))
        addView(ui.column {
            addView(ui.text(15f, color(R.color.ink), ui.medium) { text = h.label; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(ui.text(12f, color(R.color.ink_soft)) {
                text = listOfNotNull(if (h.system) "System" else null, h.detail.takeIf { it.isNotEmpty() }, lastUsed(h))
                    .joinToString(" · ").ifEmpty { h.pkg }
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            }, ui.lp(top = 3))
        }, ui.lp(w = 0, weight = 1f, start = 14))
        addView(ui.iconView(R.drawable.ic_chevron_right, color(R.color.ink_faint)), ui.lp(w = ui.dp(20), h = ui.dp(20), start = 4))
        contentDescription = "${h.label}, open App info"
        setOnClickListener {
            runCatching {
                host.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", h.pkg, null)))
            }
        }
    }

    private fun lastUsed(h: PermissionAudit.Holder): String? {
        val use = lastUse ?: return null
        if (h.granted != true || h.pkg !in use) return null
        val ago = use[h.pkg] ?: return "no use on record"
        val min = ago / 60_000
        return "used " + when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 48 * 60 -> "${min / 60} h ago"
            else -> "${min / (24 * 60)} days ago"
        }
    }
}
