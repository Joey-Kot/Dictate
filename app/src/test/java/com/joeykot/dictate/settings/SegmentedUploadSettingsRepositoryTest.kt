package com.joeykot.dictate.settings

import android.content.Context
import com.joeykot.dictate.model.AppSettings
import com.joeykot.dictate.model.SegmentedUploadConfig
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SegmentedUploadSettingsRepositoryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        preferences.edit().clear().commit()
    }

    @After
    fun tearDown() {
        preferences.edit().clear().commit()
    }

    @Test
    fun segmentedUploadRoundTripsThroughStorageAndSchemaEightExport() {
        val repository = SettingsRepository(context)
        val expected = SegmentedUploadConfig(
            enabled = true,
            maximumSegmentLengthSeconds = 450,
            minimumPauseDurationMillis = 850,
            concurrency = 3,
        )

        save(repository, AppSettings(segmentedUpload = expected))

        assertEquals(expected, SettingsRepository(context).get().segmentedUpload)
        val exported = JSONObject(repository.exportJson())
        assertEquals(8, exported.getInt("schemaVersion"))
        assertEquals(true, exported.getJSONObject("segmentedUpload").getBoolean("enabled"))
        assertEquals(450, exported.getJSONObject("segmentedUpload").getInt("maximumSegmentLengthSeconds"))
        assertEquals(850, exported.getJSONObject("segmentedUpload").getInt("minimumPauseDurationMillis"))
        assertEquals(3, exported.getJSONObject("segmentedUpload").getInt("concurrency"))
        assertEquals(expected, repository.previewImport(exported.toString()).settings.segmentedUpload)
    }

    @Test
    fun schemaSevenImportUsesDisabledSegmentedUploadDefaults() {
        val repository = SettingsRepository(context)
        val legacy = JSONObject(repository.exportJson()).apply {
            put("schemaVersion", 7)
            remove("segmentedUpload")
        }

        assertEquals(SegmentedUploadConfig(), repository.previewImport(legacy.toString()).settings.segmentedUpload)
    }

    @Test
    fun segmentedUploadValuesMustRemainPositiveWhenDisabled() {
        val repository = SettingsRepository(context)
        val invalid = AppSettings(
            segmentedUpload = SegmentedUploadConfig(
                enabled = false,
                maximumSegmentLengthSeconds = 0,
                minimumPauseDurationMillis = 700,
                concurrency = 1,
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            save(repository, invalid)
        }

        val malformedImport = JSONObject(repository.exportJson()).apply {
            getJSONObject("segmentedUpload").put("concurrency", 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.previewImport(malformedImport.toString())
        }
    }

    @Test
    fun segmentedUploadConcurrencyUsesTheAndroidSafeUpperBound() {
        val repository = SettingsRepository(context)
        val overLimit = SegmentedUploadConfig.MAX_CONCURRENCY + 1
        val invalid = AppSettings(
            segmentedUpload = SegmentedUploadConfig(
                enabled = false,
                concurrency = overLimit,
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            save(repository, invalid)
        }

        val malformedImport = JSONObject(repository.exportJson()).apply {
            getJSONObject("segmentedUpload").put("concurrency", overLimit)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.previewImport(malformedImport.toString())
        }

        preferences.edit()
            .putInt("segmented_upload.concurrency", overLimit)
            .commit()
        assertEquals(
            SegmentedUploadConfig.DEFAULT_CONCURRENCY,
            repository.get().segmentedUpload.concurrency,
        )
    }

    private fun save(repository: SettingsRepository, settings: AppSettings) {
        val error = AtomicReference<Throwable>()
        Thread {
            try {
                repository.save(settings, "")
            } catch (failure: Throwable) {
                error.set(failure)
            }
        }.apply {
            start()
            join()
        }
        error.get()?.let { throw it }
    }
}
