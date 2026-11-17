package com.hongkilldong.hotspottoggler.ui

import android.app.AlertDialog
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hongkilldong.hotspottoggler.AppTheme
import com.hongkilldong.hotspottoggler.BuildConfig
import com.hongkilldong.hotspottoggler.MainActivity
import com.hongkilldong.hotspottoggler.Prefs
import com.hongkilldong.hotspottoggler.R
import com.hongkilldong.hotspottoggler.TogglerState
import com.hongkilldong.hotspottoggler.activation.ActivationService
import com.hongkilldong.hotspottoggler.activation.AdbController

class SettingsScreen(host: MainActivity) : Screen(host) {
    private val root = ctx.vbox()
    private val body = ctx.vbox(12).apply { pad(ctx, 16, 8, 16, 8) }
    private val scroll = ScrollView(ctx).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
    }
    private var accessValue: TextView? = null

    init {
        root.addView(simpleHeader(ctx.getString(R.string.settings_title)), ctx.lpDp(MATCH, 64))
        root.addView(scroll, lp(MATCH, 0, 1f))
        render()
    }

    override val view: View get() = root

    override fun onShow() {
        render()
    }

    override fun onBack(): Boolean {
        host.showHome()
        return true
    }

    override fun tick() {
        val helperReady = TogglerState.state.value.activation.helperReady
        val paired = Prefs(host).paired
        val wireless = AdbController.wirelessDebuggingEnabled(host)
        val usb = AdbController.usbDebuggingEnabled(host)
        val value = when {
            helperReady && !usb -> ctx.getString(R.string.settings_status_usb_off)
            helperReady -> ctx.getString(R.string.access_ready)
            paired && !wireless -> ctx.getString(R.string.settings_status_wifi_needed)
            paired -> ctx.getString(R.string.settings_status_helper_stopped)
            else -> ctx.getString(R.string.access_no)
        }
        if (accessValue?.text?.toString() != value) accessValue?.text = value
    }

    private fun render() {
        val y = scroll.scrollY
        body.removeAllViews()
        val prefs = Prefs(host)

        val automation = ctx.card(20).apply { pad(ctx, 0, 4, 0, 4) }
        automation.addView(
            ToggleRow(
                ctx,
                ctx.getString(R.string.settings_auto_off),
                ctx.getString(R.string.settings_auto_off_sub),
                prefs.autoOffOnDisconnect,
            ) { prefs.autoOffOnDisconnect = it },
            lp(MATCH, WRAP),
        )
        automation.addView(ctx.divider())
        val delayBox = ctx.vbox(8).apply { pad(ctx, 16, 12, 16, 14) }
        delayBox.addView(ctx.text(ctx.getString(R.string.settings_delay), 15f, 500), lp(MATCH, WRAP))
        delayBox.addView(
            ctx.choiceChips(
                listOf("0", "15", "30", "60", "120").map { it to "$it ${ctx.getString(R.string.unit_seconds)}" },
                prefs.autoOffDelaySeconds.toString(),
            ) { value ->
                prefs.autoOffDelaySeconds = value.toIntOrNull() ?: 30
                render()
            },
            lp(MATCH, WRAP).margins(ctx, 0, 8, 0, 0),
        )
        automation.addView(delayBox, lp(MATCH, WRAP))
        automation.addView(ctx.divider())
        automation.addView(
            ToggleRow(
                ctx,
                ctx.getString(R.string.settings_boot),
                ctx.getString(R.string.settings_boot_sub),
                prefs.autoStartOnBoot,
            ) { prefs.autoStartOnBoot = it },
            lp(MATCH, WRAP),
        )
        body.addView(automation, lp(MATCH, WRAP))

        val theme = ctx.card(20).apply { pad(ctx, 8, 8, 8, 8) }
        val modes = listOf(
            AppTheme.SYSTEM to ctx.getString(R.string.settings_theme_system),
            AppTheme.LIGHT to ctx.getString(R.string.settings_theme_light),
            AppTheme.DARK to ctx.getString(R.string.settings_theme_dark),
        )
        val currentTheme = AppTheme.get(host)
        for ((mode, title) in modes) {
            val row = RadioRow(ctx, title, "") {
                if (AppTheme.get(host) != mode) {
                    AppTheme.set(host, mode)
                    host.recreateForTheme()
                }
            }
            row.setSelectedUi(currentTheme == mode)
            theme.addView(row, lp(MATCH, WRAP))
        }
        body.addView(theme, lp(MATCH, WRAP))

        val system = ctx.card(20).apply { pad(ctx, 0, 4, 0, 4) }
        system.addView(ctx.actionRow(R.drawable.ic_alert, ctx.getString(R.string.settings_notifications), ctx.getString(R.string.settings_notifications_sub)) {
            host.openNotificationSettings()
        }, lp(MATCH, WRAP).margins(ctx, 8, 6, 8, 6))
        system.addView(ctx.actionRow(R.drawable.ic_power, ctx.getString(R.string.settings_battery), ctx.getString(R.string.settings_battery_sub)) {
            host.openBatterySettings()
        }, lp(MATCH, WRAP).margins(ctx, 8, 0, 8, 6))
        body.addView(system, lp(MATCH, WRAP))

        val access = ctx.card(20).apply { pad(ctx, 0, 4, 0, 4) }
        accessValue = ctx.text("", 13f, 500, ctx.clr(R.color.on2)).apply { pad(ctx, 20, 0, 20, 6) }
        access.addView(ctx.actionRow(R.drawable.ic_shield, ctx.getString(R.string.access_title), null) { host.showAccess() }, lp(MATCH, WRAP).margins(ctx, 8, 6, 8, 0))
        access.addView(accessValue, lp(MATCH, WRAP))
        access.addView(ctx.actionRow(R.drawable.ic_refresh, ctx.getString(R.string.settings_restart_helper), ctx.getString(R.string.settings_restart_helper_sub)) {
            if (!AdbController.wirelessDebuggingEnabled(host)) {
                host.openWirelessDebuggingSettings()
                host.toast(ctx.getString(R.string.access_enable_wifi_hint))
            } else {
                ActivationService.reconnect(host)
                host.toast(ctx.getString(R.string.diag_restart_sent))
            }
        }, lp(MATCH, WRAP).margins(ctx, 8, 0, 8, 6))
        body.addView(access, lp(MATCH, WRAP))

        val diag = ctx.card(20).apply { pad(ctx, 8, 8, 8, 8) }
        diag.addView(ctx.actionRow(R.drawable.ic_speed, ctx.getString(R.string.settings_diagnostics), null) { host.showDiagnostics() }, lp(MATCH, WRAP))
        body.addView(diag, lp(MATCH, WRAP))

        val about = ctx.card(24).apply { pad(ctx, 20, 16, 20, 16) }
        about.addView(ctx.text(ctx.getString(R.string.app_name), 18f, 600), lp(MATCH, WRAP))
        about.addView(
            ctx.text(ctx.getString(R.string.settings_version, BuildConfig.VERSION_NAME), 13f, 400, ctx.clr(R.color.on2), monospace = true),
            lp(MATCH, WRAP).margins(ctx, 0, 4, 0, 0),
        )
        about.addView(
            ctx.text(ctx.getString(R.string.settings_about_text), 14f, 400, ctx.clr(R.color.on2)).apply { lineHeight = ctx.dp(20) },
            lp(MATCH, WRAP).margins(ctx, 0, 10, 0, 0),
        )
        val licenses = ctx.linkButton(ctx.getString(R.string.settings_licenses), 44, 14f)
        licenses.setOnClickListener { showLicenses() }
        about.addView(licenses, lp(MATCH, WRAP).margins(ctx, 0, 6, 0, 0))
        body.addView(about, lp(MATCH, WRAP))
        body.addView(View(ctx), ctx.lpDp(MATCH, 12))
        scroll.post { scroll.scrollTo(0, y) }
        tick()
    }

    private fun showLicenses() {
        val text = runCatching {
            listOf("OFL-GolosText.txt", "OFL-JetBrainsMono.txt").joinToString("\n\n") {
                host.assets.open("licenses/$it").bufferedReader().readText()
            }
        }.getOrDefault("OFL")
        AlertDialog.Builder(host)
            .setTitle(R.string.settings_licenses)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
