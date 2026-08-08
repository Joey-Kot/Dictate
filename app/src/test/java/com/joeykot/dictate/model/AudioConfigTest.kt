package com.joeykot.dictate.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioConfigTest {
    @Test
    fun codecContainerCompatibilityIsFixed() {
        assertEquals(
            listOf(AudioContainer.OPUS, AudioContainer.OGG),
            AudioConfig.compatibleContainers(AudioCodec.OPUS),
        )
        assertEquals(listOf(AudioContainer.MP3), AudioConfig.compatibleContainers(AudioCodec.MP3))
        assertEquals(listOf(AudioContainer.M4A), AudioConfig.compatibleContainers(AudioCodec.AAC))
        assertEquals(listOf(AudioContainer.WAV), AudioConfig.compatibleContainers(AudioCodec.PCM))
    }

    @Test
    fun normalizationReplacesInvalidContainer() {
        val normalized = AudioConfig(
            codec = AudioCodec.AAC,
            container = AudioContainer.MP3,
        ).normalized()
        assertEquals(AudioContainer.M4A, normalized.container)
    }

    @Test
    fun normalizationPreservesCompatibleOggContainer() {
        val normalized = AudioConfig(
            codec = AudioCodec.OPUS,
            container = AudioContainer.OGG,
        ).normalized()
        assertEquals(AudioContainer.OGG, normalized.container)
    }

    @Test
    fun opusRejectsUnsupported320Kbps() {
        val normalized = AudioConfig(
            codec = AudioCodec.OPUS,
            container = AudioContainer.OPUS,
            bitrateKbps = 320,
        ).normalized()
        assertEquals(AudioConfig.DEFAULT_BITRATE_KBPS, normalized.bitrateKbps)
        assertFalse(320 in AudioConfig.compatibleBitrates(AudioCodec.OPUS, 16_000))
    }

    @Test
    fun bitrateChoicesAvoidEncoderClamping() {
        assertEquals(
            listOf(16, 32, 64),
            AudioConfig.compatibleBitrates(AudioCodec.MP3, 8_000),
        )
        assertEquals(
            listOf(16, 32, 64, 128),
            AudioConfig.compatibleBitrates(AudioCodec.MP3, 16_000),
        )
        assertEquals(
            listOf(32, 64, 128, 192, 256, 320),
            AudioConfig.compatibleBitrates(AudioCodec.MP3, 48_000),
        )
        assertEquals(
            listOf(16, 32),
            AudioConfig.compatibleBitrates(AudioCodec.AAC, 8_000),
        )
        assertEquals(
            listOf(16, 32, 64, 128, 192, 256),
            AudioConfig.compatibleBitrates(AudioCodec.AAC, 48_000),
        )
    }

    @Test
    fun pcmUsesBitDepthAndHidesBitrateAtUiLayer() {
        val valid = AudioConfig(
            bitDepth = 24,
            codec = AudioCodec.PCM,
            container = AudioContainer.WAV,
        )
        val invalid = valid.copy(container = AudioContainer.OGG)
        assertTrue(valid.validate().isEmpty())
        assertFalse(invalid.validate().isEmpty())
    }
}
