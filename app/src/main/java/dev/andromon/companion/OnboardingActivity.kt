// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import kotlin.math.abs

/**
 * The welcome tour, shown once on the first start (MainActivity opens it; Settings › About can show
 * it again). Six pages with the mascot: what the app is, the link to the computer, the phone-care
 * tools, Wi‑Fi transfer, privacy, and "you're all set". Swipe or use Next; Skip, Back on the first
 * page or Get started end it. Nothing is asked for here: each feature asks for its own permission
 * when it is first used.
 */
class OnboardingActivity : Activity() {

    private class Page(val art: Int, val title: String, val body: String, val brand: Boolean = false, val artLabel: String)

    private val pages = listOf(
        Page(R.drawable.brand_wordmark, "Welcome to Andromon Companion",
            "Your phone, linked to your computer, and a set of tools that keep it in good shape.",
            brand = true, artLabel = "The Andromon logo"),
        Page(R.drawable.onboard_link, "Your phone on your computer",
            "Andromon on your Windows, Mac or Linux computer shows your messages, contacts, calls, photos and music. " +
                "This app hands them over through the USB cable or Wi‑Fi, and only to computers you allowed.",
            artLabel = "The Andromon robot working on a laptop"),
        Page(R.drawable.onboard_care, "Phone care built in",
            "Clean up junk files, check the battery, freeze apps you don't use and see where your storage and screen time go. No computer needed.",
            artLabel = "The Andromon robot with a wrench and a checklist"),
        Page(R.drawable.onboard_transfer, "Files in a flash",
            "Send photos and files between the phone and your computer over Wi‑Fi, without a cable. Every transfer asks you first.",
            artLabel = "The Andromon robot flying with a jetpack"),
        Page(R.drawable.onboard_privacy, "Private by design",
            "No account, no ads and no analytics. Stop the PC link whenever you like, and turn on Blur personal data in Settings to keep screenshots safe to share.",
            artLabel = "The Andromon robot looking through a magnifying glass"),
        Page(R.drawable.onboard_ready, "You're all set",
            "Plug the phone into a computer running Andromon, or start with the tools. You can see this tour again in Settings › About.",
            artLabel = "The Andromon robot giving a thumbs up"),
    )

    private lateinit var ui: Ui
    private lateinit var stage: FrameLayout
    private lateinit var dots: LinearLayout
    private lateinit var next: TextView
    private lateinit var skip: TextView
    private var index = 0
    private var shown: View? = null
    private var backCallback: Any? = null

    /** Same theme and text size as the rest of the app (see MainActivity.attachBaseContext). */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        val night = Prefs.nightOverride(base)
        val scale = Prefs.textSize(base).scale
        if (night == null && scale == 1f) return
        applyOverrideConfiguration(Configuration().apply {
            if (night != null) uiMode = Configuration.UI_MODE_TYPE_NORMAL or night
            if (scale != 1f) fontScale = base.resources.configuration.fontScale * scale
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        index = savedInstanceState?.getInt("page")?.coerceIn(0, pages.lastIndex) ?: 0

        skip = ui.text(15f, ui.color(R.color.ink_soft), ui.medium) {
            text = "Skip"
            gravity = Gravity.CENTER
            minHeight = ui.dp(48); minWidth = ui.dp(64)
            setPadding(ui.dp(14), 0, ui.dp(14), 0)
            background = ui.pressable(ui.rounded(Color.TRANSPARENT, 50))
            contentDescription = "Skip the tour"
            setOnClickListener { finishTour() }
        }
        stage = FrameLayout(this)
        dots = ui.row { gravity = Gravity.CENTER_VERTICAL }
        next = ui.action("Next", R.drawable.ic_arrow_right) { if (index < pages.lastIndex) go(index + 1) else finishTour() }

        val column = ui.column {
            addView(ui.row {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                addView(skip)
            }, ui.lp(h = ui.dp(56)))
            addView(stage, ui.lp(h = 0, weight = 1f))
            addView(ui.row {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(4), ui.dp(12), 0, ui.dp(16))
                addView(dots, ui.lp(w = 0, weight = 1f))
                addView(next, ui.lp(w = ViewGroup.LayoutParams.WRAP_CONTENT))
            })
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(ui.color(R.color.bg))
            addView(column, FrameLayout.LayoutParams(-1, -1))
            setOnApplyWindowInsetsListener { v, insets ->
                val (l, t, r, b) = bars(insets)
                val side = ui.dp(20)
                column.setPadding(l + side, t + ui.dp(4), r + side, b + ui.dp(4))
                insets
            }
        }
        setContentView(root)
        setupSystemBars()
        show(index, 0)
        setupBack()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", index)
    }

    // ---------- pages ----------

    private fun pageView(p: Page): View {
        val art = FrameLayout(this).apply {
            background = if (p.brand) ui.rounded(ui.color(R.color.splash_bg), 32) else ui.hero(32)
            addView(ImageView(this@OnboardingActivity).apply {
                setImageResource(p.art)
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                contentDescription = p.artLabel
                val pad = ui.dp(if (p.brand) 34 else 18)
                setPadding(pad, pad, pad, pad)
            }, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        }
        // As tall as there is room for, so the words below still fit on a small phone.
        val artHeight = (resources.displayMetrics.heightPixels * 0.38f).toInt().coerceIn(ui.dp(180), ui.dp(340))
        val content = ui.column {
            addView(art, ui.lp(h = artHeight))
            addView(ui.text(30f, ui.color(R.color.ink), ui.light) {
                text = p.title
                letterSpacing = -0.02f
                setLineSpacing(0f, 0.95f)
                if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            }, ui.lp(top = 28))
            addView(ui.text(16f, ui.color(R.color.ink_soft)) {
                text = p.body
                setLineSpacing(ui.dp(3).toFloat(), 1f)
                ellipsize = TextUtils.TruncateAt.END
            }, ui.lp(top = 14))
        }
        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            addView(content)
        }
    }

    /** Shows page [i]; [dir] 1 slides in from the right, -1 from the left, 0 without motion. */
    private fun show(i: Int, dir: Int) {
        index = i
        val view = pageView(pages[i])
        val old = shown
        shown = view
        stage.addView(view, FrameLayout.LayoutParams(-1, -1))
        if (old != null) {
            if (dir == 0 || Prefs.reduceMotion(this)) stage.removeView(old)
            else {
                val shift = stage.width * 0.28f
                old.animate().translationX(-dir * shift).alpha(0f).setDuration(200)
                    .withEndAction { stage.removeView(old) }.start()
                view.translationX = dir * shift
                view.alpha = 0f
                view.animate().translationX(0f).alpha(1f).setDuration(320).setInterpolator(DecelerateInterpolator(2f)).start()
            }
        }
        renderControls()
        view.post { view.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED) }
    }

    private fun go(i: Int) {
        if (i !in pages.indices || i == index) return
        show(i, if (i > index) 1 else -1)
    }

    private fun renderControls() {
        val last = index == pages.lastIndex
        ui.setLabel(next, if (last) "Get started" else "Next")
        next.contentDescription = if (last) "Get started" else "Next page"
        skip.visibility = if (last) View.INVISIBLE else View.VISIBLE
        dots.removeAllViews()
        pages.indices.forEach { d ->
            val on = d == index
            dots.addView(View(this).apply {
                background = ui.rounded(ui.color(if (on) R.color.ink else R.color.ink_faint), 50)
                alpha = if (on) 1f else 0.5f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ui.dp(if (on) 22 else 8), ui.dp(8)).apply { marginEnd = ui.dp(6) })
        }
        dots.contentDescription = "Page ${index + 1} of ${pages.size}"
        dots.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    // ---------- swipe and Back ----------

    private val swipe by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) < 600 || abs(vx) < abs(vy) * 1.3f) return false
                if (vx < 0) go(index + 1) else go(index - 1)
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipe.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun setupBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            val cb = OnBackInvokedCallback { back() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        }
    }

    @Deprecated("Android 12 and older; newer ones use the OnBackInvokedCallback above")
    override fun onBackPressed() = back()

    private fun back() { if (index > 0) go(index - 1) else finishTour() }

    private fun finishTour() {
        Prefs.setOnboarded(this, true)
        finish()
        if (!Prefs.reduceMotion(this)) @Suppress("DEPRECATION") overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) (backCallback as? OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        super.onDestroy()
    }

    // ---------- window ----------

    private fun bars(insets: WindowInsets): List<Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            listOf(i.left, i.top, i.right, i.bottom)
        } else @Suppress("DEPRECATION") listOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
            insets.systemWindowInsetRight, insets.systemWindowInsetBottom)

    @Suppress("DEPRECATION")
    private fun setupSystemBars() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (night) 0 else light, light)
        } else {
            var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (!night) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = flags
        }
    }

    companion object {
        /** Opens the tour (from MainActivity on the first start, or Settings › About). */
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, OnboardingActivity::class.java))
    }
}
