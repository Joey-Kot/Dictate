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
}
