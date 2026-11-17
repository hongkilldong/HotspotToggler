package com.hongkilldong.hotspottoggler.helper

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.TetheringManager
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import androidx.annotation.Keep
import com.hongkilldong.hotspottoggler.HelperSnapshot
import com.hongkilldong.hotspottoggler.HotspotClient
import org.json.JSONArray
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class HelperFailure(val code: String, message: String) : Exception(message)

/** Runs in app_process with shell identity; only system services are used here. */
class HotspotOps(private val context: Context?) {
    private val uid = Process.myUid()
    private val callerPkg = if (uid == 0) "root" else "com.android.shell"
    private val wifiManager: WifiManager? = context?.getSystemService(WifiManager::class.java)
    private val wifi: Any? = service("wifi", "android.net.wifi.IWifiManager")
    private val tethering: Any? = service("tethering", "android.net.ITetheringConnector")
    private val clients = ConcurrentHashMap<String, HotspotClient>()
    private val callbackLock = Any()
    private var apCallback: Any? = null
    private var callbackInterface: Class<*>? = null
    private var lastIface: String? = null
    private var lastConfig: SoftApConfiguration? = null
    private var lastClientsAt = 0L
    private var lastDumpsysAt = 0L
    private var lastDumpsys = ""
    private var lastState = -1
    private var lastAlready = false

    init {
        registerSoftApCallback()
    }

    fun snapshot(already: Boolean = lastAlready): JSONObject = JSONObject().apply {
        val state = getState()
        val cfg = getConfig()
        val iface = getIface()
        val counters = getCounters(iface)
        val list = getClients(iface)
        put("ok", true)
        put("on", state == STATE_ENABLED)
        put("starting", state == STATE_ENABLING)
        put("already", already)
        put("state", state)
        put("ssid", ssidOf(cfg) ?: "")
        put("iface", iface ?: "")
        put("rx", counters.first)
        put("tx", counters.second)
        put("uid", uid)
        put("pid", Process.myPid())
        put("version", HelperMain.HELPER_VERSION)
        put("clients", JSONArray().apply {
            list.forEach { c ->
                put(JSONObject().apply {
                    put("mac", c.mac)
                    c.ip?.let { put("ip", it) }
                    c.name?.let { put("name", it) }
                })
            }
        })
    }

    fun start(): JSONObject {
        val before = getState()
        if (before == STATE_ENABLED) {
            lastAlready = true
            return snapshot(already = true)
        }
        if (before == STATE_ENABLING) {
            waitForState(STATE_ENABLED, 20_000)
            lastAlready = true
            return snapshot(already = true)
        }
        val cfg = getConfig()
        val builder = TetheringManager.TetheringRequest.Builder(TetheringManager.TETHERING_WIFI)
        if (cfg != null) runCatching { builder.setSoftApConfiguration(cfg) }
        val request = builder.build()
        val parcel = request.javaClass.getMethod("getParcel").invoke(request)
        val result = tetheringCall("startTethering", parcel, 25_000)
        Log.i(TAG, "startTethering result=$result")
        if (result != TETHER_OK) throw failure(result)
        if (!waitForState(STATE_ENABLED, 20_000)) {
            val state = getState()
            if (state == STATE_ENABLING) throw HelperFailure("timeout", "hotspot is still enabling")
            throw HelperFailure("hotspot_failed", "hotspot state=$state")
        }
        lastAlready = false
        return snapshot(already = false)
    }

    fun stop(): JSONObject {
        val state = getState()
        if (state != STATE_ENABLED && state != STATE_ENABLING) return snapshot()
        val result = tetheringCall("stopTethering", TetheringManager.TETHERING_WIFI, 12_000)
        Log.i(TAG, "stopTethering result=$result")
        if (result != TETHER_OK) throw failure(result)
        waitForState(STATE_DISABLED, 8_000)
        lastAlready = false
        return snapshot()
    }

    fun forget(mac: String): JSONObject {
        if (!Pattern.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}", mac)) {
            throw HelperFailure("bad_mac", "invalid MAC")
        }
        val adapter = adapter()
        val device = adapter.getRemoteDevice(mac)
        val result = runCatching {
            val method = device.javaClass.getMethod("removeBond")
            method.invoke(device) as? Boolean
        }.getOrElse {
            throw HelperFailure("forget_failed", it.message ?: "removeBond failed")
        }
        Log.i(TAG, "removeBond $mac -> $result")
        return JSONObject().put("ok", true).put("result", result != false)
    }

    fun shutdown() {
        unregisterSoftApCallback()
    }

    private fun adapter(): BluetoothAdapter {
        val ctx = context ?: throw HelperFailure("no_context", "no Android context")
        val manager = ctx.getSystemService(BluetoothManager::class.java)
            ?: throw HelperFailure("no_bluetooth", "BluetoothManager is unavailable")
        return manager.adapter ?: throw HelperFailure("no_bluetooth", "Bluetooth is off")
    }

    private fun getState(): Int {
        lastState = runCatching {
            if (wifi != null) {
                val method = wifi.javaClass.methods.firstOrNull { it.name == "getWifiApEnabledState" && it.parameterTypes.isEmpty() }
                if (method != null) return@runCatching (method.invoke(wifi) as Number).toInt()
            }
            if (wifiManager != null) {
                val method = wifiManager.javaClass.methods.firstOrNull { it.name == "getWifiApState" && it.parameterTypes.isEmpty() }
                if (method != null) return@runCatching (method.invoke(wifiManager) as Number).toInt()
            }
            lastState
        }.getOrDefault(lastState)
        return lastState
    }

    private fun ssidOf(cfg: SoftApConfiguration?): String? {
        if (cfg == null) return null
        val direct = runCatching { cfg.javaClass.getMethod("getSsid").invoke(cfg) as? String }.getOrNull()
        if (!direct.isNullOrBlank()) return direct
        val wifiSsid = runCatching { cfg.javaClass.getMethod("getWifiSsid").invoke(cfg) }.getOrNull()
        return wifiSsid?.toString()?.trim('"')
    }

    private fun getConfig(): SoftApConfiguration? {
        lastConfig?.let { return it }
        val cfg = runCatching {
            if (wifi != null) {
                val method = wifi.javaClass.methods.firstOrNull { it.name == "getSoftApConfiguration" && it.parameterTypes.isEmpty() }
                if (method != null) return@runCatching method.invoke(wifi) as? SoftApConfiguration
            }
            if (wifiManager != null) {
                val method = wifiManager.javaClass.methods.firstOrNull { it.name == "getSoftApConfiguration" && it.parameterTypes.isEmpty() }
                if (method != null) return@runCatching method.invoke(wifiManager) as? SoftApConfiguration
            }
            null
        }.getOrNull()
        lastConfig = cfg
        return cfg
    }

    private fun waitForState(target: Int, timeoutMs: Long): Boolean {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < until) {
            if (getState() == target) return true
            if (target == STATE_ENABLED && getState() == STATE_FAILED) return false
            Thread.sleep(200)
        }
        return getState() == target
    }

    private fun tetheringCall(methodName: String, arg: Any?, timeoutMs: Long): Int {
        val service = tethering ?: throw HelperFailure("no_tethering", "tethering service is unavailable")
        val result = CountDownLatch(1)
        val intResult = intArrayOf(Int.MIN_VALUE)
        val listenerBinder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
                data.enforceInterface("android.net.IIntResultListener")
                val value = runCatching { data.readInt() }.getOrDefault(Int.MIN_VALUE)
                intResult[0] = value
                result.countDown()
                return true
            }
        }
        val listenerInterface = Class.forName("android.net.IIntResultListener")
        val listener = Proxy.newProxyInstance(
            listenerInterface.classLoader,
            arrayOf(listenerInterface),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "asBinder" -> listenerBinder
                    "onResult" -> {
                        intResult[0] = (args?.firstOrNull() as? Number)?.toInt() ?: Int.MIN_VALUE
                        result.countDown()
                        null
                    }
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "HotspotTogglerIntResult"
                    else -> null
                }
            },
        )
        val method = service.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterTypes.size == 4
        } ?: throw HelperFailure("protocol", "$methodName not found")
        try {
            method.invoke(service, arg, callerPkg, null, listener)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
        if (!result.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw HelperFailure("timeout", "$methodName did not return a result")
        }
        return intResult[0]
    }

    private fun failure(result: Int): HelperFailure {
        val code = when (result) {
            11, 13 -> "entitlement"
            14, 15 -> "no_permission"
            3 -> "unsupported"
            else -> "hotspot_failed"
        }
        return HelperFailure(code, "tethering result=$result")
    }

    private fun getIface(): String? {
        val state = getState()
        val dumpsys = dumpsysTethering()
        val m = IFACE_PATTERN.matcher(dumpsys)
        if (m.find()) {
            lastIface = m.group(1)
            return lastIface
        }
        val m2 = TETHERED_PATTERN.matcher(dumpsys)
        if (m2.find()) {
            lastIface = m2.group(1)
            return lastIface
        }
        if (state == STATE_ENABLED) {
            val known = lastIface
            if (known != null && File("/sys/class/net/$known").exists()) return known
        }
        val guessed = runCatching {
            File("/sys/class/net").listFiles()?.map { it.name }?.firstOrNull {
                it.startsWith("wlan") || it.startsWith("ap") || it.startsWith("softap")
            }
        }.getOrNull()
        if (guessed != null) lastIface = guessed
        return lastIface
    }

    private fun getClients(iface: String?): List<HotspotClient> {
        val now = SystemClock.elapsedRealtime()
        if (now - lastClientsAt < 1500 && clients.isNotEmpty()) return clients.values.toList()
        lastClientsAt = now
        val fromDump = parseTetheringClients(dumpsysTethering())
        val fromArp = if (iface != null) parseArp(iface) else emptyList()
        val merged = LinkedHashMap<String, HotspotClient>()
        for (c in fromDump) merged[c.mac.uppercase()] = c
        for (c in fromArp) {
            val key = c.mac.uppercase()
            val existing = merged[key]
            merged[key] = existing?.copy(ip = existing.ip ?: c.ip) ?: c
        }
        for ((mac, c) in clients) {
            val key = mac.uppercase()
            val existing = merged[key]
            merged[key] = existing?.copy(name = existing.name ?: c.name) ?: c
        }
        clients.clear()
        merged.forEach { (mac, c) -> clients[mac] = c.copy(mac = mac) }
        return clients.values.toList()
    }

    private fun parseTetheringClients(text: String): List<HotspotClient> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<HotspotClient>()
        for (line in text.lineSequence()) {
            if (!line.contains("ClientInfo{")) continue
            val mac = extract(line, "macAddress='")
            if (mac.isNullOrBlank()) continue
            out += HotspotClient(
                mac = mac,
                ip = extract(line, "ipAddress='"),
                name = extract(line, "hostname='")?.takeIf { it.isNotBlank() && it != "null" },
            )
        }
        return out
    }

    private fun parseArp(iface: String): List<HotspotClient> {
        val text = runCommand("/system/bin/ip", "neigh", "show", "dev", iface)
        val out = mutableListOf<HotspotClient>()
        for (line in text.lineSequence()) {
            val m = ARP_PATTERN.matcher(line)
            if (m.find()) {
                val mac = m.group(2) ?: continue
                out += HotspotClient(mac = mac, ip = m.group(1))
            }
        }
        return out
    }

    private fun extract(line: String, prefix: String): String? {
        val start = line.indexOf(prefix)
        if (start < 0) return null
        val from = start + prefix.length
        val end = line.indexOf('\'', from)
        if (end < 0) return null
        return line.substring(from, end)
    }

    private fun dumpsysTethering(): String {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDumpsysAt < 1500) return lastDumpsys
        lastDumpsysAt = now
        lastDumpsys = runCommand("/system/bin/dumpsys", "tethering")
        return lastDumpsys
    }

    private fun runCommand(vararg command: String): String {
        return runCatching {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val bytes = ByteArray(512 * 1024)
            var total = 0
            val reader = Thread {
                try {
                    val input = process.inputStream
                    while (total < bytes.size) {
                        val n = input.read(bytes, total, bytes.size - total)
                        if (n <= 0) break
                        total += n
                    }
                } catch (_: Throwable) {
                }
            }
            reader.start()
            if (!process.waitFor(4, TimeUnit.SECONDS)) process.destroyForcibly()
            reader.join(1500)
            String(bytes, 0, total, Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun getCounters(iface: String?): Pair<Long, Long> {
        if (iface == null) return 0L to 0L
        val dev = runCatching { File("/proc/net/dev").readLines() }.getOrNull() ?: return 0L to 0L
        for (line in dev) {
            val colon = line.indexOf(':')
            if (colon < 0) continue
            if (line.substring(0, colon).trim() != iface) continue
            val parts = line.substring(colon + 1).trim().split(Regex("\\s+"))
            val rx = parts.getOrNull(0)?.toLongOrNull() ?: 0L
            val tx = parts.getOrNull(8)?.toLongOrNull() ?: 0L
            return rx to tx
        }
        return 0L to 0L
    }

    private fun registerSoftApCallback() {
        val wm = wifiManager ?: return
        val iface = runCatching { Class.forName("android.net.wifi.WifiManager\$SoftApCallback") }.getOrNull() ?: return
        callbackInterface = iface
        val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), InvocationHandler { p, method, args ->
            when (method.name) {
                "onConnectedClientsChanged", "onConnectedClientsOrInfoChanged" -> {
                    val found = linkedSetOf<String>()
                    args?.forEach { scanClients(it, found) }
                    synchronized(clients) {
                        if (found.isEmpty()) clients.clear()
                        else clients.keys.retainAll(found)
                        found.forEach { mac -> if (!clients.containsKey(mac)) clients[mac] = HotspotClient(mac = mac) }
                    }
                    null
                }
                "onInfoChanged" -> {
                    val info = args?.firstOrNull()
                    if (info != null) {
                        runCatching {
                            val name = info.javaClass.getMethod("getInterfaceName").invoke(info) as? String
                            if (!name.isNullOrBlank()) lastIface = name
                        }
                    }
                    null
                }
                "onStateChanged" -> {
                    val value = args?.firstOrNull()
                    if (value is Number) {
                        lastState = value.toInt()
                        if (lastState == STATE_DISABLED || lastState == STATE_FAILED) clients.clear()
                    }
                    null
                }
                "equals" -> p === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(p)
                "toString" -> "HotspotTogglerSoftApCallback"
                else -> null
            }
        })
        runCatching {
            val method = wm.javaClass.methods.firstOrNull {
                it.name == "registerSoftApCallback" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Executor::class.java
            } ?: throw NoSuchMethodException("registerSoftApCallback")
            method.invoke(wm, Executor { it.run() }, proxy)
            synchronized(callbackLock) { apCallback = proxy }
            Log.i(TAG, "SoftApCallback registered")
        }.onFailure { Log.w(TAG, "SoftApCallback unavailable: ${it.message}") }
    }

    private fun scanClients(value: Any?, found: MutableSet<String>) {
        val obj = value ?: return
        when (obj) {
            is Map<*, *> -> obj.values.forEach { scanClients(it, found) }
            is Iterable<*> -> obj.forEach { scanClients(it, found) }
            is Array<*> -> obj.forEach { scanClients(it, found) }
            else -> {
                if (obj.javaClass.name.endsWith("WifiClient")) {
                    runCatching {
                        val mac = obj.javaClass.getMethod("getMacAddress").invoke(obj).toString().uppercase()
                        if (MAC_PATTERN.matcher(mac).matches()) found += mac
                    }
                }
            }
        }
    }

    private fun unregisterSoftApCallback() {
        val wm = wifiManager ?: return
        val iface = callbackInterface ?: return
        val proxy = synchronized(callbackLock) { apCallback } ?: return
        runCatching {
            val method = wm.javaClass.methods.firstOrNull {
                it.name == "unregisterSoftApCallback" && it.parameterTypes.size == 1
            } ?: return@runCatching
            method.invoke(wm, proxy)
        }
        synchronized(callbackLock) { apCallback = null }
    }

    private fun service(name: String, interfaceName: String): Any? = runCatching {
        HiddenApiBypass.addHiddenApiExemptions("L")
        val manager = Class.forName("android.os.ServiceManager")
        val binder = manager.getMethod("getService", String::class.java).invoke(null, name) as? IBinder ?: return null
        val stub = Class.forName("$interfaceName\$Stub")
        stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
    }.getOrNull()

    companion object {
        private const val TAG = "HotspotOps"
        private const val STATE_DISABLED = 11
        private const val STATE_ENABLING = 12
        private const val STATE_ENABLED = 13
        private const val STATE_FAILED = 14
        private const val TETHER_OK = 0
        private val IFACE_PATTERN = Pattern.compile("(?m)^\\s*([A-Za-z0-9_.-]+):\\s*\\[TETHERING_WIFI\\]")
        private val TETHERED_PATTERN = Pattern.compile("(?m)^\\s*([A-Za-z0-9_.-]+)\\s+-\\s+TetheredState")
        private val ARP_PATTERN = Pattern.compile("^([0-9a-fA-F:.]+)\\s+dev\\s+\\S+\\s+lladdr\\s+([0-9a-fA-F:]{17})")
        private val MAC_PATTERN = Pattern.compile("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")
    }
}
