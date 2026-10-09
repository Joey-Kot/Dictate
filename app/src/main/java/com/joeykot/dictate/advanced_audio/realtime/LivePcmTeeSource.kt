package com.joeykot.dictate.advanced_audio.realtime

import com.joeykot.dictate.advanced_audio.RealtimeAudioStream
import com.joeykot.dictate.advanced_audio.http.AdvancedCancellationToken
import com.joeykot.dictate.audio.PcmCaptureTee
import com.joeykot.dictate.model.Pcm16Format as CapturePcm16Format
import java.util.ArrayDeque

/**
 * Adapts the recorder's bounded raw-PCM tee to one live realtime session.
 *
 * Local capture owns the authoritative PCM file. This source deliberately
 * never makes the recorder wait: a full tee or a late packet becomes a
 * [RealtimeChunkSourceException.LiveCaptureBackpressure], so the lifecycle
 * layer discards this live transcript and replays the complete local file.
 */
class LivePcmTeeSource(
    private val tee: PcmCaptureTee,
    private val captureFormat: CapturePcm16Format,
    target: RealtimeAudioStream,
    private val pollTimeoutMillis: Long = DEFAULT_POLL_TIMEOUT_MILLIS,
) : RealtimeChunkSource {
    private val chunker = Pcm16RealtimeChunker(captureFormat, target)
    private val ready = ArrayDeque<RealtimeAudioChunk>()

    private var inputFinished = false
    private var closed = false

    override fun nextChunk(cancellation: AdvancedCancellationToken): RealtimeAudioChunk? {
        cancellation.throwIfCancelled()
        if (closed) return null

        while (true) {
            cancellation.throwIfCancelled()
            failIfCaptureGap()

            // Once the producer closes, no later capture packet is allowed.
            // Drain every queued raw packet and flush the converter *before*
            // returning the last target chunk. This makes isFinished true as
            // soon as that last chunk has been handed to the runner, so a
            // normal server complete event cannot be mistaken for an early
            // completion merely because final converter state was pending.
            if (tee.isClosed()) {
                while (true) {
                    val queued = tee.poll(0L) ?: break
                    accept(queued)
                }
                failIfCaptureGap()
                if (!inputFinished) {
                    ready.addAll(chunker.finish())
                    inputFinished = true
                }
                return if (ready.isEmpty()) null else ready.removeFirst()
            }

            if (ready.isNotEmpty()) return ready.removeFirst()

            val packet = tee.poll(pollTimeoutMillis)
            if (packet != null) {
                accept(packet)
                failIfCaptureGap()
                continue
            }

            failIfCaptureGap()
            // A close that races the timed poll is handled by the next
            // iteration, where all queued packets are drained atomically from
            // the source's point of view.
        }
    }

    override val requiresRealtimePacing: Boolean
        get() = false

    override val isFinished: Boolean
        get() = inputFinished && ready.isEmpty()

    override fun close() {
        if (closed) return
        closed = true
        // A runner can end early only on cancellation or failure. Closing its
        // tee makes any concurrent future packet a visible gap instead of
        // silently allowing a partial live transcript to be used.
        tee.close()
    }

    private fun failIfCaptureGap() {
        if (tee.hasGap()) throw RealtimeChunkSourceException.LiveCaptureBackpressure
    }

    private fun accept(packet: com.joeykot.dictate.audio.PcmCapturePacket) {
        if (packet.format != captureFormat ||
            packet.bytes.size % captureFormat.bytesPerFrame != 0
        ) {
            throw RealtimeChunkSourceException.InvalidPcmInput
        }
        ready.addAll(chunker.push(packet.bytes))
    }

    private companion object {
        const val DEFAULT_POLL_TIMEOUT_MILLIS = 50L
    }
}
