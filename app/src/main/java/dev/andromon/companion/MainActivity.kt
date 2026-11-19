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
 * The app's only activity. Opening it (or Andromon starting it with --ez autostart true)
 * starts the PC link service. A bottom bar docked to the screen edge switches between PC link
 * (where the app opens), Transfer, Tools, Stats and Settings; every other tool opens from its
 * tile on Tools and highlights Tools in the bar.
 */
class MainActivity : Activity() {

    companion object {
        /**
         * Intent extra: name of the screen to open (e.g. "Receive" from a notification). Older
         * names still work: "Home" opens Tools, "Receive" and "Send" open Transfer on that side.
         */
        const val EXTRA_SCREEN = "screen"
        /** Bottom bar height above the system navigation bar. */
        private const val NAV_H = 68
        private const val NAV_GAP = 0
        /** Height of the dock above the bottom bar (a screen's sticky main button, e.g. Send). */
        private const val DOCK_H = 104
        /** Screen names from earlier versions, still used by notifications, tiles and automation. */
        private val ALIASES = mapOf("Home" to "Tools", "Receive" to "Transfer", "Send" to "Transfer")
        /** Intent extra: a Wi‑Fi receive request to accept once the app is on screen. */
        const val EXTRA_ACCEPT = "accept"
    }

    internal lateinit var ui: Ui
    private lateinit var scroll: ScrollView
    private lateinit var nav: FrameLayout
    private lateinit var navBar: LinearLayout
    private lateinit var root: FrameLayout
    /** One bottom-bar button: the icon on its pill and the label under it. */
    private class NavItem(val screen: Int, val icon: ImageView, val label: TextView)
    private val navItems = mutableListOf<NavItem>()
    private val screens: List<Screen> by lazy {
        listOf(PcLinkScreen(this), TransferScreen(this), ToolsScreen(this), StatsScreen(this), SettingsScreen(this),
            MemoryScreen(this), CleanerScreen(this), NetworkScreen(this),
            BatteryScreen(this), AppsScreen(this),
            StorageScreen(this), PermissionsScreen(this), HardwareScreen(this),
            FreezerScreen(this), SaverScreen(this), RecoverScreen(this), FdroidScreen(this))
    }
    /** Screens in the bottom bar, left to right. The first is where the app opens and where Back ends. */
    private val navOrder = listOf("PC link", "Transfer", "Tools", "Stats", "Settings")
    private val navLabels = mapOf("PC link" to "PC link", "Transfer" to "Transfer", "Tools" to "Tools", "Stats" to "Stats", "Settings" to "Settings")
    /** Where Back goes: the screens opened on the way here (only tool screens are remembered). */
    private val history = ArrayDeque<Int>()
    private lateinit var dock: FrameLayout
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
        setTheme(R.style.AppTheme) // the manifest's LaunchTheme only shows the splash while the app starts
        super.onCreate(savedInstanceState)
        // The extras of the intent that opened the app are acted on once. Android hands the same
        // intent to a recreated activity (theme or text size changed, Battery Saver's dark mode,
        // rotation) and to one reopened from Recents: acting again jumped to the screen a
        // notification had asked for, and Andromon's autostart hid the app the person opened.
        val fresh = savedInstanceState == null &&
            (intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
        val autostart = fresh && intent?.getBooleanExtra("autostart", false) == true
        // Allowed: the activity is in the foreground. Andromon's autostart always starts it.
        if (Prefs.startLinkOnOpen(this) || autostart) CompanionService.start(this)
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
            dock = FrameLayout(this@MainActivity).apply {
                visibility = View.GONE
                setBackgroundColor(ui.color(R.color.bg))
            }
            addView(dock, FrameLayout.LayoutParams(-1, ui.dp(DOCK_H), Gravity.BOTTOM))
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
        show(savedInstanceState?.getInt("screen")?.takeIf { it in screens.indices } ?: home)
        // Opened by the person from the launcher: not Andromon's autostart, and not a notification,
        // tile, Share or automation asking for a screen.
        val byPerson = fresh && !autostart && intent?.action == Intent.ACTION_MAIN &&
            intent?.getStringExtra(EXTRA_SCREEN) == null && intent?.hasExtra(EXTRA_ACCEPT) != true
        if (fresh) openRequested(intent)
        // The welcome tour, once, on the first start the person opens themselves; after the splash.
        tourPending = (byPerson && !Prefs.onboarded(this)) || savedInstanceState?.getBoolean("tour_pending") == true
        if (byPerson) {
            splash = Splash(this, ui, ::splashDone).also { it.show(root) }
            setContentHidden(true)
            splashBars()
        } else {
            if (tourPending) openTour()
            CrashReports.offer(this) // once after a crash; nothing is sent unless the person shares it
        }

        if (autostart) {
            // Started by the PC as a fallback: get out of the user's way.
            moveTaskToBack(true)
        }
    }

    // ---------- start-up splash and welcome tour ----------

    private var splash: Splash? = null
    /** The welcome tour opens once the splash is over and the app is in the foreground. */
    private var tourPending = false
    private var resumed = false

    /** The splash's five seconds are up: the tour comes next on the first start, else the app. */
    private fun splashDone() {
        setContentHidden(false)
        setupSystemBars()
        val firstStart = tourPending
        if (tourPending) {
            // The tour opens over the splash; the splash goes once it is covered (onStop).
            if (resumed) openTour()
        } else {
            splash?.remove(); splash = null
        }
        CrashReports.offer(this) // once after a crash; nothing is sent unless the person shares it
        // Once a day, when the person opens the app (not on the first start, which shows the tour).
        if (!firstStart) Updates.autoCheck(this)
    }

    private fun openTour() {
        tourPending = false
        OnboardingActivity.open(this)
        if (!Prefs.reduceMotion(this)) @Suppress("DEPRECATION") overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /** While the splash is up, the screen under it is hidden from screen readers. */
    private fun setContentHidden(hidden: Boolean) {
        val mode = if (hidden) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        for (v in listOf(scroll, nav, dock)) v.importantForAccessibility = mode
    }

    /** Light status- and navigation-bar icons on the splash's dark backdrop, also in light mode. */
    @Suppress("DEPRECATION")
    private fun splashBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    override fun onStop() {
        super.onStop()
        // Covered by the tour: the finished splash can go without anyone seeing the app flash under it.
        splash?.let { if (!it.showing) { it.removeNow(); splash = null } }
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
        // "Receive" / "Send" (notifications, tiles, Share): Transfer on that side.
        if (name == "Receive" || name == "Send") TransferScreen.requestMode(send = name == "Send")
        val i = screenIndex(name).takeIf { it >= 0 } ?: return
        // Opened from outside (notification, tile, automation): Back leads to the screen's parent, not to where the app was.
        history.clear()
        if (i == current) screens[i].onShow() else show(i, remember = false)
    }

    internal fun scrollToTop() = if (Prefs.reduceMotion(this)) scroll.scrollTo(0, 0) else scroll.smoothScrollTo(0, 0)

    /** Scrolls so [v] (a view inside the current screen) is near the top. */
    internal fun scrollTo(v: View) {
        var y = 0
        var x: View? = v
        while (x != null && x !== scroll) { y += x.top; x = x.parent as? View }
        val target = (y - ui.dp(16)).coerceAtLeast(0)
        if (Prefs.reduceMotion(this)) scroll.scrollTo(0, target) else scroll.smoothScrollTo(0, target)
    }

    internal fun show(name: String) = screenIndex(name).takeIf { it >= 0 }?.let(::show)

    /** The PC link screen, where the app opens. */
    private val home get() = screenIndex("PC link")

    internal fun goHome() = show(home)

    /**
     * A screen's sticky main button above the bottom bar (Transfer › Send), or null for none.
     * Cleared whenever another screen opens.
     */
    internal fun setDock(v: View?) {
        if (dock.childCount == 1 && dock.getChildAt(0) === v && dock.visibility == View.VISIBLE) return
        dock.removeAllViews()
        (v?.parent as? FrameLayout)?.removeView(v)
        if (v != null) dock.addView(v, FrameLayout.LayoutParams(-1, ui.dp(56), Gravity.CENTER_VERTICAL))
        dock.visibility = if (v != null) View.VISIBLE else View.GONE
        applyInsets()
    }

    /** Opens an app's details (the Apps screen's sheet); Back returns to the screen it came from. */
    internal fun showAppDetails(pkg: String) {
        AppsScreen.pendingDetails = pkg
        val i = screenIndex("Apps")
        if (i == current) screens[i].onShow() else show(i)
    }

    /** The bottom-bar item a screen belongs to: itself, or Tools for every tool screen. */
    private fun tabOf(index: Int): Int {
        val name = screens[index].name
        return if (name in navOrder) index else screenIndex("Tools")
    }

    /** Opens Settings on one of its pages (a [SettingsScreen] page key such as "privileged"). */
    internal fun showSettings(page: String) {
        SettingsScreen.pending = page
        val i = screenIndex("Settings")
        if (i == current) screens[i].onShow() else show(i)
    }

    internal fun screenIndex(name: String): Int {
        val n = ALIASES[name] ?: name
        return screens.indexOfFirst { it.name == n }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("screen", current)
        outState.putBoolean("tour_pending", tourPending)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (tourPending && splash?.showing == false) openTour()
        // Accepted from the notification: answer now that we're in the foreground (Direct link needs it).
        acceptOnResume?.let { WifiReceive.respond(it, true) }
        acceptOnResume = null
        screens[current].onShow()
    }

    override fun onPause() {
        resumed = false
        screens[current].onHide()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        screens[current].onPermissionsResult()
    }

    @Deprecated("Framework callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        screens[current].onActivityResult(requestCode, resultCode)
    }

    /** Pre-Android 13 back handling; newer versions use [updateBackHandling]. */
    @Deprecated("Framework callback")
    override fun onBackPressed() {
        if (current != home || history.isNotEmpty()) goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    /**
     * Back: the screen's own sub-page first, then the screen it was opened from (Stats › an app's
     * details › Back returns to Stats), else Tools for a tool screen, else PC link.
     */
    private fun goBack() {
        if (screens[current].onBack()) return
        val to = history.removeLastOrNull() ?: if (screens[current].name !in navOrder) screenIndex("Tools") else home
        show(to, remember = false)
    }

    // ---------- navigation ----------

    internal fun show(index: Int) = show(index, remember = true)

    private fun show(index: Int, remember: Boolean) {
        if (index == current) return
        // Opening a tool screen remembers where it was opened from; a bottom-bar screen starts afresh.
        if (screens[index].name in navOrder) history.clear()
        else if (remember && current >= 0 && history.lastOrNull() != current) { history.addLast(current); while (history.size > 8) history.removeFirst() }
        if (current >= 0) { screens[current].onHide(); screens[current].onLeave() }
        if (::dock.isInitialized) setDock(null)
        current = index
        val screen = screens[index]
        scroll.removeAllViews()
        (screen.view.parent as? ScrollView)?.removeView(screen.view)
        scroll.addView(screen.view)
        scroll.scrollTo(0, 0)
        applyInsets()
        // Colour-blocked screens: each one paints the whole window in its own colour.
        root.setBackgroundColor(ui.color(screen.navColor))
        val tab = tabOf(index)
        for (item in navItems) {
            val active = item.screen == tab
            val s = screens[item.screen]
            // The open item's icon sits on a white pill; the others are white on the dark bar.
            item.icon.setImageDrawable(ui.icon(s.navIcon, ui.color(if (active) R.color.centre_icon else R.color.nav_icon)))
            item.icon.background = if (active) ui.rounded(ui.color(R.color.nav_active), 16) else null
            item.label.typeface = if (active) ui.medium else ui.regular
            (item.icon.parent as View).isSelected = active
        }
        updateBackHandling()
        screen.onShow()
    }

    /**
     * Dark bar docked to the bottom edge, full width with rounded top corners (it runs on behind the
     * system navigation bar): one icon and label per main screen, evenly spaced, the open one's icon
     * on a white pill. Tapping the open item again scrolls its screen back to the top.
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
                setPadding(ui.dp(17), ui.dp(5), ui.dp(17), ui.dp(5))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val label = ui.text(12f, ui.color(R.color.nav_icon)) {
                text = navLabels[name] ?: name; gravity = Gravity.CENTER; maxLines = 1
                // The bar has a fixed height: labels grow with the text size, but only so far.
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, minOf(textSize, ui.dp(14).toFloat()))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            navItems += NavItem(i, icon, label)
            // The whole cell is the touch target: the icon's pill with the label under it.
            bar.addView(ui.column {
                gravity = Gravity.CENTER
                contentDescription = navLabels[name] ?: name
                background = ui.pressable(ui.rounded(Color.TRANSPARENT, 18))
                setOnClickListener { onNavTap(i) }
                addView(icon, LinearLayout.LayoutParams(ui.dp(56), ui.dp(32)))
                addView(label, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(4) })
            }, LinearLayout.LayoutParams(0, ui.dp(NAV_H - 8), 1f).apply { topMargin = ui.dp(4) })
        }
        return box
    }

    /** A bottom-bar tap: open that screen, or, when it is already open, back to its top. */
    private fun onNavTap(i: Int) {
        if (i == current) {
            screens[i].onReselect()
            scrollToTop()
        } else show(i)
    }

    private fun updateBackHandling() {
        if (Build.VERSION.SDK_INT < 33) return
        val dispatcher = onBackInvokedDispatcher
        (backCallback as? OnBackInvokedCallback)?.let { dispatcher.unregisterOnBackInvokedCallback(it) }
        backCallback = null
        if (current != home || history.isNotEmpty()) {
            val cb = OnBackInvokedCallback { goBack() }
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
        val docked = ::dock.isInitialized && dock.visibility == View.VISIBLE && !keyboard
        if (::dock.isInitialized) {
            dock.visibility = if (dock.childCount > 0 && !keyboard) View.VISIBLE else View.GONE
            (dock.layoutParams as FrameLayout.LayoutParams).bottomMargin = ui.dp(NAV_H + NAV_GAP) + b - ui.dp(24)
            dock.setPadding(ui.dp(20) + l, 0, ui.dp(20) + r, ui.dp(34))
            dock.requestLayout()
        }
        val bottom = if (keyboard) imeBottom + ui.dp(24) else b + ui.dp(NAV_H + NAV_GAP + 20) + if (docked) ui.dp(DOCK_H - 24) else 0
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
