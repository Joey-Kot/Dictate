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
class SettingsRepositoryAudioTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    @Before fun setup() { prefs.edit().clear().commit() }
    @After fun cleanup() { prefs.edit().clear().commit() }

    private fun save(repository: SettingsRepository, audio: AudioConfig) {
        val error = AtomicReference<Throwable>()
        Thread { try { repository.save(AppSettings(audio = audio), "") } catch (failure: Throwable) { error.set(failure) } }
            .apply { start(); join() }
        error.get()?.let { throw it }
    }

    @Test fun exactBitratesAndEveryCodecRoundTripThroughStorageAndJson() {
        val repository = SettingsRepository(context)
        for (codec in AudioCodec.entries) {
            val audio = AudioConfig(codec = codec).normalized()
            save(repository, audio)
            assertEquals(audio, SettingsRepository(context).get().audio)
            val exported = JSONObject(repository.exportJson())
            assertEquals(8, exported.getInt("schemaVersion"))
            assertEquals(audio.bitrateBps, exported.getJSONObject("audioOutput").getInt("bitrateBps"))
            assertEquals(audio, repository.previewImport(exported.toString()).settings.audio)
        }
    }

    @Test fun legacyOpusRatesKeepTheirPreviouslyEncodedRate() {
        prefs.edit().putString("audio.codec", "OPUS").putString("audio.container", "OGG")
            .putInt("audio.sample_rate", 44100).putInt("audio.bitrate", 128).commit()
        val repository = SettingsRepository(context)
        assertEquals(48000, repository.get().audio.sampleRate)
        for (version in 1..4) {
            val legacy = JSONObject(repository.exportJson()).apply {
                put("schemaVersion", version)
                getJSONObject("audioOutput").apply {
                    put("codec", "opus"); put("container", "ogg"); put("sampleRate", 32000)
                    remove("bitrateBps"); put("bitrateKbps", 128)
                }
            }
            val imported = repository.previewImport(legacy.toString()).settings.audio
            assertEquals(48000, imported.sampleRate)
            assertEquals(128000, imported.bitrateBps)
            assertEquals(AudioContainer.OGG, imported.container)
        }
    }

    @Test fun importsRejectInvalidCombinationsWithoutMutatingSettings() {
        val repository = SettingsRepository(context)
        val original = repository.get()
        for ((key, value) in listOf("codec" to "unknown", "container" to "unknown", "sampleRate" to 12345, "bitrateBps" to 23851)) {
            val json = JSONObject(repository.exportJson()).apply { getJSONObject("audioOutput").put(key, value) }
            assertThrows(IllegalArgumentException::class.java) { repository.previewImport(json.toString()) }
            assertEquals(original, repository.get())
        }
    }

    @Test fun legacyPcm8SettingsAndImportsKeepTheirOutputFormat() {
        prefs.edit().putString("audio.codec", "PCM").putString("audio.container", "WAV")
            .putInt("audio.bit_depth", 8).putInt("audio.sample_rate", 44100)
            .putInt("audio.bitrate", 128).commit()
        val repository = SettingsRepository(context)
        val expected = AudioConfig(codec = AudioCodec.PCM, container = AudioContainer.WAV, bitDepth = 8, sampleRate = 44100)
        assertEquals(expected, repository.get().audio)
        val legacy = JSONObject(repository.exportJson()).apply {
            put("schemaVersion", 4)
            getJSONObject("audioOutput").apply {
                remove("bitrateBps")
                put("bitrateKbps", 128)
            }
        }
        assertEquals(expected, repository.previewImport(legacy.toString()).settings.audio)
        legacy.getJSONObject("audioOutput").put("bitrateKbps", Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { repository.previewImport(legacy.toString()) }
        assertEquals(expected, repository.get().audio)
    }
}
