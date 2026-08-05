package com.joeykot.dictate.util

import android.content.Context
import com.joeykot.dictate.model.AudioContainer
import java.io.File
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

class AudioFileStore(context: Context) {
    private val recordingsDir = File(context.filesDir, "recordings").apply { mkdirs() }
    private val temporaryDir = File(context.cacheDir, "voice-jobs").apply { mkdirs() }

    val lastRecordingFile: File
        get() = File(recordingsDir, LAST_RECORDING_NAME)

    fun newRawFile(jobId: Long): File = File(recordingsDir, "job-$jobId.pcm")

    fun newEncodedFile(jobId: Long, container: AudioContainer): File =
        File(temporaryDir, "job-$jobId.${container.extension}")

    fun newConnectivityRawFile(jobId: Long): File = File(temporaryDir, "connectivity-$jobId.pcm")

    fun hasLastRecording(): Boolean = isValidRaw(lastRecordingFile)

    fun isValidRaw(file: File): Boolean = file.isFile && file.length() >= MIN_VALID_RAW_BYTES

    @Synchronized
    fun promoteToLast(rawFile: File): File? {
        if (!isValidRaw(rawFile)) return null
        val last = lastRecordingFile
        if (rawFile.canonicalFile == last.canonicalFile) return last

        val staging = File(recordingsDir, "$LAST_RECORDING_NAME.new")
        Files.copy(rawFile.toPath(), staging.toPath(), StandardCopyOption.REPLACE_EXISTING)
        try {
            Files.move(
                staging.toPath(),
                last.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staging.toPath(), last.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        rawFile.delete()
        return last
    }

    fun cleanupTemporaryFiles() {
        temporaryDir.listFiles()?.forEach { it.delete() }
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        recordingsDir.listFiles()
            ?.filter { it.name.startsWith("job-") && it.lastModified() < cutoff }
            ?.forEach { it.delete() }
        File(recordingsDir, "$LAST_RECORDING_NAME.new").delete()
    }

    private companion object {
        const val LAST_RECORDING_NAME = "last-recording.pcm"
        const val MIN_VALID_RAW_BYTES = 3_200L
    }
}
