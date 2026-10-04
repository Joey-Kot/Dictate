package com.joeykot.dictate.audio

import android.content.Context
import com.joeykot.dictate.R
import com.joeykot.dictate.i18n.AppStrings
import com.joeykot.dictate.model.AudioCodec
import com.joeykot.dictate.model.AudioConfig
import com.joeykot.dictate.model.AudioContainer
import com.joeykot.dictate.model.Pcm16Format
import com.joeykot.dictate.util.Diagnostics
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class AudioTranscoder(
    private val context: Context,
    private val diagnostics: Diagnostics,
) {
    sealed interface Result {
        data class Success(val output: File) : Result
        data class Failure(val message: String, val exitCode: Int? = null) : Result
        data object Cancelled : Result
    }

    private val activeProcesses = ConcurrentHashMap<Long, Process>()
    private val cancelledJobs = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())

    fun transcode(
        jobId: Long,
        rawInput: File,
        inputFormat: Pcm16Format,
        output: File,
        config: AudioConfig,
    ): Result {
        if (cancelledJobs.remove(jobId)) return Result.Cancelled
        if (!rawInput.isFile || rawInput.length() == 0L) {
            return Result.Failure(AppStrings.get(R.string.runtime_raw_recording_missing, "The original recording file is missing or empty"))
        }

        val executable = File(context.applicationInfo.nativeLibraryDir, FFMPEG_LIBRARY_NAME)
        if (!executable.isFile) {
            return Result.Failure(AppStrings.get(R.string.runtime_ffmpeg_missing, "This app does not include arm64-v8a FFmpeg CLI. Reinstall the complete release APK"))
        }

        output.parentFile?.mkdirs()
        output.delete()
        val normalized = config.resolvedForInput(inputFormat.sampleRateHz)
        val effectiveRate = effectiveSampleRate(normalized)
        val command = buildCommand(
            executable,
            rawInput,
            inputFormat,
            output,
            normalized,
            effectiveRate,
        )
        diagnostics.info(
            "ffmpeg",
            "job=$jobId codec=${normalized.codec.value} container=${normalized.container.value} " +
                "input=${inputFormat.summary()} requestedSampleRate=${config.sampleRate} " +
                "sampleRate=${normalized.sampleRate} effectiveRate=$effectiveRate " +
                "bitDepth=${normalized.bitDepth} bitrate=${normalized.bitrateKbps}k",
        )

        return try {
            val processBuilder = ProcessBuilder(command)
                .redirectErrorStream(true)
            processBuilder.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
            val process = processBuilder.start()
            activeProcesses[jobId] = process
            if (jobId in cancelledJobs) process.destroy()

            val tail = ArrayDeque<String>()
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    tail.addLast(line)
                    while (tail.size > MAX_OUTPUT_LINES) tail.removeFirst()
                }
            }
            val exitCode = process.waitFor()
            activeProcesses.remove(jobId)

            if (jobId in cancelledJobs) {
                output.delete()
                Result.Cancelled
            } else if (exitCode == 0 && output.isFile && output.length() > 0L) {
                diagnostics.info("ffmpeg", "job=$jobId exit=0 bytes=${output.length()}")
                Result.Success(output)
            } else {
                val summary = diagnostics.sanitize(tail.joinToString("\n"), 1_500)
                diagnostics.error("ffmpeg", "job=$jobId exit=$exitCode $summary")
                output.delete()
                Result.Failure(
                    message = if (summary.isBlank()) {
                        AppStrings.get(R.string.runtime_ffmpeg_transcode_failed, "FFmpeg transcoding failed")
                    } else {
                        AppStrings.get(R.string.runtime_ffmpeg_transcode_detail, "FFmpeg transcoding failed: %1\$s", summary)
                    },
                    exitCode = exitCode,
                )
            }
        } catch (error: Exception) {
            activeProcesses.remove(jobId)
            output.delete()
            if (jobId in cancelledJobs) {
                Result.Cancelled
            } else {
                val summary = diagnostics.sanitize(error.message ?: error.javaClass.simpleName)
                diagnostics.error("ffmpeg", "job=$jobId start/read failure: $summary")
                Result.Failure(AppStrings.get(R.string.runtime_ffmpeg_execution_failed, "Unable to run FFmpeg: %1\$s", summary))
            }
        } finally {
            cancelledJobs.remove(jobId)
        }
    }

    fun cancel(jobId: Long) {
        cancelledJobs.add(jobId)
        activeProcesses.remove(jobId)?.let { process ->
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun buildCommand(
        executable: File,
        input: File,
        inputFormat: Pcm16Format,
        output: File,
        config: AudioConfig,
        effectiveRate: Int,
    ): List<String> = buildList {
        add(executable.absolutePath)
        addAll(listOf("-hide_banner", "-nostdin", "-y"))
        addAll(
            listOf(
                "-f", "s16le",
                "-ar", inputFormat.sampleRateHz.toString(),
                "-ac", inputFormat.channelCount.toString(),
            ),
        )
        addAll(listOf("-i", input.absolutePath, "-vn", "-ac", "1", "-ar", effectiveRate.toString()))

        when (config.codec) {
            AudioCodec.OPUS -> addAll(
                listOf(
                    "-c:a", "libopus",
                    "-application", "voip",
                    "-b:a", "${config.bitrateKbps}k",
                    "-vbr", "on",
                ),
            )
            AudioCodec.MP3 -> addAll(
                listOf("-c:a", "libmp3lame", "-b:a", "${config.bitrateKbps}k"),
            )
            AudioCodec.AAC -> addAll(
                listOf("-c:a", "aac", "-b:a", "${config.bitrateKbps}k", "-movflags", "+faststart"),
            )
            AudioCodec.PCM -> addAll(listOf("-c:a", pcmEncoder(config.bitDepth)))
        }

        addAll(listOf("-f", muxer(config.container), output.absolutePath))
    }

    private fun effectiveSampleRate(config: AudioConfig): Int = when (config.codec) {
        AudioCodec.OPUS -> when (config.sampleRate) {
            8_000, 16_000, 24_000, 48_000 -> config.sampleRate
            else -> 48_000
        }
        else -> config.sampleRate
    }

    private fun pcmEncoder(bitDepth: Int): String = when (bitDepth) {
        8 -> "pcm_u8"
        16 -> "pcm_s16le"
        24 -> "pcm_s24le"
        32 -> "pcm_s32le"
        else -> error("Unsupported PCM bit depth")
    }

    private fun muxer(container: AudioContainer): String = when (container) {
        AudioContainer.OPUS -> "opus"
        AudioContainer.OGG -> "ogg"
        AudioContainer.MP3 -> "mp3"
        AudioContainer.M4A -> "ipod"
        AudioContainer.WAV -> "wav"
    }

    private companion object {
        const val FFMPEG_LIBRARY_NAME = "libffmpeg.so"
        const val MAX_OUTPUT_LINES = 24
    }
}
