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
import android.view.HapticFeedbackConstants
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
 * The welcome tour, shown once on the first start (MainActivity opens it after the splash; Settings ›
 * About can show it again). Eight pages: what the app is, the link to the computer, the phone-care
 * tools, Wi‑Fi transfer, privacy, the permissions in one place, the PC link lock, and "you're all
 * set". Swipe or use Next; Skip, Back on the first page or Get started end it. The permissions page
 * ([Perms.TOUR]) shows each access with its state and an Allow button; nothing there is required, and
 * each feature still asks for its own access when it is first used. The lock page offers Set up PIN
 * ([UnlockActivity], the same steps as Settings › PC link › Lock the PC link); it is optional too.
 *
 * Haptics: a light tick when the page changes, a click on the buttons and a confirm on Get started.
 * performHapticFeedback follows the phone's own touch-feedback setting and needs no permission.
 */
class OnboardingActivity : Activity() {

    private class Page(val art: Int, val title: String, val body: String, val brand: Boolean = false, val artLabel: String,
                       val perms: Boolean = false, val lock: Boolean = false)

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
        Page(0, "Allow what you'll use",
            "Nothing here is required. You can skip this page: each feature asks when you first use it.",
            artLabel = "", perms = true),
        Page(R.drawable.onboard_privacy, "Protect the PC link",
            "Ask for a 4-digit PIN or your fingerprint before a computer can read anything from this phone. " +
                "Optional: you can also set it up later in Settings › PC link.",
            artLabel = "The Andromon robot looking through a magnifying glass", lock = true),
        Page(R.drawable.onboard_ready, "You're all set",
            "Plug the phone into a computer running Andromon, or start with the tools. You can see this tour again in Settings › About.",
            artLabel = "The Andromon robot giving a thumbs up"),
    )

    private lateinit var ui: Ui
    private lateinit var stage: FrameLayout
    private lateinit var dots: LinearLayout
    private lateinit var next: ImageView
    private lateinit var skip: TextView
    /** The lock page's Set up PIN pill and the "PIN set" line that replaces it. */
    private var lockPill: TextView? = null
    private var lockDone: TextView? = null
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
            setOnClickListener { haptic(TAP); finishTour() }
        }
        stage = FrameLayout(this)
        dots = ui.row { gravity = Gravity.CENTER_VERTICAL }
        // A round black button with just an arrow (a check on the last page); its name is read aloud.
        next = ui.roundIcon(64, ui.color(R.color.fab), R.drawable.ic_arrow_right, ui.color(R.color.on_fab), 26).apply {
            background = ui.pressable(ui.circle(ui.color(R.color.fab)))
            setOnClickListener {
                if (index < pages.lastIndex) { haptic(TAP); go(index + 1, feel = false) }
                else { haptic(CONFIRM); finishTour() }
            }
        }

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
                addView(next, ui.lp(w = ui.dp(64), h = ui.dp(64)))
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
        if (p.perms) return permsView(p)
        val art = FrameLayout(this).apply {
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
            if (p.lock) {
                val pill = ui.pill("Set up PIN", ui.color(R.color.fab), ui.color(R.color.on_fab), R.drawable.ic_lock) {
                    haptic(TAP)
                    @Suppress("DEPRECATION")
                    startActivityForResult(UnlockActivity.intent(this@OnboardingActivity, UnlockActivity.Mode.SET), REQ_PIN)
                }
                val done = ui.text(15f, ui.color(R.color.ink), ui.medium) {
                    text = "PIN set"
                    compoundDrawablePadding = ui.dp(8)
                    gravity = Gravity.CENTER_VERTICAL
                    val check = ui.icon(R.drawable.ic_check, ui.color(R.color.good)).apply { setBounds(0, 0, ui.dp(20), ui.dp(20)) }
                    setCompoundDrawablesRelative(check, null, null, null)
                    minHeight = ui.dp(42)
                }
                lockPill = pill; lockDone = done
                addView(pill, ui.lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, top = 22))
                addView(done, ui.lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, top = 22))
                refreshLock()
            }
        }
        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            addView(content)
        }
    }

    // ---------- permissions page ----------

    private class PermRow(val access: Perms.Access, val state: TextView, val allow: TextView)
    private val permRows = mutableListOf<PermRow>()

    /** The accesses the app uses, each with what it is for, its state and Allow. */
    private fun permsView(p: Page): View {
        permRows.clear()
        val card = ui.glassCard(6)
        Perms.TOUR.forEachIndexed { i, a ->
            val state = ui.text(12f, ui.color(R.color.ink_soft), ui.medium)
            val allow = ui.smallPill("Allow", filled = true) { haptic(TAP); Perms.ask(this, a) }.apply {
                contentDescription = "Allow ${a.title}"
            }
            permRows += PermRow(a, state, allow)
            if (i > 0) card.addView(View(this).apply { setBackgroundColor(ui.color(R.color.line)) },
                LinearLayout.LayoutParams(-1, 1).apply { marginStart = ui.dp(56); marginEnd = ui.dp(10) })
            card.addView(ui.row {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(10), ui.dp(12), ui.dp(10), ui.dp(12))
                addView(ui.chipIcon(a.icon), ui.lp(w = ui.dp(34), h = ui.dp(34)))
                addView(ui.column {
                    addView(ui.text(15f, ui.color(R.color.ink), ui.medium) { text = a.title })
                    addView(ui.text(13f, ui.color(R.color.ink_soft)) {
                        text = a.why
                        setLineSpacing(ui.dp(2).toFloat(), 1f)
                    }, ui.lp(top = 2))
                    addView(state, ui.lp(top = 4))
                }, ui.lp(w = 0, weight = 1f, start = 12))
                addView(allow, ui.lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, start = 10))
            })
        }
        val content = ui.column {
            addView(ui.text(30f, ui.color(R.color.ink), ui.light) {
                text = p.title
                letterSpacing = -0.02f
                setLineSpacing(0f, 0.95f)
                if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            }, ui.lp(top = 8))
            addView(ui.text(16f, ui.color(R.color.ink_soft)) {
                text = p.body
                setLineSpacing(ui.dp(3).toFloat(), 1f)
            }, ui.lp(top = 14))
            addView(card, ui.lp(top = 20))
            addView(ui.text(13f, ui.color(R.color.ink_soft)) {
                text = "The microphone, the camera and installing apps are asked for when you use the feature that needs them. " +
                    "You can change all of this later in Android's settings for the app."
                setLineSpacing(ui.dp(2).toFloat(), 1f)
            }, ui.lp(top = 14))
        }
        refreshPerms()
        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            addView(content)
        }
    }

    /** Re-reads each access (after Android's dialog, or back from a Settings page). */
    private fun refreshPerms() {
        for (r in permRows) {
            val on = Perms.granted(this, r.access)
            r.state.text = when {
                on -> "Allowed"
                Perms.partly(this, r.access) -> "Partly allowed"
                else -> "Not allowed"
            }
            r.state.setTextColor(ui.color(if (on) R.color.good else R.color.ink_soft))
            r.allow.visibility = if (on) View.GONE else View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPerms()
        refreshLock()
    }

    // ---------- lock page ----------

    /** "PIN set" once the PC link lock is on (also when it was set up before this tour). */
    private fun refreshLock() {
        val on = LinkLock.enabled(this)
        lockPill?.visibility = if (on) View.GONE else View.VISIBLE
        lockDone?.visibility = if (on) View.VISIBLE else View.GONE
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = refreshPerms()

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

    /** Opens page [i]; [feel] gives the page-change tick (off when a button tap already gave its own). */
    private fun go(i: Int, feel: Boolean = true) {
        if (i !in pages.indices || i == index) return
        if (feel) haptic(TICK)
        show(i, if (i > index) 1 else -1)
    }

    // ---------- haptics ----------

    private fun haptic(kind: Int) { window.decorView.performHapticFeedback(kind) }

    private fun renderControls() {
        val last = index == pages.lastIndex
        next.setImageDrawable(ui.icon(if (last) R.drawable.ic_check else R.drawable.ic_arrow_right, ui.color(R.color.on_fab)))
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
        /** Light tick for a page change (a segment tick on Android 14+). */
        private val TICK = if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        /**
         * A button tap: a tick, lighter than [CONFIRM]. (VIRTUAL_KEY would feel the same as CONFIRM: on
         * Android 11 to 13 both are the system's standard click.)
         */
        private const val TAP = HapticFeedbackConstants.CONTEXT_CLICK
        /** Get started: a stronger, confirming feel. */
        private val CONFIRM = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS

        private const val REQ_PIN = 1

        /** Opens the tour (from MainActivity on the first start, or Settings › About). */
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, OnboardingActivity::class.java))
    }
}
