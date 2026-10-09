package com.joeykot.dictate.advanced_audio.extractor

import com.joeykot.dictate.advanced_audio.http.HttpHeader
import com.joeykot.dictate.advanced_audio.http.HttpResponseData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseExtractionTest {
    @Test
    fun headerPlainBodyStatusAndJsonPathUseBoundedSecretSafeValues() {
        val response = HttpResponseData(
            statusCode = 202,
            headers = listOf(HttpHeader("X-Task-ID", "task-42")),
            body = "plain response".toByteArray(),
        )
        assertEquals("task-42", ResponseExtraction.header(response, "x-task-id"))
        assertEquals("plain response", ResponseExtraction.plainBody(response))
        assertEquals("202", ResponseExtraction.status(response))
        assertEquals(
            "transcript",
            ResponseExtraction.jsonPath(response, "$.text") { _, path ->
                assertEquals("$.text", path)
                "transcript"
            },
        )
    }

    @Test
    fun extractionErrorsDoNotExposeResponseValues() {
        val secret = "response-secret-value"
        val response = HttpResponseData(
            statusCode = 200,
            headers = listOf(HttpHeader("X-Secret", secret)),
            body = secret.toByteArray(),
        )
        val headerError = capture { ResponseExtraction.header(response, "bad header") }
        val pathError = capture {
            ResponseExtraction.jsonPath(response, "$.missing") { _, _ -> throw JsonPathEvaluationException.NoMatch }
        }
        assertFalse(headerError.message.orEmpty().contains(secret))
        assertFalse(pathError.message.orEmpty().contains(secret))
        assertTrue(headerError is ResponseExtractionException.InvalidHeaderName)
        assertTrue(pathError is JsonPathEvaluationException.NoMatch)
    }

    @Test
    fun collectorLimitsAndCommitsCapturesPerStage() {
        val collector = ResponseCaptureCollector()
        collector.putStage(listOf("task" to "id"))
        assertEquals(mapOf("task" to "id"), collector.snapshot())

        assertTrue(
            capture { collector.putStage(listOf("task" to "other")) } is ResponseExtractionException.DuplicateCapture,
        )
        val large = "x".repeat(MAX_CAPTURE_BYTES)
        assertTrue(
            capture {
                ResponseCaptureCollector().putStage(listOf("large" to large, "next" to "x"))
            } is ResponseExtractionException.CapturesTooLarge,
        )

        val perStage = ResponseCaptureCollector()
        perStage.putStage(listOf("prepare" to large))
        perStage.putStage(listOf("submit" to large))
        assertEquals(setOf("prepare", "submit"), perStage.snapshot().keys)
    }

    private fun capture(block: () -> Unit): Throwable = try {
        block()
        error("operation unexpectedly succeeded")
    } catch (error: Throwable) {
        error
    }
}
