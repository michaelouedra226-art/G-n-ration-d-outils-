package com.example.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Abstraction for encrypting and decrypting sensitive user credentials (API keys)
 * at rest without hardcoding secrets or exposing plaintext values in Room/SQLite.
 */
interface SecretCipher {
    /** Encrypts a plaintext secret and returns a serialized Base64 payload (`iv || ciphertext`). */
    fun encrypt(plaintext: String): String

    /** Decrypts a payload previously created by [encrypt]. */
    fun decrypt(encodedPayload: String): String
}

/**
 * Hardware-backed Android Keystore AES-256-GCM implementation of [SecretCipher].
 *
 * Each encryption generates a fresh 96-bit random IV via the JCE provider and
 * authenticates the ciphertext with a 128-bit GCM authentication tag.
 */
class AndroidKeystoreCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : SecretCipher {

    override fun encrypt(plaintext: String): String {
        val secretKey = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val buffer = ByteBuffer.allocate(4 + iv.size + ciphertext.size)
        buffer.putInt(iv.size)
        buffer.put(iv)
        buffer.put(ciphertext)
        return Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
    }

    override fun decrypt(encodedPayload: String): String {
        val raw = Base64.decode(encodedPayload, Base64.NO_WRAP)
        val buffer = ByteBuffer.wrap(raw)
        val ivLength = buffer.int
        require(ivLength in 12..32) { "Invalid IV length in encrypted payload" }
        val iv = ByteArray(ivLength)
        buffer.get(iv)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)

        val secretKey = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existingEntry = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        if (existingEntry != null) {
            return existingEntry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_KEY_ALIAS = "forgeai_api_vault_aes256_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}

/**
 * Masks an API key for safe display in UI and diagnostics, never revealing the full secret.
 */
fun maskApiKey(rawKey: String): String {
    val trimmed = rawKey.trim()
    if (trimmed.length <= 8) return "••••••••"
    return "${trimmed.take(4)}••••••${trimmed.takeLast(4)}"
}
