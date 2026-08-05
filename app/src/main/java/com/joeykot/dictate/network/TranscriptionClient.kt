package com.joeykot.dictate.network

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
                    "上传音频超时",
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
                    message = "转写服务返回 HTTP $status",
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
                error.message ?: "响应缺少有效 text",
                false,
                startedAt,
                request.apiKey,
            )
        } catch (error: IllegalArgumentException) {
            failure(
                FailureKind.CONFIGURATION,
                error.message ?: "请求配置无效",
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
            failure(FailureKind.DNS, "无法解析转写服务域名", true, startedAt, request.apiKey)
        } catch (error: SSLException) {
            failure(FailureKind.TLS, "TLS 连接失败：${safeMessage(error)}", true, startedAt, request.apiKey)
        } catch (error: ConnectException) {
            failure(FailureKind.CONNECTION, "无法连接转写服务", true, startedAt, request.apiKey)
        } catch (error: NoRouteToHostException) {
            failure(FailureKind.CONNECTION, "网络不可达", true, startedAt, request.apiKey)
        } catch (error: FileNotFoundException) {
            failure(
                FailureKind.CONFIGURATION,
                "待上传音频在请求期间不可读取",
                false,
                startedAt,
                request.apiKey,
            )
        } catch (error: SocketException) {
            if (jobId in cancelledJobs) {
                Result.Cancelled
            } else if (writeTimedOut.get()) {
                failure(FailureKind.WRITE_TIMEOUT, "上传音频超时", true, startedAt, request.apiKey)
            } else {
                failure(
                    FailureKind.CONNECTION,
                    "网络连接中断：${safeMessage(error)}",
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
                    "网络读写失败：${safeMessage(error)}",
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
                    "请求失败：${safeMessage(error)}",
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
            "转写端点无效"
        }
        require(request.apiKey.isNotBlank()) { "API Key 不能为空" }
        require(request.model.isNotBlank()) { "Model 不能为空" }
        require(request.audioFile.isFile && request.audioFile.length() > 0L) { "待上传音频不存在或为空" }
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
            throw InvalidResponseException("响应不是有效的 JSON 对象")
        }
        if (!root.has("text")) throw InvalidResponseException("响应缺少顶层 text 字段")
        val value = root.get("text")
        if (value !is String) throw InvalidResponseException("响应顶层 text 必须是字符串")
        if (value.isBlank()) throw InvalidResponseException("响应顶层 text 不能为空")
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
        FailureKind.CONNECT_TIMEOUT -> "连接转写服务超时"
        FailureKind.WRITE_TIMEOUT -> "上传音频超时"
        FailureKind.READ_TIMEOUT -> "等待转写响应超时"
        else -> "请求超时"
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
            val fields = linkedMapOf("model" to request.model).apply {
                putAll(request.additionalFields)
            }
            fieldParts = fields.map { (name, value) ->
                ("--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"$name\"\r\n\r\n" +
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
