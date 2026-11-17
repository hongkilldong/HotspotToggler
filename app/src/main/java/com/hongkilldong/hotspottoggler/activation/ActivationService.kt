package com.hongkilldong.hotspottoggler.activation

import android.app.NotificationManager
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.provider.Settings
import com.hongkilldong.hotspottoggler.ActivationUi
import com.hongkilldong.hotspottoggler.AppLog
import com.hongkilldong.hotspottoggler.HelperSnapshot
import com.hongkilldong.hotspottoggler.Notifications
import com.hongkilldong.hotspottoggler.Prefs
import com.hongkilldong.hotspottoggler.R
import com.hongkilldong.hotspottoggler.TogglerState
import com.hongkilldong.hotspottoggler.helper.HelperClient
import com.hongkilldong.hotspottoggler.helper.HelperException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class ActivationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var discovery: AdbMdns? = null
    private var codeDeferred: CompletableDeferred<String>? = null
    private var lastCodeError: String? = null
    private var foreground = false
    private var codeOverlay: CodeOverlay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        HelperClient.attach(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifications.createChannels(this)
        startForegroundSafe(getString(R.string.access_busy_search))
        when (intent?.action) {
            ACTION_CODE -> {
                val direct = intent.getStringExtra(EXTRA_CODE_VALUE)
                val code = direct ?: RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(Notifications.EXTRA_CODE)?.toString()?.trim().orEmpty()
                val deferred = codeDeferred
                if (deferred != null && !deferred.isCompleted) {
                    deferred.complete(code.trim())
                } else {
                    AppLog.i("pairing code received while no activation is waiting")
                }
            }
            ACTION_CANCEL -> cancelActivation()
            ACTION_RECONNECT -> {
                clearOldNotifications()
                startJob {
                    runReconnect()
                }
            }
            ACTION_START, null -> {
                clearOldNotifications()
                startJob {
                    runActivation(
                        pairPortArg = intent?.getIntExtra(EXTRA_PAIR_PORT, 0) ?: 0,
                        connectPortArg = intent?.getIntExtra(EXTRA_CONNECT_PORT, 0) ?: 0,
                        initialCode = intent?.getStringExtra(EXTRA_CODE),
                    )
                }
            }
            else -> stopSelfSafely()
        }
        return START_NOT_STICKY
    }

    /** Old result notifications caused Android to autogroup and hide the live code notification. */
    private fun clearOldNotifications() {
        getSystemService(NotificationManager::class.java).apply {
            cancel(Notifications.ID_CODE)
            cancel(Notifications.ID_RESULT)
        }
    }

    private fun startJob(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                AppLog.i("activation cancelled")
            } catch (e: Throwable) {
                fail(e)
            } finally {
                discovery?.close()
                discovery = null
                codeDeferred = null
                TogglerState.onActivation { it.copy(busy = false, awaitingCode = false) }
            }
        }
    }

    private suspend fun runActivation(pairPortArg: Int, connectPortArg: Int, initialCode: String?) {
        AdbController.configure(this)
        val prefs = Prefs(this)
        val wireless = AdbController.wirelessDebuggingEnabled(this)
        TogglerState.onActivation {
            it.copy(
                busy = true,
                step = ActivationUi.Step.SEARCHING,
                error = null,
                awaitingCode = false,
                paired = prefs.paired,
                wirelessDebug = wireless,
                port = prefs.lastConnectPort.takeIf { it > 0 },
            )
        }
        if (!wireless) {
            throw HelperException("wireless_off", getString(R.string.access_need_wifi_debug))
        }
        var pairPort = pairPortArg
        var connectPort = connectPortArg
        var code = initialCode?.takeIf { it.length == 6 && it.all(Char::isDigit) }
        lastCodeError = null

        val known = if (connectPort == 0 && prefs.lastConnectPort > 0) prefs.lastConnectPort else 0
        if (prefs.paired && known > 0) {
            updateProgress(getString(R.string.access_busy_connecting))
            val snapshot = runCatching { AdbController.connectAndLaunch(this, known) }.getOrNull()
            if (snapshot != null) {
                complete(snapshot, known)
                return
            }
        }

        val disc = AdbController.discovery(this)
        discovery = disc
        disc.start()

        if (!prefs.paired) {
            if (pairPort <= 0) {
                TogglerState.onActivation {
                    it.copy(busy = true, step = ActivationUi.Step.WAIT_PAIR, awaitingCode = false, error = null)
                }
                updateProgress(getString(R.string.access_busy_pair_dialog))
                pairPort = disc.awaitPairPort(120_000)
                    ?: throw HelperException("no_pair_port", getString(R.string.access_code_wrong))
            }
            var attempts = 0
            while (true) {
                if (code == null) {
                    code = awaitCode()
                }
                // The user may have reopened the pairing dialog while typing the code.
                disc.state.value.pairing.lastOrNull()?.let { pairPort = it }
                updateProgress(getString(R.string.access_busy_pairing))
                try {
                    AdbController.pair(pairPort, code)
                    prefs.paired = true
                    lastCodeError = null
                    break
                } catch (e: Throwable) {
                    attempts++
                    AppLog.e("pairing failed", e)
                    if (initialCode != null || attempts >= 5) throw HelperException("pairing", getString(R.string.err_pairing))
                    code = null
                    lastCodeError = getString(R.string.notif_code_wrong)
                    // The pairing dialog normally closes after a failed attempt. Wait for the user
                    // to reopen it; the next code will be sent to the freshly advertised port.
                    disc.awaitPairPort(60_000)?.let { pairPort = it }
                }
            }
        }

        if (connectPort <= 0) {
            updateProgress(getString(R.string.access_busy_starting))
            val (snapshot, port) = connectUsingDiscovery(disc, 0)
            prefs.lastConnectPort = port
            complete(snapshot, port)
            return
        }

        updateProgress(getString(R.string.access_busy_starting))
        val snapshot = AdbController.connectAndLaunch(this, connectPort)
        prefs.lastConnectPort = connectPort
        complete(snapshot, connectPort)
    }

    /**
     * mDNS can briefly keep a stale connect port after wireless debugging restarts. Try every
     * advertised port (plus the remembered one) until one accepts the ADB connection.
     */
    private suspend fun connectUsingDiscovery(disc: AdbMdns, manualPort: Int): Pair<HelperSnapshot, Int> {
        val tried = linkedSetOf<Int>()
        var lastError: Throwable? = null
        repeat(30) {
            val ports = linkedSetOf<Int>()
            if (manualPort > 0) ports += manualPort
            disc.state.value.connect.forEach { ports += it }
            Prefs(this).lastConnectPort.takeIf { it > 0 }?.let { ports += it }
            for (port in ports) {
                if (!tried.add(port)) continue
                val result = runCatching { AdbController.connectAndLaunch(this, port) }
                if (result.isSuccess) {
                    Prefs(this).lastConnectPort = port
                    return result.getOrNull()!! to port
                }
                lastError = result.exceptionOrNull()
                AppLog.e("ADB connect port $port failed", lastError)
            }
            if (manualPort > 0) {
                throw HelperException("connect", lastError?.message ?: getString(R.string.err_helper_unavailable))
            }
            delay(1000)
        }
        throw HelperException("connect", lastError?.message ?: getString(R.string.err_helper_unavailable))
    }

    private suspend fun runReconnect() {
        AdbController.configure(this)
        val prefs = Prefs(this)
        if (!prefs.paired) throw HelperException("not_paired", getString(R.string.err_pairing))
        if (!AdbController.wirelessDebuggingEnabled(this)) {
            throw HelperException("wireless_off", getString(R.string.access_need_wifi_debug))
        }
        TogglerState.onActivation {
            it.copy(busy = true, step = ActivationUi.Step.CONNECTING, error = null, awaitingCode = false, paired = true)
        }
        updateProgress(getString(R.string.access_busy_connecting))
        val known = AdbController.tryKnownPorts(this)
        if (known != null) {
            complete(known, prefs.lastConnectPort)
            return
        }
        val disc = AdbController.discovery(this)
        discovery = disc
        disc.start()
        updateProgress(getString(R.string.access_busy_starting))
        val (snapshot, port) = connectUsingDiscovery(disc, 0)
        prefs.lastConnectPort = port
        complete(snapshot, port)
    }

    private suspend fun awaitCode(): String {
        val deferred = CompletableDeferred<String>()
        codeDeferred = deferred
        TogglerState.onActivation {
            it.copy(
                busy = true,
                awaitingCode = true,
                step = ActivationUi.Step.WAIT_CODE,
                error = null,
            )
        }
        updateProgress(getString(R.string.access_busy_code))
        val nm = getSystemService(NotificationManager::class.java)
        if (Settings.canDrawOverlays(this)) {
            codeOverlay = CodeOverlay.show(
                this,
                onSubmit = { value ->
                    codeOverlay?.hide()
                    codeOverlay = null
                    deferred.complete(value)
                },
                onCancel = {
                    codeOverlay = null
                    cancelActivation()
                },
            )
        }
        nm.cancel(Notifications.ID_RESULT)
        nm.cancel(Notifications.ID_CODE)
        // Show the input on the foreground notification itself: Android hides extra app
        // notifications (and non-system overlays) while the system pairing dialog is open.
        showCodeForeground(lastCodeError)
        val code = withTimeoutOrNull(5 * 60_000L) { deferred.await() }
        codeOverlay?.hide()
        codeOverlay = null
        nm.cancel(Notifications.ID_CODE)
        TogglerState.onActivation { it.copy(awaitingCode = false) }
        if (code == null) throw HelperException("code_timeout", getString(R.string.access_busy_code))
        if (code.length != 6 || code.any { !it.isDigit() }) {
            lastCodeError = getString(R.string.access_code_wrong)
            return awaitCode()
        }
        return code
    }

    private fun complete(snapshot: HelperSnapshot, port: Int) {
        codeOverlay?.hide()
        codeOverlay = null
        val prefs = Prefs(this)
        prefs.paired = true
        prefs.lastConnectPort = port
        TogglerState.setError(null)
        TogglerState.onActivationReady(helperReady = true, paired = true, port = port)
        TogglerState.onHelper(snapshot, prefs.hotspotOwnedByApp)
        getSystemService(NotificationManager::class.java).apply {
            cancel(Notifications.ID_CODE)
            notify(Notifications.ID_RESULT, Notifications.result(this@ActivationService, getString(R.string.access_result_ready), false))
        }
        AppLog.i("activation complete, helper pid=${snapshot.pid} uid=${snapshot.uid}")
        stopSelfSafely()
    }

    private fun fail(error: Throwable) {
        codeOverlay?.hide()
        codeOverlay = null
        val message = userMessage(error)
        AppLog.e("activation failed", error)
        TogglerState.onActivation {
            it.copy(
                busy = false,
                awaitingCode = false,
                step = ActivationUi.Step.FAILED,
                error = message,
            )
        }
        getSystemService(NotificationManager::class.java).apply {
            cancel(Notifications.ID_CODE)
            notify(Notifications.ID_RESULT, Notifications.result(this@ActivationService, message, true))
        }
        stopSelfSafely()
    }

    private fun userMessage(error: Throwable): String {
        val raw = error.message?.takeIf { it.isNotBlank() } ?: getString(R.string.err_unknown)
        return when (error) {
            is HelperException -> when (error.code) {
                "wireless_off" -> getString(R.string.err_wireless_off)
                "pairing" -> getString(R.string.err_pairing)
                "not_paired" -> getString(R.string.err_pairing)
                "unavailable", "launch_failed", "not_shell", "package_mismatch", "dispatch" ->
                    getString(R.string.err_helper_unavailable)
                else -> raw
            }
            else -> raw
        }.take(500)
    }

    private fun cancelActivation() {
        codeOverlay?.hide()
        codeOverlay = null
        codeDeferred?.complete("")
        codeDeferred = null
        discovery?.close()
        discovery = null
        job?.cancel()
        job = null
        getSystemService(NotificationManager::class.java).cancel(Notifications.ID_CODE)
        TogglerState.onActivation { it.copy(busy = false, awaitingCode = false, step = ActivationUi.Step.IDLE) }
        AppLog.i("activation cancelled by user")
        stopSelfSafely()
    }

    private fun updateProgress(text: String) {
        startForegroundSafe(text)
    }

    private fun showCodeForeground(error: String?) {
        val notification = Notifications.pairingCode(this, error)
        if (!foreground) {
            startForeground(Notifications.ID_ACTIVATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            foreground = true
        } else {
            getSystemService(NotificationManager::class.java)
                .notify(Notifications.ID_ACTIVATION, notification)
        }
    }

    private fun startForegroundSafe(text: String) {
        val notification = Notifications.activationProgress(this, text)
        if (!foreground) {
            startForeground(Notifications.ID_ACTIVATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            foreground = true
        } else {
            getSystemService(NotificationManager::class.java).notify(Notifications.ID_ACTIVATION, notification)
        }
    }

    private fun stopSelfSafely() {
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelf()
    }

    override fun onDestroy() {
        codeOverlay?.hide()
        codeOverlay = null
        scope.cancel()
        discovery?.close()
        discovery = null
        codeDeferred = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.hongkilldong.hotspottoggler.action.ACTIVATE"
        const val ACTION_RECONNECT = "com.hongkilldong.hotspottoggler.action.RECONNECT"
        const val ACTION_CANCEL = "com.hongkilldong.hotspottoggler.action.CANCEL"
        const val ACTION_CODE = "com.hongkilldong.hotspottoggler.action.CODE"
        const val EXTRA_PAIR_PORT = "pair_port"
        const val EXTRA_CONNECT_PORT = "connect_port"
        const val EXTRA_CODE = "code"
        const val EXTRA_CODE_VALUE = "code_value"

        fun start(context: Context, pairPort: Int = 0, connectPort: Int = 0, code: String? = null) {
            val intent = Intent(context, ActivationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PAIR_PORT, pairPort)
                .putExtra(EXTRA_CONNECT_PORT, connectPort)
            if (code != null) intent.putExtra(EXTRA_CODE, code)
            context.startForegroundService(intent)
        }

        fun reconnect(context: Context) {
            context.startForegroundService(Intent(context, ActivationService::class.java).setAction(ACTION_RECONNECT))
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, ActivationService::class.java).setAction(ACTION_CANCEL))
        }

        fun sendCode(context: Context, code: String) {
            context.startService(
                Intent(context, ActivationService::class.java)
                    .setAction(ACTION_CODE)
                    .putExtra(EXTRA_CODE_VALUE, code),
            )
        }

        fun developerSettingsIntent(context: Context, newTask: Boolean = true): Intent =
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                if (newTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        /**
         * Best effort to open Wireless debugging directly. If the ROM does not support a direct
         * link, Settings' Developer options root is opened instead. Activity launches pass
         * newTask=false, so Back from the Settings root returns to Hotspot Toggler.
         */
        fun wirelessDebuggingIntent(context: Context, newTask: Boolean = true): Intent {
            val direct = Intent("android.settings.WIRELESS_DEBUGGING")
            if (context.packageManager.resolveActivity(direct, 0) != null) {
                if (newTask) direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return direct
            }
            return Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                putExtra(":settings:show_fragment", "com.android.settings.development.WirelessDebuggingFragment")
                putExtra(":settings:show_fragment_as_subsetting", true)
                if (newTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}
