// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

package dev.droiddesk.companion

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.DynamicDrawableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Tiny view-building toolkit for the pastel look: a soft grey dashboard with pastel cards, and
 * colour-blocked tool screens with big headings, glass cards and black pill buttons. Plain
 * framework views and drawables only, so the APK stays small (no AndroidX, no Compose).
 */
internal class Ui(private val ctx: Context) {

    private val density = ctx.resources.displayMetrics.density

    fun dp(v: Number): Int = (v.toFloat() * density).roundToInt()
    fun color(id: Int): Int = ctx.getColor(id)

    // Poppins (res/font, subset to Latin). "bold" is SemiBold, the heaviest weight the design uses.
    val light: Typeface = ctx.resources.getFont(R.font.poppins_light)
    /** The brand look has no italics: the second part of a heading is set upright like the first. */
    val lightItalic: Typeface = light
    val regular: Typeface = ctx.resources.getFont(R.font.poppins_regular)
    val medium: Typeface = ctx.resources.getFont(R.font.poppins_medium)
    val bold: Typeface = ctx.resources.getFont(R.font.poppins_semibold)

    // ---------- drawables ----------

    fun rounded(color: Int, radiusDp: Number): Drawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    /** The hero card's lime-to-green gradient: one per screen, for its headline figure. */
    fun hero(radiusDp: Number = 26): Drawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(color(R.color.hero_start), color(R.color.hero), color(R.color.hero_end)),
    ).apply { cornerRadius = dp(radiusDp).toFloat() }

    fun circle(color: Int): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /** Adds touch feedback; the ripple is clipped to [bg]'s shape. */
    fun pressable(bg: Drawable): Drawable =
        RippleDrawable(ColorStateList.valueOf(color(R.color.ripple)), bg, null)

    fun icon(res: Int, tint: Int): Drawable =
        ctx.getDrawable(res)!!.mutate().apply { setTint(tint) }

    // ---------- basic views ----------

    fun text(sizeSp: Float, color: Int, face: Typeface = regular, init: TextView.() -> Unit = {}) =
        TextView(ctx).apply {
            textSize = sizeSp
            setTextColor(color)
            typeface = face
            includeFontPadding = false
            init()
        }

    /** Plain page heading (used by screens that build their own header). */
    fun pageTitle(title: String) = text(30f, color(R.color.ink), light) {
        text = title
        letterSpacing = -0.02f
    }

    /**
     * Big expressive heading, "Activities *just for your* taste": [plain] upright, [italic] in
     * light italic. Either part may contain line breaks.
     */
    fun heading(plain: String, italic: String = "", after: String = "", sizeSp: Float = 38f) = text(sizeSp, color(R.color.ink), light) {
        letterSpacing = -0.03f
        setLineSpacing(0f, 0.95f)
        text = SpannableStringBuilder(plain).apply {
            if (italic.isNotEmpty()) {
                val s = length
                append(italic)
                setSpan(FontSpan(lightItalic), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            append(after)
        }
    }

    /** No shadows in this look; kept so callers can still ask for a lift (e.g. the nav button). */
    fun <T : View> elevate(v: T, dpVal: Number = 6): T = v.apply {
        elevation = dp(dpVal).toFloat()
        if (Build.VERSION.SDK_INT >= 28) {
            outlineSpotShadowColor = color(R.color.shadow)
            outlineAmbientShadowColor = color(R.color.shadow)
        }
    }

    /** Glass circle button with an icon, as in the screen headers. */
    fun circleButton(res: Int, desc: String, sizeDp: Int = 42, bg: Int = color(R.color.glass_strong), onClick: () -> Unit) =
        roundIcon(sizeDp, bg, res, color(R.color.ink), 20).apply {
            background = pressable(rounded(bg, sizeDp / 3)) // white rounded square, like the nav's active item
            contentDescription = desc
            setOnClickListener { onClick() }
        }

    /** One-line screen header: back button, the screen's short name in large type, optional action button. */
    fun topBar(title: String, onBack: () -> Unit, actionIcon: Int? = null, actionLabel: String? = null,
               onAction: (() -> Unit)? = null): Pair<LinearLayout, ImageView?> {
        var action: ImageView? = null
        val bar = row {
            addView(circleButton(R.drawable.ic_back, "Back", onClick = onBack), lp(w = dp(42), h = dp(42)))
            addView(text(28f, color(R.color.ink), light) {
                text = title; letterSpacing = -0.02f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, lp(w = 0, weight = 1f, start = 14))
            if (actionIcon != null && onAction != null) {
                action = circleButton(actionIcon, actionLabel ?: title, onClick = onAction)
                addView(action, lp(w = dp(42), h = dp(42), start = 8))
            } else addView(spacer(), lp(w = dp(42), h = dp(42), start = 8))
        }
        return bar to action
    }

    /** "Audience Flow                Filter ›" style section heading. */
    fun section(title: String, init: LinearLayout.() -> Unit = {}) = row {
        addView(text(22f, color(R.color.ink), light) { text = title; letterSpacing = -0.02f }, lp(w = 0, weight = 1f))
        init()
    }

    /** Small white circle holding an icon: the chip at the top-left of pastel cards. */
    fun chipIcon(res: Int, sizeDp: Int = 34) = roundIcon(sizeDp, color(R.color.glass_strong), res, color(R.color.ink), 17)

    /** Rounded square with a coloured icon (list rows, tiles). */
    fun iconTile(res: Int, fg: Int, bg: Int, sizeDp: Int = 40, iconDp: Int = 20) = ImageView(ctx).apply {
        background = rounded(bg, sizeDp * 0.32f)
        setImageDrawable(icon(res, fg))
        scaleType = ImageView.ScaleType.FIT_CENTER
        val p = dp((sizeDp - iconDp) / 2f)
        setPadding(p, p, p, p)
    }

    /** Scalloped "badge" behind an icon, like the date and time badges in the reference. */
    fun badge(res: Int, bg: Int, fg: Int, sizeDp: Int = 46) = ImageView(ctx).apply {
        background = Scallop(bg)
        setImageDrawable(icon(res, fg))
        val p = dp(sizeDp * 0.3f)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /**
     * Pastel dashboard tile ("Sport Data / Keep active / Check →"). Returns the box and the
     * subtitle so callers can show live values there.
     */
    class Tile(val box: LinearLayout, val sub: TextView)

    fun tile(bg: Int, title: String, iconRes: Int, onClick: () -> Unit): Tile {
        val sub = text(13f, color(R.color.ink_soft)) { maxLines = 2; setLineSpacing(dp(2).toFloat(), 1f) }
        val box = column {
            background = pressable(rounded(bg, 24))
            setPadding(dp(16), dp(16), dp(14), dp(16))
            addView(row {
                gravity = Gravity.TOP
                addView(iconView(iconRes, color(R.color.ink)), lp(w = dp(26), h = dp(26)))
                addView(spacer(), lp(w = 0, h = 1, weight = 1f))
                addView(iconView(R.drawable.ic_arrow_up_right, color(R.color.ink)), lp(w = dp(18), h = dp(18)))
            })
            addView(spacer(), lp(h = 0, weight = 1f))
            addView(text(17f, color(R.color.ink), regular) { text = title; maxLines = 2 }, lp(top = 14))
            addView(sub, lp(top = 4))
            contentDescription = title
            setOnClickListener { onClick() }
        }
        return Tile(box, sub)
    }

    /** "Proteins 62.5   Fats 23.6 …": a row of small labels over big numbers. */
    fun numbers(labels: List<String>): Pair<LinearLayout, List<TextView>> {
        val values = mutableListOf<TextView>()
        val r = row {
            gravity = Gravity.TOP
            labels.forEach { label ->
                addView(column {
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(text(12f, color(R.color.ink_soft)) { text = label; gravity = Gravity.CENTER; maxLines = 1 }, lp())
                    val v = text(24f, color(R.color.ink), regular) { text = "–"; gravity = Gravity.CENTER; maxLines = 1 }
                    values += v
                    addView(v, lp(top = 8))
                }, lp(w = 0, weight = 1f))
            }
        }
        return r to values
    }

    /** Translucent pill row inside a coloured card ("📅 Check dates  ›"). */
    fun listPill(iconRes: Int, label: String, onClick: (() -> Unit)? = null): Pair<LinearLayout, TextView> {
        val value = text(14f, color(R.color.ink), medium) { gravity = Gravity.END; maxLines = 1 }
        val r = row {
            background = if (onClick != null) pressable(rounded(color(R.color.glass), 50)) else rounded(color(R.color.glass), 50)
            setPadding(dp(6), dp(6), dp(14), dp(6))
            addView(roundIcon(34, color(R.color.glass_strong), iconRes, color(R.color.ink), 16))
            addView(text(14f, color(R.color.ink)) { text = label; maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                lp(w = 0, weight = 1f, start = 12))
            addView(value, lp(w = LinearLayout.LayoutParams.WRAP_CONTENT, start = 8))
            if (onClick != null) {
                addView(iconView(R.drawable.ic_chevron_right, color(R.color.ink)), lp(w = dp(20), h = dp(20), start = 6))
                setOnClickListener { onClick() }
            }
        }
        return r to value
    }

    /** "92%" with a smaller percent sign. */
    fun percent(value: Int): CharSequence = SpannableStringBuilder("$value%").apply {
        setSpan(RelativeSizeSpan(0.5f), length - 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** "1278 Kkal": the number at full size, the unit smaller and softer. */
    fun withUnit(value: String, unit: String): CharSequence = SpannableStringBuilder(value).apply {
        if (unit.isNotEmpty()) {
            val start = length
            append(" ").append(unit)
            setSpan(RelativeSizeSpan(0.5f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(color(R.color.ink_soft)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** A circle with a centred icon. */
    fun roundIcon(sizeDp: Int, bg: Int, iconRes: Int, tint: Int, iconDp: Int = 22) = ImageView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        background = circle(bg)
        setImageDrawable(icon(iconRes, tint))
        scaleType = ImageView.ScaleType.FIT_CENTER
        val p = dp((sizeDp - iconDp) / 2f)
        setPadding(p, p, p, p)
    }

    fun iconView(res: Int, tint: Int) = ImageView(ctx).apply { setImageDrawable(icon(res, tint)) }

    fun row(init: LinearLayout.() -> Unit = {}) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // Baseline alignment shifts top-aligned text down without growing the row, which cut off
        // the last line of wrapped text (e.g. the PC link steps) on some phones.
        isBaselineAligned = false
        init()
    }

    fun column(init: LinearLayout.() -> Unit = {}) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        init()
    }

    fun spacer() = View(ctx)

    fun lp(
        w: Int = LinearLayout.LayoutParams.MATCH_PARENT,
        h: Int = LinearLayout.LayoutParams.WRAP_CONTENT,
        weight: Float = 0f,
        top: Int = 0,
        start: Int = 0,
    ) = LinearLayout.LayoutParams(w, h, weight).apply {
        topMargin = dp(top)
        marginStart = dp(start)
    }

    // ---------- components ----------

    /** Rounded flat card. On the colour-blocked screens pass R.color.glass / surface. */
    fun card(bg: Int, padDp: Int = 12, init: LinearLayout.() -> Unit = {}) = column {
        background = if (bg == color(R.color.hero)) hero(26) else rounded(bg, 26)
        val p = dp(padDp)
        setPadding(p, p, p, p)
        init()
    }

    /** The screen's headline card, on the lime-to-green gradient. */
    fun heroCard(padDp: Int = 16, init: LinearLayout.() -> Unit = {}) = card(color(R.color.hero), padDp, init)

    /** Card on the grey page: off-white. */
    fun glassCard(padDp: Int = 16, init: LinearLayout.() -> Unit = {}) = card(color(R.color.glass), padDp, init)

    /** Card header: white icon chip, title and subtitle, optional round actions on the right. */
    fun cardHeader(iconRes: Int, title: String, init: LinearLayout.() -> Unit = {}): Pair<LinearLayout, TextView> {
        val sub = text(12f, color(R.color.ink_soft)) { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val r = row {
            addView(chipIcon(iconRes))
            addView(column {
                addView(text(15f, color(R.color.ink), medium) { text = title; maxLines = 1 })
                addView(sub, lp(top = 3))
            }, lp(w = 0, weight = 1f, start = 12))
            init()
        }
        return r to sub
    }

    /** Pill-shaped button or badge. Pass [onClick] null for a non-interactive badge. */
    fun pill(label: String, bg: Int, fg: Int, iconRes: Int? = null, onClick: (() -> Unit)? = null) =
        TextView(ctx).apply {
            textSize = 14f
            typeface = medium
            includeFontPadding = false
            gravity = Gravity.CENTER
            minHeight = dp(42)
            setPadding(dp(18), 0, dp(18), 0)
            stylePill(this, label, bg, fg, iconRes, onClick)
        }

    private val pillIcons = WeakHashMap<TextView, Int>()

    fun stylePill(tv: TextView, label: String, bg: Int, fg: Int, iconRes: Int? = null, onClick: (() -> Unit)? = null) {
        tv.setTextColor(fg)
        if (iconRes != null) pillIcons[tv] = iconRes else pillIcons.remove(tv)
        setLabel(tv, label)
        tv.background = if (onClick != null) pressable(rounded(bg, 50)) else rounded(bg, 50)
        if (onClick != null) tv.setOnClickListener { onClick() } else { tv.setOnClickListener(null); tv.isClickable = false }
    }

    /** Changes a pill's text, keeping its icon right before the label (centred together). */
    fun setLabel(tv: TextView, label: String) {
        val res = pillIcons[tv]
        if (res == null) { tv.text = label; return }
        val d = icon(res, tv.currentTextColor).apply { setBounds(0, 0, dp(18), dp(18)) }
        val align = if (Build.VERSION.SDK_INT >= 29) ImageSpan.ALIGN_CENTER else DynamicDrawableSpan.ALIGN_BASELINE
        tv.text = SpannableStringBuilder("   ").append(label).apply {
            setSpan(ImageSpan(d, align), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Big black pill button ("Start →"). */
    fun action(label: String, iconRes: Int?, onClick: () -> Unit) =
        pill(label, color(R.color.fab), color(R.color.on_fab), iconRes, onClick).apply {
            textSize = 16f
            minHeight = dp(56)
        }

    fun setEnabled(v: View, enabled: Boolean) {
        v.isEnabled = enabled
        v.alpha = if (enabled) 1f else 0.4f
    }

    /** Small stat card: icon chip + caption on top, value below. [bg] is the card colour. */
    class Stat(val box: LinearLayout, val value: TextView, val caption: TextView)

    fun stat(bg: Int, iconRes: Int, caption: String, valueSp: Float = 24f): Stat {
        val cap = text(13f, color(R.color.ink)) { text = caption; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val value = text(valueSp, color(R.color.ink), regular) { text = "–"; maxLines = 1 }
        val box = column {
            background = rounded(bg, 22)
            setPadding(dp(14), dp(14), dp(10), dp(16))
            addView(row {
                addView(cap, lp(w = 0, weight = 1f))
                addView(iconTile(iconRes, color(R.color.ink), color(R.color.chip), 28, 15), lp(w = dp(28), h = dp(28), start = 6))
            })
            addView(value, lp(top = 14))
        }
        return Stat(box, value, cap)
    }

    /** Note row: round icon, text, optional arrow when tappable. */
    fun insight(iconRes: Int, iconBg: Int, onClick: (() -> Unit)? = null): Pair<LinearLayout, TextView> {
        val label = text(14f, color(R.color.ink)) { setLineSpacing(dp(2).toFloat(), 1f) }
        val box = row {
            val bg = color(R.color.glass_strong)
            background = if (onClick != null) pressable(rounded(bg, 24)) else rounded(bg, 24)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(roundIcon(42, iconBg, iconRes, color(R.color.ink), 18))
            addView(label, lp(w = 0, weight = 1f, start = 14))
            if (onClick != null) {
                addView(iconView(R.drawable.ic_arrow_right, color(R.color.ink)), lp(w = dp(20), h = dp(20), start = 8))
                setOnClickListener { onClick() }
            }
        }
        return box to label
    }

    /** A "● Current" style status label. Returns the row, the dot and the label. */
    fun dotLabel(): Triple<LinearLayout, View, TextView> {
        val dot = View(ctx)
        val label = text(14f, color(R.color.ink_soft))
        val r = row {
            addView(dot, lp(w = dp(8), h = dp(8)))
            addView(label, lp(w = LinearLayout.LayoutParams.WRAP_CONTENT, start = 8))
        }
        return Triple(r, dot, label)
    }

    /** Key on the left, value on the right. */
    fun keyValue(key: String): Pair<LinearLayout, TextView> {
        val value = text(14f, color(R.color.ink), medium) { gravity = Gravity.END; maxLines = 3 }
        val r = row {
            gravity = Gravity.TOP
            addView(text(14f, color(R.color.ink_soft)) { text = key }, lp(w = LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(value, lp(w = 0, weight = 1f, start = 16))
        }
        return r to value
    }

    /** Round checkbox: filled check when on, empty ring when off. */
    fun setCheck(iv: ImageView, checked: Boolean) {
        iv.setImageDrawable(
            if (checked) icon(R.drawable.ic_check_circle, color(R.color.ink))
            else icon(R.drawable.ic_dot_empty, color(R.color.ink_faint)))
    }

    /** Solid pill switch: dark track when on, grey when off, white knob. */
    fun toggle(onClick: ((Boolean) -> Unit)? = null) = android.widget.Switch(ctx).apply {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        trackDrawable = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setSize(dp(50), dp(30))
            color = ColorStateList(states, intArrayOf(color(R.color.ink), color(R.color.ink_faint)))
        }
        thumbDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setSize(dp(30), dp(30))
            // On: knob in the opposite of the track colour (white in light mode, dark in dark mode).
            color = ColorStateList(states, intArrayOf(color(R.color.on_fab), android.graphics.Color.WHITE))
            setStroke(dp(4), android.graphics.Color.TRANSPARENT) // knob sits inside the track
        }
        showText = false
        if (onClick != null) setOnClickListener { onClick(isChecked) }
    }

    /** Day-style round chip ("MO TU WE"): selected is filled black. */
    fun styleDayChip(tv: TextView, selected: Boolean, accent: Int? = null) {
        tv.background = pressable(circle(when {
            selected -> accent ?: color(R.color.fab)
            else -> color(R.color.glass_strong)
        }))
        tv.setTextColor(if (selected) color(R.color.on_fab) else color(R.color.ink))
    }
}

/** Switches a run of text to another typeface (the real Poppins italic, not a synthetic slant). */
internal class FontSpan(private val face: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) { tp.typeface = face }
    override fun updateMeasureState(tp: TextPaint) { tp.typeface = face }
}

/** Scalloped circle (a flower-like badge), drawn as a Drawable so it can sit behind an icon. */
internal class Scallop(private val fill: Int, private val lobes: Int = 9) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private val path = Path()
    override fun draw(canvas: Canvas) {
        val b = bounds
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        val r = minOf(b.width(), b.height()) / 2f
        path.reset()
        val steps = 180
        for (i in 0..steps) {
            val a = 2 * Math.PI * i / steps
            val rr = r * (0.88f + 0.12f * cos(lobes * a).toFloat())
            val x = cx + rr * cos(a).toFloat(); val y = cy + rr * sin(a).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, p)
    }
    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    @Deprecated("Framework API") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Rounded progress bar. */
internal class Meter(ctx: Context) : View(ctx) {
    var fraction = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun colors(trackColor: Int, fillColor: Int) { track.color = trackColor; fill.color = fillColor; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, track)
        if (fraction > 0f) {
            rect.right = max(height.toFloat(), width * fraction)
            canvas.drawRoundRect(rect, r, r, fill)
        }
    }
}

/**
 * Smooth line chart with dashed vertical guides and a dot on the latest value, matching the
 * heart-rate chart in the reference design. Shows the last [slots] values, filling from the left.
 */
internal class LineChart(ctx: Context) : View(ctx) {
    var values: List<Float> = emptyList()
        set(v) { field = v.takeLast(slots); invalidate() }
    var slots = 60
    /** When set, the y axis runs from 0 to max(this, data max); otherwise it fits the data. */
    var floorZero = false
    var minSpan = 1f
    /** Soft gradient under the line, in the line colour. */
    var fill = false
    /** Vertical marker through the latest value, with a dot at the top (0 = none). */
    var marker = 0

    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * d
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * d
        pathEffect = DashPathEffect(floatArrayOf(3 * d, 4 * d), 0f)
    }
    private val area = Paint(Paint.ANTI_ALIAS_FLAG)
    private val areaPath = Path()
    private val dotOuter = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotInner = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    fun colors(lineColor: Int, guideColor: Int, dotRing: Int) {
        line.color = lineColor; dotInner.color = lineColor
        guide.color = guideColor; dotOuter.color = dotRing
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val pad = 8 * d
        val left = pad; val right = width - pad
        val top = pad; val bottom = height - pad
        for (i in 0..4) {
            val x = left + (right - left) * i / 4f
            canvas.drawLine(x, 0f, x, height.toFloat(), guide)
        }
        if (values.size < 2) return

        var lo = if (floorZero) 0f else values.min()
        var hi = values.max()
        if (hi - lo < minSpan) {
            if (floorZero) hi = lo + minSpan
            else { val mid = (hi + lo) / 2; lo = mid - minSpan / 2; hi = mid + minSpan / 2 }
        }
        hi += (hi - lo) * 0.1f
        fun x(i: Int) = left + (right - left) * i / (slots - 1).toFloat()
        fun y(v: Float) = bottom - (bottom - top) * (v - lo) / (hi - lo)

        path.reset()
        path.moveTo(x(0), y(values[0]))
        for (i in 1 until values.size) {
            val x0 = x(i - 1); val x1 = x(i); val mid = (x0 + x1) / 2
            path.cubicTo(mid, y(values[i - 1]), mid, y(values[i]), x1, y(values[i]))
        }
        if (fill) {
            areaPath.set(path)
            areaPath.lineTo(x(values.size - 1), height.toFloat()); areaPath.lineTo(x(0), height.toFloat()); areaPath.close()
            area.shader = LinearGradient(0f, top, 0f, height.toFloat(),
                (line.color and 0xFFFFFF) or 0x40000000, line.color and 0xFFFFFF, Shader.TileMode.CLAMP)
            canvas.drawPath(areaPath, area)
        }
        canvas.drawPath(path, line)
        val lx = x(values.size - 1); val ly = y(values.last())
        if (marker != 0) {
            dotInner.color = marker
            guide.color.let { g ->
                guide.color = marker; guide.pathEffect = null
                canvas.drawLine(lx, top, lx, height.toFloat(), guide)
                guide.color = g; guide.pathEffect = DashPathEffect(floatArrayOf(3 * d, 4 * d), 0f)
            }
            canvas.drawCircle(lx, top, 6.5f * d, dotOuter)
            canvas.drawCircle(lx, top, 4.5f * d, dotInner)
            dotInner.color = line.color
        }
        canvas.drawCircle(lx, ly, 6.5f * d, dotOuter)
        canvas.drawCircle(lx, ly, 4f * d, dotInner)
    }
}

/** "1.2 GB" / "340 MB", using 1000-based units like Android's own storage screens. */
internal fun sizeParts(bytes: Long): Pair<String, String> {
    val b = bytes.toDouble()
    return when {
        b >= 1e12 -> String.format("%.1f", b / 1e12) to "TB"
        b >= 1e9 -> String.format(if (b >= 1e11) "%.0f" else "%.1f", b / 1e9) to "GB"
        b >= 1e6 -> String.format("%.0f", b / 1e6) to "MB"
        b >= 1e3 -> String.format("%.0f", b / 1e3) to "KB"
        else -> "$bytes" to "B"
    }
}

internal fun fmtSize(bytes: Long) = sizeParts(bytes).let { "${it.first} ${it.second}" }

/**
 * Ring gauge with a lime gradient and round ends (health score, junk total, memory). With
 * [segments] set it draws a multi-colour donut instead (storage breakdown).
 */
internal class Ring(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    var strokeDp = 14f
        set(v) { field = v; invalidate() }
    private var shown = 0f
    private var animator: ValueAnimator? = null
    /** 0..1; changes animate. */
    var fraction = 0f
        set(v) {
            val target = v.coerceIn(0f, 1f)
            if (target == field && animator == null) return
            field = target
            animator?.cancel()
            animator = ValueAnimator.ofFloat(shown, target).apply {
                duration = 700; interpolator = DecelerateInterpolator()
                addUpdateListener { shown = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    /** Spinning arc while something is being measured. */
    var busy = false
        set(v) { field = v; if (v) postInvalidateOnAnimation() else invalidate() }
    var segments: List<Pair<Float, Int>> = emptyList()
        set(v) { field = v; invalidate() }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val seg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val rect = RectF()
    private var from = 0; private var to = 0
    private val rotate = Matrix()

    fun colors(trackColor: Int, start: Int, end: Int) {
        track.color = trackColor; from = start; to = end; arc.shader = null; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val sw = strokeDp * d
        track.strokeWidth = sw; arc.strokeWidth = sw; seg.strokeWidth = sw
        val size = minOf(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f
        val r = (size - sw) / 2f
        rect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawOval(rect, track)
        if (segments.isNotEmpty()) {
            var start = -90f
            val gap = if (segments.count { it.first > 0.004f } > 1) 2.5f else 0f
            for ((f, c) in segments) {
                val sweep = 360f * f
                if (sweep < 0.5f) continue
                seg.color = c
                canvas.drawArc(rect, start + gap / 2, (sweep - gap).coerceAtLeast(0.5f), false, seg)
                start += sweep
            }
            return
        }
        if (arc.shader == null && width > 0) {
            // Gradient runs light → strong around the ring, starting at 12 o'clock.
            arc.shader = SweepGradient(cx, cy, intArrayOf(from, to, to), floatArrayOf(0f, 0.85f, 1f)).also {
                rotate.setRotate(-90f, cx, cy); it.setLocalMatrix(rotate)
            }
        }
        if (busy) {
            val t = (System.currentTimeMillis() % 1200) / 1200f
            canvas.drawArc(rect, 360f * t - 90f, 90f, false, arc.apply { alpha = 255 })
            postInvalidateOnAnimation()
            return
        }
        if (shown > 0.002f) canvas.drawArc(rect, -90f, 360f * shown, false, arc)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { arc.shader = null }
}

/** Decorative soft wave along the bottom of a card: a filled gradient or a single line. */
internal class Wave(ctx: Context, private val filled: Boolean) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var tint = 0
    var waves = 2.5f

    fun tint(c: Int) { tint = c; paint.shader = null; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f) return
        path.reset()
        val amp = if (filled) h * 0.22f else h * 0.32f
        val mid = if (filled) h * 0.42f else h * 0.5f
        val steps = 60
        for (i in 0..steps) {
            val x = w * i / steps
            val y = mid + amp * kotlin.math.sin((i / steps.toFloat()) * waves * 2 * Math.PI - 0.6).toFloat() *
                (0.55f + 0.45f * kotlin.math.sin(i / steps.toFloat() * Math.PI).toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (filled) {
            path.lineTo(w, h); path.lineTo(0f, h); path.close()
            paint.style = Paint.Style.FILL
            if (paint.shader == null) paint.shader = LinearGradient(0f, 0f, 0f, h,
                (tint and 0xFFFFFF) or 0x66000000, (tint and 0xFFFFFF) or 0x1A000000, Shader.TileMode.CLAMP)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f * d
            paint.strokeCap = Paint.Cap.ROUND
            if (paint.shader == null) paint.shader = LinearGradient(0f, 0f, w, 0f,
                (tint and 0xFFFFFF) or 0x33000000, tint, Shader.TileMode.CLAMP)
        }
        canvas.drawPath(path, paint)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { paint.shader = null }
}

/** A glossy battery drawn to [level] (0..1), with a bolt while charging. */
internal class BatteryView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    var level = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var charging = false
        set(v) { field = v; invalidate() }
    private var low = 0; private var high = 0; private var glass = 0; private var cap = 0; private var bolt = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val boltPath = Path()

    fun colors(glassColor: Int, capColor: Int, fillLight: Int, fillStrong: Int, boltColor: Int) {
        glass = glassColor; cap = capColor; low = fillLight; high = fillStrong; bolt = boltColor; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val bw = h * 0.56f
        val left = (width - bw) / 2f
        val capH = h * 0.07f
        val body = RectF(left, capH, left + bw, h)
        val r = bw * 0.2f
        // cap
        p.shader = null; p.color = cap
        rect.set(left + bw * 0.3f, 0f, left + bw * 0.7f, capH + r)
        canvas.drawRoundRect(rect, capH * 0.5f, capH * 0.5f, p)
        // glass body
        p.color = glass
        canvas.drawRoundRect(body, r, r, p)
        // fill
        val inset = 5 * d
        val inner = RectF(body.left + inset, body.top + inset, body.right - inset, body.bottom - inset)
        val top = inner.bottom - inner.height() * level
        if (level > 0f) {
            canvas.save()
            val clip = Path().apply { addRoundRect(inner, r - inset, r - inset, Path.Direction.CW) }
            canvas.clipPath(clip)
            p.shader = LinearGradient(0f, top, 0f, inner.bottom, low, high, Shader.TileMode.CLAMP)
            canvas.drawRect(inner.left, top, inner.right, inner.bottom, p)
            // highlight stripe for a bit of gloss
            p.shader = null; p.color = 0x33FFFFFF
            canvas.drawRect(inner.left + inner.width() * 0.12f, top, inner.left + inner.width() * 0.26f, inner.bottom, p)
            canvas.restore()
        }
        if (charging) {
            val cx = body.centerX(); val cy = body.centerY() + capH / 2
            val s = bw * 0.36f
            boltPath.reset()
            boltPath.moveTo(cx + s * 0.15f, cy - s)
            boltPath.lineTo(cx - s * 0.55f, cy + s * 0.12f)
            boltPath.lineTo(cx - s * 0.02f, cy + s * 0.12f)
            boltPath.lineTo(cx - s * 0.15f, cy + s)
            boltPath.lineTo(cx + s * 0.55f, cy - s * 0.12f)
            boltPath.lineTo(cx + s * 0.02f, cy - s * 0.12f)
            boltPath.close()
            p.shader = null; p.color = bolt
            canvas.drawPath(boltPath, p)
        }
    }
}
