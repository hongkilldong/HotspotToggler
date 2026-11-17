package com.hongkilldong.hotspottoggler.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hongkilldong.hotspottoggler.AppLog
import com.hongkilldong.hotspottoggler.MainActivity
import com.hongkilldong.hotspottoggler.Prefs
import com.hongkilldong.hotspottoggler.R
import com.hongkilldong.hotspottoggler.TogglerState
import com.hongkilldong.hotspottoggler.activation.ActivationService
import com.hongkilldong.hotspottoggler.activation.AdbController

class DiagnosticsScreen(host: MainActivity) : Screen(host) {
    private val root = ctx.vbox()
    private val body = ctx.vbox(12).apply { pad(ctx, 16, 8, 16, 16) }
    private val scroll = ScrollView(ctx).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
    }
    private val checks = ctx.card(20).apply { pad(ctx, 0, 4, 0, 4) }
    private val logView = TextView(ctx).apply {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(ctx.clr(R.color.on2))
        typeface = ctx.mono()
        setTextIsSelectable(true)
        pad(ctx, 12, 10, 12, 10)
    }
    private var lastLog = ""
    private var lastCheckKey: String? = null

    init {
        root.addView(simpleHeader(ctx.getString(R.string.diag_title)), ctx.lpDp(MATCH, 64))
        val logCard = ctx.card(20).apply {
            addView(ctx.text(ctx.getString(R.string.diag_log), 14f, 600, ctx.clr(R.color.acc)).apply { pad(ctx, 16, 12, 16, 8) }, lp(MATCH, WRAP))
            addView(ScrollView(ctx).apply {
                isFillViewport = true
                addView(logView, FrameLayout.LayoutParams(MATCH, WRAP))
            }, ctx.lpDp(MATCH, 300))
            val actions = ctx.vbox(8).apply { pad(ctx, 12, 8, 12, 12) }
            val share = ctx.tonalButton(ctx.getString(R.string.diag_share), 52, 14f)
            share.setOnClickListener { share() }
            val copy = ctx.tonalButton(ctx.getString(R.string.diag_copy), 52, 14f)
            copy.setOnClickListener { copy() }
            val clear = ctx.tonalButton(ctx.getString(R.string.diag_clear), 52, 14f)
            clear.setOnClickListener { AppLog.clear(); lastLog = ""; update() }
            actions.addView(share, ctx.lpDp(MATCH, 52))
            actions.addView(copy, ctx.lpDp(MATCH, 52))
            actions.addView(clear, ctx.lpDp(MATCH, 52))
            addView(actions, lp(MATCH, WRAP))
        }
        body.addView(checks, lp(MATCH, WRAP))
        val restart = ctx.actionRow(R.drawable.ic_refresh, ctx.getString(R.string.diag_restart_helper), null) {
            if (!AdbController.wirelessDebuggingEnabled(host)) {
                host.openWirelessDebuggingSettings()
                host.toast(ctx.getString(R.string.access_enable_wifi_hint))
            } else {
                ActivationService.reconnect(host)
                host.toast(ctx.getString(R.string.diag_restart_sent))
            }
        }
        body.addView(restart, lp(MATCH, WRAP))
        if (com.hongkilldong.hotspottoggler.BuildConfig.DEBUG) {
            val testCode = ctx.actionRow(R.drawable.ic_link, ctx.getString(R.string.diag_test_code), null) {
                host.getSystemService(android.app.NotificationManager::class.java)
                    .notify(
                        com.hongkilldong.hotspottoggler.Notifications.ID_CODE,
                        com.hongkilldong.hotspottoggler.Notifications.pairingCode(host, null),
                    )
            }
            body.addView(testCode, lp(MATCH, WRAP))
            val testOverlay = ctx.actionRow(R.drawable.ic_link, ctx.getString(R.string.diag_test_overlay), null) {
                com.hongkilldong.hotspottoggler.activation.CodeOverlay.show(
                    host,
                    onSubmit = { value -> host.toast("code=$value") },
                    onCancel = {},
                )
            }
            body.addView(testOverlay, lp(MATCH, WRAP))
        }
        body.addView(logCard, lp(MATCH, WRAP))
        root.addView(scroll, lp(MATCH, 0, 1f))
    }

    override val view: View = root

    override fun onShow() {
        update()
    }

    override fun onBack(): Boolean {
        host.showHome()
        return true
    }

    override fun tick() {
        update()
    }

    private fun update() {
        val state = TogglerState.state.value
        val prefs = Prefs(host)
        val wireless = AdbController.wirelessDebuggingEnabled(host)
        val usb = AdbController.usbDebuggingEnabled(host)
        val checkKey = "${host.hasBluetoothPermission()}|${host.hasNotificationPermission()}|" +
            "${prefs.paired}|$wireless|$usb|${state.activation.helperReady}|${state.hotspot.on}"
        if (checkKey != lastCheckKey) {
            lastCheckKey = checkKey
            checks.removeAllViews()
            addCheck(ctx.getString(R.string.diag_check_bt), host.hasBluetoothPermission())
            addCheck(ctx.getString(R.string.diag_check_notifications), host.hasNotificationPermission())
            addCheck(ctx.getString(R.string.diag_check_activation), prefs.paired)
            addCheck(ctx.getString(R.string.diag_check_wireless), wireless || state.activation.helperReady, optional = true, failText = R.string.diag_off)
            addCheck(ctx.getString(R.string.diag_check_usb), usb, optional = !state.activation.helperReady, failText = R.string.diag_off)
            addCheck(ctx.getString(R.string.diag_check_helper), state.activation.helperReady, failText = R.string.diag_off)
            addCheck(ctx.getString(R.string.diag_check_hotspot), state.hotspot.on, optional = true, failText = R.string.diag_off)
        }

        val log = AppLog.tail(12000)
        if (log != lastLog) {
            lastLog = log
            logView.text = log.ifBlank { ctx.getString(R.string.diag_none) }
        }
    }

    private fun addCheck(label: String, ok: Boolean, optional: Boolean = false, failText: Int = R.string.diag_fail) {
        if (checks.childCount > 0) checks.addView(ctx.divider())
        val row = ctx.hbox(12).apply {
            minimumHeight = ctx.dp(46)
            pad(ctx, 16, 0, 16, 0)
        }
        row.addView(ctx.stageIcon(if (ok) StageState.DONE else StageState.ERROR), lp(ctx.dp(24), ctx.dp(24)))
        row.addView(ctx.text(label, 15f, 500), lp(0, WRAP, 1f))
        row.addView(
            ctx.text(
                ctx.getString(if (ok) R.string.diag_ok else failText),
                13f, 600,
                ctx.clr(if (ok) R.color.ok else if (optional) R.color.on2 else R.color.err),
            ),
        )
        checks.addView(row, lp(MATCH, WRAP))
    }

    private fun share() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Hotspot Toggler diagnostics")
            putExtra(Intent.EXTRA_TEXT, buildReport())
        }
        runCatching { host.startActivity(Intent.createChooser(intent, ctx.getString(R.string.diag_share))) }
    }

    private fun copy() {
        val clipboard = host.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Hotspot Toggler", buildReport()))
        host.toast(ctx.getString(R.string.diag_copy))
    }

    private fun buildReport(): String {
        val state = TogglerState.state.value
        return buildString {
            appendLine("Hotspot Toggler diagnostics")
            appendLine("serviceRunning=${state.serviceRunning} serviceEnabled=${state.serviceEnabled}")
            appendLine("selected=${state.selected}")
            appendLine("activation=${state.activation}")
            appendLine("wirelessDebugging=${AdbController.wirelessDebuggingEnabled(host)}")
            appendLine("usbDebugging=${AdbController.usbDebuggingEnabled(host)}")
            appendLine("hotspot=${state.hotspot}")
            appendLine("error=${state.error}")
            appendLine()
            append(AppLog.tail(24000))
        }
    }
}
