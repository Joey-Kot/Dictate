package com.joeykot.dictate.network

import com.joeykot.dictate.R
import com.joeykot.dictate.i18n.AppStrings
import com.joeykot.dictate.util.Diagnostics
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException

class TranscriptionClient(private val diagnostics: Diagnostics) {
    data class Request(
        val endpoint: String,
        val apiKey: String,
        val model: String,
        val additionalFields: LinkedHashMap<String, String>,
        val audioFile: File,
        val mimeType: String,
        val additionalJson: String? = null,
    )

    enum class FailureKind {
        CONFIGURATION,
        CONNECTION,
        DNS,
        TLS,
        CONNECT_TIMEOUT,
        WRITE_TIMEOUT,
        READ_TIMEOUT,
        HTTP,
        SERVICE,
        INVALID_RESPONSE,
        IO,
    }

    sealed interface Result {
        data class Success(
            val text: String,
            val statusCode: Int,
            val elapsedMillis: Long,
        ) : Result

        data class Failure(
            val kind: FailureKind,
            val message: String,
            val retryable: Boolean,
            val statusCode: Int? = null,
            val elapsedMillis: Long,
            val serverSummary: String = "",
        ) : Result

        data object Cancelled : Result
    }

    /**
     * Identifies one request within a logical parent recording job.  A
     * segmented upload gives every segment its own identifier, so finishing
     * one request cannot remove another request's connection or cancellation
     * state.
     */
    data class OperationId(
        val parentJobId: Long,
        val requestId: Long,
    )

    /**
     * Keeps a parent-job registration alive while a caller schedules several
     * independently cancellable requests.  Keep this open until every
     * started operation has drained.
     */
    class JobSession internal constructor(
        private val owner: TranscriptionClient,
        val parentJobId: Long,
        private val state: ParentJobState,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val batchAborted = AtomicBoolean(false)

        fun isCancellationRequested(): Boolean = state.cancelled.get()

        fun newOperationId(): OperationId {
            check(!closed.get()) { "The transcription job session is closed" }
            return OperationId(parentJobId, owner.nextOperationId.incrementAndGet())
        }

        fun transcribe(request: Request): Result = transcribe(newOperationId(), request)

        fun transcribe(operationId: OperationId, request: Request): Result {
            check(!closed.get()) { "The transcription job session is closed" }
            require(operationId.parentJobId == parentJobId) {
                "The operation belongs to a different transcription job"
            }
            return owner.transcribe(state, operationId, request, batchAborted)
        }

        /**
         * Stops currently open HTTP connections and prevents a sibling that
         * has not registered its connection yet from starting one.  This
         * batch-local abort intentionally does not change
         * [isCancellationRequested], because the first segment failure must
         * remain reportable unless the parent job itself was cancelled.
         */
        fun cancelInFlightOperations() {
            batchAborted.set(true)
            owner.disconnectActiveOperations(state)
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) owner.releaseJobSession(parentJobId, state)
        }
    }

    /**
     * Registers a queued legacy-transcription job before its worker starts.
     * This keeps a cancellation that wins the gap before [openJob] attached
     * to the intended job, without retaining a cancellation marker for an
     * unrelated job that never starts.
     *
     * Close the registration when its worker finishes or is discarded before
     * it starts. A closed registration remains usable by a worker that had
     * already captured it, so a cancellation racing with worker dispatch is
     * still observed when that worker opens its session.
     */
    class JobRegistration internal constructor(
        internal val owner: TranscriptionClient,
        val parentJobId: Long,
    ) : AutoCloseable {
        private val lock = Any()
        private val closed = AtomicBoolean(false)
        private var cancellationRequested = false
        private var attachedState: ParentJobState? = null

        /** Returns whether cancellation had already been requested. */
        internal fun attach(state: ParentJobState): Boolean = synchronized(lock) {
            attachedState = state
            cancellationRequested
        }

        /**
         * Marks the queued job as cancelled and returns its already attached
         * parent state, if the worker has reached [openJob].
         */
        internal fun requestCancellation(): ParentJobState? = synchronized(lock) {
            cancellationRequested = true
            attachedState
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) owner.unregisterJob(this)
        }
    }

    private enum class Phase {
        CONNECTING,
        WRITING,
        READING,
    }

    internal class ParentJobState {
        val cancelled = AtomicBoolean(false)
        val openSessions = AtomicInteger(0)
        val activeOperations = AtomicInteger(0)
        val activeConnections = ConcurrentHashMap<Long, HttpURLConnection>()
    }

    private val parentJobs = ConcurrentHashMap<Long, ParentJobState>()
    /**
     * Jobs whose controller worker has been queued but has not necessarily
     * entered [openJob] yet. Registrations are explicitly released instead of
     * leaving bare cancellation IDs behind indefinitely.
     */
    private val queuedJobs = ConcurrentHashMap<Long, JobRegistration>()
    private val nextOperationId = AtomicLong()
    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dictate-http-watchdog").apply { isDaemon = true }
    }

    /**
     * Registers a worker before it is submitted so [cancel] can safely win
     * the interval before that worker enters [openJob].
     */
    fun registerJob(parentJobId: Long): JobRegistration {
        val registration = JobRegistration(this, parentJobId)
        check(queuedJobs.putIfAbsent(parentJobId, registration) == null) {
            "A transcription job is already queued for $parentJobId"
        }
        return registration
    }

    /**
     * Opens a parent-job session for a batch of independently cancellable
     * requests. The caller must retain and close it only after every started
     * request has completed, including requests being drained after an error.
     *
     * Callers that queue work before opening the session should use
     * [registerJob] and [openJob] with its returned registration.
     */
    fun openJob(parentJobId: Long): JobSession = openJob(parentJobId, registration = null)

    /** Opens a session for work previously registered with [registerJob]. */
    fun openJob(registration: JobRegistration): JobSession {
        check(registration.owner === this) { "The transcription job registration belongs to a different client" }
        return openJob(registration.parentJobId, registration)
    }

    private fun openJob(parentJobId: Long, registration: JobRegistration?): JobSession {
        val state = parentJobs.compute(parentJobId) { _, existing ->
            (existing ?: ParentJobState()).also { it.openSessions.incrementAndGet() }
        } ?: error("Unable to create the transcription job session")
        if (registration?.attach(state) == true) cancelParentJob(state)
        registration?.let { queuedJobs.remove(parentJobId, it) }
        return JobSession(this, parentJobId, state)
    }

    /** Preserves the single-file call path used by non-segmented recording. */
    fun transcribe(jobId: Long, request: Request): Result {
        val session = openJob(jobId)
        return try {
            session.transcribe(request)
        } finally {
            session.close()
        }
    }

    /** Uses a pre-registered session for a queued single-file request. */
    fun transcribe(registration: JobRegistration, request: Request): Result {
        val session = openJob(registration)
        return try {
            session.transcribe(request)
        } finally {
            session.close()
        }
    }

    private fun transcribe(
        state: ParentJobState,
        operation: OperationId,
        request: Request,
        batchAborted: AtomicBoolean,
    ): Result {
        val startedAt = System.nanoTime()
        var phase = Phase.CONNECTING
        val writeTimedOut = AtomicBoolean(false)
        var connection: HttpURLConnection? = null
        state.activeOperations.incrementAndGet()
        fun isCancelled(): Boolean = state.cancelled.get() || batchAborted.get()

        return try {
            if (isCancelled()) return Result.Cancelled
            validateRequest(request)
            val boundary = "DictateBoundary${operation.parentJobId.toString(16)}${operation.requestId.toString(16)}${System.nanoTime().toString(16)}"
            val body = MultipartBody(boundary, request)
            val openedConnection = (URL(request.endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doInput = true
                doOutput = true
                useCaches = false
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer ${request.apiKey}")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setFixedLengthStreamingMode(body.contentLength)
            }
            connection = openedConnection
            state.activeConnections[operation.requestId] = openedConnection
            if (isCancelled()) {
                openedConnection.disconnect()
                return Result.Cancelled
            }

            openedConnection.connect()
            phase = Phase.WRITING
            val timeoutFuture = watchdog.schedule(
                {
                    writeTimedOut.set(true)
                    state.activeConnections[operation.requestId]?.disconnect()
                },
                WRITE_TIMEOUT_MS.toLong(),
                TimeUnit.MILLISECONDS,
            )
            try {
                openedConnection.outputStream.buffered(FILE_BUFFER_SIZE).use { output ->
                    body.writeTo(output) {
                        if (isCancelled()) throw JobCancelledException()
                    }
                }
            } finally {
                timeoutFuture.cancel(false)
            }

            if (isCancelled()) return Result.Cancelled
            if (writeTimedOut.get()) {
                return failure(
                    FailureKind.WRITE_TIMEOUT,
                    AppStrings.get(R.string.val_upload_timeout, "Audio upload timed out"),
                    true,
                    startedAt,
                    request.apiKey,
                )
            }

            phase = Phase.READING
            val status = openedConnection.responseCode
            val responseBody = readResponseBody(openedConnection, status)
            val elapsed = elapsedMillis(startedAt)
            if (isCancelled()) return Result.Cancelled

            if (status !in 200..299) {
                val summary = diagnostics.sanitize(
                    responseBody,
                    MAX_SERVER_SUMMARY,
                    listOf(request.apiKey),
                )
                val retryable = isRetryableHttpStatus(status)
                diagnostics.error(
                    "http",
                    "job=${operation.parentJobId} request=${operation.requestId} status=$status elapsed=${elapsed}ms retryable=$retryable response=$summary",
                )
                Result.Failure(
                    kind = FailureKind.HTTP,
                    message = AppStrings.get(R.string.val_transcription_http, "The transcription service returned HTTP %1\$d", status),
                    retryable = retryable,
                    statusCode = status,
                    elapsedMillis = elapsed,
                    serverSummary = summary,
                )
            } else {
                val text = parseText(responseBody)
                diagnostics.info("http", "job=${operation.parentJobId} request=${operation.requestId} status=$status elapsed=${elapsed}ms")
                Result.Success(text, status, elapsed)
            }
        } catch (_: JobCancelledException) {
            Result.Cancelled
        } catch (error: InvalidResponseException) {
            if (isCancelled()) Result.Cancelled else failure(
                FailureKind.INVALID_RESPONSE,
                error.message ?: AppStrings.get(R.string.val_response_text_invalid, "The response has no valid text field"),
                false,
                startedAt,
                request.apiKey,
            )
        } catch (error: IllegalArgumentException) {
            if (isCancelled()) Result.Cancelled else failure(
                FailureKind.CONFIGURATION,
                error.message ?: AppStrings.get(R.string.val_request_config_invalid, "Invalid request configuration"),
                false,
                startedAt,
                request.apiKey,
            )
        } catch (error: SocketTimeoutException) {
            if (isCancelled()) {
                Result.Cancelled
            } else {
                val kind = when (phase) {
                    Phase.CONNECTING -> FailureKind.CONNECT_TIMEOUT
                    Phase.WRITING -> FailureKind.WRITE_TIMEOUT
                    Phase.READING -> FailureKind.READ_TIMEOUT
                }
                failure(kind, timeoutMessage(kind), true, startedAt, request.apiKey)
            }
        } catch (error: UnknownHostException) {
            if (isCancelled()) Result.Cancelled else failure(FailureKind.DNS, AppStrings.get(R.string.val_transcription_dns, "Cannot resolve the transcription service hostname"), true, startedAt, request.apiKey)
        } catch (error: SSLException) {
            if (isCancelled()) Result.Cancelled else failure(FailureKind.TLS, AppStrings.get(R.string.val_tls_error, "TLS connection failed: %1\$s", safeMessage(error)), true, startedAt, request.apiKey)
        } catch (error: ConnectException) {
            if (isCancelled()) Result.Cancelled else failure(FailureKind.CONNECTION, AppStrings.get(R.string.val_transcription_connection, "Cannot connect to the transcription service"), true, startedAt, request.apiKey)
        } catch (error: NoRouteToHostException) {
            if (isCancelled()) Result.Cancelled else failure(FailureKind.CONNECTION, AppStrings.get(R.string.val_network_unreachable, "Network unreachable"), true, startedAt, request.apiKey)
        } catch (error: FileNotFoundException) {
            if (isCancelled()) Result.Cancelled else failure(
                FailureKind.CONFIGURATION,
                AppStrings.get(R.string.val_audio_unreadable, "The audio file became unreadable during the request"),
                false,
                startedAt,
                request.apiKey,
            )
        } catch (error: SocketException) {
            if (isCancelled()) {
                Result.Cancelled
            } else if (writeTimedOut.get()) {
                failure(FailureKind.WRITE_TIMEOUT, AppStrings.get(R.string.val_upload_timeout, "Audio upload timed out"), true, startedAt, request.apiKey)
            } else {
                failure(
                    FailureKind.CONNECTION,
                    AppStrings.get(R.string.val_connection_interrupted, "Network connection interrupted: %1\$s", safeMessage(error)),
                    true,
                    startedAt,
                    request.apiKey,
                )
            }
        } catch (error: IOException) {
            if (isCancelled()) {
                Result.Cancelled
            } else {
                failure(
                    FailureKind.IO,
                    AppStrings.get(R.string.val_network_io, "Network I/O failed: %1\$s", safeMessage(error)),
                    true,
                    startedAt,
                    request.apiKey,
                )
            }
        } catch (error: Exception) {
            if (isCancelled()) {
                Result.Cancelled
            } else {
                failure(
                    FailureKind.IO,
                    AppStrings.get(R.string.val_request_error, "Request failed: %1\$s", safeMessage(error)),
                    false,
                    startedAt,
                    request.apiKey,
                )
            }
        } finally {
            connection?.let { state.activeConnections.remove(operation.requestId, it) }
            connection?.disconnect()
            state.activeOperations.decrementAndGet()
            removeJobStateIfUnused(operation.parentJobId, state)
        }
    }

    fun cancel(jobId: Long) {
        parentJobs[jobId]?.let {
            cancelParentJob(it)
            return
        }

        // A registration serializes this race with openJob(): either it
        // records cancellation before the parent state attaches, or it hands
        // us that attached state to cancel immediately.
        queuedJobs[jobId]?.requestCancellation()?.let(::cancelParentJob)
    }

    private fun cancelParentJob(state: ParentJobState) {
        state.cancelled.set(true)
        disconnectActiveOperations(state)
    }

    private fun unregisterJob(registration: JobRegistration) {
        queuedJobs.remove(registration.parentJobId, registration)
    }

    private fun disconnectActiveOperations(state: ParentJobState) {
        state.activeConnections.values.toList().forEach { connection ->
            runCatching { connection.disconnect() }
        }
    }

    private fun releaseJobSession(parentJobId: Long, state: ParentJobState) {
        state.openSessions.decrementAndGet()
        removeJobStateIfUnused(parentJobId, state)
    }

    private fun removeJobStateIfUnused(parentJobId: Long, state: ParentJobState) {
        // Serialize removal with openJob's compute call. A plain conditional
        // remove could otherwise evict this state just after a new session
        // increments openSessions, losing a concurrent parent cancellation.
        parentJobs.computeIfPresent(parentJobId) { _, current ->
            if (current !== state) {
                current
            } else if (state.openSessions.get() == 0 && state.activeOperations.get() == 0) {
                null
            } else {
                state
            }
        }
    }

    private fun validateRequest(request: Request) {
        require(request.endpoint.startsWith("https://") || request.endpoint.startsWith("http://")) {
            AppStrings.get(R.string.val_transcription_endpoint, "Invalid transcription endpoint")
        }
        require(request.apiKey.isNotBlank()) { AppStrings.get(R.string.val_api_key_empty, "API key cannot be empty") }
        effectiveFields(request)
        require(request.audioFile.isFile && request.audioFile.length() > 0L) { AppStrings.get(R.string.val_audio_missing, "The audio file to upload is missing or empty") }
    }

    private fun readResponseBody(connection: HttpURLConnection, status: Int): String {
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            ?: return ""
        stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                val accepted = minOf(read, MAX_RESPONSE_BYTES - total)
                if (accepted > 0) output.write(buffer, 0, accepted)
                total += read
                if (total >= MAX_RESPONSE_BYTES) break
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }

    private fun parseText(body: String): String {
        val root = try {
            JSONObject(body)
        } catch (_: JSONException) {
            throw InvalidResponseException(AppStrings.get(R.string.val_response_object, "The response is not a valid JSON object"))
        }
        if (!root.has("text")) throw InvalidResponseException(AppStrings.get(R.string.val_response_text_missing, "The response is missing the top-level text field"))
        val value = root.get("text")
        if (value !is String) throw InvalidResponseException(AppStrings.get(R.string.val_response_text_type, "The top-level text field must be a string"))
        if (value.isBlank()) throw InvalidResponseException(AppStrings.get(R.string.val_response_text_empty, "The top-level text field cannot be empty"))
        return value
    }

    private fun failure(
        kind: FailureKind,
        message: String,
        retryable: Boolean,
        startedAt: Long,
        apiKey: String,
    ): Result.Failure {
        val elapsed = elapsedMillis(startedAt)
        val safeMessage = diagnostics.sanitize(message, 500, listOf(apiKey))
        diagnostics.error("http", "kind=$kind elapsed=${elapsed}ms retryable=$retryable $safeMessage")
        return Result.Failure(
            kind = kind,
            message = safeMessage,
            retryable = retryable,
            elapsedMillis = elapsed,
        )
    }

    private fun timeoutMessage(kind: FailureKind): String = when (kind) {
        FailureKind.CONNECT_TIMEOUT -> AppStrings.get(R.string.val_transcription_connect_timeout, "Timed out connecting to the transcription service")
        FailureKind.WRITE_TIMEOUT -> AppStrings.get(R.string.val_upload_timeout, "Audio upload timed out")
        FailureKind.READ_TIMEOUT -> AppStrings.get(R.string.val_transcription_read_timeout, "Timed out waiting for the transcription response")
        else -> AppStrings.get(R.string.val_request_timeout, "Request timed out")
    }

    private fun safeMessage(error: Throwable): String =
        diagnostics.sanitize(error.message ?: error.javaClass.simpleName, 300)

    private fun elapsedMillis(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private class MultipartBody(
        boundary: String,
        private val request: Request,
    ) {
        private val fieldParts: List<ByteArray>
        private val fileHeader: ByteArray
        private val closing: ByteArray

        val contentLength: Long

        init {
            val fields = effectiveFields(request)
            fieldParts = fields.map { (name, value) ->
                ("--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"${AdditionalParameters.multipartFieldName(name)}\"\r\n\r\n" +
                    value + "\r\n").toByteArray(Charsets.UTF_8)
            }
            val safeExtension = request.audioFile.extension.ifBlank { "bin" }
            fileHeader = ("--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"audio.$safeExtension\"\r\n" +
                "Content-Type: ${request.mimeType}\r\n\r\n").toByteArray(Charsets.UTF_8)
            closing = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
            contentLength = fieldParts.sumOf { it.size.toLong() } +
                fileHeader.size + request.audioFile.length() + closing.size
        }

        fun writeTo(output: java.io.OutputStream, checkCancelled: () -> Unit) {
            fieldParts.forEach {
                checkCancelled()
                output.write(it)
            }
            output.write(fileHeader)
            request.audioFile.inputStream().buffered(FILE_BUFFER_SIZE).use { input ->
                val buffer = ByteArray(FILE_BUFFER_SIZE)
                while (true) {
                    checkCancelled()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
            checkCancelled()
            output.write(closing)
            output.flush()
        }
    }

    private class InvalidResponseException(message: String) : Exception(message)
    private class JobCancelledException : IOException()

    private companion object {
        fun effectiveFields(request: Request): LinkedHashMap<String, String> =
            request.additionalJson?.let { AdditionalParameters.transcriptionFields(request.model, it) }
                ?: linkedMapOf("model" to request.model).apply {
                    require("file" !in request.additionalFields) { AppStrings.get(R.string.val_additional_file_conflict, "The additional file parameter conflicts with the audio upload") }
                    putAll(request.additionalFields)
                    require(!get("model").isNullOrBlank()) { AppStrings.get(R.string.val_merged_model_empty, "The merged model cannot be empty") }
                }

        const val CONNECT_TIMEOUT_MS = 15_000
        const val WRITE_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 60_000
        const val FILE_BUFFER_SIZE = 16 * 1024
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val MAX_SERVER_SUMMARY = 2_000
    }
}

internal fun isRetryableHttpStatus(status: Int): Boolean =
    status == 408 || status == 429 || status in 500..599
