package com.joeykot.dictate.settings

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureApiKeyStore(
    context: Context,
    private val preferences: SharedPreferences,
) {
    private val legacyPreferences = context.getSharedPreferences(
        LEGACY_PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun get(): String {
        val primaryEncoded = preferences.getString(KEY_VALUE, null)
        val migrationComplete = preferences.getBoolean(KEY_MIGRATION_COMPLETE, false)
        val legacyEncoded = if (primaryEncoded == null && !migrationComplete) {
            legacyPreferences.getString(LEGACY_KEY_VALUE, null)
        } else {
            null
        }
        val selection = selectStoredApiKey(
            primaryEncoded = primaryEncoded,
            migrationComplete = migrationComplete,
            legacyEncoded = legacyEncoded,
        )
        return when (selection) {
            is StoredApiKeySelection.Primary -> decryptOrClear(selection.encoded) {
                preferences.edit().remove(KEY_VALUE).apply()
            }
            is StoredApiKeySelection.Legacy -> decryptOrClear(selection.encoded) {
                legacyPreferences.edit().remove(LEGACY_KEY_VALUE).apply()
            }
            StoredApiKeySelection.Empty -> ""
        }
    }

    fun stage(editor: SharedPreferences.Editor, value: String) {
        val encoded = encrypt(value)
        editor.putBoolean(KEY_MIGRATION_COMPLETE, true)
        if (encoded == null) {
            editor.remove(KEY_VALUE)
        } else {
            editor.putString(KEY_VALUE, encoded)
        }
    }

    fun clearLegacyValue() {
        if (legacyPreferences.contains(LEGACY_KEY_VALUE)) {
            legacyPreferences.edit().remove(LEGACY_KEY_VALUE).apply()
        }
    }

    private fun encrypt(value: String): String? {
        if (value.isEmpty()) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decryptOrClear(encoded: String, clear: () -> Unit): String {
        return try {
            val parts = encoded.split(':', limit = 2)
            require(parts.size == 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            clear()
            ""
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_VALUE = "secure.api_key_ciphertext"
        const val KEY_MIGRATION_COMPLETE = "secure.api_key_migrated"
        const val LEGACY_PREFS_NAME = "secure_settings"
        const val LEGACY_KEY_VALUE = "api_key_ciphertext"
        const val KEY_ALIAS = "dictate_api_key_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
