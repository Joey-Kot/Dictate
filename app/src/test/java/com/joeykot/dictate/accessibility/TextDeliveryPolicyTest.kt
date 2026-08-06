package com.joeykot.dictate.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDeliveryPolicyTest {
    @Test
    fun enabledSettingAlwaysRequestsClipboardCopy() {
        assertTrue(shouldCopyToClipboard(alwaysCopyToClipboard = true, inserted = true))
        assertTrue(shouldCopyToClipboard(alwaysCopyToClipboard = true, inserted = false))
    }

    @Test
    fun disabledSettingKeepsClipboardAsFailureFallback() {
        assertFalse(shouldCopyToClipboard(alwaysCopyToClipboard = false, inserted = true))
        assertTrue(shouldCopyToClipboard(alwaysCopyToClipboard = false, inserted = false))
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
