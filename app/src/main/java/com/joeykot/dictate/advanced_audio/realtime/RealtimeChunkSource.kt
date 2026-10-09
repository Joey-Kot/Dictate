package com.joeykot.dictate.advanced_audio.realtime

import com.joeykot.dictate.advanced_audio.RealtimeAudioStream
import com.joeykot.dictate.advanced_audio.http.AdvancedCancellationToken
import com.joeykot.dictate.model.Pcm16Format
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One converted realtime audio frame and the length it occupies on the media
 * timeline. Bytes always belong to the workflow's declared target format.
 */
data class RealtimeAudioChunk(
    val bytes: ByteArray,
    val durationMillis: Long,
) {
    init {
        require(bytes.isNotEmpty()) { "Realtime audio chunks cannot be empty" }
        require(durationMillis >= 0L) { "Realtime audio chunk duration cannot be negative" }
    }
}

/**
 * A finite producer of target-format chunks for one websocket session.
 *
 * A recorded replay source returns data as quickly as local storage permits,
 * so [requiresRealtimePacing] remains enabled. A live recorder tee uses the
 * same interface with that flag disabled because microphone data has already
 * arrived on the media timeline. For that live case, [isFinished] must only
 * turn true after recording has stopped and all pending audio has been made
 * available to the runner.
 */
interface RealtimeChunkSource : AutoCloseable {
    fun nextChunk(cancellation: AdvancedCancellationToken): RealtimeAudioChunk?

    val requiresRealtimePacing: Boolean
        get() = true

    /** Whether no later chunk can arrive from this source. */
    val isFinished: Boolean
        get() = false

    override fun close() = Unit
}

/**
 * Stateful raw PCM16 converter and chunker shared by replay and a future
 * live recorder tee. It applies a bounded linear resampler and deterministic
 * channel mapping while retaining only the frames needed at the resampling
 * boundary; it never materializes an entire recording in memory.
 *
 * When targeting mono, all source channels are averaged. When targeting more
 * than one channel, source channels repeat in order (`out[n] = in[n % inC]`),
 * matching the established Windows realtime conversion behavior.
 */
class Pcm16RealtimeChunker(
    private val sourceFormat: Pcm16Format,
    private val target: RealtimeAudioStream,
) {
    private val targetFormat: Pcm16Format
    private val resampler: IncrementalPcm16Resampler
    private val pendingBytes = ByteQueue()
    private val ready = ArrayDeque<RealtimeAudioChunk>()
    private val chunkFrameNumerator: Long
    private var chunkFrameRemainder = 0L
    private var nextChunkFrames = 0
    private var emittedOutputFrames = 0L
    private var finished = false

    init {
        if (!target.codec.equals("pcm_s16le", ignoreCase = true)) {
            throw RealtimeChunkSourceException.UnsupportedCodec
        }
        if (target.chunkDurationMs <= 0) throw RealtimeChunkSourceException.InvalidTargetFormat
        targetFormat = Pcm16Format(sampleRateHz = target.sampleRate, channelCount = target.channels)
        if (sourceFormat.bytesPerFrame <= 0 || targetFormat.bytesPerFrame <= 0) {
            throw RealtimeChunkSourceException.InvalidTargetFormat
        }
        chunkFrameNumerator = try {
            Math.multiplyExact(target.sampleRate.toLong(), target.chunkDurationMs.toLong())
        } catch (_: ArithmeticException) {
            throw RealtimeChunkSourceException.InvalidTargetFormat
        }
        if (chunkFrameNumerator < MILLIS_PER_SECOND) {
            // The next logical interval would contain zero whole sample
            // frames, which cannot be encoded as a websocket PCM chunk.
            throw RealtimeChunkSourceException.InvalidTargetFormat
        }
        resampler = IncrementalPcm16Resampler(sourceFormat, targetFormat)
        advanceChunkSize()
    }

    /** Accepts one complete-frame PCM16 source packet and returns ready chunks. */
    fun push(pcm16le: ByteArray): List<RealtimeAudioChunk> {
        if (finished) throw RealtimeChunkSourceException.InvalidPcmInput
        appendOutput(resampler.push(pcm16le))
        splitReady()
        return takeReady()
    }

    /** Flushes the final interpolation boundary and any short last chunk. */
    fun finish(): List<RealtimeAudioChunk> {
        if (finished) return takeReady()
        appendOutput(resampler.finish())
        splitReady()
        if (!pendingBytes.isEmpty()) {
            val bytes = pendingBytes.take(pendingBytes.size)
            if (bytes.size % targetFormat.bytesPerFrame != 0) {
                throw RealtimeChunkSourceException.InvalidPcmInput
            }
            val frames = bytes.size / targetFormat.bytesPerFrame
            val startFrames = emittedOutputFrames
            emittedOutputFrames = saturatingAdd(emittedOutputFrames, frames.toLong())
            ready += RealtimeAudioChunk(
                bytes = bytes,
                durationMillis = mediaMillis(emittedOutputFrames) - mediaMillis(startFrames),
            )
        }
        finished = true
        return takeReady()
    }

    private fun appendOutput(samples: ShortArray) {
        samples.forEach { sample -> pendingBytes.appendShortLittleEndian(sample) }
    }

    private fun splitReady() {
        while (pendingBytes.size >= nextChunkBytes()) {
            val bytes = pendingBytes.take(nextChunkBytes())
            val frames = nextChunkFrames
            emittedOutputFrames = saturatingAdd(emittedOutputFrames, frames.toLong())
            ready += RealtimeAudioChunk(bytes, target.chunkDurationMs.toLong())
            advanceChunkSize()
        }
    }

    private fun takeReady(): List<RealtimeAudioChunk> = buildList {
        while (ready.isNotEmpty()) add(ready.removeFirst())
    }

    private fun nextChunkBytes(): Int = try {
        Math.multiplyExact(nextChunkFrames, targetFormat.bytesPerFrame)
    } catch (_: ArithmeticException) {
        throw RealtimeChunkSourceException.InvalidTargetFormat
    }

    private fun advanceChunkSize() {
        val framesWithRemainder = try {
            Math.addExact(chunkFrameRemainder, chunkFrameNumerator)
        } catch (_: ArithmeticException) {
            throw RealtimeChunkSourceException.InvalidTargetFormat
        }
        val frames = framesWithRemainder / MILLIS_PER_SECOND
        chunkFrameRemainder = framesWithRemainder % MILLIS_PER_SECOND
        if (frames <= 0L || frames > Int.MAX_VALUE.toLong()) {
            throw RealtimeChunkSourceException.InvalidTargetFormat
        }
        nextChunkFrames = frames.toInt()
    }

    private fun mediaMillis(frames: Long): Long {
        val sampleRate = targetFormat.sampleRateHz.toLong()
        val completeSeconds = frames / sampleRate
        val remainingFrames = frames % sampleRate
        val secondsMillis = if (completeSeconds > Long.MAX_VALUE / MILLIS_PER_SECOND) {
            Long.MAX_VALUE
        } else {
            completeSeconds * MILLIS_PER_SECOND
        }
        val remainderMillis = remainingFrames * MILLIS_PER_SECOND / sampleRate
        return saturatingAdd(secondsMillis, remainderMillis)
    }

    private fun saturatingAdd(left: Long, right: Long): Long = when {
        right > 0L && left > Long.MAX_VALUE - right -> Long.MAX_VALUE
        right < 0L && left < Long.MIN_VALUE - right -> Long.MIN_VALUE
        else -> left + right
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}

/**
 * Replays a raw PCM16 little-endian recording. [sourceFormat] is the actual
 * Android capture format, while [target] is the workflow format transmitted
 * to the provider. The default preserves the convenient already-matching
 * case, but callers with AudioRecord metadata should always pass it explicitly.
 */
class Pcm16ReplayChunkSource(
    pcmFile: File,
    private val target: RealtimeAudioStream,
    val sourceFormat: Pcm16Format = Pcm16Format(
        sampleRateHz = target.sampleRate,
        channelCount = target.channels,
    ),
) : RealtimeChunkSource {
    private val chunker: Pcm16RealtimeChunker
    private val stream: InputStream
    private var remainingBytes: Long
    private val ready = ArrayDeque<RealtimeAudioChunk>()
    private var inputFinished = false
    private var closed = false

    init {
        if (!pcmFile.isFile || !pcmFile.canRead()) {
            throw RealtimeChunkSourceException.AudioUnavailable
        }
        val size = pcmFile.length()
        if (size < 0L || size % sourceFormat.bytesPerFrame.toLong() != 0L) {
            throw RealtimeChunkSourceException.InvalidPcmInput
        }
        chunker = Pcm16RealtimeChunker(sourceFormat, target)
        stream = try {
            FileInputStream(pcmFile)
        } catch (_: IOException) {
            throw RealtimeChunkSourceException.AudioUnavailable
        }
        remainingBytes = size
    }

    override fun nextChunk(cancellation: AdvancedCancellationToken): RealtimeAudioChunk? {
        cancellation.throwIfCancelled()
        if (closed) return null
        while (ready.isEmpty() && !inputFinished) {
            if (remainingBytes > 0L) {
                val bytes = ByteArray(nextReadSize())
                readFully(bytes, cancellation)
                remainingBytes -= bytes.size.toLong()
                ready.addAll(chunker.push(bytes))
            } else {
                ready.addAll(chunker.finish())
                inputFinished = true
            }
        }
        if (ready.isNotEmpty()) return ready.removeFirst()
        close()
        return null
    }

    override val requiresRealtimePacing: Boolean
        get() = true

    override val isFinished: Boolean
        get() = inputFinished && ready.isEmpty()

    override fun close() {
        if (closed) return
        closed = true
        runCatching { stream.close() }
    }

    private fun nextReadSize(): Int {
        val preferred = max(sourceFormat.bytesPerFrame, REPLAY_READ_BYTES / sourceFormat.bytesPerFrame) *
            sourceFormat.bytesPerFrame
        return min(remainingBytes, preferred.toLong()).toInt()
    }

    private fun readFully(bytes: ByteArray, cancellation: AdvancedCancellationToken) {
        var offset = 0
        try {
            while (offset < bytes.size) {
                cancellation.throwIfCancelled()
                val count = stream.read(bytes, offset, bytes.size - offset)
                if (count < 0) throw RealtimeChunkSourceException.InvalidPcmInput
                if (count == 0) {
                    val single = stream.read()
                    if (single < 0) throw RealtimeChunkSourceException.InvalidPcmInput
                    bytes[offset] = single.toByte()
                    offset += 1
                } else {
                    offset += count
                }
            }
        } catch (error: RealtimeChunkSourceException) {
            throw error
        } catch (_: IOException) {
            throw RealtimeChunkSourceException.AudioReadFailed
        }
    }

    private companion object {
        const val REPLAY_READ_BYTES = 64 * 1024
    }
}

/** Stateful, bounded linear PCM16 resampler. */
private class IncrementalPcm16Resampler(
    private val source: Pcm16Format,
    private val target: Pcm16Format,
) {
    private val samples = FloatSampleBuffer()
    private var firstStoredFrame = 0L
    private var totalInputFrames = 0L
    private var nextOutputFrame = 0L
    private var finished = false

    fun push(input: ByteArray): ShortArray {
        if (finished || input.size % source.bytesPerFrame != 0) {
            throw RealtimeChunkSourceException.InvalidPcmInput
        }
        val frames = input.size / source.bytesPerFrame
        if (frames == 0) return ShortArray(0)
        totalInputFrames = try {
            Math.addExact(totalInputFrames, frames.toLong())
        } catch (_: ArithmeticException) {
            throw RealtimeChunkSourceException.InvalidPcmInput
        }
        var index = 0
        repeat(frames * source.channelCount) {
            val low = input[index].toInt() and 0xff
            val high = input[index + 1].toInt()
            samples.append(((high shl 8 or low).toShort().toInt()) / PCM16_SCALE)
            index += PCM16_BYTES_PER_SAMPLE
        }
        return drain(finalInput = false)
    }

    fun finish(): ShortArray {
        if (finished) return ShortArray(0)
        finished = true
        return drain(finalInput = true)
    }

    private fun drain(finalInput: Boolean): ShortArray {
        // Keep the emitted media duration at floor(inputFrames * outRate /
        // inRate), even before the source closes.  `second < totalInputFrames`
        // alone would allow a downsampler to emit one frame whose timestamp is
        // valid between the final two currently-known samples but lies beyond
        // that duration. Once such a frame has gone over a live websocket it
        // cannot be withdrawn at finish. Holding it until another source
        // frame arrives preserves exact duration and remains packet-invariant.
        val availableOutputFrames = multiplyDivideFloor(
            totalInputFrames,
            target.sampleRateHz.toLong(),
            source.sampleRateHz.toLong(),
        )
        val output = ShortAccumulator()
        while (nextOutputFrame < availableOutputFrames) {
            val positionNumerator = try {
                Math.multiplyExact(nextOutputFrame, source.sampleRateHz.toLong())
            } catch (_: ArithmeticException) {
                throw RealtimeChunkSourceException.InvalidPcmInput
            }
            val first = positionNumerator / target.sampleRateHz.toLong()
            if (first >= totalInputFrames) break
            val secondRaw = try {
                Math.addExact(first, 1L)
            } catch (_: ArithmeticException) {
                throw RealtimeChunkSourceException.InvalidPcmInput
            }
            if (!finalInput && secondRaw >= totalInputFrames) break
            val second = if (finalInput) min(secondRaw, totalInputFrames - 1L) else secondRaw
            val fraction = (positionNumerator % target.sampleRateHz.toLong()).toFloat() / target.sampleRateHz.toFloat()
            appendInterpolatedFrame(output, first, second, fraction)
            nextOutputFrame = try {
                Math.addExact(nextOutputFrame, 1L)
            } catch (_: ArithmeticException) {
                throw RealtimeChunkSourceException.InvalidPcmInput
            }
        }
        discardConsumedFrames()
        return output.toArray()
    }

    private fun appendInterpolatedFrame(
        output: ShortAccumulator,
        first: Long,
        second: Long,
        fraction: Float,
    ) {
        fun interpolate(channel: Int): Float {
            val before = sampleAt(first, channel)
            val after = sampleAt(second, channel)
            return before + (after - before) * fraction
        }
        if (target.channelCount == 1) {
            var sum = 0f
            repeat(source.channelCount) { channel -> sum += interpolate(channel) }
            output.append(floatToPcm16(sum / source.channelCount.toFloat()))
            return
        }
        repeat(target.channelCount) { channel ->
            output.append(floatToPcm16(interpolate(channel % source.channelCount)))
        }
    }

    private fun sampleAt(frame: Long, channel: Int): Float {
        val relative = frame - firstStoredFrame
        if (relative < 0L || relative > Int.MAX_VALUE.toLong()) {
            throw RealtimeChunkSourceException.InvalidPcmInput
        }
        val sampleIndex = try {
            Math.addExact(
                Math.multiplyExact(relative.toInt(), source.channelCount),
                channel,
            )
        } catch (_: ArithmeticException) {
            throw RealtimeChunkSourceException.InvalidPcmInput
        }
        return samples[sampleIndex] ?: throw RealtimeChunkSourceException.InvalidPcmInput
    }

    private fun discardConsumedFrames() {
        val nextPositionNumerator = try {
            Math.multiplyExact(nextOutputFrame, source.sampleRateHz.toLong())
        } catch (_: ArithmeticException) {
            throw RealtimeChunkSourceException.InvalidPcmInput
        }
        val nextFirstFrame = min(
            totalInputFrames,
            nextPositionNumerator / target.sampleRateHz.toLong(),
        )
        // The next interpolation still needs its floor(position) frame as
        // `first`. Only frames strictly before it are no longer observable.
        // This matters for upsampling: after output positions 0, 1/3 and 2/3,
        // the next output begins at source frame 1 and must retain that frame.
        val discardBefore = (nextFirstFrame - 1L).coerceAtLeast(0L)
        val requestedDiscard = (discardBefore - firstStoredFrame).coerceAtLeast(0L)
        val storedFrames = samples.size / source.channelCount
        val discardFrames = min(requestedDiscard, storedFrames.toLong()).toInt()
        if (discardFrames == 0) return
        samples.discard(discardFrames * source.channelCount)
        firstStoredFrame = saturatingAdd(firstStoredFrame, discardFrames.toLong())
    }

    private fun multiplyDivideFloor(value: Long, multiplier: Long, divisor: Long): Long {
        if (value == 0L) return 0L
        val quotient = value / divisor
        val remainder = value % divisor
        val left = if (quotient > Long.MAX_VALUE / multiplier) Long.MAX_VALUE else quotient * multiplier
        val right = remainder * multiplier / divisor
        return saturatingAdd(left, right)
    }

    private fun saturatingAdd(left: Long, right: Long): Long = when {
        right > 0L && left > Long.MAX_VALUE - right -> Long.MAX_VALUE
        right < 0L && left < Long.MIN_VALUE - right -> Long.MIN_VALUE
        else -> left + right
    }

    private fun floatToPcm16(value: Float): Short = (value.coerceIn(-1f, 1f) * Short.MAX_VALUE.toFloat())
        .roundToInt()
        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        .toShort()

    private companion object {
        const val PCM16_BYTES_PER_SAMPLE = 2
        const val PCM16_SCALE = 32768f
    }
}

/** Primitive FIFO retains only the frame window needed by the resampler. */
private class FloatSampleBuffer {
    private var values = FloatArray(INITIAL_CAPACITY)
    private var offset = 0
    var size: Int = 0
        private set

    operator fun get(index: Int): Float? = if (index in 0 until size) values[offset + index] else null

    fun append(value: Float) {
        ensureCapacity(1)
        values[offset + size] = value
        size += 1
    }

    fun discard(count: Int) {
        if (count !in 0..size) throw RealtimeChunkSourceException.InvalidPcmInput
        offset += count
        size -= count
        if (size == 0) {
            offset = 0
        } else if (offset >= values.size / 2) {
            values.copyInto(values, 0, offset, offset + size)
            offset = 0
        }
    }

    private fun ensureCapacity(additional: Int) {
        if (values.size - (offset + size) >= additional) return
        if (offset > 0) {
            values.copyInto(values, 0, offset, offset + size)
            offset = 0
        }
        if (values.size - size >= additional) return
        val required = try {
            Math.addExact(size, additional)
        } catch (_: ArithmeticException) {
            throw RealtimeChunkSourceException.InvalidPcmInput
        }
        val expanded = FloatArray(max(required, values.size * 2))
        values.copyInto(expanded, 0, 0, size)
        values = expanded
    }

    private companion object {
        const val INITIAL_CAPACITY = 4_096
    }
}

private class ShortAccumulator {
    private var values = ShortArray(INITIAL_CAPACITY)
    private var count = 0

    fun append(value: Short) {
        if (count == values.size) values = values.copyOf(values.size * 2)
        values[count++] = value
    }

    fun toArray(): ShortArray = values.copyOf(count)

    private companion object {
        const val INITIAL_CAPACITY = 4_096
    }
}

private class ByteQueue {
    private var values = ByteArray(INITIAL_CAPACITY)
    private var offset = 0
    var size: Int = 0
        private set

    fun appendShortLittleEndian(value: Short) {
        ensureCapacity(PCM16_BYTES_PER_SAMPLE)
        values[offset + size] = value.toByte()
        values[offset + size + 1] = (value.toInt() ushr 8).toByte()
        size += PCM16_BYTES_PER_SAMPLE
    }

    fun take(count: Int): ByteArray {
        if (count !in 0..size) throw RealtimeChunkSourceException.InvalidPcmInput
        val output = values.copyOfRange(offset, offset + count)
        offset += count
        size -= count
        if (size == 0) {
            offset = 0
        } else if (offset >= values.size / 2) {
            values.copyInto(values, 0, offset, offset + size)
            offset = 0
        }
        return output
    }

    fun isEmpty(): Boolean = size == 0

    private fun ensureCapacity(additional: Int) {
        if (values.size - (offset + size) >= additional) return
        if (offset > 0) {
            values.copyInto(values, 0, offset, offset + size)
            offset = 0
        }
        if (values.size - size >= additional) return
        val required = try {
            Math.addExact(size, additional)
        } catch (_: ArithmeticException) {
            throw RealtimeChunkSourceException.InvalidPcmInput
        }
        val expanded = ByteArray(max(required, values.size * 2))
        values.copyInto(expanded, 0, 0, size)
        values = expanded
    }

    private companion object {
        const val INITIAL_CAPACITY = 8 * 1024
        const val PCM16_BYTES_PER_SAMPLE = 2
    }
}

/** Errors intentionally omit local paths, audio data, and capture metadata. */
sealed class RealtimeChunkSourceException(message: String) : IOException(message) {
    data object UnsupportedCodec : RealtimeChunkSourceException(
        "Realtime audio source supports only pcm_s16le",
    )

    data object InvalidTargetFormat : RealtimeChunkSourceException(
        "Realtime audio target format is invalid",
    )

    data object AudioUnavailable : RealtimeChunkSourceException(
        "Realtime audio source is unavailable",
    )

    data object InvalidPcmInput : RealtimeChunkSourceException(
        "Realtime audio source is not valid PCM16 data for the declared format",
    )

    data object AudioReadFailed : RealtimeChunkSourceException(
        "Realtime audio source could not be read",
    )

    data object LiveCaptureBackpressure : RealtimeChunkSourceException(
        "Realtime audio source fell behind live capture",
    )
}
