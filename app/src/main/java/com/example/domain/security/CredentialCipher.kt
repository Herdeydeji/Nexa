package com.example.domain.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

sealed interface DecryptResult {
    data object Empty : DecryptResult
    data class Success(val plainText: String) : DecryptResult
    data class KeyInvalidated(val reason: String) : DecryptResult
}

interface CredentialCipher {
    fun encrypt(plainText: String): String
    fun decrypt(cipherPayload: String): String
    fun decryptSafely(cipherPayload: String): DecryptResult {
        if (cipherPayload.isEmpty()) return DecryptResult.Empty
        val plain = decrypt(cipherPayload)
        return if (plain.isEmpty()) {
            DecryptResult.KeyInvalidated("Stored credential could not be decrypted.")
        } else {
            DecryptResult.Success(plain)
        }
    }
}

class AndroidKeyStoreCredentialCipher(
    private val keyAlias: String = "nexa_provider_credential_key_v1"
) : CredentialCipher {

    override fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val buffer = ByteBuffer.allocate(4 + iv.size + encrypted.size)
        buffer.putInt(iv.size)
        buffer.put(iv)
        buffer.put(encrypted)
        return Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
    }

    override fun decrypt(cipherPayload: String): String {
        return when (val result = decryptSafely(cipherPayload)) {
            is DecryptResult.Success -> result.plainText
            else -> ""
        }
    }

    override fun decryptSafely(cipherPayload: String): DecryptResult {
        if (cipherPayload.isEmpty()) return DecryptResult.Empty
        return try {
            val bytes = Base64.decode(cipherPayload, Base64.NO_WRAP)
            if (bytes.size < 8) {
                return DecryptResult.KeyInvalidated("Encrypted credential payload is corrupted.")
            }
            val buffer = ByteBuffer.wrap(bytes)
            val ivLength = buffer.int
            if (ivLength <= 0 || ivLength > 32 || buffer.remaining() <= 0) {
                return DecryptResult.KeyInvalidated("Encrypted credential metadata is invalid.")
            }
            val iv = ByteArray(ivLength)
            buffer.get(iv)
            val encrypted = ByteArray(buffer.remaining())
            buffer.get(encrypted)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
            DecryptResult.Success(String(cipher.doFinal(encrypted), Charsets.UTF_8))
        } catch (_: Exception) {
            DecryptResult.KeyInvalidated(
                "Android Keystore key is missing or invalidated. Please re-enter your API key."
            )
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
