package com.hongkilldong.hotspottoggler

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SelectedDevice(
    val mac: String,
    val name: String,
    val connected: Boolean = false,
)

data class HotspotClient(
    val mac: String,
    val ip: String? = null,
    val name: String? = null,
)

enum class HotspotSource { NONE, PUBLIC, HELPER }

data class HotspotStatus(
    val on: Boolean = false,
    val starting: Boolean = false,
    val ssid: String? = null,
    val iface: String? = null,
    val clients: List<HotspotClient> = emptyList(),
    val owned: Boolean = false,
    val internet: Boolean? = null,
    val rxTotal: Long = 0,
    val txTotal: Long = 0,
    val source: HotspotSource = HotspotSource.NONE,
)

data class SpeedSample(
    val downBps: Double = 0.0,
    val upBps: Double = 0.0,
    val downHistory: List<Double> = emptyList(),
    val upHistory: List<Double> = emptyList(),
    val rxTotal: Long = 0,
    val txTotal: Long = 0,
)

data class ActivationUi(
    val paired: Boolean = false,
    val helperReady: Boolean = false,
    val busy: Boolean = false,
    val awaitingCode: Boolean = false,
    val step: Step = Step.IDLE,
    val error: String? = null,
    val wirelessDebug: Boolean? = null,
    val port: Int? = null,
) {
    enum class Step { IDLE, SEARCHING, WAIT_PAIR, WAIT_CODE, PAIRING, CONNECTING, STARTING, READY, FAILED }
}

data class HelperSnapshot(
    val on: Boolean = false,
    val starting: Boolean = false,
    val already: Boolean = false,
    val state: Int = 0,
    val ssid: String? = null,
    val iface: String? = null,
    val clients: List<HotspotClient> = emptyList(),
    val rxTotal: Long = 0,
    val txTotal: Long = 0,
    val uid: Int = -1,
    val pid: Int = -1,
    val version: Int = 0,
    val error: String? = null,
)

data class UiState(
    val serviceRunning: Boolean = false,
    val serviceEnabled: Boolean = false,
    val selected: SelectedDevice? = null,
    val btOn: Boolean = false,
    val activation: ActivationUi = ActivationUi(),
    val hotspot: HotspotStatus = HotspotStatus(),
    val speed: SpeedSample = SpeedSample(),
    val error: String? = null,
    val helperRaw: String? = null,
)

object TogglerState {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun onService(running: Boolean, enabled: Boolean) = _state.update {
        it.copy(serviceRunning = running, serviceEnabled = enabled)
    }

    fun onSelected(selected: SelectedDevice?) = _state.update {
        it.copy(selected = selected)
    }

    fun onBluetooth(adapterOn: Boolean, connected: Boolean) = _state.update {
        it.copy(
            btOn = adapterOn,
            selected = it.selected?.copy(connected = connected),
        )
    }

    fun onActivation(block: (ActivationUi) -> ActivationUi) = _state.update {
        it.copy(activation = block(it.activation))
    }

    fun onActivationReady(helperReady: Boolean, paired: Boolean, port: Int?) = _state.update {
        it.copy(
            activation = it.activation.copy(
                helperReady = helperReady,
                paired = paired,
                busy = false,
                awaitingCode = false,
                port = port,
                step = if (helperReady) ActivationUi.Step.READY else it.activation.step,
                error = if (helperReady) null else it.activation.error,
            ),
        )
    }

    fun onPublicHotspot(on: Boolean, starting: Boolean = false, ssid: String?, iface: String?) = _state.update {
        val current = it.hotspot
        val fromHelper = current.source == HotspotSource.HELPER
        it.copy(
            hotspot = current.copy(
                on = if (fromHelper) current.on else on,
                starting = if (fromHelper) current.starting else starting,
                ssid = if (fromHelper) current.ssid else ssid,
                iface = if (fromHelper) current.iface else iface,
                clients = if (fromHelper) current.clients else emptyList(),
                source = if (fromHelper) current.source else HotspotSource.PUBLIC,
                internet = if (on) current.internet else false,
            ),
        )
    }

    fun onHelper(snapshot: HelperSnapshot, owned: Boolean) = _state.update {
        it.copy(
            hotspot = HotspotStatus(
                on = snapshot.on,
                starting = snapshot.starting,
                ssid = snapshot.ssid,
                iface = snapshot.iface,
                clients = snapshot.clients,
                owned = owned,
                internet = it.hotspot.internet,
                rxTotal = snapshot.rxTotal,
                txTotal = snapshot.txTotal,
                source = HotspotSource.HELPER,
            ),
            activation = it.activation.copy(helperReady = true, paired = true),
            helperRaw = snapshot.error,
        )
    }

    fun onHelperUnavailable(error: String? = null) = _state.update {
        it.copy(
            activation = it.activation.copy(helperReady = false),
            hotspot = if (it.hotspot.source == HotspotSource.HELPER)
                it.hotspot.copy(source = HotspotSource.PUBLIC, clients = emptyList(), starting = false)
            else it.hotspot,
            error = error ?: it.error,
        )
    }

    fun onStarting(starting: Boolean) = _state.update {
        it.copy(hotspot = it.hotspot.copy(starting = starting))
    }

    fun onSpeed(speed: SpeedSample) = _state.update { it.copy(speed = speed) }

    fun onInternet(available: Boolean?) = _state.update {
        it.copy(hotspot = it.hotspot.copy(internet = available))
    }

    fun setError(message: String?) = _state.update { it.copy(error = message) }

    fun onHelperRaw(raw: String?) = _state.update { it.copy(helperRaw = raw) }
}
