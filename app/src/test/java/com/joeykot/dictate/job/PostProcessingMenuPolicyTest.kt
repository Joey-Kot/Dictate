package com.joeykot.dictate.job

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostProcessingMenuPolicyTest {
    @Test
    fun selectedTextAndSavedPromptsOpenTheMenu() {
        assertTrue(shouldOpenPostProcessingMenu("选中的文本", 1))
        assertTrue(shouldOpenPostProcessingMenu(" ", 2))
    }

    @Test
    fun noSelectedTextKeepsTheResendAction() {
        assertFalse(shouldOpenPostProcessingMenu(null, 2))
        assertFalse(shouldOpenPostProcessingMenu("", 2))
    }

    @Test
    fun emptyPromptListKeepsTheResendActionEvenWithSelectedText() {
        assertFalse(shouldOpenPostProcessingMenu("选中的文本", 0))
    }
}
