package com.joeykot.dictate.settings

import android.content.Context
import com.joeykot.dictate.model.*
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PromptProviderSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val prompt = PromptConfig(id = "first", title = "First", prompt = "Summarize", provider = PostProcessingProvider.GOOGLE,
        baseUrl = "https://prompt.example", model = "prompt-model")

    @Before fun setup() { prefs.edit().clear().commit(); TestAndroidKeyStore.install() }
    @After fun cleanup() { prefs.edit().clear().commit(); TestAndroidKeyStore.remove() }

    @Test fun encryptedKeysFollowIdsAcrossEditsReorderingInheritanceAndDeletion() {
        val repository = SettingsRepository(context)
        val second = prompt.copy(id = "second", title = "Second")
        background { repository.savePrompts(listOf(prompt, second), mapOf(prompt.id to "first-secret", second.id to "second-secret")) }
        assertFalse(prefs.all.toString().contains("first-secret"))
        assertFalse(prefs.all.toString().contains("second-secret"))
        assertEquals("first-secret", repository.promptApiKey(prompt.id))
        background { repository.savePrompts(listOf(second, prompt.copy(title = "Renamed", provider = null))) }
        assertEquals("first-secret", repository.promptApiKey(prompt.id))
        assertEquals("second-secret", repository.runtime().promptApiKeys[second.id])
        assertNull(repository.runtime().promptApiKeys[prompt.id])
        background { repository.save(repository.get(), "audio-secret", "main-secret") }
        assertEquals("first-secret", repository.promptApiKey(prompt.id))
        background { repository.savePrompts(listOf(second)) }
        assertEquals("", repository.promptApiKey(prompt.id))
        assertEquals("second-secret", repository.promptApiKey(second.id))
        assertEquals("audio-secret", repository.runtime().apiKey)
        assertEquals("main-secret", repository.runtime().postProcessingApiKey)
    }

    @Test fun everyProviderRoundTripsAndExportsOmitEvenInactiveSecrets() {
        val repository = SettingsRepository(context)
        val prompts = PostProcessingProvider.entries.map { prompt.copy(id = it.name, provider = it) } + prompt.copy(provider = null)
        background { repository.savePrompts(prompts, prompts.associate { it.id to "private-${it.id}" }) }
        val json = repository.exportJson()
        assertEquals(7, JSONObject(json).getInt("schemaVersion"))
        assertFalse(json.contains("private-"))
        assertFalse(json.contains("apiKey"))
        val preview = repository.previewImport(json)
        assertEquals(prompts, preview.settings.postProcessing.prompts)
        assertFalse(preview.hasApiKeys)
        background { repository.applyImport(preview, false) }
        prompts.forEach { assertEquals("private-${it.id}", repository.promptApiKey(it.id)) }
    }

    @Test fun importingKeysRequiresConfirmationAndMissingKeysOnlyMatchTheSameApi() {
        val repository = SettingsRepository(context)
        background { repository.savePrompts(listOf(prompt), mapOf(prompt.id to "original-secret")) }
        fun preview(field: String? = null, value: Any = "") = repository.previewImport(JSONObject(repository.exportJson()).apply {
            if (field != null) getJSONObject("postProcessing").getJSONArray("prompts").getJSONObject(0).put(field, value)
        }.toString())
        val keyed = preview("apiKey", "imported-secret")
        assertTrue(keyed.hasApiKeys)
        assertThrows(IllegalArgumentException::class.java) { background { repository.applyImport(keyed, false) } }
        assertEquals("original-secret", repository.promptApiKey(prompt.id))
        background { repository.applyImport(keyed, true) }
        assertEquals("imported-secret", repository.promptApiKey(prompt.id))
        background { repository.applyImport(preview(), false) }
        assertEquals("imported-secret", repository.promptApiKey(prompt.id))
        background { repository.applyImport(preview("baseUrl", "  ${prompt.baseUrl}  "), false) }
        assertEquals("imported-secret", repository.promptApiKey(prompt.id))
        background { repository.applyImport(preview("baseUrl", "https://different.example"), false) }
        assertEquals("", repository.promptApiKey(prompt.id))
        background { repository.savePrompts(listOf(prompt), mapOf(prompt.id to "restored-secret")) }
        background { repository.applyImport(preview("provider", "ANTHROPIC"), false) }
        assertEquals("", repository.promptApiKey(prompt.id))
        background { repository.savePrompts(listOf(prompt), mapOf(prompt.id to "restored-secret")) }
        background { repository.applyImport(preview("id", "new-id"), false) }
        assertEquals("", repository.promptApiKey("new-id"))
        assertEquals("", repository.promptApiKey(prompt.id))
        background { repository.savePrompts(listOf(prompt), mapOf(prompt.id to "restored-secret")) }
        background { repository.applyImport(preview("apiKey", ""), true) }
        assertEquals("", repository.promptApiKey(prompt.id))
    }

    @Test fun legacyStorageAndVersionFourAndFiveImportsDefaultToInheritance() {
        val repository = SettingsRepository(context)
        val item = PostProcessingSettingsCodec.encodePrompts(listOf(prompt)).getJSONObject(0).apply {
            remove("provider"); remove("baseUrl"); remove("model")
        }
        prefs.edit().putString("post_processing.prompts", "[$item]").commit()
        assertNull(repository.get().postProcessing.prompts.single().provider)
        for (version in 4..5) {
            val root = JSONObject(repository.exportJson()).apply {
                put("schemaVersion", version)
                getJSONObject("postProcessing").getJSONArray("prompts").put(0, item)
                if (version == 4) getJSONObject("audioOutput").apply { put("bitrateKbps", 128); remove("bitrateBps") }
            }
            assertNull(repository.previewImport(root.toString()).settings.postProcessing.prompts.single().provider)
        }
    }

    @Test fun encryptionFailureDoesNotPartiallySaveConfigsOrDeleteKeys() {
        val repository = SettingsRepository(context)
        val second = prompt.copy(id = "second")
        background { repository.savePrompts(listOf(prompt, second), mapOf(prompt.id to "first-secret", second.id to "second-secret")) }
        val previous = prefs.all
        TestAndroidKeyStore.remove()
        try {
            assertThrows(Exception::class.java) {
                background { repository.savePrompts(listOf(prompt.copy(title = "Changed")), mapOf(prompt.id to "new-secret")) }
            }
            assertEquals(previous, prefs.all)
        } finally { TestAndroidKeyStore.install() }
        assertEquals("first-secret", repository.promptApiKey(prompt.id))
        assertEquals("second-secret", repository.promptApiKey(second.id))
    }

    @Test fun invalidImportsAndFailedPromptValidationLeaveConfigsAndSecretsUntouched() {
        val repository = SettingsRepository(context)
        background { repository.savePrompts(listOf(prompt), mapOf(prompt.id to "original-secret")) }
        val previous = prefs.all
        for ((field, value) in listOf("provider" to "UNKNOWN", "provider" to 3, "baseUrl" to false, "model" to JSONObject.NULL, "apiKey" to 3)) {
            val root = JSONObject(repository.exportJson()).apply {
                getJSONObject("postProcessing").getJSONArray("prompts").getJSONObject(0).put(field, value)
            }
            assertThrows(IllegalArgumentException::class.java) { repository.previewImport(root.toString()) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            background { repository.savePrompts(listOf(prompt.copy(title = "")), mapOf(prompt.id to "replacement")) }
        }
        assertEquals(previous, prefs.all)
        // Inactive API values do not stop a valid inherited prompt from being saved.
        background { repository.savePrompts(listOf(prompt.copy(provider = null, baseUrl = "invalid hidden URL"))) }
        assertEquals("original-secret", repository.promptApiKey(prompt.id))
    }

    private fun background(block: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        Thread { runCatching(block).exceptionOrNull()?.let(failure::set) }.apply { start(); join() }
        failure.get()?.let { throw it }
    }
}
