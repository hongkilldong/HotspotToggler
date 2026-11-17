package com.hongkilldong.hotspottoggler

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.Toast
import com.hongkilldong.hotspottoggler.activation.ActivationService
import com.hongkilldong.hotspottoggler.helper.HelperClient
import com.hongkilldong.hotspottoggler.service.ServiceStarter
import com.hongkilldong.hotspottoggler.service.TogglerService
import com.hongkilldong.hotspottoggler.ui.AccessScreen
import com.hongkilldong.hotspottoggler.ui.DiagnosticsScreen
import com.hongkilldong.hotspottoggler.ui.HomeScreen
import com.hongkilldong.hotspottoggler.ui.Screen
import com.hongkilldong.hotspottoggler.ui.SettingsScreen
import com.hongkilldong.hotspottoggler.ui.clr
import com.hongkilldong.hotspottoggler.ui.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private lateinit var container: FrameLayout
    private var current: Screen? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var refreshing = false
    private var lastRefresh = 0L
    private var tickRunning = false
    private var askedPermissions = false

    private val tick = object : Runnable {
        override fun run() {
            current?.tick()
            val prefs = Prefs(this@MainActivity)
            val now = System.currentTimeMillis()
            if (!TogglerState.state.value.serviceRunning && prefs.paired && now - lastRefresh > 2000) {
                lastRefresh = now
                refreshFromHelper()
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        HotspotRepository.attach(this)
        HelperClient.attach(this)
        Notifications.createChannels(this)
        TetheringWatcher.start(this)
        val prefs = Prefs(this)
        TogglerState.onService(TogglerState.state.value.serviceRunning, prefs.serviceEnabled)
        TogglerState.onActivation {
            it.copy(paired = prefs.paired, port = prefs.lastConnectPort.takeIf { p -> p > 0 })
        }
        if (TogglerState.state.value.selected == null) {
            TogglerState.onSelected(HotspotRepository.selected(this))
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        container = FrameLayout(this).apply {
            setBackgroundColor(clr(R.color.bg))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(container)
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                if (!AppTheme.isNight(this)) {
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                } else 0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) {
                if (current?.onBack() != true) finish()
            }
        }
        show(HomeScreen(this))
        requestCorePermissions()
        ServiceStarter.startIfAllowed(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (current?.onBack() == true) return
        super.onBackPressed()
    }

    override fun onStart() {
        super.onStart()
        if (!tickRunning) {
            tickRunning = true
            handler.post(tick)
        }
        ServiceStarter.startIfAllowed(this)
    }

    override fun onStop() {
        tickRunning = false
        handler.removeCallbacks(tick)
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    fun show(screen: Screen) {
        current?.onHide()
        container.removeAllViews()
        current = screen
        container.addView(screen.view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        screen.onShow()
    }

    fun showHome() = show(HomeScreen(this))
    fun showAccess() = show(AccessScreen(this))
    fun showSettings() = show(SettingsScreen(this))
    fun showDiagnostics() = show(DiagnosticsScreen(this))

    fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    fun runAsync(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    fun hasBluetoothPermission(): Boolean =
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun requestCorePermissions() {
        if (askedPermissions) return
        askedPermissions = true
        val missing = mutableListOf<String>()
        if (!hasBluetoothPermission()) missing += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) missing += Manifest.permission.POST_NOTIFICATIONS
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_CORE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CORE) {
            if (hasBluetoothPermission()) {
                ServiceStarter.startIfAllowed(this)
                lastRefresh = 0
            }
            current?.tick()
        }
    }

    fun startTogglerService() {
        if (!hasBluetoothPermission()) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_SERVICE)
            return
        }
        if (Prefs(this).selectedMac == null) {
            toast(getString(R.string.home_status_no_device_title))
            return
        }
        Prefs(this).serviceEnabled = true
        ServiceStarter.startIfAllowed(this)
        TogglerState.onService(true, true)
        current?.tick()
    }

    fun stopTogglerService() {
        TogglerService.stop(this)
        TogglerState.onService(false, false)
        current?.tick()
    }

    fun reconnectHelper() {
        ActivationService.reconnect(this)
    }

    fun openHotspotSettings() {
        val intents = listOf(
            Intent("android.settings.WIFI_TETHER_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(packageManager) != null) {
                runCatching { startActivity(intent) }
                return
            }
        }
        toast(getString(R.string.home_open_settings))
    }

    fun openDeveloperSettings() {
        runCatching { startActivity(ActivationService.developerSettingsIntent(this, newTask = false)) }
    }

    fun openWirelessDebuggingSettings() {
        runCatching { startActivity(ActivationService.wirelessDebuggingIntent(this, newTask = false)) }
    }

    fun openNotificationSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun openBatterySettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun openAppInfo() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun recreateForTheme() {
        recreate()
    }

    private fun refreshFromHelper() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            runCatching { HotspotRepository.refresh(this@MainActivity, withSpeed = true) }
            refreshing = false
        }
    }

    companion object {
        private const val REQ_CORE = 10
        private const val REQ_SERVICE = 11
    }
}
