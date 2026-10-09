package com.joeykot.dictate.advanced_audio

import com.google.re2j.Pattern as Re2Pattern
import com.google.re2j.PatternSyntaxException as Re2PatternSyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

/**
 * A bounded evaluator for the RFC 9535 JSONPath language accepted by the
 * Windows workflow implementation. It intentionally has no script, host, or
 * reflection hooks: selectors and the standard pure filter functions are the
 * complete evaluation surface.
 */
class JsonPath internal constructor(
    private val query: PathQuery,
) {
    fun query(root: JsonValue): List<JsonValue> =
        query.evaluate(current = root, root = root, state = EvaluationState())

    fun extractOne(root: JsonValue): String {
        val matches = query(root)
        return when (matches.size) {
            0 -> throw JsonPathException.NoMatch
            1 -> scalar(matches.single())
            else -> throw JsonPathException.MultipleMatches(matches.size)
        }
    }

    companion object {
        fun parse(input: String): JsonPath = PathParser(input).parse()

        fun extractOne(body: ByteArray, path: String): String {
            if (body.size > MAX_JSONPATH_RESPONSE_BYTES) throw JsonPathException.InvalidJson
            val text = try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString()
            } catch (_: CharacterCodingException) {
                throw JsonPathException.InvalidJson
            }
            val root = try {
                JsonValueCodec.parse(text)
            } catch (_: IllegalArgumentException) {
                throw JsonPathException.InvalidJson
            }
            return parse(path).extractOne(root)
        }

        private fun scalar(value: JsonValue): String = when (value) {
            is JsonValue.Text -> value.value
            is JsonValue.Number -> value.serdeText()
            is JsonValue.Bool -> value.value.toString()
            JsonValue.Null -> throw JsonPathException.InvalidType("null")
            is JsonValue.Array -> throw JsonPathException.InvalidType("an array")
            is JsonValue.Object -> throw JsonPathException.InvalidType("an object")
        }
    }
}

sealed class JsonPathException(message: String) : IllegalArgumentException(message) {
    data object InvalidPath : JsonPathException("invalid JSONPath response extractor")
    data object InvalidRegex : JsonPathException(
        "JSONPath match/search regular expression is not supported by the cross-platform RE2 engine",
    )
    data object InvalidJson : JsonPathException("response body is not valid JSON")
    data object NoMatch : JsonPathException("JSONPath matched no values; expected exactly one")
    data class MultipleMatches(val count: Int) : JsonPathException("JSONPath matched " + count + " values; expected exactly one")
    data class InvalidType(val kind: String) : JsonPathException(
        "JSONPath selected " + kind + "; expected a string, number or boolean",
    )
}

internal data class PathQuery(
    val origin: QueryOrigin,
    val segments: List<PathSegment>,
) {
    fun evaluate(current: JsonValue, root: JsonValue, state: EvaluationState): List<JsonValue> {
        var values: List<JsonValue> = listOf(if (origin == QueryOrigin.ROOT) root else current)
        segments.forEach { segment ->
            val selected = ArrayList<JsonValue>()
            values.forEach { value ->
                if (segment.descendant) {
                    segment.selectDescendants(value, root, state, selected)
                } else {
                    segment.select(value, root, state, selected)
                }
            }
            values = selected
        }
        return values
    }

    fun isSingular(): Boolean = segments.none { it.descendant || it.selectors.size != 1 || !it.selectors.single().isSingular }
}

internal enum class QueryOrigin {
    ROOT,
    CURRENT,
}

internal data class PathSegment(
    val descendant: Boolean,
    val selectors: List<PathSelector>,
) {
    fun select(
        value: JsonValue,
        root: JsonValue,
        state: EvaluationState,
        output: MutableList<JsonValue>,
    ) {
        state.consumeWork()
        selectors.forEach { selector ->
            selector.select(value, root, state).forEach { match ->
                state.add(output, match)
            }
        }
    }

    fun selectDescendants(
        value: JsonValue,
        root: JsonValue,
        state: EvaluationState,
        output: MutableList<JsonValue>,
    ) {
        val pending = ArrayDeque<JsonValue>()
        pending.addLast(value)
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            select(current, root, state, output)
            val children = directChildren(current)
            for (childIndex in children.indices.reversed()) {
                pending.addLast(children[childIndex])
            }
        }
    }
}

internal sealed interface PathSelector {
    val isSingular: Boolean

    fun select(current: JsonValue, root: JsonValue, state: EvaluationState): List<JsonValue>

    data class Field(val name: String) : PathSelector {
        override val isSingular: Boolean = true

        override fun select(current: JsonValue, root: JsonValue, state: EvaluationState): List<JsonValue> =
            (current as? JsonValue.Object)?.values?.get(name)?.let(::listOf) ?: emptyList()
    }

    data class Index(val index: Long) : PathSelector {
        override val isSingular: Boolean = true

        override fun select(current: JsonValue, root: JsonValue, state: EvaluationState): List<JsonValue> {
            val values = (current as? JsonValue.Array)?.values ?: return emptyList()
            val resolved = normalizeIndex(index, values.size)
            return values.getOrNull(resolved)?.let(::listOf) ?: emptyList()
        }
    }

    data object Wildcard : PathSelector {
        override val isSingular: Boolean = false

        override fun select(current: JsonValue, root: JsonValue, state: EvaluationState): List<JsonValue> = directChildren(current)
    }

    data class Slice(
        val start: Long?,
        val end: Long?,
        val step: Long?,
    ) : PathSelector {
        override val isSingular: Boolean = false

        override fun select(current: JsonValue, root: JsonValue, state: EvaluationState): List<JsonValue> {
            val values = (current as? JsonValue.Array)?.values ?: return emptyList()
            return sliceIndexes(values.size, start, end, step).map(values::get)
        }
    }

    data class Filter(val expression: FilterExpression) : PathSelector {
        override val isSingular: Boolean = false

        override fun select(current: JsonValue, root: JsonValue, state: EvaluationState): List<JsonValue> =
            directChildren(current).filter { candidate ->
                state.consumeWork()
                expression.matches(candidate, root, state)
            }
    }
}

internal sealed interface FilterExpression {
    fun matches(current: JsonValue, root: JsonValue, state: EvaluationState): Boolean

    data class AnyOf(val expressions: List<FilterExpression>) : FilterExpression {
        override fun matches(current: JsonValue, root: JsonValue, state: EvaluationState): Boolean =
            expressions.any { it.matches(current, root, state) }
    }

    data class AllOf(val expressions: List<FilterExpression>) : FilterExpression {
        override fun matches(current: JsonValue, root: JsonValue, state: EvaluationState): Boolean =
            expressions.all { it.matches(current, root, state) }
    }

    data class Negated(val expression: FilterExpression) : FilterExpression {
        override fun matches(current: JsonValue, root: JsonValue, state: EvaluationState): Boolean =
            !expression.matches(current, root, state)
    }

    data class Exists(val query: PathQuery) : FilterExpression {
        override fun matches(current: JsonValue, root: JsonValue, state: EvaluationState): Boolean =
            query.evaluate(current, root, state).isNotEmpty()
    }

    data class Compare(
        val left: FilterComparable,
        val operator: FilterOperator,
        val right: FilterComparable,
    ) : FilterExpression {
        override fun matches(current: JsonValue, root: JsonValue, state: EvaluationState): Boolean {
            val leftValue = left.evaluate(current, root, state)
            val rightValue = right.evaluate(current, root, state)
            return when (operator) {
                FilterOperator.EQ -> optionalEquals(leftValue, rightValue)
                FilterOperator.NE -> !optionalEquals(leftValue, rightValue)
                FilterOperator.LT -> sameType(leftValue, rightValue) && optionalLessThan(leftValue, rightValue)
                FilterOperator.GT -> sameType(leftValue, rightValue) &&
                    !optionalLessThan(leftValue, rightValue) &&
                    !optionalEquals(leftValue, rightValue)

                FilterOperator.LE -> sameType(leftValue, rightValue) &&
                    (optionalLessThan(leftValue, rightValue) || optionalEquals(leftValue, rightValue))

                FilterOperator.GE -> sameType(leftValue, rightValue) && !optionalLessThan(leftValue, rightValue)
            }
        }
    }

    data class FunctionPredicate(val function: FilterFunction) : FilterExpression {
        override fun matches(current: JsonValue, root: JsonValue, state: EvaluationState): Boolean =
            (function.evaluate(current, root, state) as? FunctionValue.Logical)?.value == true
    }
}

internal sealed interface FilterComparable {
    fun evaluate(current: JsonValue, root: JsonValue, state: EvaluationState): OptionalJsonValue

    data class Literal(val value: JsonValue) : FilterComparable {
        override fun evaluate(current: JsonValue, root: JsonValue, state: EvaluationState): OptionalJsonValue =
            OptionalJsonValue.Present(value)
    }

    data class SingularQuery(val query: PathQuery) : FilterComparable {
        override fun evaluate(current: JsonValue, root: JsonValue, state: EvaluationState): OptionalJsonValue =
            query.evaluate(current, root, state).singleOrNull()?.let(OptionalJsonValue::Present)
                ?: OptionalJsonValue.Missing
    }

    data class ValueFunction(val function: FilterFunction) : FilterComparable {
        override fun evaluate(current: JsonValue, root: JsonValue, state: EvaluationState): OptionalJsonValue =
            when (val value = function.evaluate(current, root, state)) {
                is FunctionValue.Value -> OptionalJsonValue.Present(value.value)
                else -> OptionalJsonValue.Missing
            }
    }
}

internal sealed interface OptionalJsonValue {
    data object Missing : OptionalJsonValue
    data class Present(val value: JsonValue) : OptionalJsonValue
}

internal enum class FilterOperator {
    EQ,
    NE,
    LT,
    GT,
    LE,
    GE,
}

internal sealed interface FilterFunction {
    val resultKind: FunctionResultKind

    fun evaluate(current: JsonValue, root: JsonValue, state: EvaluationState): FunctionValue

    data class Length(val argument: FilterComparable) : FilterFunction {
        override val resultKind: FunctionResultKind = FunctionResultKind.VALUE

        override fun evaluate(current: JsonValue, root: JsonValue, state: EvaluationState): FunctionValue {
            val value = (argument.evaluate(current, root, state) as? OptionalJsonValue.Present)?.value
                ?: return FunctionValue.Nothing
            val length = when (value) {
                is JsonValue.Text -> value.value.codePointCount(0, value.value.length)
                is JsonValue.Array -> value.values.size
                is JsonValue.Object -> value.values.size
                else -> return FunctionValue.Nothing
            }
            return FunctionValue.Value(JsonValue.Number(length.toString()))
        }
    }

    data class Count(val query: PathQuery) : FilterFunction {
        override val resultKind: FunctionResultKind = FunctionResultKind.VALUE

        override fun evaluate(current: JsonValue, root: JsonValue, state: EvaluationState): FunctionValue =
            FunctionValue.Value(JsonValue.Number(query.evaluate(current, root, state).size.toString()))
    }

    data class Value(val query: PathQuery) : FilterFunction {
        override val resultKind: FunctionResultKind = FunctionResultKind.VALUE

        override fun evaluate(current: JsonValue, root: JsonValue, state: EvaluationState): FunctionValue {
            val nodes = query.evaluate(current, root, state)
            return if (nodes.size == 1) FunctionValue.Value(nodes.single()) else FunctionValue.Nothing
        }
    }

    data class Match(val value: FilterComparable, val expression: FilterComparable) : FilterFunction {
        override val resultKind: FunctionResultKind = FunctionResultKind.LOGICAL

        override fun evaluate(current: JsonValue, root: JsonValue, state: EvaluationState): FunctionValue =
            FunctionValue.Logical(matchesRegex(value, expression, current, root, state, anchored = true))
    }

    data class Search(val value: FilterComparable, val expression: FilterComparable) : FilterFunction {
        override val resultKind: FunctionResultKind = FunctionResultKind.LOGICAL

        override fun evaluate(current: JsonValue, root: JsonValue, state: EvaluationState): FunctionValue =
            FunctionValue.Logical(matchesRegex(value, expression, current, root, state, anchored = false))
    }
}

internal enum class FunctionResultKind {
    VALUE,
    LOGICAL,
}

internal sealed interface FunctionValue {
    data object Nothing : FunctionValue
    data class Value(val value: JsonValue) : FunctionValue
    data class Logical(val value: Boolean) : FunctionValue
}

private class PathParser(private val input: String) {
    private var index = 0
    private var selectorCount = 0
    private var filterDepth = 0

    init {
        if (input.length > MAX_JSONPATH_CHARS) invalid()
    }

    fun parse(): JsonPath {
        if (!take('$')) invalid()
        val query = parsePathAfterOrigin(QueryOrigin.ROOT)
        skipWhitespace()
        if (index != input.length) invalid()
        return JsonPath(query)
    }

    private fun parsePathAfterOrigin(origin: QueryOrigin): PathQuery {
        val segments = ArrayList<PathSegment>()
        while (true) {
            skipWhitespace()
            when {
                take('.') -> {
                    if (take('.')) {
                        segments += parseDescendantSegment()
                    } else {
                        segments += PathSegment(descendant = false, selectors = listOf(parseDotSelector()))
                    }
                }

                peek() == '[' -> segments += parseLongHandSegment(descendant = false)
                else -> return PathQuery(origin, segments)
            }
            if (segments.size > MAX_PATH_SEGMENTS) invalid()
        }
    }

    private fun parseDescendantSegment(): PathSegment = when {
        take('*') -> PathSegment(descendant = true, selectors = listOf(PathSelector.Wildcard))
        peek() == '[' -> parseLongHandSegment(descendant = true)
        else -> PathSegment(descendant = true, selectors = listOf(PathSelector.Field(parseDotName())))
    }

    private fun parseDotSelector(): PathSelector =
        if (take('*')) PathSelector.Wildcard else PathSelector.Field(parseDotName())

    private fun parseDotName(): String {
        val start = index
        val first = peek() ?: invalid()
        if (!isNameFirst(first)) invalid()
        index += 1
        while (peek()?.let(::isNamePart) == true) index += 1
        return input.substring(start, index)
    }

    private fun parseLongHandSegment(descendant: Boolean): PathSegment {
        expect('[')
        skipWhitespace()
        if (peek() == ']') invalid()
        val selectors = ArrayList<PathSelector>()
        while (true) {
            selectors += parseSelector()
            if (++selectorCount > MAX_PATH_SELECTORS) invalid()
            skipWhitespace()
            if (take(',')) {
                skipWhitespace()
                if (peek() == ']') invalid()
                continue
            }
            expect(']')
            return PathSegment(descendant, selectors)
        }
    }

    private fun parseSelector(): PathSelector = when (peek()) {
        '*' -> {
            index += 1
            PathSelector.Wildcard
        }

        '\'', '"' -> PathSelector.Field(parseQuotedString())
        '?' -> {
            index += 1
            PathSelector.Filter(parseFilterExpression())
        }

        else -> parseIndexOrSlice()
    }

    private fun parseIndexOrSlice(): PathSelector {
        val start = parseOptionalIndex()
        skipWhitespace()
        if (!take(':')) {
            return start?.let(PathSelector::Index) ?: invalid()
        }

        skipWhitespace()
        val end = parseOptionalIndex()
        skipWhitespace()
        val step = if (take(':')) {
            skipWhitespace()
            parseOptionalIndex()
        } else {
            null
        }
        return PathSelector.Slice(start, end, step)
    }

    private fun parseOptionalIndex(): Long? {
        val current = peek() ?: return null
        if (current != '-' && current !in '0'..'9') return null
        val start = index
        val negative = take('-')
        when {
            take('0') -> {
                if (peek()?.isDigit() == true || negative) invalid()
            }

            peek()?.let { it in '1'..'9' } == true -> {
                index += 1
                while (peek()?.isDigit() == true) index += 1
            }

            else -> invalid()
        }
        val literal = input.substring(start, index)
        val parsed = literal.toLongOrNull() ?: invalid()
        if (parsed !in -MAX_JSONPATH_INTEGER..MAX_JSONPATH_INTEGER) invalid()
        return parsed
    }

    private fun parseFilterExpression(): FilterExpression {
        if (++filterDepth > MAX_FILTER_NESTING) invalid()
        return try {
            parseLogicalOr()
        } finally {
            filterDepth -= 1
        }
    }

    private fun parseLogicalOr(): FilterExpression {
        val expressions = mutableListOf(parseLogicalAnd())
        while (true) {
            skipWhitespace()
            if (!take("||")) return expressions.singleOrNull() ?: FilterExpression.AnyOf(expressions)
            skipWhitespace()
            expressions += parseLogicalAnd()
        }
    }

    private fun parseLogicalAnd(): FilterExpression {
        val expressions = mutableListOf(parseBasicExpression())
        while (true) {
            skipWhitespace()
            if (!take("&&")) return expressions.singleOrNull() ?: FilterExpression.AllOf(expressions)
            skipWhitespace()
            expressions += parseBasicExpression()
        }
    }

    private fun parseBasicExpression(): FilterExpression {
        skipWhitespace()
        if (take('!')) {
            skipWhitespace()
            return when (peek()) {
                '(' -> {
                    index += 1
                    val inner = parseNestedFilterExpression()
                    skipWhitespace()
                    expect(')')
                    FilterExpression.Negated(inner)
                }

                '@', '$' -> FilterExpression.Negated(FilterExpression.Exists(parseFilterQuery()))
                else -> {
                    val function = parseFunction()
                    if (function.resultKind != FunctionResultKind.LOGICAL) invalid()
                    FilterExpression.Negated(FilterExpression.FunctionPredicate(function))
                }
            }
        }
        if (take('(')) {
            val inner = parseNestedFilterExpression()
            skipWhitespace()
            expect(')')
            return inner
        }

        if (peek() == '@' || peek() == '$') {
            val query = parseFilterQuery()
            val operator = parseComparisonOperator()
            return if (operator == null) {
                FilterExpression.Exists(query)
            } else {
                if (!query.isSingular()) invalid()
                FilterExpression.Compare(
                    FilterComparable.SingularQuery(query),
                    operator,
                    parseComparable(),
                )
            }
        }

        if (peek()?.isAsciiLowercase() == true && !startsLiteral()) {
            val function = parseFunction()
            return when (function.resultKind) {
                FunctionResultKind.LOGICAL -> FilterExpression.FunctionPredicate(function)
                FunctionResultKind.VALUE -> {
                    val operator = parseComparisonOperator() ?: invalid()
                    FilterExpression.Compare(FilterComparable.ValueFunction(function), operator, parseComparable())
                }
            }
        }

        val left = parseComparable()
        val operator = parseComparisonOperator() ?: invalid()
        return FilterExpression.Compare(left, operator, parseComparable())
    }

    private fun parseNestedFilterExpression(): FilterExpression {
        if (++filterDepth > MAX_FILTER_NESTING) invalid()
        return try {
            parseLogicalOr()
        } finally {
            filterDepth -= 1
        }
    }

    private fun parseComparable(): FilterComparable {
        skipWhitespace()
        return when (peek()) {
            '\'', '"' -> FilterComparable.Literal(JsonValue.Text(parseQuotedString()))
            '-', in '0'..'9' -> {
                val literal = parseJsonNumber()
                val number = try {
                    JsonValue.Number(literal)
                } catch (_: IllegalArgumentException) {
                    invalid()
                }
                FilterComparable.Literal(number)
            }
            '@', '$' -> {
                val query = parseFilterQuery()
                if (!query.isSingular()) invalid()
                FilterComparable.SingularQuery(query)
            }

            else -> when {
                takeKeyword("true") -> FilterComparable.Literal(JsonValue.Bool(true))
                takeKeyword("false") -> FilterComparable.Literal(JsonValue.Bool(false))
                takeKeyword("null") -> FilterComparable.Literal(JsonValue.Null)
                peek()?.isAsciiLowercase() == true -> {
                    val function = parseFunction()
                    if (function.resultKind != FunctionResultKind.VALUE) invalid()
                    FilterComparable.ValueFunction(function)
                }

                else -> invalid()
            }
        }
    }

    private fun parseFilterQuery(): PathQuery {
        val origin = when {
            take('@') -> QueryOrigin.CURRENT
            take('$') -> QueryOrigin.ROOT
            else -> invalid()
        }
        return parsePathAfterOrigin(origin)
    }

    private fun parseFunction(): FilterFunction {
        val name = parseFunctionName()
        expect('(')
        skipWhitespace()
        return when (name) {
            "length" -> {
                val argument = parseComparable()
                skipWhitespace()
                expect(')')
                FilterFunction.Length(argument)
            }

            "count" -> {
                val query = parseFilterQuery()
                skipWhitespace()
                expect(')')
                FilterFunction.Count(query)
            }

            "value" -> {
                val query = parseFilterQuery()
                skipWhitespace()
                expect(')')
                FilterFunction.Value(query)
            }

            "match" -> {
                val value = parseComparable()
                skipWhitespace()
                expect(',')
                val expression = parseComparable()
                validateStaticRegex(expression)
                skipWhitespace()
                expect(')')
                FilterFunction.Match(value, expression)
            }

            "search" -> {
                val value = parseComparable()
                skipWhitespace()
                expect(',')
                val expression = parseComparable()
                validateStaticRegex(expression)
                skipWhitespace()
                expect(')')
                FilterFunction.Search(value, expression)
            }

            else -> invalid()
        }
    }

    /**
     * Literal regular expressions are known while a workflow is being
     * validated, so reject RE2/J-incompatible constructs before any network
     * request. A response-derived expression is checked again at evaluation
     * time by [matchesRegex].
     */
    private fun validateStaticRegex(expression: FilterComparable) {
        val literal = (expression as? FilterComparable.Literal)?.value as? JsonValue.Text ?: return
        compileCrossPlatformRegex(literal.value)
    }

    private fun parseFunctionName(): String {
        val start = index
        if (peek()?.isAsciiLowercase() != true) invalid()
        index += 1
        while (peek()?.let { it.isAsciiLowercase() || it.isDigit() || it == '_' } == true) index += 1
        return input.substring(start, index)
    }

    private fun parseComparisonOperator(): FilterOperator? {
        skipWhitespace()
        return when {
            take("==") -> FilterOperator.EQ
            take("!=") -> FilterOperator.NE
            take("<=") -> FilterOperator.LE
            take(">=") -> FilterOperator.GE
            take('<') -> FilterOperator.LT
            take('>') -> FilterOperator.GT
            else -> null
        }
    }

    private fun parseJsonNumber(): String {
        val start = index
        take('-')
        when {
            take('0') -> Unit
            peek()?.let { it in '1'..'9' } == true -> {
                index += 1
                while (peek()?.isDigit() == true) index += 1
            }

            else -> invalid()
        }
        if (take('.')) {
            if (peek()?.isDigit() != true) invalid()
            while (peek()?.isDigit() == true) index += 1
        }
        if (peek() == 'e' || peek() == 'E') {
            index += 1
            if (peek() == '+' || peek() == '-') index += 1
            if (peek()?.isDigit() != true) invalid()
            while (peek()?.isDigit() == true) index += 1
        }
        return input.substring(start, index)
    }

    private fun parseQuotedString(): String {
        val quote = peek() ?: invalid()
        if (quote != '\'' && quote != '"') invalid()
        index += 1
        return buildString {
            while (true) {
                val current = peek() ?: invalid()
                index += 1
                when (current) {
                    quote -> return@buildString
                    '\\' -> appendEscapedStringCharacter(this, quote)
                    in '\u0000'..'\u001f' -> invalid()
                    else -> append(current)
                }
            }
        }
    }

    private fun appendEscapedStringCharacter(target: StringBuilder, quote: Char) {
        val escaped = peek() ?: invalid()
        index += 1
        when (escaped) {
            'b' -> target.append('\b')
            't' -> target.append('\t')
            'n' -> target.append('\n')
            'f' -> target.append('\u000c')
            'r' -> target.append('\r')
            '/', '\\', quote -> target.append(escaped)
            'u' -> {
                val first = parseHexCodeUnit()
                when {
                    first in HIGH_SURROGATE_RANGE -> {
                        if (!take('\\') || !take('u')) invalid()
                        val second = parseHexCodeUnit()
                        if (second !in LOW_SURROGATE_RANGE) invalid()
                        target.append(String(Character.toChars(Character.toCodePoint(first.toChar(), second.toChar()))))
                    }

                    first in LOW_SURROGATE_RANGE -> invalid()
                    else -> target.append(first.toChar())
                }
            }

            else -> invalid()
        }
    }

    private fun parseHexCodeUnit(): Int {
        if (index + 4 > input.length) invalid()
        val encoded = input.substring(index, index + 4)
        val codeUnit = encoded.toIntOrNull(16) ?: invalid()
        index += 4
        return codeUnit
    }

    private fun startsLiteral(): Boolean =
        isKeywordAt("true") || isKeywordAt("false") || isKeywordAt("null")

    private fun takeKeyword(value: String): Boolean {
        if (!isKeywordAt(value)) return false
        index += value.length
        return true
    }

    private fun isKeywordAt(value: String): Boolean =
        input.regionMatches(index, value, 0, value.length) &&
            input.getOrNull(index + value.length)?.let { !it.isAsciiLowercase() && !it.isDigit() && it != '_' } != false

    private fun skipWhitespace() {
        while (peek()?.isWhitespace() == true) index += 1
    }

    private fun expect(value: Char) {
        if (!take(value)) invalid()
    }

    private fun take(value: Char): Boolean =
        (peek() == value).also { if (it) index += 1 }

    private fun take(value: String): Boolean =
        input.regionMatches(index, value, 0, value.length).also { if (it) index += value.length }

    private fun peek(): Char? = input.getOrNull(index)

    private fun invalid(): Nothing = throw JsonPathException.InvalidPath

    private companion object {
        const val MAX_PATH_SEGMENTS = 256
        const val MAX_PATH_SELECTORS = 256
        const val MAX_FILTER_NESTING = 64
        const val MAX_JSONPATH_INTEGER = 9_007_199_254_740_991L
        val HIGH_SURROGATE_RANGE: IntRange = 0xD800..0xDBFF
        val LOW_SURROGATE_RANGE: IntRange = 0xDC00..0xDFFF
    }
}

internal class EvaluationState {
    private var work = 0
    private var matches = 0

    fun consumeWork() {
        if (++work > MAX_JSONPATH_WORK) throw JsonPathException.InvalidPath
    }

    fun add(output: MutableList<JsonValue>, value: JsonValue) {
        if (++matches > MAX_JSONPATH_MATCHES) throw JsonPathException.InvalidPath
        output += value
    }
}

private fun directChildren(value: JsonValue): List<JsonValue> = when (value) {
    is JsonValue.Array -> value.values
    is JsonValue.Object -> value.values.values.toList()
    else -> emptyList()
}

private fun normalizeIndex(index: Long, size: Int): Int {
    val normalized = if (index < 0L) size.toLong() + index else index
    return if (normalized in 0 until size.toLong()) normalized.toInt() else -1
}

private fun sliceIndexes(size: Int, start: Long?, end: Long?, step: Long?): List<Int> {
    val stride = step ?: 1L
    if (stride == 0L || size == 0) return emptyList()
    val values = ArrayList<Int>()
    val length = size.toLong()
    if (stride > 0L) {
        var current = normalizeSliceBound(start ?: 0L, length, lower = 0L, upper = length)
        val limit = normalizeSliceBound(end ?: length, length, lower = 0L, upper = length)
        while (current < limit) {
            values += current.toInt()
            current = safeAdd(current, stride) ?: break
        }
    } else {
        val defaultStart = length - 1L
        // The omitted reverse bound is a sentinel below index zero. It must
        // differ from an explicit -1, which normalizes to the final element.
        val defaultEnd = -length - 1L
        var current = normalizeSliceBound(start ?: defaultStart, length, lower = -1L, upper = length - 1L)
        val limit = normalizeSliceBound(end ?: defaultEnd, length, lower = -1L, upper = length - 1L)
        while (limit < current) {
            if (current in 0 until length) values += current.toInt()
            current = safeAdd(current, stride) ?: break
        }
    }
    return values
}

private fun normalizeSliceBound(value: Long, length: Long, lower: Long, upper: Long): Long {
    val normalized = if (value < 0L) length + value else value
    return normalized.coerceIn(lower, upper)
}

private fun safeAdd(left: Long, right: Long): Long? =
    try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        null
    }

private fun optionalEquals(left: OptionalJsonValue, right: OptionalJsonValue): Boolean = when {
    left is OptionalJsonValue.Missing && right is OptionalJsonValue.Missing -> true
    left is OptionalJsonValue.Present && right is OptionalJsonValue.Present -> jsonEquals(left.value, right.value)
    else -> false
}

private fun sameType(left: OptionalJsonValue, right: OptionalJsonValue): Boolean =
    left is OptionalJsonValue.Present &&
        right is OptionalJsonValue.Present &&
        sameJsonType(left.value, right.value)

private fun optionalLessThan(left: OptionalJsonValue, right: OptionalJsonValue): Boolean {
    if (left !is OptionalJsonValue.Present || right !is OptionalJsonValue.Present) return false
    return when {
        left.value is JsonValue.Number && right.value is JsonValue.Number ->
            left.value.serdeDouble() < right.value.serdeDouble()

        left.value is JsonValue.Text && right.value is JsonValue.Text ->
            compareUtf8(left.value.value, right.value.value) < 0

        else -> false
    }
}

private fun sameJsonType(left: JsonValue, right: JsonValue): Boolean = when (left) {
    JsonValue.Null -> right === JsonValue.Null
    is JsonValue.Bool -> right is JsonValue.Bool
    is JsonValue.Number -> right is JsonValue.Number
    is JsonValue.Text -> right is JsonValue.Text
    is JsonValue.Array -> right is JsonValue.Array
    is JsonValue.Object -> right is JsonValue.Object
}

private fun jsonEquals(left: JsonValue, right: JsonValue): Boolean = when {
    left is JsonValue.Number && right is JsonValue.Number -> left.serdeDouble() == right.serdeDouble()
    left is JsonValue.Array && right is JsonValue.Array ->
        left.values.size == right.values.size && left.values.indices.all { jsonEquals(left.values[it], right.values[it]) }

    left is JsonValue.Object && right is JsonValue.Object ->
        left.values.size == right.values.size &&
            left.values.all { (key, value) -> right.values[key]?.let { jsonEquals(value, it) } == true }

    else -> left == right
}

private fun compareUtf8(left: String, right: String): Int {
    val leftBytes = left.toByteArray(StandardCharsets.UTF_8)
    val rightBytes = right.toByteArray(StandardCharsets.UTF_8)
    val length = minOf(leftBytes.size, rightBytes.size)
    for (byteIndex in 0 until length) {
        val compared = (leftBytes[byteIndex].toInt() and 0xff).compareTo(rightBytes[byteIndex].toInt() and 0xff)
        if (compared != 0) return compared
    }
    return leftBytes.size.compareTo(rightBytes.size)
}

private fun matchesRegex(
    value: FilterComparable,
    expression: FilterComparable,
    current: JsonValue,
    root: JsonValue,
    state: EvaluationState,
    anchored: Boolean,
): Boolean {
    val source = (value.evaluate(current, root, state) as? OptionalJsonValue.Present)?.value as? JsonValue.Text
        ?: return false
    val pattern = (expression.evaluate(current, root, state) as? OptionalJsonValue.Present)?.value as? JsonValue.Text
        ?: return false
    if (source.value.length > MAX_REGEX_INPUT_CHARS) return false
    val matcher = compileCrossPlatformRegex(pattern.value).matcher(source.value)
    return if (anchored) matcher.matches() else matcher.find()
}

/**
 * JSONPath's match/search functions use RE2/J rather than Java's backtracking
 * engine. This keeps Android's accepted language near the Windows Rust regex
 * engine and, critically, guarantees linear-time evaluation for a workflow
 * regular expression.
 */
private fun compileCrossPlatformRegex(expression: String): Re2Pattern {
    if (expression.length > MAX_REGEX_CHARS) throw JsonPathException.InvalidRegex
    return try {
        Re2Pattern.compile(expression)
    } catch (_: Re2PatternSyntaxException) {
        throw JsonPathException.InvalidRegex
    }
}

private fun isNameFirst(value: Char): Boolean =
    value.isAsciiLowercase() || value in 'A'..'Z' || value == '_' || value >= '\u0080'

private fun isNamePart(value: Char): Boolean = isNameFirst(value) || value.isDigit()

private fun Char.isAsciiLowercase(): Boolean = this in 'a'..'z'

private const val MAX_JSONPATH_CHARS = 32 * 1024
private const val MAX_JSONPATH_RESPONSE_BYTES = 2 * 1024 * 1024
private const val MAX_JSONPATH_WORK = 262_144
private const val MAX_JSONPATH_MATCHES = 131_072
private const val MAX_REGEX_CHARS = 512
private const val MAX_REGEX_INPUT_CHARS = 16 * 1024
