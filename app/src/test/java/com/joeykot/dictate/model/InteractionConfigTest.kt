package com.joeykot.dictate.model

import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionConfigTest {
    @Test
    fun clipboardSafetyCopyDefaultsToEnabled() {
        assertTrue(InteractionConfig().alwaysCopyToClipboard)
    }
}
