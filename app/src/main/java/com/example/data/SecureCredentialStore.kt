package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts sensitive API credentials using Android Keystore (AES-256-GCM) when available,
 * and safely migrates any legacy plaintext keys out of standard SharedPreferences.
 */
class SecureCredentialStore(context: Context) {

    companion object {
        private const val TAG = "SecureCredentialStore"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "SiriAiSecureKeyAlias_v1"
        private const val PREF_FILE = "siri_secure_credentials"
        private const val KEY_API_CIPHERTEXT = "enc_api_key_v1"
        private const val KEY_API_IV = "enc_api_iv_v1"
        private const val KEY_FALLBACK_OBF = "enc_api_fallback_v1"
        private const val LEGACY_PLAIN_KEY = "custom_api_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }

    private val securePrefs: SharedPreferences =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    fun saveApiKey(rawKey: String) {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) {
            clearApiKey()
            return
        }
        try {
            val secretKey = getOrCreateSecretKey()
            if (secretKey != null) {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                val ciphertext = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
                val iv = cipher.iv
                securePrefs.edit()
                    .putString(KEY_API_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                    .putString(KEY_API_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .remove(KEY_FALLBACK_OBF)
                    .apply()
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "AndroidKeyStore unavailable in current runtime; storing fallback encoded credential", e)
        }

        // Local JVM test / Robolectric fallback without AndroidKeyStore hardware provider
        val encoded = Base64.encodeToString(trimmed.toByteArray(Charsets.UTF_8).reversedArray(), Base64.NO_WRAP)
        securePrefs.edit()
            .putString(KEY_FALLBACK_OBF, encoded)
            .remove(KEY_API_CIPHERTEXT)
            .remove(KEY_API_IV)
            .apply()
    }

    fun loadApiKey(): String {
        val cipherB64 = securePrefs.getString(KEY_API_CIPHERTEXT, null)
        val ivB64 = securePrefs.getString(KEY_API_IV, null)
        if (!cipherB64.isNullOrBlank() && !ivB64.isNullOrBlank()) {
            try {
                val secretKey = getOrCreateSecretKey()
                if (secretKey != null) {
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    val spec = GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP))
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
                    val plain = cipher.doFinal(Base64.decode(cipherB64, Base64.NO_WRAP))
                    return String(plain, Charsets.UTF_8)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decrypt stored API key", e)
            }
        }

        val fallback = securePrefs.getString(KEY_FALLBACK_OBF, null)
        if (!fallback.isNullOrBlank()) {
            return try {
                String(Base64.decode(fallback, Base64.NO_WRAP).reversedArray(), Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
        }
        return ""
    }

    fun clearApiKey() {
        securePrefs.edit()
            .remove(KEY_API_CIPHERTEXT)
            .remove(KEY_API_IV)
            .remove(KEY_FALLBACK_OBF)
            .apply()
    }

    /**
     * Migrates any legacy unencrypted key from standard prefs into [SecureCredentialStore]
     * and deletes it from the plaintext preferences file.
     */
    fun migrateLegacyPlaintextKey(legacyPrefs: SharedPreferences): String {
        val legacyKey = legacyPrefs.getString(LEGACY_PLAIN_KEY, null)?.trim().orEmpty()
        if (legacyKey.isNotEmpty()) {
            saveApiKey(legacyKey)
            legacyPrefs.edit().remove(LEGACY_PLAIN_KEY).apply()
            return legacyKey
        }
        return loadApiKey()
    }

    private fun getOrCreateSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
            keyStore.load(null)
            val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) return existing

            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        } catch (_: Exception) {
            null
        }
    }
}
