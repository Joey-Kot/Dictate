package com.joeykot.dictate.audio

import com.joeykot.dictate.model.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/** Opt-in: use the packaged Android binary via a device/QEMU runner, not a host encoder. */
class NativeAudioEncodingTest {
    private fun run(command: List<String>, directory: File): String {
        val log = File(directory, "process.log")
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Timed out: $command")
        }
        val output = log.readText()
        check(process.exitValue() == 0) { "$command\n$output" }
        return output
    }

    @Test fun packagedEncoderProducesDecodableFilesAndPreservesPcmSamples() {
        val executable = System.getenv("DICTATE_FFMPEG")
        assumeTrue("Set DICTATE_FFMPEG to the Android FFmpeg binary", !executable.isNullOrBlank())
        val runner = System.getenv("DICTATE_FFMPEG_RUNNER")?.let { listOf(it) }.orEmpty()
        val probe = System.getenv("DICTATE_FFPROBE") ?: "ffprobe"
        val decoder = System.getenv("DICTATE_FFMPEG_DECODER") ?: "ffmpeg"
        val directory = Files.createTempDirectory("dictate-audio-matrix-").toFile()
        val inputFormat = Pcm16Format(16000, 1)
        val source = ByteBuffer.allocate(32000).order(ByteOrder.LITTLE_ENDIAN)
        repeat(16000) { source.putShort((sin(2 * PI * 440 * it / 16000) * 12000).toInt().toShort()) }
        val input = File(directory, "input.pcm").apply { writeBytes(source.array()) }
        val failures = mutableListOf<String>()
        var checked = 0
        try {
            for (codec in AudioCodec.entries) {
                val rates = AudioConfig.compatibleSampleRates(codec).filter { it > 0 }
                val testedRates = if (System.getenv("DICTATE_AUDIO_FULL") == "1") rates else
                    listOf(rates.first(), AudioConfig.nearestSampleRate(codec, 16000), rates.last()).distinct()
                for (rate in testedRates) {
                    for (depth in AudioConfig.compatibleBitDepths(codec).ifEmpty { listOf(16) }) {
                        val base = AudioConfig(codec = codec, sampleRate = rate, bitDepth = depth).normalized()
                        val bitrates = AudioConfig.compatibleBitrates(codec, rate)
                        val testedBitrates = if (codec in listOf(AudioCodec.AMR, AudioCodec.AMR_WB, AudioCodec.SPEEX)) {
                            bitrates
                        } else (listOf(base.bitrateBps) + listOfNotNull(bitrates.firstOrNull(), bitrates.lastOrNull())).distinct()
                        for (bitrate in testedBitrates) for (container in AudioConfig.compatibleContainers(codec, rate, depth)) {
                            val config = base.copy(container = container, bitrateBps = bitrate)
                            try {
                                val plan = AudioEncodingPlan.resolve(config, inputFormat)
                                val output = File(directory, "output.${plan.extension}")
                                run(runner + plan.command(File(executable!!), input, inputFormat, output), directory)
                                assertTrue(output.length() > 0)
                                val raw = container.mimeType == "application/octet-stream"
                                val demuxArgs = when {
                                    raw -> listOf("-f", container.muxer, "-ar", rate.toString(), "-ch_layout", "mono")
                                    // Very short MPEG-PS audio files can fall below the automatic probe threshold.
                                    container == AudioContainer.MPEG -> listOf("-f", "mpeg")
                                    else -> emptyList()
                                }
                                val metadata = run(listOf(probe, "-v", "error") + demuxArgs + listOf(
                                    "-select_streams", "a:0", "-show_entries", "stream=codec_name,sample_rate,channels,bit_rate,bits_per_sample,bits_per_raw_sample",
                                    "-of", "default=noprint_wrappers=1", output.absolutePath,
                                ), directory).lineSequence().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
                                assertEquals("1", metadata["channels"])
                                // Opus always decodes on its 48 kHz timebase.
                                assertEquals(if (codec == AudioCodec.OPUS) "48000" else rate.toString(), metadata["sample_rate"])
                                val expectedCodec = when (codec) {
                                    AudioCodec.AMR -> "amr_nb"
                                    AudioCodec.AMR_WB -> "amr_wb"
                                    // MP4 uses the same MPEG audio object type for layers II and III.
                                    AudioCodec.MP2 -> if (container == AudioContainer.MP4) "mp3" else "mp2"
                                    else -> config.encoder().removePrefix("lib")
                                }.let { if (it == "mp3lame") "mp3" else it }
                                assertEquals(expectedCodec, metadata["codec_name"])
                                if (codec == AudioCodec.SPEEX) assertEquals(bitrate.toString(), metadata["bit_rate"])
                                if (codec in listOf(AudioCodec.PCM, AudioCodec.FLAC, AudioCodec.ALAC, AudioCodec.WAVPACK)) {
                                    val bits = metadata["bits_per_raw_sample"]?.toIntOrNull()?.takeIf { it > 0 }
                                        ?: metadata["bits_per_sample"]?.toIntOrNull()
                                    assertEquals(depth, bits)
                                }
                                val decoded = File(directory, "decoded.pcm")
                                val decoderArgs = if (codec == AudioCodec.MP2) listOf("-c:a", "mp2") else emptyList()
                                run(listOf(decoder, "-v", "error", "-y") + demuxArgs + decoderArgs + listOf("-i", output.absolutePath, "-ar", "16000", "-ac", "1", "-f", "s16le", decoded.absolutePath), directory)
                                assertTrue("Unexpected decoded duration: ${decoded.length()}", decoded.length() in 24000..48000)
                                assertTrue("Silent output", decoded.readBytes().any { it.toInt() != 0 })
                                if (codec == AudioCodec.PCM && rate == 16000 && depth >= 16) assertArrayEquals(source.array(), decoded.readBytes())
                                if (container == AudioContainer.S16BE && rate == 16000) {
                                    val expected = source.array().copyOf()
                                    for (i in expected.indices step 2) { val b = expected[i]; expected[i] = expected[i + 1]; expected[i + 1] = b }
                                    assertArrayEquals(expected, output.readBytes())
                                }
                                if (raw) {
                                    val width = when (container) {
                                        AudioContainer.S8, AudioContainer.ALAW, AudioContainer.MULAW -> 1
                                        AudioContainer.S16LE, AudioContainer.S16BE -> 2
                                        AudioContainer.S24LE, AudioContainer.S24BE -> 3
                                        AudioContainer.F64LE, AudioContainer.F64BE -> 8
                                        else -> 4
                                    }
                                    assertEquals(rate.toLong() * width, output.length())
                                }
                                checked++
                            } catch (failure: Throwable) {
                                failures += "$config: ${failure.message}"
                            }
                        }
                    }
                }
            }
            // Exact duplication is checked before any lossy encoding can change samples.
            for (layout in listOf(OutputChannelLayout.STEREO, OutputChannelLayout("5.1", 6))) {
                val config = AudioConfig(codec = AudioCodec.PCM, container = AudioContainer.S16LE, sampleRate = 16000)
                val plan = AudioEncodingPlan(config, layout)
                val output = File(directory, "duplicated.pcm")
                run(runner + plan.command(File(executable!!), input, inputFormat, output), directory)
                val bytes = output.readBytes()
                assertEquals(source.array().size * layout.channels, bytes.size)
                for (frame in 0 until 16000) for (channel in 0 until layout.channels) {
                    assertEquals(source.array()[frame * 2], bytes[(frame * layout.channels + channel) * 2])
                    assertEquals(source.array()[frame * 2 + 1], bytes[(frame * layout.channels + channel) * 2 + 1])
                }
            }
            println("Verified $checked Android audio combinations; ${failures.size} failures")
            assertTrue(failures.take(30).joinToString("\n\n"), failures.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }
}
