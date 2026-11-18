// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Appearance, PC link, tool options, access the app has, and about. Opened from Home. */
internal class SettingsScreen(host: MainActivity) : Screen(host) {

    override val name = "Settings"
    override val navIcon = R.drawable.ic_settings
    override val navColor = R.color.bg

    private lateinit var themeValue: TextView
    private lateinit var textSizeValue: TextView
    private lateinit var contrastSwitch: Switch
    private lateinit var boldSwitch: Switch
    private lateinit var motionSwitch: Switch
    private lateinit var linkSwitch: Switch
    private lateinit var largeValue: TextView
    private lateinit var rulesValue: TextView
    private lateinit var dataSwitch: Switch
    private lateinit var filesValue: TextView
    private lateinit var usageValue: TextView
    private lateinit var notifyValue: TextView
    private val privileged by lazy { PrivilegedPanel(host) }
    /** A larger text size is chosen: rows show their whole subtitle instead of cutting it at two lines. */
    private val bigText = Prefs.textSize(host) != Prefs.TextSize.SYSTEM

    /** One page of Settings: a row on the first page that opens [box]. */
    private class Page(val key: String, val icon: Int, val title: String, val sub: String, val box: LinearLayout)

    companion object {
        /** Page to open the next time Settings is shown (a link from another screen, or the page that was open before a redraw). */
        var pending: String? = null
    }

    private val pages = mutableListOf<Page>()
    private var open: Page? = null
    private lateinit var title: TextView
    private lateinit var backBtn: View
    private lateinit var menu: LinearLayout

    /**
     * The first page lists the pages; a tap opens one in place, and Back (or the arrow beside the
     * title) returns to the list. Keeps every page short enough to need little or no scrolling.
     */
    override fun build(): View = ui.column {
        addView(ui.row {
            gravity = android.view.Gravity.CENTER_VERTICAL
            backBtn = ui.circleButton(R.drawable.ic_back, "Back to Settings") { showPage(null) }
            addView(backBtn, ui.lp(w = ui.dp(42), h = ui.dp(42)))
            title = ui.text(28f, color(R.color.ink), ui.light) {
                letterSpacing = -0.02f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                minimumHeight = ui.dp(42); gravity = android.view.Gravity.CENTER_VERTICAL
                if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            }
            addView(title, ui.lp(w = 0, weight = 1f, start = 2))
        })

        page("display", R.drawable.ic_moon, "Display", "Theme, text size, contrast and motion") {
            addView(ui.glassCard(6) {
                themeValue = valueRow(R.drawable.ic_moon, "Theme", "Light, dark or the same as your phone") { pickTheme() }
            }, ui.lp(top = 20))
            // Each of these redraws the app (like Theme), so the switches only act on a real change.
            addView(ui.section("Accessibility"), ui.lp(top = 26))
            addView(ui.glassCard(6) {
                textSizeValue = valueRow(R.drawable.ic_text_size, "Text size", "Makes the text in this app bigger") { pickTextSize() }
                contrastSwitch = switchRow(R.drawable.ic_contrast, "High contrast", "Grey text and thin lines become as dark as the main text") { on ->
                    if (on != Prefs.highContrast(host)) { Prefs.setHighContrast(host, on); redraw() }
                }
                boldSwitch = switchRow(R.drawable.ic_bold, "Bold text", "Heavier letters everywhere in the app") { on ->
                    if (on != Prefs.boldText(host)) { Prefs.setBoldText(host, on); redraw() }
                }
                motionSwitch = switchRow(R.drawable.ic_motion, "Reduce motion", "Gauges jump to their value and pages don't glide when they scroll") { on ->
                    Prefs.setReduceMotion(host, on)
                }
                valueRow(R.drawable.ic_accessibility, "Phone accessibility settings", "TalkBack, magnification, colour correction and more") {
                    runCatching { host.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }.text = ""
            }, ui.lp(top = 12))
        }

        page("link", R.drawable.ic_usb, "PC link", "When the link to your computer starts") {
            addView(ui.glassCard(6) {
                linkSwitch = switchRow(R.drawable.ic_usb, "Start when the app opens",
                    "Andromon can still start the link over USB when this is off") { on ->
                    Prefs.setStartLinkOnOpen(host, on)
                }
            }, ui.lp(top = 20))
        }

        page("tools", R.drawable.ic_broom, "Tools", "Junk Cleaner rules, speed tests and automation") {
            addView(ui.glassCard(6) {
                rulesValue = valueRow(R.drawable.ic_broom, "Cleaning rules", "What the Junk Cleaner looks for") { pickRules() }
                largeValue = valueRow(R.drawable.ic_big_file, "Large files", "Size from which the Junk Cleaner lists a file") { pickLargeSize() }
                dataSwitch = switchRow(R.drawable.ic_speed, "Save data on speed tests",
                    "Always run the shorter test (about 35 MB), also on Wi‑Fi") { on -> Prefs.setSaveData(host, on) }
            }, ui.lp(top = 20))
        }

        page("access", R.drawable.ic_lock, "Access", "Files, usage, notifications and permissions") {
            addView(ui.glassCard(6) {
                filesValue = valueRow(R.drawable.ic_folder, "All files access", "Lets the Junk Cleaner scan your storage") {
                    openAppSetting(if (Build.VERSION.SDK_INT >= 30) Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION else null)
                }
                usageValue = valueRow(R.drawable.ic_apps, "Usage access", "Lets the app measure app sizes and caches") {
                    openAppSetting(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                }
                notifyValue = valueRow(R.drawable.ic_info, "Notifications", "PC link status and Wi‑Fi receive requests") {
                    runCatching {
                        host.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, host.packageName))
                    }.onFailure { openAppSetting(null) }
                }
                valueRow(R.drawable.ic_lock, "App permissions", "Messages, contacts, call log and more") { openAppSetting(null) }
                    .text = ""
            }, ui.lp(top = 20))
        }

        // Shizuku, the Andromon helper, root (PrivilegedPanel.kt)
        page("privileged", R.drawable.ic_key, "Privileged access", "Shizuku, the Andromon helper or root") {
            privileged.addTo(this)
        }

        page("about", R.drawable.ic_info, "About", "Version, your data and licences") {
            addView(ui.glassCard(6) {
                val version = runCatching { host.packageManager.getPackageInfo(host.packageName, 0).versionName }.getOrNull()
                valueRow(R.drawable.ic_desktop, "Andromon Companion", "Helper app for Andromon on your computer", null).text =
                    version?.let { "v$it" } ?: ""
                valueRow(R.drawable.ic_shield, "Your data", "What the app reads and where it goes") { showPrivacy() }.text = ""
                valueRow(R.drawable.ic_lock, "Licence", "Free software under the GNU GPL version 3") { showLicence() }.text = "GPL-3.0"
                valueRow(R.drawable.ic_file, "Third-party notices", "SD Maid SE, SuperFreezZ, Exodus Privacy, the libraries, Poppins and Lucide icons") { showThirdParty() }.text = ""
            }, ui.lp(top = 20))
        }

        menu = ui.glassCard(6) {
            for (p in pages) valueRow(p.icon, p.title, p.sub) { showPage(p) }.text = ""
        }
        addView(menu, 1, ui.lp(top = 20))
        showPage(null)
    }

    /** Declares a page: built now, hidden until its row on the first page is tapped. */
    private fun LinearLayout.page(key: String, icon: Int, title: String, sub: String, content: LinearLayout.() -> Unit) {
        val box = ui.column(content).apply { visibility = View.GONE }
        pages += Page(key, icon, title, sub, box)
        addView(box)
    }

    /** Shows [page], or the list of pages when it is null. */
    private fun showPage(page: Page?) {
        open = page
        title.text = page?.title ?: "Settings"
        backBtn.visibility = if (page == null) View.GONE else View.VISIBLE
        title.setPadding(if (page == null) 0 else ui.dp(10), 0, 0, 0)
        menu.visibility = if (page == null) View.VISIBLE else View.GONE
        for (p in pages) p.box.visibility = if (p === page) View.VISIBLE else View.GONE
        host.scrollToTop()
    }

    /** Redraws the whole app for a new look and comes back to the page that is open. */
    private fun redraw() {
        pending = open?.key
        host.recreate()
    }

    override fun onBack(): Boolean {
        if (open == null) return false
        showPage(null)
        return true
    }

    override fun onLeave() { if (pending == null) showPage(null) }

    override fun onShow() {
        view // builds the pages on first use
        pending?.let { key -> pages.firstOrNull { it.key == key }?.let(::showPage) }
        pending = null
        refresh(); privileged.onShow()
    }

    override fun onHide() = privileged.onHide()

    override fun onPermissionsResult() = refresh()

    private fun refresh() {
        themeValue.text = Prefs.theme(host).label
        textSizeValue.text = Prefs.textSize(host).label
        contrastSwitch.isChecked = Prefs.highContrast(host)
        boldSwitch.isChecked = Prefs.boldText(host)
        motionSwitch.isChecked = Prefs.reduceMotionPref(host)
        linkSwitch.isChecked = Prefs.startLinkOnOpen(host)
        largeValue.text = sizeLabel(Prefs.largeFileMb(host))
        val rules = Prefs.CLEANING_RULES
        rulesValue.text = "${rules.count { Prefs.ruleOn(host, it.first) }} of ${rules.size} on"
        dataSwitch.isChecked = Prefs.saveData(host)
        state(filesValue, Cleaner.hasFileAccess(host))
        state(usageValue, Cleaner.hasUsageAccess(host))
        state(notifyValue, Build.VERSION.SDK_INT < 33 ||
            host.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }

    private fun state(v: TextView, on: Boolean) {
        v.text = if (on) "Allowed" else "Off"
        v.setTextColor(color(if (on) R.color.good else R.color.danger))
    }

    private fun sizeLabel(mb: Int) = if (mb >= 1000) "${mb / 1000} GB" else "$mb MB"

    // ---------- rows ----------

    /** Icon, title and subtitle; returns the value text shown on the right. */
    private fun LinearLayout.valueRow(icon: Int, title: String, sub: String, onClick: (() -> Unit)?): TextView {
        // At a larger text size the value may wrap, so the title and subtitle keep most of the row.
        val value = ui.text(13f, color(R.color.ink_soft), ui.medium) {
            maxLines = if (bigText) 2 else 1
            if (bigText) { maxWidth = ui.dp(104); gravity = android.view.Gravity.END }
        }
        addView(baseRow(icon, title, sub, onClick) {
            addView(value, ui.lp(w = WRAP_CONTENT, start = 8))
            if (onClick != null) addView(ui.iconView(R.drawable.ic_chevron_right, color(R.color.ink_faint)),
                ui.lp(w = ui.dp(20), h = ui.dp(20), start = 4))
        })
        return value
    }

    private fun LinearLayout.switchRow(icon: Int, title: String, sub: String, onToggle: (Boolean) -> Unit): Switch {
        val sw = ui.toggle().apply { setOnCheckedChangeListener { _, on -> onToggle(on) } }
        val row = baseRow(icon, title, sub, { sw.toggle() }) { addView(sw, ui.lp(w = WRAP_CONTENT, start = 8)) }
        asOneSwitch(row, sw, title, sub)
        addView(row)
        return sw
    }

    private fun baseRow(icon: Int, title: String, sub: String, onClick: (() -> Unit)?, trailing: LinearLayout.() -> Unit) = ui.row {
        background = if (onClick != null) ui.pressable(ui.rounded(Color.TRANSPARENT, 20)) else null
        setPadding(ui.dp(10), ui.dp(12), ui.dp(8), ui.dp(12))
        addView(ui.roundIcon(40, color(R.color.glass_strong), icon, color(R.color.ink), 19), ui.lp(w = ui.dp(40), h = ui.dp(40)))
        addView(ui.column {
            addView(ui.text(15f, color(R.color.ink), ui.medium) { text = title; maxLines = 2 })
            addView(ui.text(12f, color(R.color.ink_soft)) {
                text = sub; maxLines = if (bigText) 6 else 2; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(ui.dp(1).toFloat(), 1f)
            }, ui.lp(top = 2))
        }, ui.lp(w = 0, weight = 1f, start = 12))
        trailing()
        if (onClick != null) setOnClickListener { onClick() }
    }

    /** A screen reader reads [row] as one item ("High contrast, off, switch") instead of stopping on the switch separately. */
    private fun asOneSwitch(row: View, sw: Switch, title: String, sub: String) {
        sw.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        row.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(v: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(v, info)
                info.className = Switch::class.java.name
                info.isCheckable = true
                info.isChecked = sw.isChecked
                info.contentDescription = "$title. $sub"
            }
        }
    }

    // ---------- actions ----------

    private fun pickTheme() {
        val options = Prefs.Theme.values()
        val current = options.indexOf(Prefs.theme(host))
        AlertDialog.Builder(host)
            .setTitle("Theme")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), current) { d, which ->
                d.dismiss()
                if (which != current) {
                    Prefs.setTheme(host, options[which])
                    redraw() // reloads every colour; Settings opens on the same page again
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickTextSize() {
        val options = Prefs.TextSize.values()
        val current = options.indexOf(Prefs.textSize(host))
        AlertDialog.Builder(host)
            .setTitle("Text size")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), current) { d, which ->
                d.dismiss()
                if (which != current) {
                    Prefs.setTextSize(host, options[which])
                    redraw() // every text is measured again at the new size
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Switch Junk Cleaner rules on or off; switched-off rules aren't scanned at all. */
    private fun pickRules() {
        val rules = Prefs.CLEANING_RULES
        val on = BooleanArray(rules.size) { Prefs.ruleOn(host, rules[it].first) }
        AlertDialog.Builder(host)
            .setTitle("Cleaning rules")
            .setMultiChoiceItems(rules.map { it.second }.toTypedArray(), on) { _, which, checked -> on[which] = checked }
            .setPositiveButton("Save") { _, _ ->
                Prefs.setRules(host, rules.indices.associate { rules[it].first to on[it] })
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickLargeSize() {
        val choices = Prefs.LARGE_FILE_CHOICES
        val current = choices.indexOf(Prefs.largeFileMb(host))
        AlertDialog.Builder(host)
            .setTitle("List files larger than")
            .setSingleChoiceItems(choices.map(::sizeLabel).toTypedArray(), current) { d, which ->
                Prefs.setLargeFileMb(host, choices[which])
                d.dismiss()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Opens [action] for this app, falling back to the general screen and then to App info. */
    private fun openAppSetting(action: String?) {
        val pkg = Uri.fromParts("package", host.packageName, null)
        val tries = listOfNotNull(
            action?.let { Intent(it, pkg) },
            action?.let { Intent(it) },
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
        )
        for (i in tries) if (runCatching { host.startActivity(i) }.isSuccess) return
    }

    private fun showPrivacy() = textDialog("Your data",
        "• Messages, contacts and call log are read only when Andromon asks over the USB cable, and only " +
            "go to that computer. Nothing is uploaded.\n\n" +
            "• The internet is used only for the speed test (speed.cloudflare.com), ping and DNS lookups you start, " +
            "and Wi‑Fi receive while you have it on.\n\n" +
            "• The camera is used only while you scan a code in Send; the picture is read on the phone and not kept.\n\n" +
            "• The Junk Cleaner and App Manager work on the phone itself. Nothing is deleted or uninstalled " +
            "until you confirm.\n\n" +
            "• Settings are stored only on this phone.")

    private fun raw(id: Int, fallback: String) =
        runCatching { host.resources.openRawResource(id).bufferedReader().use { it.readText() } }.getOrDefault(fallback)

    /** GPL-3.0 section 5(d): the "Appropriate Legal Notices" an interactive program must show. */
    private fun showLicence() {
        AlertDialog.Builder(host)
            .setTitle("Licence")
            .setView(dialogText(
                "Andromon Companion\n" +
                    "Copyright (C) 2026 The Andromon authors\n\n" +
                    "This program is free software: you can redistribute it and/or modify it under the terms of " +
                    "the GNU General Public License as published by the Free Software Foundation, version 3.\n\n" +
                    "This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; " +
                    "without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. " +
                    "See the GNU General Public License for more details.\n\n" +
                    "You should have received a copy of the GNU General Public License along with this program. " +
                    "If not, see https://www.gnu.org/licenses/."))
            .setNeutralButton("Full licence") { _, _ ->
                textDialog("GNU GPL version 3", raw(R.raw.gpl_3_0, "https://www.gnu.org/licenses/gpl-3.0.html"))
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showThirdParty() {
        AlertDialog.Builder(host)
            .setTitle("Third-party notices")
            .setView(dialogText(
                "SD Maid SE\n" +
                    "The Junk Cleaner's cleaning rules (System Cleaner filters and CorpseFinder) and its " +
                    "leftover-folder database are ported from SD Maid SE by the SD Maid SE authors " +
                    "(github.com/d4rken-org/sdmaid-se), GNU GPL version 3.\n\n" +
                    "SuperFreezZ\n" +
                    "The Freezer's freeze modes, its “not used for a while” rule, the freeze on screen off and the " +
                    "accessibility method are ported from SuperFreezZ, Copyright (c) 2018 Hocuri " +
                    "(gitlab.com/SuperFreezZ/SuperFreezZ), GNU GPL version 3 or later.\n\n" +
                    "Shizuku-API\n" +
                    "The library used to reach the Shizuku app (dev.rikka.shizuku:api and provider 13.1.5), " +
                    "Copyright (c) 2021 RikkaW, MIT License: permission is granted, free of charge, to use, copy, modify, " +
                    "merge, publish, distribute, sublicense and sell copies of the software, provided this copyright and " +
                    "permission notice is included. The software is provided “as is”, without warranty of any kind.\n\n" +
                    "libsu\n" +
                    "Root access for Privileged access (com.github.topjohnwu.libsu:core 6.0.0), Copyright John Wu, " +
                    "Apache License, Version 2.0.\n\n" +
                    "ZXing\n" +
                    "Reads the QR code in Send › Scan code (com.google.zxing:core 3.5.3), Copyright ZXing authors, " +
                    "Apache License, Version 2.0.\n\n" +
                    "Poppins font\n" +
                    "Copyright 2020 The Poppins Project Authors. SIL Open Font License 1.1. " +
                    "This app includes it subset to Latin characters.\n\n" +
                    "Lucide icons\n" +
                    "The app's icons are from Lucide (lucide.dev), Copyright (c) Lucide Icons and Contributors, " +
                    "ISC License: permission to use, copy, modify and distribute is granted, provided the copyright " +
                    "and permission notice appear in all copies. Some Lucide icons derive from Feather, " +
                    "Copyright (c) 2013-present Cole Bemis, MIT License. Provided “as is”, without warranty.\n\n" +
                    "Material icons\n" +
                    "The Freeze shortcut icon is based on Material Icons, Copyright Google LLC, " +
                    "licensed under the Apache License, Version 2.0."))
            .setNeutralButton("Font licence") { _, _ -> textDialog("SIL Open Font License 1.1", raw(R.raw.poppins_ofl, "SIL Open Font License 1.1")) }
            .setPositiveButton("Apache 2.0") { _, _ -> textDialog("Apache License 2.0", raw(R.raw.apache_2_0, "https://www.apache.org/licenses/LICENSE-2.0")) }
            .show()
    }

    private fun dialogText(body: String) = ScrollView(host).apply {
        addView(ui.text(13f, color(R.color.ink)) {
            text = body
            setLineSpacing(ui.dp(3).toFloat(), 1f)
            setTextIsSelectable(true)
            setPadding(ui.dp(24), ui.dp(8), ui.dp(24), ui.dp(8))
        })
    }

    private fun textDialog(title: String, body: String) {
        val tv = ui.text(13f, color(R.color.ink)) {
            text = body
            setLineSpacing(ui.dp(3).toFloat(), 1f)
            setTextIsSelectable(true)
            setPadding(ui.dp(24), ui.dp(8), ui.dp(24), ui.dp(8))
        }
        AlertDialog.Builder(host)
            .setTitle(title)
            .setView(ScrollView(host).apply { addView(tv) })
            .setPositiveButton("Close", null)
            .show()
    }
}
