package com.hongkilldong.hotspottoggler.service

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.hongkilldong.hotspottoggler.AppLog
import com.hongkilldong.hotspottoggler.Prefs

object ServiceStarter {
    fun startIfAllowed(context: Context) {
        val prefs = Prefs(context)
        if (!prefs.serviceEnabled) return
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        try {
            TogglerService.start(context)
        } catch (e: ForegroundServiceStartNotAllowedException) {
            AppLog.e("foreground service start blocked", e)
        } catch (e: Throwable) {
            AppLog.e("foreground service start failed", e)
        }
    }
}

fun stopService(context: Context) {
    context.startService(Intent(context, TogglerService::class.java).setAction(TogglerService.ACTION_STOP))
}
