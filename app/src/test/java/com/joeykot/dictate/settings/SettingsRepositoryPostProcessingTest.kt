package com.joeykot.dictate.settings

import android.content.Context
import android.util.Base64
import com.joeykot.dictate.model.AppSettings
import com.joeykot.dictate.model.PostProcessingConfig
import com.joeykot.dictate.model.PostProcessingProvider
import com.joeykot.dictate.model.PromptConfig
import com.joeykot.dictate.model.ProviderConfig
import com.joeykot.dictate.model.RetryConfig
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsRepositoryPostProcessingTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val icons: File get() = File(context.filesDir, "icons")

    @Before
    fun setUp() = clean()

    @After
    fun tearDown() = clean()

    @Test
    fun legacyConfigurationLoadsWithNoPromptsAndImportsAllEarlierSchemas() {
        val repository = SettingsRepository(context)
        assertEquals(PostProcessingConfig(), repository.get().postProcessing)
        for (version in 1..3) {
            val legacy = JSONObject(repository.exportJson()).apply {
                put("schemaVersion", version)
                getJSONObject("audioOutput").apply {
                    put("bitrateKbps", getInt("bitrateBps") / 1000)
                    remove("bitrateBps")
                }
                remove("postProcessing")
                remove("promptIconAssets")
                if (version == 1) remove("display")
            }
            val imported = repository.previewImport(legacy.toString())
            assertEquals(PostProcessingConfig(), imported.settings.postProcessing)
            assertNull(imported.postProcessingApiKey)
            assertFalse(imported.hasApiKeys)
        }
    }

    @Test
    fun promptWritesOnlyUpdatePromptsAndPreserveJsonDeleteInstructions() {
        val repository = SettingsRepository(context)
        val initial = AppSettings(
            provider = ProviderConfig(baseUrl = "https://asr.example/v1", model = "audio-model"),
            postProcessing = PostProcessingConfig(
                provider = PostProcessingProvider.ANTHROPIC,
                baseUrl = "https://text.example/v1",
                model = "default-model",
            ),
            retry = RetryConfig(enabled = true, maxRetries = 4),
        )
        background { repository.save(initial, "") }
        val first = prompt("first").copy(additionalJson = """{"model":"other-model","nested":{"remove":null},"array":[1,true]}""")
        val second = prompt("second")
        background { repository.savePrompts(listOf(second, first)) }
        val loaded = repository.get()
        assertEquals(initial.provider, loaded.provider)
        assertEquals(initial.retry, loaded.retry)
        assertEquals(initial.postProcessing.copy(prompts = loaded.postProcessing.prompts), loaded.postProcessing)
        assertEquals(listOf("second", "first"), loaded.postProcessing.prompts.map { it.id })
        val extra = JSONObject(loaded.postProcessing.prompts[1].additionalJson)
        assertTrue(extra.getJSONObject("nested").has("remove"))
        assertTrue(extra.getJSONObject("nested").isNull("remove"))
        assertEquals("other-model", extra.getString("model"))
        val exported = JSONObject(repository.exportJson())
        val exportedExtra = exported.getJSONObject("postProcessing").getJSONArray("prompts")
            .getJSONObject(1).getJSONObject("additionalParameters")
        assertTrue(exportedExtra.getJSONObject("nested").has("remove"))
        assertFalse(exported.getJSONObject("postProcessing").has("apiKey"))
        assertFalse(exported.getJSONObject("openAICompatible").has("apiKey"))
        val reimport = repository.previewImport(exported.toString())
        assertEquals(loaded.postProcessing, reimport.settings.postProcessing)
    }

    @Test
    fun promptsSaveWithoutPublicConfigurationAndRejectInvalidEntriesAtomically() {
        val repository = SettingsRepository(context)
        val valid = prompt("only")
        background { repository.savePrompts(listOf(valid)) }
        assertEquals("", repository.get().postProcessing.model)
        assertTrue(repository.validate(repository.get()).isEmpty())
        val invalidCases = listOf(
            listOf(valid, valid),
            listOf(valid.copy(title = " ")),
            listOf(valid.copy(prompt = "")),
            listOf(valid.copy(additionalJson = "[]")),
            listOf(valid.copy(customIcon = "../outside.png")),
        )
        invalidCases.forEach { invalid ->
            val failure = runCatching { background { repository.savePrompts(invalid) } }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals(listOf(valid), repository.get().postProcessing.prompts)
        }
    }

    @Test
    fun exportedCustomIconsRestoreWithFreshNamesAndPreviewDoesNotWrite() {
        val repository = SettingsRepository(context)
        icons.mkdirs()
        val bytes = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray()
        File(icons, "original.svg").writeBytes(bytes)
        background { repository.savePrompts(listOf(prompt("icon").copy(customIcon = "original.svg"))) }
        val json = repository.exportJson()
        val preview = repository.previewImport(json)
        assertArrayEquals(bytes, preview.iconAssets["original.svg"])
        assertEquals(listOf("original.svg"), icons.listFiles()!!.map { it.name })
        File(icons, "original.svg").delete()
        background { repository.applyImport(preview, false) }
        val installed = repository.get().postProcessing.prompts.single().customIcon
        assertNotNull(installed)
        assertNotEquals("original.svg", installed)
        assertArrayEquals(bytes, File(icons, installed!!).readBytes())
    }

    @Test
    fun unavailableCustomIconFallsBackAndUnsafeAssetNamesAreRejected() {
        val repository = SettingsRepository(context)
        background { repository.savePrompts(listOf(prompt("missing").copy(customIcon = "missing.png"))) }
        val json = JSONObject(repository.exportJson())
        val preview = repository.previewImport(json.toString())
        assertTrue(preview.iconAssets.isEmpty())
        background { repository.applyImport(preview, false) }
        assertNull(repository.get().postProcessing.prompts.single().customIcon)

        json.getJSONObject("promptIconAssets").put("../outside.png", Base64.encodeToString(byteArrayOf(1), Base64.NO_WRAP))
        assertTrue(runCatching { repository.previewImport(json.toString()) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun importOfEitherApiKeyRequiresExistingConfirmationBeforeAnyWrites() {
        val repository = SettingsRepository(context)
        val json = JSONObject(repository.exportJson()).apply {
            getJSONObject("postProcessing").put("apiKey", "post-secret")
        }
        val postOnly = repository.previewImport(json.toString())
        assertTrue(postOnly.hasApiKeys)
        assertNull(postOnly.apiKey)
        assertEquals("post-secret", postOnly.postProcessingApiKey)
        val failure = runCatching { background { repository.applyImport(postOnly, false) } }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals("", repository.runtime().postProcessingApiKey)
        assertFalse(icons.exists())
        json.getJSONObject("openAICompatible").put("apiKey", "audio-secret")
        val both = repository.previewImport(json.toString())
        assertEquals("audio-secret", both.apiKey)
        assertEquals("post-secret", both.postProcessingApiKey)
    }

    @Test
    fun clearingPostProcessingSecretDoesNotChangeTranscriptionStorageOrMigration() {
        val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        preferences.edit().putString("secure.api_key_ciphertext", "transcription-ciphertext")
            .putString("secure.post_processing_api_key_ciphertext", "post-ciphertext").commit()
        val store = SecureApiKeyStore(context, preferences)
        val editor = preferences.edit()
        store.stagePostProcessing(editor, "")
        editor.commit()
        assertEquals("transcription-ciphertext", preferences.getString("secure.api_key_ciphertext", null))
        assertFalse(preferences.contains("secure.post_processing_api_key_ciphertext"))
        assertFalse(preferences.contains("secure.api_key_migrated"))
    }

    private fun prompt(id: String) = PromptConfig(id = id, title = "Title $id", prompt = "Summarize")

    private fun background(block: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        Thread { runCatching(block).exceptionOrNull()?.let(failure::set) }.apply { start(); join() }
        failure.get()?.let { throw it }
    }

    private fun clean() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("secure_settings", Context.MODE_PRIVATE).edit().clear().commit()
        icons.deleteRecursively()
    }
}
