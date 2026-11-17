package com.hongkilldong.hotspottoggler.activation

import android.content.Context
import android.provider.Settings
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.hongkilldong.hotspottoggler.AppLog
import com.hongkilldong.hotspottoggler.HelperSnapshot
import com.hongkilldong.hotspottoggler.Prefs
import com.hongkilldong.hotspottoggler.helper.HelperException
import com.hongkilldong.hotspottoggler.helper.HelperLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

object AdbController {
    private const val LOOPBACK = "127.0.0.1"
    @Volatile private var configured = false

    @Synchronized
    fun configure(context: Context) {
        if (configured) return
        KadbCert.configure(store = KeystoreFile(context.applicationContext))
        runCatching { KadbCert.ensureReady() }
            .onFailure {
                AppLog.e("ADB identity init failed, resetting", it)
                runCatching { KadbCert.clear() }
                KadbCert.ensureReady()
            }
        configured = true
        AppLog.i("ADB identity ready")
    }

    fun wirelessDebuggingEnabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) != 0
    }.getOrDefault(false)

    fun usbDebuggingEnabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, "adb_enabled", 0) != 0
    }.getOrDefault(false)

    suspend fun pair(port: Int, code: String): Unit = withContext(Dispatchers.IO) {
        withTimeout(30_000) {
            Kadb.pair(LOOPBACK, port, code, "Hotspot Toggler")
        }
    }

    suspend fun connect(context: Context, port: Int): Kadb = withContext(Dispatchers.IO) {
        configure(context)
        val adb = Kadb.create(LOOPBACK, port, connectTimeout = 3_000, socketTimeout = 15_000)
        try {
            val uid = adb.shell("id -u")
            if (uid.exitCode != 0 || uid.output.trim() != "2000") {
                throw HelperException("not_shell", "adb shell uid is ${uid.output.trim()}")
            }
            adb
        } catch (e: Throwable) {
            runCatching { adb.close() }
            throw e
        }
    }

    suspend fun connectAndLaunch(context: Context, port: Int): HelperSnapshot = withContext(Dispatchers.IO) {
        connect(context, port).use { adb ->
            HelperLauncher.launch(context, adb)
        }
    }

    suspend fun tryKnownPorts(context: Context): HelperSnapshot? = withContext(Dispatchers.IO) {
        val prefs = Prefs(context)
        val ports = linkedSetOf<Int>()
        if (prefs.lastConnectPort > 0) ports += prefs.lastConnectPort
        for (port in ports) {
            val result = runCatching { connectAndLaunch(context, port) }
            if (result.isSuccess) return@withContext result.getOrNull()
            AppLog.i("known ADB port $port unavailable: ${result.exceptionOrNull()?.message}")
        }
        null
    }

    /** Known port first, then mDNS. Used by the service to recover after a reboot or helper stop. */
    suspend fun relaunch(context: Context): HelperSnapshot? = withContext(Dispatchers.IO) {
        tryKnownPorts(context)?.let { return@withContext it }
        val disc = discovery(context)
        try {
            val tried = linkedSetOf<Int>()
            repeat(10) {
                val ports = linkedSetOf<Int>()
                Prefs(context).lastConnectPort.takeIf { it > 0 }?.let { ports += it }
                disc.state.value.connect.forEach { ports += it }
                for (port in ports) {
                    if (!tried.add(port)) continue
                    val result = runCatching { connectAndLaunch(context, port) }
                    if (result.isSuccess) {
                        Prefs(context).lastConnectPort = port
                        AppLog.i("helper relaunched on ADB port $port")
                        return@withContext result.getOrNull()
                    }
                    AppLog.e("helper relaunch port $port failed", result.exceptionOrNull())
                }
                delay(1000)
            }
            null
        } finally {
            disc.close()
        }
    }

    fun discovery(context: Context): AdbMdns = AdbMdns(context).also { it.start() }
}
