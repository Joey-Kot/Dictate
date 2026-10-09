package com.joeykot.dictate.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.joeykot.dictate.R
import com.joeykot.dictate.i18n.AppStrings
import com.joeykot.dictate.model.Pcm16Format
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

class AudioRecorder(
    private val outputFile: File,
    private val callback: Callback,
) {
    interface Callback {
        fun onStarted(source: Int, format: Pcm16Format)
        fun onAmplitude(amplitude: Float)
        fun onCompleted(file: File, valid: Boolean, discarded: Boolean)
        fun onFailed(message: String, file: File, valid: Boolean)
    }

    private val stateLock = Object()
    private val pcmSinkLock = Any()
    private val finished = AtomicBoolean(false)
    private val pcmPacketSink = AtomicReference<PcmPacketSink?>(null)

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    @Volatile
    private var discardOnFinish = false

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var captureFormat: Pcm16Format? = null

    @Volatile
    private var bytesWritten = 0L

    @Volatile
    private var forcedFailureMessage: String? = null

    /** Guarded by [stateLock]. */
    private var pauseAcknowledged = false

    private var captureThread: Thread? = null

    fun start() {
        if (running || captureThread != null) return
        outputFile.parentFile?.mkdirs()
        outputFile.delete()

        val initialized = createTestedAudioRecord()
        if (initialized == null) {
            finishFailure(AppStrings.get(R.string.runtime_microphone_start_failed, "Unable to initialize or start microphone recording"), null)
            return
        }

        val (record, source, bufferSize, format) = initialized
        audioRecord = record
        captureFormat = format
        var startupBarrier: CaptureStartBarrier? = null
        try {
            // Preparing the optional realtime tee can load settings and
            // secrets, parse a workflow, and start a websocket worker.  Do
            // all of that before AudioRecord begins sampling: otherwise a
            // slow preparation could overflow AudioRecord's FIFO before the
            // capture loop is able to persist and tee its first PCM packet.
            callback.onStarted(source, format)

            // The reader has to be parked and ready before AudioRecord starts
            // sampling.  Creating it afterwards can let the device FIFO
            // overflow before its first blocking read is scheduled.
            val barrier = CaptureStartBarrier()
            startupBarrier = barrier
            captureThread = Thread(
                { captureLoop(record, bufferSize, barrier) },
                "dictate-audio-capture",
            ).apply { start() }
            if (!barrier.awaitReaderReady(CAPTURE_THREAD_READY_TIMEOUT_MILLIS)) {
                throw IllegalStateException(
                    AppStrings.get(R.string.runtime_microphone_start_failed, "Unable to initialize or start microphone recording"),
                )
            }

            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException(
                    AppStrings.get(R.string.runtime_recorder_not_resumed, "AudioRecord did not resume recording"),
                )
            }
            running = true
            barrier.releaseReader()
        } catch (error: Exception) {
            // If AudioRecord could not start after the reader was created,
            // release the parked thread so it can exit instead of leaking.
            startupBarrier?.releaseReader()
            finishFailure(
                AppStrings.get(
                    R.string.runtime_microphone_start_failed,
                    "Unable to initialize or start microphone recording",
                ),
                error,
            )
        }
    }

    fun pause(): Boolean {
        synchronized(stateLock) {
            if (!running || paused) return false
            paused = true
            pauseAcknowledged = false
        }
        return try {
            audioRecord?.stop()
            if (waitForPauseBoundary()) {
                true
            } else {
                // The capture thread may still be completing a blocking read
                // or writing its final buffer.  Let that owner publish the
                // terminal callback after it has closed the file instead of
                // exposing a raw PCM path that is still being written.
                fail(
                    AppStrings.get(
                        R.string.runtime_recorder_pause_failed,
                        "Failed to pause recording: capture did not reach a pause boundary",
                    ),
                )
                false
            }
        } catch (error: Exception) {
            fail(
                AppStrings.get(
                    R.string.runtime_recorder_pause_failed,
                    "Failed to pause recording: %1\$s",
                    error.message ?: error.javaClass.simpleName,
                ),
            )
            false
        }
    }

    fun resume(): Boolean {
        synchronized(stateLock) {
            if (!running || !paused) return false
            return try {
                audioRecord?.startRecording()
                if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException(AppStrings.get(R.string.runtime_recorder_not_resumed, "AudioRecord did not resume recording"))
                }
                paused = false
                stateLock.notifyAll()
                true
            } catch (error: Exception) {
                // The reader is paused on its own state boundary.  It still
                // owns the output stream, so let it publish the terminal
                // callback after wake-up rather than exposing the file early.
                fail(
                    AppStrings.get(
                        R.string.runtime_recorder_resume_failed,
                        "Failed to resume recording: %1\$s",
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
                false
            }
        }
    }

    fun stop(discard: Boolean) {
        discardOnFinish = discard
        running = false
        synchronized(stateLock) {
            paused = false
            stateLock.notifyAll()
        }
        runCatching { audioRecord?.stop() }
    }

    fun fail(message: String) {
        forcedFailureMessage = message
        running = false
        synchronized(stateLock) {
            paused = false
            stateLock.notifyAll()
        }
        runCatching { audioRecord?.stop() }
        // stop() should unblock a blocking read; interrupt also wakes the
        // paused-state wait if a device reports a stop failure.
        captureThread?.interrupt()
    }

    fun isActive(): Boolean = running

    fun audioSessionId(): Int = audioRecord?.audioSessionId ?: AudioRecord.ERROR

    /** The PCM format of bytes already written to [outputFile], if recording started. */
    fun captureFormat(): Pcm16Format? = captureFormat

    /**
     * Atomically swaps the optional live PCM destination.
     *
     * The returned destination is no longer reachable from capture. Callers
     * own closing it after a pause boundary, or after deciding to abandon a
     * live session. The recorder closes its still-attached destination before
     * reporting completion or failure.
     */
    fun replacePcmPacketSink(sink: PcmPacketSink?): PcmPacketSink? = synchronized(pcmSinkLock) {
        pcmPacketSink.getAndSet(sink)
    }

    /**
     * Detaches and closes the live PCM destination after [pause] has reached
     * its capture boundary. Queued packets remain available to its consumer.
     */
    fun closePcmPacketSink(): PcmPacketSink? {
        val sink = synchronized(pcmSinkLock) { pcmPacketSink.getAndSet(null) }
        runCatching { sink?.close() }
        return sink
    }

    private fun captureLoop(
        record: AudioRecord,
        bufferSize: Int,
        startupBarrier: CaptureStartBarrier? = null,
    ) {
        val buffer = ByteArray(bufferSize)
        try {
            startupBarrier?.awaitRecordingStart()
            if (!running) return
            BufferedOutputStream(FileOutputStream(outputFile, false), bufferSize * 2).use { output ->
                while (running) {
                    synchronized(stateLock) {
                        while (running && paused) {
                            pauseAcknowledged = true
                            stateLock.notifyAll()
                            stateLock.wait()
                        }
                    }
                    if (!running) break

                    val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        output.write(buffer, 0, read)
                        bytesWritten += read
                        offerPcmPacket(buffer, read)
                        callback.onAmplitude(calculateAmplitude(buffer, read))
                    } else if (running && !paused) {
                        val reason = when (read) {
                            AudioRecord.ERROR_DEAD_OBJECT -> AppStrings.get(R.string.runtime_microphone_busy, "The microphone is in use by another app or the system")
                            AudioRecord.ERROR_INVALID_OPERATION -> AppStrings.get(R.string.runtime_recorder_invalid_state, "The recording device entered an invalid state")
                            AudioRecord.ERROR_BAD_VALUE -> AppStrings.get(R.string.runtime_recorder_invalid_buffer, "Invalid recording buffer parameters")
                            else -> AppStrings.get(R.string.runtime_recorder_read_failed, "Unable to read audio (%1\$d)", read)
                        }
                        throw IOException(reason)
                    }
                }
                output.flush()
            }
            forcedFailureMessage?.let { finishFailure(it, null) } ?: finishSuccess()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            finishFailure(
                forcedFailureMessage
                    ?: AppStrings.get(R.string.runtime_recording_interrupted, "Recording task interrupted"),
                error,
            )
        } catch (error: Exception) {
            finishFailure(error.message ?: AppStrings.get(R.string.runtime_recording_write_failed, "Unable to write the recording"), error)
        }
    }

    private fun finishSuccess() {
        if (!finished.compareAndSet(false, true)) return
        closePcmPacketSink()
        releaseRecord()
        if (discardOnFinish) outputFile.delete()
        callback.onCompleted(
            outputFile,
            !discardOnFinish && hasMinimumAudio(),
            discardOnFinish,
        )
    }

    private fun finishFailure(message: String, cause: Throwable?) {
        if (!finished.compareAndSet(false, true)) return
        running = false
        synchronized(stateLock) {
            paused = false
            stateLock.notifyAll()
        }
        closePcmPacketSink()
        releaseRecord()
        callback.onFailed(message, outputFile, hasMinimumAudio())
    }

    private fun releaseRecord() {
        val record = audioRecord
        audioRecord = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
    }

    private fun offerPcmPacket(buffer: ByteArray, length: Int) {
        synchronized(pcmSinkLock) {
            pcmPacketSink.get()?.offer(requireNotNull(captureFormat), buffer, length)
        }
    }

    /**
     * AudioRecord.stop() unblocks a pending read asynchronously. Waiting for
     * the capture loop to observe [paused] makes a pause a clean PCM boundary:
     * the final locally-written packet is still delivered to the live tee
     * before the tee is detached and finalized.
     */
    private fun waitForPauseBoundary(): Boolean {
        val deadlineNanos = System.nanoTime() + PAUSE_BOUNDARY_TIMEOUT_MILLIS * NANOS_PER_MILLISECOND
        synchronized(stateLock) {
            while (running && !pauseAcknowledged) {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) return false
                val waitMillis = (remainingNanos / NANOS_PER_MILLISECOND).coerceAtLeast(1L)
                stateLock.wait(waitMillis)
            }
            return running && pauseAcknowledged
        }
    }

    @SuppressLint("MissingPermission")
    private fun createTestedAudioRecord(): InitializedAudioRecord? {
        // Do not set a sample rate here. Android then selects a route-dependent input rate, which
        // avoids forcing a 48 kHz USB microphone through an unnecessary 16 kHz client stream.
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        for (source in listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            // Some devices report VOICE_RECOGNITION as initialized but reject
            // the actual start. Probe a temporary recorder first so the
            // historical fallback to MIC remains available. It must not be
            // reused for capture: AudioManager delivers configuration changes
            // asynchronously, and a probe session must not share a session
            // with the real recording.
            val probe = createAudioRecord(source, format) ?: continue
            var canStart = false
            var stopped = false
            try {
                if (probe.state == AudioRecord.STATE_INITIALIZED) {
                    canStart = runCatching {
                        probe.startRecording()
                        probe.recordingState == AudioRecord.RECORDSTATE_RECORDING
                    }.getOrDefault(false)
                    stopped = runCatching { probe.stop() }.isSuccess
                }
            } finally {
                runCatching { probe.release() }
            }
            if (!canStart || !stopped) continue

            // Create a new, untouched recorder for capture. Its actual start
            // remains below the realtime tee and reader-start barrier.
            val record = createAudioRecord(source, format) ?: continue
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { record.release() }
                continue
            }
            val pcmFormat = Pcm16Format(
                sampleRateHz = record.sampleRate,
                channelCount = record.channelCount,
            )
            val nativeBufferBytes = record.bufferSizeInFrames.toLong() * pcmFormat.bytesPerFrame
            val readBufferBytes = maxOf(
                nativeBufferBytes,
                pcmFormat.bytesPerSecond / READS_PER_SECOND,
            ).coerceAtMost(MAX_READ_BUFFER_BYTES.toLong()).toInt()
            return InitializedAudioRecord(record, source, readBufferBytes, pcmFormat)
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(source: Int, format: AudioFormat): AudioRecord? = runCatching {
        AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(format)
            .build()
    }.getOrNull()

    private fun hasMinimumAudio(): Boolean = captureFormat
        ?.let { bytesWritten >= it.minimumBytesFor(MIN_VALID_DURATION_MILLIS) }
        ?: false

    private fun calculateAmplitude(buffer: ByteArray, length: Int): Float {
        var sum = 0.0
        var samples = 0
        var index = 0
        while (index + 1 < length) {
            val sample = ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xff)).toShort()
            val normalized = sample.toDouble() / Short.MAX_VALUE.toDouble()
            sum += normalized * normalized
            samples++
            index += 2
        }
        return if (samples == 0) 0f else sqrt(sum / samples).toFloat().coerceIn(0f, 1f)
    }

    private companion object {
        const val MIN_VALID_DURATION_MILLIS = 100L
        const val READS_PER_SECOND = 4L
        const val MAX_READ_BUFFER_BYTES = 1_048_576
        const val PAUSE_BOUNDARY_TIMEOUT_MILLIS = 2_000L
        const val CAPTURE_THREAD_READY_TIMEOUT_MILLIS = 2_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }

    private data class InitializedAudioRecord(
        val record: AudioRecord,
        val source: Int,
        val bufferSize: Int,
        val format: Pcm16Format,
    )
}

/**
 * Synchronizes the initial reader scheduling with AudioRecord startup without
 * allowing the reader to call [AudioRecord.read] before recording begins.
 */
internal class CaptureStartBarrier {
    private val readerReady = CountDownLatch(1)
    private val recordingStarted = CountDownLatch(1)

    fun awaitReaderReady(timeoutMillis: Long): Boolean =
        readerReady.await(timeoutMillis, TimeUnit.MILLISECONDS)

    @Throws(InterruptedException::class)
    fun awaitRecordingStart() {
        readerReady.countDown()
        recordingStarted.await()
    }

    fun releaseReader() {
        recordingStarted.countDown()
    }
}
