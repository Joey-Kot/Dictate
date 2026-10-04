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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    private enum class Phase {
        CONNECTING,
        WRITING,
        READING,
    }

    private val activeConnections = ConcurrentHashMap<Long, HttpURLConnection>()
    private val cancelledJobs = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dictate-http-watchdog").apply { isDaemon = true }
    }

    fun transcribe(jobId: Long, request: Request): Result {
        if (cancelledJobs.remove(jobId)) return Result.Cancelled
        val startedAt = System.nanoTime()
        var phase = Phase.CONNECTING
        val writeTimedOut = AtomicBoolean(false)
        var connection: HttpURLConnection? = null

        return try {
            validateRequest(request)
            val boundary = "DictateBoundary${jobId.toString(16)}${System.nanoTime().toString(16)}"
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
            activeConnections[jobId] = openedConnection
            if (jobId in cancelledJobs) {
                openedConnection.disconnect()
                return Result.Cancelled
            }

            openedConnection.connect()
            phase = Phase.WRITING
            val timeoutFuture = watchdog.schedule(
                {
                    writeTimedOut.set(true)
                    activeConnections[jobId]?.disconnect()
                },
                WRITE_TIMEOUT_MS.toLong(),
                TimeUnit.MILLISECONDS,
            )
            try {
                openedConnection.outputStream.buffered(FILE_BUFFER_SIZE).use { output ->
                    body.writeTo(output) {
                        if (jobId in cancelledJobs) throw JobCancelledException()
                    }
                }
            } finally {
                timeoutFuture.cancel(false)
            }

            if (jobId in cancelledJobs) return Result.Cancelled
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

            if (status !in 200..299) {
                val summary = diagnostics.sanitize(
                    responseBody,
                    MAX_SERVER_SUMMARY,
                    listOf(request.apiKey),
                )
                val retryable = isRetryableHttpStatus(status)
                diagnostics.error(
                    "http",
                    "job=$jobId status=$status elapsed=${elapsed}ms retryable=$retryable response=$summary",
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
                diagnostics.info("http", "job=$jobId status=$status elapsed=${elapsed}ms")
                Result.Success(text, status, elapsed)
            }
        } catch (_: JobCancelledException) {
            Result.Cancelled
        } catch (error: InvalidResponseException) {
            failure(
                FailureKind.INVALID_RESPONSE,
                error.message ?: AppStrings.get(R.string.val_response_text_invalid, "The response has no valid text field"),
                false,
                startedAt,
                request.apiKey,
            )
        } catch (error: IllegalArgumentException) {
            failure(
                FailureKind.CONFIGURATION,
                error.message ?: AppStrings.get(R.string.val_request_config_invalid, "Invalid request configuration"),
                false,
                startedAt,
                request.apiKey,
            )
        } catch (error: SocketTimeoutException) {
            val kind = when (phase) {
                Phase.CONNECTING -> FailureKind.CONNECT_TIMEOUT
                Phase.WRITING -> FailureKind.WRITE_TIMEOUT
                Phase.READING -> FailureKind.READ_TIMEOUT
            }
            failure(kind, timeoutMessage(kind), true, startedAt, request.apiKey)
        } catch (error: UnknownHostException) {
            failure(FailureKind.DNS, AppStrings.get(R.string.val_transcription_dns, "Cannot resolve the transcription service hostname"), true, startedAt, request.apiKey)
        } catch (error: SSLException) {
            failure(FailureKind.TLS, AppStrings.get(R.string.val_tls_error, "TLS connection failed: %1\$s", safeMessage(error)), true, startedAt, request.apiKey)
        } catch (error: ConnectException) {
            failure(FailureKind.CONNECTION, AppStrings.get(R.string.val_transcription_connection, "Cannot connect to the transcription service"), true, startedAt, request.apiKey)
        } catch (error: NoRouteToHostException) {
            failure(FailureKind.CONNECTION, AppStrings.get(R.string.val_network_unreachable, "Network unreachable"), true, startedAt, request.apiKey)
        } catch (error: FileNotFoundException) {
            failure(
                FailureKind.CONFIGURATION,
                AppStrings.get(R.string.val_audio_unreadable, "The audio file became unreadable during the request"),
                false,
                startedAt,
                request.apiKey,
            )
        } catch (error: SocketException) {
            if (jobId in cancelledJobs) {
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
            if (jobId in cancelledJobs) {
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
            if (jobId in cancelledJobs) {
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
            activeConnections.remove(jobId)
            connection?.disconnect()
            cancelledJobs.remove(jobId)
        }
    }

    fun cancel(jobId: Long) {
        cancelledJobs.add(jobId)
        activeConnections.remove(jobId)?.disconnect()
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
