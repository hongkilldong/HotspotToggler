package com.hongkilldong.hotspottoggler.activation

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.hongkilldong.hotspottoggler.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque

data class AdbPorts(
    val pairing: Set<Int> = emptySet(),
    val connect: Set<Int> = emptySet(),
)

/** mDNS discovery of this phone's own wireless-debugging ports. Hosts are ignored; we always use loopback. */
class AdbMdns(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(NsdManager::class.java)
    private val _state = MutableStateFlow(AdbPorts())
    val state: StateFlow<AdbPorts> = _state.asStateFlow()
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val servicePorts = LinkedHashMap<String, Pair<Type, Int>>()
    private val callbacks = mutableMapOf<String, NsdManager.ServiceInfoCallback>()
    private val queue = ArrayDeque<Pair<Type, NsdServiceInfo>>()
    private var resolving = false
    private var started = false

    private enum class Type(val dns: String) {
        PAIRING("_adb-tls-pairing._tcp"),
        CONNECT("_adb-tls-connect._tcp"),
    }

    fun start() {
        if (started) return
        started = true
        Type.entries.forEach { type ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    AppLog.e("mDNS start failed $serviceType code=$errorCode")
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onServiceFound(info: NsdServiceInfo) {
                    AppLog.i("mDNS found ${type.name.lowercase()} ${info.serviceName}")
                    if (Build.VERSION.SDK_INT >= 34) {
                        registerModern(type, info)
                    } else {
                        queue.offer(type to info)
                        pumpLegacy()
                    }
                }
                override fun onServiceLost(info: NsdServiceInfo) {
                    val key = key(type, info)
                    servicePorts.remove(key)
                    unregisterModern(key)
                    publish()
                }
            }
            listeners += listener
            runCatching { nsd.discoverServices(type.dns, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onFailure { AppLog.e("mDNS discover ${type.dns}", it) }
        }
    }

    suspend fun awaitPairPort(timeoutMs: Long): Int? = withTimeoutOrNull(timeoutMs) {
        state.mapNotNull { it.pairing.firstOrNull() }.first()
    }

    suspend fun awaitConnectPort(timeoutMs: Long): Int? = withTimeoutOrNull(timeoutMs) {
        state.mapNotNull { it.connect.firstOrNull() }.first()
    }

    private fun registerModern(type: Type, info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT < 34) return
        val key = key(type, info)
        if (callbacks.containsKey(key)) return
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                AppLog.e("mDNS resolve failed $key code=$errorCode")
                callbacks.remove(key)
            }
            override fun onServiceInfoCallbackUnregistered() {
                callbacks.remove(key)
            }
            override fun onServiceLost() {
                servicePorts.remove(key)
                callbacks.remove(key)
                publish()
            }
            override fun onServiceUpdated(updated: NsdServiceInfo) {
                servicePorts[key] = type to updated.port
                publish()
            }
        }
        callbacks[key] = callback
        runCatching {
            nsd.registerServiceInfoCallback(info, app.mainExecutor, callback)
        }.onFailure {
            callbacks.remove(key)
            AppLog.e("mDNS register callback $key", it)
        }
    }

    @Suppress("DEPRECATION")
    private fun pumpLegacy() {
        if (resolving || queue.isEmpty() || Build.VERSION.SDK_INT >= 34) return
        val (type, info) = queue.removeFirst()
        resolving = true
        val key = key(type, info)
        runCatching {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    AppLog.e("mDNS resolve failed $key code=$errorCode")
                    resolving = false
                    pumpLegacy()
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    servicePorts[key] = type to serviceInfo.port
                    resolving = false
                    publish()
                    pumpLegacy()
                }
            })
        }.onFailure {
            resolving = false
            AppLog.e("mDNS resolve $key", it)
            pumpLegacy()
        }
    }

    private fun unregisterModern(key: String) {
        if (Build.VERSION.SDK_INT < 34) return
        val callback = callbacks.remove(key) ?: return
        runCatching { nsd.unregisterServiceInfoCallback(callback) }
    }

    private fun publish() {
        val pairing = servicePorts.values.filter { it.first == Type.PAIRING }.map { it.second }.filter { it > 0 }.toSet()
        val connect = servicePorts.values.filter { it.first == Type.CONNECT }.map { it.second }.filter { it > 0 }.toSet()
        _state.value = AdbPorts(pairing, connect)
    }

    private fun key(type: Type, info: NsdServiceInfo): String = "${type.name}:${info.serviceName}"

    override fun close() {
        started = false
        listeners.toList().forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        listeners.clear()
        if (Build.VERSION.SDK_INT >= 34) {
            callbacks.values.toList().forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        }
        callbacks.clear()
        queue.clear()
        servicePorts.clear()
        _state.value = AdbPorts()
    }
}
