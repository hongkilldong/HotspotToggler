package com.hongkilldong.hotspottoggler

import android.content.Context
import java.security.SecureRandom
import java.util.Locale

class Prefs(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("hotspot_toggler", Context.MODE_PRIVATE)

    var selectedMac: String?
        get() = p.getString(KEY_SELECTED_MAC, null)
        set(value) = p.edit().putString(KEY_SELECTED_MAC, value).apply()

    var selectedName: String?
        get() = p.getString(KEY_SELECTED_NAME, null)
        set(value) = p.edit().putString(KEY_SELECTED_NAME, value).apply()

    var serviceEnabled: Boolean
        get() = p.getBoolean(KEY_SERVICE_ENABLED, false)
        set(value) = p.edit().putBoolean(KEY_SERVICE_ENABLED, value).apply()

    var autoStartOnBoot: Boolean
        get() = p.getBoolean(KEY_AUTO_START_BOOT, true)
        set(value) = p.edit().putBoolean(KEY_AUTO_START_BOOT, value).apply()

    var autoOffOnDisconnect: Boolean
        get() = p.getBoolean(KEY_AUTO_OFF, true)
        set(value) = p.edit().putBoolean(KEY_AUTO_OFF, value).apply()

    var autoOffDelaySeconds: Int
        get() = p.getInt(KEY_AUTO_OFF_DELAY, 30).coerceIn(0, 120)
        set(value) = p.edit().putInt(KEY_AUTO_OFF_DELAY, value.coerceIn(0, 120)).apply()

    var hotspotOwnedByApp: Boolean
        get() = p.getBoolean(KEY_HOTSPOT_OWNED, false)
        set(value) = p.edit().putBoolean(KEY_HOTSPOT_OWNED, value).apply()

    var pendingHotspotStart: Boolean
        get() = p.getBoolean(KEY_PENDING_START, false)
        set(value) = p.edit().putBoolean(KEY_PENDING_START, value).apply()

    var paired: Boolean
        get() = p.getBoolean(KEY_PAIRED, false)
        set(value) = p.edit().putBoolean(KEY_PAIRED, value).apply()

    var helperSocket: String?
        get() = p.getString(KEY_HELPER_SOCKET, null)
        set(value) = p.edit().putString(KEY_HELPER_SOCKET, value).apply()

    var helperToken: String?
        get() = p.getString(KEY_HELPER_TOKEN, null)
        set(value) = p.edit().putString(KEY_HELPER_TOKEN, value).apply()

    var helperPort: Int
        get() = p.getInt(KEY_HELPER_PORT, 0)
        set(value) = p.edit().putInt(KEY_HELPER_PORT, value).apply()

    var helperVersion: Int
        get() = p.getInt(KEY_HELPER_VERSION, 0)
        set(value) = p.edit().putInt(KEY_HELPER_VERSION, value).apply()

    var lastConnectPort: Int
        get() = p.getInt(KEY_LAST_CONNECT_PORT, 0)
        set(value) = p.edit().putInt(KEY_LAST_CONNECT_PORT, value).apply()

    var theme: String
        get() = p.getString(KEY_THEME, AppTheme.SYSTEM) ?: AppTheme.SYSTEM
        set(value) = p.edit().putString(KEY_THEME, value).apply()

    fun ensureHelperIdentity(): Triple<String, String, Int> {
        val socket = helperSocket?.takeIf { it.isNotBlank() } ?: ("hotspot-toggler-" + randomHex(8)).also { helperSocket = it }
        val token = helperToken?.takeIf { it.isNotBlank() } ?: randomHex(24).also { helperToken = it }
        val port = helperPort.takeIf { it in 20000..65000 } ?: (20000 + SecureRandom().nextInt(30000)).also { helperPort = it }
        return Triple(socket, token, port)
    }

    fun newHelperIdentity(): Triple<String, String, Int> {
        helperSocket = "hotspot-toggler-" + randomHex(8)
        helperToken = randomHex(24)
        helperPort = 20000 + SecureRandom().nextInt(30000)
        helperVersion = 0
        return Triple(helperSocket!!, helperToken!!, helperPort)
    }

    fun clearHelperIdentity() {
        helperSocket = null
        helperToken = null
        helperPort = 0
        helperVersion = 0
    }

    fun clear() {
        p.edit().clear().apply()
    }

    private fun randomHex(bytes: Int): String {
        val data = ByteArray(bytes)
        SecureRandom().nextBytes(data)
        return data.joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xff) }
    }

    private companion object {
        const val KEY_SELECTED_MAC = "selected_mac"
        const val KEY_SELECTED_NAME = "selected_name"
        const val KEY_SERVICE_ENABLED = "service_enabled"
        const val KEY_AUTO_START_BOOT = "auto_start_boot"
        const val KEY_AUTO_OFF = "auto_off"
        const val KEY_AUTO_OFF_DELAY = "auto_off_delay"
        const val KEY_HOTSPOT_OWNED = "hotspot_owned"
        const val KEY_PENDING_START = "pending_start"
        const val KEY_PAIRED = "paired"
        const val KEY_HELPER_SOCKET = "helper_socket"
        const val KEY_HELPER_TOKEN = "helper_token"
        const val KEY_HELPER_PORT = "helper_port"
        const val KEY_HELPER_VERSION = "helper_version"
        const val KEY_LAST_CONNECT_PORT = "last_connect_port"
        const val KEY_THEME = "theme"
    }
}
