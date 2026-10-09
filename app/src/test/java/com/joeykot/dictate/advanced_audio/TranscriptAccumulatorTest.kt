package com.joeykot.dictate.advanced_audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptAccumulatorTest {
    @Test
    fun committedAndPartialTextAreConcatenatedOnlyAfterExplicitCompletion() {
        val accumulator = TranscriptAccumulator()
        accumulator.replacePartial("first draft")
        accumulator.commitSegment("first ")
        accumulator.appendDelta("second")

        assertEquals("first ", accumulator.committedText)
        assertEquals("second", accumulator.partialText)
        assertTrue(capture { accumulator.finalText() } is TranscriptAccumulatorException.IncompleteStream)

        accumulator.complete()
        assertEquals("first second", accumulator.finalText())
    }

    @Test
    fun authoritativeFinalWinsAndEmptyFinalIsPreserved() {
        val accumulator = TranscriptAccumulator()
        accumulator.commitSegment("stale ")
        accumulator.setFinalText("")
        accumulator.complete()

        assertEquals("", accumulator.intoFinalText())
    }

    @Test
    fun failureIsImmediateAndTerminalActionsAreRejected() {
        val accumulator = TranscriptAccumulator()
        val failure = capture { accumulator.fail("   ") }
        assertTrue(failure is TranscriptAccumulatorException.StreamFailed)
        assertEquals("server reported stream failure without a message", accumulator.failureMessage)
        assertTrue(capture { accumulator.complete() } is TranscriptAccumulatorException.ActionAfterFailure)
    }

    private fun capture(block: () -> Unit): Throwable = try {
        block()
        error("operation unexpectedly succeeded")
    } catch (error: Throwable) {
        error
    }
}
