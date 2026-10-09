package com.joeykot.dictate.job

import android.os.Looper
import com.joeykot.dictate.DictateApplication
import com.joeykot.dictate.advanced_audio.http.AdvancedCancellationSource
import com.joeykot.dictate.audio.AudioEncodingPlan
import com.joeykot.dictate.model.AdvancedAudioConfig
import com.joeykot.dictate.model.AppSettings
import com.joeykot.dictate.model.AudioConfig
import com.joeykot.dictate.model.AudioCodec
import com.joeykot.dictate.model.AudioContainer
import com.joeykot.dictate.model.JobState
import com.joeykot.dictate.model.Pcm16Format
import com.joeykot.dictate.model.RetryConfig
import com.joeykot.dictate.model.RuntimeSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Covers the controller boundary only. The workflow executor itself has its
 * own protocol tests; these tests ensure it cannot fall back to the legacy
 * client or legacy retry loop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DictateApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AdvancedAudioJobTest {
    private val application: DictateApplication
        get() = RuntimeEnvironment.getApplication() as DictateApplication

    private val controller: VoiceJobController
        get() = application.voiceJobController

    private lateinit var server: ServerSocket
    private lateinit var serverExecutor: ExecutorService
    private val requestCount = AtomicInteger()
    private val requestLines = CopyOnWriteArrayList<String>()
    private val serverErrors = CopyOnWriteArrayList<Throwable>()

    @Volatile
    private var onRequest: (Socket) -> Unit = { socket -> socket.reply(200, "{\"text\":\"advanced result\"}") }

    @Before
    fun setUp() {
        server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        serverExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        serverExecutor.submit {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val request = socket.readRequest()
                        requestLines += request.requestLine
                        requestCount.incrementAndGet()
                        onRequest(socket)
                    }
                } catch (error: Throwable) {
                    if (!server.isClosed) serverErrors += error
                }
            }
        }
    }

    @After
    fun tearDown() {
        if (controller.currentState().state != JobState.IDLE) {
            controller.handleDoubleTap(controller.currentState().state)
        }
        server.close()
        serverExecutor.shutdownNow()
        executor("worker").shutdownNow()
        executor("realtimeWorker").shutdownNow()
        executor("scheduler").shutdownNow()
    }

    @Test
    fun advancedConnectionTestUsesWorkflowWithoutLegacyBaseUrlOrApiKey() {
        val results = mutableListOf<VoiceJobController.ConnectionTestResult>()
        val states = mutableListOf<JobState>()
        controller.addListener { states += it.state }

        val prepared = startPausedConnectionTest(requestWorkflow(), retryEnabled = true) { results += it }
        prepared.releaseWorker()
        await { results.isNotEmpty() }

        assertTrue(results.single().success)
        assertEquals(200, results.single().statusCode)
        assertEquals("advanced result", results.single().text)
        assertEquals(1, requestCount.get())
        assertEquals("POST /advanced HTTP/1.1", requestLines.single())
        assertFalse(states.contains(JobState.RETRY_WAITING))
        assertEquals(JobState.IDLE, controller.currentState().state)
    }

    @Test
    fun advancedConnectionTestRejectsMalformedWorkflowBeforePreparingAudio() {
        val results = mutableListOf<VoiceJobController.ConnectionTestResult>()
        val runtime = RuntimeSettings(
            app = AppSettings(
                advancedAudio = AdvancedAudioConfig(enabled = true, workflowJson = "{"),
            ),
            apiKey = "",
        )

        assertFalse(controller.testConnection(runtime) { results += it })

        assertEquals(1, results.size)
        assertFalse(results.single().success)
        assertTrue(results.single().message.contains("ADVANCED_AUDIO_API.workflow"))
        assertNull(activeJob())
        assertEquals(JobState.IDLE, controller.currentState().state)
        assertEquals(0, requestCount.get())
    }

    @Test
    fun advancedConnectionTestRejectsMissingRequiredWorkflowSecretBeforePreparingAudio() {
        val results = mutableListOf<VoiceJobController.ConnectionTestResult>()
        val runtime = RuntimeSettings(
            app = AppSettings(
                advancedAudio = AdvancedAudioConfig(enabled = true, workflowJson = realtimeWorkflow()),
            ),
            apiKey = "",
            advancedAudioSecrets = emptyMap(),
        )

        assertFalse(controller.testConnection(runtime) { results += it })

        assertEquals(1, results.size)
        assertFalse(results.single().success)
        assertTrue(results.single().message.contains("ADVANCED_AUDIO_API.secrets.api_key"))
        assertNull(activeJob())
        assertEquals(JobState.IDLE, controller.currentState().state)
        assertEquals(0, requestCount.get())
    }

    @Test
    fun asyncSubmitFailureNeverUsesTheLegacyWholeJobRetry() {
        onRequest = { socket -> socket.reply(503, "{\"error\":\"temporary\"}") }
        val results = mutableListOf<VoiceJobController.ConnectionTestResult>()
        val states = mutableListOf<JobState>()
        controller.addListener { states += it.state }

        val prepared = startPausedConnectionTest(asyncSubmitWorkflow(), retryEnabled = true) { results += it }
        prepared.releaseWorker()
        await { results.isNotEmpty() }

        assertFalse(results.single().success)
        assertEquals(503, results.single().statusCode)
        assertEquals(1, requestCount.get())
        assertFalse(states.contains(JobState.RETRY_WAITING))
        assertEquals(JobState.IDLE, controller.currentState().state)
    }

    @Test
    fun realtimeWorkflowUsesTheRawReplayPathWithoutLegacyFallback() {
        val results = mutableListOf<VoiceJobController.ConnectionTestResult>()

        val prepared = startPausedConnectionTest(realtimeWorkflow(), retryEnabled = false) { results += it }
        prepared.releaseWorker()
        await { results.isNotEmpty() }

        assertFalse(results.single().success)
        assertTrue(results.single().message.contains("realtime", ignoreCase = true))
        assertFalse(results.single().message.contains("not supported", ignoreCase = true))
        assertEquals(0, requestCount.get())
        assertEquals(JobState.IDLE, controller.currentState().state)
    }

    @Test
    fun realtimeReplayRetriesWithASecondWebSocketSession() {
        RetryingRealtimeServer().use { realtimeServer ->
            val results = mutableListOf<VoiceJobController.ConnectionTestResult>()
            val states = mutableListOf<JobState>()
            controller.addListener { states += it.state }

            val runtime = RuntimeSettings(
                app = AppSettings(
                    retry = RetryConfig(enabled = true, maxRetries = 1, initialBackoffSeconds = 0.1),
                    advancedAudio = AdvancedAudioConfig(
                        enabled = true,
                        workflowJson = realtimeRetryWorkflow(realtimeServer.port),
                    ),
                ),
                apiKey = "",
            )

            assertTrue(controller.testConnection(runtime) { results += it })
            await { results.isNotEmpty() }

            assertTrue(results.single().success)
            assertEquals("retried transcript", results.single().text)
            assertEquals(2, realtimeServer.connectionCount.get())
            assertTrue("Realtime test server failed: ${realtimeServer.errors.firstOrNull()}", realtimeServer.errors.isEmpty())
            assertTrue(states.contains(JobState.RETRY_WAITING))
            assertEquals(JobState.IDLE, controller.currentState().state)
        }
    }

    @Test
    fun cancellingRealtimeReplayBackoffPreventsTheScheduledSecondSession() {
        RetryingRealtimeServer().use { realtimeServer ->
            val results = mutableListOf<VoiceJobController.ConnectionTestResult>()
            val runtime = RuntimeSettings(
                app = AppSettings(
                    retry = RetryConfig(enabled = true, maxRetries = 1, initialBackoffSeconds = 0.5),
                    advancedAudio = AdvancedAudioConfig(
                        enabled = true,
                        workflowJson = realtimeRetryWorkflow(realtimeServer.port),
                    ),
                ),
                apiKey = "",
            )

            assertTrue(controller.testConnection(runtime) { results += it })
            await { controller.currentState().state == JobState.RETRY_WAITING }
            controller.handleDoubleTap(JobState.RETRY_WAITING)
            await { results.isNotEmpty() }

            Thread.sleep(750L)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, realtimeServer.connectionCount.get())
            assertEquals(1, results.size)
            assertFalse(results.single().success)
            assertEquals("Test cancelled", results.single().message)
            assertEquals(JobState.IDLE, controller.currentState().state)
        }
    }

    @Test
    fun cancellingAdvancedRequestCancelsTheJobOwnedSource() {
        val requestStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        onRequest = { socket ->
            requestStarted.countDown()
            releaseResponse.await(5L, TimeUnit.SECONDS)
            runCatching { socket.reply(200, "{\"text\":\"late\"}") }
        }
        val results = mutableListOf<VoiceJobController.ConnectionTestResult>()

        val prepared = startPausedConnectionTest(requestWorkflow(), retryEnabled = false) { results += it }
        prepared.releaseWorker()
        assertTrue(requestStarted.await(5L, TimeUnit.SECONDS))

        val source = jobField<AdvancedCancellationSource>(prepared.job, "advancedCancellationSource")
        assertNotNull(source)
        controller.handleDoubleTap(JobState.REQUESTING)

        assertTrue(checkNotNull(source).token.isCancelled())
        assertEquals(JobState.IDLE, controller.currentState().state)
        assertEquals(1, results.size)
        assertFalse(results.single().success)
        assertEquals("Test cancelled", results.single().message)
        releaseResponse.countDown()
    }

    private fun startPausedConnectionTest(
        workflow: String,
        retryEnabled: Boolean,
        callback: (VoiceJobController.ConnectionTestResult) -> Unit,
    ): PausedConnectionTest {
        val releaseWorker = CountDownLatch(1)
        val workerBlocked = CountDownLatch(1)
        executor("worker").submit {
            workerBlocked.countDown()
            releaseWorker.await()
        }
        assertTrue(workerBlocked.await(5L, TimeUnit.SECONDS))

        val runtime = RuntimeSettings(
            app = AppSettings(
                retry = RetryConfig(enabled = retryEnabled, maxRetries = 1, initialBackoffSeconds = 0.1),
                advancedAudio = AdvancedAudioConfig(enabled = true, workflowJson = workflow),
            ),
            // A valid Advanced workflow must not require any Legacy provider field.
            apiKey = "",
            advancedAudioSecrets = mapOf("api_key" to "test-api-key"),
        )
        assertTrue(controller.testConnection(runtime, callback))
        val job = checkNotNull(activeJob())
        val queuedConnectivityTask = checkNotNull(jobField<Future<*>>(job, "workerFuture"))
        assertTrue(queuedConnectivityTask.cancel(false))

        val output = File.createTempFile("advanced-controller-", ".wav").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        setJobField(job, "outputFile", output)
        setJobField(
            job,
            "encodingPlan",
            AudioEncodingPlan.resolve(
                AudioConfig(codec = AudioCodec.PCM, container = AudioContainer.WAV, sampleRate = 16_000),
                Pcm16Format.LEGACY_MONO_16_KHZ,
            ),
        )
        invokeStartRequest(jobId(job))
        return PausedConnectionTest(job, releaseWorker)
    }

    private fun requestWorkflow(): String = """
        {
          "schema_version": 1,
          "name": "Controller request test",
          "audio": {"delivery": {"type": "raw_audio"}, "mime": "audio/wav"},
          "recognition": {
            "mode": "request",
            "request": {
              "method": "POST",
              "url": "http://127.0.0.1:${server.localPort}/advanced",
              "body": {"type": "raw_audio"},
              "accepted_statuses": [200]
            },
            "final_text": {"type": "json_path", "path": "$.text"}
          }
        }
    """.trimIndent()

    private fun asyncSubmitWorkflow(): String = """
        {
          "schema_version": 1,
          "name": "Controller async submit test",
          "audio": {"delivery": {"type": "raw_audio"}, "mime": "audio/wav"},
          "recognition": {
            "mode": "async_poll",
            "submit": {
              "method": "POST",
              "url": "http://127.0.0.1:${server.localPort}/advanced",
              "body": {"type": "raw_audio"},
              "accepted_statuses": [200]
            },
            "final_text": {"type": "json_path", "path": "$.text"}
          }
        }
    """.trimIndent()

    private fun realtimeWorkflow(): String = checkNotNull(
        javaClass.classLoader?.getResourceAsStream("advanced_audio/workflows/valid-v2-realtime-websocket.json"),
    ).bufferedReader().use { it.readText() }

    private fun realtimeRetryWorkflow(port: Int): String = """
        {
          "schema_version": 2,
          "name": "Realtime replay retry test",
          "audio": {"delivery": {"type": "realtime_chunks"}},
          "recognition": {
            "mode": "realtime_session",
            "realtime": {
              "transport": "websocket",
              "connect": {"url": "ws://127.0.0.1:$port/realtime"},
              "audio_stream": {
                "codec": "pcm_s16le",
                "sample_rate": 16000,
                "channels": 1,
                "chunk_duration_ms": 20,
                "pacing": "realtime"
              },
              "audio_message": {"type": "binary"},
              "receive_rules": [
                {"event": "completed", "path": "$.text", "action": "set_final_text"}
              ],
              "completion": {"event": "completed"},
              "finalization_timeout_ms": 1000
            }
          }
        }
    """.trimIndent()

    private fun activeJob(): Any? = VoiceJobController::class.java
        .getDeclaredField("activeJob")
        .apply { isAccessible = true }
        .get(controller)

    private fun jobId(job: Any): Long = job.javaClass.getDeclaredField("id")
        .apply { isAccessible = true }
        .getLong(job)

    private fun setJobField(job: Any, name: String, value: Any?) {
        job.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(job, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> jobField(job: Any, name: String): T? = job.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }
        .get(job) as T?

    private fun invokeStartRequest(jobId: Long) {
        VoiceJobController::class.java.getDeclaredMethod("startRequest", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(controller, jobId)
    }

    private fun executor(name: String): ExecutorService = VoiceJobController::class.java
        .getDeclaredField(name)
        .apply { isAccessible = true }
        .get(controller) as ExecutorService

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
        while (System.nanoTime() < deadline) {
            assertTrue("Local server failed: ${serverErrors.firstOrNull()}", serverErrors.isEmpty())
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5L)
        }
        assertTrue("Timed out waiting for Advanced Audio workflow: ${controller.currentState()}", condition())
    }

    private data class PausedConnectionTest(
        val job: Any,
        private val release: CountDownLatch,
    ) {
        fun releaseWorker() = release.countDown()
    }

    private data class CapturedRequest(val requestLine: String)

    private fun Socket.readRequest(): CapturedRequest {
        val input = getInputStream()
        val headers = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            check(value >= 0) { "Connection closed before HTTP headers" }
            headers.write(value)
            check(headers.size() <= 16_384) { "HTTP headers exceed test limit" }
            if (headers.toString("UTF-8").endsWith("\r\n\r\n")) break
        }
        val headerText = headers.toString("UTF-8")
        val contentLength = headerText.lineSequence()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull()
            ?: 0
        if (contentLength > 0) {
            val body = ByteArray(contentLength)
            var offset = 0
            while (offset < body.size) {
                val count = input.read(body, offset, body.size - offset)
                check(count > 0) { "Connection closed before request body" }
                offset += count
            }
        }
        return CapturedRequest(headerText.lineSequence().first())
    }

    private fun Socket.reply(status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        getOutputStream().apply {
            write(
                ("HTTP/1.1 $status Test\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                    .toByteArray(Charsets.UTF_8),
            )
            write(bytes)
            flush()
        }
    }

    private class RetryingRealtimeServer : AutoCloseable {
        private val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val port: Int = server.localPort
        val connectionCount = AtomicInteger()
        val errors = CopyOnWriteArrayList<Throwable>()
        private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

        @Volatile
        private var activeSocket: Socket? = null

        init {
            executor.submit {
                while (!server.isClosed) {
                    try {
                        server.accept().use { socket ->
                            activeSocket = socket
                            handle(socket, connectionCount.incrementAndGet())
                        }
                    } catch (error: Throwable) {
                        if (!server.isClosed) errors += error
                    }
                }
            }
        }

        override fun close() {
            activeSocket?.close()
            server.close()
            executor.shutdownNow()
        }

        private fun handle(socket: Socket, attempt: Int) {
            val headers = readHeaders(socket)
            if (attempt == 1) return

            val key = headers.lineSequence()
                .firstOrNull { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                ?: error("missing Sec-WebSocket-Key")
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest((key + WEBSOCKET_GUID).toByteArray(Charsets.ISO_8859_1)),
            )
            socket.getOutputStream().apply {
                write(
                    (
                        "HTTP/1.1 101 Switching Protocols\r\n" +
                            "Upgrade: websocket\r\n" +
                            "Connection: Upgrade\r\n" +
                            "Sec-WebSocket-Accept: $accept\r\n\r\n"
                        ).toByteArray(Charsets.ISO_8859_1),
                )
                writeTextFrame("{\"event\":\"completed\",\"text\":\"retried transcript\"}")
                flush()
            }
            // The client receives the queued completion while it sends the
            // first replay packet. Keep the peer open long enough for its
            // normal websocket close instead of turning that success into a
            // transport failure.
            Thread.sleep(500L)
        }

        private fun readHeaders(socket: Socket): String {
            val output = ByteArrayOutputStream()
            val input = socket.getInputStream()
            while (true) {
                val value = input.read()
                check(value >= 0) { "connection closed before websocket request headers" }
                output.write(value)
                check(output.size() <= 16_384) { "websocket request headers exceed test limit" }
                if (output.toString(Charsets.ISO_8859_1.name()).endsWith("\r\n\r\n")) {
                    return output.toString(Charsets.ISO_8859_1.name())
                }
            }
        }

        private fun java.io.OutputStream.writeTextFrame(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            check(bytes.size <= 125) { "test frame must use the short websocket length form" }
            write(0x81)
            write(bytes.size)
            write(bytes)
        }

        private companion object {
            const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        }
    }
}
