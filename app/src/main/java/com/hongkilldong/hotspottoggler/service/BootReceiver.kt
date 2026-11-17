package com.hongkilldong.hotspottoggler.service

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hongkilldong.hotspottoggler.AppLog
import com.hongkilldong.hotspottoggler.Prefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        if (!prefs.serviceEnabled || !prefs.autoStartOnBoot) return
        try {
            TogglerService.start(context)
            AppLog.i("service started after boot")
        } catch (e: ForegroundServiceStartNotAllowedException) {
            AppLog.e("boot foreground service blocked", e)
        } catch (e: Throwable) {
            AppLog.e("boot service start failed", e)
        }
    }
}
