package com.joeykot.dictate.advanced_audio.realtime

import com.joeykot.dictate.advanced_audio.AdvancedAudioWorkflow
import com.joeykot.dictate.advanced_audio.AudioTemplateValues
import com.joeykot.dictate.advanced_audio.HeaderSafetyException
import com.joeykot.dictate.advanced_audio.HttpHeaderSafety
import com.joeykot.dictate.advanced_audio.JsonPath
import com.joeykot.dictate.advanced_audio.JsonPathException
import com.joeykot.dictate.advanced_audio.JsonValue
import com.joeykot.dictate.advanced_audio.JsonValueCodec
import com.joeykot.dictate.advanced_audio.ParameterDefinition
import com.joeykot.dictate.advanced_audio.RealtimeAudioMessage
import com.joeykot.dictate.advanced_audio.RealtimeCompletion
import com.joeykot.dictate.advanced_audio.RealtimeMessage
import com.joeykot.dictate.advanced_audio.RealtimeSessionRecognition
import com.joeykot.dictate.advanced_audio.RealtimeWorkflow
import com.joeykot.dictate.advanced_audio.RuntimeTemplateValues
import com.joeykot.dictate.advanced_audio.SignerConfig
import com.joeykot.dictate.advanced_audio.StreamAction
import com.joeykot.dictate.advanced_audio.StreamRule
import com.joeykot.dictate.advanced_audio.TemplateContext
import com.joeykot.dictate.advanced_audio.TranscriptAccumulator
import com.joeykot.dictate.advanced_audio.TranscriptAccumulatorException
import com.joeykot.dictate.advanced_audio.TypedTemplateRenderer
import com.joeykot.dictate.advanced_audio.WorkflowValidator
import com.joeykot.dictate.advanced_audio.auth.AwsSigV4Signer
import com.joeykot.dictate.advanced_audio.auth.HttpRequestSigner
import com.joeykot.dictate.advanced_audio.auth.RequestSigningException
import com.joeykot.dictate.advanced_audio.auth.SigningHeaders
import com.joeykot.dictate.advanced_audio.auth.SigningRequest
import com.joeykot.dictate.advanced_audio.auth.TencentTc3Signer
import com.joeykot.dictate.advanced_audio.http.AdvancedCancellationToken
import com.joeykot.dictate.advanced_audio.http.AdvancedHttpMethod
import com.joeykot.dictate.advanced_audio.http.HttpHeader
import com.joeykot.dictate.advanced_audio.http.HttpTransportException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.Base64
import kotlin.math.min

/** A completed realtime transcript. Partial transcript state is never exposed. */
data class RealtimeSessionResult(
    val text: String,
)

/**
 * Executes a validated declarative `realtime_session` workflow.
 *
 * This class is deliberately synchronous. The Android job/lifecycle layer
 * owns the worker thread and one [AdvancedCancellationToken] per recording.
 * It provides the shared protocol behavior used by a completed PCM replay and
 * a future bounded live recorder tee without coupling the core to UI or
 * AudioRecord APIs.
 */
class RealtimeSessionRunner(
    private val webSocketFactory: RealtimeWebSocketFactory = defaultRealtimeWebSocketFactory,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Runs exactly one websocket session. [source] is consumed and closed by
     * this call, including on cancellation or a protocol failure.
     */
    fun run(
        workflow: AdvancedAudioWorkflow,
        values: Map<String, String>,
        secrets: Map<String, String>,
        runtime: RuntimeTemplateValues,
        source: RealtimeChunkSource,
        cancellation: AdvancedCancellationToken = AdvancedCancellationToken.none(),
    ): RealtimeSessionResult = try {
        cancellation.throwIfCancelled()
        val realtime = (workflow.recognition as? RealtimeSessionRecognition)?.realtime
            ?: throw RealtimeSessionException.InvalidConfiguration
        val effectiveValues = effectiveValues(workflow, values)
        val workflowSecrets = secrets.filterKeys { candidate -> workflow.secrets.any { it.id == candidate } }
        try {
            WorkflowValidator.requireValidExecutionInputs(workflow, effectiveValues, workflowSecrets)
        } catch (_: Exception) {
            throw RealtimeSessionException.InvalidConfiguration
        }

        val renderContext = RealtimeRenderContext(
            values = effectiveValues,
            secrets = workflowSecrets,
            runtime = runtime,
            parameters = workflow.parameters,
            schemaVersion = workflow.schemaVersion,
        )
        val request = renderConnection(realtime, renderContext)
        val socket = webSocketFactory.connect(request, cancellation)
        try {
            runConnected(socket, realtime, renderContext, source, cancellation)
        } finally {
            runCatching { socket.close() }
        }
    } catch (_: HttpTransportException.Cancelled) {
        throw RealtimeSessionException.SessionCancelled
    } catch (error: RealtimeSessionException) {
        throw error
    } catch (_: RealtimeChunkSourceException) {
        throw RealtimeSessionException.AudioSourceFailed
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        throw RealtimeSessionException.SessionCancelled
    } catch (_: Exception) {
        // Factories and third-party implementations must never turn a URL,
        // header, secret, frame, or server error body into a user diagnostic.
        throw RealtimeSessionException.WebSocketSendReceiveFailed
    } finally {
        runCatching { source.close() }
    }

    private fun runConnected(
        socket: RealtimeWebSocket,
        realtime: RealtimeWorkflow,
        renderContext: RealtimeRenderContext,
        source: RealtimeChunkSource,
        cancellation: AdvancedCancellationToken,
    ): RealtimeSessionResult {
        val accumulator = TranscriptAccumulator()
        realtime.initialMessages.forEach { message ->
            sendMessage(socket, message, renderContext, cancellation)
        }

        while (true) {
            cancellation.throwIfCancelled()
            val chunk = source.nextChunk(cancellation) ?: break
            sendAudio(socket, realtime.audioMessage, chunk, renderContext, cancellation)
            if (source.requiresRealtimePacing) {
                waitWithMessages(
                    socket = socket,
                    realtime = realtime,
                    accumulator = accumulator,
                    durationMillis = chunk.durationMillis,
                    cancellation = cancellation,
                )
            } else {
                drainAvailableMessages(socket, realtime, accumulator, cancellation)
            }
            if (accumulator.isComplete) return completed(accumulator, source)
        }

        realtime.finishMessages.forEach { message ->
            sendMessage(socket, message, renderContext, cancellation)
        }
        waitUntilComplete(socket, realtime, accumulator, cancellation)
        return completed(accumulator, source)
    }

    private fun sendMessage(
        socket: RealtimeWebSocket,
        message: RealtimeMessage,
        renderContext: RealtimeRenderContext,
        cancellation: AdvancedCancellationToken,
    ) {
        cancellation.throwIfCancelled()
        when (message) {
            is RealtimeMessage.Json -> {
                val text = JsonValueCodec.stringify(renderJson(message.value, renderContext, null))
                if (!socket.sendText(text)) throw RealtimeSessionException.WebSocketSendReceiveFailed
            }

            is RealtimeMessage.Text -> {
                if (!socket.sendText(renderText(message.value, renderContext, null))) {
                    throw RealtimeSessionException.WebSocketSendReceiveFailed
                }
            }

            is RealtimeMessage.Binary -> {
                val bytes = try {
                    Base64.getDecoder().decode(renderText(message.value, renderContext, null))
                } catch (_: IllegalArgumentException) {
                    throw RealtimeSessionException.InvalidConfiguration
                }
                if (!socket.sendBinary(bytes)) throw RealtimeSessionException.WebSocketSendReceiveFailed
            }
        }
    }

    private fun sendAudio(
        socket: RealtimeWebSocket,
        message: RealtimeAudioMessage,
        chunk: RealtimeAudioChunk,
        renderContext: RealtimeRenderContext,
        cancellation: AdvancedCancellationToken,
    ) {
        cancellation.throwIfCancelled()
        when (message) {
            RealtimeAudioMessage.Binary -> {
                if (!socket.sendBinary(chunk.bytes)) throw RealtimeSessionException.WebSocketSendReceiveFailed
            }

            is RealtimeAudioMessage.Text -> {
                if (!socket.sendText(renderText(message.value, renderContext, chunk))) {
                    throw RealtimeSessionException.WebSocketSendReceiveFailed
                }
            }

            is RealtimeAudioMessage.Json -> {
                val text = JsonValueCodec.stringify(renderJson(message.value, renderContext, chunk))
                if (!socket.sendText(text)) throw RealtimeSessionException.WebSocketSendReceiveFailed
            }
        }
    }

    private fun waitWithMessages(
        socket: RealtimeWebSocket,
        realtime: RealtimeWorkflow,
        accumulator: TranscriptAccumulator,
        durationMillis: Long,
        cancellation: AdvancedCancellationToken,
    ) {
        val deadline = Deadline.after(durationMillis)
        while (!accumulator.isComplete) {
            cancellation.throwIfCancelled()
            val remainingMillis = deadline.remainingMillis() ?: return
            val event = socket.receive(min(remainingMillis, RECEIVE_WAIT_SLICE_MILLIS)) ?: continue
            processIncoming(event, realtime, accumulator)
        }
    }

    private fun drainAvailableMessages(
        socket: RealtimeWebSocket,
        realtime: RealtimeWorkflow,
        accumulator: TranscriptAccumulator,
        cancellation: AdvancedCancellationToken,
    ) {
        while (!accumulator.isComplete) {
            cancellation.throwIfCancelled()
            val event = socket.receive(0L) ?: return
            processIncoming(event, realtime, accumulator)
        }
    }

    private fun waitUntilComplete(
        socket: RealtimeWebSocket,
        realtime: RealtimeWorkflow,
        accumulator: TranscriptAccumulator,
        cancellation: AdvancedCancellationToken,
    ) {
        val deadline = Deadline.after(realtime.finalizationTimeoutMs)
        while (!accumulator.isComplete) {
            cancellation.throwIfCancelled()
            val remainingMillis = deadline.remainingMillis()
                ?: throw RealtimeSessionException.FinalizationTimeout
            val event = socket.receive(min(remainingMillis, RECEIVE_WAIT_SLICE_MILLIS)) ?: continue
            processIncoming(event, realtime, accumulator)
        }
    }

    private fun processIncoming(
        event: RealtimeWebSocketEvent,
        realtime: RealtimeWorkflow,
        accumulator: TranscriptAccumulator,
    ) {
        val data = when (event) {
            is RealtimeWebSocketEvent.Text -> event.value
            is RealtimeWebSocketEvent.Binary -> decodeUtf8(event.value)
            RealtimeWebSocketEvent.Closed -> throw RealtimeSessionException.ConnectionClosedBeforeCompletion
            RealtimeWebSocketEvent.Failed -> throw RealtimeSessionException.WebSocketSendReceiveFailed
        }
        val root = try {
            JsonValueCodec.parse(data)
        } catch (_: IllegalArgumentException) {
            throw RealtimeSessionException.InvalidIncomingJsonOrText
        }
        val eventName = websocketEventName(root)
        applyReceiveRules(realtime.receiveRules, eventName, root, accumulator)
        if (!accumulator.isComplete && completionMatches(realtime.completion, eventName, root)) {
            try {
                accumulator.complete()
            } catch (_: TranscriptAccumulatorException) {
                throw RealtimeSessionException.StreamProtocolEventFailed
            }
        }
    }

    private fun applyReceiveRules(
        rules: List<StreamRule>,
        eventName: String?,
        root: JsonValue,
        accumulator: TranscriptAccumulator,
    ) {
        rules.forEach { rule ->
            if (rule.event != null && rule.event != eventName) return@forEach
            val value = rule.path?.let { path ->
                try {
                    JsonPath.parse(path).extractOne(root)
                } catch (_: JsonPathException.NoMatch) {
                    // Mixed event streams commonly omit another event's
                    // transcript field. That is a non-match, not an error.
                    return@forEach
                } catch (_: JsonPathException) {
                    throw RealtimeSessionException.StreamProtocolEventFailed
                }
            }
            if (rule.equals != null && value != rule.equals) return@forEach
            val action = when (rule.action) {
                StreamAction.IGNORE -> com.joeykot.dictate.advanced_audio.TranscriptAction.Ignore
                StreamAction.APPEND_DELTA -> com.joeykot.dictate.advanced_audio.TranscriptAction.AppendDelta(value.orEmpty())
                StreamAction.REPLACE_PARTIAL -> com.joeykot.dictate.advanced_audio.TranscriptAction.ReplacePartial(value.orEmpty())
                StreamAction.COMMIT_SEGMENT -> com.joeykot.dictate.advanced_audio.TranscriptAction.CommitSegment(value.orEmpty())
                StreamAction.SET_FINAL_TEXT -> com.joeykot.dictate.advanced_audio.TranscriptAction.SetFinalText(value.orEmpty())
                StreamAction.COMPLETE -> com.joeykot.dictate.advanced_audio.TranscriptAction.Complete
                StreamAction.FAIL -> com.joeykot.dictate.advanced_audio.TranscriptAction.Fail(
                    value ?: "server stream failure",
                )
            }
            try {
                accumulator.apply(action)
            } catch (_: TranscriptAccumulatorException) {
                throw RealtimeSessionException.StreamProtocolEventFailed
            }
        }
    }

    private fun completionMatches(
        completion: RealtimeCompletion,
        eventName: String?,
        root: JsonValue,
    ): Boolean {
        if (completion.event == null && completion.path == null) return false
        if (completion.event != null && completion.event != eventName) return false
        val value = completion.path?.let { path ->
            try {
                JsonPath.parse(path).extractOne(root)
            } catch (_: JsonPathException) {
                throw RealtimeSessionException.StreamProtocolEventFailed
            }
        }
        return completion.equals == null || value == completion.equals
    }

    private fun websocketEventName(root: JsonValue): String? {
        val objectValue = root as? JsonValue.Object ?: return null
        return listOf("event", "type").firstNotNullOfOrNull { name ->
            (objectValue[name] as? JsonValue.Text)?.value
        }
    }

    private fun completed(
        accumulator: TranscriptAccumulator,
        source: RealtimeChunkSource,
    ): RealtimeSessionResult = try {
        // A server may emit a syntactically valid complete event while Android
        // is still recording. Returning that text would silently discard the
        // unsent tail. The lifecycle must instead replay the complete local
        // PCM recording through a fresh session after recording stops.
        if (!source.requiresRealtimePacing && !source.isFinished) {
            throw RealtimeSessionException.LiveCompletedBeforeSourceEnd
        }
        RealtimeSessionResult(accumulator.finalText())
    } catch (_: TranscriptAccumulatorException) {
        // A result is never emitted without an explicit complete action.
        throw RealtimeSessionException.StreamProtocolEventFailed
    }

    private fun renderConnection(
        realtime: RealtimeWorkflow,
        renderContext: RealtimeRenderContext,
    ): RealtimeWebSocketRequest {
        val baseUrl = parseWebSocketUrl(renderText(realtime.connect.url, renderContext, null))
        val url = baseUrl.newBuilder().apply {
            realtime.connect.query.forEach { (name, value) ->
                addQueryParameter(name, renderText(value, renderContext, null))
            }
        }.build()
        val headers = SigningHeaders(
            realtime.connect.headers.map { (name, value) ->
                HttpHeader(name, renderText(value, renderContext, null))
            },
        ).apply {
            // This is intentionally fixed, matching the Windows websocket
            // handshake and keeping the user agent out of workflow control.
            set("User-Agent", ADVANCED_USER_AGENT)
            realtime.connect.subprotocol?.let { protocol ->
                set("Sec-WebSocket-Protocol", renderText(protocol, renderContext, null))
            }
        }
        val signer = try {
            signerFor(realtime.connect.signer, renderContext.secrets)
        } catch (_: RequestSigningException) {
            throw RealtimeSessionException.InvalidConfiguration
        } catch (_: IllegalArgumentException) {
            throw RealtimeSessionException.InvalidConfiguration
        }
        try {
            signer?.sign(
                SigningRequest(
                    method = AdvancedHttpMethod.GET,
                    url = url,
                    headers = headers,
                    payload = ByteArray(0),
                    timestamp = clock.instant(),
                ),
            )
        } catch (_: RequestSigningException) {
            throw RealtimeSessionException.InvalidConfiguration
        } catch (_: IllegalArgumentException) {
            throw RealtimeSessionException.InvalidConfiguration
        }
        try {
            // Template values and signer-generated fields must be checked only
            // after they have all been materialized, before a websocket
            // factory can open a handshake connection.
            HttpHeaderSafety.requireSafe(headers.toList())
        } catch (_: HeaderSafetyException) {
            throw RealtimeSessionException.InvalidConfiguration
        }
        // Keep this construction beside signing. The factory is intentionally
        // transport-only and never renders or mutates protocol templates.
        return RealtimeWebSocketRequest(url.toString(), headers.toList())
    }

    private fun parseWebSocketUrl(value: String): HttpUrl {
        val httpValue = when {
            value.startsWith("ws://", ignoreCase = true) -> "http://${value.substring(5)}"
            value.startsWith("wss://", ignoreCase = true) -> "https://${value.substring(6)}"
            else -> throw RealtimeSessionException.InvalidConfiguration
        }
        return httpValue.toHttpUrlOrNull()?.takeIf { url ->
            url.scheme == "http" || url.scheme == "https"
        } ?: throw RealtimeSessionException.InvalidConfiguration
    }

    private fun signerFor(
        config: SignerConfig,
        secrets: Map<String, String>,
    ): HttpRequestSigner? = when (config) {
        SignerConfig.None -> null
        is SignerConfig.AwsSigv4 -> AwsSigV4Signer(
            region = config.region,
            service = config.service,
            accessKey = requiredSecret(config.accessKeySecret, secrets),
            secretKey = requiredSecret(config.secretKeySecret, secrets),
            sessionToken = config.sessionTokenSecret?.let { requiredSecret(it, secrets) },
        )

        is SignerConfig.TencentTc3 -> TencentTc3Signer(
            service = config.service,
            secretId = requiredSecret(config.secretIdSecret, secrets),
            secretKey = requiredSecret(config.secretKeySecret, secrets),
        )
    }

    private fun requiredSecret(id: String, secrets: Map<String, String>): String = secrets[id]
        ?.takeIf { it.isNotBlank() }
        ?: throw RealtimeSessionException.InvalidConfiguration

    private fun renderText(
        value: String,
        renderContext: RealtimeRenderContext,
        chunk: RealtimeAudioChunk?,
    ): String = try {
        TypedTemplateRenderer.renderText(
            value,
            renderContext.templateContext(chunk),
            renderContext.parameters,
            renderContext.schemaVersion,
        )
    } catch (_: IllegalArgumentException) {
        throw RealtimeSessionException.InvalidConfiguration
    }

    private fun renderJson(
        value: JsonValue,
        renderContext: RealtimeRenderContext,
        chunk: RealtimeAudioChunk?,
    ): JsonValue = try {
        TypedTemplateRenderer.renderJson(
            value,
            renderContext.templateContext(chunk),
            renderContext.parameters,
            renderContext.schemaVersion,
        )
    } catch (_: IllegalArgumentException) {
        throw RealtimeSessionException.InvalidConfiguration
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        throw RealtimeSessionException.InvalidIncomingJsonOrText
    }

    private fun effectiveValues(
        workflow: AdvancedAudioWorkflow,
        configured: Map<String, String>,
    ): Map<String, String> = LinkedHashMap<String, String>().apply {
        putAll(configured)
        workflow.parameters.forEach { parameter ->
            if (parameter.id !in this && parameter.defaultValue != null) {
                put(parameter.id, parameter.defaultValue)
            }
        }
    }

    private class RealtimeRenderContext(
        val values: Map<String, String>,
        val secrets: Map<String, String>,
        val runtime: RuntimeTemplateValues,
        val parameters: List<ParameterDefinition>,
        val schemaVersion: Int,
    ) {
        fun templateContext(chunk: RealtimeAudioChunk?): TemplateContext = TemplateContext(
            values = values,
            secrets = secrets,
            captures = emptyMap(),
            audio = AudioTemplateValues(
                chunkBase64 = chunk?.let { Base64.getEncoder().encodeToString(it.bytes) },
            ),
            runtime = runtime,
        )
    }

    private class Deadline private constructor(
        private val startedAtNanos: Long,
        private val durationNanos: Long,
    ) {
        fun remainingMillis(): Long? {
            val elapsed = System.nanoTime() - startedAtNanos
            if (elapsed >= durationNanos) return null
            val remainingNanos = durationNanos - elapsed
            return (remainingNanos + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND
        }

        companion object {
            fun after(millis: Long): Deadline = Deadline(
                startedAtNanos = System.nanoTime(),
                durationNanos = when {
                    millis <= 0L -> 0L
                    millis > Long.MAX_VALUE / NANOS_PER_MILLISECOND -> Long.MAX_VALUE
                    else -> millis * NANOS_PER_MILLISECOND
                },
            )
        }
    }

    private companion object {
        const val ADVANCED_USER_AGENT = "dictate-client/advanced-audio-v1"
        const val RECEIVE_WAIT_SLICE_MILLIS = 50L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

/**
 * Replay retries and pause-delimited live sessions create fresh runners, but
 * they must not allocate a fresh OkHttp dispatcher and connection pool each
 * time. A process-lifetime factory preserves the required per-session socket
 * isolation while reusing the transport resources underneath.
 */
private val defaultRealtimeWebSocketFactory: RealtimeWebSocketFactory by lazy {
    OkHttpRealtimeWebSocketFactory()
}

/**
 * Every message is safe to place in UI state or an ordinary log line. None
 * contains a rendered URL, header, secret, frame payload, server close reason,
 * or provider error text.
 */
sealed class RealtimeSessionException(message: String) : IOException(message) {
    data object InvalidConfiguration : RealtimeSessionException("Realtime session configuration is invalid")
    data object WebSocketConnectionFailed : RealtimeSessionException("WebSocket connection failed")
    data object WebSocketSendReceiveFailed : RealtimeSessionException("WebSocket send/receive failed")
    data object InvalidIncomingJsonOrText : RealtimeSessionException("Realtime server sent invalid JSON/text")
    data object ConnectionClosedBeforeCompletion : RealtimeSessionException(
        "WebSocket connection closed before explicit completion",
    )

    data object FinalizationTimeout : RealtimeSessionException(
        "Realtime session did not complete before the finalization timeout",
    )

    data object SessionCancelled : RealtimeSessionException("Realtime session was cancelled")
    data object StreamProtocolEventFailed : RealtimeSessionException("Realtime stream protocol event failed")
    data object AudioSourceFailed : RealtimeSessionException("Realtime audio source failed")
    data object LiveCompletedBeforeSourceEnd : RealtimeSessionException(
        "Realtime live session completed before the audio source ended",
    )
}
