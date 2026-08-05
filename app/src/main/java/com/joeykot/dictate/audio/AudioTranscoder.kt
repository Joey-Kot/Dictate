package com.joeykot.dictate.audio

import android.content.Context
import com.joeykot.dictate.model.AudioCodec
import com.joeykot.dictate.model.AudioConfig
import com.joeykot.dictate.model.AudioContainer
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

    fun transcode(jobId: Long, rawInput: File, output: File, config: AudioConfig): Result {
        if (cancelledJobs.remove(jobId)) return Result.Cancelled
        if (!rawInput.isFile || rawInput.length() == 0L) {
            return Result.Failure("原始录音文件不存在或为空")
        }

        val executable = File(context.applicationInfo.nativeLibraryDir, FFMPEG_LIBRARY_NAME)
        if (!executable.isFile) {
            return Result.Failure("应用未包含 arm64-v8a FFmpeg CLI，请重新安装完整 release APK")
        }

        output.parentFile?.mkdirs()
        output.delete()
        val normalized = config.normalized()
        val effectiveRate = effectiveSampleRate(normalized)
        val command = buildCommand(executable, rawInput, output, normalized, effectiveRate)
        diagnostics.info(
            "ffmpeg",
            "job=$jobId codec=${normalized.codec.value} container=${normalized.container.value} " +
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
                    message = if (summary.isBlank()) "FFmpeg 转码失败" else "FFmpeg 转码失败：$summary",
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
                Result.Failure("无法执行 FFmpeg：$summary")
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
        output: File,
        config: AudioConfig,
        effectiveRate: Int,
    ): List<String> = buildList {
        add(executable.absolutePath)
        addAll(listOf("-hide_banner", "-nostdin", "-y"))
        addAll(listOf("-f", "s16le", "-ar", AudioConfig.INPUT_SAMPLE_RATE.toString(), "-ac", "1"))
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
