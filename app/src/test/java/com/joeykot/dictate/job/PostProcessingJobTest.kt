package com.joeykot.dictate.job

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import com.joeykot.dictate.DictateApplication
import com.joeykot.dictate.model.AppSettings
import com.joeykot.dictate.model.JobState
import com.joeykot.dictate.model.Pcm16Format
import com.joeykot.dictate.model.PostProcessingConfig
import com.joeykot.dictate.model.PostProcessingProvider
import com.joeykot.dictate.model.RetryConfig
import com.joeykot.dictate.model.RuntimeSettings
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DictateApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PostProcessingJobTest {
    private val application: DictateApplication
        get() = RuntimeEnvironment.getApplication() as DictateApplication

    private val controller: VoiceJobController
        get() = application.voiceJobController

    private lateinit var server: ServerSocket
    private lateinit var serverExecutor: ExecutorService
    private lateinit var clipboard: ClipboardManager
    private lateinit var previousRecording: File
    private val recordingBytes = ByteArray(6_400) { (it % 127).toByte() }
    private val requestCount = AtomicInteger()
    private val requestBodies = CopyOnWriteArrayList<String>()
    private val serverErrors = CopyOnWriteArrayList<Exception>()

    @Volatile
    private var activeSocket: Socket? = null

    @Volatile
    private var respond: (Int, Socket) -> Unit = { _, socket -> socket.reply(200, success("OK")) }

    @Before
    fun setUp() {
        clipboard = application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("existing", CLIPBOARD_TEXT))
        val raw = application.audioFileStore.newRawFile(700L).apply { writeBytes(recordingBytes) }
        previousRecording = checkNotNull(
            application.audioFileStore.promoteToLast(raw, Pcm16Format.LEGACY_MONO_16_KHZ),
        ).file
        server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        serverExecutor = Executors.newSingleThreadExecutor()
        serverExecutor.submit {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        activeSocket = socket
                        socket.soTimeout = 5_000
                        requestBodies += socket.readRequestBody()
                        respond(requestCount.incrementAndGet(), socket)
                    }
                } catch (error: Exception) {
                    if (!server.isClosed) serverErrors += error
                } finally {
                    activeSocket = null
                }
            }
        }
    }

    @After
    fun tearDown() {
        server.close()
        activeSocket?.close()
        serverExecutor.shutdownNow()
        // The application owns these long-lived executors; terminate them between test sandboxes.
        executor("worker").shutdownNow()
        executor("scheduler").shutdownNow()
    }

    @Test
    fun connectionTestRetriesUsingSharedPolicyWithoutDeliveringTextOrReplacingRecording() {
        respond = { attempt, exchange ->
            if (attempt == 1) exchange.reply(503, "{\"error\":\"temporarily unavailable\"}")
            else exchange.reply(200, success("Recovered response"))
        }
        val results = mutableListOf<VoiceJobController.ConnectionTestResult>()
        val states = mutableListOf<JobState>()
        controller.addListener { states += it.state }

        assertTrue(controller.testPostProcessingConnection(runtime(retryEnabled = true)) { results += it })
        await { results.isNotEmpty() }

        assertEquals(2, requestCount.get())
        assertEquals(requestBodies[0], requestBodies[1])
        assertTrue(states.contains(JobState.RETRY_WAITING))
        assertTrue(results.single().success)
        assertEquals(200, results.single().statusCode)
        assertEquals("Recovered response", results.single().text)
        assertEquals(JobState.IDLE, controller.currentState().state)
        assertNoDeliverySideEffects()
    }

    @Test
    fun connectionTestRetriesStreamOverloadWithoutExposingPartialText() {
        val base = runtime(retryEnabled = true)
        val settings = base.copy(app = base.app.copy(postProcessing = base.app.postProcessing.copy(
            provider = PostProcessingProvider.ANTHROPIC,
        )))
        val allowSuccessfulResponse = CountDownLatch(1)
        respond = { attempt, socket ->
            val stream = if (attempt == 1) {
                "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Discard partial text\"}}\n\n" +
                    "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n\n"
            } else {
                check(allowSuccessfulResponse.await(5L, TimeUnit.SECONDS))
                "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Recovered response\"}}\n\n" +
                    "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
            }
            socket.reply(200, stream, "text/event-stream")
        }
        val states = mutableListOf<JobState>()
        val results = mutableListOf<VoiceJobController.ConnectionTestResult>()
        controller.addListener { states += it.state }

        try {
            assertTrue(controller.testPostProcessingConnection(settings) { results += it })
            await { states.contains(JobState.RETRY_WAITING) }
            assertTrue(results.isEmpty())
            assertEquals(CLIPBOARD_TEXT, clipboard.primaryClip!!.getItemAt(0).text.toString())
        } finally {
            allowSuccessfulResponse.countDown()
        }
        await { results.isNotEmpty() }

        assertEquals(2, requestCount.get())
        assertEquals(requestBodies[0], requestBodies[1])
        assertTrue(results.single().success)
        assertEquals("Recovered response", results.single().text)
        assertEquals(JobState.IDLE, controller.currentState().state)
        assertNoDeliverySideEffects()
    }

    @Test
    fun exhaustedRetriesReturnFailureAndReleaseTheTaskForTheNextRequest() {
        respond = { attempt, exchange ->
            if (attempt <= 2) exchange.reply(503, "{\"error\":\"busy\"}")
            else exchange.reply(200, success("Next task"))
        }
        val failed = mutableListOf<VoiceJobController.ConnectionTestResult>()
        assertTrue(controller.testPostProcessingConnection(runtime(retryEnabled = true)) { failed += it })
        await { failed.isNotEmpty() }

        assertEquals(2, requestCount.get())
        assertFalse(failed.single().success)
        assertEquals(503, failed.single().statusCode)
        assertEquals(JobState.IDLE, controller.currentState().state)

        val next = mutableListOf<VoiceJobController.ConnectionTestResult>()
        assertTrue(controller.testPostProcessingConnection(runtime()) { next += it })
        await { next.isNotEmpty() }
        assertTrue(next.single().success)
        assertEquals("Next task", next.single().text)
        assertEquals(3, requestCount.get())
        assertNoDeliverySideEffects()
    }

    @Test
    fun cancellingProcessingDiscardsQueuedSuccessBeforeStartingTheNextTask() {
        respond = { attempt, exchange -> exchange.reply(200, success("Response $attempt")) }
        val cancelled = mutableListOf<VoiceJobController.ConnectionTestResult>()
        assertTrue(controller.testPostProcessingConnection(runtime()) { cancelled += it })

        // Finish the HTTP work but leave its successful result queued on the paused main looper.
        // This makes the late-result race deterministic without delaying a real network response.
        drainWorker()
        assertEquals(1, requestCount.get())
        assertEquals(JobState.REQUESTING, controller.currentState().state)
        assertTrue(cancelled.isEmpty())

        controller.handleDoubleTap(JobState.REQUESTING)
        assertEquals(JobState.IDLE, controller.currentState().state)
        assertFalse(cancelled.single().success)
        assertEquals("Test cancelled", cancelled.single().message)

        val next = mutableListOf<VoiceJobController.ConnectionTestResult>()
        assertTrue(controller.testPostProcessingConnection(runtime()) { next += it })
        await { next.isNotEmpty() }

        assertEquals(1, cancelled.size)
        assertTrue(next.single().success)
        assertEquals("Response 2", next.single().text)
        assertEquals(2, requestCount.get())
        assertEquals(JobState.IDLE, controller.currentState().state)
        assertNoDeliverySideEffects()
    }

    @Test
    fun cancellingRetryWaitPreventsScheduledRequestAndAllowsTheNextTask() {
        respond = { attempt, exchange ->
            if (attempt == 1) exchange.reply(503, "{\"error\":\"busy\"}")
            else exchange.reply(200, success("Next task"))
        }
        val cancelled = mutableListOf<VoiceJobController.ConnectionTestResult>()
        assertTrue(controller.testPostProcessingConnection(runtime(retryEnabled = true)) { cancelled += it })
        await { controller.currentState().state == JobState.RETRY_WAITING }

        controller.handleDoubleTap(JobState.RETRY_WAITING)
        assertEquals(JobState.IDLE, controller.currentState().state)
        assertFalse(cancelled.single().success)
        assertEquals("Test cancelled", cancelled.single().message)

        val next = mutableListOf<VoiceJobController.ConnectionTestResult>()
        assertTrue(controller.testPostProcessingConnection(runtime()) { next += it })
        await { next.isNotEmpty() }
        assertTrue(next.single().success)

        // Cross the original retry deadline, then flush any callback it could have posted.
        (executor("scheduler") as ScheduledExecutorService)
            .schedule({}, 200L, TimeUnit.MILLISECONDS)
            .get(5L, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        drainWorker()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(2, requestCount.get())
        assertEquals(1, cancelled.size)
        assertEquals(1, next.size)
        assertEquals(JobState.IDLE, controller.currentState().state)
        assertNoDeliverySideEffects()
    }

    private fun runtime(retryEnabled: Boolean = false): RuntimeSettings = RuntimeSettings(
        app = AppSettings(
            retry = RetryConfig(enabled = retryEnabled, maxRetries = 1, initialBackoffSeconds = 0.1),
            postProcessing = PostProcessingConfig(
                provider = PostProcessingProvider.OPENAI_COMPATIBLE,
                baseUrl = "http://127.0.0.1:${server.localPort}/v1",
                model = "local-test-model",
            ),
        ),
        apiKey = "",
        postProcessingApiKey = "local-test-key",
    )

    private fun assertNoDeliverySideEffects() {
        assertTrue("Local server failed: ${serverErrors.firstOrNull()}", serverErrors.isEmpty())
        assertEquals(CLIPBOARD_TEXT, clipboard.primaryClip!!.getItemAt(0).text.toString())
        val retained = checkNotNull(application.audioFileStore.lastRecording())
        assertEquals(previousRecording, retained.file)
        assertEquals(Pcm16Format.LEGACY_MONO_16_KHZ, retained.format)
        assertArrayEquals(recordingBytes, retained.file.readBytes())
    }

    private fun drainWorker() {
        executor("worker").submit {}.get(5L, TimeUnit.SECONDS)
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
        assertTrue("Timed out waiting for post-processing: ${controller.currentState()}", condition())
    }

    private companion object {
        const val CLIPBOARD_TEXT = "Keep the existing clipboard"

        fun success(text: String): String = """{"choices":[{"message":{"content":"$text"}}]}"""

        fun Socket.readRequestBody(): String {
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
            check(headerText.startsWith("POST /v1/chat/completions ") || headerText.startsWith("POST /v1/messages "))
            val length = headerText.lineSequence()
                .first { it.startsWith("Content-Length:", ignoreCase = true) }
                .substringAfter(':').trim().toInt()
            val payload = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(payload, offset, length - offset)
                check(count > 0) { "Connection closed before request body" }
                offset += count
            }
            return payload.toString(Charsets.UTF_8)
        }

        fun Socket.reply(status: Int, body: String, contentType: String = "application/json") {
            val bytes = body.toByteArray(Charsets.UTF_8)
            getOutputStream().apply {
                write(
                    ("HTTP/1.1 $status Test\r\nContent-Type: $contentType\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                        .toByteArray(Charsets.UTF_8),
                )
                write(bytes)
                flush()
            }
        }
    }
}
