package com.hongkilldong.hotspottoggler.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.flyfishxu.kadb.cert.KadbCert
import com.hongkilldong.hotspottoggler.ActivationUi
import com.hongkilldong.hotspottoggler.AppLog
import com.hongkilldong.hotspottoggler.HotspotRepository
import com.hongkilldong.hotspottoggler.MainActivity
import com.hongkilldong.hotspottoggler.Prefs
import com.hongkilldong.hotspottoggler.R
import com.hongkilldong.hotspottoggler.TogglerState
import com.hongkilldong.hotspottoggler.activation.ActivationService
import com.hongkilldong.hotspottoggler.activation.AdbController
import com.hongkilldong.hotspottoggler.activation.KeystoreFile
import com.hongkilldong.hotspottoggler.helper.HelperLauncher

class AccessScreen(host: MainActivity) : Screen(host) {
    private val root = ctx.vbox()
    private val body = ctx.vbox(12).apply { pad(ctx, 16, 8, 16, 8) }
    private val scroll = ScrollView(ctx).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
    }
    private val footer = ctx.vbox().apply { pad(ctx, 16, 12, 16, 20) }
    private val primary = ctx.primaryButton(ctx.getString(R.string.access_activate), R.drawable.ic_shield)
    private var wifiValue: TextView? = null
    private var wifiHint: TextView? = null
    private var usbValue: TextView? = null
    private var pairValue: TextView? = null
    private var helperValue: TextView? = null
    private var portValue: TextView? = null
    private var progressView: TextView? = null
    private var manualBox: LinearLayout? = null
    private var manualToggle: PillButton? = null
    private var codeButton: PillButton? = null
    private var pairPortField: EditText? = null
    private var connectPortField: EditText? = null
    private var codeField: EditText? = null
    private var codeDialog: AlertDialog? = null
    private var codeDialogDismissed = false
    private var manualOpen = false
    private var lastUiKey: String? = null
    private var overlayValue: TextView? = null
    private var overlayGrant: PillButton? = null

    init {
        root.addView(simpleHeader(ctx.getString(R.string.access_title)), ctx.lpDp(MATCH, 64))
        buildBody()
        root.addView(scroll, lp(MATCH, 0, 1f))
        primary.setOnClickListener { onPrimary() }
        footer.addView(primary)
        root.addView(footer, lp(MATCH, WRAP))
    }

    override val view: View get() = root

    override fun onShow() {
        update()
    }

    override fun onHide() {
        codeDialog?.dismiss()
        codeDialog = null
    }

    override fun onBack(): Boolean {
        host.showHome()
        return true
    }

    override fun tick() {
        update()
        val activation = TogglerState.state.value.activation
        if (activation.awaitingCode && !host.hasNotificationPermission() && !codeDialogDismissed && codeDialog == null) {
            showCodeDialog()
        }
        if (!activation.awaitingCode) {
            codeDialog?.dismiss()
            codeDialog = null
            codeDialogDismissed = false
        }
    }

    private fun buildBody() {
        val intro = ctx.card(24).apply { pad(ctx, 20, 18, 20, 18) }
        intro.addView(ctx.text(ctx.getString(R.string.access_intro_title), 20f, 600).apply { letterSpacing = -0.01f }, lp(MATCH, WRAP))
        intro.addView(
            ctx.text(ctx.getString(R.string.access_intro), 15f, 400, ctx.clr(R.color.on2)).apply { lineHeight = ctx.dp(21) },
            lp(MATCH, WRAP).margins(ctx, 0, 10, 0, 0),
        )
        body.addView(intro, lp(MATCH, WRAP))

        val steps = ctx.card(24).apply { pad(ctx, 20, 14, 20, 14) }
        steps.addView(ctx.text(ctx.getString(R.string.access_steps_title), 14f, 600, ctx.clr(R.color.acc)), lp(MATCH, WRAP))
        steps.addView(numberedList(listOf(
            ctx.getString(R.string.access_step1),
            ctx.getString(R.string.access_step2),
            ctx.getString(R.string.access_step3),
            ctx.getString(R.string.access_step4),
        )), lp(MATCH, WRAP).margins(ctx, 0, 10, 0, 0))
        body.addView(steps, lp(MATCH, WRAP))

        val status = ctx.card(24).apply { pad(ctx, 20, 14, 20, 14) }
        status.addView(ctx.text(ctx.getString(R.string.access_status_title), 14f, 600, ctx.clr(R.color.acc)), lp(MATCH, WRAP))
        wifiValue = addStatus(status, ctx.getString(R.string.access_wifi_debug))
        usbValue = addStatus(status, ctx.getString(R.string.access_usb_debug))
        pairValue = addStatus(status, ctx.getString(R.string.access_paired))
        helperValue = addStatus(status, ctx.getString(R.string.access_helper))
        portValue = addStatus(status, ctx.getString(R.string.access_port), mono = true)
        wifiHint = ctx.text(ctx.getString(R.string.access_wifi_after), 12f, 400, ctx.clr(R.color.ok)).apply {
            pad(ctx, 0, 2, 0, 2)
            visibility = View.GONE
        }
        status.addView(wifiHint, lp(MATCH, WRAP))
        progressView = ctx.text("", 14f, 500, ctx.clr(R.color.on2)).apply {
            pad(ctx, 0, 10, 0, 2)
            visibility = View.GONE
        }
        status.addView(progressView, lp(MATCH, WRAP))
        body.addView(status, lp(MATCH, WRAP))

        val overlay = ctx.card(20).apply { pad(ctx, 16, 12, 16, 12) }
        overlay.addView(ctx.text(ctx.getString(R.string.access_overlay), 15f, 500), lp(MATCH, WRAP))
        overlayValue = ctx.text("", 13f, 400, ctx.clr(R.color.on2)).apply {
            lineHeight = ctx.dp(18)
        }
        overlay.addView(overlayValue, lp(MATCH, WRAP).margins(ctx, 0, 3, 0, 6))
        overlayGrant = ctx.tonalButton(ctx.getString(R.string.access_overlay_grant), 44, 14f)
        overlayGrant?.setOnClickListener { openOverlaySettings() }
        overlay.addView(overlayGrant, ctx.lpDp(MATCH, 44))
        body.addView(overlay, lp(MATCH, WRAP))

        if (!host.hasNotificationPermission()) {
            val warn = ctx.card(20, ctx.clr(R.color.warnc)).apply { pad(ctx, 16, 14, 16, 14) }
            warn.addView(
                ctx.text(ctx.getString(R.string.access_need_notifications), 14f, 500, ctx.clr(R.color.onwarnc)).apply { lineHeight = ctx.dp(19) },
                lp(MATCH, WRAP),
            )
            val open = ctx.linkButton(ctx.getString(R.string.settings_notifications), 44, 14f)
            open.setOnClickListener { host.openNotificationSettings() }
            warn.addView(open, ctx.lpDp(MATCH, 44).margins(ctx, 0, 6, 0, 0))
            body.addView(warn, lp(MATCH, WRAP))
        }

        manualToggle = ctx.tonalButton(ctx.getString(R.string.access_manual), 52, 14f)
        manualToggle?.setOnClickListener {
            manualOpen = !manualOpen
            manualToggle?.label = ctx.getString(if (manualOpen) R.string.access_manual_hide else R.string.access_manual)
            manualBox?.visibility = if (manualOpen) View.VISIBLE else View.GONE
        }
        body.addView(manualToggle, ctx.lpDp(MATCH, 52))

        manualBox = ctx.vbox(8).apply {
            visibility = View.GONE
            addView(ctx.text(ctx.getString(R.string.access_manual_hint), 13f, 400, ctx.clr(R.color.on2)).apply { lineHeight = ctx.dp(18) }, lp(MATCH, WRAP))
            pairPortField = numberField(ctx.getString(R.string.access_pair_port))
            connectPortField = numberField(ctx.getString(R.string.access_connect_port))
            codeField = numberField(ctx.getString(R.string.access_code))
            addView(pairPortField, ctx.lpDp(MATCH, 52))
            addView(connectPortField, ctx.lpDp(MATCH, 52))
            addView(codeField, ctx.lpDp(MATCH, 52))
            val go = ctx.outlineButton(ctx.getString(R.string.access_go), 0, 54, 15f)
            go.setOnClickListener { manualGo() }
            addView(go, lp(MATCH, WRAP))
        }
        body.addView(manualBox, lp(MATCH, WRAP))

        codeButton = ctx.outlineButton(ctx.getString(R.string.access_enter_code), 0, 52, 15f)
        codeButton?.setOnClickListener { showCodeDialog() }
        codeButton?.visibility = View.GONE
        body.addView(codeButton, ctx.lpDp(MATCH, 52))

        val dev = ctx.tonalButton(ctx.getString(R.string.access_open_dev), 52, 14f)
        dev.setOnClickListener { host.openWirelessDebuggingSettings() }
        body.addView(dev, ctx.lpDp(MATCH, 52))

        val forget = ctx.linkButton(ctx.getString(R.string.access_forget_key), 48, 14f)
        forget.setOnClickListener { forgetKey() }
        body.addView(forget, ctx.lpDp(MATCH, 48))
        body.addView(View(ctx), ctx.lpDp(MATCH, 8))
    }

    private fun addStatus(card: LinearLayout, label: String, mono: Boolean = false): TextView {
        val row = ctx.hbox(12).apply {
            minimumHeight = ctx.dp(38)
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(ctx.text(label, 14f, 400, ctx.clr(R.color.on2)), lp(0, WRAP, 1f))
        val value = ctx.text("—", 14f, 600, ctx.clr(R.color.on), monospace = mono).apply {
            gravity = Gravity.END
            ellipsizeEnd()
        }
        row.addView(value, lp(0, WRAP, 1f))
        card.addView(row, lp(MATCH, WRAP))
        return value
    }

    private fun update() {
        val state = TogglerState.state.value
        val activation = state.activation
        val prefs = Prefs(host)
        val wireless = AdbController.wirelessDebuggingEnabled(host)
        val usb = AdbController.usbDebuggingEnabled(host)
        val overlayAllowed = Settings.canDrawOverlays(host)
        val key = "$activation|${prefs.paired}|${prefs.lastConnectPort}|$wireless|$usb|${prefs.helperVersion}|$overlayAllowed"
        if (key == lastUiKey) return
        lastUiKey = key
        overlayValue?.text =
            if (overlayAllowed) ctx.getString(R.string.access_overlay_granted)
            else ctx.getString(R.string.access_overlay_sub)
        overlayValue?.setTextColor(ctx.clr(if (overlayAllowed) R.color.ok else R.color.on2))
        overlayGrant?.visibility = if (overlayAllowed) View.GONE else View.VISIBLE
        wifiValue?.apply {
            text = ctx.getString(
                when {
                    wireless -> R.string.access_yes
                    activation.helperReady -> R.string.access_wifi_not_needed
                    else -> R.string.access_no
                },
            )
            setTextColor(ctx.clr(if (wireless || activation.helperReady) R.color.ok else R.color.err))
        }
        usbValue?.apply {
            text = ctx.getString(if (usb) R.string.access_yes else R.string.access_no)
            setTextColor(ctx.clr(if (usb) R.color.ok else R.color.err))
        }
        wifiHint?.apply {
            val hint = when {
                !activation.helperReady && !wireless -> R.string.access_wifi_stop_warning
                !activation.helperReady -> R.string.access_helper_start_hint
                !usb -> R.string.access_usb_off_warning
                wireless -> R.string.access_wifi_after
                else -> R.string.access_wifi_off_ok
            }
            text = ctx.getString(hint)
            setTextColor(
                ctx.clr(
                    when {
                        !activation.helperReady && !wireless -> R.color.err
                        !activation.helperReady -> R.color.on2
                        !usb -> R.color.err
                        wireless -> R.color.on2
                        else -> R.color.ok
                    },
                ),
            )
            visibility = View.VISIBLE
        }
        pairValue?.apply {
            val paired = activation.paired || prefs.paired
            text = ctx.getString(if (paired) R.string.access_yes else R.string.access_no)
            setTextColor(ctx.clr(if (paired) R.color.ok else R.color.on2))
        }
        helperValue?.apply {
            text = ctx.getString(if (activation.helperReady) R.string.access_yes else R.string.access_no)
            setTextColor(ctx.clr(if (activation.helperReady) R.color.ok else R.color.on2))
        }
        portValue?.text = (activation.port ?: prefs.lastConnectPort).takeIf { it > 0 }?.toString() ?: "—"

        val progress = progressView ?: return
        val (text, color) = when {
            activation.busy -> stepText(activation.step) to R.color.acc
            activation.error != null -> ctx.getString(R.string.access_error, activation.error) to R.color.err
            activation.helperReady -> ctx.getString(R.string.access_ready) to R.color.ok
            prefs.paired -> ctx.getString(R.string.access_helper_dead) to R.color.warn
            else -> "" to R.color.on2
        }
        progress.text = text
        progress.setTextColor(ctx.clr(color))
        progress.visibility = if (text.isBlank()) View.GONE else View.VISIBLE

        codeButton?.visibility = if (activation.awaitingCode) View.VISIBLE else View.GONE
        if (activation.busy) {
            primary.label = ctx.getString(R.string.access_cancel)
            primary.setIconVisible(false)
        } else if (activation.helperReady) {
            primary.label = ctx.getString(R.string.access_reconnect)
            primary.setIconVisible(false)
        } else if (prefs.paired) {
            primary.label = ctx.getString(R.string.access_start_helper)
            primary.setIconVisible(true)
        } else {
            primary.label = ctx.getString(R.string.access_activate)
            primary.setIconVisible(true)
        }
        primary.setBusy(false)
    }

    private fun stepText(step: ActivationUi.Step): String = when (step) {
        ActivationUi.Step.SEARCHING -> ctx.getString(R.string.access_busy_search)
        ActivationUi.Step.WAIT_PAIR -> ctx.getString(R.string.access_busy_pair_dialog)
        ActivationUi.Step.WAIT_CODE -> ctx.getString(R.string.access_busy_code)
        ActivationUi.Step.PAIRING -> ctx.getString(R.string.access_busy_pairing)
        ActivationUi.Step.CONNECTING -> ctx.getString(R.string.access_busy_connecting)
        ActivationUi.Step.STARTING -> ctx.getString(R.string.access_busy_starting)
        ActivationUi.Step.READY -> ctx.getString(R.string.access_ready)
        else -> ""
    }

    private fun onPrimary() {
        val activation = TogglerState.state.value.activation
        val prefs = Prefs(host)
        if (activation.busy) {
            ActivationService.cancel(host)
            return
        }
        if (!host.hasNotificationPermission()) host.requestCorePermissions()
        val wireless = AdbController.wirelessDebuggingEnabled(host)
        if (!wireless && prefs.paired) {
            host.openWirelessDebuggingSettings()
            host.toast(ctx.getString(R.string.access_enable_wifi_hint))
            return
        }
        if (activation.helperReady || prefs.paired) {
            ActivationService.reconnect(host)
        } else {
            ActivationService.start(host)
            // Pairing needs the system dialog: take the user to Wireless debugging immediately.
            host.openWirelessDebuggingSettings()
        }
    }

    private fun manualGo() {
        val pairPort = pairPortField?.text?.toString()?.toIntOrNull() ?: 0
        val connectPort = connectPortField?.text?.toString()?.toIntOrNull() ?: 0
        val code = codeField?.text?.toString()?.trim().orEmpty()
        val prefs = Prefs(host)
        if (!prefs.paired && (pairPort !in 1..65535 || code.length != 6)) {
            host.toast(ctx.getString(R.string.access_code_wrong))
            return
        }
        ActivationService.start(host, pairPort, connectPort, code.takeIf { it.length == 6 })
    }

    private fun showCodeDialog() {
        if (codeDialog != null) return
        codeDialogDismissed = false
        val input = EditText(host).apply {
            hint = ctx.getString(R.string.access_code)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(ctx.clr(R.color.on))
            setHintTextColor(ctx.clr(R.color.on2))
            background = ctx.shape(ctx.clr(R.color.surf2), 14)
            pad(ctx, 16, 12, 16, 12)
            inputType = InputType.TYPE_CLASS_NUMBER
            typeface = ctx.mono()
            gravity = Gravity.CENTER
        }
        val dialog = AlertDialog.Builder(host)
            .setTitle(R.string.access_enter_code)
            .setView(input)
            .setPositiveButton(R.string.notif_code_reply) { _, _ ->
                codeDialogDismissed = true
                val code = input.text.toString().trim()
                if (code.length == 6) ActivationService.sendCode(host, code)
                else host.toast(ctx.getString(R.string.access_code_wrong))
            }
            .setNegativeButton(R.string.access_cancel) { _, _ ->
                codeDialogDismissed = true
            }
            .create()
        dialog.setOnDismissListener { codeDialog = null }
        codeDialog = dialog
        dialog.show()
    }

    private fun openOverlaySettings() {
        runCatching {
            host.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    .setData(Uri.parse("package:${host.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun forgetKey() {
        host.runAsync {
            HelperLauncher.stop(host)
            runCatching { KadbCert.clear() }
            runCatching { KeystoreFile(host).clear() }
            val prefs = Prefs(host)
            prefs.paired = false
            prefs.lastConnectPort = 0
            prefs.clearHelperIdentity()
            TogglerState.onActivation {
                it.copy(paired = false, helperReady = false, busy = false, awaitingCode = false, port = null, error = null, step = ActivationUi.Step.IDLE)
            }
            AppLog.i("ADB key forgotten")
            host.runOnUiThread {
                host.toast(ctx.getString(R.string.access_paired_forgotten))
                update()
            }
        }
    }

    private fun numberField(hint: String): EditText = EditText(host).apply {
        this.hint = hint
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(ctx.clr(R.color.on))
        setHintTextColor(ctx.clr(R.color.on2))
        background = ctx.shape(ctx.clr(R.color.surf2), 14)
        pad(ctx, 16, 0, 16, 0)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_NUMBER
        typeface = ctx.sans()
    }
}
