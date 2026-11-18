// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.view.View
import kotlin.concurrent.thread

/**
 * One page of the app (Home, Memory, Network, Cleaner). [MainActivity] hosts them and switches
 * between them with the bottom bar; each builds its view once and keeps it.
 */
internal abstract class Screen(protected val host: MainActivity) {
    protected val ui: Ui get() = host.ui

    abstract val name: String
    abstract val navIcon: Int
    /** Window colour while this screen is shown (the tool screens are colour-blocked). */
    abstract val navColor: Int

    private var built: View? = null
    val view: View get() = built ?: build().also { built = it }

    protected abstract fun build(): View

    /** Called when the screen becomes visible (also on activity resume). */
    open fun onShow() {}
    /** Called when the screen is hidden (also on activity pause). */
    open fun onHide() {}
    /** Called when another screen replaces this one (not on activity pause). */
    open fun onLeave() {}
    open fun onPermissionsResult() {}
    open fun onActivityResult(requestCode: Int) {}
    /** Back was pressed. Return true when the screen used it (e.g. to leave a sub-page); false goes Home. */
    open fun onBack(): Boolean = false

    /** Runs [work] on a background thread, then [done] on the main thread if the screen still exists. */
    protected fun <T> background(work: () -> T, done: (Result<T>) -> Unit) {
        thread(isDaemon = true) {
            val r = runCatching(work)
            host.runOnUiThread { if (!host.isDestroyed) done(r) }
        }
    }

    protected fun color(id: Int) = ui.color(id)
}
