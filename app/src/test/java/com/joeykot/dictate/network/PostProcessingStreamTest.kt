package com.joeykot.dictate.network

import com.joeykot.dictate.model.PostProcessingProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PostProcessingStreamTest {
    @Test
    fun compatibleStreamCollectsFirstChoiceAndSkipsReasoning() {
        val stream = """
            : keep-alive

            data: {"choices":[{"index":0,"delta":{"reasoning_content":"hidden"}},{"index":1,"delta":{"content":"other answer"}}]}

            data: {"choices":[{"index":0,"delta":{"content":"你"}}]}

            data: {"choices":[{"index":0,"delta":{"content":"好"},"finish_reason":"stop"}]}

            data: [DONE]

        """.trimIndent()
        assertEquals("你好", PostProcessingStream.parseText(PostProcessingProvider.OPENAI_COMPATIBLE, stream))
    }

    @Test
    fun responsesUsesFinalOutputWithoutDuplicatingDeltas() {
        val stream = """
            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"answer"}

            event: response.completed
            data: {"type":"response.completed","response":{"output":[{"type":"message","content":[{"type":"output_text","text":"answer"}]}]}}

        """.trimIndent()
        assertEquals("answer", PostProcessingStream.parseText(PostProcessingProvider.OPENAI_RESPONSES, stream))
    }

    @Test
    fun anthropicCollectsTextBlocksAndIgnoresThinking() {
        val stream = """
            event: content_block_delta
            data: {"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"hidden"}}

            event: content_block_start
            data: {"type":"content_block_start","content_block":{"type":"text","text":"Hello"}}

            event: content_block_delta
            data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"!"}}

            event: message_stop
            data: {"type":"message_stop"}

        """.trimIndent()
        assertEquals("Hello!", PostProcessingStream.parseText(PostProcessingProvider.ANTHROPIC, stream))
    }

    @Test
    fun disconnectedOrFailedStreamsDoNotReturnPartialText() {
        val partial = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
        assertThrows(PostProcessingRequest.InvalidResponseException::class.java) {
            PostProcessingStream.parseText(PostProcessingProvider.OPENAI_COMPATIBLE, partial)
        }
        assertThrows(PostProcessingStream.ServiceException::class.java) {
            PostProcessingStream.parseText(PostProcessingProvider.OPENAI_COMPATIBLE, partial + "data: {\"error\":{\"message\":\"overloaded\"}}\n\n")
        }
    }
}
