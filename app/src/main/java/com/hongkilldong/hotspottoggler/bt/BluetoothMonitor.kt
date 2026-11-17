package com.hongkilldong.hotspottoggler.bt

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import com.hongkilldong.hotspottoggler.AppLog
import java.util.concurrent.ConcurrentHashMap

class BluetoothMonitor(
    private val context: Context,
    private val onAcl: (BluetoothDevice, Boolean) -> Unit,
    private val onBondLost: (String) -> Unit,
    private val onAdapter: (Boolean) -> Unit,
) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val mac = device?.address ?: return
                    BtDevices.updateKnown(mac, true)
                    if (mac == com.hongkilldong.hotspottoggler.Prefs(context).selectedMac) {
                        AppLog.i("ACL connected: $mac")
                        onAcl(device, true)
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val mac = device?.address ?: return
                    BtDevices.updateKnown(mac, false)
                    if (mac == com.hongkilldong.hotspottoggler.Prefs(context).selectedMac) {
                        AppLog.i("ACL disconnected: $mac")
                        onAcl(device, false)
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val mac = device?.address ?: return
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    if (state == BluetoothDevice.BOND_NONE) {
                        AppLog.i("bond removed: $mac")
                        onBondLost(mac)
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (state == BluetoothAdapter.STATE_ON || state == BluetoothAdapter.STATE_OFF) {
                        BtDevices.onAdapterChanged(state == BluetoothAdapter.STATE_ON)
                        onAdapter(state == BluetoothAdapter.STATE_ON)
                    }
                }
            }
        }
    }

    fun start(): Boolean {
        if (registered) return true
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
            registered = true
            true
        }.onFailure { AppLog.e("cannot register Bluetooth receiver", it) }.getOrDefault(false)
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { context.unregisterReceiver(receiver) }
    }

    fun adapterOn(): Boolean = runCatching { adapter?.isEnabled == true }.getOrDefault(false)

    fun isConnected(mac: String): Boolean = BtDevices.isConnected(context, mac)
}

object BtDevices {
    private data class CacheEntry(val at: Long, val connected: Boolean)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val lastLogged = ConcurrentHashMap<String, Boolean>()
    private val proxies = ConcurrentHashMap<Int, BluetoothProfile>()
    private val binding = ConcurrentHashMap.newKeySet<Int>()

    /**
     * Android 16's public [BluetoothDevice.isConnected] is not present in the Bluetooth APEX on
     * many devices (NoSuchMethodError), so presence is built from profile connection lists plus
     * ACL broadcasts. These profiles cover headphones, car kits, speakers, keyboards and BLE.
     */
    private val profileIds = intArrayOf(
        BluetoothProfile.HEADSET,
        BluetoothProfile.A2DP,
        BluetoothProfile.HEARING_AID,
        BluetoothProfile.LE_AUDIO,
    )

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            proxies[profile] = proxy
            binding.remove(profile)
        }

        override fun onServiceDisconnected(profile: Int) {
            proxies.remove(profile)
            binding.remove(profile)
        }
    }

    fun bonded(context: Context): List<BluetoothDevice> {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return emptyList()
        val adapter = manager.adapter ?: return emptyList()
        return runCatching { adapter.bondedDevices?.toList().orEmpty() }
            .onFailure { AppLog.e("cannot read bonded devices", it) }
            .getOrDefault(emptyList())
            .sortedWith(compareBy<BluetoothDevice> { deviceName(it).lowercase() })
    }

    fun deviceName(device: BluetoothDevice): String =
        runCatching { device.alias?.takeIf { it.isNotBlank() } ?: device.name?.takeIf { it.isNotBlank() } }
            .getOrNull() ?: device.address

    fun updateKnown(mac: String, connected: Boolean) {
        cache[mac.uppercase()] = CacheEntry(SystemClock.elapsedRealtime(), connected)
    }

    fun onAdapterChanged(on: Boolean) {
        if (!on) cache.clear()
    }

    fun isConnected(context: Context, mac: String): Boolean {
        val key = mac.uppercase()
        val now = SystemClock.elapsedRealtime()
        cache[key]?.let { if (now - it.at < if (it.connected) 5000 else 1000) return it.connected }
        val connected = queryConnected(context, key)
        cache[key] = CacheEntry(now, connected)
        if (lastLogged.put(key, connected) != connected) AppLog.i("isConnected $key: $connected")
        return connected
    }

    private fun queryConnected(context: Context, mac: String): Boolean {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return false
        val adapter = manager.adapter ?: return false
        if (runCatching { adapter.isEnabled }.getOrDefault(false).not()) return false

        bindProfiles(context, adapter)

        if (gattConnected(manager, BluetoothProfile.GATT, mac) ||
            gattConnected(manager, BluetoothProfile.GATT_SERVER, mac)
        ) {
            return true
        }

        for ((profile, proxy) in proxies) {
            val connected = runCatching {
                proxy.connectedDevices.orEmpty().any { it.address.equals(mac, ignoreCase = true) }
            }.onFailure { AppLog.e("profile $profile connection query failed", it) }.getOrDefault(false)
            if (connected) return true
        }
        return false
    }

    private fun gattConnected(manager: BluetoothManager, profile: Int, mac: String): Boolean = runCatching {
        manager.getConnectedDevices(profile).orEmpty().any { it.address.equals(mac, ignoreCase = true) }
    }.getOrDefault(false)

    private fun bindProfiles(context: Context, adapter: BluetoothAdapter) {
        val app = context.applicationContext
        for (profile in profileIds) {
            if (proxies.containsKey(profile)) continue
            if (!binding.add(profile)) continue
            val requested = runCatching {
                adapter.getProfileProxy(app, profileListener, profile)
            }.getOrDefault(false)
            if (!requested) binding.remove(profile)
        }
    }

    fun isBonded(context: Context, mac: String): Boolean {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return false
        val adapter = manager.adapter ?: return false
        return runCatching {
            adapter.bondedDevices?.any { it.address.equals(mac, ignoreCase = true) } == true
        }.getOrDefault(false)
    }
}
