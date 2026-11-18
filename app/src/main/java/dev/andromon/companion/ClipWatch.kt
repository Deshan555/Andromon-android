// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.content.ClipData
import android.os.IBinder
import org.json.JSONObject
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Shared clipboard for Andromon on the PC, run with the shell user's rights.
 *
 * Since Android 10 an app may only read the clipboard while it is in front, so the companion app
 * can't follow it. The shell user (adb) may, the way scrcpy does it: the PC starts this class
 * from the companion's own APK with
 *
 *     CLASSPATH=$(pm path dev.andromon.companion | head -n1 | cut -d: -f2) app_process / dev.andromon.companion.ClipWatch
 *
 * and talks to it over the command's stdin and stdout, one JSON object per line:
 *  - out `{"ready":true}` once it can reach the clipboard
 *  - out `{"text":"…","initial":true}` once, what is on the clipboard at the start (text null if none)
 *  - out `{"text":"…"}` whenever the text on the clipboard changes
 *  - out `{"error":"…"}` when something fails (it keeps running)
 *  - in  `{"set":"…"}` puts that text on the clipboard; it is not reported back as a change
 * It ends when stdin closes, so it can't outlive the PC's connection.
 *
 * The clipboard is checked every half second rather than through the hidden change listener: a
 * listener needs a binder object of our own and its signature changes between versions, polling
 * only needs the plain getters. Each check first reads the small clip description and only
 * fetches the text when its time stamp changed.
 *
 * Clipboard contents go to stdout only; nothing is logged.
 */
object ClipWatch {

    /** The shell user's package: Android checks that it belongs to the calling uid (2000). */
    private const val PKG = "com.android.shell"
    private const val USER = 0
    private const val LABEL = "Andromon"
    private const val POLL_MS = 500L
    /** Larger texts aren't sent either way: a clip travels through Binder, which has about 1 MB. */
    private const val MAX_BYTES = 1_000_000

    private val out = PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8")
    private val lock = Object()
    /** The last text seen or set, so a change is only reported once and our own isn't echoed. */
    private var last: String? = null
    private var lastStamp = Long.MIN_VALUE

    private lateinit var clipboard: Any
    private lateinit var getClip: Method
    private lateinit var getDesc: Method
    private lateinit var setClip: Method

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "clipboard") as? IBinder ?: throw IllegalStateException("no clipboard service")
            clipboard = Class.forName("android.content.IClipboard\$Stub").getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder) ?: throw IllegalStateException("no clipboard interface")
            getClip = method("getPrimaryClip")
            getDesc = method("getPrimaryClipDescription")
            setClip = method("setPrimaryClip")
        } catch (e: Throwable) {
            send(JSONObject().put("error", "Can't reach the clipboard: ${reason(e)}"))
            exitProcess(1)
        }
        send(JSONObject().put("ready", true))
        synchronized(lock) {
            val text = try {
                lastStamp = runCatching { stamp() }.getOrNull() ?: Long.MIN_VALUE
                read()
            } catch (e: Throwable) { send(JSONObject().put("error", reason(e))); null }
            last = text
            send(JSONObject().put("text", text ?: JSONObject.NULL).put("initial", true))
        }
        thread(isDaemon = true, name = "clip-poll") { poll() }

        val input = System.`in`.bufferedReader(Charsets.UTF_8)
        while (true) {
            val line = try { input.readLine() } catch (_: Exception) { null } ?: break
            if (line.isBlank()) continue
            // A parse error would quote the line, which holds the text: say less.
            val req = try { JSONObject(line) } catch (_: Exception) { send(JSONObject().put("error", "Not a JSON object")); continue }
            try {
                if (req.has("set")) set(req.getString("set"))
            } catch (e: Throwable) {
                send(JSONObject().put("error", "Couldn't set the clipboard: ${reason(e)}"))
            }
        }
        exitProcess(0)
    }

    /**
     * The IClipboard method [name]. Its arguments differ between Android versions and makers:
     * (String pkg[, String attributionTag], int userId[, int deviceId]), on some phones
     * (String pkg, int userId, String attributionTag), and setPrimaryClip has the ClipData first.
     * AIDL interfaces have no overloads, so the one method of that name is taken and [args]
     * fills its parameters by type: the first String is the package, any further String the
     * attribution tag (none), the first int the user and a second int the device (the default, 0).
     */
    private fun method(name: String): Method =
        clipboard.javaClass.methods.firstOrNull { it.name == name } ?: throw NoSuchMethodException(name)

    private fun args(m: Method, clip: ClipData? = null): Array<Any?> {
        var strings = 0
        var ints = 0
        return m.parameterTypes.map { t ->
            when (t) {
                ClipData::class.java -> clip
                String::class.java -> if (strings++ == 0) PKG else null
                Int::class.javaPrimitiveType -> if (ints++ == 0) USER else 0
                else -> null
            }
        }.toTypedArray()
    }

    private fun call(m: Method, clip: ClipData? = null): Any? = try {
        m.invoke(clipboard, *args(m, clip))
    } catch (e: InvocationTargetException) {
        throw e.targetException ?: e
    }

    /** The text on the clipboard, or null when there is none (empty, or an image or link only). */
    private fun read(): String? {
        val clip = call(getClip) as? ClipData ?: return null
        if (clip.itemCount < 1) return null
        val item = clip.getItemAt(0)
        val text = (item.text ?: item.htmlText)?.toString() ?: return null
        if (text.length > MAX_BYTES || text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            throw IllegalStateException("The text on the clipboard is larger than 1 MB")
        }
        return text
    }

    private fun stamp(): Long? {
        val desc = call(getDesc) as? android.content.ClipDescription ?: return null
        return desc.timestamp
    }

    private fun poll() {
        var failing = false
        while (true) {
            Thread.sleep(POLL_MS)
            try {
                synchronized(lock) {
                    // No description (nothing on the clipboard): reading the text is cheap then.
                    val s = stamp()
                    if (s != null && s == lastStamp) return@synchronized
                    val text = read()
                    if (s != null) lastStamp = s
                    if (text != null && text != last) {
                        last = text
                        send(JSONObject().put("text", text))
                    }
                }
                failing = false
            } catch (e: Throwable) {
                // Say it once, not twice a second, until it works again.
                if (!failing) send(JSONObject().put("error", reason(e)))
                failing = true
            }
        }
    }

    private fun set(text: String) {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "The text is larger than 1 MB" }
        synchronized(lock) {
            call(setClip, ClipData.newPlainText(LABEL, text))
            // Remember it before the next check, so it isn't sent back to the PC as a change.
            last = text
            lastStamp = runCatching { stamp() }.getOrNull() ?: lastStamp
        }
    }

    /** What went wrong, without the clipboard's contents. */
    private fun reason(e: Throwable): String = (e as? InvocationTargetException)?.targetException?.let(::reason)
        ?: "${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}"

    private fun send(o: JSONObject) {
        synchronized(out) { out.println(o.toString()) }
    }
}
