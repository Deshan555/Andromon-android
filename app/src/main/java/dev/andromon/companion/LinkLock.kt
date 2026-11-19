// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The PC link lock (Settings › PC link › Lock the PC link): when it is on, every new link session
 * starts locked ([CompanionService.locked]) and the computer gets nothing but `info` until the
 * person enters their 4-digit PIN or uses their fingerprint or face on the phone ([UnlockActivity]).
 *
 * The PIN itself is never stored: only a random 16-byte salt and its PBKDF2-HMAC-SHA256 hash, in
 * a preferences file of its own ([FILE]) that is left out of backups and device transfers
 * (res/xml/data_extraction_rules.xml). Wrong PINs are counted across restarts: from the fifth one
 * each further try is refused for 30 s, then 60 s, 120 s… at most 15 minutes.
 */
internal object LinkLock {

    const val FILE = "link_lock"
    const val PIN_LENGTH = 4
    private const val ITERATIONS = 120_000
    private const val FREE_TRIES = 5
    private const val FIRST_WAIT_MS = 30_000L
    private const val MAX_WAIT_MS = 15 * 60_000L

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The lock is on: a PIN is set and the switch is on. */
    fun enabled(ctx: Context): Boolean = sp(ctx).let { it.getBoolean("on", false) && it.contains("hash") }

    /** Saves a new PIN (as salt and hash) and turns the lock on. Slow (PBKDF2): not on the main thread. */
    fun setPin(ctx: Context, pin: String) {
        require(pin.length == PIN_LENGTH && pin.all { it in '0'..'9' })
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt, ITERATIONS)
        sp(ctx).edit()
            .putString("salt", b64(salt)).putString("hash", b64(hash)).putInt("iter", ITERATIONS)
            .putBoolean("on", true)
            .putInt("fails", 0).remove("until").remove("until_set")
            .commit()
    }

    /** Turns the lock off and forgets the PIN; a locked session opens. */
    fun turnOff(ctx: Context) {
        sp(ctx).edit().clear().commit()
        CompanionService.unlock(ctx)
    }

    /** Result of [check]. */
    sealed class Check {
        object Right : Check()
        /** Wrong; [waitMs] > 0 when this try started a wait. */
        class Wrong(val waitMs: Long) : Check()
        /** Too many wrong tries: refused without looking at the PIN. */
        class Wait(val waitMs: Long) : Check()
    }

    /** Compares [pin] with the stored hash in constant time. Slow (PBKDF2): not on the main thread. */
    @Synchronized
    fun check(ctx: Context, pin: String): Check {
        waitLeft(ctx).takeIf { it > 0 }?.let { return Check.Wait(it) }
        val p = sp(ctx)
        val salt = p.getString("salt", null)?.let(::unb64)
        val hash = p.getString("hash", null)?.let(::unb64)
        if (salt == null || hash == null) return Check.Right // no PIN set: nothing to protect
        val ok = MessageDigest.isEqual(derive(pin, salt, p.getInt("iter", ITERATIONS)), hash)
        if (ok) { passed(ctx); return Check.Right }
        val fails = p.getInt("fails", 0) + 1
        val edit = p.edit().putInt("fails", fails)
        var wait = 0L
        if (fails >= FREE_TRIES) {
            val steps = (fails - FREE_TRIES).coerceAtMost(10)
            wait = (FIRST_WAIT_MS shl steps).coerceAtMost(MAX_WAIT_MS)
            val now = System.currentTimeMillis()
            edit.putLong("until", now + wait).putLong("until_set", now)
        }
        edit.commit()
        return Check.Wrong(wait)
    }

    /** A right PIN or a recognised fingerprint or face: the count of wrong tries starts again. */
    fun passed(ctx: Context) {
        sp(ctx).edit().putInt("fails", 0).remove("until").remove("until_set").commit()
    }

    /** How long PINs are still refused after too many wrong ones (0: not waiting). Kept across restarts. */
    fun waitLeft(ctx: Context): Long {
        val p = sp(ctx)
        val until = p.getLong("until", 0)
        if (until == 0L) return 0
        val set = p.getLong("until_set", 0)
        val now = System.currentTimeMillis()
        if (now < set) {
            // The clock was turned back: the whole wait starts again from now instead of ending early.
            val length = until - set
            p.edit().putLong("until", now + length).putLong("until_set", now).apply()
            return length
        }
        return (until - now).coerceAtLeast(0)
    }

    // ---------- fingerprint or face ----------

    /** The phone has fingerprint or face hardware that apps may use (enrolled or not); false below Android 9. */
    fun biometricHardware(ctx: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 29 -> biometricStatus(ctx) != BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE &&
            biometricStatus(ctx) != BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE
        Build.VERSION.SDK_INT == 28 -> ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
        else -> false
    }

    /**
     * A fingerprint or face is set up and BiometricPrompt can use it. Android 9 can't tell before
     * trying, so there it counts as yes when the phone has a fingerprint reader.
     */
    fun biometricReady(ctx: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 29 -> biometricStatus(ctx) == BiometricManager.BIOMETRIC_SUCCESS
        Build.VERSION.SDK_INT == 28 -> ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
        else -> false
    }

    @Suppress("DEPRECATION")
    private fun biometricStatus(ctx: Context): Int {
        val bm = ctx.getSystemService(BiometricManager::class.java) ?: return BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
        return if (Build.VERSION.SDK_INT >= 30) bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        else bm.canAuthenticate()
    }

    /** The person chose to unlock with a fingerprint or face as well. */
    fun biometricChosen(ctx: Context) = sp(ctx).getBoolean("bio", false)
    fun setBiometric(ctx: Context, on: Boolean) { sp(ctx).edit().putBoolean("bio", on).apply() }

    /** Offer the fingerprint or face: chosen, and usable on this phone right now. */
    fun biometricOn(ctx: Context) = enabled(ctx) && biometricChosen(ctx) && biometricReady(ctx)

    // ---------- starting the link unlocked ----------

    @Volatile private var openUntil = 0L

    /**
     * The person just proved who they are and pressed Start (PC link screen): the session the
     * service starts in the next few seconds begins unlocked. Kept in memory only, so nothing
     * outside this app (not even adb) can ask for an unlocked start.
     */
    fun allowUnlockedStart() { openUntil = SystemClock.elapsedRealtime() + 10_000 }

    /** Used by the service when a session starts: true once after [allowUnlockedStart]. */
    fun takeUnlockedStart(): Boolean {
        val ok = SystemClock.elapsedRealtime() < openUntil
        openUntil = 0
        return ok
    }

    // ---------- hashing ----------

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = runCatching { Base64.decode(s, Base64.NO_WRAP) }.getOrNull()
}
