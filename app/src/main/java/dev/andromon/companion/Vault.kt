// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets the app keeps on disk (the Wi‑Fi transfer key) with an AES-256-GCM key
 * that lives in the Android Keystore: the phone's secure hardware holds it and never hands it out,
 * so a copy of the app's files is useless on its own. Uses the platform only, no library.
 */
internal object Vault {
    private const val ALIAS = "andromon.vault.v1"
    private const val STORE = "AndroidKeyStore"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    /** [plain] encrypted, as text; null when this phone's Keystore doesn't work (the caller then stores it as before). */
    fun seal(plain: ByteArray): String? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        Base64.encodeToString(c.iv + c.doFinal(plain), Base64.NO_WRAP)
    } catch (_: Exception) { null }

    /** What [seal] was given, or null when [sealed] can't be read (Keystore reset, damaged file). */
    fun open(sealed: String): ByteArray? = try {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12)) }
        c.doFinal(raw, 12, raw.size - 12)
    } catch (_: Exception) { null }
}
