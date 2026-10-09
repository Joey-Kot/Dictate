package com.joeykot.dictate.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStartBarrierTest {
    @Test
    fun readerIsReadyBeforeRecordingButCannotReadUntilItIsReleased() {
        val barrier = CaptureStartBarrier()
        val passedStartGate = CountDownLatch(1)
        val reader = Thread {
            barrier.awaitRecordingStart()
            passedStartGate.countDown()
        }.apply { start() }

        assertTrue(barrier.awaitReaderReady(1_000))
        assertFalse(passedStartGate.await(50, TimeUnit.MILLISECONDS))

        barrier.releaseReader()

        assertTrue(passedStartGate.await(1_000, TimeUnit.MILLISECONDS))
        reader.join(1_000)
        assertFalse(reader.isAlive)
    }
}
