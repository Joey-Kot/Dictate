package com.joeykot.dictate.advanced_audio

import com.joeykot.dictate.advanced_audio.http.AdvancedCancellationToken
import com.joeykot.dictate.advanced_audio.http.AdvancedCancellationSource
import com.joeykot.dictate.advanced_audio.http.AdvancedHttpMethod
import com.joeykot.dictate.advanced_audio.http.AdvancedHttpTransport
import com.joeykot.dictate.advanced_audio.http.HttpBodySpec
import com.joeykot.dictate.advanced_audio.http.HttpHeader
import com.joeykot.dictate.advanced_audio.http.HttpRequestSpec
import com.joeykot.dictate.advanced_audio.http.HttpResponse
import com.joeykot.dictate.advanced_audio.http.HttpTransportException
import com.joeykot.dictate.model.AdvancedAudioConfig
import com.joeykot.dictate.model.AdvancedRemoteAudioConfig
import com.joeykot.dictate.model.RetryConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class AdvancedAudioClientTest {
    @Test
    fun requestReturnsTheExtractedFinalText() = withAudio { audio ->
        val transport = FakeTransport(ScriptedOutcome.Response(response(200, "{\"text\":\"synchronous result\"}")))
        val result = run(workflowConfig(requestRecognition()), transport, audio)

        assertTrue(result is AdvancedAudioClient.Result.Success)
        assertEquals("synchronous result", (result as AdvancedAudioClient.Result.Success).text)
        assertEquals(200, result.statusCode)
        assertEquals(1, transport.requests.size)
        assertEquals(AdvancedHttpMethod.POST, transport.requests.single().method)
    }

    @Test
    fun asyncSubmitDoesNotRetryNetworkOrServerFailures() = withAudio { audio ->
        listOf<ScriptedOutcome>(
            ScriptedOutcome.Failure(HttpTransportException.NetworkFailure(IOException("offline"))),
            ScriptedOutcome.Response(response(503, "{\"error\":\"unavailable\"}")),
        ).forEach { failure ->
            val transport = FakeTransport(failure)
            val result = run(workflowConfig(asyncRecognition()), transport, audio, retrying())

            assertTrue(result is AdvancedAudioClient.Result.Failure)
            assertEquals(1, transport.requests.count { it.url.endsWith("/submit") })
            assertEquals(1, transport.requests.size)
        }
    }

    @Test
    fun pollRetriesItsOwnNetworkAndServerFailuresWithoutResubmitting() = withAudio { audio ->
        listOf<ScriptedOutcome>(
            ScriptedOutcome.Failure(HttpTransportException.NetworkFailure(IOException("offline"))),
            ScriptedOutcome.Response(response(503, "{\"status\":\"pending\"}")),
        ).forEach { pollFailure ->
            val transport = FakeTransport(
                ScriptedOutcome.Response(response(202, "{\"task\":\"one\"}")),
                pollFailure,
                ScriptedOutcome.Response(response(200, "{\"status\":\"succeeded\",\"text\":\"polled\"}")),
            )
            val result = run(workflowConfig(asyncRecognition(withPoll = true)), transport, audio, retrying())

            assertTrue(result is AdvancedAudioClient.Result.Success)
            assertEquals("polled", (result as AdvancedAudioClient.Result.Success).text)
            assertEquals(1, transport.requests.count { it.url.endsWith("/submit") })
            assertEquals(2, transport.requests.count { it.url.endsWith("/poll") })
        }
    }

    @Test
    fun pollRetryDelayCannotOutrunThePollDeadline() = withAudio { audio ->
        val transport = FakeTransport(
            ScriptedOutcome.Response(response(202, "{\"task\":\"one\"}")),
            ScriptedOutcome.Failure(HttpTransportException.NetworkFailure(IOException("offline"))),
            ScriptedOutcome.Response(response(200, "{\"status\":\"succeeded\",\"text\":\"late\"}")),
        )
        val result = run(
            workflowConfig(asyncRecognition(withPoll = true)),
            transport,
            audio,
            RetryConfig(enabled = true, maxRetries = 1, initialBackoffSeconds = 1.0),
        )

        assertTrue(result is AdvancedAudioClient.Result.Failure)
        assertTrue((result as AdvancedAudioClient.Result.Failure).message.contains("timed out"))
        assertEquals(1, transport.requests.count { it.url.endsWith("/submit") })
        assertEquals(1, transport.requests.count { it.url.endsWith("/poll") })
    }

    @Test
    fun pollResponseThatArrivesAfterItsDeadlineIsNotAccepted() = withAudio { audio ->
        val transport = FakeTransport(
            ScriptedOutcome.Response(response(202, "{\"task\":\"one\"}")),
            ScriptedOutcome.DelayedResponse(
                delayMillis = 125,
                response = response(200, "{\"status\":\"succeeded\",\"text\":\"late\"}"),
            ),
        )
        val result = run(workflowConfig(asyncRecognition(withPoll = true)), transport, audio)

        assertTrue(result is AdvancedAudioClient.Result.Failure)
        assertTrue((result as AdvancedAudioClient.Result.Failure).message.contains("timed out"))
        assertEquals(1, transport.requests.count { it.url.endsWith("/submit") })
        assertEquals(1, transport.requests.count { it.url.endsWith("/poll") })
    }

    @Test
    fun pollPassesItsRemainingDeadlineToTheInFlightTransportCall() = withAudio { audio ->
        val transport = FakeTransport(
            ScriptedOutcome.Response(response(202, "{\"task\":\"one\"}")),
            ScriptedOutcome.DelayedResponse(
                delayMillis = 125,
                response = response(200, "{\"status\":\"succeeded\",\"text\":\"late\"}"),
            ),
        )

        val result = run(workflowConfig(asyncRecognition(withPoll = true)), transport, audio)

        assertTrue(result is AdvancedAudioClient.Result.Failure)
        assertTrue((result as AdvancedAudioClient.Result.Failure).message.contains("timed out"))
        assertEquals(2, transport.callTimeouts.size)
        assertEquals(null, transport.callTimeouts[0])
        assertTrue((transport.callTimeouts[1] ?: 0L) in 1L..100L)
    }

    @Test
    fun capturesAreBoundedPerStageAndRemainAvailableAcrossStages() = withAudio { audio ->
        val captureValue = "x".repeat(200 * 1024)
        val prepare = HttpStage(
            method = HttpMethod.GET,
            url = "https://workflow.test/prepare",
            acceptedStatuses = listOf(200),
            captures = listOf(
                Capture("prepare_first", ResponseExtractor.JsonPath("$.first")),
                Capture("prepare_second", ResponseExtractor.JsonPath("$.second")),
            ),
        )
        val submit = audioSubmitStage("https://workflow.test/submit", acceptedStatuses = listOf(200)).copy(
            captures = listOf(
                Capture("submit_first", ResponseExtractor.JsonPath("$.first")),
                Capture("submit_second", ResponseExtractor.JsonPath("$.second")),
            ),
        )
        val workflow = AsyncPollRecognition(
            prepare = prepare,
            submit = submit,
            finalText = ResponseExtractor.JsonPath("$.text"),
        )
        val transport = FakeTransport(
            ScriptedOutcome.Response(response(200, "{\"first\":\"$captureValue\",\"second\":\"$captureValue\"}")),
            ScriptedOutcome.Response(
                response(200, "{\"first\":\"$captureValue\",\"second\":\"$captureValue\",\"text\":\"complete\"}"),
            ),
        )

        val result = run(workflowConfig(workflow), transport, audio)

        assertTrue(result is AdvancedAudioClient.Result.Success)
        assertEquals("complete", (result as AdvancedAudioClient.Result.Success).text)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun pollComparisonAndBooleanConditionsFailWhenTheirExtractorIsMissing() = withAudio { audio ->
        listOf(
            PollOperator.EQ to "missing-value",
            PollOperator.NE to "missing-value",
            PollOperator.IS_TRUE to null,
            PollOperator.IS_FALSE to null,
        ).forEach { (operator, value) ->
            val poll = PollStage(
                request = HttpStage(HttpMethod.GET, "https://workflow.test/poll", acceptedStatuses = listOf(200)),
                intervalMs = 100,
                timeoutMs = 100,
                pending = listOf(PollCondition(ResponseExtractor.Status, PollOperator.EQ, "201")),
                success = listOf(PollCondition(ResponseExtractor.Status, PollOperator.EQ, "200")),
                failure = listOf(
                    PollCondition(ResponseExtractor.JsonPath("$.missing"), operator, value),
                ),
            )
            val workflow = AsyncPollRecognition(
                submit = audioSubmitStage("https://workflow.test/submit", acceptedStatuses = listOf(202)),
                poll = poll,
                finalText = ResponseExtractor.JsonPath("$.text"),
            )
            val transport = FakeTransport(
                ScriptedOutcome.Response(response(202, "{\"task\":\"one\"}")),
                ScriptedOutcome.Response(response(200, "{\"text\":\"unexpected success\"}")),
            )

            val result = run(workflowConfig(workflow), transport, audio)

            assertTrue("$operator must fail extraction", result is AdvancedAudioClient.Result.Failure)
            assertEquals(
                "Advanced Audio API response could not be extracted",
                (result as AdvancedAudioClient.Result.Failure).message,
            )
            assertEquals(1, transport.requests.count { it.url.endsWith("/submit") })
            assertEquals(1, transport.requests.count { it.url.endsWith("/poll") })
        }
    }

    @Test
    fun readonlyResultRetriesWithoutResubmitting() = withAudio { audio ->
        val transport = FakeTransport(
            ScriptedOutcome.Response(response(202, "{\"task\":\"one\"}")),
            ScriptedOutcome.Response(response(503, "{\"error\":\"retry\"}")),
            ScriptedOutcome.Response(response(200, "{\"text\":\"result step\"}")),
        )
        val result = run(workflowConfig(asyncRecognition(withResultStep = true)), transport, audio, retrying())

        assertTrue(result is AdvancedAudioClient.Result.Success)
        assertEquals("result step", (result as AdvancedAudioClient.Result.Success).text)
        assertEquals(1, transport.requests.count { it.url.endsWith("/submit") })
        assertEquals(2, transport.requests.count { it.url.endsWith("/result") })
    }

    @Test
    fun streamDoesNotReturnPartialTextBeforeAnExplicitCompleteEvent() = withAudio { audio ->
        val transport = FakeTransport(
            ScriptedOutcome.Response(response(200, "data: {\"delta\":\"partial\"}\n\n")),
        )
        val result = run(workflowConfig(streamRecognition()), transport, audio)

        assertTrue(result is AdvancedAudioClient.Result.Failure)
        assertFalse((result as AdvancedAudioClient.Result.Failure).message.contains("partial"))
    }

    @Test
    fun streamReturnsTextAfterItsExplicitCompleteEvent() = withAudio { audio ->
        val transport = FakeTransport(
            ScriptedOutcome.Response(
                response(
                    200,
                    "data: {\"delta\":\"complete text\"}\n\n" +
                        "event: done\n" +
                        "data: {}\n\n",
                ),
            ),
        )
        val result = run(workflowConfig(streamRecognition()), transport, audio)

        assertTrue(result is AdvancedAudioClient.Result.Success)
        assertEquals("complete text", (result as AdvancedAudioClient.Result.Success).text)
    }

    @Test
    fun remoteDeliveryRejectsMissingRemoteConfigurationBeforeAnyRequest() = withAudio { audio ->
        val transport = FakeTransport()
        val workflow = AdvancedAudioWorkflow(
            schemaVersion = LEGACY_WORKFLOW_SCHEMA_VERSION,
            name = "remote",
            audio = AudioSpec(AudioDeliveryType.PUBLIC_HTTPS_URL, "audio/wav"),
            recognition = RequestRecognition(
                request = HttpStage(
                    method = HttpMethod.POST,
                    url = "https://workflow.test/request",
                    body = HttpBody.Json(
                        JsonValue.Object(linkedMapOf("url" to JsonValue.Text("{{audio:public_url}}"))),
                    ),
                ),
                finalText = ResponseExtractor.JsonPath("$.text"),
            ),
        )

        val result = run(workflowConfig(workflow), transport, audio)

        assertTrue(result is AdvancedAudioClient.Result.Failure)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun unsafeStaticOrRenderedHeadersAreRejectedBeforeRemoteAudioUpload() = withAudio { audio ->
        val remoteSecrets = mapOf("remote-user" to "user", "remote-password" to "password")
        val remoteConfig = AdvancedRemoteAudioConfig.WebDav(
            uploadBaseUrl = "https://storage.test/upload",
            publicDownloadBaseUrl = "https://download.test/audio",
            usernameSecretId = "remote-user",
            passwordSecretId = "remote-password",
        )
        val recognition = RequestRecognition(
            request = HttpStage(
                method = HttpMethod.POST,
                url = "https://workflow.test/request",
                headers = mapOf("Authorization" to "{{var:token}}"),
                body = HttpBody.Json(
                    JsonValue.Object(linkedMapOf("url" to JsonValue.Text("{{audio:public_url}}"))),
                ),
            ),
            finalText = ResponseExtractor.JsonPath("$.text"),
        )
        val workflow = AdvancedAudioWorkflow(
            schemaVersion = CURRENT_WORKFLOW_SCHEMA_VERSION,
            name = "remote header preflight",
            parameters = listOf(
                ParameterDefinition(
                    id = "token",
                    label = "Token",
                    required = true,
                    parameterType = ParameterType.TEXT,
                ),
            ),
            audio = AudioSpec(AudioDeliveryType.PUBLIC_HTTPS_URL, "audio/wav"),
            recognition = recognition,
        )
        val transport = FakeTransport()
        val result = run(
            config = workflowConfig(workflow).copy(
                values = mapOf("token" to "Bearer valid\r\nX-Injected: true"),
                remoteAudio = remoteConfig,
            ),
            transport = transport,
            audio = audio,
            secrets = remoteSecrets,
        )

        assertTrue(result is AdvancedAudioClient.Result.Failure)
        // The RemoteAudioPublisher uses the same fake transport. A request
        // here would prove that a dynamic header escaped the preflight gate.
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun cancellationAfterRecognitionCompletionForcesRemoteAudioCleanup() = withAudio { audio ->
        val cancellation = AdvancedCancellationSource()
        val transport = FakeTransport(
            ScriptedOutcome.Response(response(201, "")),
            ScriptedOutcome.Response(response(200, "{\"text\":\"ok\"}")),
            ScriptedOutcome.Response(response(204, "")),
        )
        val workflow = AdvancedAudioWorkflow(
            schemaVersion = LEGACY_WORKFLOW_SCHEMA_VERSION,
            name = "remote cleanup cancellation",
            audio = AudioSpec(AudioDeliveryType.PUBLIC_HTTPS_URL, "audio/wav"),
            recognition = RequestRecognition(
                request = HttpStage(
                    method = HttpMethod.POST,
                    url = "https://workflow.test/request",
                    body = HttpBody.Json(
                        JsonValue.Object(linkedMapOf("url" to JsonValue.Text("{{audio:public_url}}"))),
                    ),
                    acceptedStatuses = listOf(200),
                ),
                finalText = ResponseExtractor.JsonPath("$.text"),
            ),
        )
        val config = workflowConfig(workflow).copy(
            remoteAudio = AdvancedRemoteAudioConfig.WebDav(
                uploadBaseUrl = "https://storage.test/upload",
                publicDownloadBaseUrl = "https://download.test/audio",
                usernameSecretId = "remote-user",
                passwordSecretId = "remote-password",
                deleteAfterRecognition = false,
            ),
        )
        val client = AdvancedAudioClient(transport).apply {
            afterRecognitionCompletedBeforeCleanupForTest = { cancellation.cancel() }
        }

        val result = client.transcribe(
            AdvancedAudioClient.Request(
                config = config,
                secrets = mapOf("remote-user" to "user", "remote-password" to "password"),
                audioFile = audio,
                mimeType = "audio/wav",
            ),
            cancellation.token,
        )

        assertTrue(result is AdvancedAudioClient.Result.Cancelled)
        assertEquals(
            listOf(AdvancedHttpMethod.PUT, AdvancedHttpMethod.POST, AdvancedHttpMethod.DELETE),
            transport.requests.map(HttpRequestSpec::method),
        )
    }

    private fun requestRecognition(): RequestRecognition = RequestRecognition(
        request = audioSubmitStage("https://workflow.test/request", acceptedStatuses = listOf(200)),
        finalText = ResponseExtractor.JsonPath("$.text"),
    )

    private fun asyncRecognition(
        withPoll: Boolean = false,
        withResultStep: Boolean = false,
    ): AsyncPollRecognition = AsyncPollRecognition(
        submit = audioSubmitStage("https://workflow.test/submit", acceptedStatuses = listOf(202)),
        poll = if (withPoll) {
            PollStage(
                request = HttpStage(HttpMethod.GET, "https://workflow.test/poll", acceptedStatuses = listOf(200)),
                intervalMs = 100,
                timeoutMs = 100,
                pending = listOf(PollCondition(ResponseExtractor.JsonPath("$.status"), PollOperator.EQ, "pending")),
                success = listOf(PollCondition(ResponseExtractor.JsonPath("$.status"), PollOperator.EQ, "succeeded")),
                failure = listOf(PollCondition(ResponseExtractor.JsonPath("$.status"), PollOperator.EQ, "failed")),
            )
        } else {
            null
        },
        resultSteps = if (withResultStep) {
            listOf(HttpStage(HttpMethod.GET, "https://workflow.test/result", acceptedStatuses = listOf(200)))
        } else {
            emptyList()
        },
        finalText = ResponseExtractor.JsonPath("$.text"),
    )

    private fun streamRecognition(): RequestStreamRecognition = RequestStreamRecognition(
        request = audioSubmitStage("https://workflow.test/stream", acceptedStatuses = listOf(200)),
        stream = StreamResponse(
            format = StreamFormat.SSE,
            rules = listOf(
                StreamRule(path = "$.delta", action = StreamAction.APPEND_DELTA),
                StreamRule(event = "done", action = StreamAction.COMPLETE),
            ),
        ),
    )

    private fun audioSubmitStage(url: String, acceptedStatuses: List<Int>): HttpStage = HttpStage(
        method = HttpMethod.POST,
        url = url,
        body = HttpBody.Multipart(listOf(MultipartField("file", MultipartValue.AudioFile))),
        acceptedStatuses = acceptedStatuses,
    )

    private fun workflowConfig(recognition: Recognition): AdvancedAudioConfig = workflowConfig(
        AdvancedAudioWorkflow(
            schemaVersion = LEGACY_WORKFLOW_SCHEMA_VERSION,
            name = "test workflow",
            audio = AudioSpec(AudioDeliveryType.MULTIPART_FILE, "audio/wav"),
            recognition = recognition,
        ),
    )

    private fun workflowConfig(workflow: AdvancedAudioWorkflow): AdvancedAudioConfig = AdvancedAudioConfig(
        enabled = true,
        workflowJson = AdvancedAudioWorkflowCodec.encode(workflow),
    )

    private fun run(
        config: AdvancedAudioConfig,
        transport: FakeTransport,
        audio: File,
        retry: RetryConfig = RetryConfig(),
        secrets: Map<String, String> = emptyMap(),
    ): AdvancedAudioClient.Result = AdvancedAudioClient(transport).transcribe(
        AdvancedAudioClient.Request(
            config = config,
            secrets = secrets,
            audioFile = audio,
            mimeType = "audio/wav",
            retry = retry,
        ),
    )

    private fun retrying(): RetryConfig = RetryConfig(
        enabled = true,
        maxRetries = 2,
        initialBackoffSeconds = 0.0,
    )

    private fun response(status: Int, body: String): FakeResponse = FakeResponse(status, body.toByteArray())

    private fun withAudio(block: (File) -> Unit) {
        val audio = File.createTempFile("advanced-client-", ".wav").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        try {
            block(audio)
        } finally {
            audio.delete()
        }
    }

    private sealed interface ScriptedOutcome {
        data class Response(val response: FakeResponse) : ScriptedOutcome
        data class DelayedResponse(val delayMillis: Long, val response: FakeResponse) : ScriptedOutcome
        data class Failure(val error: Throwable) : ScriptedOutcome
    }

    private class FakeTransport(vararg planned: ScriptedOutcome) : AdvancedHttpTransport {
        private val planned = planned.toMutableList()
        val requests = mutableListOf<HttpRequestSpec>()
        val callTimeouts = mutableListOf<Long?>()

        override fun execute(
            request: HttpRequestSpec,
            cancellation: AdvancedCancellationToken,
            callTimeoutMillis: Long?,
        ): HttpResponse {
            requests += request
            callTimeouts += callTimeoutMillis
            cancellation.throwIfCancelled()
            return when (val outcome = planned.removeFirstOrNull() ?: error("Unexpected request")) {
                is ScriptedOutcome.Response -> outcome.response.copy()
                is ScriptedOutcome.DelayedResponse -> {
                    Thread.sleep(outcome.delayMillis)
                    outcome.response.copy()
                }
                is ScriptedOutcome.Failure -> throw outcome.error
            }
        }
    }

    private class FakeResponse(
        override val statusCode: Int,
        private val bytes: ByteArray,
        override val headers: List<HttpHeader> = emptyList(),
    ) : HttpResponse {
        override fun readBody(maxBytes: Int): ByteArray {
            check(bytes.size <= maxBytes)
            return bytes.copyOf()
        }

        override fun openBodyStream(): InputStream = ByteArrayInputStream(bytes.copyOf())

        override fun close() = Unit

        fun copy(): FakeResponse = FakeResponse(statusCode, bytes.copyOf(), headers.toList())
    }
}
