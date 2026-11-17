package com.hongkilldong.hotspottoggler.helper

import android.content.Context
import com.hongkilldong.hotspottoggler.HelperSnapshot
import com.hongkilldong.hotspottoggler.HotspotClient
import com.hongkilldong.hotspottoggler.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.InetAddress
import java.net.Socket
import kotlin.math.max

class HelperException(val code: String, message: String) : Exception(message)

object HelperClient {
    private const val MAX_LINE = 64 * 1024

    suspend fun status(timeoutMs: Long = 2500): HelperSnapshot = withContext(Dispatchers.IO) {
        parseStatus(request("status", timeoutMs))
    }

    suspend fun ping(timeoutMs: Long = 2000): HelperSnapshot = withContext(Dispatchers.IO) {
        parseStatus(request("ping", timeoutMs))
    }

    suspend fun startHotspot(timeoutMs: Long = 30000): HelperSnapshot = withContext(Dispatchers.IO) {
        parseStatus(request("start", timeoutMs))
    }

    suspend fun stopHotspot(timeoutMs: Long = 12000): HelperSnapshot = withContext(Dispatchers.IO) {
        parseStatus(request("stop", timeoutMs))
    }

    suspend fun forget(mac: String, timeoutMs: Long = 7000): Boolean = withContext(Dispatchers.IO) {
        request("forget", timeoutMs, "mac" to mac).optBoolean("result", false)
    }

    suspend fun shutdown() {
        withContext(Dispatchers.IO) {
            runCatching { request("shutdown", 2000) }
        }
    }

    suspend fun isRunning(context: Context): Boolean = withContext(Dispatchers.IO) {
        runCatching { ping() }.isSuccess
    }

    private suspend fun request(
        cmd: String,
        timeoutMs: Long,
        vararg params: Pair<String, Any?>,
    ): JSONObject {
        val context = Holder.context ?: throw HelperException("unavailable", "no context")
        val prefs = Prefs(context)
        val token = prefs.helperToken
        if (token.isNullOrBlank()) {
            throw HelperException("not_activated", "helper is not activated")
        }
        val request = JSONObject().apply {
            put("token", token)
            put("cmd", cmd)
            params.forEach { (key, value) -> if (value != null) put(key, value) }
        }
        val line = request.toString()
        val response = runCatching {
            tcpSocket(prefs.helperPort, line, timeoutMs)
        }.getOrElse {
            throw HelperException("unavailable", it.message ?: "helper is not reachable")
        }
        val json = runCatching { JSONObject(response) }
            .getOrElse { throw HelperException("protocol", "invalid helper response") }
        if (!json.optBoolean("ok", false)) {
            val code = json.optString("code", "helper_error")
            val message = json.optString("error", "helper error")
            throw HelperException(code, message)
        }
        return json
    }

    /**
     * The helper is a shell process. Android SELinux forbids an untrusted app from connecting
     * to a shell-owned Unix abstract socket, so the transport is TCP on loopback only.
     */
    private fun tcpSocket(port: Int, line: String, timeoutMs: Long): String {
        if (port !in 1..65535) throw HelperException("unavailable", "no helper port")
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = max(500, timeoutMs.toInt())
            val out: BufferedWriter = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
            val input: BufferedReader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            out.write(line)
            out.write("\n")
            out.flush()
            val response = input.readLine() ?: throw HelperException("protocol", "helper closed the socket")
            if (response.length > MAX_LINE) throw HelperException("protocol", "helper response too large")
            return response
        }
    }

    private fun parseStatus(json: JSONObject): HelperSnapshot {
        val clientsJson = json.optJSONArray("clients") ?: JSONArray()
        val clients = buildList {
            for (i in 0 until clientsJson.length()) {
                val c = clientsJson.optJSONObject(i) ?: continue
                val mac = c.optString("mac").takeIf { it.isNotBlank() } ?: continue
                add(
                    HotspotClient(
                        mac = mac,
                        ip = c.optString("ip").takeIf { it.isNotBlank() },
                        name = c.optString("name").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
        return HelperSnapshot(
            on = json.optBoolean("on", false),
            starting = json.optBoolean("starting", false),
            already = json.optBoolean("already", false),
            state = json.optInt("state", 0),
            ssid = json.optString("ssid").takeIf { it.isNotBlank() },
            iface = json.optString("iface").takeIf { it.isNotBlank() },
            clients = clients,
            rxTotal = json.optLong("rx", 0),
            txTotal = json.optLong("tx", 0),
            uid = json.optInt("uid", -1),
            pid = json.optInt("pid", -1),
            version = json.optInt("version", 0),
            error = json.optString("error").takeIf { it.isNotBlank() },
        )
    }

    internal fun attach(context: Context) {
        Holder.context = context.applicationContext
    }

    private object Holder {
        @Volatile var context: Context? = null
    }
}
