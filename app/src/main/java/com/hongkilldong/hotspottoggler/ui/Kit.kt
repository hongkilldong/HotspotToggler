package com.hongkilldong.hotspottoggler.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView
import com.hongkilldong.hotspottoggler.R
import java.util.concurrent.ConcurrentHashMap

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(value: Number): Int = (value.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

fun Context.clr(id: Int): Int = getColor(id)

private val typeCache = ConcurrentHashMap<String, Typeface>()

fun Context.sans(weight: Int = 400): Typeface = typeCache.getOrPut("g$weight") {
    runCatching {
        Typeface.Builder(assets, "fonts/golos.ttf")
            .setFontVariationSettings("'wght' $weight")
            .build()
    }.getOrNull() ?: Typeface.create("sans-serif", weight)
}

fun Context.mono(weight: Int = 400): Typeface = typeCache.getOrPut("m$weight") {
    runCatching {
        Typeface.Builder(assets, "fonts/jetbrains_mono.ttf")
            .setFontVariationSettings("'wght' $weight")
            .build()
    }.getOrNull() ?: Typeface.create("monospace", weight)
}

fun Context.text(
    value: CharSequence,
    sp: Float,
    weight: Int = 400,
    color: Int = clr(R.color.on),
    monospace: Boolean = false,
): TextView = TextView(this).apply {
    text = value
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(color)
    typeface = if (monospace) mono(weight) else sans(weight)
    includeFontPadding = false
}

fun Context.icon(res: Int, sizeDp: Number, tint: Int): ImageView = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(tint)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

fun Context.shape(fill: Int, radiusDp: Number, stroke: Int = 0, strokeDp: Number = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != 0) setStroke(dp(strokeDp), stroke)
    }

fun Context.oval(fill: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(fill)
}

fun Context.rippleBg(content: Drawable?, radiusDp: Number): RippleDrawable {
    val mask = GradientDrawable().apply {
        setColor(Color.BLACK)
        cornerRadius = dp(radiusDp).toFloat()
    }
    return RippleDrawable(ColorStateList.valueOf(clr(R.color.ripple)), content, mask)
}

fun Context.vbox(gapDp: Number = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (gapDp.toInt() > 0) {
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply {
            setSize(1, dp(gapDp))
            setColor(Color.TRANSPARENT)
        }
    }
}

fun Context.hbox(gapDp: Number = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    if (gapDp.toInt() > 0) {
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply {
            setSize(dp(gapDp), 1)
            setColor(Color.TRANSPARENT)
        }
    }
}

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

fun Context.lpDp(w: Int, hDp: Number, weight: Float = 0f) = LinearLayout.LayoutParams(w, dp(hDp), weight)

fun View.pad(ctx: Context, l: Number = 0, t: Number = 0, r: Number = 0, b: Number = 0) =
    setPaddingRelative(ctx.dp(l), ctx.dp(t), ctx.dp(r), ctx.dp(b))

fun LinearLayout.LayoutParams.margins(ctx: Context, l: Number = 0, t: Number = 0, r: Number = 0, b: Number = 0) = apply {
    marginStart = ctx.dp(l)
    topMargin = ctx.dp(t)
    marginEnd = ctx.dp(r)
    bottomMargin = ctx.dp(b)
}

fun Context.card(radiusDp: Number = 20, fill: Int = clr(R.color.surf)): LinearLayout = vbox().apply {
    background = shape(fill, radiusDp)
    clipToOutline = true
}

fun Context.divider(): View = View(this).apply {
    setBackgroundColor(clr(R.color.surf2))
    layoutParams = LinearLayout.LayoutParams(MATCH, dp(1))
}

fun Context.sectionTitle(text: String): TextView = text(text, 14f, 600, clr(R.color.acc)).apply {
    layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).margins(this@sectionTitle, 8, 12, 8, 4)
}

fun Context.iconButton(res: Int, tint: Int, desc: String, sizeDp: Int = 48): FrameLayout = FrameLayout(this).apply {
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    background = rippleBg(null, sizeDp / 2)
    isClickable = true
    isFocusable = true
    contentDescription = desc
    addView(icon(res, 24, tint), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
}

class PillButton(
    ctx: Context,
    private val fill: Int,
    private val fg: Int,
    labelText: String,
    iconRes: Int = 0,
    heightDp: Int = 64,
    sp: Float = 18f,
    private val stroke: Int = 0,
) : LinearLayout(ctx) {
    private val labelView = ctx.text(labelText, sp, 600, fg).apply { gravity = Gravity.CENTER }
    private val iconView = if (iconRes != 0) ctx.icon(iconRes, 22, fg) else null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        background = ctx.rippleBg(
            if (fill != 0 || stroke != 0) ctx.shape(fill, heightDp / 2, stroke, 1.5f) else null,
            heightDp / 2,
        )
        isClickable = true
        isFocusable = true
        minimumHeight = ctx.dp(heightDp)
        pad(ctx, 20, 0, 20, 0)
        iconView?.let { addView(it, lp(ctx.dp(22), ctx.dp(22)).margins(ctx, 0, 0, 10, 0)) }
        addView(labelView, lp(0, WRAP, 1f))
        layoutParams = LinearLayout.LayoutParams(MATCH, ctx.dp(heightDp))
    }

    var label: String
        get() = labelView.text.toString()
        set(value) { labelView.text = value }

    fun setBusy(busy: Boolean) {
        isEnabled = !busy
        alpha = if (busy) 0.55f else 1f
    }

    fun setIconVisible(visible: Boolean) {
        iconView?.visibility = if (visible) View.VISIBLE else View.GONE
    }
}

fun Context.primaryButton(label: String, iconRes: Int = 0, heightDp: Int = 64, sp: Float = 18f): PillButton =
    PillButton(this, clr(R.color.pri), clr(R.color.onpri), label, iconRes, heightDp, sp)

fun Context.outlineButton(label: String, iconRes: Int = 0, heightDp: Int = 64, sp: Float = 18f): PillButton =
    PillButton(this, 0, clr(R.color.on), label, iconRes, heightDp, sp, clr(R.color.line))

fun Context.tonalButton(label: String, heightDp: Int = 52, sp: Float = 15f): PillButton =
    PillButton(this, clr(R.color.surf2), clr(R.color.on), label, 0, heightDp, sp)

fun Context.linkButton(label: String, heightDp: Int = 48, sp: Float = 15f): PillButton =
    PillButton(this, 0, clr(R.color.acc), label, 0, heightDp, sp)

class ToggleRow(
    ctx: Context,
    title: String,
    sub: String?,
    checked: Boolean,
    private val standalone: Boolean = true,
    val onChange: (Boolean) -> Unit,
) : LinearLayout(ctx) {
    private val sw = Switch(ctx).apply {
        isChecked = checked
        showText = false
        isClickable = false
        isFocusable = false
        switchMinWidth = ctx.dp(48)
        thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ctx.clr(R.color.pri), ctx.clr(R.color.on2)),
        )
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ctx.clr(R.color.pric), ctx.clr(R.color.surf2)),
        )
    }
    private val subView = sub?.let { ctx.text(it, 14f, 400, ctx.clr(R.color.on2)) }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ctx.dp(if (sub == null) 60 else 76)
        pad(ctx, 16, 10, 12, 10)
        val col = ctx.vbox(3)
        col.addView(ctx.text(title, 17f, 500))
        subView?.let { col.addView(it) }
        addView(col, lp(0, WRAP, 1f))
        addView(sw, lp(WRAP, WRAP).margins(ctx, 12))
        if (standalone) background = ctx.rippleBg(ctx.shape(ctx.clr(R.color.surf), 20), 20)
        isClickable = true
        setOnClickListener {
            sw.toggle()
            onChange(sw.isChecked)
        }
    }

    fun setChecked(value: Boolean) {
        if (sw.isChecked != value) sw.isChecked = value
    }
}

class RadioRow(
    private val ctx: Context,
    title: String,
    sub: String,
    val onPick: () -> Unit,
) : LinearLayout(ctx) {
    private val radio = RadioButton(ctx).apply {
        buttonTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ctx.clr(R.color.pri), ctx.clr(R.color.on2)),
        )
        isClickable = false
        isFocusable = false
    }
    private val titleView = ctx.text(title, 17f, 500)
    private val subView = ctx.text(sub, 14f, 400, ctx.clr(R.color.on2)).apply {
        visibility = if (sub.isBlank()) View.GONE else View.VISIBLE
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ctx.dp(if (sub.isBlank()) 56 else 72)
        pad(ctx, 8, 10, 16, 10)
        addView(radio, lp(WRAP, WRAP))
        val col = ctx.vbox(2)
        col.addView(titleView)
        col.addView(subView)
        addView(col, lp(0, WRAP, 1f).margins(ctx, 6, 0, 0, 0))
        isClickable = true
        setOnClickListener { onPick() }
        setSelectedUi(false)
    }

    fun setSelectedUi(selected: Boolean) {
        background = ctx.rippleBg(
            ctx.shape(ctx.clr(if (selected) R.color.pric else R.color.surf), 20),
            20,
        )
        radio.isChecked = selected
        titleView.setTextColor(ctx.clr(if (selected) R.color.onpric else R.color.on))
        subView.setTextColor(ctx.clr(if (selected) R.color.onpric else R.color.on2))
        titleView.typeface = ctx.sans(if (selected) 600 else 500)
    }

    fun setSub(text: String) {
        subView.text = text
        subView.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }
}

enum class StageState { DONE, ACTIVE, TODO, ERROR, PAUSE }

fun Context.stageIcon(state: StageState): View {
    val box = FrameLayout(this)
    fun glyph(res: Int, tint: Int) = box.addView(icon(res, 16, tint), FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
    when (state) {
        StageState.DONE -> { box.background = oval(clr(R.color.ok)); glyph(R.drawable.ic_check, clr(R.color.onok)) }
        StageState.ACTIVE -> {
            box.background = oval(clr(R.color.pri))
            box.addView(text("!", 16f, 700, clr(R.color.onpri)).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        StageState.PAUSE -> { box.background = oval(clr(R.color.warnc)); glyph(R.drawable.ic_play, clr(R.color.warn)) }
        StageState.ERROR -> { box.background = oval(clr(R.color.err)); glyph(R.drawable.ic_alert, clr(R.color.onerr)) }
        StageState.TODO -> {
            box.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), clr(R.color.line))
            }
        }
    }
    return box
}

fun Context.stageRow(label: String, state: StageState, meta: String, onClick: (() -> Unit)? = null): LinearLayout {
    val row = hbox(14)
    row.minimumHeight = dp(52)
    row.pad(this, 20, 0, 20, 0)
    row.addView(stageIcon(state), lp(dp(28), dp(28)))
    row.addView(text(label, 16f, 500, clr(if (state == StageState.ACTIVE) R.color.on else R.color.on2)), lp(0, WRAP, 1f))
    val color = when (state) {
        StageState.ERROR -> R.color.err
        StageState.PAUSE -> R.color.warn
        StageState.ACTIVE, StageState.TODO -> R.color.acc
        else -> R.color.on2
    }
    row.addView(text(meta, 14f, 500, clr(color)))
    if (onClick != null) {
        row.isClickable = true
        row.background = rippleBg(null, 12)
        row.setOnClickListener { onClick() }
    }
    return row
}

fun Context.chip(textValue: String, fg: Int = clr(R.color.on2), bg: Int = clr(R.color.surf2)): TextView =
    text(textValue, 12f, 600, fg).apply {
        background = shape(bg, 8)
        pad(this@chip, 8, 3, 8, 3)
    }

fun Context.choiceChips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit): LinearLayout {
    val row = hbox(8)
    for ((value, label) in options) {
        val chip = text(label, 14f, 600, clr(if (value == selected) R.color.onpri else R.color.on)).apply {
            background = shape(clr(if (value == selected) R.color.pri else R.color.surf2), 14)
            pad(this@choiceChips, 14, 8, 14, 8)
            isClickable = true
            isFocusable = true
            setOnClickListener { onSelect(value) }
        }
        row.addView(chip, lp(WRAP, WRAP))
    }
    return row
}

fun Context.actionRow(iconRes: Int, title: String, sub: String?, onClick: () -> Unit): LinearLayout {
    val row = hbox(12).apply {
        minimumHeight = dp(64)
        pad(this@actionRow, 16, 10, 16, 10)
        background = rippleBg(shape(clr(R.color.surf), 20), 20)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }
    row.addView(icon(iconRes, 22, clr(R.color.acc)))
    val col = vbox(2)
    col.addView(text(title, 16f, 500))
    sub?.let { col.addView(text(it, 13f, 400, clr(R.color.on2))) }
    row.addView(col, lp(0, WRAP, 1f))
    row.addView(icon(R.drawable.ic_chevron, 20, clr(R.color.on2)))
    return row
}

fun TextView.ellipsizeEnd(max: Int = 1) {
    this.maxLines = max
    ellipsize = TextUtils.TruncateAt.END
}
