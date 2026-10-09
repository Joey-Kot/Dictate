package com.joeykot.dictate.advanced_audio

import java.util.ArrayDeque

/**
 * The intentionally small template language shared by all workflow stages.
 * It has no expressions, filesystem access, environment access, or control
 * flow: every placeholder resolves one scalar value from a fixed namespace.
 */
class Template private constructor(
    private val parts: List<TemplatePart>,
) {
    val placeholders: List<Placeholder> = parts.mapNotNull { (it as? TemplatePart.PlaceholderPart)?.placeholder }

    fun render(context: TemplateContext): String = buildString {
        parts.forEach { part ->
            when (part) {
                is TemplatePart.Literal -> append(part.value)
                is TemplatePart.PlaceholderPart -> append(context.valueFor(part.placeholder))
            }
        }
    }

    companion object {
        fun parse(input: String): Template {
            val parts = mutableListOf<TemplatePart>()
            var cursor = 0
            while (true) {
                val open = input.indexOf("{{", cursor)
                if (open < 0) break
                val unexpectedClose = input.indexOf("}}", cursor)
                if (unexpectedClose >= 0 && unexpectedClose < open) {
                    throw TemplateException.UnexpectedClose(byteOffset(input, unexpectedClose))
                }
                if (open > cursor) parts += TemplatePart.Literal(input.substring(cursor, open))
                val contentStart = open + 2
                val end = input.indexOf("}}", contentStart)
                if (end < 0) throw TemplateException.Unterminated(byteOffset(input, open))
                parts += TemplatePart.PlaceholderPart(parsePlaceholder(input.substring(contentStart, end), byteOffset(input, open)))
                cursor = end + 2
            }
            val unexpectedClose = input.indexOf("}}", cursor)
            if (unexpectedClose >= 0) throw TemplateException.UnexpectedClose(byteOffset(input, unexpectedClose))
            if (cursor < input.length) parts += TemplatePart.Literal(input.substring(cursor))
            return Template(parts)
        }

        private fun byteOffset(value: String, index: Int): Int = value.substring(0, index).toByteArray(Charsets.UTF_8).size
    }
}

private sealed interface TemplatePart {
    data class Literal(val value: String) : TemplatePart
    data class PlaceholderPart(val placeholder: Placeholder) : TemplatePart
}

sealed interface Placeholder {
    val namespace: String
    val key: String

    data class Variable(val id: String) : Placeholder {
        override val namespace: String = "var"
        override val key: String = id
    }

    data class Secret(val id: String) : Placeholder {
        override val namespace: String = "secret"
        override val key: String = id
    }

    data class Capture(val id: String) : Placeholder {
        override val namespace: String = "capture"
        override val key: String = id
    }

    data class Audio(val type: AudioPlaceholder) : Placeholder {
        override val namespace: String = "audio"
        override val key: String = type.wireName
    }

    data class Runtime(val type: RuntimePlaceholder) : Placeholder {
        override val namespace: String = "runtime"
        override val key: String = type.wireName
    }

    fun display(): String = "{{$namespace:$key}}"
}

enum class AudioPlaceholder(val wireName: String) {
    FILENAME("filename"),
    MIME("mime"),
    SIZE("size"),
    BASE64("base64"),
    DATA_URI("data_uri"),
    PUBLIC_URL("public_url"),
    CLOUD_URI("cloud_uri"),
    CHUNK_BASE64("chunk_base64");

    companion object {
        fun fromWire(value: String): AudioPlaceholder? = entries.firstOrNull { it.wireName == value }
    }
}

enum class RuntimePlaceholder(val wireName: String) {
    UUID("uuid"),
    UNIX_SECONDS("unix_seconds"),
    UNIX_MILLIS("unix_millis");

    companion object {
        fun fromWire(value: String): RuntimePlaceholder? = entries.firstOrNull { it.wireName == value }
    }
}

data class AudioTemplateValues(
    val filename: String? = null,
    val mime: String? = null,
    val size: String? = null,
    val base64: String? = null,
    val dataUri: String? = null,
    val publicUrl: String? = null,
    val cloudUri: String? = null,
    val chunkBase64: String? = null,
) {
    fun valueFor(placeholder: AudioPlaceholder): String? = when (placeholder) {
        AudioPlaceholder.FILENAME -> filename
        AudioPlaceholder.MIME -> mime
        AudioPlaceholder.SIZE -> size
        AudioPlaceholder.BASE64 -> base64
        AudioPlaceholder.DATA_URI -> dataUri
        AudioPlaceholder.PUBLIC_URL -> publicUrl
        AudioPlaceholder.CLOUD_URI -> cloudUri
        AudioPlaceholder.CHUNK_BASE64 -> chunkBase64
    }
}

data class RuntimeTemplateValues(
    val uuid: String? = null,
    val unixSeconds: String? = null,
    val unixMillis: String? = null,
) {
    fun valueFor(placeholder: RuntimePlaceholder): String? = when (placeholder) {
        RuntimePlaceholder.UUID -> uuid
        RuntimePlaceholder.UNIX_SECONDS -> unixSeconds
        RuntimePlaceholder.UNIX_MILLIS -> unixMillis
    }
}

class TemplateContext(
    val values: Map<String, String>,
    val secrets: Map<String, String>,
    val captures: Map<String, String>,
    val audio: AudioTemplateValues,
    val runtime: RuntimeTemplateValues,
) {
    internal fun valueFor(placeholder: Placeholder): String = when (placeholder) {
        is Placeholder.Variable -> values[placeholder.id]
        is Placeholder.Secret -> secrets[placeholder.id]
        is Placeholder.Capture -> captures[placeholder.id]
        is Placeholder.Audio -> audio.valueFor(placeholder.type)
        is Placeholder.Runtime -> runtime.valueFor(placeholder.type)
    } ?: throw TemplateException.MissingValue(placeholder)
}

sealed class TemplateException(message: String) : IllegalArgumentException(message) {
    data class Unterminated(val position: Int) : TemplateException("unterminated template placeholder at byte $position")
    data class UnexpectedClose(val position: Int) : TemplateException("unexpected closing template delimiter at byte $position")
    data class Empty(val position: Int) : TemplateException("empty template placeholder at byte $position")
    data class InvalidPlaceholder(val placeholder: String) : TemplateException("invalid template placeholder '$placeholder'")
    data class UnknownNamespace(val namespace: String) : TemplateException("unknown template namespace '$namespace'")
    data class UnknownFixedPlaceholder(val namespace: String, val key: String) : TemplateException(
        "unknown $namespace placeholder '$key'",
    )

    data class MissingValue(val placeholder: Placeholder) : TemplateException("missing value for ${placeholder.display()}")
}

fun isWorkflowIdentifier(value: String): Boolean {
    if (value.isEmpty()) return false
    val first = value.first()
    return (first in 'a'..'z' || first in 'A'..'Z' || first == '_') &&
        value.drop(1).all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }
}

private fun parsePlaceholder(input: String, position: Int): Placeholder {
    if (input.isEmpty()) throw TemplateException.Empty(position)
    if (input.trim() != input || input.contains('{') || input.contains('}')) {
        throw TemplateException.InvalidPlaceholder(input)
    }
    val separator = input.indexOf(':')
    if (separator <= 0 || separator != input.lastIndexOf(':') || separator == input.lastIndex) {
        throw TemplateException.InvalidPlaceholder(input)
    }
    val namespace = input.substring(0, separator)
    val key = input.substring(separator + 1)
    return when (namespace) {
        "var" -> if (isWorkflowIdentifier(key)) Placeholder.Variable(key) else TemplateException.InvalidPlaceholder(input).throwIt()
        "secret" -> if (isWorkflowIdentifier(key)) Placeholder.Secret(key) else TemplateException.InvalidPlaceholder(input).throwIt()
        "capture" -> if (isWorkflowIdentifier(key)) Placeholder.Capture(key) else TemplateException.InvalidPlaceholder(input).throwIt()
        "audio" -> AudioPlaceholder.fromWire(key)?.let(Placeholder::Audio)
            ?: throw TemplateException.UnknownFixedPlaceholder(namespace, key)

        "runtime" -> RuntimePlaceholder.fromWire(key)?.let(Placeholder::Runtime)
            ?: throw TemplateException.UnknownFixedPlaceholder(namespace, key)

        else -> throw TemplateException.UnknownNamespace(namespace)
    }
}

private fun TemplateException.throwIt(): Nothing = throw this

/** Shared v2 JSON-leaf renderer used by HTTP JSON bodies and realtime JSON messages. */
object TypedTemplateRenderer {
    fun renderText(
        value: String,
        context: TemplateContext,
        parameters: List<ParameterDefinition>,
        schemaVersion: Int,
    ): String {
        val template = Template.parse(value)
        rejectStructuredTextUse(template, parameters, schemaVersion)
        return template.render(context)
    }

    fun renderJson(
        value: JsonValue,
        context: TemplateContext,
        parameters: List<ParameterDefinition>,
        schemaVersion: Int,
    ): JsonValue {
        // Workflow JSON is normally parser-bounded, but renderers can also be
        // called with a programmatically constructed tree.  Keep traversal on
        // the heap so a deeply nested, otherwise valid tree cannot consume the
        // Kotlin call stack before any request is sent.
        val containers = ArrayDeque<JsonRenderFrame>()
        var current = value

        while (true) {
            val node = current
            val completed: JsonValue = when (node) {
                JsonValue.Null,
                is JsonValue.Bool,
                is JsonValue.Number,
                -> node

                is JsonValue.Text -> renderJsonText(node.value, context, parameters, schemaVersion)
                is JsonValue.Array -> {
                    val frame = JsonArrayRenderFrame(node.values)
                    val firstChild = frame.takeNext()
                    if (firstChild != null) {
                        containers.addLast(frame)
                        current = firstChild
                        continue
                    }
                    JsonValue.Array(ArrayList())
                }

                is JsonValue.Object -> {
                    val frame = JsonObjectRenderFrame(node.values)
                    val firstChild = frame.takeNext()
                    if (firstChild != null) {
                        containers.addLast(frame)
                        current = firstChild
                        continue
                    }
                    JsonValue.Object(LinkedHashMap())
                }
            }

            var rendered = completed
            while (true) {
                val parent = containers.peekLast() ?: return rendered
                parent.append(rendered)
                val nextChild = parent.takeNext()
                if (nextChild != null) {
                    current = nextChild
                    break
                }
                containers.removeLast()
                rendered = parent.complete()
            }
        }
    }

    /** One pending JSON container in the iterative post-order renderer. */
    private sealed interface JsonRenderFrame {
        /** Returns the next original child, or null after all children have rendered. */
        fun takeNext(): JsonValue?

        /** Records one already-rendered child in its original order. */
        fun append(value: JsonValue)

        /** Builds the rendered container after every child has been appended. */
        fun complete(): JsonValue
    }

    private class JsonArrayRenderFrame(
        private val source: List<JsonValue>,
    ) : JsonRenderFrame {
        private val rendered = ArrayList<JsonValue>(source.size)
        private var index = 0

        override fun takeNext(): JsonValue? = source.getOrNull(index)

        override fun append(value: JsonValue) {
            rendered += value
            index += 1
        }

        override fun complete(): JsonValue = JsonValue.Array(rendered)
    }

    private class JsonObjectRenderFrame(
        source: Map<String, JsonValue>,
    ) : JsonRenderFrame {
        private val entries = source.entries.iterator()
        private val rendered = LinkedHashMap<String, JsonValue>(source.size)
        private var pendingKey: String? = null

        override fun takeNext(): JsonValue? {
            check(pendingKey == null) { "JSON object renderer advanced before its previous child completed" }
            if (!entries.hasNext()) return null
            val entry = entries.next()
            pendingKey = entry.key
            return entry.value
        }

        override fun append(value: JsonValue) {
            val key = checkNotNull(pendingKey) { "JSON object renderer completed an unexpected child" }
            rendered[key] = value
            pendingKey = null
        }

        override fun complete(): JsonValue = JsonValue.Object(rendered)
    }

    private fun renderJsonText(
        value: String,
        context: TemplateContext,
        parameters: List<ParameterDefinition>,
        schemaVersion: Int,
    ): JsonValue {
        val template = Template.parse(value)
        val id = wholeParameterReference(value)
        val parameter = id?.let { candidate -> parameters.firstOrNull { it.id == candidate } }
        if (parameter != null) {
            return try {
                parameter.parseValue(schemaVersion, template.render(context))
            } catch (error: ParameterValueException) {
                throw TypedTemplateRenderException.InvalidParameterValue(parameter.id, error.message ?: "invalid value")
            }
        }
        rejectStructuredTextUse(template, parameters, schemaVersion)
        return JsonValue.Text(template.render(context))
    }

    private fun rejectStructuredTextUse(
        template: Template,
        parameters: List<ParameterDefinition>,
        schemaVersion: Int,
    ) {
        val parameter = template.placeholders.filterIsInstance<Placeholder.Variable>().firstNotNullOfOrNull { placeholder ->
            parameters.firstOrNull { it.id == placeholder.id && it.isStructured(schemaVersion) }
        }
        if (parameter != null) throw TypedTemplateRenderException.StructuredParameterRequiresJsonLeaf(parameter.id)
    }

    private fun wholeParameterReference(value: String): String? = value
        .removePrefix("{{var:")
        .takeIf { value.startsWith("{{var:") }
        ?.removeSuffix("}}")
        ?.takeIf { value.endsWith("}}") && isWorkflowIdentifier(it) }
}

sealed class TypedTemplateRenderException(message: String) : IllegalArgumentException(message) {
    data class StructuredParameterRequiresJsonLeaf(val id: String) : TypedTemplateRenderException(
        "structured parameter '$id' may only be used as a complete JSON leaf",
    )

    data class InvalidParameterValue(val id: String, val reason: String) : TypedTemplateRenderException(
        "invalid value for parameter '$id': $reason",
    )
}
