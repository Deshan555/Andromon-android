// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

package dev.droiddesk.companion

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * The floating "Recording" badge shown over other apps while Droid Desk on the computer records
 * a test (its Test automation tab). It shows that taps on the phone are being recorded, how many
 * steps there are and what the last one was. It can be dragged out of the way.
 *
 * Needs "Display over other apps", which Droid Desk allows over adb before it asks for the badge.
 * The badge belongs to one PC connection ([begin] returns its token) and goes away when that
 * connection closes, so it can't be left behind if the computer disconnects.
 */
object RecOverlay {
    /** Window title. Droid Desk uses it to leave this window out of what it records. */
    const val TITLE = "DroidDeskRec"
    private const val TAG = "DroidDeskCompanion"

    private val main = Handler(Looper.getMainLooper())
    private var token = 0
    private var root: View? = null
    private var title: TextView? = null
    private var detail: TextView? = null
    private var blink: ObjectAnimator? = null
    private var wm: WindowManager? = null

    fun allowed(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /**
     * Shows the badge (replacing one from an earlier connection). Returns the token to pass back,
     * or 0 if Android refused to show it.
     *
     * This tries to add the window instead of trusting [allowed] first: on some phones (seen on a
     * Galaxy S20, Android 13) Settings.canDrawOverlays() still says no right after the computer
     * has allowed "Display over other apps", although adding the window works.
     */
    fun begin(ctx: Context, steps: Int, last: String): Int {
        val mine = synchronized(this) { ++token }
        val app = ctx.applicationContext
        val done = java.util.concurrent.CountDownLatch(1)
        var shown = false
        main.post {
            if (mine == token) {
                remove()
                shown = add(app)
                if (shown) render(steps, last)
            }
            done.countDown()
        }
        try { done.await(3, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        if (!shown) { end(mine); return 0 }
        return mine
    }

    fun update(t: Int, steps: Int, last: String) {
        main.post { if (t == token) render(steps, last) }
    }

    @Synchronized
    fun end(t: Int) {
        if (t != token) return
        token++
        main.post { remove() }
    }

    /** The service is going away: nothing may stay on screen. */
    @Synchronized
    fun endAll() {
        token++
        main.post { remove() }
    }

    private fun render(steps: Int, last: String) {
        title?.text = when (steps) {
            0 -> "Recording"
            1 -> "Recording · 1 step"
            else -> "Recording · $steps steps"
        }
        detail?.let {
            it.text = last
            it.visibility = if (last.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun remove() {
        blink?.cancel()
        blink = null
        val v = root
        root = null
        title = null
        detail = null
        if (v != null) try { wm?.removeViewImmediate(v) } catch (_: Exception) {}
        wm = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun add(ctx: Context): Boolean {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()

        val dot = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFF4D4D.toInt()) }
            layoutParams = LinearLayout.LayoutParams(dp(10f), dp(10f)).apply { marginEnd = dp(10f) }
        }
        val head = TextView(ctx).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
        }
        val sub = TextView(ctx).apply {
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(220f)
            visibility = View.GONE
        }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(head)
            addView(sub)
        }
        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(8f), dp(16f), dp(8f))
            background = GradientDrawable().apply {
                cornerRadius = dp(22f).toFloat()
                setColor(0xE617171B.toInt())
                setStroke(dp(1f), 0x33FFFFFF)
            }
            elevation = dp(6f).toFloat()
            contentDescription = "Droid Desk is recording a test"
            addView(dot)
            addView(texts)
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(40f)
            title = TITLE
        }

        // Drag to move it off whatever it covers.
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        pill.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(4f) || abs(dy) > dp(4f)) {
                        lp.x = startX + dx.toInt()
                        lp.y = (startY + dy.toInt()).coerceAtLeast(0)
                        try { wm?.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                }
            }
            true
        }

        val manager = ctx.getSystemService(WindowManager::class.java)
        try {
            manager.addView(pill, lp)
        } catch (e: Exception) {
            Log.w(TAG, "Can't show the recording badge", e)
            return false
        }
        wm = manager
        root = pill
        title = head
        detail = sub
        blink = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        return true
    }
}
