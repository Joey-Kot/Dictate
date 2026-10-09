package com.joeykot.dictate.advanced_audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StreamFramingTest {
    @Test
    fun ssePreservesFieldsAcrossChunksAndFlushesAnEofEvent() {
        val decoder = SseDecoder()
        assertTrue(
            decoder.push(
                byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                    "event: transcript\r\ndata: caf".toByteArray(),
            ).isEmpty(),
        )
        assertTrue(decoder.push(byteArrayOf(0xC3.toByte())).isEmpty())
        assertTrue(decoder.push(byteArrayOf(0xA9.toByte())).isEmpty())

        val events = decoder.finish()
        assertEquals(1, events.size)
        assertEquals("transcript", events.single().event)
        assertEquals("café", events.single().data)
        assertEquals(
            listOf(
                SseField("event", "transcript"),
                SseField("data", "café"),
            ),
            events.single().fields,
        )
    }

    @Test
    fun sseHasIndependentPhysicalAndEventBounds() {
        val decoder = SseDecoder()
        val line = "data: ${"x".repeat(MAX_STREAM_FRAME_BYTES / 2)}\n".toByteArray()
        assertTrue(decoder.push(line).isEmpty())

        val error = capture { decoder.push(line) }
        assertTrue(error is StreamFrameException.FrameTooLarge)
        assertEquals("SSE", (error as StreamFrameException.FrameTooLarge).format)
    }

    @Test
    fun ndjsonAcceptsArbitraryChunksBlankLinesAndFinalLine() {
        val decoder = NdjsonDecoder()
        assertTrue(decoder.push("{\"delta\":\"he".toByteArray()).isEmpty())
        assertEquals(
            listOf(NdjsonFrame(1, "{\"delta\":\"hello\"}")),
            decoder.push("llo\"}\r\n \t\r\n".toByteArray()),
        )
        assertTrue(decoder.push("{\"done\":true}".toByteArray()).isEmpty())
        assertEquals(listOf(NdjsonFrame(3, "{\"done\":true}")), decoder.finish())
    }

    @Test
    fun jsonChunksRetainPartialValuesAndCanonicalizeCompletedValues() {
        val decoder = JsonChunksDecoder()
        assertTrue(decoder.push("{\"delta\":\"he".toByteArray()).isEmpty())
        assertEquals(
            listOf("{\"delta\":\"hello\"}", "{\"done\":true}"),
            decoder.push("llo\"}{ \"done\" : true }".toByteArray()),
        )
        assertTrue(decoder.finish().isEmpty())
    }

    @Test
    fun jsonChunksRejectAnIncompleteFinalValue() {
        val decoder = JsonChunksDecoder()
        assertTrue(decoder.push("{\"delta\":\"hello".toByteArray()).isEmpty())
        assertTrue(capture { decoder.finish() } is StreamFrameException.InvalidJsonChunk)
    }

    @Test
    fun jsonChunksPreserveStrictJsonNumberValues() {
        val decoder = JsonChunksDecoder()

        assertEquals(
            listOf("{\"rate\":1.25e+3}", "-42"),
            decoder.push("{\"rate\":1.25e+3}-42".toByteArray()),
        )
        assertTrue(decoder.finish().isEmpty())
    }

    private fun capture(block: () -> Unit): Throwable = try {
        block()
        error("operation unexpectedly succeeded")
    } catch (error: Throwable) {
        error
    }
}
