// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File

/** Storage › Compress and Extract: zip archives through zip4j, also password-protected (AES-256) ones. */
internal object Archives {

    /** The password was missing or wrong. */
    class NeedsPassword : Exception("Wrong password")

    fun isZip(f: File) = f.isFile && f.extension.lowercase() in setOf("zip", "apk", "jar", "cbz", "epub", "docx", "xlsx", "pptx", "odt")

    /** A name next to [wanted] that isn't taken yet: "Photos.zip", "Photos (2).zip"… */
    fun free(wanted: File): File {
        if (!wanted.exists()) return wanted
        val base = wanted.nameWithoutExtension
        val ext = if (wanted.extension.isEmpty() || wanted.isDirectory) "" else "." + wanted.extension
        val stem = if (wanted.isDirectory) wanted.name else base
        for (i in 2..999) File(wanted.parentFile, "$stem ($i)$ext").let { if (!it.exists()) return it }
        throw IllegalStateException("Too many copies of ${wanted.name}")
    }

    /** Packs [source] (a file or a whole folder) into a new zip beside it; [password] empty for none. Returns the zip. */
    fun compress(source: File, password: String): File {
        val dest = free(File(source.parentFile, (if (source.isDirectory) source.name else source.nameWithoutExtension) + ".zip"))
        val params = ZipParameters().apply {
            if (password.isNotEmpty()) {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
        }
        try {
            ZipFile(dest, password.toCharArray().takeIf { it.isNotEmpty() }).use { zip ->
                if (source.isDirectory) zip.addFolder(source, params) else zip.addFile(source, params)
            }
        } catch (e: Exception) {
            dest.delete() // no half-written archive left behind
            throw e
        }
        return dest
    }

    fun encrypted(zip: File): Boolean = runCatching { ZipFile(zip).use { it.isEncrypted } }.getOrDefault(false)

    /**
     * Unpacks [zip] into a new folder beside it, named after the archive. zip4j refuses entries
     * that would land outside that folder ("zip slip"). Returns the folder.
     */
    fun extract(zip: File, password: String): File {
        val dest = free(File(zip.parentFile, zip.nameWithoutExtension.ifEmpty { "Extracted" }))
        try {
            ZipFile(zip, password.toCharArray().takeIf { it.isNotEmpty() }).use { it.extractAll(dest.path) }
        } catch (e: ZipException) {
            dest.deleteRecursively()
            if (e.type == ZipException.Type.WRONG_PASSWORD) throw NeedsPassword()
            throw e
        } catch (e: Exception) {
            dest.deleteRecursively()
            throw e
        }
        return dest
    }
}
