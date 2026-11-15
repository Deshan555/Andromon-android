// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

package dev.droiddesk.companion

import android.content.Context
import android.content.res.Configuration

/** The person's choices from the Settings screen (stored in this app's private preferences). */
internal object Prefs {

    enum class Theme(val label: String) { SYSTEM("Same as phone"), LIGHT("Light"), DARK("Dark") }

    /** Size from which the cleaner lists a file under "Large files". */
    val LARGE_FILE_CHOICES = listOf(50, 100, 250, 500, 1000)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun theme(ctx: Context): Theme =
        runCatching { Theme.valueOf(sp(ctx).getString("theme", null) ?: "SYSTEM") }.getOrDefault(Theme.SYSTEM)
    fun setTheme(ctx: Context, t: Theme) = sp(ctx).edit().putString("theme", t.name).apply()

    /** Start the PC link whenever the app is opened (Droid Desk can always start it over USB). */
    fun startLinkOnOpen(ctx: Context) = sp(ctx).getBoolean("start_link", true)
    fun setStartLinkOnOpen(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("start_link", on).apply()

    fun largeFileMb(ctx: Context) = sp(ctx).getInt("large_mb", 100).takeIf { it in LARGE_FILE_CHOICES } ?: 100
    fun setLargeFileMb(ctx: Context, mb: Int) = sp(ctx).edit().putInt("large_mb", mb).apply()

    /** Junk Cleaner rules the person can switch off (key -> label). All are on by default. */
    val CLEANING_RULES = listOf(
        "sdm_logs" to "Log files", "sdm_ads" to "Advertisement caches", "sdm_analytics" to "Tracking & analytics",
        "sdm_lostdir" to "LOST.DIR", "sdm_linux" to "Linux trash", "sdm_mac" to "macOS files",
        "sdm_windows" to "Windows files", "sdm_temp" to "Temporary files", "sdm_empty" to "Empty folders",
        "sdm_thumbs" to "Thumbnail caches", "sdm_apks" to "Superfluous APKs", "sdm_screenshots" to "Old screenshots",
        "sdm_trashed" to "Android trash", "corpses" to "Leftover app folders", "dups" to "Duplicate files",
        "large" to "Large files", "cache" to "App caches on shared storage",
    )

    fun ruleOn(ctx: Context, key: String) = sp(ctx).getBoolean("rule_$key", true)
    fun setRules(ctx: Context, on: Map<String, Boolean>) =
        sp(ctx).edit().apply { on.forEach { (k, v) -> putBoolean("rule_$k", v) } }.apply()

    /** Always run the smaller speed test, even on Wi‑Fi. */
    fun saveData(ctx: Context) = sp(ctx).getBoolean("save_data", false)
    fun setSaveData(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("save_data", on).apply()

    // ---------- accessibility (Settings › Accessibility) ----------

    /** Text size inside this app, on top of the phone's own font size. */
    enum class TextSize(val label: String, val scale: Float) {
        SYSTEM("Same as phone", 1f), LARGE("Large", 1.15f), LARGER("Larger", 1.3f), LARGEST("Largest", 1.5f)
    }

    fun textSize(ctx: Context): TextSize =
        runCatching { TextSize.valueOf(sp(ctx).getString("a11y_text", null) ?: "SYSTEM") }.getOrDefault(TextSize.SYSTEM)
    fun setTextSize(ctx: Context, t: TextSize) = sp(ctx).edit().putString("a11y_text", t.name).apply()

    /** Grey secondary text and thin lines are drawn in the main text colour instead. */
    fun highContrast(ctx: Context) = sp(ctx).getBoolean("a11y_contrast", false)
    fun setHighContrast(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("a11y_contrast", on).apply()

    /** Every text weight is one step heavier. */
    fun boldText(ctx: Context) = sp(ctx).getBoolean("a11y_bold", false)
    fun setBoldText(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("a11y_bold", on).apply()

    fun reduceMotionPref(ctx: Context) = sp(ctx).getBoolean("a11y_motion", false)
    fun setReduceMotion(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("a11y_motion", on).apply()

    /** True when animations should be skipped: switched off here, or "Remove animations" is on in the phone's settings. */
    fun reduceMotion(ctx: Context): Boolean = reduceMotionPref(ctx) || runCatching {
        android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

    /** The uiMode to force for the chosen theme, or null to follow the phone. */
    fun nightOverride(ctx: Context): Int? = when (theme(ctx)) {
        Theme.SYSTEM -> null
        Theme.LIGHT -> Configuration.UI_MODE_NIGHT_NO
        Theme.DARK -> Configuration.UI_MODE_NIGHT_YES
    }
}
