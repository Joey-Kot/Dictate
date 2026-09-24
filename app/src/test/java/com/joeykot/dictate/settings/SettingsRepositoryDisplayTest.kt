package com.joeykot.dictate.settings

import android.content.Context
import com.joeykot.dictate.model.AppSettings
import com.joeykot.dictate.model.DisplayConfig
import com.joeykot.dictate.model.OverlayColorScheme
import com.joeykot.dictate.model.OverlayPalette
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class SettingsRepositoryDisplayTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        clearPreferences()
    }

    @After
    fun tearDown() {
        clearPreferences()
    }

    @Test
    fun freshSettingsUseCurrentDefaultDisplayAppearance() {
        val display = SettingsRepository(context).get().display

        assertEquals(1f, display.buttonScale, 0f)
        assertEquals(1f, display.buttonOpacity, 0f)
        assertEquals(OverlayColorScheme.DEFAULT, display.colorScheme)
        assertEquals(OverlayPalette.DEFAULT, display.customPalette)
    }

    @Test
    fun savesAndExportsDisplayConfiguration() {
        val repository = SettingsRepository(context)
        val expected = DisplayConfig(
            buttonScale = 1.62f,
            buttonOpacity = 0.47f,
            colorScheme = OverlayColorScheme.CUSTOM,
            customPalette = OverlayPalette(
                recordingColor = 0x123456,
                pausedColor = 0xABCDEF,
                processingColor = 0x654321,
            ),
        )

        saveInBackground(repository, AppSettings(display = expected))

        assertEquals(expected, repository.get().display)
        val exported = JSONObject(repository.exportJson())
        assertEquals(2, exported.getInt("schemaVersion"))
        assertEquals(0.47, exported.getJSONObject("display").getDouble("buttonOpacity"), 0.0001)
        assertEquals("#123456", exported.getJSONObject("display").getJSONObject("customColors").getString("recording"))
        assertEquals(expected, repository.previewImport(exported.toString()).settings.display)
    }

    @Test
    fun schemaVersionOneImportUsesDefaultDisplayConfiguration() {
        val repository = SettingsRepository(context)
        saveInBackground(repository, AppSettings())
        val legacy = JSONObject(repository.exportJson()).apply {
            put("schemaVersion", 1)
            remove("display")
        }

        assertEquals(DisplayConfig(), repository.previewImport(legacy.toString()).settings.display)
    }

    @Test
    fun rejectsOutOfRangeOpacityInVersionTwoImport() {
        val repository = SettingsRepository(context)
        saveInBackground(repository, AppSettings())
        val invalid = JSONObject(repository.exportJson()).apply {
            getJSONObject("display").put("buttonOpacity", 0.29)
        }

        val failure = runCatching { repository.previewImport(invalid.toString()) }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure is IllegalArgumentException)
    }

    private fun saveInBackground(repository: SettingsRepository, settings: AppSettings) {
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            runCatching { repository.save(settings, "") }
                .exceptionOrNull()
                ?.let(failure::set)
        }
        thread.start()
        thread.join()
        failure.get()?.let { throw AssertionError("保存失败", it) }
    }

    private fun clearPreferences() {
        context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(LEGACY_SECURE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private companion object {
        const val SETTINGS_PREFS = "settings"
        const val LEGACY_SECURE_PREFS = "secure_settings"
    }
}
