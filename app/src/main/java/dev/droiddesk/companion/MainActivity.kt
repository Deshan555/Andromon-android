// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

package dev.droiddesk.companion

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * The app's only activity. Opening it (or Droid Desk starting it with --ez autostart true)
 * starts the PC link service. A bottom bar docked to the screen edge switches between Home,
 * PC link, Cleaner (the round centre button), Network and Receive; Memory, Battery, Apps and
 * Settings open from Home.
 */
class MainActivity : Activity() {

    companion object {
        /** Intent extra: name of the screen to open (e.g. "Receive" from a notification). */
        const val EXTRA_SCREEN = "screen"
        /** Bottom bar height above the system navigation bar, and how far the centre button rises above it. */
        private const val NAV_H = 64
        private const val NAV_GAP = 0
        /** Intent extra: a Wi‑Fi receive request to accept once the app is on screen. */
        const val EXTRA_ACCEPT = "accept"
    }

    internal lateinit var ui: Ui
    private lateinit var scroll: ScrollView
    private lateinit var nav: FrameLayout
    private lateinit var navBar: LinearLayout
    private lateinit var root: FrameLayout
    /** One bottom-bar button; [dot] marks the open screen (null for the round centre button). */
    private class NavItem(val screen: Int, val icon: ImageView, val dot: View?)
    private val navItems = mutableListOf<NavItem>()
    private val screens: List<Screen> by lazy {
        listOf(HomeScreen(this), MemoryScreen(this), CleanerScreen(this), NetworkScreen(this), ReceiveScreen(this),
            BatteryScreen(this), AppsScreen(this), SettingsScreen(this), PcLinkScreen(this),
            StorageScreen(this), PermissionsScreen(this), HardwareScreen(this),
            FreezerScreen(this), SaverScreen(this))
    }
    /** Screens in the bottom bar, left to right. */
    private val navOrder = listOf("Home", "PC link", "Cleaner", "Network", "Receive", "Settings")
    private var current = -1
    private var bars = listOf(0, 0, 0, 0)
    private var imeBottom = 0
    private var backCallback: Any? = null

    /**
     * Light / Dark and the text size from Settings: override the night bit and the font scale before
     * any resources are loaded. The text size multiplies the phone's own font size.
     */
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
        // Allowed: the activity is in the foreground. Droid Desk's autostart always starts it.
        if (Prefs.startLinkOnOpen(this) || intent?.getBooleanExtra("autostart", false) == true) CompanionService.start(this)
        ui = Ui(this)
        BatteryLog.schedule(this) // battery history keeps sampling while the app is closed
        PowerWatch.sync(this) // the "Battery watch" service, only when an automatic freeze/saver option is on

        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
        }
        nav = buildNav()
        root = FrameLayout(this).apply {
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
            addView(nav, FrameLayout.LayoutParams(-1, ui.dp(NAV_H + NAV_GAP), Gravity.BOTTOM))
        }
        // Draw behind the system bars and pad the content by their size instead.
        root.setOnApplyWindowInsetsListener { _, insets ->
            readInsets(insets)
            applyInsets()
            insets
        }
        setContentView(root)
        setupSystemBars()
        show(savedInstanceState?.getInt("screen") ?: 0)
        openRequested(intent)

        if (intent?.getBooleanExtra("autostart", false) == true) {
            // Started by the PC as a fallback: get out of the user's way.
            moveTaskToBack(true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequested(intent)
    }

    private var acceptOnResume: String? = null

    private fun openRequested(intent: Intent?) {
        intent?.getStringExtra(EXTRA_ACCEPT)?.let { acceptOnResume = it; intent.removeExtra(EXTRA_ACCEPT) }
        val name = intent?.getStringExtra(EXTRA_SCREEN) ?: return
        screenIndex(name).takeIf { it >= 0 }?.let(::show)
    }

    internal fun scrollToTop() = if (Prefs.reduceMotion(this)) scroll.scrollTo(0, 0) else scroll.smoothScrollTo(0, 0)

    internal fun show(name: String) = screenIndex(name).takeIf { it >= 0 }?.let(::show)

    internal fun goHome() = show(0)

    internal fun screenIndex(name: String) = screens.indexOfFirst { it.name == name }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("screen", current)
    }

    override fun onResume() {
        super.onResume()
        // Accepted from the notification: answer now that we're in the foreground (Direct link needs it).
        acceptOnResume?.let { WifiReceive.respond(it, true) }
        acceptOnResume = null
        screens[current].onShow()
    }

    override fun onPause() {
        screens[current].onHide()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        screens[current].onPermissionsResult()
    }

    @Deprecated("Framework callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        screens[current].onActivityResult(requestCode)
    }

    /** Pre-Android 13 back handling; newer versions use [updateBackHandling]. */
    @Deprecated("Framework callback")
    override fun onBackPressed() {
        if (current != 0) { if (!screens[current].onBack()) show(0) } else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---------- navigation ----------

    internal fun show(index: Int) {
        if (index == current) return
        if (current >= 0) screens[current].onHide()
        current = index
        val screen = screens[index]
        scroll.removeAllViews()
        (screen.view.parent as? ScrollView)?.removeView(screen.view)
        scroll.addView(screen.view)
        scroll.scrollTo(0, 0)
        applyInsets()
        // Colour-blocked screens: each one paints the whole window in its own colour.
        root.setBackgroundColor(ui.color(screen.navColor))
        for (item in navItems) {
            val active = item.screen == index
            val s = screens[item.screen]
            // The open screen's icon sits on a white rounded square; the others are white on the dark pill.
            item.icon.setImageDrawable(ui.icon(s.navIcon, ui.color(if (active) R.color.centre_icon else R.color.nav_icon)))
            item.icon.background = if (active) ui.rounded(ui.color(R.color.nav_active), 16)
                else null
            item.icon.isSelected = active
        }
        updateBackHandling()
        screen.onShow()
    }

    /**
     * Dark bar docked to the bottom edge, full width with rounded top corners (it runs on behind the
     * system navigation bar): one icon per main screen, evenly spaced, the open one on a white
     * rounded square.
     */
    private fun buildNav(): FrameLayout {
        val bar = ui.row {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ui.color(R.color.nav_bg))
                val r = ui.dp(24).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            gravity = Gravity.TOP
        }
        navBar = bar
        val box = FrameLayout(this)
        box.addView(bar, FrameLayout.LayoutParams(-1, -1))
        for (name in navOrder) {
            val i = screenIndex(name)
            val icon = ImageView(this).apply {
                val p = ui.dp(13)
                setPadding(p, p, p, p)
            }
            navItems += NavItem(i, icon, null)
            // The whole cell is the touch target; the icon sits centred in it.
            bar.addView(FrameLayout(this).apply {
                contentDescription = screens[i].name
                setOnClickListener { show(i) }
                addView(icon, FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.CENTER))
            }, LinearLayout.LayoutParams(0, ui.dp(NAV_H), 1f))
        }
        return box
    }

    private fun updateBackHandling() {
        if (Build.VERSION.SDK_INT < 33) return
        val dispatcher = onBackInvokedDispatcher
        (backCallback as? OnBackInvokedCallback)?.let { dispatcher.unregisterOnBackInvokedCallback(it) }
        backCallback = null
        if (current != 0) {
            val cb = OnBackInvokedCallback { if (!screens[current].onBack()) show(0) }
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        }
    }

    // ---------- window ----------

    @Suppress("DEPRECATION")
    private fun readInsets(insets: WindowInsets) {
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            bars = listOf(i.left, i.top, i.right, i.bottom)
            imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
        } else {
            bars = listOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            imeBottom = 0 // pre-30 the window resizes for the keyboard (adjustResize)
        }
    }

    private fun applyInsets() {
        val (l, t, r, b) = bars
        val keyboard = imeBottom > b
        nav.visibility = if (keyboard) View.GONE else View.VISIBLE
        // Docked: full width, flush with the bottom edge; the bar grows by the system bar's height.
        // Docked: flush with the bottom edge; the bar grows by the system bar's height.
        nav.layoutParams.height = ui.dp(NAV_H + NAV_GAP) + b
        navBar.setPadding(ui.dp(8) + l, 0, ui.dp(8) + r, b)
        nav.requestLayout()
        val bottom = if (keyboard) imeBottom + ui.dp(24) else b + ui.dp(NAV_H + NAV_GAP + 20)
        // 20dp sides leave room for the cards' soft shadows.
        if (current >= 0) screens[current].view.setPadding(ui.dp(20) + l, ui.dp(16) + t, ui.dp(20) + r, bottom)
    }

    @Suppress("DEPRECATION")
    private fun setupSystemBars() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            // Dark status-bar icons in light mode; the navigation bar always sits on the dark bottom
            // bar, so its icons stay light.
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(
                if (night) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS, mask) // nav icons stay light: they sit on the dark bar
        } else {
            var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (!night) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            window.decorView.systemUiVisibility = flags
        }
    }
}
