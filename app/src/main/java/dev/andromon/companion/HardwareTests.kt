// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

package dev.andromon.companion

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/** Pieces of the Hardware test screen that draw on the whole display or play and record sound. */
internal object HardwareTests {

    /** A dialog that covers the whole display, status and navigation bars included. Back closes it. */
    fun fullScreen(ctx: Context, content: View): Dialog =
        Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(content)
            window?.let { w ->
                w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (Build.VERSION.SDK_INT >= 28) w.attributes = w.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                @Suppress("DEPRECATION")
                w.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }
        }

    // ---------- sound ----------

    /** A 1 kHz tone with soft edges, 16-bit mono. */
    private fun tone(rate: Int, seconds: Float): ShortArray {
        val n = (rate * seconds).toInt()
        val fade = rate / 50
        return ShortArray(n) { i ->
            val edge = min(1f, min(i, n - 1 - i) / fade.toFloat())
            (sin(2 * PI * 1000 * i / rate) * 0.6 * edge * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun play(pcm: ShortArray, rate: Int, usage: Int, content: Int): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(content).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
        track.play()
        return track
    }

    /** Something that is making sound; [stop] ends it and puts the audio routing back. */
    class Playing(private val track: AudioTrack, val ms: Long, private val restore: () -> Unit = {}) {
        private var stopped = false
        fun stop() {
            if (stopped) return
            stopped = true
            runCatching { track.stop() }
            runCatching { track.release() }
            runCatching(restore)
        }
    }

    /** Plays the tone as media: the loudspeaker, unless headphones are connected. */
    fun speakerTone(): Playing =
        Playing(play(tone(44100, 1.5f), 44100, AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC), 1500)

    fun hasEarpiece(ctx: Context): Boolean = ctx.getSystemService(AudioManager::class.java)
        .getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }

    /**
     * Plays the tone the way a phone call sounds: through the earpiece. The audio mode is switched
     * to "in communication" for the tone and put back by [Playing.stop].
     */
    @Suppress("DEPRECATION")
    fun earpieceTone(ctx: Context): Playing {
        val am = ctx.getSystemService(AudioManager::class.java)
        val oldMode = am.mode
        val oldSpeaker = am.isSpeakerphoneOn
        runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
        if (Build.VERSION.SDK_INT >= 31) runCatching {
            am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                ?.let { am.setCommunicationDevice(it) }
        } else runCatching { am.isSpeakerphoneOn = false }
        val restore = {
            if (Build.VERSION.SDK_INT >= 31) runCatching { am.clearCommunicationDevice() }
            else runCatching { am.isSpeakerphoneOn = oldSpeaker }
            runCatching { am.mode = oldMode }
            Unit
        }
        return try {
            Playing(play(tone(44100, 2f), 44100, AudioAttributes.USAGE_VOICE_COMMUNICATION, AudioAttributes.CONTENT_TYPE_SPEECH), 2000, restore)
        } catch (e: Exception) {
            restore()
            throw e
        }
    }

    const val MIC_RATE = 16000

    /**
     * Records [seconds] from the microphone (the caller has checked RECORD_AUDIO). [level] gets the
     * loudest sample of each chunk as 0..1, on the recording thread. Returns the recording, cut
     * short if [cancelled] turns true.
     */
    @Suppress("MissingPermission")
    fun record(seconds: Int, cancelled: () -> Boolean, level: (Float) -> Unit): ShortArray {
        val min = AudioRecord.getMinBufferSize(MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "This phone doesn't offer 16 kHz recording" }
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, MIC_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, MIC_RATE / 2))
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "The microphone couldn't be opened (another app may be using it)" }
        val out = ShortArray(MIC_RATE * seconds)
        var filled = 0
        try {
            rec.startRecording()
            val chunk = ShortArray(MIC_RATE / 20)
            while (filled < out.size && !cancelled()) {
                val n = rec.read(chunk, 0, minOf(chunk.size, out.size - filled))
                if (n <= 0) break
                var peak = 0
                for (i in 0 until n) { out[filled + i] = chunk[i]; peak = maxOf(peak, abs(chunk[i].toInt())) }
                filled += n
                level(peak / 32768f)
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        return out.copyOf(filled)
    }

    fun playRecording(pcm: ShortArray): Playing =
        Playing(play(pcm, MIC_RATE, AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_SPEECH), pcm.size * 1000L / MIC_RATE)
}

/** Solid colours over the whole display; each tap shows the next, the last tap calls [onDone]. */
internal class ColorTestView(ctx: Context, private val onDone: () -> Unit) : View(ctx) {
    private val colors = intArrayOf(Color.WHITE, Color.BLACK, Color.RED, Color.GREEN, Color.BLUE)
    private var index = 0

    init {
        setBackgroundColor(colors[0])
        contentDescription = "Full-screen colour. Tap for the next one."
        setOnClickListener {
            index++
            if (index >= colors.size) onDone() else setBackgroundColor(colors[index])
        }
    }
}

/**
 * The display cut into cells; every cell a finger passes over is painted. [onDone] is called once
 * all are painted. A cell that can't be painted points at a dead area of the touch panel.
 */
internal class TouchGridView(ctx: Context, private val onDone: () -> Unit) : View(ctx) {
    private val d = resources.displayMetrics.density
    private var cols = 0
    private var rows = 0
    private var cellW = 0f
    private var cellH = 0f
    private var painted = BooleanArray(0)
    private var finished = false
    val total get() = painted.size
    val remaining get() = painted.count { !it }

    private val fill = Paint().apply { color = 0xFF2F8F6E.toInt() }
    private val lines = Paint().apply { color = 0xFF55555C.toInt(); style = Paint.Style.STROKE; strokeWidth = d }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 16 * d }

    init { setBackgroundColor(0xFF17171B.toInt()) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cols = (w / (44 * d)).toInt().coerceAtLeast(4)
        rows = (h / (44 * d)).toInt().coerceAtLeast(6)
        cellW = w / cols.toFloat(); cellH = h / rows.toFloat()
        painted = BooleanArray(cols * rows)
        finished = false
    }

    private fun mark(x: Float, y: Float) {
        if (cols == 0) return
        val c = (x / cellW).toInt().coerceIn(0, cols - 1)
        val r = (y / cellH).toInt().coerceIn(0, rows - 1)
        painted[r * cols + c] = true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        for (p in 0 until e.pointerCount) {
            for (h in 0 until e.historySize) mark(e.getHistoricalX(p, h), e.getHistoricalY(p, h))
            mark(e.getX(p), e.getY(p))
        }
        invalidate()
        if (!finished && painted.isNotEmpty() && painted.all { it }) { finished = true; post { onDone() } }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        for (r in 0 until rows) for (c in 0 until cols) {
            val x = c * cellW; val y = r * cellH
            if (painted[r * cols + c]) canvas.drawRect(x, y, x + cellW, y + cellH, fill)
            canvas.drawRect(x, y, x + cellW, y + cellH, lines)
        }
        val n = remaining
        if (n > 0) {
            canvas.drawText("Drag a finger over every square", width / 2f, height / 2f - 14 * d, text)
            canvas.drawText("$n left · Back to stop", width / 2f, height / 2f + 14 * d, text)
        }
    }
}

/** Shows how many fingers the touch panel reports at once, and remembers the most ([max]). */
internal class MultiTouchView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    var max = 0
        private set
    private var points: List<Pair<Float, Float>> = emptyList()
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7FDCC0.toInt(); style = Paint.Style.STROKE; strokeWidth = 4 * d }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 64 * d }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB9BEC4.toInt(); textAlign = Paint.Align.CENTER; textSize = 15 * d }

    init { setBackgroundColor(0xFF17171B.toInt()) }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val up = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL
        val lifted = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        points = if (up) emptyList() else (0 until e.pointerCount).filter { it != lifted }.map { e.getX(it) to e.getY(it) }
        max = maxOf(max, points.size)
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        for ((x, y) in points) canvas.drawCircle(x, y, 38 * d, ring)
        canvas.drawText("${points.size}", width / 2f, height / 2f, big)
        canvas.drawText("fingers now · most so far: $max", width / 2f, height / 2f + 30 * d, small)
        canvas.drawText("Put down as many fingers as you can, then press Back", width / 2f, height / 2f + 56 * d, small)
    }
}
