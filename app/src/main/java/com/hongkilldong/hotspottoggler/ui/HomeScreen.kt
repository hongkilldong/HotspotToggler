package com.hongkilldong.hotspottoggler.ui

import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hongkilldong.hotspottoggler.Fmt
import com.hongkilldong.hotspottoggler.HotspotRepository
import com.hongkilldong.hotspottoggler.MainActivity
import com.hongkilldong.hotspottoggler.Prefs
import com.hongkilldong.hotspottoggler.R
import com.hongkilldong.hotspottoggler.SelectedDevice
import com.hongkilldong.hotspottoggler.SpeedSample
import com.hongkilldong.hotspottoggler.TogglerState
import com.hongkilldong.hotspottoggler.UiState
import com.hongkilldong.hotspottoggler.bt.BtDevices
import com.hongkilldong.hotspottoggler.service.ServiceStarter
import com.hongkilldong.hotspottoggler.service.TogglerService

class HomeScreen(host: MainActivity) : Screen(host) {
    private val root = ctx.vbox()
    private val body = ctx.vbox(12).apply { pad(ctx, 16, 8, 16, 8) }
    private val scroll = ScrollView(ctx).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
    }
    private val footer = ctx.vbox(8).apply { pad(ctx, 16, 12, 16, 20) }
    private var lastKey: String? = null
    private var speedDown: TextView? = null
    private var speedUp: TextView? = null
    private var speedTotal: TextView? = null
    private var sparkline: SparklineView? = null
    private var btSection: View? = null
    private var deviceDialog: AlertDialog? = null

    init {
        val header = ctx.hbox().apply {
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            pad(ctx, 20, 8, 8, 0)
            minimumHeight = ctx.dp(64)
        }
        val logo = FrameLayout(ctx).apply {
            background = ctx.shape(ctx.clr(R.color.pri), 10)
            addView(ctx.icon(R.drawable.ic_hotspot, 22, ctx.clr(R.color.onpri)), FrameLayout.LayoutParams(ctx.dp(22), ctx.dp(22), Gravity.CENTER))
        }
        header.addView(logo, lp(ctx.dp(32), ctx.dp(32)))
        header.addView(
            ctx.text("Hotspot Toggler", 22f, 600).apply { letterSpacing = -0.01f },
            lp(WRAP, WRAP).margins(ctx, 10),
        )
        header.addView(View(ctx), lp(0, 1, 1f))
        header.addView(ctx.iconButton(R.drawable.ic_settings, ctx.clr(R.color.on2), ctx.getString(R.string.home_settings)).apply {
            setOnClickListener { host.showSettings() }
        })
        root.addView(header, ctx.lpDp(MATCH, 64))
        root.addView(scroll, lp(MATCH, 0, 1f))
        root.addView(footer, lp(MATCH, WRAP))
    }

    override val view: View get() = root

    override fun onShow() {
        lastKey = null
        render()
    }

    override fun onHide() {
        deviceDialog?.dismiss()
        deviceDialog = null
    }

    override fun tick() {
        val state = TogglerState.state.value
        val selected = state.selected
        if (selected != null && !state.serviceRunning) {
            val connected = BtDevices.isConnected(host, selected.mac)
            if (connected != selected.connected) {
                TogglerState.onSelected(selected.copy(connected = connected))
            }
        }
        val fresh = TogglerState.state.value
        val key = layoutKey(fresh)
        if (key != lastKey) render(fresh)
        updateSpeed(fresh.speed)
    }

    private fun render(state: UiState = TogglerState.state.value) {
        val y = scroll.scrollY
        body.removeAllViews()
        body.addView(hero(state), lp(MATCH, WRAP))
        body.addView(checklist(state), lp(MATCH, WRAP))
        val bt = bluetoothCard(state)
        btSection = bt
        body.addView(bt, lp(MATCH, WRAP))
        body.addView(hotspotCard(state), lp(MATCH, WRAP))
        body.addView(speedCard(state), lp(MATCH, WRAP))
        body.addView(clientsCard(state), lp(MATCH, WRAP))
        body.addView(View(ctx), ctx.lpDp(MATCH, 8))
        renderFooter(state)
        lastKey = layoutKey(state)
        updateSpeed(state.speed)
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun layoutKey(s: UiState): String = buildString {
        append(s.serviceRunning).append('|').append(s.serviceEnabled)
        append('|').append(s.selected)
        append('|').append(s.activation.helperReady).append('|').append(s.activation.paired)
        append('|').append(s.activation.busy).append('|').append(s.activation.error)
        append('|').append(s.hotspot.on).append('|').append(s.hotspot.starting)
        append('|').append(s.hotspot.ssid).append('|').append(s.hotspot.iface)
        append('|').append(s.hotspot.clients).append('|').append(s.hotspot.owned)
        append('|').append(s.hotspot.internet).append('|').append(s.error)
        append('|').append(s.btOn).append('|').append(host.hasBluetoothPermission()).append('|').append(host.hasNotificationPermission())
    }

    private enum class Tone { NEUTRAL, PRI, OK, WARN, ERR }

    private data class Hero(
        val tone: Tone,
        val icon: Int,
        val title: String,
        val sub: String,
        val click: (() -> Unit)? = null,
    )

    private fun hero(state: UiState): View {
        val hero = when {
            state.error != null -> Hero(
                Tone.ERR, R.drawable.ic_alert,
                ctx.getString(R.string.home_status_error_title),
                ctx.getString(R.string.home_status_error_sub, state.error),
            ) { host.showAccess() }
            !state.serviceRunning -> Hero(
                Tone.NEUTRAL, R.drawable.ic_power,
                ctx.getString(R.string.home_status_off_title),
                ctx.getString(R.string.home_status_off_sub),
            )
            state.selected == null -> Hero(
                Tone.PRI, R.drawable.ic_bluetooth,
                ctx.getString(R.string.home_status_no_device_title),
                ctx.getString(R.string.home_status_no_device_sub),
            ) { scrollToBt() }
            state.hotspot.starting -> Hero(
                Tone.WARN, R.drawable.ic_refresh,
                ctx.getString(R.string.home_status_starting_title),
                ctx.getString(R.string.home_status_starting_sub, state.selected.name),
            )
            state.hotspot.on -> Hero(
                Tone.OK, R.drawable.ic_hotspot,
                ctx.getString(R.string.home_status_on_title),
                if (state.hotspot.ssid.isNullOrBlank()) ctx.getString(R.string.home_status_on_no_ssid)
                else ctx.getString(R.string.home_status_on_sub, state.hotspot.ssid, state.hotspot.clients.size),
            )
            state.selected.connected -> Hero(
                Tone.PRI, R.drawable.ic_bluetooth,
                ctx.getString(R.string.home_status_connected_title),
                ctx.getString(R.string.home_status_connected_sub, state.selected.name),
            )
            else -> Hero(
                Tone.PRI, R.drawable.ic_bluetooth,
                ctx.getString(R.string.home_status_waiting_title),
                ctx.getString(R.string.home_status_waiting_sub, state.selected.name),
            )
        }
        val (bg, fg, onFg) = when (hero.tone) {
            Tone.NEUTRAL -> Triple(ctx.clr(R.color.surf), ctx.clr(R.color.surf2), ctx.clr(R.color.on2))
            Tone.PRI -> Triple(ctx.clr(R.color.pric), ctx.clr(R.color.pri), ctx.clr(R.color.onpri))
            Tone.OK -> Triple(ctx.clr(R.color.okc), ctx.clr(R.color.ok), ctx.clr(R.color.onok))
            Tone.WARN -> Triple(ctx.clr(R.color.warnc), ctx.clr(R.color.warn), ctx.clr(R.color.onwarnc))
            Tone.ERR -> Triple(ctx.clr(R.color.errc), ctx.clr(R.color.err), ctx.clr(R.color.onerr))
        }
        val card = ctx.card(28, bg).apply {
            pad(ctx, 20, 20, 20, 20)
        }
        val top = ctx.hbox(12)
        val circle = FrameLayout(ctx).apply {
            background = ctx.oval(fg)
            addView(ctx.icon(hero.icon, 24, onFg), FrameLayout.LayoutParams(ctx.dp(24), ctx.dp(24), Gravity.CENTER))
        }
        top.addView(circle, lp(ctx.dp(44), ctx.dp(44)))
        top.addView(
            ctx.text(hero.title, 20f, 600).apply {
                letterSpacing = -0.015f
                lineHeight = ctx.dp(26)
                ellipsizeEnd()
            },
            lp(0, WRAP, 1f),
        )
        card.addView(top, lp(MATCH, WRAP))
        card.addView(
            ctx.text(hero.sub, 16f, 400, ctx.clr(R.color.on)).apply { lineHeight = ctx.dp(22) },
            lp(MATCH, WRAP).margins(ctx, 0, 14, 0, 0),
        )
        hero.click?.let {
            card.isClickable = true
            card.isFocusable = true
            card.background = ctx.rippleBg(ctx.shape(bg, 28), 28)
            card.setOnClickListener { it() }
        }
        return card
    }

    private fun checklist(state: UiState): View {
        val card = ctx.card(24).apply { pad(ctx, 0, 6, 0, 6) }
        val bt = host.hasBluetoothPermission()
        val notif = host.hasNotificationPermission()
        val permsDone = bt && notif
        val accessDone = state.activation.helperReady
        val deviceDone = state.selected != null
        val serviceDone = state.serviceRunning

        fun add(label: String, stage: StageState, meta: String, click: (() -> Unit)?) {
            if (card.childCount > 0) card.addView(ctx.divider())
            card.addView(ctx.stageRow(label, stage, meta, click), lp(MATCH, WRAP))
        }
        add(
            ctx.getString(R.string.home_stage_permissions),
            if (permsDone) StageState.DONE else StageState.ACTIVE,
            if (permsDone) ctx.getString(R.string.stage_done) else ctx.getString(R.string.stage_todo),
        ) { host.requestCorePermissions() }
        add(
            ctx.getString(R.string.home_stage_access),
            when {
                accessDone -> StageState.DONE
                state.activation.error != null -> StageState.ERROR
                else -> StageState.ACTIVE
            },
            when {
                accessDone -> ctx.getString(R.string.stage_done)
                state.activation.error != null -> ctx.getString(R.string.stage_error)
                else -> ctx.getString(R.string.stage_todo)
            },
        ) { host.showAccess() }
        add(
            ctx.getString(R.string.home_stage_device),
            if (deviceDone) StageState.DONE else StageState.ACTIVE,
            when {
                !deviceDone -> ctx.getString(R.string.stage_todo)
                state.selected?.connected == true -> ctx.getString(R.string.home_bt_connected)
                else -> ctx.getString(R.string.home_bt_disconnected)
            },
        ) { scrollToBt() }
        add(
            ctx.getString(R.string.home_stage_service),
            if (serviceDone) StageState.DONE else StageState.TODO,
            if (serviceDone) ctx.getString(R.string.home_service_running) else ctx.getString(R.string.home_service_stopped),
        ) { if (serviceDone) host.stopTogglerService() else host.startTogglerService() }
        return card
    }

    private fun bluetoothCard(state: UiState): View {
        val card = ctx.card(24).apply { pad(ctx, 0, 6, 0, 6) }
        val head = ctx.hbox(10).apply {
            pad(ctx, 20, 12, 12, 4)
        }
        head.addView(ctx.text(ctx.getString(R.string.home_bt_title), 14f, 600, ctx.clr(R.color.acc)), lp(0, WRAP, 1f))
        if (state.selected?.connected == true) {
            head.addView(ctx.chip(ctx.getString(R.string.home_bt_connected), ctx.clr(R.color.onok), ctx.clr(R.color.ok)))
        }
        card.addView(head, lp(MATCH, WRAP))

        val selected = state.selected
        val row = ctx.hbox(12).apply {
            pad(ctx, 20, 8, 12, 10)
            minimumHeight = ctx.dp(66)
            isClickable = true
            isFocusable = true
            background = ctx.rippleBg(null, 18)
            setOnClickListener { showDeviceDialog() }
        }
        val circle = FrameLayout(ctx).apply {
            background = ctx.oval(if (selected == null) ctx.clr(R.color.surf2) else ctx.clr(R.color.pric))
            addView(
                ctx.icon(R.drawable.ic_bluetooth, 20, ctx.clr(if (selected == null) R.color.acc else R.color.onpric)),
                FrameLayout.LayoutParams(ctx.dp(20), ctx.dp(20), Gravity.CENTER),
            )
        }
        row.addView(circle, lp(ctx.dp(38), ctx.dp(38)))
        val col = ctx.vbox(2)
        col.addView(
            ctx.text(
                selected?.name ?: ctx.getString(R.string.home_bt_pick),
                17f, 600,
            ).apply { ellipsizeEnd() },
        )
        col.addView(
            ctx.text(
                if (selected == null) ctx.getString(R.string.home_bt_pick_sub)
                else Fmt.mac(selected.mac) + " · " + ctx.getString(
                    if (selected.connected) R.string.home_bt_connected else R.string.home_bt_disconnected,
                ) + " · " + ctx.getString(R.string.home_bt_change_sub),
                13f, 400, ctx.clr(R.color.on2), monospace = false,
            ).apply { maxLines = 2 },
        )
        row.addView(col, lp(0, WRAP, 1f))
        if (selected != null) {
            row.addView(ctx.iconButton(R.drawable.ic_forget, ctx.clr(R.color.err), ctx.getString(R.string.home_forget), 40).apply {
                setOnClickListener { forget(selected.mac) }
            })
        }
        row.addView(ctx.icon(R.drawable.ic_chevron, 20, ctx.clr(R.color.on2)))
        card.addView(row, lp(MATCH, WRAP))
        return card
    }

    private fun showDeviceDialog() {
        if (deviceDialog != null) return
        val devices = BtDevices.bonded(host)
        val selectedMac = TogglerState.state.value.selected?.mac
        val list = ctx.vbox(6).apply { pad(ctx, 8, 4, 8, 8) }
        if (devices.isEmpty()) {
            list.addView(
                ctx.text(ctx.getString(R.string.home_no_devices), 15f, 400, ctx.clr(R.color.on2)).apply {
                    pad(ctx, 12, 16, 12, 16)
                },
                lp(MATCH, WRAP),
            )
        } else {
            devices.forEach { device ->
                val selected = selectedMac.equals(device.address, ignoreCase = true)
                val connected = selected && TogglerState.state.value.selected?.connected == true
                val sub = buildString {
                    append(Fmt.mac(device.address))
                    append(" · ").append(Fmt.deviceType(device.type))
                    if (connected) append(" · ").append(ctx.getString(R.string.home_bt_connected))
                }
                val row = RadioRow(ctx, BtDevices.deviceName(device), sub) {
                    deviceDialog?.dismiss()
                    deviceDialog = null
                    select(device)
                }
                row.setSelectedUi(selected)
                list.addView(row, lp(MATCH, WRAP))
            }
        }
        val maxHeight = minOf(ctx.dp(420), (host.resources.displayMetrics.heightPixels * 0.55f).toInt())
        val scroll = ScrollView(ctx).apply {
            isFillViewport = false
            addView(list, FrameLayout.LayoutParams(MATCH, WRAP))
            layoutParams = FrameLayout.LayoutParams(MATCH, maxHeight)
        }
        val dialog = AlertDialog.Builder(host)
            .setTitle(R.string.home_bt_choose_title)
            .setView(scroll)
            .setNegativeButton(R.string.access_cancel, null)
            .create()
        dialog.setOnDismissListener { deviceDialog = null }
        deviceDialog = dialog
        dialog.show()
    }

    private fun hotspotCard(state: UiState): View {
        val card = ctx.card(24).apply { pad(ctx, 20, 14, 20, 14) }
        val head = ctx.hbox(10)
        head.addView(ctx.text(ctx.getString(R.string.home_hotspot_title), 14f, 600, ctx.clr(R.color.acc)), lp(0, WRAP, 1f))
        val chipText = when {
            state.hotspot.starting -> ctx.getString(R.string.home_hotspot_starting)
            state.hotspot.on -> ctx.getString(R.string.home_hotspot_on)
            else -> ctx.getString(R.string.home_hotspot_off)
        }
        val chipFg = when {
            state.hotspot.starting -> ctx.clr(R.color.warn)
            state.hotspot.on -> ctx.clr(R.color.onok)
            else -> ctx.clr(R.color.on2)
        }
        val chipBg = when {
            state.hotspot.starting -> ctx.clr(R.color.warnc)
            state.hotspot.on -> ctx.clr(R.color.ok)
            else -> ctx.clr(R.color.surf2)
        }
        head.addView(ctx.chip(chipText, chipFg, chipBg))
        card.addView(head, lp(MATCH, WRAP))
        card.addView(
            ctx.text(
                if (state.hotspot.ssid.isNullOrBlank()) "—" else state.hotspot.ssid,
                18f, 600,
            ).apply { pad(ctx, 0, 8, 0, 2); ellipsizeEnd() },
            lp(MATCH, WRAP),
        )
        card.addView(labelValue(ctx.getString(R.string.home_iface), state.hotspot.iface ?: "—", mono = true), lp(MATCH, WRAP))
        card.addView(labelValue(ctx.getString(R.string.home_clients), state.hotspot.clients.size.toString()), lp(MATCH, WRAP))
        card.addView(
            labelValue(
                ctx.getString(R.string.home_internet_label),
                when (state.hotspot.internet) {
                    true -> ctx.getString(R.string.home_internet_yes)
                    false -> ctx.getString(R.string.home_internet_no)
                    null -> "—"
                },
            ),
            lp(MATCH, WRAP),
        )
        if (state.hotspot.on) {
            card.addView(
                labelValue(
                    ctx.getString(R.string.home_owned_label),
                    if (state.hotspot.owned) ctx.getString(R.string.home_owned_app) else ctx.getString(R.string.home_owned_external),
                ),
                lp(MATCH, WRAP),
            )
        }
        card.addView(ctx.actionRow(R.drawable.ic_hotspot, ctx.getString(R.string.home_open_settings), null) { host.openHotspotSettings() }, lp(MATCH, WRAP).margins(ctx, 0, 8, 0, 0))
        if (!state.activation.helperReady && state.serviceRunning) {
            card.addView(
                ctx.text(ctx.getString(R.string.home_activation_needed), 13f, 500, ctx.clr(R.color.err)).apply {
                    pad(ctx, 0, 10, 0, 0)
                    isClickable = true
                    setOnClickListener { host.showAccess() }
                },
                lp(MATCH, WRAP),
            )
        }
        return card
    }

    private fun speedCard(state: UiState): View {
        val card = ctx.card(24).apply { pad(ctx, 20, 14, 20, 14) }
        card.addView(ctx.text(ctx.getString(R.string.home_speed_title), 14f, 600, ctx.clr(R.color.acc)), lp(MATCH, WRAP))
        if (!state.hotspot.on && !state.hotspot.starting) {
            card.addView(ctx.text(ctx.getString(R.string.home_speed_idle), 15f, 400, ctx.clr(R.color.on2)).apply { pad(ctx, 0, 12, 0, 4) }, lp(MATCH, WRAP))
            return card
        }
        val metrics = ctx.hbox(20).apply { pad(ctx, 0, 10, 0, 4) }
        val downCol = ctx.vbox(2)
        downCol.addView(ctx.text(ctx.getString(R.string.home_speed_down), 13f, 400, ctx.clr(R.color.on2)))
        speedDown = ctx.text("—", 20f, 600, ctx.clr(R.color.pri), monospace = true)
        downCol.addView(speedDown)
        val upCol = ctx.vbox(2)
        upCol.addView(ctx.text(ctx.getString(R.string.home_speed_up), 13f, 400, ctx.clr(R.color.on2)))
        speedUp = ctx.text("—", 20f, 600, ctx.clr(R.color.ok), monospace = true)
        upCol.addView(speedUp)
        metrics.addView(downCol, lp(0, WRAP, 1f))
        metrics.addView(upCol, lp(0, WRAP, 1f))
        card.addView(metrics, lp(MATCH, WRAP))
        sparkline = SparklineView(ctx).also { card.addView(it, ctx.lpDp(MATCH, 72).margins(ctx, 0, 8, 0, 4)) }
        speedTotal = ctx.text("", 12f, 400, ctx.clr(R.color.on2))
        card.addView(speedTotal, lp(MATCH, WRAP))
        return card
    }

    private fun clientsCard(state: UiState): View {
        val card = ctx.card(24).apply { pad(ctx, 20, 14, 20, 14) }
        val head = ctx.hbox(10)
        head.addView(ctx.text(ctx.getString(R.string.home_clients_title), 14f, 600, ctx.clr(R.color.acc)), lp(0, WRAP, 1f))
        head.addView(ctx.chip(state.hotspot.clients.size.toString(), ctx.clr(R.color.on2), ctx.clr(R.color.surf2)))
        card.addView(head, lp(MATCH, WRAP))
        if (state.hotspot.clients.isEmpty()) {
            card.addView(ctx.text(ctx.getString(R.string.home_clients_empty), 15f, 400, ctx.clr(R.color.on2)).apply { pad(ctx, 0, 10, 0, 2) }, lp(MATCH, WRAP))
            return card
        }
        for (client in state.hotspot.clients) {
            val row = ctx.hbox(12).apply { pad(ctx, 0, 10, 0, 2) }
            val circle = FrameLayout(ctx).apply {
                background = ctx.oval(ctx.clr(R.color.surf2))
                addView(ctx.icon(R.drawable.ic_devices, 18, ctx.clr(R.color.acc)), FrameLayout.LayoutParams(ctx.dp(18), ctx.dp(18), Gravity.CENTER))
            }
            row.addView(circle, lp(ctx.dp(34), ctx.dp(34)))
            val col = ctx.vbox(1)
            col.addView(ctx.text(client.name ?: ctx.getString(R.string.home_client_unknown), 15f, 500).apply { ellipsizeEnd() })
            val sub = if (client.ip.isNullOrBlank()) ctx.getString(R.string.home_client_no_ip, Fmt.mac(client.mac))
            else ctx.getString(R.string.home_client_ip, client.ip, Fmt.mac(client.mac))
            col.addView(ctx.text(sub, 12f, 400, ctx.clr(R.color.on2), monospace = false))
            row.addView(col, lp(0, WRAP, 1f))
            card.addView(row, lp(MATCH, WRAP))
        }
        return card
    }

    private fun renderFooter(state: UiState) {
        footer.removeAllViews()
        val running = state.serviceRunning
        val button = if (running) ctx.outlineButton(ctx.getString(R.string.home_service_stop), R.drawable.ic_stop)
        else ctx.primaryButton(ctx.getString(R.string.home_service_start), R.drawable.ic_play)
        button.setOnClickListener { if (running) host.stopTogglerService() else host.startTogglerService() }
        footer.addView(button)
    }

    private fun updateSpeed(speed: SpeedSample) {
        speedDown?.text = Fmt.rate(speed.downBps)
        speedUp?.text = Fmt.rate(speed.upBps)
        sparkline?.setData(speed.downHistory, speed.upHistory)
        speedTotal?.text = ctx.getString(R.string.home_speed_total, Fmt.bytes(speed.rxTotal + speed.txTotal))
    }

    private fun select(device: BluetoothDevice) {
        val prefs = Prefs(host)
        prefs.selectedMac = device.address
        prefs.selectedName = BtDevices.deviceName(device)
        TogglerState.onSelected(
            SelectedDevice(
                mac = device.address,
                name = BtDevices.deviceName(device),
                connected = BtDevices.isConnected(host, device.address),
            ),
        )
        TogglerState.setError(null)
        if (prefs.serviceEnabled) {
            if (TogglerState.state.value.serviceRunning) TogglerService.reload(host)
            else ServiceStarter.startIfAllowed(host)
        }
        render()
    }

    private fun forget(mac: String) {
        val name = BtDevices.bonded(host).firstOrNull { it.address.equals(mac, ignoreCase = true) }
            ?.let { BtDevices.deviceName(it) }
            ?: TogglerState.state.value.selected?.takeIf { it.mac.equals(mac, ignoreCase = true) }?.name
            ?: mac
        AlertDialog.Builder(host)
            .setTitle(R.string.home_forget_confirm_title)
            .setMessage(ctx.getString(R.string.home_forget_confirm_text, name))
            .setPositiveButton(R.string.home_forget_confirm_yes) { _, _ -> doForget(mac) }
            .setNegativeButton(R.string.access_cancel, null)
            .show()
    }

    private fun doForget(mac: String) {
        if (!TogglerState.state.value.activation.helperReady) {
            host.toast(ctx.getString(R.string.err_helper_unavailable))
            host.showAccess()
            return
        }
        host.runAsync {
            val ok = runCatching { HotspotRepository.forget(host, mac) }.getOrDefault(false)
            host.runOnUiThread {
                host.toast(ctx.getString(if (ok) R.string.home_forget_done else R.string.home_forget_failed))
                render()
            }
        }
    }

    private fun scrollToBt() {
        val target = btSection ?: return
        scroll.post { scroll.smoothScrollTo(0, target.top) }
    }
}
