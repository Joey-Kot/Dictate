package com.joeykot.dictate.model

import org.junit.Assert.*
import org.junit.Test

class PromptConfigTest {
    @Test fun defaultAndInheritedPromptsUseTheEntireCurrentMainApi() {
        assertNull(PromptConfig().provider)
        val prompt = PromptConfig(baseUrl = "ignored", model = "ignored")
        for (provider in PostProcessingProvider.entries) {
            val main = PostProcessingConfig(provider, "https://main.example", "main-model")
            val resolved = prompt.effectiveApi(main, "main-secret", "ignored-secret")
            assertEquals(main, resolved.config)
            assertEquals("main-secret", resolved.apiKey)
        }
    }

    @Test fun explicitProviderOwnsEveryFieldEvenWhenItMatchesMainOrHasBlanks() {
        for (provider in PostProcessingProvider.entries) {
            val main = PostProcessingConfig(provider, "https://main.example", "main-model")
            val prompt = PromptConfig(provider = provider, baseUrl = "https://prompt.example", model = "prompt-model")
            val resolved = prompt.effectiveApi(main, "main-secret", "prompt-secret")
            assertEquals(PostProcessingConfig(provider, "https://prompt.example", "prompt-model"), resolved.config)
            assertEquals("prompt-secret", resolved.apiKey)
            val empty = prompt.copy(baseUrl = "", model = "").effectiveApi(main, "main-secret", "")
            assertEquals("", empty.config.baseUrl)
            assertEquals("", empty.config.model)
            assertEquals("", empty.apiKey)
            assertFalse(resolved.toString().contains("prompt-secret"))
        }
    }
}
