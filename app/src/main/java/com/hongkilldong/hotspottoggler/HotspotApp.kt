package com.hongkilldong.hotspottoggler

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.TetheringInterface
import android.net.TetheringManager
import android.os.Build
import java.util.concurrent.Executor

class HotspotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        clearJournalOnUpdate()
        HotspotRepository.attach(this)
        Notifications.createChannels(this)
        TetheringWatcher.start(this)
        AppLog.i("app started")
    }

    /** Old diagnostics entries (for example local-socket errors) must not survive an update. */
    private fun clearJournalOnUpdate() {
        runCatching {
            val meta = getSharedPreferences("app_meta", Context.MODE_PRIVATE)
            val installedAt = packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0),
            ).lastUpdateTime
            if (meta.getLong("last_update_time", 0L) != installedAt) {
                AppLog.clear()
                meta.edit().putLong("last_update_time", installedAt).apply()
            }
        }
    }
}

/** Public Android 16 tethering callback: gives hotspot on/off, SSID and interface without any privilege. */
object TetheringWatcher {
    private var callback: TetheringManager.TetheringEventCallback? = null
    private var manager: TetheringManager? = null

    @SuppressLint("NewApi")
    fun start(context: Context) {
        if (Build.VERSION.SDK_INT < 36 || callback != null) return
        val ctx = context.applicationContext
        val tm = ctx.getSystemService(TetheringManager::class.java) ?: return
        val cb = object : TetheringManager.TetheringEventCallback {
            override fun onTetheredInterfacesChanged(interfaces: Set<TetheringInterface>) {
                runCatching {
                    val wifi = interfaces.firstOrNull { it.type == TetheringManager.TETHERING_WIFI }
                    if (wifi == null) {
                        TogglerState.onPublicHotspot(false, false, null, null)
                    } else {
                        val cfg = wifi.softApConfiguration
                        val ssid = cfg?.ssid?.takeIf { it.isNotBlank() }
                            ?: cfg?.wifiSsid?.toString()?.trim('"')
                        TogglerState.onPublicHotspot(true, false, ssid, wifi.`interface`)
                    }
                }.onFailure { AppLog.e("tethering callback", it) }
            }
        }
        runCatching {
            tm.registerTetheringEventCallback(Executor { it.run() }, cb)
            manager = tm
            callback = cb
            AppLog.i("tethering watcher registered")
        }.onFailure { AppLog.e("tethering watcher unavailable", it) }
    }
}
