package com.joeykot.dictate.advanced_audio

import java.math.BigDecimal
import java.math.BigInteger
import java.util.Locale

/**
 * A small immutable JSON tree used by the workflow schema.
 *
 * Keeping this separate from [org.json.JSONObject] makes dynamic JSON values
 * safe to retain in an immutable workflow snapshot and lets typed parameters
 * preserve their native JSON shape until the request layer serializes them.
 */
sealed interface JsonValue {
    data object Null : JsonValue

    data class Bool(val value: Boolean) : JsonValue

    data class Number(val literal: kotlin.String) : JsonValue {
        init {
            require(JSON_NUMBER.matches(literal)) { "Invalid JSON number" }
            try {
                BigDecimal(literal)
            } catch (error: NumberFormatException) {
                throw IllegalArgumentException("Invalid JSON number", error)
            }
            require(literal.toDouble().isFinite()) { "Invalid JSON number" }
        }

        fun decimalValue(): BigDecimal = BigDecimal(literal)

        fun isIntegerLiteral(): Boolean = JSON_INTEGER.matches(literal)

        /**
         * `serde_json::Number::to_string()` compatible text for captures and
         * JSONPath scalar extraction. JSON keeps the original valid literal,
         * but serde_json stores decimal/exponent literals as f64 and therefore
         * canonicalizes them before later template interpolation.
         */
        fun serdeText(): String {
            if (isNativeSerdeInteger()) return literal
            return formatSerdeFloat(literal.toDouble())
        }

        fun serdeDouble(): Double = literal.toDouble()

        private fun isNativeSerdeInteger(): Boolean {
            if (!isIntegerLiteral() || literal == "-0") return false
            val number = try {
                BigInteger(literal)
            } catch (_: NumberFormatException) {
                return false
            }
            return number >= MIN_SIGNED_64 && number <= MAX_UNSIGNED_64
        }

        private fun formatSerdeFloat(value: Double): String {
            // The constructor already establishes finiteness. Keep this guard
            // here so manually constructed values cannot leak non-JSON text.
            require(value.isFinite()) { "Invalid JSON number" }
            val java = java.lang.Double.toString(value)
            val exponentMarker = java.indexOfAny(charArrayOf('E', 'e'))
            if (exponentMarker < 0) return java

            val mantissa = java.substring(0, exponentMarker)
            val exponent = java.substring(exponentMarker + 1).toInt()
            val integerDigits = mantissa.substringBefore('.').removePrefix("-").length
            val scientificExponent = exponent + integerDigits - 1
            if (scientificExponent in -5..15) {
                val plain = BigDecimal(java).toPlainString()
                return if (plain.contains('.')) plain else plain + ".0"
            }

            val compactMantissa = mantissa.removeSuffix(".0")
            val sign = if (exponent >= 0) "+" else ""
            return compactMantissa + "e" + sign + exponent
        }
    }

    data class Text(val value: kotlin.String) : JsonValue

    data class Array(val values: List<JsonValue>) : JsonValue

    data class Object(val values: Map<kotlin.String, JsonValue>) : JsonValue {
        operator fun get(name: kotlin.String): JsonValue? = values[name]
    }

    companion object {
        private val JSON_NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
        private val JSON_INTEGER = Regex("-?(?:0|[1-9][0-9]*)")
        private val MIN_SIGNED_64: BigInteger = BigInteger.valueOf(Long.MIN_VALUE)
        private val MAX_UNSIGNED_64: BigInteger = BigInteger("18446744073709551615")
    }
}

/** Strict JSON parsing and serialization without a workflow-specific library dependency. */
object JsonValueCodec {
    /** Maximum accepted JSON text before building an in-memory tree. */
    const val MAX_JSON_DOCUMENT_CHARS: Int = 2 * 1024 * 1024

    /**
     * Parses JSON without going through Android's [android.util.JsonReader].
     *
     * JsonReader reports some root number tokens as strings in the JVM test
     * environment, which would make a persisted v2 value such as `16000`
     * indistinguishable from the JSON string `"16000"`. The workflow contract
     * depends on preserving that distinction, so this deliberately small,
     * strict parser keeps every numeric literal verbatim.
     */
    fun parse(input: String): JsonValue = StrictJsonParser(input).parseDocument()

    fun parseObject(input: String): JsonValue.Object = parse(input) as? JsonValue.Object
        ?: throw IllegalArgumentException("Expected a JSON object")

    fun stringify(value: JsonValue): String = buildString { appendJson(value) }

    private fun StringBuilder.appendJson(value: JsonValue) {
        when (value) {
            JsonValue.Null -> append("null")
            is JsonValue.Bool -> append(if (value.value) "true" else "false")
            is JsonValue.Number -> append(value.literal)
            is JsonValue.Text -> appendQuoted(value.value)
            is JsonValue.Array -> {
                append('[')
                value.values.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendJson(item)
                }
                append(']')
            }

            is JsonValue.Object -> {
                append('{')
                value.values.entries.forEachIndexed { index, (name, item) ->
                    if (index > 0) append(',')
                    appendQuoted(name)
                    append(':')
                    appendJson(item)
                }
                append('}')
            }
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append(String.format(Locale.ROOT, "\\u%04x", character.code))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}

/** Strict RFC 8259 JSON reader retaining number literals exactly as written. */
private class StrictJsonParser(private val source: String) {
    private var index = 0
    private var nodes = 0

    fun parseDocument(): JsonValue {
        if (source.length > JsonValueCodec.MAX_JSON_DOCUMENT_CHARS) invalid()
        skipWhitespace()
        val value = parseValue(depth = 0)
        skipWhitespace()
        if (index != source.length) invalid()
        return value
    }

    private fun parseValue(depth: Int): JsonValue {
        if (index >= source.length) invalid()
        if (++nodes > MAX_JSON_NODES || depth > MAX_JSON_NESTING) invalid()
        return when (source[index]) {
            '{' -> parseObject(depth + 1)
            '[' -> parseArray(depth + 1)
            '"' -> JsonValue.Text(parseString())
            't' -> {
                takeLiteral("true")
                JsonValue.Bool(true)
            }

            'f' -> {
                takeLiteral("false")
                JsonValue.Bool(false)
            }

            'n' -> {
                takeLiteral("null")
                JsonValue.Null
            }

            '-', in '0'..'9' -> JsonValue.Number(parseNumber())
            else -> invalid()
        }
    }

    private fun parseObject(depth: Int): JsonValue.Object {
        expect('{')
        skipWhitespace()
        val values = LinkedHashMap<String, JsonValue>()
        if (take('}')) return JsonValue.Object(values)
        while (true) {
            skipWhitespace()
            if (!take('"')) invalid()
            index -= 1
            val name = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            values[name] = parseValue(depth)
            skipWhitespace()
            if (take('}')) return JsonValue.Object(values)
            expect(',')
            skipWhitespace()
        }
    }

    private fun parseArray(depth: Int): JsonValue.Array {
        expect('[')
        skipWhitespace()
        val values = mutableListOf<JsonValue>()
        if (take(']')) return JsonValue.Array(values)
        while (true) {
            values += parseValue(depth)
            skipWhitespace()
            if (take(']')) return JsonValue.Array(values)
            expect(',')
            skipWhitespace()
        }
    }

    private fun parseString(): String {
        expect('"')
        return buildString {
            while (index < source.length) {
                when (val character = source[index++]) {
                    '"' -> return@buildString
                    '\\' -> appendEscapedCharacter(this)
                    in '\u0000'..'\u001f' -> invalid()
                    else -> append(character)
                }
            }
            invalid()
        }.also {
            // A closing quote is the only successful exit from the builder.
            if (index == 0 || source[index - 1] != '"') invalid()
        }
    }

    private fun appendEscapedCharacter(target: StringBuilder) {
        if (index >= source.length) invalid()
        when (val escaped = source[index++]) {
            '"', '\\', '/' -> target.append(escaped)
            'b' -> target.append('\b')
            'f' -> target.append('\u000c')
            'n' -> target.append('\n')
            'r' -> target.append('\r')
            't' -> target.append('\t')
            'u' -> {
                if (index + 4 > source.length) invalid()
                val codePoint = source.substring(index, index + 4).toIntOrNull(16) ?: invalid()
                target.append(codePoint.toChar())
                index += 4
            }

            else -> invalid()
        }
    }

    private fun parseNumber(): String {
        val start = index
        take('-')
        when {
            take('0') -> Unit
            index < source.length && source[index] in '1'..'9' -> {
                index += 1
                while (index < source.length && source[index] in '0'..'9') index += 1
            }

            else -> invalid()
        }
        if (take('.')) {
            if (index >= source.length || source[index] !in '0'..'9') invalid()
            while (index < source.length && source[index] in '0'..'9') index += 1
        }
        if (index < source.length && source[index] in setOf('e', 'E')) {
            index += 1
            if (index < source.length && source[index] in setOf('+', '-')) index += 1
            if (index >= source.length || source[index] !in '0'..'9') invalid()
            while (index < source.length && source[index] in '0'..'9') index += 1
        }
        return source.substring(start, index)
    }

    private fun takeLiteral(value: String) {
        if (!source.regionMatches(index, value, 0, value.length)) invalid()
        index += value.length
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index] in JSON_WHITESPACE) index += 1
    }

    private fun expect(character: Char) {
        if (!take(character)) invalid()
    }

    private fun take(character: Char): Boolean =
        (index < source.length && source[index] == character).also { if (it) index += 1 }

    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid JSON")

    private companion object {
        const val JSON_WHITESPACE = " \t\n\r"
        const val MAX_JSON_NESTING = 128
        const val MAX_JSON_NODES = 262_144
    }
}
