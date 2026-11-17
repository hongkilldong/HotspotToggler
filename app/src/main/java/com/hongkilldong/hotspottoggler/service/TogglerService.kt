package com.hongkilldong.hotspottoggler.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.hongkilldong.hotspottoggler.AppLog
import com.hongkilldong.hotspottoggler.HotspotRepository
import com.hongkilldong.hotspottoggler.Notifications
import com.hongkilldong.hotspottoggler.Prefs
import com.hongkilldong.hotspottoggler.R
import com.hongkilldong.hotspottoggler.SelectedDevice
import com.hongkilldong.hotspottoggler.TogglerState
import com.hongkilldong.hotspottoggler.bt.BluetoothMonitor
import com.hongkilldong.hotspottoggler.bt.BtDevices
import com.hongkilldong.hotspottoggler.activation.AdbController
import com.hongkilldong.hotspottoggler.helper.HelperClient
import com.hongkilldong.hotspottoggler.helper.HelperException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TogglerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitor: BluetoothMonitor? = null
    private var pollJob: Job? = null
    private var startJob: Job? = null
    private var stopJob: Job? = null
    private var foreground = false
    private var lastNotification: String? = null
    private var connectionHandled = false
    private var lastHotspotOn = false
    private var emptyPolls = 0
    private var refreshInFlight = false
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        HotspotRepository.attach(this)
        Notifications.createChannels(this)
        monitor = BluetoothMonitor(
            this,
            onAcl = ::onAcl,
            onBondLost = ::onBondLost,
            onAdapter = ::onAdapter,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopEverything()
            ACTION_START_NOW -> {
                AppLog.i("START_NOW received")
                startEverything()
                val mac = TogglerState.state.value.selected?.mac
                AppLog.i("START_NOW selected=$mac")
                if (mac != null) scope.launch {
                    delay(500)
                    startHotspot(mac)
                }
            }
            ACTION_STOP_NOW -> scope.launch {
                runCatching { HotspotRepository.stop(this@TogglerService) }
                updateNotification()
            }
            ACTION_RELOAD -> reloadSelection()
            else -> startEverything()
        }
        return START_STICKY
    }

    private fun startEverything() {
        if (started) {
            updateNotification()
            return
        }
        started = true
        val prefs = Prefs(this)
        prefs.serviceEnabled = true
        TogglerState.onService(true, true)
        TogglerState.onActivation {
            it.copy(paired = prefs.paired, wirelessDebug = null, port = prefs.lastConnectPort.takeIf { p -> p > 0 })
        }
        startForegroundSafe()
        mon().start()
        TogglerState.onBluetooth(mon().adapterOn(), false)
        val selected = HotspotRepository.selected(this) ?: SelectedDevice(
            mac = prefs.selectedMac ?: "",
            name = prefs.selectedName ?: "",
            connected = false,
        ).takeIf { it.mac.isNotBlank() }
        if (selected == null) {
            TogglerState.onSelected(null)
            TogglerState.setError(null)
        } else {
            val connected = mon().isConnected(selected.mac)
            TogglerState.onSelected(selected.copy(connected = connected))
            TogglerState.onBluetooth(mon().adapterOn(), connected)
            connectionHandled = false
            if (connected) scheduleStart(selected.mac)
            else scheduleInitialRecheck(selected.mac)
        }
        if (pollJob?.isActive != true) {
            pollJob = scope.launch { pollLoop() }
        }
        AppLog.i("service started")
    }

    private fun reloadSelection() {
        val prefs = Prefs(this)
        val selected = HotspotRepository.selected(this) ?: SelectedDevice(
            mac = prefs.selectedMac ?: "",
            name = prefs.selectedName ?: "",
            connected = false,
        ).takeIf { it.mac.isNotBlank() }
        if (selected == null) {
            TogglerState.onSelected(null)
            connectionHandled = true
            return
        }
        val connected = mon().isConnected(selected.mac)
        TogglerState.onSelected(selected.copy(connected = connected))
        TogglerState.onBluetooth(mon().adapterOn(), connected)
        startJob?.cancel()
        stopJob?.cancel()
        connectionHandled = false
        if (connected) scheduleStart(selected.mac) else scheduleInitialRecheck(selected.mac)
        updateNotification()
    }

    private fun stopEverything() {
        val prefs = Prefs(this)
        prefs.serviceEnabled = false
        TogglerState.onService(false, false)
        startJob?.cancel()
        stopJob?.cancel()
        pollJob?.cancel()
        pollJob = null
        scope.launch {
            if (prefs.hotspotOwnedByApp) {
                runCatching { HotspotRepository.stop(this@TogglerService) }
                    .onFailure { AppLog.e("cannot stop hotspot on service stop", it) }
            }
            finishStop()
        }
    }

    private fun finishStop() {
        started = false
        TogglerState.onService(false, false)
        monitor?.stop()
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelf()
        AppLog.i("service stopped")
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            if (!refreshInFlight) {
                refreshInFlight = true
                val snapshot = runCatching { HotspotRepository.refresh(this, withSpeed = true) }.getOrNull()
                refreshInFlight = false
                if (snapshot == null) {
                    emptyPolls++
                    if (emptyPolls == 1 || emptyPolls % 10 == 0) {
                        val ok = runCatching { HotspotRepository.ensureHelper(this) }.getOrDefault(false)
                        if (!ok) AppLog.i("helper is not available; waiting for wireless debugging")
                    }
                } else {
                    val recovered = emptyPolls > 0
                    emptyPolls = 0
                    val prefs = Prefs(this)
                    if (recovered) {
                        // Helper is back: allow the connected trigger device to start the hotspot again.
                        connectionHandled = false
                        TogglerState.setError(null)
                    }
                    if (lastHotspotOn && !snapshot.on && !snapshot.starting) {
                        // The hotspot was turned off outside the app; allow a fresh start.
                        connectionHandled = false
                    }
                    lastHotspotOn = snapshot.on
                    if (!snapshot.on && !snapshot.starting && prefs.hotspotOwnedByApp) {
                        // The hotspot was switched off outside the app (system UI or another app).
                        prefs.hotspotOwnedByApp = false
                    }
                    val selected = TogglerState.state.value.selected
                    if (selected != null && selected.connected && !connectionHandled &&
                        !snapshot.on && !snapshot.starting
                    ) {
                        connectionHandled = true
                        scheduleStart(selected.mac)
                    }
                    if (snapshot.on && prefs.hotspotOwnedByApp && !selectedConnected(selected)) {
                        // The device disconnected while the poll was in flight; the ACL callback normally handles this.
                        if (prefs.autoOffOnDisconnect) scheduleStop(selected?.mac)
                    }
                }
                updateNotification()
            }
            delay(if (TogglerState.state.value.hotspot.on) 1000 else 2000)
        }
    }

    private fun selectedConnected(selected: SelectedDevice?): Boolean =
        selected != null && mon().isConnected(selected.mac)

    private fun onAcl(device: android.bluetooth.BluetoothDevice, connected: Boolean) {
        val mac = device.address
        val prefs = Prefs(this)
        if (!prefs.selectedMac.equals(mac, ignoreCase = true)) return
        val selected = TogglerState.state.value.selected
            ?: SelectedDevice(mac, prefs.selectedName ?: mac, connected)
        TogglerState.onBluetooth(mon().adapterOn(), connected)
        TogglerState.onSelected(selected.copy(connected = connected))
        if (connected) {
            stopJob?.cancel()
            stopJob = null
            connectionHandled = false
            TogglerState.setError(null)
            scheduleStart(mac)
        } else {
            startJob?.cancel()
            startJob = null
            if (Prefs(this).autoOffOnDisconnect) scheduleStop(mac)
            updateNotification()
        }
    }

    private fun onBondLost(mac: String) {
        val prefs = Prefs(this)
        if (!prefs.selectedMac.equals(mac, ignoreCase = true)) return
        AppLog.i("selected device was forgotten: $mac")
        prefs.selectedMac = null
        prefs.selectedName = null
        TogglerState.onSelected(null)
        TogglerState.setError(getString(R.string.home_forget_done))
        startJob?.cancel()
        startJob = null
        if (prefs.hotspotOwnedByApp) scope.launch { runCatching { HotspotRepository.stop(this@TogglerService) } }
        updateNotification()
    }

    private fun onAdapter(on: Boolean) {
        TogglerState.onBluetooth(on, false)
        TogglerState.setError(if (on) null else getString(R.string.err_bluetooth_off))
        if (!on) {
            startJob?.cancel()
            startJob = null
            val prefs = Prefs(this)
            if (prefs.hotspotOwnedByApp && prefs.autoOffOnDisconnect) scheduleStop(TogglerState.state.value.selected?.mac)
        } else {
            val selected = TogglerState.state.value.selected
            if (selected != null) {
                if (mon().isConnected(selected.mac) && !connectionHandled) {
                    connectionHandled = false
                    scheduleStart(selected.mac)
                } else {
                    scheduleInitialRecheck(selected.mac)
                }
            }
        }
        updateNotification()
    }

    private fun scheduleStart(mac: String) {
        if (!Prefs(this).serviceEnabled) return
        if (startJob?.isActive == true) return
        startJob = scope.launch {
            delay(1200)
            AppLog.i("scheduleStart -> startHotspot mac=$mac")
            startHotspot(mac)
        }
    }

    /** Profile proxies bind asynchronously; re-check for a short while after service start. */
    private fun scheduleInitialRecheck(mac: String) {
        scope.launch {
            repeat(7) { attempt ->
                delay(if (attempt == 0) 1500L else 2000L)
                if (!Prefs(this@TogglerService).serviceEnabled) return@launch
                val state = TogglerState.state.value
                if (connectionHandled || state.hotspot.on || state.hotspot.starting) return@launch
                val current = state.selected ?: return@launch
                if (!current.mac.equals(mac, ignoreCase = true)) return@launch
                if (!mon().isConnected(mac)) return@repeat
                TogglerState.onBluetooth(mon().adapterOn(), true)
                TogglerState.onSelected(current.copy(connected = true))
                scheduleStart(mac)
                return@launch
            }
        }
    }

    private suspend fun startHotspot(mac: String) {
        AppLog.i("startHotspot begin mac=$mac")
        val prefs = Prefs(this)
        if (!prefs.serviceEnabled) return
        val selected = TogglerState.state.value.selected
            ?: SelectedDevice(mac, prefs.selectedName ?: mac, false)
        if (!selected.mac.equals(mac, ignoreCase = true)) return
        if (prefs.hotspotOwnedByApp) {
            if (TogglerState.state.value.hotspot.on || TogglerState.state.value.hotspot.starting) return
            prefs.hotspotOwnedByApp = false
        }
        TogglerState.setError(null)
        TogglerState.onStarting(true)
        updateNotification()
        val helper = runCatching { HotspotRepository.ensureHelper(this) }.getOrDefault(false)
        AppLog.i("startHotspot helper=$helper")
        if (!helper) {
            TogglerState.onStarting(false)
            connectionHandled = true
            TogglerState.setError(
                when {
                    !prefs.paired -> getString(R.string.err_activation_required)
                    !AdbController.wirelessDebuggingEnabled(this) -> getString(R.string.err_wifi_needed)
                    else -> getString(R.string.err_helper_stopped)
                },
            )
            updateNotification()
            return
        }
        try {
            AppLog.i("startHotspot calling helper start")
            HotspotRepository.start(this)
            connectionHandled = true
            TogglerState.setError(null)
            AppLog.i("hotspot started by Bluetooth trigger")
        } catch (e: HelperException) {
            connectionHandled = true
            val text = when (e.code) {
                "carrier", "entitlement", "provisioning" -> getString(R.string.err_carrier)
                "no_upstream" -> getString(R.string.err_no_upstream)
                else -> getString(R.string.err_hotspot_failed)
            }
            TogglerState.setError(text)
            AppLog.e("hotspot start failed: ${e.code} ${e.message}")
        } catch (e: Throwable) {
            connectionHandled = true
            TogglerState.setError(getString(R.string.err_hotspot_failed))
            AppLog.e("hotspot start failed", e)
        } finally {
            TogglerState.onStarting(false)
            updateNotification()
        }
    }

    private fun scheduleStop(mac: String?) {
        if (!Prefs(this).serviceEnabled) return
        if (stopJob?.isActive == true) return
        val delaySec = Prefs(this).autoOffDelaySeconds.coerceAtLeast(0)
        stopJob = scope.launch {
            delay(delaySec * 1000L)
            val selected = TogglerState.state.value.selected
            if (selected != null && mon().isConnected(selected.mac)) return@launch
            val prefs = Prefs(this@TogglerService)
            if (!prefs.hotspotOwnedByApp) return@launch
            TogglerState.onStarting(true)
            updateNotification()
            runCatching { HotspotRepository.stop(this@TogglerService) }
                .onSuccess {
                    TogglerState.setError(null)
                    AppLog.i("hotspot stopped after disconnect delay")
                }
                .onFailure {
                    TogglerState.setError(getString(R.string.err_hotspot_failed))
                    AppLog.e("hotspot stop failed", it)
                }
            TogglerState.onStarting(false)
            updateNotification()
        }
    }

    private fun mon(): BluetoothMonitor = monitor ?: BluetoothMonitor(
        this,
        onAcl = ::onAcl,
        onBondLost = ::onBondLost,
        onAdapter = ::onAdapter,
    ).also { monitor = it; it.start() }

    private fun startForegroundSafe() {
        if (foreground) return
        val notification = Notifications.service(this, TogglerState.state.value)
        startForeground(Notifications.ID_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        foreground = true
        lastNotification = notificationKey(TogglerState.state.value)
    }

    private fun updateNotification() {
        if (!foreground) return
        val state = TogglerState.state.value
        val key = notificationKey(state)
        if (key == lastNotification) return
        lastNotification = key
        getSystemService(android.app.NotificationManager::class.java)
            .notify(Notifications.ID_SERVICE, Notifications.service(this, state))
    }

    private fun notificationKey(state: com.hongkilldong.hotspottoggler.UiState): String = buildString {
        append(state.error)
        append('|').append(state.hotspot.on)
        append('|').append(state.hotspot.starting)
        append('|').append(state.hotspot.clients.size)
        append('|').append(state.selected?.name)
        append('|').append(state.selected?.connected)
    }

    override fun onDestroy() {
        started = false
        monitor?.stop()
        monitor = null
        scope.cancel()
        TogglerState.onService(false, Prefs(this).serviceEnabled)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.hongkilldong.hotspottoggler.action.START_SERVICE"
        const val ACTION_STOP = "com.hongkilldong.hotspottoggler.action.STOP_SERVICE"
        const val ACTION_START_NOW = "com.hongkilldong.hotspottoggler.action.START_NOW"
        const val ACTION_STOP_NOW = "com.hongkilldong.hotspottoggler.action.STOP_NOW"
        const val ACTION_RELOAD = "com.hongkilldong.hotspottoggler.action.RELOAD"

        fun reload(context: Context) {
            context.startService(Intent(context, TogglerService::class.java).setAction(ACTION_RELOAD))
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TogglerService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TogglerService::class.java).setAction(ACTION_STOP))
        }
    }
}
