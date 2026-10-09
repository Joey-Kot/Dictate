package com.joeykot.dictate.advanced_audio.extractor

import com.joeykot.dictate.advanced_audio.http.HttpResponseData
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * JSONPath is deliberately an injectable boundary.  The schema/validation
 * layer will select the cross-platform evaluator and validate a path before
 * network I/O; this package keeps response bounds, scalar semantics, and
 * secret-safe error handling independent from that choice.
 */
fun interface JsonPathScalarEvaluator {
    @Throws(JsonPathEvaluationException::class)
    fun extractOne(body: ByteArray, path: String): String
}

sealed class JsonPathEvaluationException(message: String) : Exception(message) {
    data object InvalidPath : JsonPathEvaluationException("Invalid JSONPath response extractor")
    data object InvalidRegex : JsonPathEvaluationException(
        "JSONPath match/search regular expression is not supported by the cross-platform RE2 engine",
    )
    data object InvalidJson : JsonPathEvaluationException("Response body is not valid JSON")
    data object NoMatch : JsonPathEvaluationException("JSONPath matched no values; expected exactly one")
    data class MultipleMatches(val count: Int) :
        JsonPathEvaluationException("JSONPath matched $count values; expected exactly one")

    data class InvalidType(val kind: String) :
        JsonPathEvaluationException("JSONPath selected $kind; expected a string, number or boolean")
}

/**
 * Error messages exclude response body and header values: captures often hold
 * task IDs, presigned URLs, or provider credentials.
 */
sealed class ResponseExtractionException(message: String) : IllegalArgumentException(message) {
    data object ResponseTooLarge : ResponseExtractionException(
        "Response body exceeds the $MAX_RESPONSE_BODY_BYTES-byte extraction limit",
    )

    data object ValueTooLarge : ResponseExtractionException(
        "Extracted response value exceeds the $MAX_EXTRACTED_VALUE_BYTES-byte limit",
    )

    data object InvalidBodyUtf8 : ResponseExtractionException("Response body is not valid UTF-8")
    data object InvalidHeaderName : ResponseExtractionException("Response extractor contains an invalid HTTP header name")
    data class HeaderMissing(val name: String) :
        ResponseExtractionException("Response header '$name' was not present")

    data class HeaderTooLarge(val name: String) :
        ResponseExtractionException("Response header '$name' exceeds the $MAX_EXTRACTED_VALUE_BYTES-byte limit")

    data object TooManyCaptures : ResponseExtractionException("Workflow contains more than $MAX_CAPTURE_COUNT captures")
    data class DuplicateCapture(val id: String) : ResponseExtractionException("Capture '$id' is declared more than once")
    data object CapturesTooLarge : ResponseExtractionException(
        "Combined captured response data exceeds the $MAX_CAPTURE_BYTES-byte limit",
    )
}

object ResponseExtraction {
    fun jsonPath(
        response: HttpResponseData,
        path: String,
        evaluator: JsonPathScalarEvaluator,
    ): String {
        requireBodyWithinLimit(response.body)
        val extracted = try {
            evaluator.extractOne(response.body, path)
        } catch (error: JsonPathEvaluationException) {
            throw error
        }
        return requireValueWithinLimit(extracted)
    }

    fun header(response: HttpResponseData, name: String): String {
        if (!HTTP_HEADER_NAME.matches(name)) throw ResponseExtractionException.InvalidHeaderName
        val value = response.headerValues(name).firstOrNull()
            ?: throw ResponseExtractionException.HeaderMissing(name)
        if (value.toByteArray(StandardCharsets.UTF_8).size > MAX_EXTRACTED_VALUE_BYTES) {
            throw ResponseExtractionException.HeaderTooLarge(name)
        }
        return value
    }

    fun plainBody(response: HttpResponseData): String {
        requireBodyWithinLimit(response.body)
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val value = try {
            decoder.decode(ByteBuffer.wrap(response.body)).toString()
        } catch (_: CharacterCodingException) {
            throw ResponseExtractionException.InvalidBodyUtf8
        }
        return requireValueWithinLimit(value)
    }

    fun status(response: HttpResponseData): String = response.statusCode.toString()

    private fun requireBodyWithinLimit(body: ByteArray) {
        if (body.size > MAX_RESPONSE_BODY_BYTES) throw ResponseExtractionException.ResponseTooLarge
    }

    private fun requireValueWithinLimit(value: String): String {
        if (value.toByteArray(StandardCharsets.UTF_8).size > MAX_EXTRACTED_VALUE_BYTES) {
            throw ResponseExtractionException.ValueTooLarge
        }
        return value
    }
}

/**
 * The workflow executor owns extraction-rule dispatch and feeds successful
 * values into this collector.  That keeps capture limits consistent across
 * JSONPath, headers, plain-body, and status extractors without duplicating the
 * workflow [Capture] schema model in the transport package.
 */
class ResponseCaptureCollector {
    private val values = LinkedHashMap<String, String>()

    /**
     * Commits the captures produced by one HTTP stage atomically.
     *
     * The count and byte limits bound one materialized response, matching the
     * Windows executor.  Successfully committed values remain available to
     * later stages, but must not consume the next response's extraction budget.
     */
    fun putStage(entries: List<Pair<String, String>>) {
        if (entries.size > MAX_CAPTURE_COUNT) throw ResponseExtractionException.TooManyCaptures

        val localIds = HashSet<String>(entries.size)
        var stageBytes = 0
        entries.forEach { (id, value) ->
            if (values.containsKey(id) || !localIds.add(id)) {
                throw ResponseExtractionException.DuplicateCapture(id)
            }
            val bytes = value.toByteArray(StandardCharsets.UTF_8).size
            if (stageBytes > MAX_CAPTURE_BYTES - bytes) {
                throw ResponseExtractionException.CapturesTooLarge
            }
            stageBytes += bytes
        }
        entries.forEach { (id, value) -> values[id] = value }
    }

    fun snapshot(): Map<String, String> = values.toMap()
}

/** Matches the Windows runtime limits before values can enter later templates. */
const val MAX_RESPONSE_BODY_BYTES: Int = 2 * 1024 * 1024
const val MAX_EXTRACTED_VALUE_BYTES: Int = 256 * 1024
const val MAX_CAPTURE_COUNT: Int = 64
const val MAX_CAPTURE_BYTES: Int = 512 * 1024

private val HTTP_HEADER_NAME = Regex("^[!#\\$%&'*+\\-.^_`|~0-9A-Za-z]+$")
