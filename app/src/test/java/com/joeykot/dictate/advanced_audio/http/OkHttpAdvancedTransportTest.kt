package com.joeykot.dictate.advanced_audio.http

import com.joeykot.dictate.advanced_audio.auth.HttpRequestSigner
import com.joeykot.dictate.advanced_audio.auth.AwsSigV4Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OkHttpAdvancedTransportTest {
    @Test
    fun sendsRenderedMethodUrlQueryHeadersAndMaterializedBody() {
        RawHttpServer().use { server ->
            val captured = server.respond(status = 202, body = "accepted")
            val result = OkHttpAdvancedTransport().executeAndRead(
                HttpRequestSpec(
                    method = AdvancedHttpMethod.POST,
                    url = "${server.url}/submit?original=true",
                    query = listOf(HttpQueryParameter("task", "two words")),
                    headers = listOf(HttpHeader("X-Workflow", "advanced")),
                    body = HttpBodySpec.RawBytes("payload".toByteArray(), "text/plain; charset=utf-8"),
                    acceptedStatuses = setOf(202),
                ),
            )

            assertEquals(202, result.statusCode)
            assertTrue(result.isAcceptedBy(setOf(202)))
            assertArrayEquals("accepted".toByteArray(), result.body)
            val received = captured.get(3, TimeUnit.SECONDS)
            assertTrue(received.headers.startsWith("POST /submit?original=true&task=two%20words HTTP/1.1"))
            assertTrue(received.headers.contains("X-Workflow: advanced", ignoreCase = true))
            assertTrue(received.headers.contains("User-Agent: dictate-client/advanced-audio-v1", ignoreCase = true))
            assertTrue(received.headers.contains("Content-Type: text/plain; charset=utf-8", ignoreCase = true))
            assertArrayEquals("payload".toByteArray(), received.body)
        }
    }

    @Test
    fun multipartKeepsAudioOnTheStreamingPath() {
        val audio = File.createTempFile("advanced-http-", ".wav").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        try {
            RawHttpServer().use { server ->
                val captured = server.respond(status = 200, body = "ok")
                val result = OkHttpAdvancedTransport().executeAndRead(
                    HttpRequestSpec(
                        method = AdvancedHttpMethod.POST,
                        url = "${server.url}/upload",
                        body = HttpBodySpec.Multipart(
                            fields = listOf(
                                MultipartPart("model", MultipartValue.Text("test")),
                                MultipartPart("file", MultipartValue.AudioFile),
                                MultipartPart("metadata", MultipartValue.Bytes("bytes".toByteArray())),
                            ),
                            audio = PreparedAudioUpload(audio, "audio.wav", "audio/wav"),
                        ),
                    ),
                )
                assertEquals(200, result.statusCode)
                val received = captured.get(3, TimeUnit.SECONDS)
                val wire = received.body.toString(Charsets.ISO_8859_1)
                assertTrue(received.headers.contains("multipart/form-data", ignoreCase = true))
                assertTrue(wire.contains("name=\"model\""))
                assertTrue(wire.contains("\r\n\r\ntest\r\n"))
                assertTrue(wire.contains("name=\"file\"; filename=\"audio.wav\""))
                assertTrue(wire.contains("Content-Type: audio/wav"))
                assertTrue(wire.contains("\u0001\u0002\u0003\u0004"))
            }
        } finally {
            audio.delete()
        }
    }

    @Test
    fun boundedResponseRejectsOversizedBodies() {
        RawHttpServer().use { server ->
            server.respond(status = 200, body = "x".repeat(1_025))
            val failure = captureFailure {
                OkHttpAdvancedTransport().executeAndRead(
                    HttpRequestSpec(AdvancedHttpMethod.GET, "${server.url}/large"),
                    maxBytes = 1_024,
                )
            }
            assertTrue(failure is HttpTransportException.ResponseTooLarge)
        }
    }

    @Test
    fun cancellationClosesTheActiveCall() {
        RawHttpServer().use { server ->
            val release = CountDownLatch(1)
            val accepted = server.respond(status = 200, body = "late", beforeResponse = release)
            val source = AdvancedCancellationSource()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val task = executor.submit<Unit> {
                    OkHttpAdvancedTransport().executeAndRead(
                        HttpRequestSpec(AdvancedHttpMethod.GET, "${server.url}/wait"),
                        source.token,
                    )
                }
                accepted.get(3, TimeUnit.SECONDS)
                source.cancel()
                val failure = try {
                    task.get(3, TimeUnit.SECONDS)
                    error("request unexpectedly completed")
                } catch (error: ExecutionException) {
                    error.cause
                }
                assertTrue(failure is HttpTransportException.Cancelled)
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun dynamicSignerRefusesStreamingAudioBodiesBeforeConnection() {
        val audio = File.createTempFile("advanced-http-", ".wav").apply { writeText("audio") }
        try {
            val signer = HttpRequestSigner { error("streaming body must never reach signer") }
            val failure = captureFailure {
                OkHttpAdvancedTransport().execute(
                    HttpRequestSpec(
                        method = AdvancedHttpMethod.POST,
                        url = "https://example.test/upload",
                        body = HttpBodySpec.RawAudio(PreparedAudioUpload(audio, "audio.wav", "audio/wav")),
                        signer = signer,
                    ),
                )
            }
            assertTrue(failure is HttpTransportException.SignerRequiresMaterializedBody)
        } finally {
            audio.delete()
        }
    }

    @Test
    fun remoteS3UploadCanUseOnlyTheExplicitUnsignedStreamingPayload() {
        val audio = File.createTempFile("advanced-http-", ".wav").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        try {
            RawHttpServer().use { server ->
                val captured = server.respond(status = 200, body = "ok")
                val result = OkHttpAdvancedTransport().executeAndRead(
                    HttpRequestSpec(
                        method = AdvancedHttpMethod.PUT,
                        url = "${server.url}/bucket/object.wav",
                        headers = listOf(HttpHeader("Content-Length", audio.length().toString())),
                        body = HttpBodySpec.RawAudio(PreparedAudioUpload(audio, "object.wav", "audio/wav")),
                        signer = AwsSigV4Signer("us-east-1", "s3", "access", "secret"),
                        remoteStorageSigningPayload = RemoteStorageSigningPayload.UnsignedPayload,
                    ),
                )
                assertEquals(200, result.statusCode)
                val received = captured.get(3, TimeUnit.SECONDS)
                assertTrue(received.headers.contains("X-Amz-Content-Sha256: UNSIGNED-PAYLOAD", ignoreCase = true))
                assertTrue(received.headers.contains("Authorization: AWS4-HMAC-SHA256", ignoreCase = true))
                assertArrayEquals(byteArrayOf(9, 8, 7), received.body)
            }
        } finally {
            audio.delete()
        }
    }

    @Test
    fun remoteUnsignedPayloadCannotBeReusedByAWorkflowPost() {
        val audio = File.createTempFile("advanced-http-", ".wav").apply { writeBytes(byteArrayOf(1)) }
        try {
            val failure = captureFailure {
                OkHttpAdvancedTransport().execute(
                    HttpRequestSpec(
                        method = AdvancedHttpMethod.POST,
                        url = "https://example.test/not-storage",
                        body = HttpBodySpec.RawAudio(PreparedAudioUpload(audio, "audio.wav", "audio/wav")),
                        signer = AwsSigV4Signer("us-east-1", "s3", "access", "secret"),
                        remoteStorageSigningPayload = RemoteStorageSigningPayload.UnsignedPayload,
                    ),
                )
            }
            assertTrue(failure is HttpTransportException.InvalidRequest)
        } finally {
            audio.delete()
        }
    }

    private fun captureFailure(block: () -> Unit): Throwable = try {
        block()
        error("operation unexpectedly succeeded")
    } catch (error: Throwable) {
        error
    }

    private data class ReceivedRequest(
        val headers: String,
        val body: ByteArray,
    )

    private class RawHttpServer : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val url: String = "http://127.0.0.1:${server.localPort}"
        private var activeSocket: Socket? = null

        fun respond(
            status: Int,
            body: String,
            beforeResponse: CountDownLatch? = null,
        ): CompletableFuture<ReceivedRequest> {
            val captured = CompletableFuture<ReceivedRequest>()
            Thread({
                try {
                    server.accept().use { connection ->
                        activeSocket = connection
                        connection.soTimeout = 5_000
                        val headers = readHeaders(connection)
                        val contentLength = headers.lineSequence()
                            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                            ?.substringAfter(':')
                            ?.trim()
                            ?.toIntOrNull()
                            ?: 0
                        val chunked = headers.lineSequence().any {
                            it.startsWith("Transfer-Encoding:", ignoreCase = true) &&
                                it.substringAfter(':').contains("chunked", ignoreCase = true)
                        }
                        val payload = if (chunked) readChunked(connection.getInputStream())
                        else readExactly(connection.getInputStream(), contentLength)
                        captured.complete(ReceivedRequest(headers, payload))
                        beforeResponse?.await(5, TimeUnit.SECONDS)
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        connection.getOutputStream().apply {
                            write("HTTP/1.1 $status Test\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                } catch (error: Throwable) {
                    captured.completeExceptionally(error)
                }
            }, "advanced-http-test-server").apply {
                isDaemon = true
                start()
            }
            return captured
        }

        override fun close() {
            activeSocket?.close()
            server.close()
        }

        private fun readHeaders(connection: Socket): String {
            val output = ByteArrayOutputStream()
            val input = connection.getInputStream()
            while (true) {
                val value = input.read()
                check(value >= 0)
                output.write(value)
                if (output.toString(Charsets.ISO_8859_1.name()).endsWith("\r\n\r\n")) break
            }
            return output.toString(Charsets.ISO_8859_1.name())
        }

        private fun readExactly(input: java.io.InputStream, size: Int): ByteArray {
            val bytes = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = input.read(bytes, offset, size - offset)
                check(count > 0)
                offset += count
            }
            return bytes
        }

        private fun readChunked(input: java.io.InputStream): ByteArray {
            val output = ByteArrayOutputStream()
            while (true) {
                val length = readAsciiLine(input).substringBefore(';').trim().toInt(16)
                if (length == 0) {
                    // Consume the terminating empty trailer line. This small
                    // fake has no need to retain arbitrary trailer headers.
                    while (readAsciiLine(input).isNotEmpty()) Unit
                    return output.toByteArray()
                }
                output.write(readExactly(input, length))
                check(readAsciiLine(input).isEmpty())
            }
        }

        private fun readAsciiLine(input: java.io.InputStream): String {
            val output = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                check(next >= 0)
                if (next == '\n'.code) break
                if (next != '\r'.code) output.write(next)
            }
            return output.toString(Charsets.ISO_8859_1.name())
        }
    }
}
