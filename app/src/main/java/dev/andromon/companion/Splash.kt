// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.max

/**
 * The start-up splash: the Andromon logo on its dark backdrop with a bar that fills over
 * [DURATION_MS], laid over MainActivity's content when the person opens the app. Android's own
 * splash (LaunchTheme) is the same plain backdrop, so this one takes over without a jump.
 *
 * The time is counted from the clock (from the first frame on screen), not by an animator, so it
 * stays five seconds with Android's animations off. With reduce motion on, the bar still fills but the logo doesn't fade in and the
 * splash doesn't fade out.
 */
internal class Splash(private val host: MainActivity, private val ui: Ui, private val onDone: () -> Unit) {

    companion object {
        const val DURATION_MS = 5000L
        private const val FADE_MS = 250L
    }

    private val calm = Prefs.reduceMotion(host)
    private val bar = GradientBar(host).apply {
        colors(Color.argb(38, 255, 255, 255), 0xFF9CD237.toInt(), 0xFFE3F786.toInt())
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val logo = ImageView(host).apply {
        setImageResource(R.drawable.brand_wordmark)
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** The overlay itself: full screen, takes every touch, read out as one item. */
    val view: FrameLayout = FrameLayout(host).apply {
        setBackgroundColor(ui.color(R.color.splash_bg))
        isClickable = true
        isFocusable = true
        contentDescription = "Andromon is starting"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        // The logo a little above the middle (where Android's own splash puts its icon), the bar under it.
        val width = (host.resources.displayMetrics.widthPixels * 0.72f).toInt().coerceAtMost(ui.dp(340))
        addView(ui.column {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(logo, ui.lp(w = width))
            addView(bar, ui.lp(w = (width * 0.62f).toInt(), h = ui.dp(6), top = 36))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private var start = 0L
    private var finished = false

    private val tick = object : Runnable {
        override fun run() {
            if (finished) return
            // The clock starts with the first frame on screen: a slow start shouldn't eat the five seconds.
            if (start == 0L) { start = SystemClock.uptimeMillis(); fadeIn() }
            val f = (SystemClock.uptimeMillis() - start) / DURATION_MS.toFloat()
            bar.fraction = f
            if (f >= 1f) finish() else view.postOnAnimation(this)
        }
    }

    /** Adds the overlay on top of [parent] and starts the clock. */
    fun show(parent: FrameLayout) {
        parent.addView(view, FrameLayout.LayoutParams(-1, -1))
        if (!calm) { logo.alpha = 0f; logo.scaleX = 0.94f; logo.scaleY = 0.94f }
        view.postOnAnimation(tick)
        view.post { view.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED) }
    }

    private fun fadeIn() {
        if (!calm) logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(450).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    val showing get() = !finished

    private fun finish() {
        finished = true
        bar.fraction = 1f
        onDone()
    }

    /** Takes the overlay away: a short fade, or at once with reduce motion on. */
    fun remove() {
        finished = true
        view.removeCallbacks(tick)
        val parent = view.parent as? ViewGroup ?: return
        if (calm || !view.isAttachedToWindow) { parent.removeView(view); return }
        view.isClickable = false
        view.animate().alpha(0f).setDuration(FADE_MS).withEndAction { parent.removeView(view) }.start()
    }

    /** Takes the overlay away without a fade (it is covered by another screen). */
    fun removeNow() {
        finished = true
        view.removeCallbacks(tick)
        view.animate().cancel()
        (view.parent as? ViewGroup)?.removeView(view)
    }
}

/** Rounded bar on a faint track, filled with a horizontal gradient from [colors]' start to end. */
internal class GradientBar(ctx: Context) : View(ctx) {
    var fraction = 0f
        set(v) { val c = v.coerceIn(0f, 1f); if (c != field) { field = c; invalidate() } }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var from = 0
    private var to = 0

    fun colors(trackColor: Int, start: Int, end: Int) {
        track.color = trackColor; from = start; to = end; fill.shader = null; invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { fill.shader = null }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, track)
        if (fraction <= 0f) return
        // The gradient spans the whole track, so the colour under the bar's end shows how far it is.
        if (fill.shader == null) fill.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, from, to, Shader.TileMode.CLAMP)
        rect.right = max(height.toFloat(), width * fraction)
        canvas.drawRoundRect(rect, r, r, fill)
    }
}
