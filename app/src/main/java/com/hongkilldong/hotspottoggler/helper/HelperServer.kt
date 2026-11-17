package com.hongkilldong.hotspottoggler.helper

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class HelperServer(
    private val context: android.content.Context?,
    private val options: HelperMain.Options,
) {
    private val running = AtomicBoolean(true)
    private val ops = HotspotOps(context)
    private val token = options.token.toByteArray(StandardCharsets.UTF_8)

    fun run() {
        serveTcp()
        ops.shutdown()
        Log.i(TAG, "helper stopped")
    }

    private fun serveTcp() {
        val server = ServerSocket(options.port, 8, InetAddress.getLoopbackAddress())
        Log.i(TAG, "TCP loopback on ${server.localPort}")
        try {
            while (running.get()) {
                val client = try {
                    server.accept()
                } catch (e: Throwable) {
                    if (!running.get()) break
                    Log.e(TAG, "tcp accept failed", e)
                    continue
                }
                try {
                    client.soTimeout = 30_000
                    handle(client.getInputStream().bufferedReader(StandardCharsets.UTF_8), client.getOutputStream().bufferedWriter(StandardCharsets.UTF_8))
                } catch (e: Throwable) {
                    Log.e(TAG, "tcp request failed", e)
                } finally {
                    runCatching { client.close() }
                }
            }
        } finally {
            runCatching { server.close() }
        }
    }

    private fun handle(input: BufferedReader, output: BufferedWriter) {
        val line = input.readLine() ?: return
        if (line.length > 32 * 1024) return
        val response = try {
            val request = JSONObject(line)
            if (!secureEquals(request.optString("token", ""))) {
                error("auth", "bad token")
            } else {
                when (request.optString("cmd")) {
                    "ping", "status" -> ops.snapshot()
                    "start" -> ops.start()
                    "stop" -> ops.stop()
                    "forget" -> ops.forget(request.optString("mac"))
                    "shutdown" -> {
                        running.set(false)
                        JSONObject().put("ok", true)
                    }
                    else -> error("unknown", "unknown command")
                }
            }
        } catch (e: HelperFailure) {
            error(e.code, e.message ?: e.code)
        } catch (e: Throwable) {
            Log.e(TAG, "command failed", e)
            error("internal", e.message ?: e.javaClass.simpleName)
        }
        output.write(response.toString())
        output.write("\n")
        output.flush()
    }

    private fun error(code: String, message: String): JSONObject =
        JSONObject().put("ok", false).put("code", code).put("error", message.take(800))

    private fun secureEquals(value: String): Boolean =
        MessageDigest.isEqual(value.toByteArray(StandardCharsets.UTF_8), token)

    private companion object {
        const val TAG = "HotspotHelper"
    }
}
