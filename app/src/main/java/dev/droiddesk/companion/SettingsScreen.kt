// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

package dev.droiddesk.companion

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
    private lateinit var linkSwitch: Switch
    private lateinit var largeValue: TextView
    private lateinit var rulesValue: TextView
    private lateinit var dataSwitch: Switch
    private lateinit var filesValue: TextView
    private lateinit var usageValue: TextView
    private lateinit var notifyValue: TextView

    override fun build(): View = ui.column {
        addView(ui.topBar("Settings", host::goHome).first)

        addView(ui.section("Appearance"), ui.lp(top = 28))
        addView(ui.glassCard(6) {
            themeValue = valueRow(R.drawable.ic_moon, "Theme", "Light, dark or the same as your phone") { pickTheme() }
        }, ui.lp(top = 12))

        addView(ui.section("PC link"), ui.lp(top = 26))
        addView(ui.glassCard(6) {
            linkSwitch = switchRow(R.drawable.ic_usb, "Start when the app opens",
                "Droid Desk can still start the link over USB when this is off") { on ->
                Prefs.setStartLinkOnOpen(host, on)
            }
        }, ui.lp(top = 12))

        addView(ui.section("Tools"), ui.lp(top = 26))
        addView(ui.glassCard(6) {
            rulesValue = valueRow(R.drawable.ic_broom, "Cleaning rules", "What the Junk Cleaner looks for") { pickRules() }
            largeValue = valueRow(R.drawable.ic_big_file, "Large files", "Size from which the Junk Cleaner lists a file") { pickLargeSize() }
            dataSwitch = switchRow(R.drawable.ic_speed, "Save data on speed tests",
                "Always run the shorter test (about 35 MB), also on Wi‑Fi") { on -> Prefs.setSaveData(host, on) }
        }, ui.lp(top = 12))

        addView(ui.section("Access"), ui.lp(top = 26))
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
        }, ui.lp(top = 12))

        addView(ui.section("About"), ui.lp(top = 26))
        addView(ui.glassCard(6) {
            val version = runCatching { host.packageManager.getPackageInfo(host.packageName, 0).versionName }.getOrNull()
            valueRow(R.drawable.ic_desktop, "Droid Desk Companion", "Helper app for Droid Desk on your computer", null).text =
                version?.let { "v$it" } ?: ""
            valueRow(R.drawable.ic_shield, "Your data", "What the app reads and where it goes") { showPrivacy() }.text = ""
            valueRow(R.drawable.ic_lock, "Licence", "Free software under the GNU GPL version 3") { showLicence() }.text = "GPL-3.0"
            valueRow(R.drawable.ic_file, "Third-party notices", "SD Maid SE, Poppins font and Material icons") { showThirdParty() }.text = ""
        }, ui.lp(top = 12))
    }

    override fun onShow() = refresh()

    override fun onPermissionsResult() = refresh()

    private fun refresh() {
        themeValue.text = Prefs.theme(host).label
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
        val value = ui.text(13f, color(R.color.ink_soft), ui.medium) { maxLines = 1 }
        addView(baseRow(icon, title, sub, onClick) {
            addView(value, ui.lp(w = WRAP_CONTENT, start = 8))
            if (onClick != null) addView(ui.iconView(R.drawable.ic_chevron_right, color(R.color.ink_faint)),
                ui.lp(w = ui.dp(20), h = ui.dp(20), start = 4))
        })
        return value
    }

    private fun LinearLayout.switchRow(icon: Int, title: String, sub: String, onToggle: (Boolean) -> Unit): Switch {
        val sw = ui.toggle().apply { setOnCheckedChangeListener { _, on -> onToggle(on) } }
        addView(baseRow(icon, title, sub, { sw.toggle() }) { addView(sw, ui.lp(w = WRAP_CONTENT, start = 8)) })
        return sw
    }

    private fun baseRow(icon: Int, title: String, sub: String, onClick: (() -> Unit)?, trailing: LinearLayout.() -> Unit) = ui.row {
        background = if (onClick != null) ui.pressable(ui.rounded(Color.TRANSPARENT, 20)) else null
        setPadding(ui.dp(10), ui.dp(12), ui.dp(8), ui.dp(12))
        addView(ui.roundIcon(40, color(R.color.glass_strong), icon, color(R.color.ink), 19), ui.lp(w = ui.dp(40), h = ui.dp(40)))
        addView(ui.column {
            addView(ui.text(15f, color(R.color.ink), ui.medium) { text = title; maxLines = 2 })
            addView(ui.text(12f, color(R.color.ink_soft)) {
                text = sub; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(ui.dp(1).toFloat(), 1f)
            }, ui.lp(top = 2))
        }, ui.lp(w = 0, weight = 1f, start = 12))
        trailing()
        if (onClick != null) setOnClickListener { onClick() }
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
                    host.recreate() // reloads every colour; the Settings screen is restored
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
        "• Messages, contacts and call log are read only when Droid Desk asks over the USB cable, and only " +
            "go to that computer. Nothing is uploaded.\n\n" +
            "• The internet is used only for the speed test (speed.cloudflare.com), ping and DNS lookups you start, " +
            "and Wi‑Fi receive while you have it on.\n\n" +
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
                "Droid Desk Companion\n" +
                    "Copyright (C) 2026 The Droid Desk authors\n\n" +
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
                    "Poppins font\n" +
                    "Copyright 2020 The Poppins Project Authors. SIL Open Font License 1.1. " +
                    "This app includes it subset to Latin characters.\n\n" +
                    "Material icons\n" +
                    "Many of the app's icons are based on Material Icons, Copyright Google LLC, " +
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
