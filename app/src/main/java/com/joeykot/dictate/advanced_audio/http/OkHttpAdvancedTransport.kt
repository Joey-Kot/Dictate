package com.joeykot.dictate.advanced_audio.http

import com.joeykot.dictate.advanced_audio.HeaderSafetyException
import com.joeykot.dictate.advanced_audio.HttpHeaderSafety
import com.joeykot.dictate.advanced_audio.auth.RequestSigningException
import com.joeykot.dictate.advanced_audio.auth.AwsSigV4Signer
import com.joeykot.dictate.advanced_audio.auth.SigningHeaders
import com.joeykot.dictate.advanced_audio.auth.SigningRequest
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Advanced-only HTTP implementation.  It intentionally disables OkHttp's
 * connection retry so an `async_poll` submit cannot be duplicated below the
 * workflow executor's explicit stage-level retry policy.
 */
class OkHttpAdvancedTransport(
    private val client: OkHttpClient = newDefaultClient(),
) : AdvancedHttpTransport {
    override fun execute(
        request: HttpRequestSpec,
        cancellation: AdvancedCancellationToken,
        callTimeoutMillis: Long?,
    ): HttpResponse {
        cancellation.throwIfCancelled()
        val url = buildUrl(request.url, request.query)
        val builtBody = buildBody(request.body)
        val headers = SigningHeaders(request.headers).apply {
            if (!contains("User-Agent")) set("User-Agent", ADVANCED_USER_AGENT)
            if (builtBody.contentType != null && !contains("Content-Type")) {
                set("Content-Type", builtBody.contentType)
            }
        }

        request.signer?.let { signer ->
            val remotePayload = request.remoteStorageSigningPayload
            val validRemoteStorageRequest = signer is AwsSigV4Signer && when (request.body) {
                is HttpBodySpec.RawAudio -> request.method == AdvancedHttpMethod.PUT
                HttpBodySpec.None -> request.method == AdvancedHttpMethod.DELETE
                else -> false
            }
            if (remotePayload != null && !validRemoteStorageRequest) {
                throw HttpTransportException.InvalidRequest()
            }
            val payload = builtBody.signingBytes
            if (payload == null && remotePayload == null) {
                throw HttpTransportException.SignerRequiresMaterializedBody()
            }
            try {
                signer.sign(
                    SigningRequest(
                        method = request.method,
                        url = url,
                        headers = headers,
                        payload = payload ?: ByteArray(0),
                        payloadHashOverride = remotePayload?.payloadHash,
                        timestamp = Instant.now(),
                    ),
                )
            } catch (error: RequestSigningException) {
                throw HttpTransportException.SigningFailed(error)
            }
        }

        try {
            // Renderers validate workflow headers earlier, but transport also
            // accepts remote-storage requests and signer-generated fields.
            // Keep this final guard before building an OkHttp request.
            HttpHeaderSafety.requireSafe(headers.toList())
        } catch (_: HeaderSafetyException) {
            throw HttpTransportException.InvalidRequest()
        }

        val okHttpRequest = buildOkHttpRequest(request.method, url, headers, builtBody.requestBody)
        val callClient = callTimeoutMillis?.let { timeoutMillis ->
            // OkHttp's call timeout covers the response body as well as the
            // initial execute() call.  This is required for poll deadlines:
            // a server that accepts a connection but never responds must not
            // hold the workflow past its declared timeout.
            client.newBuilder()
                .callTimeout(timeoutMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
                .build()
        } ?: client
        val call = callClient.newCall(okHttpRequest)
        val cancellationRegistration = cancellation.onCancellation { call.cancel() }
        try {
            cancellation.throwIfCancelled()
            val response = call.execute()
            if (cancellation.isCancelled()) {
                response.close()
                throw HttpTransportException.Cancelled()
            }
            return OkHttpResponse(response, cancellation, cancellationRegistration)
        } catch (error: HttpTransportException) {
            cancellationRegistration.close()
            call.cancel()
            throw error
        } catch (error: IOException) {
            cancellationRegistration.close()
            call.cancel()
            if (cancellation.isCancelled()) throw HttpTransportException.Cancelled()
            throw HttpTransportException.NetworkFailure(error)
        } catch (error: IllegalArgumentException) {
            cancellationRegistration.close()
            call.cancel()
            throw HttpTransportException.InvalidRequest()
        }
    }

    private fun buildUrl(value: String, query: List<HttpQueryParameter>): HttpUrl {
        val parsed = value.toHttpUrlOrNull() ?: throw HttpTransportException.InvalidUrl()
        if (parsed.scheme != "http" && parsed.scheme != "https" || parsed.host.isBlank()) {
            throw HttpTransportException.InvalidUrl()
        }
        if (query.isEmpty()) return parsed
        return parsed.newBuilder().apply {
            query.forEach { parameter ->
                addQueryParameter(parameter.name, parameter.value)
            }
        }.build()
    }

    private fun buildOkHttpRequest(
        method: AdvancedHttpMethod,
        url: HttpUrl,
        headers: SigningHeaders,
        requestBody: RequestBody?,
    ): Request = try {
        val effectiveBody = requestBody ?: when {
            method.requiresRequestBodyForOkHttp() -> EMPTY_REQUEST_BODY
            else -> null
        }
        val headerBuilder = Headers.Builder()
        headers.toList().forEach { header -> headerBuilder.add(header.name, header.value) }
        Request.Builder()
            .url(url)
            .headers(headerBuilder.build())
            .method(method.wireName, effectiveBody)
            .build()
    } catch (_: IllegalArgumentException) {
        throw HttpTransportException.InvalidRequest()
    }

    private fun buildBody(body: HttpBodySpec): BuiltRequestBody = when (body) {
        HttpBodySpec.None -> BuiltRequestBody(null, null, ByteArray(0))
        is HttpBodySpec.Json -> {
            val bytes = body.value.toByteArray(Charsets.UTF_8)
            BuiltRequestBody(bytes.toRequestBody(JSON_MEDIA_TYPE), JSON_MEDIA_TYPE.toString(), bytes)
        }
        is HttpBodySpec.FormUrlEncoded -> {
            val form = FormBody.Builder().apply {
                body.fields.forEach { field -> add(field.name, field.value) }
            }.build()
            val bytes = Buffer().use { buffer ->
                form.writeTo(buffer)
                buffer.readByteArray()
            }
            val contentType = form.contentType()?.toString()
            BuiltRequestBody(bytes.toRequestBody(form.contentType()), contentType, bytes)
        }
        is HttpBodySpec.Multipart -> {
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                body.fields.forEach { field ->
                    when (val value = field.value) {
                        is MultipartValue.Text -> addFormDataPart(field.name, value.value)
                        is MultipartValue.Bytes -> addFormDataPart(
                            field.name,
                            null,
                            value.value.copyOf().toRequestBody(parseMediaType(value.contentType)),
                        )
                        MultipartValue.AudioFile -> {
                            val audio = body.audio ?: throw HttpTransportException.AudioFileUnavailable()
                            addFormDataPart(
                                field.name,
                                audio.filename,
                                audioRequestBody(audio),
                            )
                        }
                    }
                }
            }.build()
            BuiltRequestBody(multipart, multipart.contentType()?.toString(), null)
        }
        is HttpBodySpec.RawAudio -> {
            val audioBody = audioRequestBody(body.audio)
            BuiltRequestBody(audioBody, audioBody.contentType()?.toString(), null)
        }
        is HttpBodySpec.RawBytes -> {
            val bytes = body.value.copyOf()
            val contentType = parseMediaType(body.contentType)
            BuiltRequestBody(bytes.toRequestBody(contentType), contentType?.toString(), bytes)
        }
    }

    private fun audioRequestBody(audio: PreparedAudioUpload): RequestBody {
        if (!audio.file.isFile || !audio.file.canRead()) {
            throw HttpTransportException.AudioFileUnavailable(FileNotFoundException())
        }
        val mediaType = parseMediaType(audio.mimeType)
            ?: throw HttpTransportException.InvalidMediaType()
        return audio.file.asRequestBody(mediaType)
    }

    private fun parseMediaType(value: String?): MediaType? {
        if (value == null) return null
        return value.toMediaTypeOrNull() ?: throw HttpTransportException.InvalidMediaType()
    }

    private data class BuiltRequestBody(
        val requestBody: RequestBody?,
        val contentType: String?,
        /** Null deliberately marks a streaming multipart/raw audio body. */
        val signingBytes: ByteArray?,
    )

    private class OkHttpResponse(
        private val response: Response,
        private val cancellation: AdvancedCancellationToken,
        private val cancellationRegistration: CancellationRegistration,
    ) : HttpResponse {
        override val statusCode: Int = response.code
        override val headers: List<HttpHeader> = buildList(response.headers.size) {
            repeat(response.headers.size) { index ->
                add(HttpHeader(response.headers.name(index), response.headers.value(index)))
            }
        }

        private val bodyClaimed = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)

        override fun readBody(maxBytes: Int): ByteArray {
            require(maxBytes >= 0) { "Response limit cannot be negative" }
            claimBody()
            try {
                val input = response.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
                input.use { stream ->
                    val output = Buffer()
                    val buffer = ByteArray(BODY_BUFFER_BYTES)
                    var total = 0
                    while (true) {
                        cancellation.throwIfCancelled()
                        val count = try {
                            stream.read(buffer)
                        } catch (error: IOException) {
                            if (cancellation.isCancelled()) throw HttpTransportException.Cancelled()
                            throw HttpTransportException.ResponseReadFailure(error)
                        }
                        if (count < 0) break
                        total = total.checkedAdd(count, maxBytes)
                        output.write(buffer, 0, count)
                    }
                    return output.readByteArray()
                }
            } finally {
                close()
            }
        }

        override fun openBodyStream(): InputStream {
            cancellation.throwIfCancelled()
            claimBody()
            val input = response.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
            return CancellableResponseStream(input, cancellation, this)
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                response.close()
            } finally {
                cancellationRegistration.close()
            }
        }

        private fun claimBody() {
            if (closed.get() || !bodyClaimed.compareAndSet(false, true)) {
                throw HttpTransportException.ResponseAlreadyConsumed()
            }
        }

        private fun Int.checkedAdd(addend: Int, limit: Int): Int {
            if (addend > limit - this) throw HttpTransportException.ResponseTooLarge(limit)
            return this + addend
        }
    }

    private class CancellableResponseStream(
        private val input: InputStream,
        private val cancellation: AdvancedCancellationToken,
        private val owner: Closeable,
    ) : InputStream() {
        override fun read(): Int = readCancellable { input.read() }

        override fun read(buffer: ByteArray): Int = readCancellable { input.read(buffer) }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            readCancellable { input.read(buffer, offset, length) }

        override fun skip(count: Long): Long = readCancellable { input.skip(count) }

        override fun close() {
            try {
                input.close()
            } finally {
                owner.close()
            }
        }

        private inline fun <T> readCancellable(block: () -> T): T {
            cancellation.throwIfCancelled()
            return try {
                block()
            } catch (error: IOException) {
                if (cancellation.isCancelled()) throw HttpTransportException.Cancelled()
                throw HttpTransportException.ResponseReadFailure(error)
            }
        }
    }

    private companion object {
        const val ADVANCED_USER_AGENT = "dictate-client/advanced-audio-v1"
        const val BODY_BUFFER_BYTES = 16 * 1024
        val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()
        val EMPTY_REQUEST_BODY: RequestBody = ByteArray(0).toRequestBody()

        fun newDefaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}
