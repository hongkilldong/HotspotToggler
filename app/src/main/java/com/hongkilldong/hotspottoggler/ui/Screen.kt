package com.hongkilldong.hotspottoggler.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hongkilldong.hotspottoggler.MainActivity
import com.hongkilldong.hotspottoggler.R

abstract class Screen(val host: MainActivity) {
    protected val ctx: Context get() = host
    abstract val view: View

    open fun onShow() {}
    open fun onHide() {}
    open fun tick() {}
    open fun onBack(): Boolean = false

    fun simpleHeader(title: String, back: Boolean = true): LinearLayout {
        val header = ctx.hbox().apply {
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            pad(ctx, 8, 8, 8, 0)
            minimumHeight = ctx.dp(64)
        }
        if (back) {
            header.addView(ctx.iconButton(R.drawable.ic_back, ctx.clr(R.color.on), ctx.getString(R.string.access_cancel)).apply {
                setOnClickListener { host.showHome() }
            }, lp(ctx.dp(48), ctx.dp(48)))
        } else {
            header.addView(View(ctx), lp(ctx.dp(8), 1))
        }
        header.addView(
            ctx.text(title, 22f, 600).apply { letterSpacing = -0.01f },
            lp(0, WRAP, 1f).margins(ctx, 8, 0, 0, 0),
        )
        header.addView(View(ctx), lp(ctx.dp(8), 1))
        return header
    }

    fun scrollBody(body: View, footer: View? = null): LinearLayout {
        val root = ctx.vbox()
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        root.addView(scroll, lp(MATCH, 0, 1f))
        footer?.let { root.addView(it, lp(MATCH, WRAP)) }
        return root
    }

    fun labelValue(label: String, value: String, mono: Boolean = false): LinearLayout {
        val row = ctx.hbox(12).apply {
            minimumHeight = ctx.dp(36)
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(ctx.text(label, 14f, 400, ctx.clr(R.color.on2)), lp(0, WRAP, 1f))
        row.addView(
            ctx.text(value, 14f, 500, monospace = mono).apply {
                gravity = Gravity.END
                ellipsizeEnd()
            },
            lp(0, WRAP, 1f),
        )
        return row
    }

    fun numberedList(items: List<String>): LinearLayout {
        val box = ctx.vbox()
        items.forEachIndexed { index, item ->
            if (index > 0) box.addView(ctx.divider())
            val row = ctx.hbox(12).apply {
                pad(ctx, 12, 12, 12, 12)
            }
            val number = TextView(ctx).apply {
                text = (index + 1).toString()
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(ctx.clr(R.color.onpri))
                typeface = ctx.sans(700)
                gravity = Gravity.CENTER
                background = ctx.oval(ctx.clr(R.color.pri))
            }
            row.addView(number, lp(ctx.dp(24), ctx.dp(24)))
            row.addView(ctx.text(item, 15f, 400).apply { lineHeight = ctx.dp(20) }, lp(0, WRAP, 1f))
            box.addView(row, lp(MATCH, WRAP))
        }
        return box
    }

    fun mono(value: String, sp: Float = 14f, color: Int = ctx.clr(R.color.on)): TextView =
        ctx.text(value, sp, 500, color, monospace = true)
}
