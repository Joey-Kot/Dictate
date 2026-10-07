package com.joeykot.dictate.audio

import com.joeykot.dictate.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AudioEncodingPlanTest {
    private val inputFormat = Pcm16Format(44100, 1)
    private fun command(config: AudioConfig): List<String> = AudioEncodingPlan.resolve(config, inputFormat)
        .command(File("/ffmpeg"), File("/input.pcm"), inputFormat, File("/output"))

    @Test fun commandResamplesAndUsesExactAmrBitrate() {
        val args = command(AudioConfig(codec = AudioCodec.AMR_WB, container = AudioContainer.AMR, bitrateBps = 23850))
        assertEquals("23850", args[args.indexOf("-b:a") + 1])
        assertEquals("aresample=16000", args[args.indexOf("-af") + 1])
        assertEquals("libvo_amrwbenc", args[args.indexOf("-c:a") + 1])
    }

    @Test fun losslessInputFormatDoesNotConfuseInternalStorageAndFileDepth() {
        val args = command(AudioConfig(codec = AudioCodec.FLAC, container = AudioContainer.FLAC, bitDepth = 24))
        assertEquals("s32", args[args.indexOf("-sample_fmt") + 1])
        assertEquals("24", args[args.indexOf("-bits_per_raw_sample") + 1])
        assertFalse("-b:a" in args)
    }

    @Test fun faststartIsOnlyUsedWithMovFamilyMuxers() {
        for (container in AudioConfig.compatibleContainers(AudioCodec.AAC)) {
            val args = command(AudioConfig(codec = AudioCodec.AAC, container = container))
            assertEquals(container in listOf(AudioContainer.M4A, AudioContainer.MP4, AudioContainer.MOV), "-movflags" in args)
        }
    }

    @Test fun selectsSmallestSupportedLayoutAndExplicitlyDuplicatesMono() {
        val surround = OutputChannelLayout("5.1", 6)
        assertEquals(OutputChannelLayout.STEREO, OutputChannelLayout.select(listOf(surround, OutputChannelLayout.STEREO)))
        assertEquals(OutputChannelLayout.MONO, OutputChannelLayout.select(listOf(surround, OutputChannelLayout.MONO)))
        assertEquals("pan=stereo|c0=c0|c1=c0", OutputChannelLayout.STEREO.duplicateMonoFilter())
        assertEquals("pan=5.1|c0=c0|c1=c0|c2=c0|c3=c0|c4=c0|c5=c0", surround.duplicateMonoFilter())
    }

    @Test fun uploadMetadataDescribesActualOutput() {
        val plan = AudioEncodingPlan.resolve(AudioConfig(codec = AudioCodec.AMR_WB).normalized(), inputFormat)
        assertEquals("amr", plan.extension)
        assertEquals("audio/amr-wb", plan.mimeType)
        assertEquals("application/octet-stream", AudioConfig(codec = AudioCodec.PCM, container = AudioContainer.S16LE).mimeType())
    }
}
