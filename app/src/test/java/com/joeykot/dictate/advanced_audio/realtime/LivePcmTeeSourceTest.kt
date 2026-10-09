package com.joeykot.dictate.advanced_audio.realtime

import com.joeykot.dictate.advanced_audio.RealtimeAudioStream
import com.joeykot.dictate.advanced_audio.http.AdvancedCancellationToken
import com.joeykot.dictate.audio.PcmCaptureTee
import com.joeykot.dictate.model.Pcm16Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePcmTeeSourceTest {
    private val format = Pcm16Format(sampleRateHz = 16_000, channelCount = 1)
    private val stream = RealtimeAudioStream(
        codec = "pcm_s16le",
        sampleRate = 16_000,
        channels = 1,
        chunkDurationMs = 10,
    )

    @Test
    fun drainsThenMarksFinishedOnlyAfterTheClosedTeeAndFinalChunk() {
        val tee = PcmCaptureTee(capacity = 2)
        val source = LivePcmTeeSource(tee, format, stream, pollTimeoutMillis = 0)
        tee.offer(format, ByteArray(320) { it.toByte() }, 320)
        tee.close()

        val chunk = checkNotNull(source.nextChunk(AdvancedCancellationToken.none()))
        assertEquals(320, chunk.bytes.size)
        assertTrue(source.isFinished)
        assertNull(source.nextChunk(AdvancedCancellationToken.none()))
        assertTrue(source.isFinished)
        assertFalse(source.requiresRealtimePacing)
    }

    @Test(expected = RealtimeChunkSourceException.LiveCaptureBackpressure::class)
    fun aTeeGapFailsTheLiveSourceInsteadOfSilentlySkippingAudio() {
        val tee = PcmCaptureTee(capacity = 1)
        val source = LivePcmTeeSource(tee, format, stream, pollTimeoutMillis = 0)
        tee.offer(format, byteArrayOf(1, 2), 2)
        tee.offer(format, byteArrayOf(3, 4), 2)

        source.nextChunk(AdvancedCancellationToken.none())
    }
}
