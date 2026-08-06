package com.joeykot.dictate.accessibility

import org.junit.Assert.assertEquals
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
}
