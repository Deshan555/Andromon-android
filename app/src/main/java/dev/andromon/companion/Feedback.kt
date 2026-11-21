// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * Settings › About › Report a bug / Send feedback. Nothing is sent from the app: it opens a
 * github.com page in the browser with the person's text (and, if they leave the box ticked, the
 * app and phone versions) filled in, and they press Submit there with their own GitHub account.
 *   Bug      → an issue in Andromon_Releases through .github/ISSUE_TEMPLATE/bug_report.yml; each
 *              query parameter fills the form field with the same id (what, steps, env, extra).
 *   Feedback → a discussion in the repo's "General" category (title and body).
 * The PC app builds the same links (pc/src/feedback.js): keep the two in step.
 */
internal object Feedback {
    private const val REPO = "https://github.com/Deshan555/Andromon_Releases"
    private const val CATEGORY = "general" // a Discussions category slug in that repo
    private const val MAX_URL = 7000 // GitHub refuses very long links; the longest text is shortened first

    /** App and phone versions; nothing personal (no names, serials or accounts). */
    private fun details(host: MainActivity): String {
        val version = runCatching { host.packageManager.getPackageInfo(host.packageName, 0) }.getOrNull()
        val code = version?.longVersionCode ?: 0
        return "Companion: ${version?.versionName ?: "?"} (build ${BuildConfig.BUILD_NUMBER}, $code)\n" +
            "Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }

    fun bug(host: MainActivity) = open(host, bug = true)
    fun feedback(host: MainActivity) = open(host, bug = false)

    private fun open(host: MainActivity, bug: Boolean) {
        val ui = host.ui
        fun input(hintText: String, lines: Int) = EditText(host).apply {
            hint = hintText
            textSize = 15f
            setTextColor(host.getColor(R.color.ink))
            setHintTextColor(host.getColor(R.color.ink_faint))
            background = ui.rounded(host.getColor(R.color.glass_strong), 16)
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            if (lines == 1) { isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
            else {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = lines; gravity = android.view.Gravity.TOP or android.view.Gravity.START
            }
        }
        fun label(text: String) = ui.text(13f, host.getColor(R.color.ink), ui.medium) { this.text = text }
        fun note(text: String) = ui.text(12f, host.getColor(R.color.ink_soft)) { this.text = text; setLineSpacing(ui.dp(1).toFloat(), 1f) }

        val title = input(if (bug) "e.g. Wi‑Fi receive stops halfway" else "A few words (optional)", 1)
        val what = input(if (bug) "What happened, and any error you saw" else "What you like, what's missing, what's confusing, an idea…", if (bug) 4 else 6)
        val steps = input("1. Open …\n2. Tap …", 3)
        val env = details(host)
        val withDetails = CheckBox(host).apply { text = "Include app and phone details (no personal data)"; isChecked = true; textSize = 13f }
        val envView = note(env).apply { background = ui.rounded(host.getColor(R.color.glass), 12); setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)); typeface = android.graphics.Typeface.MONOSPACE }
        withDetails.setOnCheckedChangeListener { _, on -> envView.visibility = if (on) View.VISIBLE else View.GONE }
        val crash = if (bug) CrashReports.last(host) else null
        val withCrash = crash?.let { CheckBox(host).apply { text = "Add the last crash report"; isChecked = true; textSize = 13f } }

        val body = ui.column {
            setPadding(ui.dp(22), ui.dp(8), ui.dp(22), ui.dp(8))
            addView(note(if (bug) "Something doesn't work? Tell us what happened and we'll look into it."
                else "Ideas, things you like, things that are missing or confusing: all of it helps."))
            addView(label("Title" + if (bug) "" else " (optional)"), ui.lp(top = 14)); addView(title, ui.lp(top = 6))
            addView(label(if (bug) "What went wrong?" else "Your feedback"), ui.lp(top = 12)); addView(what, ui.lp(top = 6))
            if (bug) { addView(label("How can we make it happen? (optional)"), ui.lp(top = 12)); addView(steps, ui.lp(top = 6)) }
            addView(withDetails, ui.lp(top = 10)); addView(envView, ui.lp(top = 2))
            withCrash?.let { addView(it, ui.lp(top = 4)) }
            addView(note("Opens GitHub in your browser with this filled in. Check it there and press Submit (a free GitHub " +
                "account is needed). Nothing is sent before that, and what you post is public. No account? Use Share."), ui.lp(top = 12))
        }

        val dialog = AlertDialog.Builder(host)
            .setTitle(if (bug) "Report a bug" else "Send feedback")
            .setView(ScrollView(host).apply { addView(body) })
            .setPositiveButton("Open GitHub", null)
            .setNeutralButton("Share…", null)
            .setNegativeButton("Cancel", null)
            .create()

        fun ready(): Boolean {
            val missing = if (bug) listOf(title, what).firstOrNull { it.text.isBlank() } else what.takeIf { it.text.isBlank() }
            missing?.let {
                it.error = "Needed"; it.requestFocus()
                return false
            }
            return true
        }
        fun envText() = if (withDetails.isChecked) env else ""
        fun crashText() = if (withCrash?.isChecked == true && crash != null) "Crash report:\n```\n$crash\n```" else ""

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!ready()) return@setOnClickListener
                val url = if (bug) bugUrl(title.text.trim().toString(), what.text.trim().toString(), steps.text.trim().toString(), envText(), crashText())
                    else feedbackUrl(title.text.trim().toString(), what.text.trim().toString(), envText())
                runCatching { host.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onSuccess { Toast.makeText(host, "Check it on GitHub and press Submit", Toast.LENGTH_LONG).show(); dialog.dismiss() }
                    .onFailure { Toast.makeText(host, "No browser found. Use Share instead", Toast.LENGTH_LONG).show() }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (!ready()) return@setOnClickListener
                val text = listOf(
                    if (bug) "Bug: ${title.text.trim()}" else title.text.trim().takeIf { it.isNotEmpty() }?.let { "Feedback: $it" },
                    if (bug) "What went wrong?\n${what.text.trim()}" else what.text.trim().toString(),
                    steps.text.trim().takeIf { bug && it.isNotEmpty() }?.let { "How to make it happen\n$it" },
                    envText(), crashText(),
                ).filterNot { it.isNullOrEmpty() }.joinToString("\n\n")
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, if (bug) "Andromon Companion bug: ${title.text.trim()}" else "Andromon Companion feedback")
                    .putExtra(Intent.EXTRA_TEXT, text)
                runCatching { host.startActivity(Intent.createChooser(send, if (bug) "Share the bug report" else "Share your feedback")) }
            }
        }
        dialog.show()
    }

    /** Shortens the longest field until [make] gives a link GitHub accepts. */
    private fun fitted(fields: MutableMap<String, String>, make: (Map<String, String>) -> String): String {
        var url = make(fields)
        repeat(60) {
            if (url.length <= MAX_URL) return url
            val k = fields.filterKeys { it != "env" }.maxByOrNull { it.value.length }?.key ?: return url
            val v = fields.getValue(k)
            if (v.length < 40) return url
            fields[k] = v.take((v.length * 0.85).toInt()).trimEnd() + " …(shortened)"
            url = make(fields)
        }
        return url
    }

    private fun link(path: String, params: Map<String, String>) =
        Uri.parse("$REPO/$path").buildUpon().apply { for ((k, v) in params) if (v.isNotEmpty()) appendQueryParameter(k, v) }.build().toString()

    fun bugUrl(title: String, what: String, steps: String, env: String, extra: String) =
        fitted(linkedMapOf("title" to title, "what" to what, "steps" to steps, "env" to env, "extra" to extra)) { f ->
            link("issues/new", linkedMapOf("template" to "bug_report.yml", "title" to "[Bug] ${f["title"]}".trim()) + f.filterKeys { it != "title" })
        }

    fun feedbackUrl(title: String, text: String, env: String) =
        fitted(linkedMapOf("title" to title, "text" to text, "env" to env)) { f ->
            val t = f.getValue("text")
            val first = t.lineSequence().first().let { if (it.length > 60) it.take(57).trimEnd() + "…" else it }
            link("discussions/new", linkedMapOf("category" to CATEGORY, "title" to f.getValue("title").ifEmpty { "Feedback: $first" },
                "body" to t + if (f.getValue("env").isNotEmpty()) "\n\n---\n${f["env"]}" else ""))
        }
}
