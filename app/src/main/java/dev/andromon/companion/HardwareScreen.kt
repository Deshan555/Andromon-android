// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/**
 * Hardware test: a checklist of guided checks. Where the phone can tell by itself (touch grid,
 * buttons, sensors, what Android lists) the result is automatic; where only a person can tell
 * (colours, sound, vibration, light) they tap Passed or Failed. The summary can be shared as text.
 */
internal class HardwareScreen(host: MainActivity) : Screen(host) {

    override val name = "Hardware"
    override val navIcon = R.drawable.ic_tools
    override val navColor = R.color.bg

    private companion object { const val REQ_MIC = 14 }

    private enum class Status(val label: String) { NOT_RUN("Not run"), PASS("Passed"), FAIL("Failed"), ABSENT("Not present") }

    private class Test(val title: String, val icon: Int, val hint: String, val run: (Test) -> Unit) {
        var status = Status.NOT_RUN
        var detail = ""
    }

    private val tests: List<Test> by lazy {
        listOf(
            Test("Display colours", R.drawable.ic_image, "Full-screen colours to spot dead pixels", ::displayTest),
            Test("Touch grid", R.drawable.ic_touch, "Paint every square to find dead touch areas", ::touchTest),
            Test("Multi-touch", R.drawable.ic_touch, "How many fingers the screen follows at once", ::multiTouchTest),
            Test("Speaker", R.drawable.ic_volume, "A tone through the loudspeaker", ::speakerTest),
            Test("Earpiece", R.drawable.ic_call, "A tone through the call speaker", ::earpieceTest),
            Test("Microphone", R.drawable.ic_mic, "Record 4 seconds and play them back", ::micTest),
            Test("Vibration", R.drawable.ic_vibration, "Three short buzzes", ::vibrationTest),
            Test("Volume buttons", R.drawable.ic_up, "Press volume up and down", ::buttonsTest),
            Test("Sensors", R.drawable.ic_speed, "Live values from the motion and environment sensors", ::sensorsTest),
            Test("Flashlight", R.drawable.ic_flashlight, "Turns the camera light on", ::flashTest),
            Test("Cameras", R.drawable.ic_camera, "Which cameras Android lists", ::camerasTest),
            Test("Wi‑Fi, Bluetooth, GPS, NFC", R.drawable.ic_wifi, "Whether each is there and switched on", ::radiosTest),
            Test("Fingerprint", R.drawable.ic_lock, "Whether a fingerprint sensor is there", ::fingerprintTest),
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var active: Dialog? = null
    /** True while [onHide] closes whatever test is open, so its "finished" handling is skipped. */
    private var aborting = false
    private var sound: HardwareTests.Playing? = null
    private var torchId: String? = null
    private var pendingMic: Test? = null

    private lateinit var score: TextView
    private lateinit var scoreSub: TextView
    private lateinit var shareBtn: TextView
    private lateinit var list: LinearLayout

    override fun build(): View = ui.column {
        addView(ui.topBar("Hardware", host::goHome, R.drawable.ic_refresh, "Clear results") {
            tests.forEach { it.status = Status.NOT_RUN; it.detail = "" }
            render()
        }.first)
        addView(ui.heroCard(18) {
            score = ui.text(40f, color(R.color.ink), ui.regular) { letterSpacing = -0.03f }
            addView(score)
            scoreSub = ui.text(14f, color(R.color.ink), ui.medium) { setLineSpacing(ui.dp(2).toFloat(), 1f) }
            addView(scoreSub, ui.lp(top = 6))
            shareBtn = ui.pill("Share summary", color(R.color.fab), color(R.color.on_fab), R.drawable.ic_arrow_right) { share() }
            addView(shareBtn, ui.lp(top = 16))
        }, ui.lp(top = 24))
        list = ui.card(color(R.color.surface), 6)
        addView(list, ui.lp(top = 12))
        addView(ui.text(12f, color(R.color.ink_soft)) {
            text = "Tap a test to run it. Results stay on this screen until you clear them or close the app; nothing is sent anywhere " +
                "unless you share the summary. These are quick checks, not a repair-shop diagnosis."
            setLineSpacing(ui.dp(2).toFloat(), 1f)
        }, ui.lp(top = 14))
    }

    override fun onShow() = render()

    override fun onHide() {
        aborting = true
        active?.let { runCatching { it.dismiss() } }
        active = null
        // Dialogs report their dismissal through the main looper, so the flag is cleared after that.
        handler.post { aborting = false }
        stopSound()
        torchOff()
    }

    override fun onPermissionsResult() {
        val t = pendingMic ?: return
        pendingMic = null
        if (host.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) micTest(t)
        else show(AlertDialog.Builder(host)
            .setTitle("Microphone")
            .setMessage("The microphone test needs the Microphone permission. It is only used while this test records, and the " +
                "recording is played back once and not saved. If Android no longer asks, allow it under App info › Permissions.")
            .setNegativeButton("Close", null)
            .setPositiveButton("App info") { _, _ ->
                runCatching {
                    host.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", host.packageName, null)))
                }
            })
    }

    // ---------- list and summary ----------

    private fun render() {
        val pass = tests.count { it.status == Status.PASS }
        val fail = tests.count { it.status == Status.FAIL }
        val absent = tests.count { it.status == Status.ABSENT }
        val todo = tests.count { it.status == Status.NOT_RUN }
        score.text = "$pass of ${tests.size - absent} passed"
        scoreSub.text = listOfNotNull(
            if (fail > 0) "$fail failed" else null,
            if (todo > 0) "$todo not run yet" else null,
            if (absent > 0) "$absent not present on this phone" else null,
        ).joinToString(" · ").ifEmpty { "Every test passed." }
        ui.setEnabled(shareBtn, todo < tests.size)

        list.removeAllViews()
        for (t in tests) list.addView(ui.row {
            background = ui.pressable(ui.rounded(color(R.color.surface), 18))
            setPadding(ui.dp(10), ui.dp(10), ui.dp(8), ui.dp(10))
            addView(ui.roundIcon(40, color(R.color.bg), t.icon, color(R.color.ink), 19), ui.lp(w = ui.dp(40), h = ui.dp(40)))
            addView(ui.column {
                addView(ui.text(15f, color(R.color.ink), ui.medium) { text = t.title; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                addView(ui.text(12f, color(R.color.ink_soft)) {
                    text = t.detail.ifEmpty { t.hint }
                    setLineSpacing(ui.dp(2).toFloat(), 1f)
                }, ui.lp(top = 3))
            }, ui.lp(w = 0, weight = 1f, start = 12))
            val (bg, fg, icon) = when (t.status) {
                Status.PASS -> Triple(R.color.good_bg, R.color.good, R.drawable.ic_check)
                Status.FAIL -> Triple(R.color.peach, R.color.danger, R.drawable.ic_warning)
                Status.ABSENT -> Triple(R.color.bg, R.color.ink_soft, null)
                Status.NOT_RUN -> Triple(R.color.fab, R.color.on_fab, null)
            }
            addView(ui.pill(if (t.status == Status.NOT_RUN) "Run" else t.status.label, color(bg), color(fg), icon).apply {
                textSize = 12f; minHeight = ui.dp(30); setPadding(ui.dp(12), 0, ui.dp(12), 0)
            }, ui.lp(w = WRAP_CONTENT, start = 8))
            contentDescription = "${t.title}: ${t.status.label}"
            setOnClickListener { t.run(t) }
        })
    }

    private fun finish(t: Test, status: Status, detail: String) {
        t.status = status
        t.detail = detail
        render()
    }

    private fun share() {
        val text = buildString {
            append("Hardware test: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n")
            append(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date())).append('\n')
            append(score.text).append(". ").append(scoreSub.text).append("\n\n")
            for (t in tests) {
                append("[${t.status.label}] ${t.title}")
                if (t.detail.isNotEmpty()) append(": ${t.detail}")
                append('\n')
            }
            append("\nMade with Andromon Companion. Quick checks on the phone itself, not a workshop diagnosis.")
        }
        runCatching {
            host.startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share summary"))
        }
    }

    // ---------- helpers ----------

    private fun show(b: AlertDialog.Builder): AlertDialog = b.show().also { active = it }

    private fun padded(v: View) = ui.column {
        setPadding(ui.dp(24), ui.dp(16), ui.dp(24), ui.dp(4))
        addView(v)
    }

    /** Explains a test, then starts it. */
    private fun intro(t: Test, message: String, start: () -> Unit) {
        show(AlertDialog.Builder(host).setTitle(t.title).setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start") { _, _ -> start() })
    }

    /** For what only a person can judge. [cleanup] runs whichever way the question is closed. */
    private fun ask(t: Test, question: String, detail: String = "", again: (() -> Unit)? = null, cleanup: () -> Unit = {}) {
        var redo = false
        val b = AlertDialog.Builder(host).setTitle(t.title).setMessage(question)
            .setPositiveButton("Passed") { _, _ -> finish(t, Status.PASS, detail) }
            .setNegativeButton("Failed") { _, _ -> finish(t, Status.FAIL, detail) }
            .setOnDismissListener {
                cleanup()
                if (redo && !aborting) again?.invoke() // only after this round is cleaned up
            }
        if (again != null) b.setNeutralButton("Again") { _, _ -> redo = true }
        show(b)
    }

    private fun stopSound() {
        sound?.stop()
        sound = null
    }

    private fun torchOff() {
        val id = torchId ?: return
        torchId = null
        runCatching { host.getSystemService(CameraManager::class.java).setTorchMode(id, false) }
    }

    // ---------- screen ----------

    private fun displayTest(t: Test) = intro(t, "Five full-screen colours follow: white, black, red, green and blue. Tap to go to the " +
        "next. Look for dots that stay dark, bright or the wrong colour, and for uneven patches.") {
        var completed = false
        lateinit var d: Dialog
        d = HardwareTests.fullScreen(host, ColorTestView(host) { completed = true; d.dismiss() })
        d.setOnDismissListener {
            if (!aborting && completed) ask(t, "Did every colour look even, with no dead or stuck pixels?")
        }
        active = d
        d.show()
    }

    private fun touchTest(t: Test) = intro(t, "The screen is cut into squares. Drag a finger over all of them; each one that feels " +
        "your finger turns green. It finishes by itself when all are green. A square that never turns green is a dead touch area. " +
        "Press Back to stop.") {
        var done = false
        lateinit var d: Dialog
        val grid = TouchGridView(host) { done = true; d.dismiss() }
        d = HardwareTests.fullScreen(host, grid)
        d.setOnDismissListener {
            val left = grid.remaining
            when {
                aborting -> Unit
                done -> finish(t, Status.PASS, "All ${grid.total} squares responded")
                left == grid.total -> Unit // nothing touched: not a result
                else -> show(AlertDialog.Builder(host).setTitle(t.title)
                    .setMessage("$left of ${grid.total} squares weren't painted. Did they not respond, or did you stop early?")
                    .setNegativeButton("Stopped early", null)
                    .setPositiveButton("They don't respond") { _, _ ->
                        finish(t, Status.FAIL, "$left of ${grid.total} squares didn't respond")
                    })
            }
        }
        active = d
        d.show()
    }

    private fun multiTouchTest(t: Test) {
        val view = MultiTouchView(host)
        val d = HardwareTests.fullScreen(host, view)
        d.setOnDismissListener {
            when {
                aborting || view.max == 0 -> Unit
                view.max >= 2 -> finish(t, Status.PASS, "Followed ${view.max} fingers at once")
                else -> finish(t, Status.FAIL, "Only one finger was followed at a time")
            }
        }
        active = d
        d.show()
    }

    // ---------- sound and vibration ----------

    private fun speakerTest(t: Test) {
        stopSound()
        val am = host.getSystemService(AudioManager::class.java)
        val external = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        val p = runCatching { HardwareTests.speakerTone() }.getOrElse {
            finish(t, Status.FAIL, "The tone couldn't be played: ${it.message}"); return
        }
        sound = p
        handler.postDelayed({ p.stop() }, p.ms + 300)
        ask(t, "A short tone is playing through the loudspeaker. Did you hear it?" +
            (if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) "\n\nMedia volume is at zero. Turn it up, then tap Again." else "") +
            (if (external) "\n\nHeadphones or a Bluetooth speaker are connected, so the tone plays there. Disconnect them, then tap Again." else ""),
            again = { speakerTest(t) }, cleanup = { p.stop() })
    }

    private fun earpieceTest(t: Test) {
        stopSound()
        if (!HardwareTests.hasEarpiece(host)) { finish(t, Status.ABSENT, "Android lists no earpiece (usual for tablets)"); return }
        intro(t, "Hold the phone to your ear as in a call, then tap Start. A 2 second tone plays through the earpiece.") {
            val p = runCatching { HardwareTests.earpieceTone(host) }.getOrElse {
                finish(t, Status.FAIL, "The tone couldn't be played: ${it.message}"); return@intro
            }
            sound = p
            handler.postDelayed({ p.stop() }, p.ms + 300)
            ask(t, "Did you hear the tone from the earpiece (quiet, at the top of the phone), not from the loudspeaker?",
                again = { earpieceTest(t) }, cleanup = { p.stop() })
        }
    }

    private fun micTest(t: Test) {
        stopSound()
        if (!host.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
            finish(t, Status.ABSENT, "Android reports no microphone"); return
        }
        if (host.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingMic = t
            host.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        val meter = Meter(host).apply { colors(color(R.color.bg), color(R.color.good)) }
        val box = ui.column {
            addView(ui.text(15f, color(R.color.ink)) {
                text = "Recording 4 seconds. Say something. The bar shows how loud the microphone hears you."
                setLineSpacing(ui.dp(2).toFloat(), 1f)
            })
            addView(meter, ui.lp(h = ui.dp(10), top = 18))
        }
        var cancelled = false
        val d = show(AlertDialog.Builder(host).setTitle(t.title).setView(padded(box)).setNegativeButton("Cancel", null)
            .setOnDismissListener { cancelled = true })
        background({
            HardwareTests.record(4, { cancelled }) { lvl -> host.runOnUiThread { meter.fraction = lvl } }
        }) { r ->
            if (cancelled) return@background
            d.dismiss()
            val pcm = r.getOrElse { finish(t, Status.FAIL, it.message ?: "Recording failed"); return@background }
            val peak = (pcm.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0) * 100 / 32768
            if (pcm.isEmpty() || pcm.all { it.toInt() in -8..8 }) {
                finish(t, Status.FAIL, "The microphone delivered only silence. Check that microphone access isn't switched off in Quick Settings.")
                return@background
            }
            val p = runCatching { HardwareTests.playRecording(pcm) }.getOrElse {
                finish(t, Status.FAIL, "The recording couldn't be played: ${it.message}"); return@background
            }
            sound = p
            handler.postDelayed({ p.stop() }, p.ms + 300)
            ask(t, "Your recording is playing back now (loudest level $peak%). Did you hear yourself clearly?",
                "Loudest level $peak%. The recording was not saved.", again = { micTest(t) }, cleanup = { p.stop() })
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrationTest(t: Test) {
        val v = host.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (v == null || !v.hasVibrator()) { finish(t, Status.ABSENT, "Android reports no vibration motor"); return }
        runCatching { v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300, 200, 300), -1)) }
            .onFailure { finish(t, Status.FAIL, "Vibration couldn't be started: ${it.message}"); return }
        ask(t, "The phone buzzes three times. Did you feel it?", again = { vibrationTest(t) }, cleanup = { runCatching { v.cancel() } })
    }

    // ---------- buttons and sensors ----------

    private fun buttonsTest(t: Test) {
        var up = false
        var down = false
        val label = ui.text(15f, color(R.color.ink)) { setLineSpacing(ui.dp(4).toFloat(), 1f) }
        fun text() = "Press each volume button once.\n\nVolume up: ${if (up) "works" else "waiting…"}\n" +
            "Volume down: ${if (down) "works" else "waiting…"}\n\nThe power button can't be checked by an app."
        label.text = text()
        val d = AlertDialog.Builder(host).setTitle(t.title).setView(padded(label))
            .setNeutralButton("Cancel", null)
            .setNegativeButton("A button doesn't respond") { _, _ ->
                val dead = listOfNotNull(if (!up) "volume up" else null, if (!down) "volume down" else null)
                finish(t, Status.FAIL, "No response from " + dead.joinToString(" and "))
            }
            .create()
        d.setOnKeyListener { _, code, e ->
            if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return@setOnKeyListener false
            if (e.action == KeyEvent.ACTION_DOWN) {
                if (code == KeyEvent.KEYCODE_VOLUME_UP) up = true else down = true
                label.text = text()
                if (up && down) { d.dismiss(); finish(t, Status.PASS, "Volume up and volume down respond") }
            }
            true
        }
        active = d
        d.show()
    }

    private fun sensorsTest(t: Test) {
        val sm = host.getSystemService(SensorManager::class.java)
        val wanted = listOf(
            Sensor.TYPE_ACCELEROMETER to "Accelerometer", Sensor.TYPE_GYROSCOPE to "Gyroscope",
            Sensor.TYPE_PROXIMITY to "Proximity", Sensor.TYPE_LIGHT to "Light",
            Sensor.TYPE_MAGNETIC_FIELD to "Magnetometer", Sensor.TYPE_PRESSURE to "Barometer",
        )
        val sensors = wanted.associate { (type, _) -> type to sm.getDefaultSensor(type) }
        if (sensors.values.all { it == null }) { finish(t, Status.ABSENT, "Android lists none of these sensors"); return }
        val values = HashMap<Int, TextView>()
        val seen = HashSet<Int>()
        val box = ui.column {
            addView(ui.text(13f, color(R.color.ink_soft)) {
                text = "Move the phone and cover its top edge with your hand, then tap Done. Proximity and light may only report when something changes."
                setLineSpacing(ui.dp(2).toFloat(), 1f)
            })
            for ((type, name) in wanted) {
                val (row, value) = ui.keyValue(name)
                value.text = if (sensors[type] == null) "Not present" else "Waiting…"
                values[type] = value
                addView(row, ui.lp(top = 12))
            }
        }
        fun xyz(e: SensorEvent, unit: String) = "%.1f  %.1f  %.1f %s".format(e.values[0], e.values[1], e.values[2], unit)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                seen += e.sensor.type
                values[e.sensor.type]?.text = when (e.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> xyz(e, "m/s²")
                    Sensor.TYPE_GYROSCOPE -> xyz(e, "rad/s")
                    Sensor.TYPE_MAGNETIC_FIELD -> xyz(e, "µT")
                    Sensor.TYPE_PROXIMITY -> if (e.values[0] < e.sensor.maximumRange) "Near (%.0f cm)".format(e.values[0]) else "Far"
                    Sensor.TYPE_LIGHT -> "%.0f lx".format(e.values[0])
                    else -> "%.1f hPa".format(e.values[0])
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        for (s in sensors.values) if (s != null) sm.registerListener(listener, s, SensorManager.SENSOR_DELAY_UI)
        show(AlertDialog.Builder(host).setTitle(t.title).setView(padded(box))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Done") { _, _ ->
                val name = wanted.toMap()
                val present = sensors.filterValues { it != null }.keys
                val silent = present.filter { it !in seen }.map { name.getValue(it) }
                val missing = sensors.filterValues { it == null }.keys.map { name.getValue(it) }
                val tail = if (missing.isEmpty()) "" else ". Not present: ${missing.joinToString()}"
                if (silent.isEmpty()) finish(t, Status.PASS, "Reporting: ${present.joinToString { name.getValue(it) }}$tail")
                else finish(t, Status.FAIL, "No reading from: ${silent.joinToString()}$tail")
            }
            .setOnDismissListener { sm.unregisterListener(listener) })
    }

    // ---------- light, cameras, radios, fingerprint ----------

    private fun flashTest(t: Test) {
        torchOff()
        val cm = host.getSystemService(CameraManager::class.java)
        val id = runCatching {
            val withFlash = cm.cameraIdList.filter { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            withFlash.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                ?: withFlash.firstOrNull()
        }.getOrNull()
        if (id == null) { finish(t, Status.ABSENT, "Android lists no camera light"); return }
        runCatching { cm.setTorchMode(id, true) }.onFailure {
            finish(t, Status.FAIL, "The light couldn't be switched on (the camera may be in use): ${it.message}"); return
        }
        torchId = id
        ask(t, "The flashlight on the back should be on now. Is it lit?", again = { flashTest(t) }, cleanup = { torchOff() })
    }

    private fun camerasTest(t: Test) {
        val cm = host.getSystemService(CameraManager::class.java)
        val declared = host.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        val cams = runCatching {
            cm.cameraIdList.map { id ->
                val c = cm.getCameraCharacteristics(id)
                val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                    CameraCharacteristics.LENS_FACING_BACK -> "Back"
                    else -> "External"
                }
                val mp = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let { " %.0f MP".format(it.width * it.height / 1e6) } ?: ""
                facing + mp + if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) " with light" else ""
            }
        }.getOrElse { finish(t, Status.FAIL, "Android's camera list couldn't be read: ${it.message}"); return }
        when {
            cams.isNotEmpty() -> finish(t, Status.PASS, cams.joinToString(", ") +
                ". As listed by Android (extra lenses can hide behind one entry); no photo was taken.")
            declared -> finish(t, Status.FAIL, "The phone should have a camera, but Android lists none (camera access may be blocked)")
            else -> finish(t, Status.ABSENT, "Android lists no camera")
        }
    }

    private fun radiosTest(t: Test) {
        val pm = host.packageManager
        val parts = ArrayList<String>()
        var broken = false
        /** [state]: null when the phone says it has the part but Android gives no handle to it. */
        fun part(name: String, feature: String, state: () -> String?) {
            if (!pm.hasSystemFeature(feature)) { parts += "$name not present"; return }
            val s = runCatching(state).getOrElse { "present (state not readable)" }
            if (s == null) { broken = true; parts += "$name not responding" } else parts += "$name $s"
        }
        fun onOff(on: Boolean) = if (on) "on" else "off"
        part("Wi‑Fi", PackageManager.FEATURE_WIFI) {
            (host.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.let { onOff(it.isWifiEnabled) }
        }
        part("Bluetooth", PackageManager.FEATURE_BLUETOOTH) {
            host.getSystemService(BluetoothManager::class.java)?.adapter?.let { a ->
                runCatching { onOff(a.isEnabled) }.getOrDefault("present (state not readable)")
            }
        }
        part("GPS", PackageManager.FEATURE_LOCATION_GPS) {
            host.getSystemService(LocationManager::class.java)?.let { lm ->
                if (lm.allProviders.contains(LocationManager.GPS_PROVIDER)) onOff(lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) else null
            }
        }
        part("NFC", PackageManager.FEATURE_NFC) {
            NfcAdapter.getDefaultAdapter(host)?.let { a -> runCatching { onOff(a.isEnabled) }.getOrDefault("present (state not readable)") }
        }
        finish(t, if (broken) Status.FAIL else Status.PASS,
            parts.joinToString(" · ") + ". Presence and switches only; signal strength isn't tested.")
    }

    private fun fingerprintTest(t: Test) {
        val pm = host.packageManager
        val others = listOfNotNull(
            if (Build.VERSION.SDK_INT >= 29 && pm.hasSystemFeature(PackageManager.FEATURE_FACE)) "face unlock hardware" else null,
            if (Build.VERSION.SDK_INT >= 29 && pm.hasSystemFeature(PackageManager.FEATURE_IRIS)) "iris scanner" else null,
        )
        val also = if (others.isEmpty()) "" else " Also reported: ${others.joinToString()}."
        if (pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT))
            finish(t, Status.PASS, "Android reports a fingerprint sensor. This only checks that it is there; try it on the lock screen.$also")
        else finish(t, Status.ABSENT, "Android reports no fingerprint sensor.$also")
    }
}
