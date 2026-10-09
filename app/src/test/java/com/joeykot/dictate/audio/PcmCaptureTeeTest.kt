package com.joeykot.dictate.audio

import com.joeykot.dictate.model.Pcm16Format
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmCaptureTeeTest {
    private val format = Pcm16Format(sampleRateHz = 16_000, channelCount = 1)

    @Test
    fun offerCopiesTheRecorderBufferBeforeReturning() {
        val tee = PcmCaptureTee(capacity = 1)
        val source = byteArrayOf(1, 2, 3, 4)

        tee.offer(format, source, source.size)
        source.fill(9)

        val packet = checkNotNull(tee.poll(0))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), packet.bytes)
        assertFalse(tee.hasGap())
    }

    @Test
    fun aFullQueueMarksTheLiveCopyIncompleteWithoutBlockingCapture() {
        val tee = PcmCaptureTee(capacity = 1)

        tee.offer(format, byteArrayOf(1, 2), 2)
        tee.offer(format, byteArrayOf(3, 4), 2)

        assertTrue(tee.hasGap())
        assertArrayEquals(byteArrayOf(1, 2), checkNotNull(tee.poll(0)).bytes)
    }

    @Test
    fun aByteLimitPreventsLargePacketsFromGrowingTheLiveQueue() {
        val tee = PcmCaptureTee(capacity = 4, maxBufferedBytes = 3)

        tee.offer(format, byteArrayOf(1, 2), 2)
        tee.offer(format, byteArrayOf(3, 4), 2)

        assertTrue(tee.hasGap())
        assertArrayEquals(byteArrayOf(1, 2), checkNotNull(tee.poll(0)).bytes)
        assertNull(tee.poll(0))
    }

    @Test
    fun closeKeepsAlreadyQueuedPacketsAvailableForFinalization() {
        val tee = PcmCaptureTee(capacity = 1)
        tee.offer(format, byteArrayOf(1, 2), 2)

        tee.close()

        assertTrue(tee.isClosed())
        assertArrayEquals(byteArrayOf(1, 2), checkNotNull(tee.poll(0)).bytes)
        assertNull(tee.poll(0))
        assertFalse(tee.hasGap())
    }

    @Test
    fun aPacketOfferedAfterCloseForcesFullReplay() {
        val tee = PcmCaptureTee(capacity = 1)
        tee.close()

        tee.offer(format, byteArrayOf(1, 2), 2)

        assertTrue(tee.hasGap())
        assertNull(tee.poll(0))
    }
}
