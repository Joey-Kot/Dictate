package com.joeykot.dictate.advanced_audio.realtime

import com.joeykot.dictate.advanced_audio.AdvancedAudioWorkflow
import com.joeykot.dictate.advanced_audio.AudioDeliveryType
import com.joeykot.dictate.advanced_audio.AudioSpec
import com.joeykot.dictate.advanced_audio.JsonValue
import com.joeykot.dictate.advanced_audio.ParameterDefinition
import com.joeykot.dictate.advanced_audio.ParameterType
import com.joeykot.dictate.advanced_audio.RealtimeAudioMessage
import com.joeykot.dictate.advanced_audio.RealtimeAudioStream
import com.joeykot.dictate.advanced_audio.RealtimeCompletion
import com.joeykot.dictate.advanced_audio.RealtimeConnect
import com.joeykot.dictate.advanced_audio.RealtimeMessage
import com.joeykot.dictate.advanced_audio.RealtimePacing
import com.joeykot.dictate.advanced_audio.RealtimeSessionRecognition
import com.joeykot.dictate.advanced_audio.RealtimeTransport
import com.joeykot.dictate.advanced_audio.RealtimeWorkflow
import com.joeykot.dictate.advanced_audio.RuntimeTemplateValues
import com.joeykot.dictate.advanced_audio.SecretDefinition
import com.joeykot.dictate.advanced_audio.SignerConfig
import com.joeykot.dictate.advanced_audio.StreamAction
import com.joeykot.dictate.advanced_audio.StreamRule
import com.joeykot.dictate.advanced_audio.http.AdvancedCancellationSource
import com.joeykot.dictate.advanced_audio.http.AdvancedCancellationToken
import com.joeykot.dictate.model.Pcm16Format
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

class RealtimeSessionRunnerTest {
    @Test
    fun rendersV2ConnectionInitialAudioAndFinishThenReturnsOnlyAfterCompletion() {
        val socket = FakeWebSocket()
        socket.onText = { text ->
            when {
                text.contains("\"event\":\"audio\"") -> {
                    socket.events += RealtimeWebSocketEvent.Text("{\"event\":\"delta\",\"text\":\"hello\"}")
                }

                text == "finish" -> socket.events += RealtimeWebSocketEvent.Text("{\"event\":\"done\"}")
            }
        }
        val factory = FakeFactory(socket)
        val result = RealtimeSessionRunner(factory).run(
            workflow = workflow(
                initial = listOf(
                    RealtimeMessage.Json(
                        JsonValue.Object(
                            linkedMapOf(
                                "event" to JsonValue.Text("start"),
                                "sample_rate" to JsonValue.Text("{{var:sample_rate}}"),
                            ),
                        ),
                    ),
                ),
                audioMessage = RealtimeAudioMessage.Json(
                    JsonValue.Object(
                        linkedMapOf(
                            "event" to JsonValue.Text("audio"),
                            "data" to JsonValue.Text("{{audio:chunk_base64}}"),
                        ),
                    ),
                ),
                finish = listOf(RealtimeMessage.Text("finish")),
                rules = listOf(StreamRule(event = "delta", path = "$.text", action = StreamAction.APPEND_DELTA)),
                completion = RealtimeCompletion(event = "done"),
                query = mapOf("language" to "{{var:language}}"),
                headers = mapOf("X-Mode" to "test"),
                subprotocol = "dictate-v1",
            ),
            values = mapOf("sample_rate" to "16000", "language" to "zh"),
            secrets = emptyMap(),
            runtime = RuntimeTemplateValues(uuid = "session-id"),
            source = ListSource(listOf(RealtimeAudioChunk(byteArrayOf(1, 2, 3), 0L))),
        )

        assertEquals("hello", result.text)
        assertEquals(
            listOf(
                "{\"event\":\"start\",\"sample_rate\":16000}",
                "{\"event\":\"audio\",\"data\":\"AQID\"}",
                "finish",
            ),
            socket.textFrames,
        )
        assertTrue(socket.closed)
        val request = requireNotNull(factory.request)
        assertEquals("https", request.url.substringBefore(':'))
        assertTrue(request.url.contains("language=zh"))
        assertTrue(request.headers.any { it.name.equals("X-Mode", ignoreCase = true) && it.value == "test" })
        assertTrue(
            request.headers.any {
                it.name.equals("Sec-WebSocket-Protocol", ignoreCase = true) && it.value == "dictate-v1"
            },
        )
    }

    @Test
    fun acceptsBinaryUtf8JsonAndUsesExplicitCompletionBeforeReturningFinalText() {
        val socket = FakeWebSocket().apply {
            onText = { text ->
                if (text == "finish") {
                    events += RealtimeWebSocketEvent.Binary(
                        "{\"type\":\"completed\",\"transcript\":\"final text\"}".toByteArray(),
                    )
                }
            }
        }
        val result = RealtimeSessionRunner(FakeFactory(socket)).run(
            workflow = workflow(
                finish = listOf(RealtimeMessage.Text("finish")),
                rules = listOf(
                    StreamRule(
                        event = "completed",
                        path = "$.transcript",
                        action = StreamAction.SET_FINAL_TEXT,
                    ),
                ),
                completion = RealtimeCompletion(event = "completed"),
            ),
            values = emptyMap(),
            secrets = emptyMap(),
            runtime = RuntimeTemplateValues(),
            source = ListSource(emptyList()),
        )

        assertEquals("final text", result.text)
    }

    @Test
    fun rejectsInvalidBinaryTextWithoutReturningPartialTranscript() {
        val socket = FakeWebSocket().apply {
            onText = { text ->
                if (text == "finish") events += RealtimeWebSocketEvent.Binary(byteArrayOf(0xC3.toByte(), 0x28))
            }
        }
        val failure = capture {
            RealtimeSessionRunner(FakeFactory(socket)).run(
                workflow = workflow(finish = listOf(RealtimeMessage.Text("finish"))),
                values = emptyMap(),
                secrets = emptyMap(),
                runtime = RuntimeTemplateValues(),
                source = ListSource(emptyList()),
            )
        }

        assertEquals(RealtimeSessionException.InvalidIncomingJsonOrText, failure)
    }

    @Test
    fun timesOutWhenNoExplicitCompletionArrives() {
        val source = ListSource(emptyList())
        val failure = capture {
            RealtimeSessionRunner(FakeFactory(FakeWebSocket())).run(
                workflow = workflow(finalizationTimeoutMs = 1L),
                values = emptyMap(),
                secrets = emptyMap(),
                runtime = RuntimeTemplateValues(),
                source = source,
            )
        }

        assertEquals(RealtimeSessionException.FinalizationTimeout, failure)
        assertTrue(source.closed)
    }

    @Test
    fun liveCompletionBeforeRecorderEndIsRejectedForSafeFullReplay() {
        val socket = FakeWebSocket().apply {
            onBinary = {
                events += RealtimeWebSocketEvent.Text("{\"event\":\"done\"}")
            }
        }
        val source = UnfinishedLiveSource()
        val failure = capture {
            RealtimeSessionRunner(FakeFactory(socket)).run(
                workflow = workflow(),
                values = emptyMap(),
                secrets = emptyMap(),
                runtime = RuntimeTemplateValues(),
                source = source,
            )
        }

        assertEquals(RealtimeSessionException.LiveCompletedBeforeSourceEnd, failure)
        assertTrue(source.closed)
    }

    @Test
    fun cancellationClosesConsumedSourceWithoutOpeningAConnection() {
        val source = ListSource(emptyList())
        val factory = FakeFactory(FakeWebSocket())
        val cancellation = AdvancedCancellationSource()
        cancellation.cancel()
        val failure = capture {
            RealtimeSessionRunner(factory).run(
                workflow = workflow(),
                values = emptyMap(),
                secrets = emptyMap(),
                runtime = RuntimeTemplateValues(),
                source = source,
                cancellation = cancellation.token,
            )
        }

        assertEquals(RealtimeSessionException.SessionCancelled, failure)
        assertTrue(source.closed)
        assertFalse(factory.connected)
    }

    @Test
    fun renderedHeaderInjectionIsRejectedBeforeOpeningAWebsocketConnection() {
        val factory = FakeFactory(FakeWebSocket())
        val baseWorkflow = workflow(headers = mapOf("Authorization" to "{{var:token}}"))
        val injectedWorkflow = baseWorkflow.copy(
            parameters = listOf(
                ParameterDefinition(
                    id = "token",
                    label = "Token",
                    required = true,
                    parameterType = ParameterType.TEXT,
                ),
            ),
        )
        val failure = capture {
            RealtimeSessionRunner(factory).run(
                workflow = injectedWorkflow,
                values = mapOf("token" to "Bearer safe\r\nX-Injected: true"),
                secrets = emptyMap(),
                runtime = RuntimeTemplateValues(),
                source = ListSource(emptyList()),
            )
        }

        assertEquals(RealtimeSessionException.InvalidConfiguration, failure)
        assertFalse(factory.connected)
    }

    @Test
    fun signsTheWebsocketHandshakeWithDeclaredAwsCredentials() {
        val socket = FakeWebSocket().apply {
            events += RealtimeWebSocketEvent.Text("{\"event\":\"done\"}")
        }
        val factory = FakeFactory(socket)
        RealtimeSessionRunner(factory).run(
            workflow = workflow(
                signer = SignerConfig.AwsSigv4(
                    region = "us-east-1",
                    service = "transcribe",
                    accessKeySecret = "aws_access",
                    secretKeySecret = "aws_secret",
                ),
            ),
            values = emptyMap(),
            secrets = mapOf("aws_access" to "access", "aws_secret" to "secret"),
            runtime = RuntimeTemplateValues(),
            source = ListSource(emptyList()),
        )

        val request = requireNotNull(factory.request)
        assertTrue(
            request.headers.any {
                it.name.equals("Authorization", ignoreCase = true) &&
                    it.value.startsWith("AWS4-HMAC-SHA256 ")
            },
        )
        assertTrue(request.headers.any { it.name.equals("X-Amz-Date", ignoreCase = true) })
        assertTrue(request.headers.any { it.name.equals("Host", ignoreCase = true) })
    }

    @Test
    fun pcmReplayUsesFractionalFramesAndClosesAtEndOfFile() {
        val file = Files.createTempFile("dictate-realtime-", ".pcm").toFile()
        try {
            file.writeBytes(ByteArray(441 * 2))
            val source = Pcm16ReplayChunkSource(
                file,
                RealtimeAudioStream(
                    codec = "pcm_s16le",
                    sampleRate = 11_025,
                    channels = 1,
                    chunkDurationMs = 10,
                    pacing = RealtimePacing.REALTIME,
                ),
            )
            val cancellation = AdvancedCancellationToken.none()
            val chunks = buildList {
                while (true) add(source.nextChunk(cancellation) ?: break)
            }

            assertEquals(listOf(220, 220, 220, 222), chunks.map { it.bytes.size })
            assertEquals(40L, chunks.sumOf { it.durationMillis })
            assertTrue(source.requiresRealtimePacing)
            assertEquals(null, source.nextChunk(cancellation))
        } finally {
            file.delete()
        }
    }

    @Test
    fun pcmReplayRejectsPartialFrames() {
        val file = Files.createTempFile("dictate-realtime-invalid-", ".pcm").toFile()
        try {
            file.writeBytes(byteArrayOf(1))
            val failure = capture {
                Pcm16ReplayChunkSource(
                    file,
                    RealtimeAudioStream("pcm_s16le", 16_000, 1, 20),
                )
            }
            assertEquals(RealtimeChunkSourceException.InvalidPcmInput, failure)
        } finally {
            file.delete()
        }
    }

    @Test
    fun pcmReplayResamplesAndDownmixesActualCaptureFormatToWorkflowTarget() {
        val file = Files.createTempFile("dictate-realtime-stereo-", ".pcm").toFile()
        try {
            // 20 ms of 48 kHz stereo becomes exactly one 20 ms 16 kHz mono
            // websocket chunk. The right channel is silent, so downmixing
            // produces half-scale samples.
            val input = ByteArray(960 * 2 * 2)
            repeat(960) { frame ->
                val offset = frame * 4
                input[offset] = Short.MAX_VALUE.toByte()
                input[offset + 1] = (Short.MAX_VALUE.toInt() ushr 8).toByte()
            }
            file.writeBytes(input)
            val source = Pcm16ReplayChunkSource(
                pcmFile = file,
                target = RealtimeAudioStream("pcm_s16le", 16_000, 1, 20),
                sourceFormat = Pcm16Format(sampleRateHz = 48_000, channelCount = 2),
            )

            val chunk = requireNotNull(source.nextChunk(AdvancedCancellationToken.none()))
            assertEquals(640, chunk.bytes.size)
            assertEquals(20L, chunk.durationMillis)
            val first = (chunk.bytes[0].toInt() and 0xff) or (chunk.bytes[1].toInt() shl 8)
            assertEquals(16_383, first.toShort().toInt())
            assertEquals(null, source.nextChunk(AdvancedCancellationToken.none()))
        } finally {
            file.delete()
        }
    }

    @Test
    fun pcmResamplerPreservesPhaseAcrossArbitraryPacketsForUpAndDownSampling() {
        listOf(
            Pcm16Format(sampleRateHz = 16_000, channelCount = 1) to 48_000,
            Pcm16Format(sampleRateHz = 48_000, channelCount = 1) to 16_000,
        ).forEach { (sourceFormat, targetRate) ->
            val inputFrames = 257
            val input = ByteArray(inputFrames * sourceFormat.bytesPerFrame)
            repeat(inputFrames) { frame ->
                val sample = ((frame * 7_919 % 65_536) - 32_768).toShort()
                val offset = frame * 2
                input[offset] = sample.toByte()
                input[offset + 1] = (sample.toInt() ushr 8).toByte()
            }
            val target = RealtimeAudioStream("pcm_s16le", targetRate, 1, 10)

            val oneShot = convertedBytes(sourceFormat, target, listOf(input))
            val packetFrames = intArrayOf(1, 17, 3, 41, 2, 29, 5, 61)
            val packets = buildList {
                var frame = 0
                var packet = 0
                while (frame < inputFrames) {
                    val count = minOf(packetFrames[packet % packetFrames.size], inputFrames - frame)
                    add(input.copyOfRange(frame * 2, (frame + count) * 2))
                    frame += count
                    packet += 1
                }
            }
            val incremental = convertedBytes(sourceFormat, target, packets)

            assertArrayEquals("${sourceFormat.sampleRateHz}Hz -> $targetRate Hz", oneShot, incremental)
            val expectedFrames = inputFrames.toLong() * targetRate / sourceFormat.sampleRateHz
            assertEquals(expectedFrames * 2L, incremental.size.toLong())
        }
    }

    private fun workflow(
        initial: List<RealtimeMessage> = emptyList(),
        audioMessage: RealtimeAudioMessage = RealtimeAudioMessage.Binary,
        finish: List<RealtimeMessage> = emptyList(),
        rules: List<StreamRule> = listOf(StreamRule(event = "ignored", action = StreamAction.IGNORE)),
        completion: RealtimeCompletion = RealtimeCompletion(event = "done"),
        finalizationTimeoutMs: Long = 50L,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        subprotocol: String? = null,
        signer: SignerConfig = SignerConfig.None,
    ): AdvancedAudioWorkflow {
        val parameters = buildList {
            if (initial.isNotEmpty()) add(
                ParameterDefinition(
                    id = "sample_rate",
                    label = "Sample rate",
                    required = true,
                    parameterType = ParameterType.INTEGER,
                ),
            )
            if (query.isNotEmpty()) add(
                ParameterDefinition(
                    id = "language",
                    label = "Language",
                    required = true,
                    parameterType = ParameterType.TEXT,
                ),
            )
        }
        return AdvancedAudioWorkflow(
            schemaVersion = 2,
            name = "Realtime test",
            parameters = parameters,
            secrets = when (signer) {
                SignerConfig.None -> emptyList()
                is SignerConfig.AwsSigv4 -> listOf(
                    SecretDefinition(signer.accessKeySecret, "AWS access", required = true),
                    SecretDefinition(signer.secretKeySecret, "AWS secret", required = true),
                ) + listOfNotNull(
                    signer.sessionTokenSecret?.let { SecretDefinition(it, "AWS session", required = true) },
                )

                is SignerConfig.TencentTc3 -> listOf(
                    SecretDefinition(signer.secretIdSecret, "Tencent ID", required = true),
                    SecretDefinition(signer.secretKeySecret, "Tencent key", required = true),
                )
            },
            audio = AudioSpec(AudioDeliveryType.REALTIME_CHUNKS),
            recognition = RealtimeSessionRecognition(
                RealtimeWorkflow(
                    transport = RealtimeTransport.WEBSOCKET,
                    connect = RealtimeConnect(
                        url = "wss://example.test/realtime",
                        query = query,
                        headers = headers,
                        signer = signer,
                        subprotocol = subprotocol,
                    ),
                    initialMessages = initial,
                    audioStream = RealtimeAudioStream(
                        codec = "pcm_s16le",
                        sampleRate = 16_000,
                        channels = 1,
                        chunkDurationMs = 20,
                    ),
                    audioMessage = audioMessage,
                    receiveRules = rules,
                    finishMessages = finish,
                    completion = completion,
                    finalizationTimeoutMs = finalizationTimeoutMs,
                ),
            ),
        )
    }

    private fun convertedBytes(
        source: Pcm16Format,
        target: RealtimeAudioStream,
        packets: List<ByteArray>,
    ): ByteArray {
        val chunker = Pcm16RealtimeChunker(source, target)
        val output = ByteArrayOutputStream()
        packets.forEach { packet ->
            chunker.push(packet).forEach { chunk -> output.write(chunk.bytes) }
        }
        chunker.finish().forEach { chunk -> output.write(chunk.bytes) }
        return output.toByteArray()
    }

    private class FakeFactory(
        private val socket: FakeWebSocket,
    ) : RealtimeWebSocketFactory {
        var request: RealtimeWebSocketRequest? = null
        var connected = false

        override fun connect(
            request: RealtimeWebSocketRequest,
            cancellation: AdvancedCancellationToken,
        ): RealtimeWebSocket {
            cancellation.throwIfCancelled()
            this.request = request
            connected = true
            return socket
        }
    }

    private class FakeWebSocket : RealtimeWebSocket {
        val events = ArrayDeque<RealtimeWebSocketEvent>()
        val textFrames = mutableListOf<String>()
        val binaryFrames = mutableListOf<ByteArray>()
        var onText: (String) -> Unit = {}
        var onBinary: (ByteArray) -> Unit = {}
        var closed = false

        override fun sendText(value: String): Boolean {
            textFrames += value
            onText(value)
            return true
        }

        override fun sendBinary(value: ByteArray): Boolean {
            binaryFrames += value.copyOf()
            onBinary(value)
            return true
        }

        override fun receive(timeoutMillis: Long): RealtimeWebSocketEvent? {
            if (events.isNotEmpty()) return events.removeFirst()
            if (timeoutMillis > 0L) Thread.sleep(minOf(timeoutMillis, 1L))
            return null
        }

        override fun close() {
            closed = true
        }
    }

    private class ListSource(
        chunks: List<RealtimeAudioChunk>,
        override val requiresRealtimePacing: Boolean = true,
    ) : RealtimeChunkSource {
        private val remaining = ArrayDeque(chunks)
        var closed = false

        override fun nextChunk(cancellation: AdvancedCancellationToken): RealtimeAudioChunk? {
            cancellation.throwIfCancelled()
            return if (remaining.isEmpty()) null else remaining.removeFirst()
        }

        override val isFinished: Boolean
            get() = remaining.isEmpty()

        override fun close() {
            closed = true
        }
    }

    private class UnfinishedLiveSource : RealtimeChunkSource {
        private var offered = false
        var closed = false

        override val requiresRealtimePacing: Boolean
            get() = false

        override val isFinished: Boolean
            get() = false

        override fun nextChunk(cancellation: AdvancedCancellationToken): RealtimeAudioChunk? {
            cancellation.throwIfCancelled()
            if (offered) return null
            offered = true
            return RealtimeAudioChunk(byteArrayOf(1, 2), 20L)
        }

        override fun close() {
            closed = true
        }
    }

    private fun capture(block: () -> Unit): Throwable = try {
        block()
        error("operation unexpectedly succeeded")
    } catch (error: Throwable) {
        error
    }
}
