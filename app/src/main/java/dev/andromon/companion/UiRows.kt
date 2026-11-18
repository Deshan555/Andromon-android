// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.Switch
import android.widget.TextView

/**
 * The setting rows of the Freezer, the Battery saver and Settings › Privileged access: icon, title
 * and a line of explanation, with a value, a switch or a button on the right. Same look as the
 * rows in [SettingsScreen].
 */
internal class UiRows(private val ctx: Context, private val ui: Ui) {

    private fun c(id: Int) = ui.color(id)

    /** A row with its parts, so screens can change the explanation or grey it out later. */
    class Row(val view: LinearLayout, val title: TextView, val sub: TextView, val value: TextView?, val switch: Switch?) {
        /** Greys the row out and says why in its second line. */
        fun available(on: Boolean, ui: Ui, why: String? = null, normal: String? = null) {
            view.isEnabled = on
            view.alpha = if (on) 1f else 0.5f
            switch?.isEnabled = on
            (if (on) normal else why)?.let { sub.text = it }
        }
    }

    private fun base(icon: Int, title: String, sub: String, onClick: (() -> Unit)?, trailing: LinearLayout.() -> Unit): Triple<LinearLayout, TextView, TextView> {
        val subView = ui.text(12f, c(R.color.ink_soft)) {
            text = sub; maxLines = 4; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(ui.dp(1).toFloat(), 1f)
        }
        val titleView = ui.text(15f, c(R.color.ink), ui.medium) { text = title; maxLines = 2 }
        val row = ui.row {
            background = if (onClick != null) ui.pressable(ui.rounded(Color.TRANSPARENT, 20)) else null
            setPadding(ui.dp(10), ui.dp(12), ui.dp(8), ui.dp(12))
            addView(ui.roundIcon(40, c(R.color.glass_strong), icon, c(R.color.ink), 19), ui.lp(w = ui.dp(40), h = ui.dp(40)))
            addView(ui.column {
                addView(titleView)
                addView(subView, ui.lp(top = 2))
            }, ui.lp(w = 0, weight = 1f, start = 12))
            trailing()
            if (onClick != null) setOnClickListener { if (isEnabled) onClick() }
        }
        return Triple(row, titleView, subView)
    }

    /** Value on the right, chevron when tappable. */
    fun value(parent: LinearLayout, icon: Int, title: String, sub: String, onClick: (() -> Unit)?): Row {
        val value = ui.text(13f, c(R.color.ink_soft), ui.medium) { maxLines = 1 }
        val (row, titleView, subView) = base(icon, title, sub, onClick) {
            addView(value, ui.lp(w = WRAP_CONTENT, start = 8))
            if (onClick != null) addView(ui.iconView(R.drawable.ic_chevron_right, c(R.color.ink_faint)),
                ui.lp(w = ui.dp(20), h = ui.dp(20), start = 4))
        }
        parent.addView(row)
        return Row(row, titleView, subView, value, null)
    }

    /**
     * A switch. [onToggle] is only called for taps, not when the screen sets the state; it gets
     * the wanted state and decides itself whether the switch really moves (set `isChecked`).
     */
    fun switch(parent: LinearLayout, icon: Int, title: String, sub: String, onToggle: (Boolean) -> Unit): Row {
        val sw = ui.toggle()
        sw.isClickable = false // the whole row is the tap target
        val (row, titleView, subView) = base(icon, title, sub, { onToggle(!sw.isChecked) }) { addView(sw, ui.lp(w = WRAP_CONTENT, start = 8)) }
        parent.addView(row)
        return Row(row, titleView, subView, null, sw)
    }

    /** Plain explanation inside a card. */
    fun note(parent: LinearLayout, text: String): TextView {
        val v = ui.text(13f, c(R.color.ink_soft)) {
            this.text = text; setLineSpacing(ui.dp(2).toFloat(), 1f)
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(10))
        }
        parent.addView(v)
        return v
    }

    /** One-of-many dialog. */
    fun <T> pick(title: String, choices: List<T>, current: T, label: (T) -> String, onPick: (T) -> Unit) {
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setSingleChoiceItems(choices.map(label).toTypedArray(), choices.indexOf(current)) { d, which ->
                d.dismiss()
                onPick(choices[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun message(title: String, body: String, button: String = "Close", onButton: (() -> Unit)? = null) {
        AlertDialog.Builder(ctx).setTitle(title).setMessage(body)
            .setPositiveButton(button) { _, _ -> onButton?.invoke() }
            .apply { if (onButton != null) setNegativeButton("Cancel", null) }
            .show()
    }

    @Suppress("unused") fun gone(v: View, gone: Boolean) { v.visibility = if (gone) View.GONE else View.VISIBLE }
}
