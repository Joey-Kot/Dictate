package com.joeykot.dictate.advanced_audio

import java.math.BigInteger

const val LEGACY_WORKFLOW_SCHEMA_VERSION = 1
const val CURRENT_WORKFLOW_SCHEMA_VERSION = 2

fun isSupportedWorkflowSchemaVersion(value: Int): Boolean =
    value == LEGACY_WORKFLOW_SCHEMA_VERSION || value == CURRENT_WORKFLOW_SCHEMA_VERSION

/** A portable, versioned Advanced Audio API workflow. */
data class AdvancedAudioWorkflow(
    val schemaVersion: Int,
    val name: String,
    val parameters: List<ParameterDefinition> = emptyList(),
    val secrets: List<SecretDefinition> = emptyList(),
    val audio: AudioSpec,
    val recognition: Recognition,
) {
    fun parameter(id: String): ParameterDefinition? = parameters.firstOrNull { it.id == id }
}

data class ParameterDefinition(
    val id: String,
    val label: String,
    val required: Boolean = false,
    val defaultValue: String? = null,
    val description: String? = null,
    val parameterType: ParameterType? = null,
    val options: List<ParameterOption> = emptyList(),
    val visibleWhen: VisibilityCondition? = null,
) {
    fun effectiveType(schemaVersion: Int): ParameterType = when (schemaVersion) {
        LEGACY_WORKFLOW_SCHEMA_VERSION -> ParameterType.TEXT
        CURRENT_WORKFLOW_SCHEMA_VERSION -> parameterType
            ?: throw ParameterValueException.MissingType(schemaVersion)

        else -> throw ParameterValueException.UnsupportedSchemaVersion(schemaVersion)
    }

    /**
     * Converts the persisted string only when a variable occupies a complete
     * JSON leaf. String contexts intentionally continue to use the original
     * stored string.
     */
    fun parseValue(schemaVersion: Int, stored: String): JsonValue = when (effectiveType(schemaVersion)) {
        ParameterType.TEXT -> JsonValue.Text(stored)
        ParameterType.INTEGER -> parseNumber(stored, integer = true)
        ParameterType.NUMBER -> parseNumber(stored, integer = false)
        ParameterType.BOOLEAN -> when (stored) {
            "true" -> JsonValue.Bool(true)
            "false" -> JsonValue.Bool(false)
            else -> throw ParameterValueException.InvalidBoolean
        }

        ParameterType.SELECT -> {
            if (options.any { it.value == stored }) JsonValue.Text(stored)
            else throw ParameterValueException.InvalidSelectOption
        }

        ParameterType.MULTI_SELECT -> parseMultiSelect(stored)
        ParameterType.JSON_OBJECT -> parseRoot(stored, ParameterValueException.InvalidJsonObject) as? JsonValue.Object
            ?: throw ParameterValueException.InvalidJsonObject

        ParameterType.JSON_ARRAY -> parseRoot(stored, ParameterValueException.InvalidJsonArray) as? JsonValue.Array
            ?: throw ParameterValueException.InvalidJsonArray
    }

    fun isStructured(schemaVersion: Int): Boolean = when (effectiveType(schemaVersion)) {
        ParameterType.MULTI_SELECT,
        ParameterType.JSON_OBJECT,
        ParameterType.JSON_ARRAY,
        -> true

        else -> false
    }

    private fun parseNumber(stored: String, integer: Boolean): JsonValue.Number {
        val invalid = if (integer) ParameterValueException.InvalidInteger else ParameterValueException.InvalidNumber
        val value = parseRoot(stored, invalid) as? JsonValue.Number ?: throw invalid
        if (integer) {
            // serde_json stores the JSON literal -0 as a floating-point
            // number, so it is not accepted by the Windows integer parameter
            // path even though it is valid JSON syntax.
            if (!value.isIntegerLiteral() || value.literal == "-0") throw invalid
            val number = try {
                BigInteger(value.literal)
            } catch (_: NumberFormatException) {
                throw invalid
            }
            if (number < MIN_SIGNED_64 || number > MAX_UNSIGNED_64) throw invalid
        }
        return value
    }

    private fun parseMultiSelect(stored: String): JsonValue.Array {
        val values = parseRoot(stored, ParameterValueException.InvalidMultiSelect) as? JsonValue.Array
            ?: throw ParameterValueException.InvalidMultiSelect
        val seen = mutableSetOf<String>()
        values.values.forEach { value ->
            val item = (value as? JsonValue.Text)?.value
                ?.takeIf { it.isNotEmpty() }
                ?: throw ParameterValueException.InvalidMultiSelectItem
            if (options.none { it.value == item }) throw ParameterValueException.InvalidMultiSelectOption
            if (!seen.add(item)) throw ParameterValueException.DuplicateMultiSelectOption
        }
        return values
    }

    private fun parseRoot(stored: String, failure: ParameterValueException): JsonValue = try {
        JsonValueCodec.parse(stored)
    } catch (_: IllegalArgumentException) {
        throw failure
    }

    private companion object {
        val MIN_SIGNED_64: BigInteger = BigInteger.valueOf(Long.MIN_VALUE)
        val MAX_UNSIGNED_64: BigInteger = BigInteger("18446744073709551615")
    }
}

enum class ParameterType(val wireName: String) {
    TEXT("text"),
    INTEGER("integer"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    SELECT("select"),
    MULTI_SELECT("multi_select"),
    JSON_OBJECT("json_object"),
    JSON_ARRAY("json_array");

    companion object {
        fun fromWire(value: String): ParameterType? = entries.firstOrNull { it.wireName == value }
    }
}

data class ParameterOption(
    val value: String,
    val label: String,
)

data class VisibilityCondition(
    val parameter: String,
    val equals: String? = null,
    val oneOf: List<String> = emptyList(),
)

sealed class ParameterValueException(message: String) : IllegalArgumentException(message) {
    data class UnsupportedSchemaVersion(val schemaVersion: Int) : ParameterValueException(
        "cannot parse a parameter value for unsupported workflow schema version $schemaVersion",
    )

    data class MissingType(val schemaVersion: Int) : ParameterValueException(
        "parameter type is required for workflow schema version $schemaVersion",
    )

    data object InvalidInteger : ParameterValueException("must be a JSON integer")
    data object InvalidNumber : ParameterValueException("must be a JSON number")
    data object InvalidBoolean : ParameterValueException("must be exactly true or false")
    data object InvalidSelectOption : ParameterValueException("must be one of the declared select options")
    data object InvalidMultiSelect : ParameterValueException("must be a JSON array of strings")
    data object InvalidMultiSelectItem : ParameterValueException("must contain only nonempty string option values")
    data object InvalidMultiSelectOption : ParameterValueException(
        "contains a value that is not a declared multi_select option",
    )

    data object DuplicateMultiSelectOption : ParameterValueException(
        "must not contain the same option more than once",
    )

    data object InvalidJsonObject : ParameterValueException("must be a JSON object")
    data object InvalidJsonArray : ParameterValueException("must be a JSON array")
}

data class SecretDefinition(
    val id: String,
    val label: String,
    val required: Boolean = false,
    val description: String? = null,
)

data class AudioSpec(
    val delivery: AudioDeliveryType,
    val mime: String? = null,
)

enum class AudioDeliveryType(val wireName: String) {
    MULTIPART_FILE("multipart_file"),
    RAW_AUDIO("raw_audio"),
    BASE64("base64"),
    DATA_URI("data_uri"),
    PUBLIC_HTTPS_URL("public_https_url"),
    CLOUD_URI("cloud_uri"),
    PROVIDER_UPLOAD("provider_upload"),
    REALTIME_CHUNKS("realtime_chunks");

    companion object {
        fun fromWire(value: String): AudioDeliveryType? = entries.firstOrNull { it.wireName == value }
    }
}

sealed interface Recognition {
    val modeName: String
}

data class RequestRecognition(
    val request: HttpStage,
    val finalText: ResponseExtractor,
) : Recognition {
    override val modeName: String = "request"
}

data class RequestStreamRecognition(
    val request: HttpStage,
    val stream: StreamResponse,
) : Recognition {
    override val modeName: String = "request_stream"
}

data class AsyncPollRecognition(
    val prepare: HttpStage? = null,
    val submit: HttpStage,
    val poll: PollStage? = null,
    val resultSteps: List<HttpStage> = emptyList(),
    val finalText: ResponseExtractor,
) : Recognition {
    override val modeName: String = "async_poll"
}

data class RealtimeSessionRecognition(
    val realtime: RealtimeWorkflow,
) : Recognition {
    override val modeName: String = "realtime_session"
}

data class HttpStage(
    val method: HttpMethod,
    val url: String,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: HttpBody = HttpBody.None,
    val acceptedStatuses: List<Int> = listOf(200),
    val signer: SignerConfig = SignerConfig.None,
    val captures: List<Capture> = emptyList(),
)

enum class HttpMethod(val wireName: String) {
    GET("GET"),
    POST("POST"),
    PUT("PUT"),
    PATCH("PATCH"),
    DELETE("DELETE");

    val isReadOnly: Boolean get() = this == GET

    companion object {
        fun fromWire(value: String): HttpMethod? = entries.firstOrNull { it.wireName == value }
    }
}

sealed interface HttpBody {
    data object None : HttpBody
    data class Json(val value: JsonValue) : HttpBody
    data class FormUrlencoded(val fields: Map<String, String>) : HttpBody
    data class Multipart(val fields: List<MultipartField>) : HttpBody
    data object RawAudio : HttpBody
    data class RawBytes(val value: String) : HttpBody
}

data class MultipartField(
    val name: String,
    val value: MultipartValue,
)

sealed interface MultipartValue {
    data class Text(val value: String) : MultipartValue
    data object AudioFile : MultipartValue
    data class Bytes(val value: String) : MultipartValue
}

sealed interface ResponseExtractor {
    data class JsonPath(val path: String) : ResponseExtractor
    data class Header(val name: String) : ResponseExtractor
    data object PlainBody : ResponseExtractor
    data object Status : ResponseExtractor
}

data class Capture(
    val id: String,
    val from: ResponseExtractor,
    val sensitive: Boolean = false,
)

data class StreamResponse(
    val format: StreamFormat,
    val rules: List<StreamRule> = emptyList(),
)

enum class StreamFormat(val wireName: String) {
    SSE("sse"),
    NDJSON("ndjson"),
    JSON_CHUNKS("json_chunks");

    companion object {
        fun fromWire(value: String): StreamFormat? = entries.firstOrNull { it.wireName == value }
    }
}

data class StreamRule(
    val event: String? = null,
    val path: String? = null,
    val action: StreamAction,
    val equals: String? = null,
)

enum class StreamAction(val wireName: String) {
    IGNORE("ignore"),
    APPEND_DELTA("append_delta"),
    REPLACE_PARTIAL("replace_partial"),
    COMMIT_SEGMENT("commit_segment"),
    SET_FINAL_TEXT("set_final_text"),
    COMPLETE("complete"),
    FAIL("fail");

    companion object {
        fun fromWire(value: String): StreamAction? = entries.firstOrNull { it.wireName == value }
    }
}

data class PollStage(
    val request: HttpStage,
    val intervalMs: Long,
    val timeoutMs: Long,
    val pending: List<PollCondition> = emptyList(),
    val success: List<PollCondition> = emptyList(),
    val failure: List<PollCondition> = emptyList(),
)

data class PollCondition(
    val from: ResponseExtractor,
    val operator: PollOperator,
    val value: String? = null,
    val values: List<String> = emptyList(),
)

enum class PollOperator(val wireName: String) {
    EQ("eq"),
    NE("ne"),
    EXISTS("exists"),
    NOT_EXISTS("not_exists"),
    IS_TRUE("is_true"),
    IS_FALSE("is_false"),
    IN("in");

    companion object {
        fun fromWire(value: String): PollOperator? = entries.firstOrNull { it.wireName == value }
    }
}

sealed interface SignerConfig {
    data object None : SignerConfig

    data class AwsSigv4(
        val region: String,
        val service: String,
        val accessKeySecret: String,
        val secretKeySecret: String,
        val sessionTokenSecret: String? = null,
    ) : SignerConfig

    data class TencentTc3(
        val service: String,
        val secretIdSecret: String,
        val secretKeySecret: String,
    ) : SignerConfig
}

data class RealtimeWorkflow(
    val transport: RealtimeTransport,
    val connect: RealtimeConnect,
    val initialMessages: List<RealtimeMessage> = emptyList(),
    val audioStream: RealtimeAudioStream,
    val audioMessage: RealtimeAudioMessage,
    val receiveRules: List<StreamRule> = emptyList(),
    val finishMessages: List<RealtimeMessage> = emptyList(),
    val completion: RealtimeCompletion,
    val pauseBehavior: PauseBehavior = PauseBehavior.RESTART_SESSION,
    val finalizationTimeoutMs: Long = 15_000L,
)

enum class RealtimeTransport(val wireName: String) {
    WEBSOCKET("websocket"),
    GRPC("grpc"),
    HTTP2_EVENT_STREAM("http2_event_stream");

    companion object {
        fun fromWire(value: String): RealtimeTransport? = entries.firstOrNull { it.wireName == value }
    }
}

data class RealtimeConnect(
    val url: String,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val signer: SignerConfig = SignerConfig.None,
    val subprotocol: String? = null,
)

sealed interface RealtimeMessage {
    data class Json(val value: JsonValue) : RealtimeMessage
    data class Text(val value: String) : RealtimeMessage
    data class Binary(val value: String) : RealtimeMessage
}

data class RealtimeAudioStream(
    val codec: String,
    val sampleRate: Int,
    val channels: Int,
    val chunkDurationMs: Int,
    val pacing: RealtimePacing = RealtimePacing.REALTIME,
)

enum class RealtimePacing(val wireName: String) {
    REALTIME("realtime"),
    UNBOUNDED("unbounded");

    companion object {
        fun fromWire(value: String): RealtimePacing? = entries.firstOrNull { it.wireName == value }
    }
}

sealed interface RealtimeAudioMessage {
    data object Binary : RealtimeAudioMessage
    data class Text(val value: String) : RealtimeAudioMessage
    data class Json(val value: JsonValue) : RealtimeAudioMessage
}

data class RealtimeCompletion(
    val event: String? = null,
    val path: String? = null,
    val equals: String? = null,
)

enum class PauseBehavior(val wireName: String) {
    RESTART_SESSION("restart_session"),
    KEEP_SESSION("keep_session");

    companion object {
        fun fromWire(value: String): PauseBehavior? = entries.firstOrNull { it.wireName == value }
    }
}
