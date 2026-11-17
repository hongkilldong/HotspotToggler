package com.hongkilldong.hotspottoggler.helper

import android.content.Context
import com.flyfishxu.kadb.Kadb
import com.hongkilldong.hotspottoggler.AppLog
import com.hongkilldong.hotspottoggler.HelperSnapshot
import com.hongkilldong.hotspottoggler.Prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object HelperLauncher {
    const val VERSION = 2
    private const val LOG_PATH = "/data/local/tmp/hotspot-toggler.log"
    private const val HELPER_APK = "/data/local/tmp/hotspot-toggler-helper.apk"
    private val launchMutex = Mutex()

    suspend fun launch(context: Context, adb: Kadb): HelperSnapshot = launchMutex.withLock {
        HelperClient.attach(context)
        val existing = runCatching { HelperClient.ping(1500) }.getOrNull()
        if (existing != null && existing.version >= VERSION && (existing.uid == 2000 || existing.uid == 0)) {
            Prefs(context).helperVersion = VERSION
            AppLog.i("helper already running pid=${existing.pid} version=${existing.version}")
            return@withLock existing
        }
        val uid = adb.shell("id -u")
        val uidText = uid.output.trim()
        if (uid.exitCode != 0 || uidText != "2000") {
            throw HelperException("not_shell", "adb shell uid is $uidText, expected 2000")
        }
        val pkg = context.packageName
        val apk = context.applicationInfo.sourceDir
        val pm = adb.shell("pm path $pkg")
        if (pm.exitCode != 0 || pm.output.lineSequence().none { it.trim() == "package:$apk" }) {
            throw HelperException("package_mismatch", "installed APK does not match the running app")
        }
        val preflight = adb.shell(
            "test -x /system/bin/app_process && test -x /system/bin/setsid && " +
                "test -x /system/bin/nohup && echo READY",
        )
        if (!preflight.output.contains("READY")) {
            throw HelperException("unsupported_shell", "app_process/setsid/nohup are not available")
        }
        val prefs = Prefs(context)
        // Replace an older helper (or a stale one from a previous build) before binding the port.
        runCatching { HelperClient.shutdown() }
        delay(1000)
        // Run from a stable copy so an app update does not kill the detached shell helper.
        // Copy to a new inode and rename atomically; truncating a running helper's APK can crash it.
        val copy = adb.shell(
            "cp '$apk' $HELPER_APK.new && chmod 644 $HELPER_APK.new && " +
                "mv -f $HELPER_APK.new $HELPER_APK && echo COPIED",
        )
        if (!copy.output.contains("COPIED")) {
            throw HelperException("helper_copy", copy.output.takeLast(300).ifBlank { "cannot copy helper apk" })
        }
        val (socket, token, port) = prefs.ensureHelperIdentity()
        val command = buildString {
            append("CLASSPATH='").append(HELPER_APK).append("' ")
            append("/system/bin/setsid /system/bin/nohup /system/bin/app_process /system/bin ")
            append("com.hongkilldong.hotspottoggler.helper.HelperMain ")
            append("--package '").append(pkg).append("' ")
            append("--socket '").append(socket).append("' ")
            append("--token '").append(token).append("' ")
            append("--port ").append(port).append(' ')
            append("--version ").append(VERSION).append(' ')
            append("</dev/null >").append(LOG_PATH).append(" 2>&1 & echo HELPER_DISPATCHED")
        }
        AppLog.i("dispatching helper on port $port")
        val dispatch = adb.shell(command)
        if (!dispatch.output.contains("HELPER_DISPATCHED")) {
            throw HelperException("dispatch", dispatch.output.takeLast(300).ifBlank { "helper dispatch failed" })
        }
        var lastError: String? = null
        repeat(30) {
            delay(400)
            runCatching { HelperClient.ping(2500) }
                .onSuccess {
                    if ((it.uid == 2000 || it.uid == 0) && it.version >= VERSION) {
                        prefs.helperVersion = VERSION
                        AppLog.i("helper ready pid=${it.pid} uid=${it.uid} version=${it.version}")
                        return@withLock it
                    }
                    lastError = "helper uid=${it.uid} version=${it.version}, expected version $VERSION"
                }
                .onFailure { lastError = it.message }
        }
        val log = runCatching { adb.shell("tail -c 3000 $LOG_PATH").output }.getOrDefault("")
        AppLog.e("helper did not start: ${lastError.orEmpty()}; log=$log")
        throw HelperException(
            "launch_failed",
            (lastError ?: "helper did not answer").take(300) +
                if (log.isNotBlank()) "\n" + log.takeLast(800) else "",
        )
    }

    suspend fun stop(context: Context) {
        HelperClient.attach(context)
        runCatching { HelperClient.shutdown() }
    }

    fun logPath(): String = LOG_PATH
}
