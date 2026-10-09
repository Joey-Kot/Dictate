package com.joeykot.dictate.settings

import android.content.Context
import com.joeykot.dictate.model.AdvancedAudioConfig
import com.joeykot.dictate.model.AdvancedRemoteAudioConfig
import com.joeykot.dictate.model.AppSettings
import com.joeykot.dictate.model.ProviderConfig
import com.joeykot.dictate.model.RemoteAudioCredentialIds
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AdvancedAudioSettingsRepositoryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        clearPreferences()
        TestAndroidKeyStore.install()
    }

    @After
    fun tearDown() {
        clearPreferences()
        TestAndroidKeyStore.remove()
    }

    @Test
    fun advancedConfigAndSecretsRoundTripWithoutExportingCredentials() {
        val repository = SettingsRepository(context)
        val config = AdvancedAudioConfig(
            enabled = true,
            workflowJson = fixture("advanced_audio/workflows/valid-v2-typed-request.json"),
            values = linkedMapOf("model" to "example-asr", "sample_rate" to "16000"),
            remoteAudio = AdvancedRemoteAudioConfig.S3Compatible(
                endpoint = "https://storage.example.test",
                region = "us-east-1",
                bucket = "private-audio",
                accessKeySecretId = "remote/key id",
                secretKeySecretId = "远程/secret:key",
                prefix = "dictate/",
                publicUrlBase = "https://media.example.test",
                presigned = true,
            ),
        )
        val secrets = linkedMapOf(
            "api_key" to "workflow-secret-value",
            "remote/key id" to "remote-access-value",
            "远程/secret:key" to "remote-secret-value",
        )

        background {
            repository.save(
                AppSettings(
                    provider = ProviderConfig(baseUrl = "https://asr.example.test/v1", model = "legacy-model"),
                    advancedAudio = config,
                ),
                apiKey = "legacy-secret-value",
                advancedAudioSecrets = secrets,
            )
        }

        assertEquals(config, SettingsRepository(context).get().advancedAudio)
        assertEquals(secrets, repository.runtime().advancedAudioSecrets)
        assertEquals("workflow-secret-value", repository.advancedAudioSecret("api_key"))
        assertFalse(repository.runtime().toString().contains("workflow-secret-value"))
        assertFalse(repository.runtime().toString().contains("storage.example.test"))
        assertFalse(preferences.all.toString().contains("workflow-secret-value"))
        assertFalse(preferences.all.toString().contains("remote-access-value"))
        assertFalse(preferences.all.keys.any { it.contains("workflow.api/key") || it.contains("远程/secret:key") })

        val exported = JSONObject(repository.exportJson())
        assertEquals(7, exported.getInt("schemaVersion"))
        val advanced = exported.getJSONObject("advancedAudio")
        assertTrue(advanced.getBoolean("enabled"))
        assertEquals(config.workflowJson, advanced.getString("workflow"))
        assertFalse(advanced.has("secrets"))
        assertEquals("remote/key id", advanced.getJSONObject("remoteAudio").getString("accessKeySecretId"))
        val exportText = exported.toString()
        assertFalse(exportText.contains("workflow-secret-value"))
        assertFalse(exportText.contains("remote-access-value"))
        assertFalse(exportText.contains("remote-secret-value"))

        val preview = repository.previewImport(exported.toString())
        assertEquals(config, preview.settings.advancedAudio)
        assertFalse(preview.hasApiKeys)
        background { repository.applyImport(preview, allowApiKey = false) }
        assertEquals(secrets, repository.advancedAudioSecrets())
    }

    @Test
    fun arbitrarySecretIdsAreEncryptedAndReplacingTheSnapshotRemovesStaleIds() {
        val repository = SettingsRepository(context)
        val config = AdvancedAudioConfig(
            workflowJson = "unfinished workflow text is retained as a draft",
            remoteAudio = AdvancedRemoteAudioConfig.WebDav(
                uploadBaseUrl = "https://dav.example.test/upload",
                usernameSecretId = "vendor/username:α",
                passwordSecretId = "vendor/password:β",
            ),
        )
        val first = mapOf("vendor/username:α" to "alice", "vendor/password:β" to "passphrase", "unused" to "old")
        background { repository.saveAdvancedAudio(config, first) }

        assertEquals(config, repository.get().advancedAudio)
        assertEquals(first, repository.advancedAudioSecrets())
        assertFalse(preferences.all.toString().contains("passphrase"))
        assertFalse(preferences.all.keys.any { it.contains("vendor/password") })

        background { repository.saveAdvancedAudio(config, mapOf("vendor/username:α" to "updated")) }
        assertEquals(mapOf("vendor/username:α" to "updated"), repository.advancedAudioSecrets())
        assertEquals("", repository.advancedAudioSecret("vendor/password:β"))

        background {
            repository.save(
                repository.get().copy(provider = ProviderConfig(baseUrl = "https://asr.example.test", model = "legacy")),
                apiKey = "",
            )
        }
        assertEquals(mapOf("vendor/username:α" to "updated"), repository.advancedAudioSecrets())
    }

    @Test
    fun internalRemoteCredentialReferencesRemainEncryptedAndAvailableAtRuntime() {
        val repository = SettingsRepository(context)
        val config = AdvancedAudioConfig(
            workflowJson = "unfinished workflow text is retained as a draft",
            remoteAudio = AdvancedRemoteAudioConfig.WebDav(
                uploadBaseUrl = "https://dav.example.test/upload",
                usernameSecretId = RemoteAudioCredentialIds.WEB_DAV_USERNAME,
                passwordSecretId = RemoteAudioCredentialIds.WEB_DAV_PASSWORD,
            ),
        )
        val credentials = mapOf(
            RemoteAudioCredentialIds.WEB_DAV_USERNAME to " alice ",
            RemoteAudioCredentialIds.WEB_DAV_PASSWORD to " passphrase ",
        )

        background { repository.saveAdvancedAudio(config, credentials) }

        assertEquals(config, repository.get().advancedAudio)
        assertEquals(credentials, repository.runtime().advancedAudioSecrets)
        assertFalse(preferences.all.toString().contains(" passphrase "))
        assertFalse(preferences.all.keys.any { it.contains(RemoteAudioCredentialIds.WEB_DAV_PASSWORD) })
    }

    @Test
    fun importedAdvancedSecretsRequireConfirmationBeforeAnyWrite() {
        val repository = SettingsRepository(context)
        val original = AdvancedAudioConfig(workflowJson = "original")
        background { repository.saveAdvancedAudio(original, mapOf("old" to "old-secret")) }
        val before = preferences.all.toMap()
        val imported = JSONObject(repository.exportJson()).apply {
            getJSONObject("advancedAudio").put("secrets", JSONObject().put("new/id", "new-secret"))
        }
        val preview = repository.previewImport(imported.toString())
        assertTrue(preview.hasApiKeys)
        assertEquals(mapOf("new/id" to "new-secret"), preview.advancedAudioSecrets)
        assertFalse(preview.toString().contains("new-secret"))

        assertThrows(IllegalArgumentException::class.java) {
            background { repository.applyImport(preview, allowApiKey = false) }
        }
        assertEquals(before, preferences.all)
        assertEquals(mapOf("old" to "old-secret"), repository.advancedAudioSecrets())

        background { repository.applyImport(preview, allowApiKey = true) }
        assertEquals(mapOf("new/id" to "new-secret"), repository.advancedAudioSecrets())
    }

    @Test
    fun explicitEmptyAdvancedSecretSnapshotAlsoRequiresConfirmation() {
        val repository = SettingsRepository(context)
        background {
            repository.saveAdvancedAudio(
                AdvancedAudioConfig(workflowJson = "original"),
                mapOf("old" to "old-secret"),
            )
        }
        val imported = JSONObject(repository.exportJson()).apply {
            getJSONObject("advancedAudio").put("secrets", JSONObject())
        }
        val preview = repository.previewImport(imported.toString())

        assertTrue(preview.hasAdvancedAudioSecrets)
        assertTrue(preview.hasApiKeys)
        assertThrows(IllegalArgumentException::class.java) {
            background { repository.applyImport(preview, allowApiKey = false) }
        }
        assertEquals(mapOf("old" to "old-secret"), repository.advancedAudioSecrets())

        background { repository.applyImport(preview, allowApiKey = true) }
        assertTrue(repository.advancedAudioSecrets().isEmpty())
    }

    @Test
    fun secretlessImportDoesNotBindExistingSecretsToChangedWorkflow() {
        val repository = SettingsRepository(context)
        background {
            repository.saveAdvancedAudio(
                AdvancedAudioConfig(workflowJson = "original workflow"),
                mapOf("shared-id" to "existing-secret"),
            )
        }
        val imported = JSONObject(repository.exportJson()).apply {
            getJSONObject("advancedAudio").put("workflow", "replacement workflow")
        }
        val preview = repository.previewImport(imported.toString())
        assertFalse(preview.hasApiKeys)

        background { repository.applyImport(preview, allowApiKey = false) }
        assertEquals("replacement workflow", repository.get().advancedAudio.workflowJson)
        assertTrue(repository.advancedAudioSecrets().isEmpty())
    }

    @Test
    fun schemasOneThroughSixKeepAdvancedAudioDisabled() {
        val repository = SettingsRepository(context)
        val exported = JSONObject(repository.exportJson())
        for (version in 1..6) {
            val legacy = JSONObject(exported.toString()).apply {
                put("schemaVersion", version)
                remove("advancedAudio")
                if (version < 5) {
                    getJSONObject("audioOutput").apply {
                        put("bitrateKbps", getInt("bitrateBps") / 1_000)
                        remove("bitrateBps")
                    }
                }
                if (version < 4) {
                    remove("postProcessing")
                    remove("promptIconAssets")
                }
                if (version == 1) remove("display")
            }
            assertEquals(AdvancedAudioConfig(), repository.previewImport(legacy.toString()).settings.advancedAudio)
        }
    }

    @Test
    fun enabledAdvancedAudioRequiresACompleteWorkflowValuesAndSecrets() {
        val repository = SettingsRepository(context)
        val base = AppSettings(provider = ProviderConfig(baseUrl = "https://asr.example.test", model = "legacy"))

        assertThrows(IllegalArgumentException::class.java) {
            background {
                repository.save(
                    base.copy(advancedAudio = AdvancedAudioConfig(enabled = true, workflowJson = "{")),
                    apiKey = "",
                    advancedAudioSecrets = emptyMap(),
                )
            }
        }

        val workflow = fixture("advanced_audio/workflows/valid-v2-typed-request.json")
        assertThrows(IllegalArgumentException::class.java) {
            background {
                repository.save(
                    base.copy(advancedAudio = AdvancedAudioConfig(enabled = true, workflowJson = workflow)),
                    apiKey = "",
                    advancedAudioSecrets = emptyMap(),
                )
            }
        }

        background {
            repository.save(
                base.copy(advancedAudio = AdvancedAudioConfig(enabled = true, workflowJson = workflow)),
                apiKey = "",
                advancedAudioSecrets = mapOf("api_key" to "workflow-secret"),
            )
        }
        assertTrue(repository.get().advancedAudio.enabled)
    }

    @Test
    fun disabledAdvancedDraftMayBeIncompleteButNeverStoresUrlUserInfo() {
        val repository = SettingsRepository(context)
        val incomplete = AdvancedAudioConfig(
            enabled = false,
            workflowJson = "{ incomplete draft",
            remoteAudio = AdvancedRemoteAudioConfig.WebDav(
                uploadBaseUrl = "not a URL yet",
                publicDownloadBaseUrl = "also incomplete",
            ),
        )
        background { repository.saveAdvancedAudio(incomplete, emptyMap()) }
        assertEquals(incomplete, repository.get().advancedAudio)

        val unsafe = incomplete.copy(
            remoteAudio = AdvancedRemoteAudioConfig.WebDav(
                uploadBaseUrl = "https://alice:password@dav.example.test/upload",
                publicDownloadBaseUrl = "https://public.example.test/audio",
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            background { repository.saveAdvancedAudio(unsafe, emptyMap()) }
        }
        assertEquals(incomplete, repository.get().advancedAudio)
    }

    @Test
    fun enabledImportUsesItsOwnAdvancedSecretSnapshotAndRejectsUrlUserInfo() {
        val repository = SettingsRepository(context)
        val source = SettingsRepository(context)
        val workflow = fixture("advanced_audio/workflows/valid-v2-typed-request.json")
        val base = AppSettings(
            provider = ProviderConfig(baseUrl = "https://asr.example.test", model = "legacy"),
            advancedAudio = AdvancedAudioConfig(enabled = true, workflowJson = workflow),
        )
        background {
            source.save(base, apiKey = "", advancedAudioSecrets = mapOf("api_key" to "source-secret"))
        }
        val imported = JSONObject(source.exportJson()).apply {
            getJSONObject("advancedAudio").put("secrets", JSONObject().put("api_key", "imported-secret"))
        }
        val preview = repository.previewImport(imported.toString())
        background { repository.applyImport(preview, allowApiKey = true) }
        assertEquals("imported-secret", repository.advancedAudioSecret("api_key"))

        val unsafe = JSONObject(source.exportJson()).apply {
            getJSONObject("advancedAudio").apply {
                put("enabled", false)
                put("remoteAudio", JSONObject()
                    .put("type", "webdav")
                    .put("uploadBaseUrl", "https://alice:password@dav.example.test/upload")
                    .put("publicDownloadBaseUrl", "https://public.example.test/audio"),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) { repository.previewImport(unsafe.toString()) }
    }

    @Test
    fun schemaSevenAcceptsOlderAdvancedDraftsWithOmittedDefaultFields() {
        val repository = SettingsRepository(context)
        val imported = JSONObject(repository.exportJson()).apply {
            getJSONObject("advancedAudio").apply {
                put("enabled", false)
                put("workflow", "unfinished draft is preserved verbatim")
                remove("values")
                remove("remoteAudio")
            }
        }

        val advanced = repository.previewImport(imported.toString()).settings.advancedAudio
        assertEquals(
            AdvancedAudioConfig(
                workflowJson = "unfinished draft is preserved verbatim",
            ),
            advanced,
        )
    }

    @Test
    fun damagedAdvancedPreferenceAndSecretNamespaceDoNotBreakLegacySettings() {
        val repository = SettingsRepository(context)
        background {
            repository.save(
                AppSettings(provider = ProviderConfig(baseUrl = "https://asr.example.test", model = "legacy-model")),
                apiKey = "legacy-secret",
            )
            repository.saveAdvancedAudio(
                AdvancedAudioConfig(workflowJson = "stored draft"),
                mapOf("secret-id" to "discard-this-corrupt-entry"),
            )
        }
        val validSecretKey = preferences.all.keys.single {
            it.startsWith("secure.advanced_audio_secret_ciphertext.")
        }
        preferences.edit()
            .putBoolean("advanced_audio.config", true)
            .putBoolean(validSecretKey, true)
            .putBoolean("secure.advanced_audio_secret_ciphertext.not-a-valid-id!", true)
            .commit()

        val settings = repository.get()
        assertEquals("https://asr.example.test", settings.provider.baseUrl)
        assertEquals("legacy-model", settings.provider.model)
        assertEquals(AdvancedAudioConfig(), settings.advancedAudio)
        assertEquals("legacy-secret", repository.runtime().apiKey)
        assertTrue(repository.runtime().advancedAudioSecrets.isEmpty())
        assertFalse(preferences.contains(validSecretKey))
        assertFalse(preferences.contains("secure.advanced_audio_secret_ciphertext.not-a-valid-id!"))
    }

    private fun background(block: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        Thread { runCatching(block).exceptionOrNull()?.let(failure::set) }.apply { start(); join() }
        failure.get()?.let { throw it }
    }

    private fun fixture(path: String): String = checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
        "Missing test fixture $path"
    }.bufferedReader().use { it.readText() }

    private fun clearPreferences() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("secure_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }
}
