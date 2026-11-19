// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.lang.ref.WeakReference
import kotlin.concurrent.thread

/**
 * The PIN pad of the PC link lock ([LinkLock]): four dots and a large keypad, with a fingerprint
 * key when the person chose to unlock with their fingerprint or face (BiometricPrompt then also
 * opens by itself).
 *
 * [Mode.UNLOCK] (the "Andromon wants to connect" notification, the PC link screen's Unlock) opens
 * the running link session. [Mode.VERIFY] only checks it is the person (Settings, the PC link
 * screen's Start) and returns RESULT_OK. [Mode.SET] chooses a new PIN (enter, then again) and turns
 * the lock on; [Mode.CHANGE] asks for the current PIN or fingerprint first.
 *
 * The window is FLAG_SECURE: no screenshots, and a blank picture in Recents.
 */
class UnlockActivity : Activity() {

    enum class Mode { UNLOCK, VERIFY, SET, CHANGE }
    private enum class Stage { VERIFY, CHOOSE, CONFIRM, OFFER_BIOMETRIC }

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUB = "sub"
        /** The unlock screen open now, so the notification and the PC link screen don't stack two. */
        private var current: WeakReference<UnlockActivity>? = null

        /** [title] and [sub] replace the words of the PIN step (e.g. "Start the PC link"). */
        fun intent(ctx: Context, mode: Mode, title: String? = null, sub: String? = null): Intent =
            Intent(ctx, UnlockActivity::class.java).putExtra(EXTRA_MODE, mode.name)
                .apply { if (title != null) putExtra(EXTRA_TITLE, title); if (sub != null) putExtra(EXTRA_SUB, sub) }

        private val REJECT = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        private val CONFIRM = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
    }

    private lateinit var ui: Ui
    private lateinit var mode: Mode
    private var stage = Stage.VERIFY
    private val entered = StringBuilder()
    /** The new PIN from the first entry, until it is entered again. */
    private var chosen: String? = null
    private var busy = false

    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var dots: LinearLayout
    private lateinit var message: TextView
    private lateinit var keypad: LinearLayout
    private lateinit var bioKey: ImageView
    private lateinit var offer: LinearLayout
    private val keys = mutableListOf<View>()

    private val main = Handler(Looper.getMainLooper())
    private var cancel: CancellationSignal? = null
    private var askedBiometric = false

    /** Counts down while PINs are refused after too many wrong ones. */
    private val countdown = object : Runnable {
        override fun run() {
            val left = LinkLock.waitLeft(this@UnlockActivity)
            if (left > 0 && stage == Stage.VERIFY) {
                say("Too many wrong PINs. Try again in ${duration(((left + 999) / 1000) * 1000)}.", error = false)
                setKeysEnabled(false)
                main.postDelayed(this, 500)
            } else {
                if (message.tag == "wait") say(null)
                setKeysEnabled(true)
            }
        }
    }

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
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        mode = runCatching { Mode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: "") }.getOrDefault(Mode.UNLOCK)
        if (mode == Mode.UNLOCK && !(CompanionService.running && CompanionService.locked)) { finish(); return }
        if (mode == Mode.VERIFY && !LinkLock.enabled(this)) {
            setResult(RESULT_OK); finish(); return // nothing to check
        }
        if (mode == Mode.UNLOCK) {
            current?.get()?.takeIf { it !== this && !it.isFinishing }?.finish()
            current = WeakReference(this)
        }
        ui = Ui(this)
        stage = if (mode == Mode.SET || (mode == Mode.CHANGE && !LinkLock.enabled(this))) Stage.CHOOSE else Stage.VERIFY
        setContentView(build())
        setupSystemBars()
        render()
    }

    override fun onResume() {
        super.onResume()
        if (mode == Mode.UNLOCK && !(CompanionService.running && CompanionService.locked)) {
            // The link stopped, or it was unlocked somewhere else, while this was in the background.
            finish(); return
        }
        main.removeCallbacks(countdown)
        if (stage == Stage.VERIFY) countdown.run()
        if (!askedBiometric && stage == Stage.VERIFY && LinkLock.biometricOn(this)) {
            askedBiometric = true
            window.decorView.post { if (!isFinishing) askBiometric() }
        }
    }

    override fun onPause() {
        main.removeCallbacks(countdown)
        super.onPause()
    }

    override fun onDestroy() {
        cancel?.cancel()
        promptSuccess = null
        main.removeCallbacksAndMessages(null)
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    // ---------- layout ----------

    private fun build(): View {
        val close = ui.circleButton(R.drawable.ic_close, "Close") { finish() }
        val logo = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher_round)
            contentDescription = "Andromon"
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setOval(0, 0, v.width, v.height) }
            }
        }
        title = ui.text(28f, ui.color(R.color.ink), ui.light) {
            gravity = Gravity.CENTER
            letterSpacing = -0.02f
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        sub = ui.text(16f, ui.color(R.color.ink_soft)) {
            gravity = Gravity.CENTER
            minLines = 2 // the dots stay put when the words change
            setLineSpacing(ui.dp(3).toFloat(), 1f)
        }
        PrivacyBlur.ifFound(sub) // the computer's name
        dots = ui.row { gravity = Gravity.CENTER }
        repeat(LinkLock.PIN_LENGTH) {
            dots.addView(View(this), LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)).apply {
                marginStart = ui.dp(10); marginEnd = ui.dp(10)
            })
        }
        dots.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        message = ui.text(14f, ui.color(R.color.danger), ui.medium) {
            gravity = Gravity.CENTER
            minLines = 2
            setLineSpacing(ui.dp(2).toFloat(), 1f)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        keypad = keypad()
        offer = ui.column {
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            addView(ui.action("Use fingerprint", R.drawable.ic_fingerprint) { haptic(HapticFeedbackConstants.VIRTUAL_KEY); enrolBiometric() })
            addView(ui.pill("Not now", ui.color(R.color.glass_strong), ui.color(R.color.ink)) { haptic(HapticFeedbackConstants.VIRTUAL_KEY); done() },
                ui.lp(top = 12))
        }

        val content = ui.column {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ui.row {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                addView(close, ui.lp(w = ui.dp(42), h = ui.dp(42)))
            }, ui.lp(h = ui.dp(56)))
            addView(logo, ui.lp(w = ui.dp(72), h = ui.dp(72), top = 4))
            addView(title, ui.lp(top = 18))
            addView(sub, ui.lp(top = 10))
            addView(dots, ui.lp(w = LinearLayout.LayoutParams.WRAP_CONTENT, top = 28))
            addView(message, ui.lp(top = 16))
            addView(ui.spacer(), ui.lp(h = 0, weight = 1f))
            addView(keypad, ui.lp(top = 12))
            addView(offer, ui.lp(top = 12))
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            addView(content)
        }
        return FrameLayout(this).apply {
            setBackgroundColor(ui.color(R.color.bg))
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
            setOnApplyWindowInsetsListener { _, insets ->
                val (l, t, r, b) = bars(insets)
                val side = ui.dp(20)
                scroll.setPadding(l + side, t + ui.dp(4), r + side, b + ui.dp(20))
                insets
            }
        }
    }

    /** 1 to 9, then fingerprint (or nothing), 0 and delete; each key a 72dp circle. */
    private fun keypad() = ui.column {
        val labels = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
        labels.forEach { row ->
            addView(ui.row { row.forEach { d -> addView(cell(digitKey(d)), ui.lp(w = 0, weight = 1f)) } }, ui.lp(top = 12))
        }
        bioKey = iconKey(R.drawable.ic_fingerprint, "Use fingerprint or face") { askBiometric() }
        val delete = iconKey(R.drawable.ic_delete, "Delete") { backspace() }
        delete.setOnLongClickListener { if (!busy) { entered.clear(); renderDots() }; true }
        addView(ui.row {
            addView(cell(bioKey), ui.lp(w = 0, weight = 1f))
            addView(cell(digitKey("0")), ui.lp(w = 0, weight = 1f))
            addView(cell(delete), ui.lp(w = 0, weight = 1f))
        }, ui.lp(top = 12))
    }

    private fun cell(key: View) = FrameLayout(this).apply {
        addView(key, FrameLayout.LayoutParams(ui.dp(72), ui.dp(72), Gravity.CENTER))
    }

    private fun digitKey(d: String) = ui.text(28f, ui.color(R.color.ink), ui.regular) {
        text = d
        gravity = Gravity.CENTER
        background = ui.pressable(ui.circle(ui.color(R.color.glass)))
        isSoundEffectsEnabled = false
        setOnClickListener { press(d[0]) }
    }.also { keys += it }

    private fun iconKey(icon: Int, label: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageDrawable(ui.icon(icon, ui.color(R.color.ink)))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val p = ui.dp(22)
        setPadding(p, p, p, p)
        background = ui.pressable(ui.circle(Color.TRANSPARENT))
        contentDescription = label
        isSoundEffectsEnabled = false
        setOnClickListener { haptic(HapticFeedbackConstants.KEYBOARD_TAP); onClick() }
    }.also { keys += it }

    // ---------- state ----------

    private fun extra(key: String) = intent.getStringExtra(key)

    private fun render() {
        val computer = LinkStats.hello(this)?.computer?.takeIf { it.isNotBlank() }
        if (computer != null) Pii.addKnown(computer)
        when (stage) {
            Stage.VERIFY -> when (mode) {
                Mode.UNLOCK -> {
                    title.text = "Unlock the PC link"
                    sub.text = when {
                        CompanionService.waiting && computer != null -> "$computer wants to read this phone's data."
                        CompanionService.waiting -> "Andromon on your computer wants to read this phone's data."
                        else -> "Unlock it so Andromon on your computer can read this phone's data."
                    }
                }
                Mode.CHANGE -> { title.text = "Enter your current PIN"; sub.text = "Then choose a new one." }
                else -> {
                    title.text = extra(EXTRA_TITLE) ?: "Enter your PIN"
                    sub.text = extra(EXTRA_SUB) ?: "The PIN of the PC link lock."
                }
            }
            Stage.CHOOSE -> {
                title.text = if (mode == Mode.CHANGE) "Choose a new PIN" else "Choose a PIN"
                sub.text = "4 digits. You'll enter it on this phone before a computer can read anything."
            }
            Stage.CONFIRM -> { title.text = "Enter it again"; sub.text = "To be sure it's the PIN you meant." }
            Stage.OFFER_BIOMETRIC -> {
                title.text = "Use your fingerprint too?"
                sub.text = "Unlock the PC link with your fingerprint or face instead of typing the PIN. The PIN always works as well."
            }
        }
        val pad = stage != Stage.OFFER_BIOMETRIC
        keypad.visibility = if (pad) View.VISIBLE else View.GONE
        dots.visibility = if (pad) View.VISIBLE else View.GONE
        offer.visibility = if (pad) View.GONE else View.VISIBLE
        bioKey.visibility = if (stage == Stage.VERIFY && LinkLock.biometricOn(this)) View.VISIBLE else View.INVISIBLE
        renderDots()
    }

    private fun renderDots() {
        for (i in 0 until dots.childCount) {
            dots.getChildAt(i).background = if (i < entered.length) ui.circle(ui.color(R.color.ink))
            else GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(ui.dp(2), ui.color(R.color.ink_soft)) }
        }
        dots.contentDescription = "${entered.length} of ${LinkLock.PIN_LENGTH} digits entered"
    }

    private fun say(text: String?, error: Boolean = true) {
        message.text = text.orEmpty()
        message.tag = if (text != null && !error) "wait" else null
        message.setTextColor(ui.color(if (error) R.color.danger else R.color.ink))
    }

    private fun setKeysEnabled(on: Boolean) {
        keys.forEach { it.isEnabled = on; it.alpha = if (on) 1f else 0.4f }
    }

    // ---------- input ----------

    private fun press(d: Char) {
        if (busy || entered.length >= LinkLock.PIN_LENGTH) return
        if (stage == Stage.VERIFY && LinkLock.waitLeft(this) > 0) return
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
        if (message.tag == null) say(null)
        entered.append(d)
        renderDots()
        if (entered.length == LinkLock.PIN_LENGTH) {
            busy = true
            main.postDelayed({ submit() }, 120) // let the last dot show
        }
    }

    private fun backspace() {
        if (busy || entered.isEmpty()) return
        entered.setLength(entered.length - 1)
        renderDots()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { press('0' + (keyCode - KeyEvent.KEYCODE_0)); return true }
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> { press('0' + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)); return true }
            KeyEvent.KEYCODE_DEL -> { backspace(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun submit() {
        val pin = entered.toString()
        when (stage) {
            Stage.VERIFY -> {
                setKeysEnabled(false)
                thread(name = "pin-check", isDaemon = true) {
                    val r = LinkLock.check(applicationContext, pin)
                    runOnUiThread { if (!isDestroyed) checked(r) }
                }
            }
            Stage.CHOOSE -> {
                chosen = pin
                entered.clear(); busy = false
                stage = Stage.CONFIRM
                render()
            }
            Stage.CONFIRM -> {
                if (pin != chosen) {
                    chosen = null
                    stage = Stage.CHOOSE
                    wrong("The PINs don't match. Try again.")
                    render()
                    return
                }
                setKeysEnabled(false)
                thread(name = "pin-save", isDaemon = true) {
                    LinkLock.setPin(applicationContext, pin)
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        chosen = null
                        entered.clear(); busy = false
                        setKeysEnabled(true)
                        haptic(CONFIRM)
                        if (mode == Mode.SET && LinkLock.biometricReady(this) && !LinkLock.biometricChosen(this)) {
                            stage = Stage.OFFER_BIOMETRIC
                            render()
                        } else done()
                    }
                }
            }
            Stage.OFFER_BIOMETRIC -> Unit
        }
    }

    private fun checked(r: LinkLock.Check) {
        busy = false
        when (r) {
            is LinkLock.Check.Right -> verified()
            is LinkLock.Check.Wrong -> {
                setKeysEnabled(true)
                wrong("Wrong PIN")
                if (r.waitMs > 0) countdown.run()
            }
            is LinkLock.Check.Wait -> {
                entered.clear(); renderDots()
                countdown.run()
            }
        }
    }

    private fun wrong(text: String) {
        haptic(REJECT)
        say(text)
        entered.clear()
        busy = false
        renderDots()
        if (!Prefs.reduceMotion(this)) {
            ObjectAnimator.ofFloat(dots, View.TRANSLATION_X, 0f, 22f, -22f, 16f, -16f, 8f, -8f, 0f).setDuration(380).start()
        }
    }

    /** The person proved who they are (PIN or fingerprint). */
    private fun verified() {
        entered.clear()
        say(null)
        when (mode) {
            Mode.UNLOCK -> { haptic(CONFIRM); CompanionService.unlock(this); done() }
            Mode.VERIFY -> { haptic(CONFIRM); done() }
            Mode.CHANGE -> { stage = Stage.CHOOSE; setKeysEnabled(true); render() }
            Mode.SET -> Unit
        }
    }

    private fun done() {
        setResult(RESULT_OK)
        finish()
    }

    // ---------- fingerprint or face ----------

    private fun askBiometric() {
        if (Build.VERSION.SDK_INT < 28 || isFinishing) return
        val title = when (mode) {
            Mode.UNLOCK -> "Unlock the PC link"
            Mode.CHANGE -> "Change your PIN"
            else -> extra(EXTRA_TITLE) ?: "Confirm it's you"
        }
        prompt(title, "Use PIN") {
            LinkLock.passed(this)
            verified()
        }
    }

    /** Turns the fingerprint on after the person showed it works ([Stage.OFFER_BIOMETRIC]). */
    private fun enrolBiometric() {
        if (Build.VERSION.SDK_INT < 28) { done(); return }
        prompt("Use your fingerprint or face", "Cancel") {
            LinkLock.setBiometric(this, true)
            haptic(CONFIRM)
            done()
        }
    }

    private fun prompt(title: String, negative: String, onSuccess: () -> Unit) {
        if (Build.VERSION.SDK_INT < 28) return
        cancel?.cancel()
        val signal = CancellationSignal().also { cancel = it }
        // The application context and a callback that holds this screen weakly: Android keeps the
        // prompt (and what it refers to) for a while after it closes, which kept the screen in memory.
        val builder = BiometricPrompt.Builder(applicationContext)
            .setTitle(title)
            .setNegativeButton(negative, mainExecutor) { _, _ -> }
        if (Build.VERSION.SDK_INT >= 29) builder.setConfirmationRequired(false)
        if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        promptSuccess = onSuccess
        runCatching { builder.build().authenticate(signal, mainExecutor, PromptCallback(WeakReference(this))) }
            .onFailure { say("Your fingerprint can't be used right now. Use your PIN.") }
    }

    private var promptSuccess: (() -> Unit)? = null

    private class PromptCallback(private val screen: WeakReference<UnlockActivity>) : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            val a = screen.get()?.takeIf { !it.isDestroyed } ?: return
            a.promptSuccess?.invoke()
        }

        override fun onAuthenticationError(code: Int, why: CharSequence) {
            val a = screen.get()?.takeIf { !it.isDestroyed } ?: return
            when (code) {
                BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT, BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT ->
                    a.say("Too many tries with your fingerprint. Use your PIN.")
                BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS, BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT ->
                    a.say("No fingerprint or face is set up on this phone. Use your PIN.")
            }
        }
    }

    // ---------- feel and window ----------

    private fun haptic(kind: Int) { window.decorView.performHapticFeedback(kind) }

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
}
