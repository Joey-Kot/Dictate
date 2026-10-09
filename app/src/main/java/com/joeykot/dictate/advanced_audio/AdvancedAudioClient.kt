package com.joeykot.dictate.advanced_audio

import com.joeykot.dictate.advanced_audio.extractor.JsonPathEvaluationException
import com.joeykot.dictate.advanced_audio.extractor.JsonPathScalarEvaluator
import com.joeykot.dictate.advanced_audio.extractor.ResponseCaptureCollector
import com.joeykot.dictate.advanced_audio.extractor.ResponseExtraction
import com.joeykot.dictate.advanced_audio.extractor.ResponseExtractionException
import com.joeykot.dictate.advanced_audio.http.AdvancedCancellationToken
import com.joeykot.dictate.advanced_audio.http.AdvancedHttpTransport
import com.joeykot.dictate.advanced_audio.http.HttpResponseData
import com.joeykot.dictate.advanced_audio.http.HttpTransportException
import com.joeykot.dictate.advanced_audio.http.OkHttpAdvancedTransport
import com.joeykot.dictate.advanced_audio.remote_audio.PublishedRemoteAudio
import com.joeykot.dictate.advanced_audio.remote_audio.RemoteAudioConfigValidator
import com.joeykot.dictate.advanced_audio.remote_audio.RemoteAudioException
import com.joeykot.dictate.advanced_audio.remote_audio.RemoteAudioPublisher
import com.joeykot.dictate.model.AdvancedAudioConfig
import com.joeykot.dictate.model.RemoteAudioCredentialIds
import com.joeykot.dictate.model.RetryConfig
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.util.UUID
import kotlin.math.min

/**
 * Runs one validated HTTP Advanced Audio API workflow.
 *
 * The client is deliberately synchronous: [VoiceJobController] already owns a
 * worker thread and cancellation lifetime.  Keeping stage sequencing here
 * prevents its legacy whole-request retry policy from accidentally resending
 * an async submit stage.
 */
class AdvancedAudioClient(
    private val transport: AdvancedHttpTransport = OkHttpAdvancedTransport(),
    private val remoteAudioPublisher: RemoteAudioPublisher = RemoteAudioPublisher(transport),
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Synchronizes the post-recognition/pre-cleanup cancellation boundary in
     * unit tests. Production callers leave this unset.
     */
    internal var afterRecognitionCompletedBeforeCleanupForTest: (() -> Unit)? = null

    data class Request(
        val config: AdvancedAudioConfig,
        /** Includes workflow secrets and, where configured, remote-storage secrets. */
        val secrets: Map<String, String>,
        val audioFile: File,
        val mimeType: String,
        val retry: RetryConfig = RetryConfig(),
        /** Contains only stable phase labels; rendered protocol data is never emitted. */
        val onPhase: ((String) -> Unit)? = null,
    )

    sealed interface Result {
        data class Success(
            val text: String,
            val statusCode: Int,
            val elapsedMillis: Long,
        ) : Result

        data class Failure(
            val message: String,
            val statusCode: Int? = null,
            val elapsedMillis: Long,
        ) : Result

        data class Cancelled(val elapsedMillis: Long) : Result
    }

    /** Executes an HTTP workflow after the controller has dispatched realtime sessions. */
    fun transcribe(
        request: Request,
        cancellation: AdvancedCancellationToken = AdvancedCancellationToken.none(),
    ): Result {
        val startedAt = System.nanoTime()
        fun elapsedMillis(): Long = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND
        return try {
            cancellation.throwIfCancelled()
            if (!request.config.enabled) throw AdvancedAudioExecutionException.Disabled
            val document = request.config.workflowJson?.takeIf { it.isNotBlank() }
                ?: throw AdvancedAudioExecutionException.MissingWorkflow
            val workflow = try {
                AdvancedAudioWorkflowCodec.parse(document)
            } catch (_: IllegalArgumentException) {
                throw AdvancedAudioExecutionException.InvalidWorkflow
            }
            // Realtime sessions are dispatched from the raw PCM recorder
            // lifecycle before this HTTP-only client is reached. Keep this
            // defensive boundary as an ordinary configuration failure rather
            // than exposing a second caller-controlled fallback result.
            if (workflow.recognition is RealtimeSessionRecognition) {
                throw AdvancedAudioExecutionException.InvalidConfiguration
            }

            val values = effectiveValues(workflow, request.config.values)
            // Remote-storage secrets intentionally share the encrypted Android
            // store but are not workflow declarations. Only declared workflow
            // secrets participate in the portable schema validation/rendering.
            val remoteCredentialIds = RemoteAudioCredentialIds.forConfig(request.config.remoteAudio)
            val workflowSecrets = request.secrets.filterKeys { secretId ->
                secretId !in remoteCredentialIds && workflow.secrets.any { it.id == secretId }
            }
            try {
                WorkflowValidator.requireValidExecutionInputs(workflow, values, workflowSecrets)
            } catch (_: WorkflowValidationException) {
                throw AdvancedAudioExecutionException.InvalidConfiguration
            }
            if (RemoteAudioConfigValidator.validate(
                    config = request.config.remoteAudio,
                    delivery = workflow.audio.delivery,
                    secrets = request.secrets,
                ).isNotEmpty()
            ) {
                throw AdvancedAudioExecutionException.InvalidConfiguration
            }

            val upload = WorkflowAudio.fromFile(
                request.audioFile,
                workflow.audio.mime?.takeIf { it.isNotBlank() } ?: request.mimeType,
            )
            val runtime = runtimeValues()
            val renderer = WorkflowStageRenderer(workflow, values, workflowSecrets, runtime)
            var published: PublishedRemoteAudio? = null
            var completed = false
            try {
                var audio = upload
                if (workflow.audio.delivery == AudioDeliveryType.PUBLIC_HTTPS_URL ||
                    workflow.audio.delivery == AudioDeliveryType.CLOUD_URI
                ) {
                    // Values and secrets are already available here. Validate
                    // their rendered header contribution before the remote
                    // upload so malformed dynamic headers do not create an
                    // otherwise unnecessary remote object.
                    renderer.preflightHeadersBeforeRemoteUpload(
                        stages = stagesForHeaderPreflight(workflow.recognition),
                        audio = upload,
                    )
                    request.onPhase?.invoke(PHASE_REMOTE_UPLOAD)
                    published = remoteAudioPublisher.publish(
                        config = request.config.remoteAudio,
                        secrets = request.secrets,
                        audio = upload.upload,
                        delivery = workflow.audio.delivery,
                        cancellation = cancellation,
                    )
                    audio = upload.withRemoteReference(
                        publicHttpsUrl = published.reference.publicHttpsUrl(),
                        cloudUri = published.reference.cloudUri(),
                    )
                }

                val completedRequest = when (val recognition = workflow.recognition) {
                    is RequestRecognition -> {
                        request.onPhase?.invoke(PHASE_REQUEST)
                        val response = executeRetryableStage(
                            stage = recognition.request,
                            renderer = renderer,
                            audio = audio,
                            cancellation = cancellation,
                            retry = request.retry,
                            captures = ResponseCaptureCollector(),
                        )
                        CompletedRequest(extract(response, recognition.finalText), response.statusCode)
                    }

                    is RequestStreamRecognition -> {
                        request.onPhase?.invoke(PHASE_STREAM)
                        executeRequestStream(
                            recognition = recognition,
                            renderer = renderer,
                            audio = audio,
                            cancellation = cancellation,
                            retry = request.retry,
                        )
                    }

                    is AsyncPollRecognition -> executeAsyncPoll(
                        recognition = recognition,
                        renderer = renderer,
                        audio = audio,
                        cancellation = cancellation,
                        retry = request.retry,
                        onPhase = request.onPhase,
                    )

                    is RealtimeSessionRecognition -> throw AdvancedAudioExecutionException.InvalidConfiguration
                }
                completed = true
                afterRecognitionCompletedBeforeCleanupForTest?.invoke()
                // A cancellation can arrive after the final response is
                // accepted but before the remote object is cleaned up. Treat
                // that as a cancellation, and force deletion below even when
                // the user normally retains successful uploads.
                cancellation.throwIfCancelled()
                Result.Success(completedRequest.text, completedRequest.statusCode, elapsedMillis())
            } finally {
                published?.let {
                    remoteAudioPublisher.cleanup(
                        it,
                        force = !completed || cancellation.isCancelled(),
                    )
                }
            }
        } catch (_: HttpTransportException.Cancelled) {
            Result.Cancelled(elapsedMillis())
        } catch (_: RemoteAudioException.Cancelled) {
            Result.Cancelled(elapsedMillis())
        } catch (error: AdvancedAudioExecutionException) {
            Result.Failure(error.message ?: GENERIC_FAILURE_MESSAGE, error.statusCode, elapsedMillis())
        } catch (error: RemoteAudioException) {
            Result.Failure(error.message ?: GENERIC_FAILURE_MESSAGE, null, elapsedMillis())
        } catch (error: HttpTransportException) {
            Result.Failure(safeTransportMessage(error), null, elapsedMillis())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Result.Cancelled(elapsedMillis())
        } catch (_: Exception) {
            // Third-party transports and JSON parsers can include rendered URLs
            // or response values in their diagnostics. Do not leak those out of
            // the advanced workflow boundary.
            Result.Failure(GENERIC_FAILURE_MESSAGE, null, elapsedMillis())
        }
    }

    private fun executeAsyncPoll(
        recognition: AsyncPollRecognition,
        renderer: WorkflowStageRenderer,
        audio: WorkflowAudio,
        cancellation: AdvancedCancellationToken,
        retry: RetryConfig,
        onPhase: ((String) -> Unit)?,
    ): CompletedRequest {
        val captures = ResponseCaptureCollector()
        recognition.prepare?.let { prepare ->
            onPhase?.invoke(PHASE_PREPARE)
            executeStage(prepare, renderer, audio, cancellation, captures, captureResponse = true)
        }

        // Submission is intentionally never retried. A lost response can
        // still mean that the vendor created a billable asynchronous task.
        onPhase?.invoke(PHASE_SUBMIT)
        var response = executeStage(
            recognition.submit,
            renderer,
            audio,
            cancellation,
            captures,
            captureResponse = true,
        )
        recognition.poll?.let { poll ->
            response = pollUntilTerminal(poll, renderer, audio, cancellation, retry, captures, onPhase)
        }
        recognition.resultSteps.forEach { stage ->
            onPhase?.invoke(PHASE_RESULT)
            response = if (stage.method.isReadOnly) {
                executeRetryableStage(stage, renderer, audio, cancellation, retry, captures)
            } else {
                executeStage(stage, renderer, audio, cancellation, captures, captureResponse = true)
            }
        }
        return CompletedRequest(extract(response, recognition.finalText), response.statusCode)
    }

    private fun pollUntilTerminal(
        poll: PollStage,
        renderer: WorkflowStageRenderer,
        audio: WorkflowAudio,
        cancellation: AdvancedCancellationToken,
        retry: RetryConfig,
        captures: ResponseCaptureCollector,
        onPhase: ((String) -> Unit)?,
    ): HttpResponseData {
        val startedAt = System.nanoTime()
        val timeoutNanos = poll.timeoutMs.coerceAtMost(MAX_TIMEOUT_MILLIS) * NANOS_PER_MILLISECOND
        val deadlineNanos = startedAt + timeoutNanos
        while (true) {
            cancellation.throwIfCancelled()
            ensurePollDeadline(deadlineNanos, poll.timeoutMs)
            onPhase?.invoke(PHASE_POLL)
            // Poll captures must only be read after a terminal success. Pending
            // task responses commonly do not yet contain result URL fields.
            val response = executeRetryableStage(
                poll.request,
                renderer,
                audio,
                cancellation,
                retry,
                captures,
                captureResponse = false,
                retryReadFailure = true,
                deadlineNanos = deadlineNanos,
                pollTimeoutMs = poll.timeoutMs,
            )
            when (pollState(poll, response)) {
                PollState.SUCCESS -> {
                    storeCaptures(poll.request, response, captures)
                    return response
                }

                PollState.FAILURE -> throw AdvancedAudioExecutionException.PollFailure
                PollState.PENDING -> {
                    val remaining = remainingMillisUntil(deadlineNanos)
                    if (remaining <= 0L) throw AdvancedAudioExecutionException.PollTimeout(poll.timeoutMs)
                    waitCancellable(min(poll.intervalMs, remaining), cancellation)
                }
            }
        }
    }

    private fun executeRequestStream(
        recognition: RequestStreamRecognition,
        renderer: WorkflowStageRenderer,
        audio: WorkflowAudio,
        cancellation: AdvancedCancellationToken,
        retry: RetryConfig,
    ): CompletedRequest {
        val retries = retryCount(retry)
        var attempt = 0
        while (true) {
            try {
                return executeRequestStreamOnce(recognition, renderer, audio, cancellation)
            } catch (error: Throwable) {
                if (!isStreamRetryable(error) || attempt >= retries) throw error
                attempt += 1
                waitCancellable(retry.delayMillis(attempt), cancellation)
            }
        }
    }

    private fun executeRequestStreamOnce(
        recognition: RequestStreamRecognition,
        renderer: WorkflowStageRenderer,
        audio: WorkflowAudio,
        cancellation: AdvancedCancellationToken,
    ): CompletedRequest {
        val captures = ResponseCaptureCollector()
        val spec = renderer.render(recognition.request, audio, captures.snapshot())
        val response = transport.execute(spec, cancellation)
        try {
            if (response.statusCode !in recognition.request.acceptedStatuses) {
                val bounded = HttpResponseData(
                    statusCode = response.statusCode,
                    headers = response.headers,
                    body = response.readBody(),
                )
                throw AdvancedAudioExecutionException.UnexpectedStatus(bounded.statusCode)
            }
            val headerView = HttpResponseData(response.statusCode, response.headers, ByteArray(0))
            storeCaptures(recognition.request, headerView, captures)
            val accumulator = TranscriptAccumulator()
            response.openBodyStream().use { stream ->
                consumeStream(stream, recognition.stream, accumulator, cancellation)
            }
            val text = try {
                accumulator.finalText()
            } catch (error: TranscriptAccumulatorException) {
                throw AdvancedAudioExecutionException.StreamFailure(error)
            }
            return CompletedRequest(text, response.statusCode)
        } finally {
            response.close()
        }
    }

    private fun consumeStream(
        input: InputStream,
        schema: StreamResponse,
        accumulator: TranscriptAccumulator,
        cancellation: AdvancedCancellationToken,
    ) {
        val buffer = ByteArray(STREAM_READ_BUFFER_BYTES)
        var total = 0L
        when (schema.format) {
            StreamFormat.SSE -> {
                val decoder = SseDecoder()
                while (true) {
                    val count = readStreamChunk(input, buffer, cancellation)
                    if (count < 0) break
                    total = addStreamBytes(total, count)
                    decoder.push(buffer.copyOf(count)).forEach { event ->
                        applyStreamRules(schema, event.event, event.data, accumulator)
                    }
                }
                decoder.finish().forEach { event ->
                    applyStreamRules(schema, event.event, event.data, accumulator)
                }
            }

            StreamFormat.NDJSON -> {
                val decoder = NdjsonDecoder()
                while (true) {
                    val count = readStreamChunk(input, buffer, cancellation)
                    if (count < 0) break
                    total = addStreamBytes(total, count)
                    decoder.push(buffer.copyOf(count)).forEach { frame ->
                        applyStreamRules(schema, null, frame.data, accumulator)
                    }
                }
                decoder.finish().forEach { frame ->
                    applyStreamRules(schema, null, frame.data, accumulator)
                }
            }

            StreamFormat.JSON_CHUNKS -> {
                val decoder = JsonChunksDecoder()
                while (true) {
                    val count = readStreamChunk(input, buffer, cancellation)
                    if (count < 0) break
                    total = addStreamBytes(total, count)
                    decoder.push(buffer.copyOf(count)).forEach { json ->
                        applyStreamRules(schema, null, json, accumulator)
                    }
                }
                decoder.finish().forEach { json ->
                    applyStreamRules(schema, null, json, accumulator)
                }
            }
        }
    }

    private fun readStreamChunk(
        input: InputStream,
        buffer: ByteArray,
        cancellation: AdvancedCancellationToken,
    ): Int {
        cancellation.throwIfCancelled()
        return input.read(buffer)
    }

    private fun addStreamBytes(total: Long, addition: Int): Long {
        if (addition < 0 || total > MAX_STREAM_RESPONSE_BYTES - addition) {
            throw AdvancedAudioExecutionException.StreamTooLarge
        }
        return total + addition
    }

    private fun applyStreamRules(
        schema: StreamResponse,
        eventName: String?,
        data: String,
        accumulator: TranscriptAccumulator,
    ) {
        schema.rules.forEach { rule ->
            if (rule.event != null && rule.event != eventName) return@forEach
            val value = rule.path?.let { path ->
                try {
                    extractJsonPath(data.toByteArray(Charsets.UTF_8), path)
                } catch (_: JsonPathEvaluationException.NoMatch) {
                    // Mixed event streams normally omit a rule's field in
                    // unrelated frames, so an absent path is not a failure.
                    return@forEach
                }
            }
            if (rule.equals != null && value != rule.equals) return@forEach
            val action = when (rule.action) {
                StreamAction.IGNORE -> TranscriptAction.Ignore
                StreamAction.APPEND_DELTA -> TranscriptAction.AppendDelta(value.orEmpty())
                StreamAction.REPLACE_PARTIAL -> TranscriptAction.ReplacePartial(value.orEmpty())
                StreamAction.COMMIT_SEGMENT -> TranscriptAction.CommitSegment(value.orEmpty())
                StreamAction.SET_FINAL_TEXT -> TranscriptAction.SetFinalText(value.orEmpty())
                StreamAction.COMPLETE -> TranscriptAction.Complete
                StreamAction.FAIL -> TranscriptAction.Fail(value ?: "server stream failure")
            }
            try {
                accumulator.apply(action)
            } catch (error: TranscriptAccumulatorException) {
                throw AdvancedAudioExecutionException.StreamFailure(error)
            }
        }
    }

    private fun executeRetryableStage(
        stage: HttpStage,
        renderer: WorkflowStageRenderer,
        audio: WorkflowAudio,
        cancellation: AdvancedCancellationToken,
        retry: RetryConfig,
        captures: ResponseCaptureCollector,
        captureResponse: Boolean = true,
        retryReadFailure: Boolean = false,
        deadlineNanos: Long? = null,
        pollTimeoutMs: Long? = null,
    ): HttpResponseData {
        val retries = retryCount(retry)
        var attempt = 0
        while (true) {
            ensureDeadline(deadlineNanos, pollTimeoutMs)
            try {
                val callTimeoutMillis = deadlineNanos?.let { deadline ->
                    val remaining = remainingMillisUntil(deadline)
                    if (remaining <= 0L) {
                        throw AdvancedAudioExecutionException.PollTimeout(requireNotNull(pollTimeoutMs))
                    }
                    remaining
                }
                val response = executeStage(
                    stage = stage,
                    renderer = renderer,
                    audio = audio,
                    cancellation = cancellation,
                    captures = captures,
                    captureResponse = captureResponse,
                    callTimeoutMillis = callTimeoutMillis,
                )
                ensureDeadline(deadlineNanos, pollTimeoutMs)
                return response
            } catch (error: Throwable) {
                // OkHttp turns a per-call deadline into an I/O failure.  Do
                // not expose that as a retryable network error or let a
                // disabled retry policy bypass the workflow poll timeout.
                // An explicit user cancellation still takes precedence.
                if (deadlineNanos != null && !cancellation.isCancelled() && System.nanoTime() >= deadlineNanos) {
                    throw AdvancedAudioExecutionException.PollTimeout(requireNotNull(pollTimeoutMs))
                }
                if (!isRetryable(error, retryReadFailure) || attempt >= retries) throw error
                attempt += 1
                val delay = deadlineNanos?.let { deadline ->
                    val remaining = remainingMillisUntil(deadline)
                    if (remaining <= 0L) throw AdvancedAudioExecutionException.PollTimeout(requireNotNull(pollTimeoutMs))
                    min(retry.delayMillis(attempt), remaining)
                } ?: retry.delayMillis(attempt)
                waitCancellable(delay, cancellation)
            }
        }
    }

    private fun ensurePollDeadline(deadlineNanos: Long, timeoutMs: Long) {
        if (System.nanoTime() >= deadlineNanos) throw AdvancedAudioExecutionException.PollTimeout(timeoutMs)
    }

    private fun ensureDeadline(deadlineNanos: Long?, pollTimeoutMs: Long?) {
        if (deadlineNanos != null && System.nanoTime() >= deadlineNanos) {
            throw AdvancedAudioExecutionException.PollTimeout(requireNotNull(pollTimeoutMs))
        }
    }

    private fun remainingMillisUntil(deadlineNanos: Long): Long {
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) return 0L
        return (remainingNanos + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND
    }

    private fun executeStage(
        stage: HttpStage,
        renderer: WorkflowStageRenderer,
        audio: WorkflowAudio,
        cancellation: AdvancedCancellationToken,
        captures: ResponseCaptureCollector,
        captureResponse: Boolean,
        callTimeoutMillis: Long? = null,
    ): HttpResponseData {
        cancellation.throwIfCancelled()
        val response = transport.executeAndRead(
            renderer.render(stage, audio, captures.snapshot()),
            cancellation,
            callTimeoutMillis = callTimeoutMillis,
        )
        if (response.statusCode !in stage.acceptedStatuses) {
            throw AdvancedAudioExecutionException.UnexpectedStatus(response.statusCode)
        }
        if (captureResponse) storeCaptures(stage, response, captures)
        return response
    }

    private fun storeCaptures(
        stage: HttpStage,
        response: HttpResponseData,
        captures: ResponseCaptureCollector,
    ) {
        val extracted = stage.captures.map { capture ->
            capture.id to extract(response, capture.from)
        }
        captures.putStage(extracted)
    }

    private fun extract(response: HttpResponseData, extractor: ResponseExtractor): String = try {
        when (extractor) {
            is ResponseExtractor.JsonPath -> ResponseExtraction.jsonPath(
                response,
                extractor.path,
                JsonPathScalarEvaluator(::extractJsonPath),
            )

            is ResponseExtractor.Header -> ResponseExtraction.header(response, extractor.name)
            ResponseExtractor.PlainBody -> ResponseExtraction.plainBody(response)
            ResponseExtractor.Status -> ResponseExtraction.status(response)
        }
    } catch (error: ResponseExtractionException) {
        throw AdvancedAudioExecutionException.Extraction(error)
    } catch (error: JsonPathEvaluationException) {
        throw AdvancedAudioExecutionException.Extraction(error)
    }

    private fun extractJsonPath(body: ByteArray, path: String): String = try {
        JsonPath.extractOne(body, path)
    } catch (error: JsonPathException) {
        throw when (error) {
            JsonPathException.InvalidPath -> JsonPathEvaluationException.InvalidPath
            JsonPathException.InvalidRegex -> JsonPathEvaluationException.InvalidRegex
            JsonPathException.InvalidJson -> JsonPathEvaluationException.InvalidJson
            JsonPathException.NoMatch -> JsonPathEvaluationException.NoMatch
            is JsonPathException.MultipleMatches -> JsonPathEvaluationException.MultipleMatches(error.count)
            is JsonPathException.InvalidType -> JsonPathEvaluationException.InvalidType(error.kind)
        }
    }

    private fun pollState(poll: PollStage, response: HttpResponseData): PollState = when {
        matchesConditions(poll.failure, response) -> PollState.FAILURE
        matchesConditions(poll.success, response) -> PollState.SUCCESS
        matchesConditions(poll.pending, response) -> PollState.PENDING
        else -> throw AdvancedAudioExecutionException.PollUnexpectedState
    }

    private fun matchesConditions(conditions: List<PollCondition>, response: HttpResponseData): Boolean =
        conditions.isNotEmpty() && conditions.all { condition -> conditionMatches(condition, response) }

    private fun conditionMatches(condition: PollCondition, response: HttpResponseData): Boolean = when (condition.operator) {
        // A missing field is meaningful only for these three operators. For
        // comparisons and boolean tests it remains an extraction failure,
        // matching the workflow contract instead of silently treating it as a
        // false/true comparison result.
        PollOperator.EXISTS -> extractOptional(condition, response) is ConditionValue.Present
        PollOperator.NOT_EXISTS -> extractOptional(condition, response) is ConditionValue.Missing
        PollOperator.IN -> (extractOptional(condition, response) as? ConditionValue.Present)?.value in condition.values
        PollOperator.EQ -> extract(response, condition.from) == condition.value.orEmpty()
        PollOperator.NE -> extract(response, condition.from) != condition.value.orEmpty()
        PollOperator.IS_TRUE -> extract(response, condition.from).equals("true", ignoreCase = true)
        PollOperator.IS_FALSE -> extract(response, condition.from).equals("false", ignoreCase = true)
    }

    private fun extractOptional(condition: PollCondition, response: HttpResponseData): ConditionValue = try {
        ConditionValue.Present(extract(response, condition.from))
    } catch (error: AdvancedAudioExecutionException.Extraction) {
        if (error.isMissing) ConditionValue.Missing else throw error
    }

    private fun retryCount(retry: RetryConfig): Int = if (retry.enabled) retry.maxRetries.coerceAtLeast(0) else 0

    private fun isRetryable(error: Throwable, retryReadFailure: Boolean): Boolean = when (error) {
        is HttpTransportException.NetworkFailure -> true
        is AdvancedAudioExecutionException.UnexpectedStatus -> {
            error.statusCode == 408 || error.statusCode == 429 || error.statusCode in 500..599
        }

        is HttpTransportException.ResponseReadFailure -> retryReadFailure
        else -> false
    }

    private fun isStreamRetryable(error: Throwable): Boolean =
        isRetryable(error, retryReadFailure = true)

    private fun waitCancellable(millis: Long, cancellation: AdvancedCancellationToken) {
        var remaining = millis.coerceAtLeast(0L)
        while (remaining > 0L) {
            cancellation.throwIfCancelled()
            val interval = min(remaining, CANCELLABLE_SLEEP_SLICE_MILLIS)
            Thread.sleep(interval)
            remaining -= interval
        }
        cancellation.throwIfCancelled()
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

    private fun stagesForHeaderPreflight(recognition: Recognition): List<HttpStage> = when (recognition) {
        is RequestRecognition -> listOf(recognition.request)
        is RequestStreamRecognition -> listOf(recognition.request)
        is AsyncPollRecognition -> buildList {
            recognition.prepare?.let(::add)
            add(recognition.submit)
            recognition.poll?.let { poll -> add(poll.request) }
            addAll(recognition.resultSteps)
        }

        is RealtimeSessionRecognition -> emptyList()
    }

    private fun runtimeValues(): RuntimeTemplateValues {
        val now = clock.instant()
        return RuntimeTemplateValues(
            uuid = UUID.randomUUID().toString(),
            unixSeconds = now.epochSecond.toString(),
            unixMillis = now.toEpochMilli().toString(),
        )
    }

    private sealed interface ConditionValue {
        data class Present(val value: String) : ConditionValue
        data object Missing : ConditionValue
    }

    private enum class PollState {
        PENDING,
        SUCCESS,
        FAILURE,
    }

    private data class CompletedRequest(
        val text: String,
        val statusCode: Int,
    )

    private companion object {
        const val PHASE_REMOTE_UPLOAD = "[remote-audio] upload"
        const val PHASE_REQUEST = "[advanced-audio] request"
        const val PHASE_STREAM = "[asr-stream] stream"
        const val PHASE_PREPARE = "[advanced-audio] prepare"
        const val PHASE_SUBMIT = "[advanced-audio] submit"
        const val PHASE_POLL = "[advanced-audio] poll"
        const val PHASE_RESULT = "[advanced-audio] result"
        const val GENERIC_FAILURE_MESSAGE = "Advanced Audio API workflow failed"
        const val STREAM_READ_BUFFER_BYTES = 16 * 1024
        const val MAX_STREAM_RESPONSE_BYTES = 32L * 1024L * 1024L
        const val CANCELLABLE_SLEEP_SLICE_MILLIS = 50L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAX_TIMEOUT_MILLIS = 24L * 60L * 60L * 1_000L

        fun safeTransportMessage(error: HttpTransportException): String = when (error) {
            is HttpTransportException.InvalidUrl -> "Advanced Audio API workflow contains an invalid HTTP URL"
            is HttpTransportException.InvalidRequest -> "Advanced Audio API request could not be constructed"
            is HttpTransportException.InvalidMediaType -> "Advanced Audio API workflow contains an invalid media type"
            is HttpTransportException.AudioFileUnavailable -> "Advanced Audio API audio file is unavailable"
            is HttpTransportException.SignerRequiresMaterializedBody ->
                "Advanced Audio API signer cannot be used with a streaming audio body"

            is HttpTransportException.SigningFailed -> "Advanced Audio API request signing failed"
            is HttpTransportException.NetworkFailure -> "Advanced Audio API request failed"
            is HttpTransportException.ResponseReadFailure -> "Advanced Audio API response could not be read"
            is HttpTransportException.ResponseTooLarge -> "Advanced Audio API response is too large"
            is HttpTransportException.ResponseAlreadyConsumed -> "Advanced Audio API response was already consumed"
            is HttpTransportException.Cancelled -> "Advanced Audio API request was cancelled"
        }
    }
}

/** Safe, user-facing execution failures that retain no rendered request data. */
sealed class AdvancedAudioExecutionException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {
    open val statusCode: Int? = null

    data object Disabled : AdvancedAudioExecutionException("Advanced Audio API is disabled")
    data object MissingWorkflow : AdvancedAudioExecutionException("Advanced Audio API is enabled but has no workflow")
    data object InvalidWorkflow : AdvancedAudioExecutionException("Advanced Audio API workflow JSON is invalid")
    data object InvalidConfiguration : AdvancedAudioExecutionException("Advanced Audio API configuration is invalid")
    data class UnexpectedStatus(override val statusCode: Int) :
        AdvancedAudioExecutionException("Advanced Audio API request returned HTTP $statusCode")

    data class Extraction(private val source: Throwable) :
        AdvancedAudioExecutionException("Advanced Audio API response could not be extracted", source) {
        val isMissing: Boolean
            get() = source is JsonPathEvaluationException.NoMatch || source is ResponseExtractionException.HeaderMissing
    }

    data class StreamFailure(private val source: Throwable) :
        AdvancedAudioExecutionException("Advanced Audio API stream could not be completed", source)

    data object StreamTooLarge : AdvancedAudioExecutionException("Advanced Audio API stream response is too large")
    data object PollFailure : AdvancedAudioExecutionException("Asynchronous recognition task failed")
    data object PollUnexpectedState :
        AdvancedAudioExecutionException("Asynchronous recognition task returned an unexpected state")

    data class PollTimeout(val timeoutMs: Long) :
        AdvancedAudioExecutionException("Asynchronous recognition task timed out after $timeoutMs ms")
}
