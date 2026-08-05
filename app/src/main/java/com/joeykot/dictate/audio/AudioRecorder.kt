package com.joeykot.dictate.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.joeykot.dictate.model.AudioConfig
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
        fun onStarted(source: Int)
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
            finishFailure("无法初始化或启动麦克风录音", null)
            return
        }

        val (record, source, bufferSize) = initialized
        audioRecord = record
        running = true
        callback.onStarted(source)
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
            finishFailure("暂停录音失败：${error.message ?: error.javaClass.simpleName}", error)
            false
        }
    }

    fun resume(): Boolean {
        synchronized(stateLock) {
            if (!running || !paused) return false
            return try {
                audioRecord?.startRecording()
                if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException("AudioRecord 未恢复录制状态")
                }
                paused = false
                stateLock.notifyAll()
                true
            } catch (error: Exception) {
                finishFailure("恢复录音失败：${error.message ?: error.javaClass.simpleName}", error)
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
                            AudioRecord.ERROR_DEAD_OBJECT -> "麦克风被其他应用或系统占用"
                            AudioRecord.ERROR_INVALID_OPERATION -> "录音设备进入无效状态"
                            AudioRecord.ERROR_BAD_VALUE -> "录音缓冲参数无效"
                            else -> "录音读取失败（$read）"
                        }
                        throw IOException(reason)
                    }
                }
                output.flush()
            }
            forcedFailureMessage?.let { finishFailure(it, null) } ?: finishSuccess()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            finishFailure("录音任务被中断", error)
        } catch (error: Exception) {
            finishFailure(error.message ?: "录音写入失败", error)
        }
    }

    private fun finishSuccess() {
        if (!finished.compareAndSet(false, true)) return
        releaseRecord()
        if (discardOnFinish) outputFile.delete()
        callback.onCompleted(
            outputFile,
            !discardOnFinish && bytesWritten >= MIN_VALID_BYTES,
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
        callback.onFailed(message, outputFile, bytesWritten >= MIN_VALID_BYTES)
    }

    private fun releaseRecord() {
        val record = audioRecord
        audioRecord = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
    }

    @SuppressLint("MissingPermission")
    private fun createStartedAudioRecord(): Triple<AudioRecord, Int, Int>? {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AudioConfig.INPUT_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minimum = AudioRecord.getMinBufferSize(
            AudioConfig.INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) return null
        val bufferSize = maxOf(minimum * 2, AudioConfig.INPUT_SAMPLE_RATE)

        for (source in listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            val record = try {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            } catch (_: Exception) {
                null
            }
            if (record?.state == AudioRecord.STATE_INITIALIZED) {
                try {
                    record.startRecording()
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        return Triple(record, source, bufferSize)
                    }
                } catch (_: Exception) {
                    // Fall through to MIC when VOICE_RECOGNITION cannot actually start.
                }
            }
            runCatching { record?.release() }
        }
        return null
    }

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
        const val MIN_VALID_BYTES = 3_200L
    }
}
