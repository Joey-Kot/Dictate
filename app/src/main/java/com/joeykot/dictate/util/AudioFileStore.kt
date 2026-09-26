package com.joeykot.dictate.util

import android.content.Context
import com.joeykot.dictate.model.AudioContainer
import com.joeykot.dictate.model.Pcm16Format
import java.io.File
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

class AudioFileStore(context: Context) {
    private val recordingsDir = File(context.filesDir, "recordings").apply { mkdirs() }
    private val temporaryDir = File(context.cacheDir, "voice-jobs").apply { mkdirs() }

    data class RawRecording(
        val file: File,
        val format: Pcm16Format,
    )

    fun newRawFile(jobId: Long): File = File(recordingsDir, "job-$jobId.pcm")

    fun newEncodedFile(jobId: Long, container: AudioContainer): File =
        File(temporaryDir, "job-$jobId.${container.extension}")

    fun newConnectivityRawFile(jobId: Long): File = File(temporaryDir, "connectivity-$jobId.pcm")

    fun lastRecording(): RawRecording? = lastRecordingCandidates()
        .filter { isValidRaw(it.file, it.format) }
        .maxByOrNull { it.file.lastModified() }

    fun isValidRaw(file: File, format: Pcm16Format): Boolean =
        file.isFile && file.length() >= format.minimumBytesFor(MIN_VALID_DURATION_MILLIS)

    @Synchronized
    fun promoteToLast(rawFile: File, format: Pcm16Format): RawRecording? {
        if (!isValidRaw(rawFile, format)) return null
        val destination = lastRecordingFileFor(format)
        if (rawFile.canonicalFile == destination.canonicalFile) {
            return RawRecording(destination, format)
        }

        val staging = File(recordingsDir, "${destination.name}.new")
        Files.copy(rawFile.toPath(), staging.toPath(), StandardCopyOption.REPLACE_EXISTING)
        try {
            Files.move(
                staging.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        removeOtherLastRecordings(destination)
        rawFile.delete()
        return RawRecording(destination, format)
    }

    fun cleanupTemporaryFiles() {
        temporaryDir.listFiles()?.forEach { it.delete() }
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        recordingsDir.listFiles()
            ?.filter { it.name.startsWith("job-") && it.lastModified() < cutoff }
            ?.forEach { it.delete() }
        recordingsDir.listFiles()
            ?.filter { it.name.endsWith(".new") && it.name.startsWith(LAST_RECORDING_PREFIX) }
            ?.forEach { it.delete() }
    }

    private fun lastRecordingCandidates(): List<RawRecording> = buildList {
        recordingsDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            if (file.name == LEGACY_LAST_RECORDING_NAME) {
                add(RawRecording(file, Pcm16Format.LEGACY_MONO_16_KHZ))
                return@forEach
            }
            val match = LAST_RECORDING_PATTERN.matchEntire(file.name) ?: return@forEach
            val sampleRateHz = match.groupValues[1].toIntOrNull() ?: return@forEach
            val channelCount = match.groupValues[2].toIntOrNull() ?: return@forEach
            runCatching { Pcm16Format(sampleRateHz, channelCount) }
                .getOrNull()
                ?.let { format -> add(RawRecording(file, format)) }
        }
    }

    private fun lastRecordingFileFor(format: Pcm16Format): File = File(
        recordingsDir,
        "${LAST_RECORDING_PREFIX}-${format.sampleRateHz}hz-${format.channelCount}ch-s16le.pcm",
    )

    private fun removeOtherLastRecordings(keeping: File) {
        recordingsDir.listFiles()
            ?.filter { file ->
                file.isFile && file.absolutePath != keeping.absolutePath && isLastRecordingFile(file)
            }
            ?.forEach { it.delete() }
    }

    private fun isLastRecordingFile(file: File): Boolean =
        file.name == LEGACY_LAST_RECORDING_NAME || LAST_RECORDING_PATTERN.matches(file.name)

    private companion object {
        const val LEGACY_LAST_RECORDING_NAME = "last-recording.pcm"
        const val LAST_RECORDING_PREFIX = "last-recording"
        const val MIN_VALID_DURATION_MILLIS = 100L
        val LAST_RECORDING_PATTERN = Regex("^last-recording-(\\d+)hz-(\\d+)ch-s16le\\.pcm$")
    }
}
