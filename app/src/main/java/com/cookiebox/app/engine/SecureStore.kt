package com.cookiebox.app.engine

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 通用本地加密：Android Keystore 中不可导出的 AES-256-GCM 密钥。
 * 密文格式："v1:" + Base64(12 字节 IV + 密文 + 16 字节 Tag)
 * 加解密失败一律返回 null，由调用方决定降级策略，绝不抛出到 UI。
 */
class SecureStore(private val keyAlias: String) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFIX = "v1:"
        private const val IV_LEN = 12
        private const val TAG_BITS = 128

        fun isCiphertext(s: String?): Boolean = s != null && s.startsWith(PREFIX)
    }

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        PREFIX + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    fun decrypt(enc: String): String? = try {
        require(enc.startsWith(PREFIX))
        val data = Base64.decode(enc.substring(PREFIX.length), Base64.NO_WRAP)
        require(data.size > IV_LEN)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, data.copyOfRange(0, IV_LEN)))
        String(cipher.doFinal(data, IV_LEN, data.size - IV_LEN), Charsets.UTF_8)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    /** 加密并立即回读校验，确保写进去的东西一定能解出来 */
    fun encryptVerified(plain: String): String? {
        val enc = encrypt(plain) ?: return null
        return if (decrypt(enc) == plain) enc else null
    }
}
