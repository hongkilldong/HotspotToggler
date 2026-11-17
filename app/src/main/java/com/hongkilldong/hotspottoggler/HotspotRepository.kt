package com.hongkilldong.hotspottoggler

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.hongkilldong.hotspottoggler.activation.AdbController
import com.hongkilldong.hotspottoggler.bt.BtDevices
import com.hongkilldong.hotspottoggler.helper.HelperClient
import com.hongkilldong.hotspottoggler.helper.HelperException
import com.hongkilldong.hotspottoggler.helper.HelperLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object HotspotRepository {
    private const val MAX_HISTORY = 60
    private var lastAt = 0L
    private var lastRx = 0L
    private var lastTx = 0L
    private val downHistory = ArrayDeque<Double>()
    private val upHistory = ArrayDeque<Double>()

    fun attach(context: Context) {
        HelperClient.attach(context)
    }

    suspend fun refresh(context: Context, withSpeed: Boolean = true): HelperSnapshot? = withContext(Dispatchers.IO) {
        val prefs = Prefs(context)
        val snapshot = runCatching { HelperClient.status() }.getOrNull()
        if (snapshot == null) {
            TogglerState.onHelperUnavailable()
            return@withContext null
        }
        TogglerState.onHelper(snapshot, prefs.hotspotOwnedByApp)
        TogglerState.onInternet(internetAvailable(context))
        if (withSpeed) updateSpeed(snapshot)
        snapshot
    }

    suspend fun start(context: Context): HelperSnapshot = withContext(Dispatchers.IO) {
        val prefs = Prefs(context)
        prefs.pendingHotspotStart = true
        try {
            val snapshot = HelperClient.startHotspot()
            if (!snapshot.on) throw HelperException("hotspot_failed", snapshot.error ?: "hotspot did not start")
            prefs.hotspotOwnedByApp = !snapshot.already
            prefs.pendingHotspotStart = false
            TogglerState.onHelper(snapshot, prefs.hotspotOwnedByApp)
            TogglerState.onInternet(internetAvailable(context))
            updateSpeed(snapshot)
            snapshot
        } catch (e: Throwable) {
            prefs.pendingHotspotStart = false
            throw e
        }
    }

    suspend fun stop(context: Context): HelperSnapshot? = withContext(Dispatchers.IO) {
        val prefs = Prefs(context)
        if (!prefs.hotspotOwnedByApp) return@withContext null
        val snapshot = HelperClient.stopHotspot()
        prefs.hotspotOwnedByApp = false
        prefs.pendingHotspotStart = false
        TogglerState.onHelper(snapshot, false)
        updateSpeed(snapshot)
        snapshot
    }

    suspend fun forget(context: Context, mac: String): Boolean = withContext(Dispatchers.IO) {
        val ok = HelperClient.forget(mac)
        if (ok) {
            val prefs = Prefs(context)
            if (prefs.selectedMac.equals(mac, ignoreCase = true)) {
                prefs.selectedMac = null
                prefs.selectedName = null
                TogglerState.onSelected(null)
            }
        }
        ok
    }

    suspend fun ensureHelper(context: Context): Boolean = withContext(Dispatchers.IO) {
        val prefs = Prefs(context)
        val wireless = AdbController.wirelessDebuggingEnabled(context)
        val ping = runCatching { HelperClient.ping() }.getOrNull()
        if (ping != null && ping.version >= HelperLauncher.VERSION) {
            TogglerState.onActivation { it.copy(wirelessDebug = wireless, helperReady = true, paired = prefs.paired) }
            TogglerState.onActivationReady(true, prefs.paired, prefs.lastConnectPort.takeIf { it > 0 })
            return@withContext true
        }
        if (ping != null) {
            AppLog.i("helper version ${ping.version}, expected ${HelperLauncher.VERSION}; restarting")
        }
        if (!prefs.paired) {
            TogglerState.onActivationReady(false, false, null)
            return@withContext false
        }
        if (!wireless) {
            TogglerState.onActivation { it.copy(wirelessDebug = false, helperReady = false) }
            return@withContext false
        }
        TogglerState.onActivation { it.copy(wirelessDebug = true) }
        val snapshot = runCatching { AdbController.relaunch(context) }
            .onFailure { AppLog.e("helper relaunch failed", it) }
            .getOrNull()
        if (snapshot != null) {
            prefs.paired = true
            TogglerState.onActivationReady(true, true, prefs.lastConnectPort.takeIf { it > 0 })
            TogglerState.onHelper(snapshot, prefs.hotspotOwnedByApp)
            return@withContext true
        }
        TogglerState.onActivationReady(false, prefs.paired, prefs.lastConnectPort.takeIf { it > 0 })
        false
    }

    fun internetAvailable(context: Context): Boolean? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrNull()

    fun selected(context: Context): SelectedDevice? {
        val prefs = Prefs(context)
        val mac = prefs.selectedMac ?: return null
        if (!BtDevices.isBonded(context, mac)) {
            prefs.selectedMac = null
            prefs.selectedName = null
            return null
        }
        return SelectedDevice(
            mac = mac,
            name = prefs.selectedName ?: mac,
            connected = BtDevices.isConnected(context, mac),
        )
    }

    private fun updateSpeed(snapshot: HelperSnapshot) {
        val now = SystemClock.elapsedRealtime()
        val rx = snapshot.rxTotal
        val tx = snapshot.txTotal
        val enabled = snapshot.on
        var down = 0.0
        var up = 0.0
        if (enabled && lastAt > 0 && now > lastAt) {
            val seconds = (now - lastAt) / 1000.0
            if (rx >= lastRx) down = (rx - lastRx) / seconds
            if (tx >= lastTx) up = (tx - lastTx) / seconds
        }
        lastAt = now
        lastRx = rx
        lastTx = tx
        downHistory.addLast(down)
        upHistory.addLast(up)
        while (downHistory.size > MAX_HISTORY) downHistory.removeFirst()
        while (upHistory.size > MAX_HISTORY) upHistory.removeFirst()
        TogglerState.onSpeed(
            SpeedSample(
                downBps = down.coerceAtLeast(0.0),
                upBps = up.coerceAtLeast(0.0),
                downHistory = downHistory.toList(),
                upHistory = upHistory.toList(),
                rxTotal = rx,
                txTotal = tx,
            ),
        )
    }
}
