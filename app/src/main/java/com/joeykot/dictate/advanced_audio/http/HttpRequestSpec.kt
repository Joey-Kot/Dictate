package com.joeykot.dictate.advanced_audio.http

import com.joeykot.dictate.advanced_audio.auth.HttpRequestSigner
import java.io.Closeable
import java.io.File
import java.io.InputStream

/**
 * Bounded HTTP primitives used exclusively by the declarative Advanced Audio
 * API.  They deliberately contain rendered request values rather than schema
 * objects so the transport cannot invent workflow semantics.
 */
enum class AdvancedHttpMethod(val wireName: String) {
    GET("GET"),
    POST("POST"),
    PUT("PUT"),
    PATCH("PATCH"),
    DELETE("DELETE"),
    ;

    internal fun requiresRequestBodyForOkHttp(): Boolean = when (this) {
        POST, PUT, PATCH -> true
        GET, DELETE -> false
    }
}

data class HttpQueryParameter(
    val name: String,
    val value: String,
)

data class HttpHeader(
    val name: String,
    val value: String,
)

/**
 * A prepared, local audio file.  File contents are intentionally streamed for
 * multipart and raw-audio delivery; only a caller explicitly choosing a
 * materialized delivery form may read it into memory.
 */
data class PreparedAudioUpload(
    val file: File,
    val filename: String = file.name.ifBlank { "audio" },
    val mimeType: String,
    val sizeBytes: Long = file.length(),
) {
    init {
        require(filename.isNotBlank()) { "Audio filename cannot be empty" }
        require(mimeType.isNotBlank()) { "Audio MIME type cannot be empty" }
    }
}

sealed interface MultipartValue {
    data class Text(val value: String) : MultipartValue
    data class Bytes(val value: ByteArray, val contentType: String? = null) : MultipartValue
    data object AudioFile : MultipartValue
}

data class MultipartPart(
    val name: String,
    val value: MultipartValue,
)

/**
 * Body values are already rendered by the workflow layer.  This is important:
 * the transport owns wire encoding and streaming, while template rendering and
 * v2 typed JSON-leaf conversion remain in the schema/execution layer.
 */
sealed interface HttpBodySpec {
    data object None : HttpBodySpec
    data class Json(val value: String) : HttpBodySpec
    data class FormUrlEncoded(val fields: List<HttpQueryParameter>) : HttpBodySpec
    data class Multipart(
        val fields: List<MultipartPart>,
        val audio: PreparedAudioUpload? = null,
    ) : HttpBodySpec

    data class RawAudio(val audio: PreparedAudioUpload) : HttpBodySpec
    data class RawBytes(
        val value: ByteArray,
        val contentType: String? = null,
    ) : HttpBodySpec
}

/**
 * A fully rendered request ready for transport.  `acceptedStatuses` is kept
 * with the request for callers that execute one HTTP stage at a time; the
 * transport still returns every response so poll/stream code controls its own
 * protocol decisions.
 */
data class HttpRequestSpec(
    val method: AdvancedHttpMethod,
    val url: String,
    val query: List<HttpQueryParameter> = emptyList(),
    val headers: List<HttpHeader> = emptyList(),
    val body: HttpBodySpec = HttpBodySpec.None,
    val acceptedStatuses: Set<Int> = setOf(200),
    val signer: HttpRequestSigner? = null,
    /**
     * Reserved for the built-in remote-storage publisher.  Declarative
     * workflow HTTP stages must leave this null: their streaming multipart and
     * raw-audio bodies remain ineligible for dynamic signing.
     */
    val remoteStorageSigningPayload: RemoteStorageSigningPayload? = null,
)

/**
 * An explicit, finite exception for S3-compatible remote storage.  S3 allows
 * a streaming PUT or DELETE to use the literal `UNSIGNED-PAYLOAD`; arbitrary
 * caller-supplied hash overrides are intentionally not exposed.
 */
sealed class RemoteStorageSigningPayload private constructor(
    internal val payloadHash: String,
) {
    data object UnsignedPayload : RemoteStorageSigningPayload("UNSIGNED-PAYLOAD")
}

/** A live response.  Callers must close it, especially after opening a stream. */
interface HttpResponse : Closeable {
    val statusCode: Int
    val headers: List<HttpHeader>

    /** Reads and closes this response, enforcing a bounded in-memory body. */
    @Throws(HttpTransportException::class)
    fun readBody(maxBytes: Int = MAX_ADVANCED_RESPONSE_BYTES): ByteArray

    /**
     * Opens the response stream for SSE, NDJSON, or JSON-chunk consumers.
     * Closing the returned stream closes the underlying HTTP response.
     */
    @Throws(HttpTransportException::class)
    fun openBodyStream(): InputStream
}

/** A completed, bounded response suitable for extractors and captures. */
data class HttpResponseData(
    val statusCode: Int,
    val headers: List<HttpHeader>,
    val body: ByteArray,
) {
    fun isAcceptedBy(acceptedStatuses: Set<Int>): Boolean = statusCode in acceptedStatuses

    fun headerValues(name: String): List<String> = headers
        .asSequence()
        .filter { it.name.equals(name, ignoreCase = true) }
        .map { it.value }
        .toList()
}

interface AdvancedHttpTransport {
    @Throws(HttpTransportException::class)
    fun execute(
        request: HttpRequestSpec,
        cancellation: AdvancedCancellationToken = AdvancedCancellationToken.none(),
        /**
         * Bounds this one complete call, including connection, request upload,
         * response headers, and response-body reads.  A null value preserves
         * the transport's ordinary configured timeout.
         */
        callTimeoutMillis: Long? = null,
    ): HttpResponse

    @Throws(HttpTransportException::class)
    fun executeAndRead(
        request: HttpRequestSpec,
        cancellation: AdvancedCancellationToken = AdvancedCancellationToken.none(),
        maxBytes: Int = MAX_ADVANCED_RESPONSE_BYTES,
        callTimeoutMillis: Long? = null,
    ): HttpResponseData = execute(request, cancellation, callTimeoutMillis).use { response ->
        HttpResponseData(
            statusCode = response.statusCode,
            headers = response.headers,
            body = response.readBody(maxBytes),
        )
    }
}

/** Matches the Windows Advanced Audio API's bounded materialized response limit. */
const val MAX_ADVANCED_RESPONSE_BYTES: Int = 32 * 1024 * 1024
