// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView

/**
 * Settings › Display › Blur personal data: names, numbers, addresses and message text are drawn
 * blurred, so screenshots of this app are safe to share. A tap on a blurred value shows it for a
 * few seconds. Android 12 and newer blur the view itself (RenderEffect); older versions cover each
 * line of its text with a solid rounded bar.
 *
 * A screen marks each view that shows personal data once, when it builds it: [always] for a value
 * that is personal whatever it says (a computer's name, an IP address), [ifFound] for text that
 * mixes normal words with what may be personal ([Pii] decides). The mark follows the view's text
 * and the setting by itself, so the code that sets the text doesn't change. Screen readers still
 * read the value: the blur is for pictures of the screen, not for the person holding the phone.
 */
internal object PrivacyBlur {

    private const val REVEAL_MS = 5000L

    @Volatile private var cached: Boolean? = null

    fun enabled(ctx: Context): Boolean = cached ?: Prefs.blurPersonal(ctx).also { cached = it }

    fun setEnabled(ctx: Context, on: Boolean) {
        Prefs.setBlurPersonal(ctx, on)
        cached = on
    }

    /** [v] shows personal data whatever it says: blurred whenever it has text (not while it shows "–"). */
    fun always(v: View) = mark(v, mixed = false)

    /** [v] mixes normal text with what may be personal data: blurred only while [Pii] finds some. */
    fun ifFound(v: TextView) = mark(v, mixed = true)

    private fun mark(v: View, mixed: Boolean) {
        val m = Mark(v, mixed)
        v.addOnAttachStateChangeListener(m)
        if (v.isAttachedToWindow) m.onViewAttachedToWindow(v)
        v.setOnTouchListener(m)
        if (Build.VERSION.SDK_INT < 31) v.foreground = Mask(m)
    }

    /** One marked view: decides before every frame whether it is drawn blurred. */
    private class Mark(val view: View, val mixed: Boolean) :
        View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener, View.OnTouchListener {

        /** True while the view is drawn blurred. */
        var applied = false
        private var revealUntil = 0L
        private var seen: CharSequence? = null
        private var seenLength = -1
        private var seenVersion = -1
        private var personal = false

        override fun onViewAttachedToWindow(v: View) {
            v.viewTreeObserver.addOnPreDrawListener(this)
            update()
        }

        override fun onViewDetachedFromWindow(v: View) {
            v.viewTreeObserver.removeOnPreDrawListener(this)
        }

        override fun onPreDraw(): Boolean {
            update()
            return true
        }

        private fun update() {
            val want = enabled(view.context) && SystemClock.uptimeMillis() >= revealUntil && personal()
            if (want == applied) return
            applied = want
            if (Build.VERSION.SDK_INT >= 31) {
                // Strong enough that no letter survives, scaled with the text so big titles blur as well as small rows.
                val r = maxOf(6f * view.resources.displayMetrics.density, ((view as? TextView)?.textSize ?: 0f) * 0.5f)
                view.setRenderEffect(if (want) RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL) else null)
            } else {
                view.invalidate() // the Mask foreground draws or not
            }
        }

        /** Whether the view shows personal data now; only checked again when its text changes. */
        private fun personal(): Boolean {
            val t = (view as? TextView)?.text ?: return true
            if (t === seen && t.length == seenLength && (!mixed || Pii.version == seenVersion)) return personal
            seen = t; seenLength = t.length; seenVersion = Pii.version
            personal = if (mixed) Pii.has(t) else t.isNotBlank() && t.toString() != "–"
            return personal
        }

        // A tap on a blurred value shows it; while it isn't blurred, touches go on as usual (rows,
        // selection, scrolling). Screen readers aren't affected, so no click action is announced.
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            if (!applied) return false
            if (e.actionMasked == MotionEvent.ACTION_UP && e.x >= 0 && e.y >= 0 && e.x <= v.width && e.y <= v.height) reveal()
            return true
        }

        private fun reveal() {
            revealUntil = SystemClock.uptimeMillis() + REVEAL_MS
            view.invalidate() // the next frame drops the blur
            view.postDelayed({ view.invalidate() }, REVEAL_MS + 50) // and this one brings it back
        }
    }

    /** Android 8 to 11 (no RenderEffect): a solid rounded bar over each line of text, or over the whole view. */
    private class Mask(private val m: Mark) : Drawable() {
        private val density = m.view.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = m.view.context.getColor(R.color.ink_faint) }
        private val rect = RectF()

        override fun draw(c: Canvas) {
            if (!m.applied) return
            val radius = 6 * density
            val tv = m.view as? TextView
            val layout = tv?.layout
            if (tv == null || layout == null) {
                rect.set(bounds)
                c.drawRoundRect(rect, radius, radius, paint)
                return
            }
            // Where TextView draws its layout: inside the padding, moved down for centred or bottom gravity.
            val box = tv.height - tv.extendedPaddingTop - tv.extendedPaddingBottom
            val spare = (box - layout.height).coerceAtLeast(0)
            val dy = tv.extendedPaddingTop - tv.scrollY + when (tv.gravity and Gravity.VERTICAL_GRAVITY_MASK) {
                Gravity.CENTER_VERTICAL -> spare / 2
                Gravity.BOTTOM -> spare
                else -> 0
            }
            val dx = tv.totalPaddingLeft - tv.scrollX
            val outset = density // a little wider than the glyphs; full line height so no ascender shows
            for (i in 0 until layout.lineCount) {
                val top = dy + layout.getLineTop(i)
                if (top >= tv.height) break
                val left = layout.getLineLeft(i)
                val right = layout.getLineRight(i)
                if (right - left < 1) continue
                rect.set(dx + left - outset, top.toFloat(), dx + right + outset, (dy + layout.getLineBottom(i)).toFloat())
                c.drawRoundRect(rect, radius, radius, paint)
            }
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        @Deprecated("Framework API") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
