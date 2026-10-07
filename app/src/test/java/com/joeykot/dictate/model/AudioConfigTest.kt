package com.joeykot.dictate.model

import org.junit.Assert.*
import org.junit.Test

class AudioConfigTest {
    @Test fun everyCodecHasValidDefaultsAndChoices() {
        for (codec in AudioCodec.entries) {
            val config = AudioConfig(codec = codec).normalized()
            assertTrue("$config: ${config.validate()}", config.validate().isEmpty())
            for (rate in AudioConfig.compatibleSampleRates(codec)) {
                val atRate = config.copy(sampleRate = rate).normalized()
                for (container in AudioConfig.compatibleContainers(codec, rate, atRate.bitDepth)) {
                    assertTrue(atRate.copy(container = container).validate().isEmpty())
                }
            }
        }
    }

    @Test fun normalizationPreservesExistingSelections() {
        val ogg = AudioConfig(codec = AudioCodec.OPUS, container = AudioContainer.OGG)
        assertEquals(ogg, ogg.normalized())
        for (depth in AudioConfig.BIT_DEPTHS) {
            val pcm = AudioConfig(codec = AudioCodec.PCM, container = AudioContainer.WAV, bitDepth = depth)
            assertEquals(pcm, pcm.normalized())
            assertTrue(pcm.validate().isEmpty())
        }
    }

    @Test fun invalidManualSettingsAreRejectedBeforeConversion() {
        assertTrue(AudioConfig(codec = AudioCodec.OPUS, sampleRate = 44100).validate().isNotEmpty())
        assertTrue(AudioConfig(codec = AudioCodec.AMR, sampleRate = 48000).validate().isNotEmpty())
        assertTrue(AudioConfig(codec = AudioCodec.AAC, container = AudioContainer.MP3).validate().isNotEmpty())
        assertTrue(AudioConfig(codec = AudioCodec.AAC, container = AudioContainer.WAV).validate().isNotEmpty())
        assertTrue(AudioConfig(codec = AudioCodec.OPUS, bitrateBps = 320000).validate().isNotEmpty())
        assertThrows(IllegalArgumentException::class.java) { AudioConfig(sampleRate = 12345).resolvedForInput(48000) }
    }

    @Test fun automaticRateAdaptsToEncoderWithoutChangingPreferences() {
        val expected = mapOf(AudioCodec.AMR to 8000, AudioCodec.AMR_WB to 16000, AudioCodec.SPEEX to 32000, AudioCodec.OPUS to 48000)
        for ((codec, rate) in expected) {
            val config = AudioConfig(codec = codec).normalized()
            assertEquals(rate, config.resolvedForInput(44100).sampleRate)
            assertEquals(0, config.sampleRate)
        }
        assertEquals(48000, AudioConfig().resolvedForInput(96000).sampleRate)
        assertEquals(44100, AudioConfig().resolvedForInput(44100).sampleRate)
        assertEquals(16000, AudioConfig.nearestSampleRate(AudioCodec.OPUS, 20000))
    }

    @Test fun automaticRateReconcilesBitrateButPreservesContainer() {
        val config = AudioConfig(bitrateBps = 320000)
        assertEquals(64000, config.resolvedForInput(8000).bitrateBps)
        assertEquals(320000, config.bitrateBps)
        for (codec in AudioCodec.entries) {
            for (container in AudioConfig.compatibleContainers(codec)) {
                val selected = AudioConfig(codec = codec, container = container).normalized()
                for (inputRate in listOf(8000, 11025, 16000, 44100, 48000, 96000)) {
                    assertEquals("$codec $container $inputRate", container, selected.resolvedForInput(inputRate).container)
                }
            }
        }
    }

    @Test fun amrUsesExactBitratesAndLosslessDoesNotHaveTargetBitrate() {
        assertEquals(listOf(4750, 5150, 5900, 6700, 7400, 7950, 10200, 12200), AudioConfig.compatibleBitrates(AudioCodec.AMR, 8000))
        assertTrue(23850 in AudioConfig.compatibleBitrates(AudioCodec.AMR_WB, 16000))
        for (codec in listOf(AudioCodec.FLAC, AudioCodec.ALAC, AudioCodec.WAVPACK, AudioCodec.PCM)) {
            assertTrue(AudioConfig.compatibleBitrates(codec, 16000).isEmpty())
        }
    }

    @Test fun pcmWidthDeterminesRawContainerAndEncoder() {
        val pcm = AudioConfig(codec = AudioCodec.PCM, bitDepth = 24, container = AudioContainer.S24LE)
        assertEquals("pcm_s24le", pcm.encoder())
        assertTrue(pcm.validate().isEmpty())
        assertTrue(pcm.copy(bitDepth = 16).validate().isNotEmpty())
        assertEquals("pcm_u8", pcm.copy(bitDepth = 8).encoder())
    }
}
