package com.joeykot.dictate.advanced_audio

import com.joeykot.dictate.model.PostProcessingConfig
import com.joeykot.dictate.network.TranscriptionClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AdvancedAudioWorkflowGeneratorTest {
    @Test
    fun compilerInputKeepsSourcesSeparateAndRedactsCredentialLiterals() {
        val inputs = mutableListOf<String>()
        val generator = generator(
            replies = listOf(ok(validWorkflow())),
            inputs = inputs,
        )
        val requirements = "Use the documented endpoint. {\\\"vendor_material\\\":\\\"do not replace this\\\"}"
        val vendorMaterial = "Authorization: Bearer bearer-token\\napi_key=api-token&sig=temporary-signature&token=query-token&mode=fast"

        assertTrue(generator.generate(PostProcessingConfig(), "rewrite-key", requirements, vendorMaterial).isSuccess)

        val envelope = JsonValueCodec.parseObject(inputs.single())
        assertEquals(JsonValue.Number("1"), envelope["input_version"])
        assertEquals(requirements, (envelope["user_requirements"] as JsonValue.Text).value)
        val redactedMaterial = (envelope["vendor_material"] as JsonValue.Text).value
        assertFalse(redactedMaterial.contains("bearer-token"))
        assertFalse(redactedMaterial.contains("api-token"))
        assertFalse(redactedMaterial.contains("temporary-signature"))
        assertFalse(redactedMaterial.contains("query-token"))
        assertTrue(redactedMaterial.contains("Authorization: Bearer [REDACTED]"))
        assertTrue(redactedMaterial.contains("sig=[REDACTED]"))
        assertTrue(redactedMaterial.contains("mode=fast"))
    }

    @Test
    fun structuralWorkflowFailureGetsExactlyOneRepairThenReturnsValidatedWorkflow() {
        val inputs = mutableListOf<String>()
        val prompts = mutableListOf<String>()
        val invalidWorkflow = validWorkflow().replace("\"schema_version\": 2", "\"schema_version\": 99")
        val generator = generator(
            replies = listOf(ok(invalidWorkflow), ok(validWorkflow())),
            inputs = inputs,
            prompts = prompts,
        )

        val result = generator.generate(
            PostProcessingConfig(),
            "rewrite-key",
            "Use the supported workflow.",
            "Documented request example.",
        )

        val document = result.getOrThrow()
        assertTrue(WorkflowValidator.validateWorkflow(AdvancedAudioWorkflowCodec.parse(document)).isEmpty())
        assertEquals(2, inputs.size)
        assertEquals(2, prompts.size)
        assertTrue(prompts.first().contains("workflow compiler for Dictate for Android"))
        assertTrue(prompts.last().contains("repairing one invalid Dictate for Android"))

        val repair = JsonValueCodec.parseObject(inputs.last())
        assertEquals(JsonValue.Number("1"), repair["input_version"])
        assertTrue(repair["previous_invalid_output"] is JsonValue.Text)
        assertTrue((repair["validation_error"] as JsonValue.Text).value.contains("Unsupported Workflow Schema Version"))
    }

    @Test
    fun explicitNonOkStatusIsReturnedWithoutRepair() {
        val inputs = mutableListOf<String>()
        val generator = generator(
            replies = listOf("""{"status":"needs_more_information","message":"Need the polling response example."}"""),
            inputs = inputs,
        )

        val result = generator.generate(
            PostProcessingConfig(),
            "rewrite-key",
            "Use async polling.",
            "The API is asynchronous.",
        )

        assertTrue(result.isFailure)
        assertEquals(1, inputs.size)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Need the polling response example."))
    }

    @Test
    fun displaySanitizerRedactsPresignedAndExplicitSecrets() {
        val safe = AdvancedAudioWorkflowGenerator.sanitizeForDisplay(
            "https://example.test/file?X-Amz-Signature=presigned-token&api_key=raw-key Bearer bearer-token",
            knownSecrets = listOf("raw-key"),
        )

        assertFalse(safe.contains("presigned-token"))
        assertFalse(safe.contains("raw-key"))
        assertFalse(safe.contains("bearer-token"))
    }

    private fun generator(
        replies: List<String>,
        inputs: MutableList<String>,
        prompts: MutableList<String> = mutableListOf(),
    ): AdvancedAudioWorkflowGenerator {
        var index = 0
        return AdvancedAudioWorkflowGenerator(
            AdvancedAudioWorkflowGenerator.RewriteExecutor { _, _, prompt, input, _ ->
                prompts += prompt.prompt
                inputs += input
                TranscriptionClient.Result.Success(replies[index++], 200, 1)
            },
        )
    }

    private fun ok(workflow: String): String = """{"status":"ok","workflow":$workflow}"""

    private fun validWorkflow(): String = checkNotNull(
        javaClass.classLoader?.getResourceAsStream("advanced_audio/workflows/valid-v2-typed-request.json"),
    ).bufferedReader().use { it.readText() }
}
