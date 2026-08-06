package com.joeykot.dictate.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextInsertionPolicyTest {
    @Test
    fun shownHintIsNotTreatedAsEditableText() {
        assertEquals(
            "",
            editableTextForInsertion(
                nodeText = "Search messages",
                isShowingHintText = true,
            ),
        )
    }

    @Test
    fun actualEditableTextIsPreserved() {
        assertEquals(
            "Existing text",
            editableTextForInsertion(
                nodeText = "Existing text",
                isShowingHintText = false,
            ),
        )
    }

    @Test
    fun missingEditableTextIsEmpty() {
        assertEquals(
            "",
            editableTextForInsertion(
                nodeText = null,
                isShowingHintText = false,
            ),
        )
    }

    @Test
    fun fallbackKeepsEarlierFailureDiagnostics() {
        val direct = TextInsertionResult.failure("input_connection=no_active_connection")
        val fallback = TextInsertionResult.success(
            TextInsertionMethod.SET_TEXT,
            "set_text=success",
        )

        val combined = direct.followedBy(fallback)

        assertTrue(combined.inserted)
        assertEquals(TextInsertionMethod.SET_TEXT, combined.method)
        assertEquals(
            listOf("input_connection=no_active_connection", "set_text=success"),
            combined.attempts,
        )
    }

    @Test
    fun successfulInsertionDoesNotRunAReplacementFallback() {
        val direct = TextInsertionResult.success(
            TextInsertionMethod.INPUT_CONNECTION,
            "input_connection=confirmed",
        )
        val fallback = TextInsertionResult.failure("paste=rejected")

        val combined = direct.followedBy(fallback)

        assertTrue(combined.inserted)
        assertEquals(TextInsertionMethod.INPUT_CONNECTION, combined.method)
        assertEquals(listOf("input_connection=confirmed"), combined.attempts)
    }

    @Test
    fun unconfirmedInsertionDoesNotRunAReplacementFallback() {
        val direct = TextInsertionResult.unconfirmed(
            TextInsertionMethod.INPUT_CONNECTION,
            "input_connection=unconfirmed",
        )
        val fallback = TextInsertionResult.success(
            TextInsertionMethod.SET_TEXT,
            "set_text=success",
        )

        val combined = direct.followedBy(fallback)

        assertFalse(combined.inserted)
        assertTrue(combined.unconfirmed)
        assertEquals(TextInsertionMethod.INPUT_CONNECTION, combined.method)
        assertEquals(listOf("input_connection=unconfirmed"), combined.attempts)
    }

    @Test
    fun failedInsertionHasNoSelectedMethod() {
        val result = TextInsertionResult.failure("set_text=rejected")

        assertFalse(result.inserted)
        assertNull(result.method)
        assertEquals(
            "state=failed method=none attempts=set_text=rejected",
            result.diagnosticSummary(),
        )
    }

    @Test
    fun confirmsInsertedTextAtExpectedCursor() {
        val before = TextInsertionSnapshot(
            text = "hello world",
            selectionStart = 5,
            selectionEnd = 5,
            offset = 0,
        )
        val after = TextInsertionSnapshot(
            text = "hello brave world",
            selectionStart = 11,
            selectionEnd = 11,
            offset = 0,
        )

        assertEquals(
            TextInsertionObservation.CONFIRMED,
            observeTextInsertion(before, after, " brave"),
        )
    }

    @Test
    fun confirmsReplacementOfSelectedText() {
        val before = TextInsertionSnapshot(
            text = "hello world",
            selectionStart = 6,
            selectionEnd = 11,
            offset = 0,
        )
        val after = TextInsertionSnapshot(
            text = "hello there",
            selectionStart = 11,
            selectionEnd = 11,
            offset = 0,
        )

        assertEquals(
            TextInsertionObservation.CONFIRMED,
            observeTextInsertion(before, after, "there"),
        )
    }

    @Test
    fun confirmsInsertionWhenSurroundingWindowOffsetMoves() {
        val before = TextInsertionSnapshot(
            text = "abc",
            selectionStart = 3,
            selectionEnd = 3,
            offset = 100,
        )
        val after = TextInsertionSnapshot(
            text = "bcX",
            selectionStart = 3,
            selectionEnd = 3,
            offset = 101,
        )

        assertEquals(
            TextInsertionObservation.CONFIRMED,
            observeTextInsertion(before, after, "X"),
        )
    }

    @Test
    fun invalidSelectionCannotConfirmInsertion() {
        val invalid = TextInsertionSnapshot(
            text = "hello",
            selectionStart = 5,
            selectionEnd = 2,
            offset = 0,
        )

        assertEquals(
            TextInsertionObservation.UNAVAILABLE,
            observeTextInsertion(invalid, invalid, " world"),
        )
    }

    @Test
    fun unchangedSnapshotCanTriggerFallback() {
        val snapshot = TextInsertionSnapshot(
            text = "hello",
            selectionStart = 5,
            selectionEnd = 5,
            offset = 0,
        )

        val observation = observeTextInsertion(snapshot, snapshot, " world")

        assertEquals(TextInsertionObservation.UNCHANGED, observation)
        assertTrue(
            shouldFallbackAfterObservations(
                listOf(
                    TextInsertionObservation.UNAVAILABLE,
                    TextInsertionObservation.UNCHANGED,
                    TextInsertionObservation.UNCHANGED,
                ),
            ),
        )
    }

    @Test
    fun unexpectedChangesRemainUnconfirmed() {
        assertFalse(
            shouldFallbackAfterObservations(
                listOf(
                    TextInsertionObservation.CHANGED,
                    TextInsertionObservation.UNCHANGED,
                    TextInsertionObservation.UNCHANGED,
                ),
            ),
        )
        assertFalse(
            shouldFallbackAfterObservations(
                listOf(
                    TextInsertionObservation.UNCHANGED,
                    TextInsertionObservation.UNAVAILABLE,
                ),
            ),
        )
    }
}
