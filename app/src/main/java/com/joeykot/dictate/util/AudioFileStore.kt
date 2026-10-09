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

    /** Root for one-attempt segmented-upload directories. The preparer creates a unique child. */
    fun segmentedUploadTemporaryRoot(): File = temporaryDir.apply { mkdirs() }

    fun newConnectivityRawFile(jobId: Long): File = File(temporaryDir, "connectivity-$jobId.pcm")

    fun lastRecording(): RawRecording? = lastRecordingCandidates()
        .filter { isValidRaw(it.file, it.format) }
        .maxByOrNull { it.file.lastModified() }

    /**
     * Returns the persisted segmented-upload snapshot for [recording], if one
     * belongs to the current "last recording" PCM file.  The protocol layer
     * owns the JSON schema; this store deliberately treats it as opaque text
     * so a recording can retain its frozen source-frame partition across
     * process restarts without coupling storage to an execution implementation.
     */
    @Synchronized
    fun lastRecordingSegmentedUploadSnapshot(recording: RawRecording): String? {
        if (!isLastRecordingFile(recording.file) || !isValidRaw(recording.file, recording.format)) return null
        val sidecar = segmentedUploadSnapshotFile(recording.file)
        if (!sidecar.exists()) return null
        if (!sidecar.isFile || sidecar.length() !in 1L..MAX_SEGMENTED_UPLOAD_SNAPSHOT_BYTES) {
            sidecar.delete()
            return null
        }
        return try {
            sidecar.readText(Charsets.UTF_8).takeIf { it.isNotBlank() } ?: run {
                sidecar.delete()
                null
            }
        } catch (_: Exception) {
            sidecar.delete()
            null
        }
    }

    /**
     * Atomically associates an opaque frozen segmented-upload snapshot with
     * the current last recording.  A new microphone recording removes this
     * sidecar during [promoteToLast], so a plan can never be reused for a
     * different PCM timeline that happens to share its sample-rate filename.
     */
    @Synchronized
    fun saveLastRecordingSegmentedUploadSnapshot(recording: RawRecording, snapshot: String): Boolean {
        if (!isLastRecordingFile(recording.file) || !isValidRaw(recording.file, recording.format) || snapshot.isBlank()) {
            return false
        }
        val bytes = snapshot.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_SEGMENTED_UPLOAD_SNAPSHOT_BYTES) return false

        val destination = segmentedUploadSnapshotFile(recording.file)
        val staging = File(recordingsDir, "${destination.name}.new")
        return try {
            staging.writeBytes(bytes)
            moveReplacing(staging, destination)
            true
        } catch (_: Exception) {
            staging.delete()
            false
        }
    }

    /**
     * Removes a persisted snapshot only when it still contains [expectedSnapshot].
     *
     * Decoding happens outside this store, so the controller passes back the
     * opaque bytes it read after finding a malformed or stale plan.  Checking
     * those bytes while synchronized with [saveLastRecordingSegmentedUploadSnapshot]
     * prevents an old reader from deleting a newer atomically-published plan.
     */
    @Synchronized
    fun clearLastRecordingSegmentedUploadSnapshotIfUnchanged(
        recording: RawRecording,
        expectedSnapshot: String,
    ): Boolean {
        if (!isLastRecordingFile(recording.file) || expectedSnapshot.isBlank()) return false
        val sidecar = segmentedUploadSnapshotFile(recording.file)
        if (!sidecar.exists()) return false
        if (!sidecar.isFile || sidecar.length() !in 1L..MAX_SEGMENTED_UPLOAD_SNAPSHOT_BYTES) {
            return sidecar.delete()
        }
        return try {
            if (sidecar.readText(Charsets.UTF_8) != expectedSnapshot) {
                false
            } else {
                sidecar.delete()
            }
        } catch (_: Exception) {
            sidecar.delete()
        }
    }

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
            moveReplacing(staging, destination)
        } catch (error: Exception) {
            staging.delete()
            throw error
        }
        segmentedUploadSnapshotFile(destination).delete()
        removeOtherLastRecordings(destination)
        rawFile.delete()
        return RawRecording(destination, format)
    }

    fun cleanupTemporaryFiles() {
        // Segmented-upload attempts own a unique nested directory. Restrict
        // recursive cleanup to direct children of this app-private temporary
        // root so a process death cannot leave encoded slices behind.
        temporaryDir.listFiles()?.forEach { it.deleteRecursively() }
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        recordingsDir.listFiles()
            ?.filter { it.name.startsWith("job-") && it.lastModified() < cutoff }
            ?.forEach { it.delete() }
        recordingsDir.listFiles()
            ?.filter { it.name.endsWith(".new") && it.name.startsWith(LAST_RECORDING_PREFIX) }
            ?.forEach { it.delete() }
        recordingsDir.listFiles()
            ?.filter { it.name.endsWith(SEGMENTED_UPLOAD_SNAPSHOT_SUFFIX) }
            ?.filter { sidecar -> !File(recordingsDir, sidecar.name.removeSuffix(SEGMENTED_UPLOAD_SNAPSHOT_SUFFIX)).isFile }
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
            ?.forEach { file ->
                segmentedUploadSnapshotFile(file).delete()
                file.delete()
            }
    }

    private fun segmentedUploadSnapshotFile(recording: File): File =
        File(recordingsDir, "${recording.name}$SEGMENTED_UPLOAD_SNAPSHOT_SUFFIX")

    private fun moveReplacing(staging: File, destination: File) {
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
    }

    private fun isLastRecordingFile(file: File): Boolean =
        file.name == LEGACY_LAST_RECORDING_NAME || LAST_RECORDING_PATTERN.matches(file.name)

    private companion object {
        const val LEGACY_LAST_RECORDING_NAME = "last-recording.pcm"
        const val LAST_RECORDING_PREFIX = "last-recording"
        const val MIN_VALID_DURATION_MILLIS = 100L
        const val SEGMENTED_UPLOAD_SNAPSHOT_SUFFIX = ".segmented-upload.json"
        // A user can deliberately select short positive segment lengths for
        // a long recording, so a frame-range plan can contain thousands of
        // entries. Keep the opaque sidecar bounded without silently losing a
        // valid retry/retranscription plan in that supported configuration.
        const val MAX_SEGMENTED_UPLOAD_SNAPSHOT_BYTES = 4L * 1024L * 1024L
        val LAST_RECORDING_PATTERN = Regex("^last-recording-(\\d+)hz-(\\d+)ch-s16le\\.pcm$")
    }
}
