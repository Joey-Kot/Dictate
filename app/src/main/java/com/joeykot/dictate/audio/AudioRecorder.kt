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
import java.util.concurrent.atomic.AtomicBoolean
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
    private val finished = AtomicBoolean(false)

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

    private var captureThread: Thread? = null

    fun start() {
        if (running || captureThread != null) return
        outputFile.parentFile?.mkdirs()
        outputFile.delete()

        val initialized = createStartedAudioRecord()
        if (initialized == null) {
            finishFailure(AppStrings.get(R.string.runtime_microphone_start_failed, "Unable to initialize or start microphone recording"), null)
            return
        }

        val (record, source, bufferSize, format) = initialized
        audioRecord = record
        captureFormat = format
        running = true
        callback.onStarted(source, format)
        captureThread = Thread(
            { captureLoop(record, bufferSize) },
            "dictate-audio-capture",
        ).apply { start() }
    }

    fun pause(): Boolean {
        synchronized(stateLock) {
            if (!running || paused) return false
            paused = true
        }
        return try {
            audioRecord?.stop()
            true
        } catch (error: Exception) {
            finishFailure(AppStrings.get(R.string.runtime_recorder_pause_failed, "Failed to pause recording: %1\$s", error.message ?: error.javaClass.simpleName), error)
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
                finishFailure(AppStrings.get(R.string.runtime_recorder_resume_failed, "Failed to resume recording: %1\$s", error.message ?: error.javaClass.simpleName), error)
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
    }

    fun isActive(): Boolean = running

    fun audioSessionId(): Int = audioRecord?.audioSessionId ?: AudioRecord.ERROR

    /** The PCM format of bytes already written to [outputFile], if recording started. */
    fun captureFormat(): Pcm16Format? = captureFormat

    private fun captureLoop(record: AudioRecord, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        try {
            BufferedOutputStream(FileOutputStream(outputFile, false), bufferSize * 2).use { output ->
                while (running) {
                    synchronized(stateLock) {
                        while (running && paused) stateLock.wait()
                    }
                    if (!running) break

                    val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        output.write(buffer, 0, read)
                        bytesWritten += read
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
            finishFailure(AppStrings.get(R.string.runtime_recording_interrupted, "Recording task interrupted"), error)
        } catch (error: Exception) {
            finishFailure(error.message ?: AppStrings.get(R.string.runtime_recording_write_failed, "Unable to write the recording"), error)
        }
    }

    private fun finishSuccess() {
        if (!finished.compareAndSet(false, true)) return
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
        releaseRecord()
        callback.onFailed(message, outputFile, hasMinimumAudio())
    }

    private fun releaseRecord() {
        val record = audioRecord
        audioRecord = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
    }

    @SuppressLint("MissingPermission")
    private fun createStartedAudioRecord(): InitializedAudioRecord? {
        // Do not set a sample rate here. Android then selects a route-dependent input rate, which
        // avoids forcing a 48 kHz USB microphone through an unnecessary 16 kHz client stream.
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        for (source in listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            val record = try {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .build()
            } catch (_: Exception) {
                null
            }
            if (record?.state == AudioRecord.STATE_INITIALIZED) {
                try {
                    record.startRecording()
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
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
                } catch (_: Exception) {
                    // Fall through to MIC when VOICE_RECOGNITION cannot actually start.
                }
            }
            runCatching { record?.release() }
        }
        return null
    }

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
    }

    private data class InitializedAudioRecord(
        val record: AudioRecord,
        val source: Int,
        val bufferSize: Int,
        val format: Pcm16Format,
    )
}
