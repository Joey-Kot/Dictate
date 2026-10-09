package com.joeykot.dictate.audio

import android.content.Context
import com.joeykot.dictate.R
import com.joeykot.dictate.i18n.AppStrings
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
        plan: AudioEncodingPlan,
        /**
         * An operation-local cancellation probe for callers that deliberately
         * do not use [cancel]'s retained pre-start marker.  Segmented export
         * uses this path so cancelling the gap between two slices cannot
         * leave an operation ID behind for a future, unrelated transcode.
         */
        cancellationRequested: (() -> Boolean)? = null,
    ): Result {
        if (isCancelled(jobId, cancellationRequested, consumeMarker = true)) return Result.Cancelled
        if (!rawInput.isFile || rawInput.length() == 0L) {
            return Result.Failure(AppStrings.get(R.string.runtime_raw_recording_missing, "The original recording file is missing or empty"))
        }

        val executable = File(context.applicationInfo.nativeLibraryDir, FFMPEG_LIBRARY_NAME)
        if (!executable.isFile) {
            return Result.Failure(AppStrings.get(R.string.runtime_ffmpeg_missing, "This app does not include arm64-v8a FFmpeg CLI. Reinstall the complete release APK"))
        }

        output.parentFile?.mkdirs()
        output.delete()
        val command = plan.command(executable, rawInput, inputFormat, output)
        diagnostics.info(
            "ffmpeg",
            "job=$jobId codec=${plan.config.codec.value} container=${plan.config.container.value} " +
                "input=${inputFormat.summary()} outputRate=${plan.config.sampleRate} " +
                "layout=${plan.layout.name} bitDepth=${plan.config.bitDepth} bitrateBps=${plan.config.bitrateBps}",
        )

        return try {
            val processBuilder = ProcessBuilder(command)
                .redirectErrorStream(true)
            processBuilder.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
            val process = processBuilder.start()
            activeProcesses[jobId] = process
            // A segmented caller can be cancelled after its controller has
            // handed the ID to us but before this process becomes visible.
            // Check its operation-local flag again after publication so the
            // process cannot escape that hand-off window.
            if (isCancelled(jobId, cancellationRequested)) destroy(process)

            val tail = ArrayDeque<String>()
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    tail.addLast(line)
                    while (tail.size > MAX_OUTPUT_LINES) tail.removeFirst()
                }
            }
            val exitCode = process.waitFor()
            activeProcesses.remove(jobId)

            if (isCancelled(jobId, cancellationRequested)) {
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
            if (isCancelled(jobId, cancellationRequested)) {
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
        cancelRunning(jobId)
    }

    /**
     * Stop an already-started process without registering a retained
     * pre-start cancellation marker.  This is required for sequential
     * segmented exports: the next slice must not inherit cancellation meant
     * only for the currently active slice.
     */
    fun cancelRunning(jobId: Long) {
        activeProcesses.remove(jobId)?.let(::destroy)
    }

    private fun isCancelled(
        jobId: Long,
        cancellationRequested: (() -> Boolean)?,
        consumeMarker: Boolean = false,
    ): Boolean =
        if (consumeMarker) {
            cancelledJobs.remove(jobId) || cancellationRequested?.invoke() == true
        } else {
            jobId in cancelledJobs || cancellationRequested?.invoke() == true
        }

    private fun destroy(process: Process) {
        process.destroy()
        if (process.isAlive) process.destroyForcibly()
    }

    private companion object {
        const val FFMPEG_LIBRARY_NAME = "libffmpeg.so"
        const val MAX_OUTPUT_LINES = 24
    }
}
