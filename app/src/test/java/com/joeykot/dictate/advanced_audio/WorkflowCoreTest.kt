package com.joeykot.dictate.advanced_audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkflowCoreTest {
    @Test
    fun jsonCodecPreservesNumberLiteralsAndRejectsNonJsonNumbers() {
        assertEquals(JsonValue.Number("16000"), JsonValueCodec.parse("16000"))
        assertEquals(JsonValue.Number("-0.2e+3"), JsonValueCodec.parse("-0.2e+3"))
        assertEquals(JsonValue.Text("16000"), JsonValueCodec.parse("\"16000\""))
        assertEquals(JsonValue.Number("1e-999"), JsonValueCodec.parse("1e-999"))

        listOf("01", "1.", "+1", "NaN", "Infinity", "1e999").forEach { literal ->
            assertTrue("$literal must not be accepted as JSON", capture {
                JsonValueCodec.parse(literal)
            } is IllegalArgumentException)
        }
    }

    @Test
    fun jsonCodecRejectsExcessiveNestingWithoutUsingTheJvmCallStack() {
        val withinLimit = "[".repeat(128) + "0" + "]".repeat(128)
        assertTrue(JsonValueCodec.parse(withinLimit) is JsonValue.Array)

        val tooDeep = "[".repeat(129) + "0" + "]".repeat(129)
        assertTrue(capture { JsonValueCodec.parse(tooDeep) } is IllegalArgumentException)
    }

    @Test
    fun jsonNumberExtractionAndTypedIntegerFollowSerdeNumberSemantics() {
        val expected = mapOf(
            "-0" to "-0.0",
            "1e3" to "1000.0",
            "1.00" to "1.0",
            "1e20" to "1e+20",
            "1e-7" to "1e-7",
            "1e-999" to "0.0",
            "18446744073709551616" to "1.8446744073709552e+19",
        )
        expected.forEach { (literal, rendered) ->
            assertEquals(literal, rendered, JsonValue.Number(literal).serdeText())
        }

        val integer = ParameterDefinition(
            id = "sample_rate",
            label = "Sample rate",
            parameterType = ParameterType.INTEGER,
        )
        assertTrue(
            capture { integer.parseValue(CURRENT_WORKFLOW_SCHEMA_VERSION, "-0") } is ParameterValueException.InvalidInteger,
        )
    }

    @Test
    fun codecDoesNotTreatExplicitNullAsAMissingSerdeDefault() {
        val requestRoot = JsonValueCodec.parseObject(resource("workflows/valid-v1-request-multipart.json"))
        val requestRecognition = requestRoot.requiredObjectForTest("recognition")
        val request = requestRecognition.requiredObjectForTest("request")
        listOf("query", "headers", "body", "signer").forEach { field ->
            val variant = requestRoot.withValueForTest(
                "recognition",
                requestRecognition.withValueForTest(
                    "request",
                    request.withValueForTest(field, JsonValue.Null),
                ),
            )
            assertTrue(
                "$field must reject explicit null",
                capture { AdvancedAudioWorkflowCodec.fromJson(variant) } is WorkflowParseException,
            )
        }

        val realtimeRoot = JsonValueCodec.parseObject(resource("workflows/valid-v2-realtime-websocket.json"))
        val realtimeRecognition = realtimeRoot.requiredObjectForTest("recognition")
        val realtime = realtimeRecognition.requiredObjectForTest("realtime")
        listOf("pause_behavior", "finalization_timeout_ms").forEach { field ->
            val variant = realtimeRoot.withValueForTest(
                "recognition",
                realtimeRecognition.withValueForTest(
                    "realtime",
                    realtime.withValueForTest(field, JsonValue.Null),
                ),
            )
            assertTrue(
                "$field must reject explicit null",
                capture { AdvancedAudioWorkflowCodec.fromJson(variant) } is WorkflowParseException,
            )
        }

        val connect = realtime.requiredObjectForTest("connect")
        listOf("query", "headers", "signer").forEach { field ->
            val variant = realtimeRoot.withValueForTest(
                "recognition",
                realtimeRecognition.withValueForTest(
                    "realtime",
                    realtime.withValueForTest(
                        "connect",
                        connect.withValueForTest(field, JsonValue.Null),
                    ),
                ),
            )
            assertTrue(
                "connect.$field must reject explicit null",
                capture { AdvancedAudioWorkflowCodec.fromJson(variant) } is WorkflowParseException,
            )
        }

        val stream = realtime.requiredObjectForTest("audio_stream")
        val pacingVariant = realtimeRoot.withValueForTest(
            "recognition",
            realtimeRecognition.withValueForTest(
                "realtime",
                realtime.withValueForTest("audio_stream", stream.withValueForTest("pacing", JsonValue.Null)),
            ),
        )
        assertTrue(
            "audio_stream.pacing must reject explicit null",
            capture { AdvancedAudioWorkflowCodec.fromJson(pacingVariant) } is WorkflowParseException,
        )
    }

    @Test
    fun windowsWorkflowFixturesDecodeRoundTripAndValidate() {
        val valid = listOf(
            "workflows/valid-v1-request-multipart.json",
            "workflows/valid-v2-typed-request.json",
            "workflows/valid-v2-request-stream-sse.json",
            "workflows/valid-v2-async-poll-provider-upload.json",
            "workflows/valid-v2-realtime-websocket.json",
        )
        valid.forEach { path ->
            val workflow = AdvancedAudioWorkflowCodec.parse(resource(path))
            assertTrue("$path: ${WorkflowValidator.validateWorkflow(workflow)}", WorkflowValidator.validateWorkflow(workflow).isEmpty())
            assertEquals(workflow, AdvancedAudioWorkflowCodec.parse(AdvancedAudioWorkflowCodec.encode(workflow)))
        }
    }

    @Test
    fun windowsInvalidWorkflowFixturesFailBeforeAnyTransport() {
        val expected = mapOf(
            "validation/invalid-v1-typed-parameter.json" to "parameters[0].type",
            "validation/invalid-v2-missing-type.json" to "parameters[0].type",
            "validation/invalid-unknown-schema-version.json" to "Unsupported Workflow Schema Version 99",
            "validation/invalid-unsafe-template.json" to "unknown template namespace 'env'",
            "validation/invalid-future-capture.json" to "references capture 'job_id' before it is created",
            "validation/invalid-dynamic-result-url-suffix.json" to "recognition.result_steps[0].url",
            "validation/invalid-poll-audio-reference.json" to "poll requests must not resend audio or an audio reference",
            "validation/invalid-visible-when-later-source.json" to "parameters[0].visible_when.parameter",
            "validation/invalid-realtime-audio-outside-chunk.json" to "{{audio:filename}} is incompatible with this audio delivery",
        )
        expected.forEach { (path, message) ->
            val errors = WorkflowValidator.validateWorkflow(AdvancedAudioWorkflowCodec.parse(resource(path)))
            assertTrue("$path: $errors", errors.any { it.toString().contains(message) })
        }
    }

    @Test
    fun httpAndWebsocketHeadersRequireTokenNamesAndSafeLiteralValues() {
        val requestWorkflow = AdvancedAudioWorkflowCodec.parse(resource("workflows/valid-v1-request-multipart.json"))
        val requestRecognition = requestWorkflow.recognition as RequestRecognition
        val invalidHttpName = requestWorkflow.copy(
            recognition = requestRecognition.copy(
                request = requestRecognition.request.copy(headers = mapOf("Bad Header" to "value")),
            ),
        )
        assertTrue(
            WorkflowValidator.validateWorkflow(invalidHttpName).any { error ->
                error.path.contains("headers") && error.message.contains("HTTP header token")
            },
        )
        listOf("line\rbreak", "line\nbreak", "line\u0000break").forEach { unsafeValue ->
            val invalidHttpValue = requestWorkflow.copy(
                recognition = requestRecognition.copy(
                    request = requestRecognition.request.copy(headers = mapOf("X-Test" to unsafeValue)),
                ),
            )
            assertTrue(
                WorkflowValidator.validateWorkflow(invalidHttpValue).any { error ->
                    error.path.contains("headers.X-Test") && error.message.contains("line breaks or NUL")
                },
            )
        }

        val realtimeWorkflow = AdvancedAudioWorkflowCodec.parse(resource("workflows/valid-v2-realtime-websocket.json"))
        val realtimeRecognition = realtimeWorkflow.recognition as RealtimeSessionRecognition
        val invalidWebSocketHeader = realtimeWorkflow.copy(
            recognition = realtimeRecognition.copy(
                realtime = realtimeRecognition.realtime.copy(
                    connect = realtimeRecognition.realtime.connect.copy(headers = mapOf("Bad:Header" to "value")),
                ),
            ),
        )
        assertTrue(
            WorkflowValidator.validateWorkflow(invalidWebSocketHeader).any { error ->
                error.path.contains("connect.headers") && error.message.contains("HTTP header token")
            },
        )
    }

    @Test
    fun stageRendererRejectsUnsafeCapturedHeaderValuesBeforeTransport() {
        val stage = HttpStage(
            method = HttpMethod.POST,
            url = "https://workflow.test/request",
            headers = mapOf("X-Task" to "{{capture:task_id}}"),
        )
        val workflow = AdvancedAudioWorkflow(
            schemaVersion = LEGACY_WORKFLOW_SCHEMA_VERSION,
            name = "captured header",
            audio = AudioSpec(AudioDeliveryType.MULTIPART_FILE, "audio/wav"),
            recognition = RequestRecognition(stage, ResponseExtractor.PlainBody),
        )
        val file = File.createTempFile("advanced-header-", ".wav").apply { writeBytes(byteArrayOf(1)) }
        try {
            val error = capture {
                WorkflowStageRenderer(
                    workflow = workflow,
                    values = emptyMap(),
                    secrets = emptyMap(),
                    runtime = RuntimeTemplateValues(),
                ).render(
                    stage = stage,
                    audio = WorkflowAudio.fromFile(file, "audio/wav"),
                    captures = mapOf("task_id" to "safe\r\nX-Injected: true"),
                )
            }
            assertTrue(error is StageRenderingException.InvalidHttpHeader)
        } finally {
            file.delete()
        }
    }

    @Test
    fun v2CompleteJsonLeavesRemainNativeAndStructuredTextUseIsRejected() {
        val fixture = JsonValueCodec.parseObject(resource("typed_rendering/v2-native-json-leaves.json"))
        val parameters = fixture.requiredArrayForTest("parameters").values.mapIndexed { index, value ->
            AdvancedAudioWorkflowCodec.fromJson(
                JsonValue.Object(
                    linkedMapOf(
                        "schema_version" to JsonValue.Number("2"),
                        "name" to JsonValue.Text("typed parameters"),
                        "parameters" to JsonValue.Array(listOf(value)),
                        "secrets" to JsonValue.Array(emptyList()),
                        "audio" to JsonValue.Object(
                            linkedMapOf("delivery" to JsonValue.Object(linkedMapOf("type" to JsonValue.Text("base64")))),
                        ),
                        "recognition" to minimalRecognition(),
                    ),
                ),
            ).parameters.single()
        }
        val values = fixture.requiredObjectForTest("stored_values").values.mapValues { (_, value) ->
            (value as JsonValue.Text).value
        }
        val input = fixture.requiredValueForTest("input_json")
        val rendered = TypedTemplateRenderer.renderJson(
            input,
            TemplateContext(values, emptyMap(), emptyMap(), AudioTemplateValues(), RuntimeTemplateValues()),
            parameters,
            CURRENT_WORKFLOW_SCHEMA_VERSION,
        ) as JsonValue.Object

        assertTrue(rendered["sample_rate"] is JsonValue.Number)
        assertTrue(rendered["temperature"] is JsonValue.Number)
        assertTrue(rendered["timestamps"] is JsonValue.Bool)
        assertTrue(rendered["languages"] is JsonValue.Array)
        assertTrue(rendered["vocabulary"] is JsonValue.Object)
        assertEquals("rate=16000", (rendered["mixed_scalar"] as JsonValue.Text).value)

        val error = capture {
            TypedTemplateRenderer.renderText(
                "languages={{var:languages}}",
                TemplateContext(values, emptyMap(), emptyMap(), AudioTemplateValues(), RuntimeTemplateValues()),
                parameters,
                CURRENT_WORKFLOW_SCHEMA_VERSION,
            )
        }
        assertTrue(error is TypedTemplateRenderException.StructuredParameterRequiresJsonLeaf)
    }

    @Test
    fun typedJsonRenderingDoesNotUseTheCallStackForDeepProgrammaticTrees() {
        val depth = 10_000
        var input: JsonValue = JsonValue.Text("prefix {{var:value}}")
        repeat(depth) { index ->
            input = if (index % 2 == 0) {
                JsonValue.Array(listOf(input))
            } else {
                JsonValue.Object(linkedMapOf("node_$index" to input))
            }
        }

        val rendered = TypedTemplateRenderer.renderJson(
            input,
            TemplateContext(
                values = mapOf("value" to "rendered"),
                secrets = emptyMap(),
                captures = emptyMap(),
                audio = AudioTemplateValues(),
                runtime = RuntimeTemplateValues(),
            ),
            parameters = emptyList(),
            schemaVersion = CURRENT_WORKFLOW_SCHEMA_VERSION,
        )

        var leaf = rendered
        for (index in depth - 1 downTo 0) {
            leaf = if (index % 2 == 0) {
                (leaf as JsonValue.Array).values.single()
            } else {
                (leaf as JsonValue.Object).values.getValue("node_$index")
            }
        }
        assertEquals(JsonValue.Text("prefix rendered"), leaf)
    }

    @Test
    fun v1VariableLeafAlwaysStaysTextAndTemplatesAllowOnlyFixedNamespaces() {
        val parameter = ParameterDefinition("rate", "Rate")
        val rendered = TypedTemplateRenderer.renderJson(
            JsonValue.Text("{{var:rate}}"),
            TemplateContext(mapOf("rate" to "16000"), emptyMap(), emptyMap(), AudioTemplateValues(), RuntimeTemplateValues()),
            listOf(parameter),
            LEGACY_WORKFLOW_SCHEMA_VERSION,
        )
        assertEquals(JsonValue.Text("16000"), rendered)
        assertTrue(capture { Template.parse("{{env:HOME}}") } is TemplateException.UnknownNamespace)
        assertTrue(capture { Template.parse("{{var:model + 1}}") } is TemplateException.InvalidPlaceholder)
        assertTrue(capture { Template.parse("literal }}") } is TemplateException.UnexpectedClose)
    }

    @Test
    fun hiddenRequiredParameterRemainsRequiredAndUnknownValuesFail() {
        val config = JsonValueCodec.parseObject(resource("config/invalid-hidden-required-missing.json"))
        val workflow = AdvancedAudioWorkflowCodec.fromJson(config.requiredObjectForTest("workflow"))
        val errors = WorkflowValidator.validateExecutionInputs(workflow, emptyMap(), emptyMap())
        assertTrue(errors.any { it.path == "ADVANCED_AUDIO_API.values.custom_vocabulary" && it.message == "required value is missing" })

        val unknown = WorkflowValidator.validateExecutionInputs(
            workflow,
            mapOf("custom_vocabulary" to "{}", "not_declared" to "value"),
            emptyMap(),
        )
        assertTrue(unknown.any { it.path == "ADVANCED_AUDIO_API.values.not_declared" })
    }

    @Test
    fun jsonPathSupportsWindowsStandardSelectorsAndExactScalarRule() {
        val root = JsonValueCodec.parse(
            """{"text":"hello","result":{"transcript":"nested"},"results":[{"alternatives":[{"transcript":true}]}],"segments":[{"id":1,"text":"first"},{"id":42,"text":"last"}],"result.text":"dotted","recognition result":{"text-value":"special"},"items":[[0,{"text":7.5}]]}""",
        )
        assertEquals("hello", JsonPath.parse("$.text").extractOne(root))
        assertEquals("nested", JsonPath.parse("$.result.transcript").extractOne(root))
        assertEquals("true", JsonPath.parse("$.results[0].alternatives[0].transcript").extractOne(root))
        assertEquals("last", JsonPath.parse("$.segments[-1].text").extractOne(root))
        assertEquals("last", JsonPath.parse("$.segments[?@.id == 42].text").extractOne(root))
        assertEquals("dotted", JsonPath.parse("$['result.text']").extractOne(root))
        assertEquals("special", JsonPath.parse("$['recognition result']['text-value']").extractOne(root))
        assertEquals("7.5", JsonPath.parse("$.items[0][1].text").extractOne(root))
        assertEquals("root", JsonPath.parse("$").extractOne(JsonValueCodec.parse("\"root\"")))
        assertEquals("root", JsonPath.parse("$[0]").extractOne(JsonValueCodec.parse("[\"root\"]")))
        assertTrue(capture { JsonPath.parse("$.segments[*].text").extractOne(root) } is JsonPathException.MultipleMatches)
        assertTrue(capture { JsonPath.parse("$..text").extractOne(root) } is JsonPathException.MultipleMatches)
        assertTrue(capture { JsonPath.parse("$.segments[").extractOne(root) } is JsonPathException.InvalidPath)
        assertTrue(capture { JsonPath.parse("text") } is JsonPathException.InvalidPath)
    }

    @Test
    fun jsonPathMatchesWindowsFilterSliceUnionAndFunctionSemantics() {
        val root = JsonValueCodec.parse(
            """
            {
              "threshold": 40,
              "readings": [
                {"id": 1, "value": 35, "enabled": false, "text": "1", "tags": [], "some_field": "one", "name": "alpha"},
                {"id": 2, "value": 42, "enabled": true, "empty": "", "tags": [], "some_field": "two", "name": "beta-2"}
              ]
            }
            """.trimIndent(),
        )

        fun ids(path: String): List<String> = JsonPath.parse(path).query(root).map { value ->
            when (value) {
                is JsonValue.Number -> value.serdeText()
                else -> error("test path did not select an id number")
            }
        }

        // Existence tests nodelist presence, not Java/Kotlin truthiness.
        assertEquals(listOf("1", "2"), ids("$.readings[?@.enabled].id"))
        assertEquals(listOf("1", "2"), ids("$.readings[?@.missing != \"x\"].id"))
        assertEquals(emptyList<String>(), ids("$.readings[?@.text < 1].id"))
        assertEquals(listOf("2"), ids("$.readings[?(@.value > $.threshold && !@.disabled)].id"))

        assertEquals(listOf("2", "1"), ids("$.readings[::-1].id"))
        assertEquals(listOf("1", "2"), ids("$.readings[0,-1].id"))

        assertEquals(listOf("1", "2"), ids("$.readings[?length(@.tags) == 0].id"))
        assertEquals(listOf("1", "2"), ids("$.readings[?count(@.tags[*]) == 0].id"))
        assertEquals(listOf("2"), ids("$.readings[?value(@..some_field) == \"two\"].id"))
        assertEquals(listOf("2"), ids("$.readings[?match(@.name, \"beta-[0-9]+\")].id"))
        assertEquals(listOf("2"), ids("$.readings[?search(@.name, \"ta-\")].id"))
    }

    @Test
    fun jsonPathRegexUsesLinearRe2AndKeepsCommonRustCompatiblePatterns() {
        val root = JsonValue.Object(
            linkedMapOf(
                "items" to JsonValue.Array(
                    listOf(
                        JsonValue.Object(linkedMapOf("id" to JsonValue.Text("case"), "value" to JsonValue.Text("DONE"))),
                        JsonValue.Object(linkedMapOf("id" to JsonValue.Text("multi"), "value" to JsonValue.Text("tag-42:alpha"))),
                        JsonValue.Object(linkedMapOf("id" to JsonValue.Text("other"), "value" to JsonValue.Text("unrelated"))),
                    ),
                ),
            ),
        )

        fun ids(path: String): List<String> = JsonPath.parse(path).query(root).map { value ->
            (value as JsonValue.Text).value
        }

        assertEquals(listOf("case"), ids("$.items[?match(@.value, \"(?i)^done$\")].id"))
        assertEquals(listOf("multi"), ids("$.items[?match(@.value, \"^tag-[0-9]+:[a-z]+$\")].id"))
    }

    @Test(timeout = 5_000)
    fun jsonPathRegexHandlesFiniteRepeatedAlternationWithoutJavaBacktracking() {
        val root = JsonValue.Object(
            linkedMapOf(
                "items" to JsonValue.Array(
                    listOf(JsonValue.Object(linkedMapOf("value" to JsonValue.Text("a".repeat(128))))),
                ),
            ),
        )

        val matches = JsonPath.parse(
            "$.items[?match(@.value, \"(a|aa){1,100}b\")].value",
        ).query(root)
        assertTrue(matches.isEmpty())
    }

    @Test
    fun jsonPathRegexReportsRe2IncompatibleExpressionsAtValidationAndRuntime() {
        val staticFailure = capture {
            JsonPath.parse("$.items[?match(@.value, \"(?=done)\")].value")
        }
        assertTrue(staticFailure is JsonPathException.InvalidRegex)

        val root = JsonValue.Object(
            linkedMapOf(
                "pattern" to JsonValue.Text("(?=done)"),
                "items" to JsonValue.Array(
                    listOf(JsonValue.Object(linkedMapOf("value" to JsonValue.Text("done")))),
                ),
            ),
        )
        val runtimeFailure = capture {
            JsonPath.parse("$.items[?match(@.value, $.pattern)].value").query(root)
        }
        assertTrue(runtimeFailure is JsonPathException.InvalidRegex)
    }

    @Test
    fun jsonPathBoundsInvalidNumbersAndLargeResponsesBeforeEvaluation() {
        assertTrue(capture { JsonPath.parse("$.items[?@.value == 1e999]") } is JsonPathException.InvalidPath)
        assertTrue(
            capture {
                JsonPath.extractOne(ByteArray(2 * 1024 * 1024 + 1) { ' '.code.toByte() }, "$.text")
            } is JsonPathException.InvalidJson,
        )
    }

    private fun resource(path: String): String = checkNotNull(
        javaClass.classLoader?.getResourceAsStream("advanced_audio/$path"),
    ).bufferedReader().use { it.readText() }

    private fun minimalRecognition(): JsonValue.Object = JsonValue.Object(
        linkedMapOf(
            "mode" to JsonValue.Text("request"),
            "request" to JsonValue.Object(
                linkedMapOf(
                    "method" to JsonValue.Text("POST"),
                    "url" to JsonValue.Text("https://example.test"),
                ),
            ),
            "final_text" to JsonValue.Object(linkedMapOf("type" to JsonValue.Text("plain_body"))),
        ),
    )

    private fun JsonValue.Object.requiredValueForTest(name: String): JsonValue = values[name] ?: error("missing $name")

    private fun JsonValue.Object.requiredObjectForTest(name: String): JsonValue.Object =
        requiredValueForTest(name) as? JsonValue.Object ?: error("$name is not an object")

    private fun JsonValue.Object.requiredArrayForTest(name: String): JsonValue.Array =
        requiredValueForTest(name) as? JsonValue.Array ?: error("$name is not an array")

    private fun JsonValue.Object.withValueForTest(name: String, value: JsonValue): JsonValue.Object =
        JsonValue.Object(LinkedHashMap(values).apply { put(name, value) })

    private fun capture(block: () -> Unit): Throwable = try {
        block()
        error("operation unexpectedly succeeded")
    } catch (error: Throwable) {
        error
    }
}
