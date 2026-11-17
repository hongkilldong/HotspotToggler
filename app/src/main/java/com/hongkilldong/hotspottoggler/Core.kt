package com.hongkilldong.hotspottoggler

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object AppTheme {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"
    val MODES = listOf(SYSTEM, LIGHT, DARK)

    fun get(context: Context): String = Prefs(context).theme.let { if (it in MODES) it else SYSTEM }

    fun set(context: Context, mode: String) {
        Prefs(context).theme = if (mode in MODES) mode else SYSTEM
    }

    fun wrap(base: Context): Context {
        val mode = get(base)
        val night = when (mode) {
            LIGHT -> Configuration.UI_MODE_NIGHT_NO
            DARK -> Configuration.UI_MODE_NIGHT_YES
            else -> return base
        }
        val cfg = Configuration(base.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(cfg)
    }

    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}

object AppLog {
    private const val TAG = "HotspotToggler"
    private const val MAX_LINES = 600
    private const val MAX_FILE = 128 * 1024L
    private val lines = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "hotspot-toggler.log")
        file = f
        runCatching {
            if (f.length() > MAX_FILE) f.writeText("")
            if (f.exists()) {
                f.readLines().takeLast(MAX_LINES).forEach { lines.addLast(it.take(2000)) }
            }
        }
    }

    @Synchronized
    fun i(message: String) {
        write("I", message)
        Log.i(TAG, message)
    }

    @Synchronized
    fun e(message: String, error: Throwable? = null) {
        val text = if (error == null) message else "$message: ${error.javaClass.simpleName}: ${error.message ?: ""}"
        write("E", text)
        Log.e(TAG, text, error)
    }

    private fun write(level: String, message: String) {
        val line = "${stamp.format(Date())} $level $message"
        lines.addLast(line.take(2000))
        while (lines.size > MAX_LINES) lines.removeFirst()
        val f = file ?: return
        runCatching {
            f.appendText(line + "\n")
            if (f.length() > MAX_FILE) {
                val tail = f.readLines().takeLast(MAX_LINES / 2)
                f.writeText(tail.joinToString("\n", postfix = "\n"))
            }
        }
    }

    @Synchronized
    fun tail(maxChars: Int = 24000): String {
        val all = lines.joinToString("\n")
        return if (all.length <= maxChars) all else all.substring(all.length - maxChars)
    }

    @Synchronized
    fun clear() {
        lines.clear()
        runCatching { file?.writeText("") }
    }
}

object Fmt {
    fun rate(bytesPerSecond: Double): String {
        val bits = bytesPerSecond.coerceAtLeast(0.0) * 8.0
        return when {
            bits >= 1_000_000_000 -> String.format(Locale.getDefault(), "%.2f Gbit/s", bits / 1_000_000_000)
            bits >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f Mbit/s", bits / 1_000_000)
            bits >= 1_000 -> String.format(Locale.getDefault(), "%.0f Kbit/s", bits / 1_000)
            else -> String.format(Locale.getDefault(), "%.0f bit/s", bits)
        }
    }

    fun bytes(value: Long): String {
        if (value < 1024) return "$value B"
        val kb = value / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
        return String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0)
    }

    fun mac(value: String?): String = value.orEmpty().uppercase(Locale.US)

    fun delay(seconds: Int, context: android.content.Context): String =
        if (seconds < 60) context.getString(R.string.time_seconds, seconds)
        else context.getString(R.string.time_minutes, seconds / 60)

    fun deviceType(type: Int): String = when (type) {
        android.bluetooth.BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"
        android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE -> "LE"
        android.bluetooth.BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual"
        else -> "BT"
    }
}
