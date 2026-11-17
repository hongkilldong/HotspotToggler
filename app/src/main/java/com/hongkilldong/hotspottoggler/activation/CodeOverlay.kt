package com.hongkilldong.hotspottoggler.activation

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.Keep
import com.hongkilldong.hotspottoggler.AppTheme
import com.hongkilldong.hotspottoggler.R
import com.hongkilldong.hotspottoggler.ui.MATCH
import com.hongkilldong.hotspottoggler.ui.PillButton
import com.hongkilldong.hotspottoggler.ui.WRAP
import com.hongkilldong.hotspottoggler.ui.clr
import com.hongkilldong.hotspottoggler.ui.dp
import com.hongkilldong.hotspottoggler.ui.hbox
import com.hongkilldong.hotspottoggler.ui.lp
import com.hongkilldong.hotspottoggler.ui.margins
import com.hongkilldong.hotspottoggler.ui.mono
import com.hongkilldong.hotspottoggler.ui.outlineButton
import com.hongkilldong.hotspottoggler.ui.pad
import com.hongkilldong.hotspottoggler.ui.primaryButton
import com.hongkilldong.hotspottoggler.ui.shape
import com.hongkilldong.hotspottoggler.ui.text
import com.hongkilldong.hotspottoggler.ui.vbox

/**
 * Small TYPE_APPLICATION_OVERLAY input. It stays above the Settings pairing dialog, so the user
 * can type the 6-digit code without switching apps and invalidating the pairing port.
 */
@Keep
class CodeOverlay private constructor(
    private val service: Context,
    private val onSubmit: (String) -> Unit,
    private val onCancel: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(WindowManager::class.java)
    private val context = android.view.ContextThemeWrapper(AppTheme.wrap(service), R.style.Theme_HotspotToggler)
    private val root: FrameLayout = FrameLayout(context)
    private var shown = false
    private var error: android.widget.TextView? = null

    private fun build(): View {
        val card = context.vbox(10).apply {
            background = context.shape(context.clr(R.color.surf), 22)
            pad(context, 16, 14, 16, 14)
        }
        card.addView(context.text(context.getString(R.string.overlay_code_title), 18f, 600), lp(MATCH, WRAP))
        card.addView(
            context.text(context.getString(R.string.overlay_code_sub), 13f, 400, context.clr(R.color.on2)),
            lp(MATCH, WRAP),
        )
        val edit = EditText(context).apply {
            hint = context.getString(R.string.overlay_code_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(context.clr(R.color.on))
            setHintTextColor(context.clr(R.color.on2))
            background = context.shape(context.clr(R.color.surf2), 14)
            pad(context, 16, 10, 16, 10)
            inputType = InputType.TYPE_CLASS_NUMBER
            typeface = context.mono()
            gravity = Gravity.CENTER
            isSingleLine = true
            tag = "code"
        }
        card.addView(edit, lp(MATCH, WRAP).margins(context, 0, 4, 0, 0))
        error = context.text("", 12f, 500, context.clr(R.color.err)).apply { visibility = View.GONE }
        card.addView(error, lp(MATCH, WRAP))
        val actions = context.hbox(8)
        val cancel = context.outlineButton(context.getString(R.string.access_cancel), 0, 48, 15f)
        cancel.setOnClickListener {
            hide()
            onCancel()
        }
        val send = context.primaryButton(context.getString(R.string.notif_code_reply), 0, 48, 15f)
        send.setOnClickListener {
            val code = edit.text.toString().trim()
            if (code.length != 6 || code.any { !it.isDigit() }) {
                error?.text = context.getString(R.string.overlay_code_invalid)
                error?.visibility = View.VISIBLE
                return@setOnClickListener
            }
            hide()
            onSubmit(code)
        }
        actions.addView(cancel, lp(0, WRAP, 1f))
        actions.addView(send, lp(0, WRAP, 1f))
        card.addView(actions, lp(MATCH, WRAP))
        root.addView(card, FrameLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = context.dp(8)
            marginStart = context.dp(12)
            marginEnd = context.dp(12)
        })
        root.setPadding(0, context.dp(8), 0, 0)
        return root
    }

    private fun addToWindow() {
        if (shown) return
        if (!android.provider.Settings.canDrawOverlays(service)) return
        build()
        val params = WindowManager.LayoutParams(
            MATCH,
            WRAP,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        }
        runCatching { wm.addView(root, params) }
            .onFailure { return }
        shown = true
        val edit = root.findViewWithTag<EditText>("code")
        edit?.requestFocus()
    }

    fun show() {
        handler.post {
            addToWindow()
            val edit = root.findViewWithTag<EditText>("code")
            edit?.requestFocus()
            val imm = service.getSystemService(InputMethodManager::class.java)
            if (edit != null) edit.post { imm?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT) }
        }
    }

    fun hide() {
        handler.post {
            if (!shown) return@post
            shown = false
            runCatching { wm.removeView(root) }
        }
    }

    companion object {
        fun show(service: Context, onSubmit: (String) -> Unit, onCancel: () -> Unit): CodeOverlay =
            CodeOverlay(service, onSubmit, onCancel).also { it.show() }
    }
}
