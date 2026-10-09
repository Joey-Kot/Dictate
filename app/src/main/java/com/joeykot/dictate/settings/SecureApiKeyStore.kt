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

    /** The post-processing key has no legacy fallback and never replaces the transcription key. */
    fun getPostProcessing(): String {
        val encoded = preferences.getString(KEY_POST_PROCESSING_VALUE, null) ?: return ""
        return decryptOrClear(encoded) {
            preferences.edit().remove(KEY_POST_PROCESSING_VALUE).apply()
        }
    }

    fun stagePostProcessing(editor: SharedPreferences.Editor, value: String) {
        val encoded = encrypt(value)
        if (encoded == null) {
            editor.remove(KEY_POST_PROCESSING_VALUE)
        } else {
            editor.putString(KEY_POST_PROCESSING_VALUE, encoded)
        }
    }

    fun clearLegacyValue() {
        if (legacyPreferences.contains(LEGACY_KEY_VALUE)) {
            legacyPreferences.edit().remove(LEGACY_KEY_VALUE).apply()
        }
    }

    fun getPrompt(id: String): String {
        val key = KEY_PROMPT_PREFIX + id
        val encoded = preferences.getString(key, null) ?: return ""
        return decryptOrClear(encoded) { preferences.edit().remove(key).apply() }
    }

    /** Stage secrets and deletions in the same transaction as their prompt configurations. */
    fun stagePrompts(editor: SharedPreferences.Editor, ids: Set<String>, updates: Map<String, String>) {
        require(updates.keys.all { it in ids }) { "A prompt key must belong to a saved prompt" }
        val encrypted = updates.mapValues { encrypt(it.value.trim()) }
        preferences.all.keys.filter { it.startsWith(KEY_PROMPT_PREFIX) && it.removePrefix(KEY_PROMPT_PREFIX) !in ids }
            .forEach { editor.remove(it) }
        encrypted.forEach { (id, value) ->
            if (value == null) editor.remove(KEY_PROMPT_PREFIX + id)
            else editor.putString(KEY_PROMPT_PREFIX + id, value)
        }
    }

    /** Reads one workflow or remote-audio secret by its caller-defined identifier. */
    fun getAdvancedSecret(id: String): String {
        val key = advancedSecretKey(id)
        val encoded = advancedEncryptedValueOrClear(key) ?: return ""
        return decryptOrClear(encoded) { preferences.edit().remove(key).apply() }
    }

    /**
     * Returns every stored Advanced Audio API secret. IDs are Base64URL-encoded
     * in preference keys, so valid workflow IDs are not constrained by the
     * SharedPreferences key namespace.
     */
    fun getAdvancedSecrets(): Map<String, String> {
        val keys = preferences.all.keys.filter { it.startsWith(KEY_ADVANCED_SECRET_PREFIX) }
        return buildMap {
            keys.forEach { key ->
                val id = decodeAdvancedSecretId(key)
                if (id == null) {
                    preferences.edit().remove(key).apply()
                    return@forEach
                }
                val encoded = advancedEncryptedValueOrClear(key) ?: return@forEach
                val value = decryptOrClear(encoded) { preferences.edit().remove(key).apply() }
                if (value.isNotEmpty()) put(id, value)
            }
        }
    }

    /**
     * Replaces the Advanced Audio API secret namespace in the same preference
     * transaction as its public configuration. Empty values remove their IDs.
     */
    fun stageAdvancedSecrets(editor: SharedPreferences.Editor, values: Map<String, String>) {
        require(values.keys.all { it.isNotEmpty() }) { "An Advanced Audio API secret ID cannot be empty" }
        val encrypted = values.mapValues { encrypt(it.value) }
        val ids = values.keys
        preferences.all.keys
            .filter { it.startsWith(KEY_ADVANCED_SECRET_PREFIX) }
            .filter { key -> decodeAdvancedSecretId(key) !in ids }
            .forEach { editor.remove(it) }
        encrypted.forEach { (id, value) ->
            val key = advancedSecretKey(id)
            if (value == null) editor.remove(key) else editor.putString(key, value)
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

    private fun advancedSecretKey(id: String): String =
        KEY_ADVANCED_SECRET_PREFIX + Base64.encodeToString(
            id.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

    private fun decodeAdvancedSecretId(key: String): String? = runCatching {
        require(key.startsWith(KEY_ADVANCED_SECRET_PREFIX))
        val encodedId = key.removePrefix(KEY_ADVANCED_SECRET_PREFIX)
        require(encodedId.isNotEmpty())
        val id = String(
            Base64.decode(encodedId, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
            Charsets.UTF_8,
        )
        require(id.isNotEmpty())
        // Ignore malformed or non-canonical foreign keys in our namespace.
        // This keeps arbitrary SharedPreferences corruption from being exposed
        // as a workflow secret identifier.
        require(advancedSecretKey(id) == key)
        id
    }.getOrNull()

    /**
     * A damaged value in the Advanced namespace must never prevent the legacy
     * transcription configuration from loading. It is safe to discard because
     * all valid values in this namespace are encrypted strings written here.
     */
    private fun advancedEncryptedValueOrClear(key: String): String? = try {
        preferences.getString(key, null)
    } catch (_: ClassCastException) {
        preferences.edit().remove(key).apply()
        null
    }

    private companion object {
        const val KEY_VALUE = "secure.api_key_ciphertext"
        const val KEY_POST_PROCESSING_VALUE = "secure.post_processing_api_key_ciphertext"
        const val KEY_PROMPT_PREFIX = "secure.prompt_api_key_ciphertext."
        const val KEY_ADVANCED_SECRET_PREFIX = "secure.advanced_audio_secret_ciphertext."
        const val KEY_MIGRATION_COMPLETE = "secure.api_key_migrated"
        const val LEGACY_PREFS_NAME = "secure_settings"
        const val LEGACY_KEY_VALUE = "api_key_ciphertext"
        const val KEY_ALIAS = "dictate_api_key_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
