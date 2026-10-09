package com.joeykot.dictate.audio

import com.joeykot.dictate.model.Pcm16Format
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Optional destination for a completed microphone PCM block.
 *
 * [pcm] is only valid for the duration of [offer]. Implementations must copy
 * it before returning. AudioRecorder invokes this only after the same block
 * was accepted by its local recording writer.
 */
interface PcmPacketSink : AutoCloseable {
    fun offer(format: Pcm16Format, pcm: ByteArray, length: Int)

    override fun close() = Unit
}

/** A PCM block that is owned by the consumer of a [PcmCaptureTee]. */
data class PcmCapturePacket(
    val format: Pcm16Format,
    val bytes: ByteArray,
)

/**
 * Bounded, non-blocking tee for optional live audio consumers.
 *
 * Microphone capture always remains authoritative in its local PCM file. A
 * full queue never blocks the capture thread; it only marks the live copy as
 * incomplete so the lifecycle layer can discard it and replay the complete
 * local recording. Closing keeps already queued packets available for a
 * normal websocket finish sequence.
 */
class PcmCaptureTee(
    capacity: Int,
    private val maxBufferedBytes: Int = DEFAULT_MAX_BUFFERED_BYTES,
) : PcmPacketSink {
    private val packets = ArrayBlockingQueue<PcmCapturePacket>(capacity)
    private val offerCloseLock = Any()
    private val closed = AtomicBoolean(false)
    private val backpressured = AtomicBoolean(false)
    private val gapDetected = AtomicBoolean(false)
    private val queuedBytes = AtomicLong(0L)

    init {
        require(capacity > 0) { "PCM tee capacity must be positive" }
        require(maxBufferedBytes > 0) { "PCM tee byte capacity must be positive" }
    }

    override fun offer(format: Pcm16Format, pcm: ByteArray, length: Int) {
        if (length !in 0..pcm.size) {
            gapDetected.set(true)
            return
        }
        if (length == 0) return
        synchronized(offerCloseLock) {
            if (backpressured.get()) return
            if (closed.get()) {
                gapDetected.set(true)
                return
            }
            if (length > maxBufferedBytes || !reserveBytes(length)) {
                backpressured.set(true)
                return
            }
        }
        val copy = pcm.copyOfRange(0, length)
        synchronized(offerCloseLock) {
            if (backpressured.get()) {
                releaseBytes(length)
                return
            }
            if (closed.get()) {
                // The recorder had already accepted this block locally, but
                // the optional live destination was closed concurrently. A
                // live transcript would have a gap, so force a full replay.
                gapDetected.set(true)
                releaseBytes(length)
                return
            }
            if (!packets.offer(PcmCapturePacket(format, copy))) {
                releaseBytes(length)
                backpressured.set(true)
            }
        }
    }

    /** Returns a queued packet or null when no packet arrives before the timeout. */
    fun poll(timeoutMillis: Long): PcmCapturePacket? = try {
        packets.poll(timeoutMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)?.also { packet ->
            releaseBytes(packet.bytes.size)
        }
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    }

    /** True once no future packet should be offered. Queued packets remain readable. */
    fun isClosed(): Boolean = closed.get()

    /** True when the live branch missed at least one locally recorded PCM block. */
    fun hasGap(): Boolean = backpressured.get() || gapDetected.get()

    override fun close() {
        synchronized(offerCloseLock) {
            closed.set(true)
        }
    }

    private fun reserveBytes(length: Int): Boolean {
        val additional = length.toLong()
        val limit = maxBufferedBytes.toLong()
        while (true) {
            val current = queuedBytes.get()
            if (current > limit - additional) return false
            if (queuedBytes.compareAndSet(current, current + additional)) return true
        }
    }

    private fun releaseBytes(length: Int) {
        queuedBytes.addAndGet(-length.toLong())
    }

    private companion object {
        const val DEFAULT_MAX_BUFFERED_BYTES = 4 * 1024 * 1024
    }
}
