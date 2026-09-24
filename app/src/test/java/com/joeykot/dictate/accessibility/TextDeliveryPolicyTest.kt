package com.joeykot.dictate.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDeliveryPolicyTest {
    @Test
    fun enabledSettingAlwaysRequestsClipboardCopy() {
        assertTrue(
            shouldCopyToClipboard(
                alwaysCopyToClipboard = true,
                insertionState = TextInsertionState.CONFIRMED,
            ),
        )
        assertTrue(
            shouldCopyToClipboard(
                alwaysCopyToClipboard = true,
                insertionState = TextInsertionState.UNCONFIRMED,
            ),
        )
        assertTrue(
            shouldCopyToClipboard(
                alwaysCopyToClipboard = true,
                insertionState = TextInsertionState.FAILED,
            ),
        )
    }

    @Test
    fun disabledSettingKeepsClipboardForExplicitFailureOnly() {
        assertFalse(
            shouldCopyToClipboard(
                alwaysCopyToClipboard = false,
                insertionState = TextInsertionState.CONFIRMED,
            ),
        )
        assertFalse(
            shouldCopyToClipboard(
                alwaysCopyToClipboard = false,
                insertionState = TextInsertionState.UNCONFIRMED,
            ),
        )
        assertTrue(
            shouldCopyToClipboard(
                alwaysCopyToClipboard = false,
                insertionState = TextInsertionState.FAILED,
            ),
        )
    }

    @Test
    fun pasteFallbackRequiresFailedDirectInsertionAndSuccessfulCopy() {
        assertTrue(
            shouldTryPasteFallback(
                directInsertionFailed = true,
                copiedToClipboard = true,
                serviceAvailable = true,
            ),
        )
        assertFalse(
            shouldTryPasteFallback(
                directInsertionFailed = false,
                copiedToClipboard = true,
                serviceAvailable = true,
            ),
        )
        assertFalse(
            shouldTryPasteFallback(
                directInsertionFailed = true,
                copiedToClipboard = false,
                serviceAvailable = true,
            ),
        )
        assertFalse(
            shouldTryPasteFallback(
                directInsertionFailed = true,
                copiedToClipboard = true,
                serviceAvailable = false,
            ),
        )
    }
}
