package com.joeykot.dictate.network

import com.joeykot.dictate.model.PostProcessingConfig
import com.joeykot.dictate.model.PostProcessingProvider
import com.joeykot.dictate.model.PromptConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PostProcessingRequestTest {
    private val prompt = PromptConfig(prompt = "Summarize this text")

    @Test
    fun chatProvidersUseSystemInstructionAndSeparateUserInput() {
        listOf(
            PostProcessingProvider.OPENAI_COMPATIBLE, PostProcessingProvider.OPENAI_COMPLETIONS,
            PostProcessingProvider.DEEPSEEK, PostProcessingProvider.QWEN, PostProcessingProvider.GLM,
        ).forEach { provider ->
            val request = request(provider)
            assertTrue(request.endpoint.endsWith("/chat/completions"))
            assertEquals("Bearer key", request.headers["Authorization"])
            val messages = request.body.getJSONArray("messages")
            assertEquals("system", messages.getJSONObject(0).getString("role"))
            assertEquals(prompt.prompt, messages.getJSONObject(0).getString("content"))
            assertEquals("user", messages.getJSONObject(1).getString("role"))
            assertEquals("selected text", messages.getJSONObject(1).getString("content"))
        }
    }

    @Test
    fun responsesUsesInstructionsAndAnthropicUsesTopLevelSystem() {
        val responses = request(PostProcessingProvider.OPENAI_RESPONSES)
        assertEquals("https://example.test/v1/responses", responses.endpoint)
        assertEquals(prompt.prompt, responses.body.getString("instructions"))
        assertEquals("user", responses.body.getJSONArray("input").getJSONObject(0).getString("role"))
        val anthropic = request(PostProcessingProvider.ANTHROPIC)
        assertEquals("https://example.test/v1/messages", anthropic.endpoint)
        assertEquals(prompt.prompt, anthropic.body.getString("system"))
        assertEquals(4096, anthropic.body.getInt("max_tokens"))
        assertEquals("key", anthropic.headers["x-api-key"])
        assertEquals("2023-06-01", anthropic.headers["anthropic-version"])
    }

    @Test
    fun googleUsesOverriddenModelInUrlAndNestedSystemInstruction() {
        val request = PostProcessingRequest.build(
            PostProcessingConfig(PostProcessingProvider.GOOGLE, "https://example.test", "default"),
            "key", prompt.copy(additionalJson = """{"model":"models/alternate","generationConfig":{"temperature":0.3}}"""), "selected text",
        )
        assertEquals("https://example.test/v1beta/models/alternate:generateContent", request.endpoint)
        assertFalse(request.body.has("model"))
        assertEquals(prompt.prompt, request.body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals("key", request.headers["x-goog-api-key"])
    }

    @Test
    fun finalMergeCanOverrideModelAndRemoveGeneratedFields() {
        val request = PostProcessingRequest.build(
            PostProcessingConfig(PostProcessingProvider.ANTHROPIC, "https://example.test", ""), "key",
            prompt.copy(additionalJson = """{"model":"other","max_tokens":null,"system":null,"metadata":{"a":null,"b":1}}"""), "text",
        )
        assertEquals("other", request.body.getString("model"))
        assertFalse(request.body.has("max_tokens"))
        assertFalse(request.body.has("system"))
        assertFalse(request.body.getJSONObject("metadata").has("a"))
        assertThrows(IllegalArgumentException::class.java) {
            PostProcessingRequest.build(
                PostProcessingConfig(baseUrl = "https://example.test", model = "default"), "key",
                prompt.copy(additionalJson = """{"model":null}"""), "text",
            )
        }
    }

    @Test
    fun nativeAndCompatibleResponsesReturnOnlyVisibleText() {
        assertEquals("answer", PostProcessingRequest.parseText(PostProcessingProvider.OPENAI_COMPATIBLE,
            """{"choices":[{"message":{"content":"answer","reasoning_content":"hidden"}}]}"""))
        assertEquals("answer", PostProcessingRequest.parseText(PostProcessingProvider.OPENAI_RESPONSES,
            """{"output":[{"type":"reasoning","summary":[]},{"type":"message","content":[{"type":"output_text","text":"answer"}]}]}"""))
        assertEquals("answer", PostProcessingRequest.parseText(PostProcessingProvider.ANTHROPIC,
            """{"content":[{"type":"thinking","thinking":"hidden"},{"type":"text","text":"answer"}]}"""))
        assertEquals("answer", PostProcessingRequest.parseText(PostProcessingProvider.GOOGLE,
            """{"candidates":[{"content":{"parts":[{"text":"hidden","thought":true},{"text":"answer"}]}}]}"""))
    }

    @Test
    fun missingTextAndMalformedResponseAreFailures() {
        listOf("bad JSON", "{}", """{"choices":[{"message":{"content":null}}]}""").forEach {
            assertThrows(PostProcessingRequest.InvalidResponseException::class.java) {
                PostProcessingRequest.parseText(PostProcessingProvider.OPENAI_COMPATIBLE, it)
            }
        }
    }

    @Test
    fun endpointsPreserveGatewayPathsAndAcceptCompleteEndpoints() {
        assertEquals("https://example.test/proxy/v1/responses", BaseUrl.postProcessingEndpoint("https://example.test/proxy/v1/", PostProcessingProvider.OPENAI_RESPONSES, "m"))
        assertEquals("https://example.test/v1/messages", BaseUrl.postProcessingEndpoint("https://example.test/v1/messages", PostProcessingProvider.ANTHROPIC, "m"))
        assertEquals("https://example.test/api/paas/v4/chat/completions", BaseUrl.postProcessingEndpoint("https://example.test", PostProcessingProvider.GLM, "m"))
        assertEquals("https://example.test/compatible-mode/v1/chat/completions", BaseUrl.postProcessingEndpoint("https://example.test", PostProcessingProvider.QWEN, "m"))
        assertEquals("https://example.test/v1beta/models/new:generateContent", BaseUrl.postProcessingEndpoint("https://example.test/v1beta/models/old:generateContent", PostProcessingProvider.GOOGLE, "new"))
        assertThrows(IllegalArgumentException::class.java) { BaseUrl.postProcessingEndpoint("https://user:pass@example.test", PostProcessingProvider.OPENAI_COMPATIBLE, "m") }
    }

    private fun request(provider: PostProcessingProvider) = PostProcessingRequest.build(
        PostProcessingConfig(provider, "https://example.test", "model"), "key", prompt, "selected text",
    )
}
