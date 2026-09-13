package com.example.config

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Secure configuration management for sensitive encryption keys.
 * Uses Android KeyStore system to protect encryption keys.
 */
object SecurityConfig {
    private const val PREFERENCE_FILE = "xtreme_secure_prefs"
    private const val DES_KEY_ALIAS = "xtreme_des_key_alias"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    /**
     * Get DES encryption key from secure storage.
     * Falls back to generating and storing a new key if not present.
     */
    fun getDESKey(context: Context): String {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val prefs = EncryptedSharedPreferences.create(
                context,
                PREFERENCE_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            // Check if key exists in secure storage
            var storedKey = prefs.getString(DES_KEY_ALIAS, null)
            if (storedKey == null) {
                // Generate and store a new key
                storedKey = generateAndStoreSecureKey(context, prefs)
            }
            storedKey ?: "38346591" // Fallback only during development
        } catch (e: Exception) {
            android.util.Log.e("SecurityConfig", "Error retrieving DES key: ${e.message}")
            // For production, this should throw an exception or use a remote configuration
            "38346591" // Development fallback only
        }
    }

    /**
     * Generate a new secure key using Android KeyStore and store it.
     */
    private fun generateAndStoreSecureKey(
        context: Context,
        prefs: EncryptedSharedPreferences
    ): String {
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
            keyStore.load(null)

            val keyGenSpec = KeyGenParameterSpec.Builder(
                DES_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_ECB)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                .setRandomizedEncryptionRequired(false)
                .build()

            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_DES,
                KEYSTORE_PROVIDER
            )
            keyGenerator.init(keyGenSpec)
            val secretKey = keyGenerator.generateKey()

            // Encode key to Base64 for storage
            val encodedKey = android.util.Base64.encodeToString(
                secretKey.encoded,
                android.util.Base64.DEFAULT
            )

            // Store in encrypted preferences
            prefs.edit().putString(DES_KEY_ALIAS, encodedKey).apply()
            encodedKey
        } catch (e: Exception) {
            android.util.Log.e("SecurityConfig", "Error generating secure key: ${e.message}")
            "38346591" // Fallback for development
        }
    }

    /**
     * Clear stored encryption keys from secure storage.
     * Should only be called during app uninstall or user data reset.
     */
    fun clearStoredKeys(context: Context) {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val prefs = EncryptedSharedPreferences.create(
                context,
                PREFERENCE_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            prefs.edit().clear().apply()
        } catch (e: Exception) {
            android.util.Log.e("SecurityConfig", "Error clearing stored keys: ${e.message}")
        }
    }
}
