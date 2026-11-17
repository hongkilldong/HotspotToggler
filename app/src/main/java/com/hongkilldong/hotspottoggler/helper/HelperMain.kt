package com.hongkilldong.hotspottoggler.helper

import android.content.Context
import android.os.Looper
import android.os.Process
import androidx.annotation.Keep
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.InvocationTargetException

@Keep
object HelperMain {
    const val HELPER_VERSION = 2

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val options = Options.parse(args)
            val uid = Process.myUid()
            if (uid != 2000 && uid != 0) {
                System.err.println("helper must run as shell, uid=$uid")
                System.exit(2)
            }
            HiddenApiBypass.addHiddenApiExemptions("L")
            if (Looper.myLooper() == null) Looper.prepareMainLooper()
            val context = systemContext()
            println("HELPER_START uid=$uid pid=${Process.myPid()} socket=${options.socket} port=${options.port}")
            HelperServer(context, options).run()
            System.exit(0)
        } catch (error: Throwable) {
            error.printStackTrace(System.err)
            System.exit(1)
        }
    }

    private fun systemContext(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val main = activityThread.getMethod("systemMain").invoke(null)
        activityThread.getMethod("getSystemContext").invoke(main) as? Context
    }.onFailure {
        val cause = (it as? InvocationTargetException)?.targetException ?: it
        System.err.println("system context unavailable: ${cause.javaClass.simpleName}: ${cause.message}")
    }.getOrNull()

    class Options(
        val packageName: String,
        val socket: String,
        val token: String,
        val port: Int,
        val version: Int,
    ) {
        companion object {
            fun parse(args: Array<String>): Options {
                var pkg = ""
                var socket = ""
                var token = ""
                var port = 0
                var version = 0
                var i = 0
                while (i < args.size) {
                    val key = args[i]
                    val value = args.getOrNull(i + 1)
                    when (key) {
                        "--package" -> { pkg = value.orEmpty(); i += 2 }
                        "--socket" -> { socket = value.orEmpty(); i += 2 }
                        "--token" -> { token = value.orEmpty(); i += 2 }
                        "--port" -> { port = value?.toIntOrNull() ?: 0; i += 2 }
                        "--version" -> { version = value?.toIntOrNull() ?: 0; i += 2 }
                        else -> i++
                    }
                }
                require(socket.length in 4..120) { "bad socket name" }
                require(token.length in 16..128) { "bad token" }
                require(port in 1..65535) { "bad port" }
                return Options(pkg, socket, token, port, version)
            }
        }
    }
}
