package com.joeykot.dictate.advanced_audio

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Shared framing limit for streamed recognition responses.
 *
 * It applies to every SSE physical line, the retained fields of one SSE
 * event, an NDJSON line, and the still-pending JSON chunk buffer. Keeping the
 * same bound at each framing layer prevents a provider from bypassing the
 * stream limit with a different wire format.
 */
const val MAX_STREAM_FRAME_BYTES: Int = 1024 * 1024

/** One raw Server-Sent Events field, retained in wire order. */
data class SseField(
    val name: String,
    val value: String,
)

/**
 * One complete Server-Sent Events event.
 *
 * Repeated [data] fields are joined with a newline. [fields] deliberately
 * retains known and vendor-specific fields so protocol interpretation remains
 * outside the generic framing layer.
 */
data class SseEvent(
    val event: String?,
    val data: String,
    val id: String?,
    val retry: ULong?,
    val fields: List<SseField>,
)

/** A raw, non-empty NDJSON line. [line] is one-based in the wire stream. */
data class NdjsonFrame(
    val line: Int,
    val data: String,
)

/** Errors produced before a workflow maps frames to transcript actions. */
sealed class StreamFrameException(message: String) : IllegalArgumentException(message) {
    data class InvalidUtf8(
        val format: String,
        val line: Int,
        val detail: String,
    ) : StreamFrameException("invalid UTF-8 in $format stream at line $line: $detail")

    data class FrameTooLarge(
        val format: String,
        val maximum: Int = MAX_STREAM_FRAME_BYTES,
    ) : StreamFrameException("$format stream contains a line, event, or value larger than $maximum bytes")

    data class InvalidJsonChunk(
        val detail: String,
    ) : StreamFrameException("invalid JSON chunk: $detail")
}

/**
 * Incrementally decodes SSE frames from arbitrary HTTP response chunks.
 *
 * Incomplete UTF-8 sequences and lines remain buffered until a later [push].
 * [finish] accepts a final unterminated event when it has at least one field.
 */
class SseDecoder {
    private var pendingBytes = ByteArray(0)
    private val pendingEvent = PendingSseEvent()
    private var line = 1
    private var firstLine = true

    fun push(chunk: ByteArray): List<SseEvent> {
        pendingBytes = appendBytes(pendingBytes, chunk)
        val events = mutableListOf<SseEvent>()
        var consumed = 0
        while (true) {
            val relativeNewline = pendingBytes.indexOfByte('\n'.code.toByte(), consumed)
            if (relativeNewline < 0) break
            val bytes = pendingBytes.copyOfRange(consumed, relativeNewline)
            processLine(bytes)?.let(events::add)
            line += 1
            consumed = relativeNewline + 1
        }
        if (consumed != 0) pendingBytes = pendingBytes.copyOfRange(consumed, pendingBytes.size)
        ensureFrameSize("SSE", withoutTrailingCr(pendingBytes).size)
        return events
    }

    fun finish(): List<SseEvent> {
        val events = mutableListOf<SseEvent>()
        val bytes = pendingBytes
        pendingBytes = ByteArray(0)
        if (bytes.isNotEmpty()) processLine(bytes)?.let(events::add)
        if (!pendingEvent.isEmpty()) events += pendingEvent.take()
        return events
    }

    private fun processLine(rawBytes: ByteArray): SseEvent? {
        val bytes = withoutTrailingCr(rawBytes)
        ensureFrameSize("SSE", bytes.size)
        var value = decodeUtf8("SSE", line, bytes)
        if (firstLine) {
            firstLine = false
            value = value.removePrefix("\uFEFF")
        }
        if (value.isEmpty()) return if (pendingEvent.isEmpty()) null else pendingEvent.take()
        if (value.startsWith(':')) return null

        val separator = value.indexOf(':')
        val name = if (separator >= 0) value.substring(0, separator) else value
        var fieldValue = if (separator >= 0) value.substring(separator + 1) else ""
        if (fieldValue.startsWith(' ')) fieldValue = fieldValue.substring(1)

        pendingEvent.addFieldBytes(bytes.size)
        pendingEvent.fields += SseField(name, fieldValue)
        when (name) {
            "event" -> pendingEvent.event = fieldValue
            "data" -> pendingEvent.dataLines += fieldValue
            "id" -> pendingEvent.id = fieldValue
            "retry" -> pendingEvent.retry = fieldValue.toULongOrNull()
        }
        return null
    }

    private class PendingSseEvent {
        var event: String? = null
        val dataLines = mutableListOf<String>()
        var id: String? = null
        var retry: ULong? = null
        val fields = mutableListOf<SseField>()
        private var fieldBytes = 0

        fun isEmpty(): Boolean = fields.isEmpty()

        fun take(): SseEvent {
            fieldBytes = 0
            val value = SseEvent(
                event = event,
                data = dataLines.joinToString("\n"),
                id = id,
                retry = retry,
                fields = fields.toList(),
            )
            event = null
            dataLines.clear()
            id = null
            retry = null
            fields.clear()
            return value
        }

        fun addFieldBytes(bytes: Int) {
            if (fieldBytes > MAX_STREAM_FRAME_BYTES - bytes) throw frameTooLarge("SSE")
            fieldBytes += bytes
        }
    }
}

/** Splits a complete in-memory SSE response into raw frames. */
fun parseSseFrames(bytes: ByteArray): List<SseEvent> {
    val decoder = SseDecoder()
    return decoder.push(bytes) + decoder.finish()
}

/**
 * Incrementally splits newline-delimited JSON into raw, unparsed lines.
 *
 * Blank lines are ignored; JSON interpretation belongs to the workflow rule
 * layer because valid providers can stream objects, arrays, strings, or
 * scalar values.
 */
class NdjsonDecoder {
    private var pendingBytes = ByteArray(0)
    private var line = 1

    fun push(chunk: ByteArray): List<NdjsonFrame> {
        pendingBytes = appendBytes(pendingBytes, chunk)
        val frames = mutableListOf<NdjsonFrame>()
        var consumed = 0
        while (true) {
            val relativeNewline = pendingBytes.indexOfByte('\n'.code.toByte(), consumed)
            if (relativeNewline < 0) break
            frame(pendingBytes.copyOfRange(consumed, relativeNewline))?.let(frames::add)
            line += 1
            consumed = relativeNewline + 1
        }
        if (consumed != 0) pendingBytes = pendingBytes.copyOfRange(consumed, pendingBytes.size)
        ensureFrameSize("NDJSON", withoutTrailingCr(pendingBytes).size)
        return frames
    }

    fun finish(): List<NdjsonFrame> {
        if (pendingBytes.isEmpty()) return emptyList()
        val bytes = pendingBytes
        pendingBytes = ByteArray(0)
        return listOfNotNull(frame(bytes))
    }

    private fun frame(rawBytes: ByteArray): NdjsonFrame? {
        val bytes = withoutTrailingCr(rawBytes)
        ensureFrameSize("NDJSON", bytes.size)
        val data = decodeUtf8("NDJSON", line, bytes)
        return if (data.trim().isEmpty()) null else NdjsonFrame(line, data)
    }
}

/** Splits a complete in-memory NDJSON response into raw frames. */
fun parseNdjsonFrames(bytes: ByteArray): List<NdjsonFrame> {
    val decoder = NdjsonDecoder()
    return decoder.push(bytes) + decoder.finish()
}

/**
 * Incrementally decodes consecutive JSON values without requiring newlines.
 *
 * Values are returned in canonical JSON form, matching the Windows
 * `serde_json::Value::to_string()` behavior. An incomplete trailing value is
 * retained until another [push], then rejected by [finish] if it never closes.
 */
class JsonChunksDecoder {
    private var pendingBytes = ByteArray(0)

    fun push(chunk: ByteArray): List<String> {
        pendingBytes = appendBytes(pendingBytes, chunk)
        return decode(finalChunk = false)
    }

    fun finish(): List<String> = decode(finalChunk = true)

    private fun decode(finalChunk: Boolean): List<String> {
        val values = mutableListOf<String>()
        while (true) {
            val leading = pendingBytes.indexOfFirstNonAsciiWhitespace()
            pendingBytes = if (leading >= pendingBytes.size) {
                ByteArray(0)
            } else if (leading != 0) {
                pendingBytes.copyOfRange(leading, pendingBytes.size)
            } else {
                pendingBytes
            }
            if (pendingBytes.isEmpty()) return values
            ensureFrameSize("JSON chunks", pendingBytes.size)

            when (val boundary = findJsonValueBoundary(pendingBytes)) {
                JsonBoundary.NeedMore -> {
                    if (finalChunk) {
                        throw StreamFrameException.InvalidJsonChunk(
                            "stream ended with an incomplete JSON value",
                        )
                    }
                    return values
                }

                is JsonBoundary.Invalid -> throw StreamFrameException.InvalidJsonChunk(boundary.detail)
                is JsonBoundary.Complete -> {
                    val source = decodeJsonChunk(pendingBytes.copyOfRange(0, boundary.endExclusive))
                    val parsed = try {
                        JsonValueCodec.parse(source)
                    } catch (_: IllegalArgumentException) {
                        throw StreamFrameException.InvalidJsonChunk("malformed JSON value")
                    }
                    values += JsonValueCodec.stringify(parsed)
                    pendingBytes = pendingBytes.copyOfRange(boundary.endExclusive, pendingBytes.size)
                }
            }
        }
    }
}

private sealed interface JsonBoundary {
    data object NeedMore : JsonBoundary

    data class Complete(val endExclusive: Int) : JsonBoundary

    data class Invalid(val detail: String) : JsonBoundary
}

private fun findJsonValueBoundary(bytes: ByteArray): JsonBoundary {
    if (bytes.isEmpty()) return JsonBoundary.NeedMore
    return when (val first = bytes[0].toInt() and 0xFF) {
        '{'.code, '['.code -> findCompositeBoundary(bytes)
        '"'.code -> findStringBoundary(bytes)
        't'.code -> findLiteralBoundary(bytes, "true")
        'f'.code -> findLiteralBoundary(bytes, "false")
        'n'.code -> findLiteralBoundary(bytes, "null")
        '-'.code, in '0'.code..'9'.code -> findNumberBoundary(bytes)
        else -> JsonBoundary.Invalid("expected a JSON value")
    }
}

private fun findCompositeBoundary(bytes: ByteArray): JsonBoundary {
    val expectedClosers = ArrayDeque<Int>()
    expectedClosers.addLast(if (bytes[0].toInt() == '{'.code) '}'.code else ']'.code)
    var inString = false
    var escaped = false
    for (index in 1 until bytes.size) {
        val value = bytes[index].toInt() and 0xFF
        if (inString) {
            when {
                escaped -> escaped = false
                value == '\\'.code -> escaped = true
                value == '"'.code -> inString = false
                value < 0x20 -> return JsonBoundary.Invalid("unescaped control character in JSON string")
            }
            continue
        }
        when (value) {
            '"'.code -> inString = true
            '{'.code -> expectedClosers.addLast('}'.code)
            '['.code -> expectedClosers.addLast(']'.code)
            '}'.code, ']'.code -> {
                val expected = expectedClosers.removeLastOrNull()
                    ?: return JsonBoundary.Invalid("unexpected JSON closing delimiter")
                if (value != expected) return JsonBoundary.Invalid("mismatched JSON closing delimiter")
                if (expectedClosers.isEmpty()) return JsonBoundary.Complete(index + 1)
            }
        }
    }
    return JsonBoundary.NeedMore
}

private fun findStringBoundary(bytes: ByteArray): JsonBoundary {
    var escaped = false
    for (index in 1 until bytes.size) {
        val value = bytes[index].toInt() and 0xFF
        when {
            escaped -> escaped = false
            value == '\\'.code -> escaped = true
            value == '"'.code -> return JsonBoundary.Complete(index + 1)
            value < 0x20 -> return JsonBoundary.Invalid("unescaped control character in JSON string")
        }
    }
    return JsonBoundary.NeedMore
}

private fun findLiteralBoundary(bytes: ByteArray, literal: String): JsonBoundary {
    val length = minOf(bytes.size, literal.length)
    for (index in 0 until length) {
        if ((bytes[index].toInt() and 0xFF) != literal[index].code) {
            return JsonBoundary.Invalid("invalid JSON literal")
        }
    }
    return if (bytes.size < literal.length) JsonBoundary.NeedMore else JsonBoundary.Complete(literal.length)
}

private fun findNumberBoundary(bytes: ByteArray): JsonBoundary {
    var end = 0
    while (end < bytes.size && isNumberByte(bytes[end])) end += 1
    val text = String(bytes, 0, end, StandardCharsets.US_ASCII)
    if (JSON_NUMBER.matches(text)) return JsonBoundary.Complete(end)
    if (end == bytes.size && isIncompleteNumberPrefix(text)) return JsonBoundary.NeedMore
    return JsonBoundary.Invalid("invalid JSON number")
}

private fun isNumberByte(value: Byte): Boolean = when (value.toInt().toChar()) {
    '-', '+', '.', 'e', 'E' -> true
    in '0'..'9' -> true
    else -> false
}

private fun isIncompleteNumberPrefix(value: String): Boolean {
    if (value == "-") return true
    return value.matches(Regex("-?(?:0|[1-9][0-9]*)?(?:\\.[0-9]*)?(?:[eE][+-]?[0-9]*)?"))
}

private val JSON_NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

private fun decodeJsonChunk(bytes: ByteArray): String = try {
    strictUtf8(bytes)
} catch (_: CharacterCodingException) {
    throw StreamFrameException.InvalidJsonChunk("invalid UTF-8")
}

private fun decodeUtf8(format: String, line: Int, bytes: ByteArray): String = try {
    strictUtf8(bytes)
} catch (error: CharacterCodingException) {
    throw StreamFrameException.InvalidUtf8(
        format = format,
        line = line,
        detail = error.message ?: "invalid UTF-8",
    )
}

private fun strictUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes))
    .toString()

private fun withoutTrailingCr(bytes: ByteArray): ByteArray = if (bytes.lastOrNull() == '\r'.code.toByte()) {
    bytes.copyOf(bytes.size - 1)
} else {
    bytes
}

private fun ensureFrameSize(format: String, size: Int) {
    if (size > MAX_STREAM_FRAME_BYTES) throw frameTooLarge(format)
}

private fun frameTooLarge(format: String): StreamFrameException.FrameTooLarge =
    StreamFrameException.FrameTooLarge(format, MAX_STREAM_FRAME_BYTES)

private fun appendBytes(existing: ByteArray, next: ByteArray): ByteArray {
    if (next.isEmpty()) return existing
    val combined = ByteArray(existing.size + next.size)
    existing.copyInto(combined)
    next.copyInto(combined, destinationOffset = existing.size)
    return combined
}

private fun ByteArray.indexOfByte(value: Byte, startIndex: Int): Int {
    for (index in startIndex until size) if (this[index] == value) return index
    return -1
}

private fun ByteArray.indexOfFirstNonAsciiWhitespace(): Int {
    for (index in indices) {
        val value = this[index].toInt() and 0xFF
        if (value != 0x20 && value !in 0x09..0x0D) return index
    }
    return size
}
