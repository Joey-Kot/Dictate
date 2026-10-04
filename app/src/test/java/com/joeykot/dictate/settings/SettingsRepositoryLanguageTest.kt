package com.joeykot.dictate.settings

import android.content.Context
import com.joeykot.dictate.R
import com.joeykot.dictate.i18n.AppLocale
import com.joeykot.dictate.i18n.AppStrings
import com.joeykot.dictate.model.AppLanguage
import com.joeykot.dictate.model.AppSettings
import com.joeykot.dictate.model.ProviderConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN")
class SettingsRepositoryLanguageTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() = clearPreferences()

    @After
    fun tearDown() = clearPreferences()

    @Test
    fun allSixLanguagesSurviveSavingAndOpeningANewRepository() {
        val repository = SettingsRepository(context)
        for (language in AppLanguage.entries) {
            val expected = AppSettings(
                language = language,
                provider = ProviderConfig(baseUrl = "https://example.com/v1", model = "user-model"),
            )
            background { repository.save(expected, "") }

            assertEquals(expected, SettingsRepository(context).get())
            assertEquals(language, AppLocale.readLanguage(context))
            assertEquals(language.tag, context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("app.language", null))
        }
    }

    @Test
    fun everyExportedLanguageRoundTripsThroughPreviewAndApplyImport() {
        val repository = SettingsRepository(context)
        for (language in AppLanguage.entries) {
            val exportedSettings = AppSettings(language = language)
            background { repository.save(exportedSettings, "") }
            val exported = JSONObject(repository.exportJson())
            assertEquals(language.tag, exported.getString("language"))

            val otherLanguage = if (language == AppLanguage.ENGLISH) AppLanguage.CHINESE else AppLanguage.ENGLISH
            background { repository.save(AppSettings(language = otherLanguage), "") }
            val preview = repository.previewImport(exported.toString())
            assertEquals(exportedSettings, preview.settings)
            assertEquals("Preview must not change the current language", otherLanguage, repository.get().language)

            background { repository.applyImport(preview, allowApiKey = false) }
            assertEquals(exportedSettings, SettingsRepository(context).get())
            assertEquals(language.tag, JSONObject(repository.exportJson()).getString("language"))
        }
    }

    @Test
    fun legacyConfigurationWithoutLanguageDefaultsToEnglishForEverySupportedSchema() {
        val repository = SettingsRepository(context)
        assertEquals(AppLanguage.ENGLISH, repository.get().language)
        for (schemaVersion in 1..4) {
            background { repository.save(AppSettings(language = AppLanguage.RUSSIAN), "") }
            val legacy = JSONObject(repository.exportJson()).apply {
                put("schemaVersion", schemaVersion)
                remove("language")
                if (schemaVersion < 4) {
                    remove("postProcessing")
                    remove("promptIconAssets")
                }
                if (schemaVersion == 1) remove("display")
            }

            val preview = repository.previewImport(legacy.toString())
            assertEquals(AppLanguage.ENGLISH, preview.settings.language)
            assertEquals(AppLanguage.RUSSIAN, repository.get().language)
            background { repository.applyImport(preview, allowApiKey = false) }
            assertEquals(AppLanguage.ENGLISH, SettingsRepository(context).get().language)
        }
    }

    @Test
    fun invalidLanguageValuesAreRejectedWithoutChangingExistingSettings() {
        val repository = SettingsRepository(context)
        val original = AppSettings(
            language = AppLanguage.FRENCH,
            provider = ProviderConfig(baseUrl = "https://example.com/v1", model = "saved-model"),
        )
        background { repository.save(original, "") }
        val exported = repository.exportJson()
        val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val storedBefore = preferences.all.toMap()
        val invalidValues = listOf("", "es", "EN", 42, 1.5, true, JSONArray(), JSONObject(), JSONObject.NULL)

        for (invalid in invalidValues) {
            val json = JSONObject(exported).put("language", invalid).toString()
            val failure = runCatching {
                val preview = repository.previewImport(json)
                background { repository.applyImport(preview, allowApiKey = false) }
            }.exceptionOrNull()

            assertTrue("Invalid language $invalid must be rejected", failure is IllegalArgumentException)
            assertEquals(original, SettingsRepository(context).get())
            assertEquals(storedBefore, preferences.all)
        }
    }

    @Test
    fun wrappingALocaleLeavesTheBaseContextResourcesUnchanged() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putString("app.language", "ja").commit()
        val originalLocales = context.resources.configuration.locales.toLanguageTags()
        val originalTitle = context.getString(R.string.main_language)

        val wrapped = AppLocale.wrap(context)

        assertNotSame(context, wrapped)
        assertEquals("ja", wrapped.resources.configuration.locales[0].language)
        assertEquals("言語", wrapped.getString(R.string.main_language))
        assertEquals(originalLocales, context.resources.configuration.locales.toLanguageTags())
        assertEquals(originalTitle, context.getString(R.string.main_language))
    }

    @Test
    fun savingEachLanguageRefreshesLocalizedMessagesAndPreservesFormatArguments() {
        val repository = SettingsRepository(context)
        val expectedMessages = listOf(
            Triple(AppLanguage.ENGLISH, "Edit prompt: 100% test", "Elapsed: 42 ms"),
            Triple(AppLanguage.CHINESE, "编辑提示词：100% test", "耗时：42 ms"),
            Triple(AppLanguage.JAPANESE, "プロンプトを編集：100% test", "所要時間：42 ms"),
            Triple(AppLanguage.GERMAN, "Prompt bearbeiten: 100% test", "Dauer: 42 ms"),
            Triple(AppLanguage.FRENCH, "Modifier le prompt : 100% test", "Durée : 42 ms"),
            Triple(AppLanguage.RUSSIAN, "Изменить промпт: 100% test", "Время: 42 мс"),
        )

        for ((language, expectedDescription, expectedElapsed) in expectedMessages) {
            background { repository.save(AppSettings(language = language), "") }
            assertEquals(expectedDescription,
                AppStrings.get(R.string.prompt_edit_description, "Edit prompt: %1\$s", "100% test"))
            assertEquals(expectedElapsed,
                AppStrings.get(R.string.prompt_test_elapsed, "Elapsed: %1\$d ms", 42L))
        }
    }

    private fun background(block: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        Thread { runCatching(block).exceptionOrNull()?.let(failure::set) }.apply { start(); join() }
        failure.get()?.let { throw it }
    }

    private fun clearPreferences() {
        listOf("settings", "secure_settings").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        AppStrings.refresh(context)
    }
}
