package com.joeykot.dictate.model

import org.junit.Assert.assertEquals
import org.junit.Test

class Pcm16FormatTest {
    @Test
    fun calculatesRawByteRatesFromTheClientFormat() {
        val format = Pcm16Format(sampleRateHz = 48_000, channelCount = 1)

        assertEquals(2, format.bytesPerFrame)
        assertEquals(96_000L, format.bytesPerSecond)
        assertEquals(9_600L, format.minimumBytesFor(100L))
    }
}
