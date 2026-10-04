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
            selectionEnd = 6,
            offset = 0,
        )

        assertEquals(
            TextInsertionObservation.UNAVAILABLE,
            observeTextInsertion(invalid, invalid, " world"),
        )
    }

    @Test
    fun confirmsReplacementOfReversedSelection() {
        val before = TextInsertionSnapshot("hello world!", 11, 6, 0)
        val after = TextInsertionSnapshot("hello there!", 11, 11, 0)

        assertEquals(
            TextInsertionObservation.CONFIRMED,
            observeTextInsertion(before, after, "there"),
        )
    }

    @Test
    fun replacesOnlySelectedRangeAndPlacesCursorAfterResult() {
        assertEquals(
            TextReplacement("before RESULT after", 13),
            replaceCurrentSelection("before selected after", 7, 15, "RESULT"),
        )
        assertEquals(
            TextReplacement("before RESULT after", 13),
            replaceCurrentSelection("before selected after", 15, 7, "RESULT"),
        )
    }

    @Test
    fun insertsAtCollapsedCursorIncludingBothEnds() {
        assertEquals(TextReplacement("Xabc", 1), replaceCurrentSelection("abc", 0, 0, "X"))
        assertEquals(TextReplacement("aXbc", 2), replaceCurrentSelection("abc", 1, 1, "X"))
        assertEquals(TextReplacement("abcX", 4), replaceCurrentSelection("abc", 3, 3, "X"))
    }

    @Test
    fun preservesUnicodeAndMultilineTextOutsideSelection() {
        assertEquals(
            TextReplacement("前🙂替换\n后", 5),
            replaceCurrentSelection("前🙂原文\n后", 3, 5, "替换"),
        )
    }

    @Test
    fun unknownSelectionInNonemptyEditorDoesNotGuessCursor() {
        assertNull(replaceCurrentSelection("abc", -1, -1, "X"))
        assertNull(replaceCurrentSelection("abc", 1, 4, "X"))
        assertEquals(TextReplacement("X", 1), replaceCurrentSelection("", -1, -1, "X"))
    }

    @Test
    fun selectedTextSupportsReadonlyReverseAndWhitespaceRanges() {
        assertEquals("selected", selectedTextInRange("before selected after", 7, 15))
        assertEquals("selected", selectedTextInRange("before selected after", 15, 7))
        assertEquals(" \n ", selectedTextInRange("a \n b", 1, 4))
    }

    @Test
    fun missingOrCollapsedOrInvalidRangeIsNotSelectedText() {
        assertNull(selectedTextInRange("abc", 1, 1))
        assertNull(selectedTextInRange("abc", -1, 1))
        assertNull(selectedTextInRange("abc", 1, 4))
        assertNull(selectedTextInRange(null, 0, 1))
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
