package com.joeykot.dictate.network

import com.joeykot.dictate.model.PostProcessingConfig
import com.joeykot.dictate.model.PostProcessingProvider
import com.joeykot.dictate.model.PromptConfig
import com.joeykot.dictate.util.Diagnostics
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PostProcessingClientTest {
    @Test
    fun transcriptionMultipartUsesTheOverriddenModelAndStructuredFields() {
        val context: android.content.Context = RuntimeEnvironment.getApplication()
        val audio = File.createTempFile("network-test-", ".wav", context.cacheDir).apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        try {
            TestServer().use { server ->
                val captured = server.respond(200, """{"text":"transcribed"}""")
                val result = TranscriptionClient(Diagnostics(context)).transcribe(
                    1, TranscriptionClient.Request(
                        endpoint = "${server.url}/v1/audio/transcriptions",
                        apiKey = "key", model = "default", additionalFields = linkedMapOf(),
                        audioFile = audio, mimeType = "audio/wav",
                        additionalJson = """{"model":"alternate","generation":{"on":true,"deleted":null},"array":[1,2],"removed":null}""",
                    ),
                )
                assertEquals("transcribed", (result as TranscriptionClient.Result.Success).text)
                val body = captured.get(3, TimeUnit.SECONDS).second
                assertEquals(1, Regex("name=\\\"model\\\"").findAll(body).count())
                assertTrue(body.contains("name=\"model\"\r\n\r\nalternate\r\n"))
                assertTrue(body.contains("name=\"generation\"\r\n\r\n{\"on\":true}\r\n"))
                assertTrue(body.contains("name=\"array\"\r\n\r\n[1,2]\r\n"))
                assertFalse(body.contains("name=\"removed\""))
            }
        } finally {
            audio.delete()
        }
    }

    @Test
    fun sendsMergedRequestAndReceivesText() {
        TestServer().use { server ->
            val captured = server.respond(200, """{"choices":[{"message":{"content":"result"}}]}""")
            val client = newClient()
            val result = client.execute(config(server), "secret", PromptConfig(prompt = "Summarize", additionalJson = """{"model":"alternate","metadata":{"nullField":null,"array":[1,2]}}"""), "input") { true }
            assertTrue(result is TranscriptionClient.Result.Success)
            assertEquals("result", (result as TranscriptionClient.Result.Success).text)
            val wire = captured.get(3, TimeUnit.SECONDS)
            assertTrue(wire.first.startsWith("POST /v1/chat/completions "))
            val body = JSONObject(wire.second)
            assertEquals("alternate", body.getString("model"))
            assertFalse(body.getJSONObject("metadata").has("nullField"))
            assertEquals(2, body.getJSONObject("metadata").getJSONArray("array").length())
        }
    }

    @Test
    fun httpFailureUsesCommonRetryPolicyAndRedactsApiKey() {
        TestServer().use { server ->
            server.respond(429, """{"error":{"message":"secret"}}""")
            val result = newClient().execute(config(server), "secret", PromptConfig(prompt = "Summarize"), "input") { true }
            assertTrue(result is TranscriptionClient.Result.Failure)
            result as TranscriptionClient.Result.Failure
            assertEquals(429, result.statusCode)
            assertTrue(result.retryable)
            assertFalse(result.serverSummary.contains("secret"))
        }
    }

    @Test
    fun streamedServiceFailuresUseRetryPolicyAndRedactApiKey() {
        val cases = listOf(
            PostProcessingProvider.ANTHROPIC to
                """{"type":"error","error":{"type":"overloaded_error","message":"secret is overloaded"}}""",
            PostProcessingProvider.OPENAI_RESPONSES to
                """{"type":"response.failed","response":{"error":{"code":"server_error","message":"secret unavailable"}}}""",
            PostProcessingProvider.OPENAI_COMPATIBLE to
                """{"error":{"code":"rate_limit_exceeded","message":"secret rate limited"}}""",
        )
        cases.forEach { (provider, event) ->
            TestServer().use { server ->
                val captured = server.respond(200, "data: $event\n\n", contentType = "text/event-stream")
                val result = newClient().execute(
                    config(server).copy(provider = provider), "secret",
                    PromptConfig(prompt = "Summarize", additionalJson = """{"stream":true}"""), "input",
                ) { true }
                assertTrue(result is TranscriptionClient.Result.Failure)
                result as TranscriptionClient.Result.Failure
                assertEquals(TranscriptionClient.FailureKind.SERVICE, result.kind)
                assertTrue(result.retryable)
                assertNull(result.statusCode) // Do not invent an HTTP 5xx for the actual HTTP 200 response.
                assertFalse(result.message.contains("secret"))
                assertTrue(JSONObject(captured.get(3, TimeUnit.SECONDS).second).getBoolean("stream"))
            }
        }
    }

    @Test
    fun streamedPermanentUnknownAndIncompleteFailuresDoNotRetry() {
        val cases = listOf(
            """{"type":"error","error":{"type":"authentication_error","message":"Invalid key"}}""" to
                TranscriptionClient.FailureKind.SERVICE,
            """{"type":"error","error":{"type":"unknown_error","message":"Unknown"}}""" to
                TranscriptionClient.FailureKind.SERVICE,
            """{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"}}}""" to
                TranscriptionClient.FailureKind.INVALID_RESPONSE,
        )
        cases.forEach { (event, expectedKind) ->
            TestServer().use { server ->
                server.respond(200, "data: $event\n\n", contentType = "text/event-stream")
                val result = newClient().execute(
                    config(server), "key", PromptConfig(prompt = "Summarize", additionalJson = """{"stream":true}"""), "input",
                ) { true }
                assertTrue(result is TranscriptionClient.Result.Failure)
                result as TranscriptionClient.Result.Failure
                assertEquals(expectedKind, result.kind)
                assertFalse(result.retryable)
            }
        }
    }

    @Test
    fun explicitStreamErrorStatusUsesTheCommonHttpRetryPolicy() {
        listOf(429 to true, 503 to true, 400 to false).forEach { (status, retryable) ->
            TestServer().use { server ->
                val event = """{"error":{"code":$status,"message":"Request failed"}}"""
                server.respond(200, "data: $event\n\n", contentType = "text/event-stream")
                val result = newClient().execute(
                    config(server), "key", PromptConfig(prompt = "Summarize", additionalJson = """{"stream":true}"""), "input",
                ) { true }
                assertTrue(result is TranscriptionClient.Result.Failure)
                result as TranscriptionClient.Result.Failure
                assertEquals(TranscriptionClient.FailureKind.SERVICE, result.kind)
                assertEquals(retryable, result.retryable)
                assertNull(result.statusCode)
                assertTrue(result.message.contains(status.toString()))
            }
        }
    }

    @Test
    fun cancellationStopsRequestAndDoesNotPoisonTheNextRequest() {
        val client = newClient()
        val executor = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        try {
            TestServer().use { blocked ->
                val accepted = blocked.respond(200, """{"choices":[{"message":{"content":"late"}}]}""", release)
                val request = executor.submit<TranscriptionClient.Result> {
                    client.execute(config(blocked), "key", PromptConfig(prompt = "Summarize"), "input") { true }
                }
                accepted.get(3, TimeUnit.SECONDS)
                client.cancel()
                assertEquals(TranscriptionClient.Result.Cancelled, request.get(3, TimeUnit.SECONDS))
            }
            TestServer().use { next ->
                next.respond(200, """{"choices":[{"message":{"content":"next"}}]}""")
                val result = client.execute(config(next), "key", PromptConfig(prompt = "Summarize"), "input") { true }
                assertEquals("next", (result as TranscriptionClient.Result.Success).text)
            }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun newClient() = PostProcessingClient(Diagnostics(RuntimeEnvironment.getApplication()))
    private fun config(server: TestServer) = PostProcessingConfig(baseUrl = server.url, model = "default")

    private class TestServer : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}"
        private var socket: Socket? = null

        fun respond(
            status: Int,
            body: String,
            release: CountDownLatch? = null,
            contentType: String = "application/json",
        ): CompletableFuture<Pair<String, String>> {
            val captured = CompletableFuture<Pair<String, String>>()
            Thread({
                try {
                    server.accept().use { connection ->
                        socket = connection
                        connection.soTimeout = 5_000
                        val input = connection.getInputStream()
                        val headerBytes = ByteArrayOutputStream()
                        while (true) {
                            val value = input.read()
                            check(value >= 0)
                            headerBytes.write(value)
                            if (headerBytes.toString("UTF-8").endsWith("\r\n\r\n")) break
                        }
                        val header = headerBytes.toString("UTF-8")
                        val length = header.lineSequence().first { it.startsWith("Content-Length:", ignoreCase = true) }.substringAfter(':').trim().toInt()
                        val payload = ByteArray(length)
                        var offset = 0
                        while (offset < length) {
                            val count = input.read(payload, offset, length - offset)
                            check(count > 0)
                            offset += count
                        }
                        captured.complete(header to payload.toString(Charsets.UTF_8))
                        release?.await(5, TimeUnit.SECONDS)
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        connection.getOutputStream().apply {
                            write("HTTP/1.1 $status Test\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                } catch (error: Exception) {
                    captured.completeExceptionally(error)
                }
            }, "post-processing-test-server").apply { isDaemon = true }.start()
            return captured
        }

        override fun close() {
            socket?.close()
            server.close()
        }
    }
}
