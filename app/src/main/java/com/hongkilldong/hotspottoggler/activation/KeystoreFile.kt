package com.hongkilldong.hotspottoggler.activation

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.flyfishxu.kadb.cert.KadbPrivateKeyStore
import com.hongkilldong.hotspottoggler.AppLog
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** ADB private key at rest: AES/GCM with a non-exportable Android Keystore key. */
class KeystoreFile(context: Context) : KadbPrivateKeyStore {
    private val file = File(context.noBackupFilesDir, "adb-identity.bin")
    private val alias = "hotspot-toggler.adb.v1"

    companion object {
        private const val VERSION: Byte = 1
        private const val IV_BYTES = 12
    }

    @Synchronized
    override fun readPrivateKeyPem(): ByteArray? {
        if (!file.exists()) return null
        return runCatching {
            val data = file.readBytes()
            require(data.size > 13 && data[0] == VERSION) { "invalid identity file" }
            val iv = data.copyOfRange(1, 13)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.doFinal(data.copyOfRange(13, data.size))
        }.onFailure {
            AppLog.e("cannot read ADB identity", it)
            runCatching { file.delete() }
        }.getOrNull()
    }

    @Synchronized
    override fun writePrivateKeyPemAtomic(privateKeyPem: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { out ->
            out.write(byteArrayOf(VERSION))
            out.write(cipher.iv)
            out.write(cipher.doFinal(privateKeyPem))
            out.fd.sync()
        }
        check(tmp.renameTo(file)) { "cannot store ADB identity" }
    }

    @Synchronized
    override fun clear() {
        runCatching { file.delete() }
        runCatching { File(file.parentFile, file.name + ".tmp").delete() }
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.deleteEntry(alias)
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }
}
