package com.joeykot.dictate.network

import com.joeykot.dictate.R
import com.joeykot.dictate.i18n.AppStrings
import com.joeykot.dictate.model.PostProcessingConfig
import com.joeykot.dictate.model.PromptConfig
import com.joeykot.dictate.network.TranscriptionClient.FailureKind
import com.joeykot.dictate.network.TranscriptionClient.Result
import com.joeykot.dictate.util.Diagnostics
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException

class PostProcessingClient(private val diagnostics: Diagnostics) {
    private enum class Phase { CONNECTING, WRITING, READING }

    private val activeConnection = AtomicReference<HttpURLConnection?>()
    private val cancellationVersion = AtomicLong()

    fun execute(
        config: PostProcessingConfig,
        apiKey: String,
        prompt: PromptConfig,
        input: String,
        shouldContinue: () -> Boolean,
    ): Result {
        val version = cancellationVersion.get()
        fun cancelled(): Boolean = version != cancellationVersion.get() || !shouldContinue()
        if (cancelled()) return Result.Cancelled
        val startedAt = System.nanoTime()
        var phase = Phase.CONNECTING
        val writeTimedOut = AtomicBoolean(false)
        var connection: HttpURLConnection? = null
        var cancellationWatch: java.util.concurrent.ScheduledFuture<*>? = null
        return try {
            val request = PostProcessingRequest.build(config, apiKey, prompt, input)
            val body = request.body.toString().toByteArray(Charsets.UTF_8)
            val opened = (URL(request.endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doInput = true
                doOutput = true
                useCaches = false
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json, text/event-stream")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
                setFixedLengthStreamingMode(body.size)
            }
            connection = opened
            activeConnection.set(opened)
            if (cancelled()) return Result.Cancelled
            cancellationWatch = watchdog.scheduleWithFixedDelay(
                { if (cancelled()) opened.disconnect() }, 100L, 100L, TimeUnit.MILLISECONDS,
            )
            opened.connect()
            if (cancelled()) return Result.Cancelled
            phase = Phase.WRITING
            val timeout = watchdog.schedule(
                { writeTimedOut.set(true); opened.disconnect() },
                WRITE_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS,
            )
            try {
                opened.outputStream.use { output -> output.write(body) }
            } finally {
                timeout.cancel(false)
            }
            if (cancelled()) return Result.Cancelled
            if (writeTimedOut.get()) return failure(FailureKind.WRITE_TIMEOUT, AppStrings.get(R.string.val_post_write_timeout, "Timed out sending the post-processing request"), true, startedAt, apiKey)
            phase = Phase.READING
            val status = opened.responseCode
            val response = readResponse(opened, status) {
                if (cancelled()) throw RequestCancelledException()
            }
            if (cancelled()) return Result.Cancelled
            val elapsed = elapsedMillis(startedAt)
            if (status !in 200..299) {
                val summary = diagnostics.sanitize(response, 2_000, listOf(apiKey))
                val retryable = isRetryableHttpStatus(status)
                diagnostics.error("post-http", "status=$status elapsed=${elapsed}ms retryable=$retryable response=$summary")
                Result.Failure(FailureKind.HTTP, AppStrings.get(R.string.val_post_http, "The post-processing service returned HTTP %1\$d", status), retryable, status, elapsed, summary)
            } else {
                val isEventStream = opened.contentType?.startsWith("text/event-stream", ignoreCase = true) == true ||
                    response.trimStart().let { it.startsWith("data:") || it.startsWith("event:") || it.startsWith(":") }
                val text = if (isEventStream) PostProcessingStream.parseText(config.provider, response)
                    else PostProcessingRequest.parseText(config.provider, response)
                diagnostics.info("post-http", "status=$status elapsed=${elapsed}ms")
                Result.Success(text, status, elapsed)
            }
        } catch (_: RequestCancelledException) {
            Result.Cancelled
        } catch (error: Exception) {
            if (cancelled()) {
                Result.Cancelled
            } else if (writeTimedOut.get()) {
                failure(FailureKind.WRITE_TIMEOUT, AppStrings.get(R.string.val_post_write_timeout, "Timed out sending the post-processing request"), true, startedAt, apiKey)
            } else {
                val (kind, message, retryable) = when (error) {
                    is IllegalArgumentException -> Triple(FailureKind.CONFIGURATION, error.message ?: AppStrings.get(R.string.val_post_config_invalid, "Invalid post-processing configuration"), false)
                    is PostProcessingStream.ServiceException -> Triple(FailureKind.SERVICE, error.message ?: AppStrings.get(R.string.val_post_stream_error, "Post-processing service streaming error"), error.retryable)
                    is PostProcessingRequest.InvalidResponseException -> Triple(FailureKind.INVALID_RESPONSE, error.message ?: AppStrings.get(R.string.val_post_response_invalid, "Invalid post-processing response"), false)
                    is SocketTimeoutException -> when (phase) {
                        Phase.CONNECTING -> Triple(FailureKind.CONNECT_TIMEOUT, AppStrings.get(R.string.val_post_connect_timeout, "Timed out connecting to the post-processing service"), true)
                        Phase.WRITING -> Triple(FailureKind.WRITE_TIMEOUT, AppStrings.get(R.string.val_post_write_timeout, "Timed out sending the post-processing request"), true)
                        Phase.READING -> Triple(FailureKind.READ_TIMEOUT, AppStrings.get(R.string.val_post_read_timeout, "Timed out waiting for the post-processing response"), true)
                    }
                    is UnknownHostException -> Triple(FailureKind.DNS, AppStrings.get(R.string.val_post_dns, "Cannot resolve the post-processing service hostname"), true)
                    is SSLException -> Triple(FailureKind.TLS, AppStrings.get(R.string.val_tls_error, "TLS connection failed: %1\$s", error.message.toString()), true)
                    is ConnectException -> Triple(FailureKind.CONNECTION, AppStrings.get(R.string.val_post_connection, "Cannot connect to the post-processing service"), true)
                    is NoRouteToHostException -> Triple(FailureKind.CONNECTION, AppStrings.get(R.string.val_network_unreachable, "Network unreachable"), true)
                    is IOException -> Triple(FailureKind.IO, AppStrings.get(R.string.val_post_io, "Post-processing network I/O failed: %1\$s", error.message.toString()), true)
                    else -> Triple(FailureKind.IO, AppStrings.get(R.string.val_post_request_error, "Post-processing request failed: %1\$s", error.message.toString()), false)
                }
                failure(kind, message, retryable, startedAt, apiKey)
            }
        } finally {
            cancellationWatch?.cancel(false)
            activeConnection.compareAndSet(connection, null)
            connection?.disconnect()
        }
    }

    fun cancel() {
        cancellationVersion.incrementAndGet()
        activeConnection.getAndSet(null)?.disconnect()
    }

    private fun readResponse(connection: HttpURLConnection, status: Int, checkCancelled: () -> Unit): String {
        val stream = (if (status in 200..299) connection.inputStream else connection.errorStream) ?: return ""
        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_RESPONSE_BYTES) {
                    throw PostProcessingRequest.InvalidResponseException(AppStrings.get(R.string.val_post_response_size, "The post-processing response exceeds the size limit"))
                }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    private fun failure(kind: FailureKind, message: String, retryable: Boolean, startedAt: Long, apiKey: String): Result.Failure {
        val elapsed = elapsedMillis(startedAt)
        val safeMessage = diagnostics.sanitize(message, 500, listOf(apiKey))
        diagnostics.error("post-http", "kind=$kind elapsed=${elapsed}ms retryable=$retryable $safeMessage")
        return Result.Failure(kind, safeMessage, retryable, elapsedMillis = elapsed)
    }

    private fun elapsedMillis(startedAt: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
    private class RequestCancelledException : IOException()

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val WRITE_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 60_000
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "dictate-post-http-watchdog").apply { isDaemon = true }
        }
    }
}
