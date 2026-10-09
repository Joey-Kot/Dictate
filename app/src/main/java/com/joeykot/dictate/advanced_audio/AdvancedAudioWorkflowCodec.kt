package com.joeykot.dictate.advanced_audio

import java.math.BigInteger

/**
 * Manual codec for the Windows workflow wire format. It deliberately ignores
 * unknown object fields, matching serde's default behavior, while rejecting
 * missing or wrong-typed known fields.
 */
object AdvancedAudioWorkflowCodec {
    fun parse(document: String): AdvancedAudioWorkflow = fromJson(JsonValueCodec.parseObject(document))

    fun encode(workflow: AdvancedAudioWorkflow): String = JsonValueCodec.stringify(toJson(workflow))

    fun fromJson(root: JsonValue.Object): AdvancedAudioWorkflow = AdvancedAudioWorkflow(
        schemaVersion = root.requiredInt("schema_version"),
        name = root.requiredString("name"),
        parameters = root.optionalArray("parameters")?.values?.mapIndexed { index, value ->
            parseParameter(value.requireObject("parameters[$index]"))
        } ?: emptyList(),
        secrets = root.optionalArray("secrets")?.values?.mapIndexed { index, value ->
            parseSecret(value.requireObject("secrets[$index]"))
        } ?: emptyList(),
        audio = parseAudio(root.requiredObject("audio")),
        recognition = parseRecognition(root.requiredObject("recognition")),
    )

    fun toJson(workflow: AdvancedAudioWorkflow): JsonValue.Object = jsonObject(
        "schema_version" to jsonNumber(workflow.schemaVersion),
        "name" to jsonString(workflow.name),
        "parameters" to JsonValue.Array(workflow.parameters.map(::parameterToJson)),
        "secrets" to JsonValue.Array(workflow.secrets.map(::secretToJson)),
        "audio" to audioToJson(workflow.audio),
        "recognition" to recognitionToJson(workflow.recognition),
    )

    private fun parseParameter(root: JsonValue.Object): ParameterDefinition = ParameterDefinition(
        id = root.requiredString("id"),
        label = root.requiredString("label"),
        required = root.booleanOrDefault("required", false),
        defaultValue = root.optionalString("default"),
        description = root.optionalString("description"),
        parameterType = root.optionalString("type")?.let(::parameterType),
        options = root.optionalArray("options")?.values?.mapIndexed { index, value ->
            val option = value.requireObject("options[$index]")
            ParameterOption(option.requiredString("value"), option.requiredString("label"))
        } ?: emptyList(),
        visibleWhen = root.optionalObject("visible_when")?.let(::parseVisibility),
    )

    private fun parameterToJson(value: ParameterDefinition): JsonValue.Object = jsonObject(
        "id" to jsonString(value.id),
        "label" to jsonString(value.label),
        "required" to JsonValue.Bool(value.required),
        optional("default", value.defaultValue),
        optional("description", value.description),
        value.parameterType?.let { "type" to jsonString(it.wireName) },
        value.options.takeIf { it.isNotEmpty() }?.let { options ->
            "options" to JsonValue.Array(options.map { option ->
                jsonObject("value" to jsonString(option.value), "label" to jsonString(option.label))
            })
        },
        value.visibleWhen?.let { "visible_when" to visibilityToJson(it) },
    )

    private fun parseVisibility(root: JsonValue.Object): VisibilityCondition = VisibilityCondition(
        parameter = root.requiredString("parameter"),
        equals = root.optionalString("equals"),
        oneOf = root.optionalArray("one_of")?.strings("one_of") ?: emptyList(),
    )

    private fun visibilityToJson(value: VisibilityCondition): JsonValue.Object = jsonObject(
        "parameter" to jsonString(value.parameter),
        optional("equals", value.equals),
        value.oneOf.takeIf { it.isNotEmpty() }?.let { "one_of" to stringArray(it) },
    )

    private fun parseSecret(root: JsonValue.Object): SecretDefinition = SecretDefinition(
        id = root.requiredString("id"),
        label = root.requiredString("label"),
        required = root.booleanOrDefault("required", false),
        description = root.optionalString("description"),
    )

    private fun secretToJson(value: SecretDefinition): JsonValue.Object = jsonObject(
        "id" to jsonString(value.id),
        "label" to jsonString(value.label),
        "required" to JsonValue.Bool(value.required),
        optional("description", value.description),
    )

    private fun parseAudio(root: JsonValue.Object): AudioSpec = AudioSpec(
        delivery = audioDelivery(root.requiredObject("delivery").requiredString("type")),
        mime = root.optionalString("mime"),
    )

    private fun audioToJson(value: AudioSpec): JsonValue.Object = jsonObject(
        "delivery" to jsonObject("type" to jsonString(value.delivery.wireName)),
        optional("mime", value.mime),
    )

    private fun parseRecognition(root: JsonValue.Object): Recognition = when (root.requiredString("mode")) {
        "request" -> RequestRecognition(
            request = parseHttpStage(root.requiredObject("request")),
            finalText = parseExtractor(root.requiredObject("final_text")),
        )

        "request_stream" -> RequestStreamRecognition(
            request = parseHttpStage(root.requiredObject("request")),
            stream = parseStream(root.requiredObject("stream")),
        )

        "async_poll" -> AsyncPollRecognition(
            prepare = root.optionalObject("prepare")?.let(::parseHttpStage),
            submit = parseHttpStage(root.requiredObject("submit")),
            poll = root.optionalObject("poll")?.let(::parsePoll),
            resultSteps = root.optionalArray("result_steps")?.values?.mapIndexed { index, value ->
                parseHttpStage(value.requireObject("result_steps[$index]"))
            } ?: emptyList(),
            finalText = parseExtractor(root.requiredObject("final_text")),
        )

        "realtime_session" -> RealtimeSessionRecognition(parseRealtime(root.requiredObject("realtime")))
        else -> invalid("recognition.mode is invalid")
    }

    private fun recognitionToJson(value: Recognition): JsonValue.Object = when (value) {
        is RequestRecognition -> jsonObject(
            "mode" to jsonString(value.modeName),
            "request" to httpStageToJson(value.request),
            "final_text" to extractorToJson(value.finalText),
        )

        is RequestStreamRecognition -> jsonObject(
            "mode" to jsonString(value.modeName),
            "request" to httpStageToJson(value.request),
            "stream" to streamToJson(value.stream),
        )

        is AsyncPollRecognition -> jsonObject(
            "mode" to jsonString(value.modeName),
            value.prepare?.let { "prepare" to httpStageToJson(it) },
            "submit" to httpStageToJson(value.submit),
            value.poll?.let { "poll" to pollToJson(it) },
            "result_steps" to JsonValue.Array(value.resultSteps.map(::httpStageToJson)),
            "final_text" to extractorToJson(value.finalText),
        )

        is RealtimeSessionRecognition -> jsonObject(
            "mode" to jsonString(value.modeName),
            "realtime" to realtimeToJson(value.realtime),
        )
    }

    private fun parseHttpStage(root: JsonValue.Object): HttpStage = HttpStage(
        method = httpMethod(root.requiredString("method")),
        url = root.requiredString("url"),
        query = root.defaultObject("query")?.stringMap("query") ?: emptyMap(),
        headers = root.defaultObject("headers")?.stringMap("headers") ?: emptyMap(),
        body = root.defaultObject("body")?.let(::parseBody) ?: HttpBody.None,
        acceptedStatuses = root.optionalArray("accepted_statuses")?.values?.mapIndexed { index, value ->
            value.requireInt("accepted_statuses[$index]")
        } ?: listOf(200),
        signer = root.defaultObject("signer")?.let(::parseSigner) ?: SignerConfig.None,
        captures = root.optionalArray("captures")?.values?.mapIndexed { index, value ->
            parseCapture(value.requireObject("captures[$index]"))
        } ?: emptyList(),
    )

    private fun httpStageToJson(value: HttpStage): JsonValue.Object = jsonObject(
        "method" to jsonString(value.method.wireName),
        "url" to jsonString(value.url),
        "query" to stringMapToJson(value.query),
        "headers" to stringMapToJson(value.headers),
        "body" to bodyToJson(value.body),
        "accepted_statuses" to JsonValue.Array(value.acceptedStatuses.map(::jsonNumber)),
        "signer" to signerToJson(value.signer),
        "captures" to JsonValue.Array(value.captures.map(::captureToJson)),
    )

    private fun parseBody(root: JsonValue.Object): HttpBody = when (root.requiredString("type")) {
        "none" -> HttpBody.None
        "json" -> HttpBody.Json(root.requiredValue("value"))
        "form_urlencoded" -> HttpBody.FormUrlencoded(root.requiredObject("fields").stringMap("body.fields"))
        "multipart" -> HttpBody.Multipart(root.requiredArray("fields").values.mapIndexed { index, value ->
            parseMultipartField(value.requireObject("body.fields[$index]"))
        })

        "raw_audio" -> HttpBody.RawAudio
        "raw_bytes" -> HttpBody.RawBytes(root.requiredString("value"))
        else -> invalid("body.type is invalid")
    }

    private fun bodyToJson(value: HttpBody): JsonValue.Object = when (value) {
        HttpBody.None -> jsonObject("type" to jsonString("none"))
        is HttpBody.Json -> jsonObject("type" to jsonString("json"), "value" to value.value)
        is HttpBody.FormUrlencoded -> jsonObject(
            "type" to jsonString("form_urlencoded"),
            "fields" to stringMapToJson(value.fields),
        )

        is HttpBody.Multipart -> jsonObject(
            "type" to jsonString("multipart"),
            "fields" to JsonValue.Array(value.fields.map(::multipartFieldToJson)),
        )

        HttpBody.RawAudio -> jsonObject("type" to jsonString("raw_audio"))
        is HttpBody.RawBytes -> jsonObject("type" to jsonString("raw_bytes"), "value" to jsonString(value.value))
    }

    private fun parseMultipartField(root: JsonValue.Object): MultipartField = MultipartField(
        name = root.requiredString("name"),
        value = when (root.requiredString("type")) {
            "text" -> MultipartValue.Text(root.requiredString("value"))
            "audio_file" -> MultipartValue.AudioFile
            "bytes" -> MultipartValue.Bytes(root.requiredString("value"))
            else -> invalid("multipart field type is invalid")
        },
    )

    private fun multipartFieldToJson(value: MultipartField): JsonValue.Object = when (val item = value.value) {
        is MultipartValue.Text -> jsonObject(
            "name" to jsonString(value.name),
            "type" to jsonString("text"),
            "value" to jsonString(item.value),
        )

        MultipartValue.AudioFile -> jsonObject(
            "name" to jsonString(value.name),
            "type" to jsonString("audio_file"),
        )

        is MultipartValue.Bytes -> jsonObject(
            "name" to jsonString(value.name),
            "type" to jsonString("bytes"),
            "value" to jsonString(item.value),
        )
    }

    private fun parseExtractor(root: JsonValue.Object): ResponseExtractor = when (root.requiredString("type")) {
        "json_path" -> ResponseExtractor.JsonPath(root.requiredString("path"))
        "header" -> ResponseExtractor.Header(root.requiredString("name"))
        "plain_body" -> ResponseExtractor.PlainBody
        "status" -> ResponseExtractor.Status
        else -> invalid("extractor.type is invalid")
    }

    private fun extractorToJson(value: ResponseExtractor): JsonValue.Object = when (value) {
        is ResponseExtractor.JsonPath -> jsonObject("type" to jsonString("json_path"), "path" to jsonString(value.path))
        is ResponseExtractor.Header -> jsonObject("type" to jsonString("header"), "name" to jsonString(value.name))
        ResponseExtractor.PlainBody -> jsonObject("type" to jsonString("plain_body"))
        ResponseExtractor.Status -> jsonObject("type" to jsonString("status"))
    }

    private fun parseCapture(root: JsonValue.Object): Capture = Capture(
        id = root.requiredString("id"),
        from = parseExtractor(root.requiredObject("from")),
        sensitive = root.booleanOrDefault("sensitive", false),
    )

    private fun captureToJson(value: Capture): JsonValue.Object = jsonObject(
        "id" to jsonString(value.id),
        "from" to extractorToJson(value.from),
        "sensitive" to JsonValue.Bool(value.sensitive),
    )

    private fun parseStream(root: JsonValue.Object): StreamResponse = StreamResponse(
        format = streamFormat(root.requiredString("format")),
        rules = root.optionalArray("rules")?.values?.mapIndexed { index, value ->
            parseStreamRule(value.requireObject("rules[$index]"))
        } ?: emptyList(),
    )

    private fun streamToJson(value: StreamResponse): JsonValue.Object = jsonObject(
        "format" to jsonString(value.format.wireName),
        "rules" to JsonValue.Array(value.rules.map(::streamRuleToJson)),
    )

    private fun parseStreamRule(root: JsonValue.Object): StreamRule = StreamRule(
        event = root.optionalString("event"),
        path = root.optionalString("path"),
        action = streamAction(root.requiredString("action")),
        equals = root.optionalString("equals"),
    )

    private fun streamRuleToJson(value: StreamRule): JsonValue.Object = jsonObject(
        optional("event", value.event),
        optional("path", value.path),
        "action" to jsonString(value.action.wireName),
        optional("equals", value.equals),
    )

    private fun parsePoll(root: JsonValue.Object): PollStage = PollStage(
        request = parseHttpStage(root.requiredObject("request")),
        intervalMs = root.requiredLong("interval_ms"),
        timeoutMs = root.requiredLong("timeout_ms"),
        pending = root.optionalArray("pending")?.values?.mapIndexed { index, value ->
            parsePollCondition(value.requireObject("pending[$index]"))
        } ?: emptyList(),
        success = root.optionalArray("success")?.values?.mapIndexed { index, value ->
            parsePollCondition(value.requireObject("success[$index]"))
        } ?: emptyList(),
        failure = root.optionalArray("failure")?.values?.mapIndexed { index, value ->
            parsePollCondition(value.requireObject("failure[$index]"))
        } ?: emptyList(),
    )

    private fun pollToJson(value: PollStage): JsonValue.Object = jsonObject(
        "request" to httpStageToJson(value.request),
        "interval_ms" to jsonNumber(value.intervalMs),
        "timeout_ms" to jsonNumber(value.timeoutMs),
        "pending" to JsonValue.Array(value.pending.map(::pollConditionToJson)),
        "success" to JsonValue.Array(value.success.map(::pollConditionToJson)),
        "failure" to JsonValue.Array(value.failure.map(::pollConditionToJson)),
    )

    private fun parsePollCondition(root: JsonValue.Object): PollCondition = PollCondition(
        from = parseExtractor(root.requiredObject("from")),
        operator = pollOperator(root.requiredString("operator")),
        value = root.optionalString("value"),
        values = root.optionalArray("values")?.strings("values") ?: emptyList(),
    )

    private fun pollConditionToJson(value: PollCondition): JsonValue.Object = jsonObject(
        "from" to extractorToJson(value.from),
        "operator" to jsonString(value.operator.wireName),
        optional("value", value.value),
        value.values.takeIf { it.isNotEmpty() }?.let { "values" to stringArray(it) },
    )

    private fun parseSigner(root: JsonValue.Object): SignerConfig = when (root.requiredString("type")) {
        "none" -> SignerConfig.None
        "aws_sigv4" -> SignerConfig.AwsSigv4(
            region = root.requiredString("region"),
            service = root.requiredString("service"),
            accessKeySecret = root.requiredString("access_key_secret"),
            secretKeySecret = root.requiredString("secret_key_secret"),
            sessionTokenSecret = root.optionalString("session_token_secret"),
        )

        "tencent_tc3" -> SignerConfig.TencentTc3(
            service = root.requiredString("service"),
            secretIdSecret = root.requiredString("secret_id_secret"),
            secretKeySecret = root.requiredString("secret_key_secret"),
        )

        else -> invalid("signer.type is invalid")
    }

    private fun signerToJson(value: SignerConfig): JsonValue.Object = when (value) {
        SignerConfig.None -> jsonObject("type" to jsonString("none"))
        is SignerConfig.AwsSigv4 -> jsonObject(
            "type" to jsonString("aws_sigv4"),
            "region" to jsonString(value.region),
            "service" to jsonString(value.service),
            "access_key_secret" to jsonString(value.accessKeySecret),
            "secret_key_secret" to jsonString(value.secretKeySecret),
            optional("session_token_secret", value.sessionTokenSecret),
        )

        is SignerConfig.TencentTc3 -> jsonObject(
            "type" to jsonString("tencent_tc3"),
            "service" to jsonString(value.service),
            "secret_id_secret" to jsonString(value.secretIdSecret),
            "secret_key_secret" to jsonString(value.secretKeySecret),
        )
    }

    private fun parseRealtime(root: JsonValue.Object): RealtimeWorkflow = RealtimeWorkflow(
        transport = realtimeTransport(root.requiredString("transport")),
        connect = parseRealtimeConnect(root.requiredObject("connect")),
        initialMessages = root.optionalArray("initial_messages")?.values?.mapIndexed { index, value ->
            parseRealtimeMessage(value.requireObject("initial_messages[$index]"))
        } ?: emptyList(),
        audioStream = parseRealtimeAudioStream(root.requiredObject("audio_stream")),
        audioMessage = parseRealtimeAudioMessage(root.requiredObject("audio_message")),
        receiveRules = root.optionalArray("receive_rules")?.values?.mapIndexed { index, value ->
            parseStreamRule(value.requireObject("receive_rules[$index]"))
        } ?: emptyList(),
        finishMessages = root.optionalArray("finish_messages")?.values?.mapIndexed { index, value ->
            parseRealtimeMessage(value.requireObject("finish_messages[$index]"))
        } ?: emptyList(),
        completion = parseRealtimeCompletion(root.requiredObject("completion")),
        pauseBehavior = root.defaultString("pause_behavior")?.let(::pauseBehavior) ?: PauseBehavior.RESTART_SESSION,
        finalizationTimeoutMs = root.defaultLong("finalization_timeout_ms") ?: 15_000L,
    )

    private fun realtimeToJson(value: RealtimeWorkflow): JsonValue.Object = jsonObject(
        "transport" to jsonString(value.transport.wireName),
        "connect" to realtimeConnectToJson(value.connect),
        "initial_messages" to JsonValue.Array(value.initialMessages.map(::realtimeMessageToJson)),
        "audio_stream" to realtimeAudioStreamToJson(value.audioStream),
        "audio_message" to realtimeAudioMessageToJson(value.audioMessage),
        "receive_rules" to JsonValue.Array(value.receiveRules.map(::streamRuleToJson)),
        "finish_messages" to JsonValue.Array(value.finishMessages.map(::realtimeMessageToJson)),
        "completion" to realtimeCompletionToJson(value.completion),
        "pause_behavior" to jsonString(value.pauseBehavior.wireName),
        "finalization_timeout_ms" to jsonNumber(value.finalizationTimeoutMs),
    )

    private fun parseRealtimeConnect(root: JsonValue.Object): RealtimeConnect = RealtimeConnect(
        url = root.requiredString("url"),
        query = root.defaultObject("query")?.stringMap("connect.query") ?: emptyMap(),
        headers = root.defaultObject("headers")?.stringMap("connect.headers") ?: emptyMap(),
        signer = root.defaultObject("signer")?.let(::parseSigner) ?: SignerConfig.None,
        subprotocol = root.optionalString("subprotocol"),
    )

    private fun realtimeConnectToJson(value: RealtimeConnect): JsonValue.Object = jsonObject(
        "url" to jsonString(value.url),
        "query" to stringMapToJson(value.query),
        "headers" to stringMapToJson(value.headers),
        "signer" to signerToJson(value.signer),
        optional("subprotocol", value.subprotocol),
    )

    private fun parseRealtimeMessage(root: JsonValue.Object): RealtimeMessage = when (root.requiredString("type")) {
        "json" -> RealtimeMessage.Json(root.requiredValue("value"))
        "text" -> RealtimeMessage.Text(root.requiredString("value"))
        "binary" -> RealtimeMessage.Binary(root.requiredString("value"))
        else -> invalid("realtime message type is invalid")
    }

    private fun realtimeMessageToJson(value: RealtimeMessage): JsonValue.Object = when (value) {
        is RealtimeMessage.Json -> jsonObject("type" to jsonString("json"), "value" to value.value)
        is RealtimeMessage.Text -> jsonObject("type" to jsonString("text"), "value" to jsonString(value.value))
        is RealtimeMessage.Binary -> jsonObject("type" to jsonString("binary"), "value" to jsonString(value.value))
    }

    private fun parseRealtimeAudioStream(root: JsonValue.Object): RealtimeAudioStream = RealtimeAudioStream(
        codec = root.requiredString("codec"),
        sampleRate = root.requiredInt("sample_rate"),
        channels = root.requiredInt("channels"),
        chunkDurationMs = root.requiredInt("chunk_duration_ms"),
        pacing = root.defaultString("pacing")?.let(::realtimePacing) ?: RealtimePacing.REALTIME,
    )

    private fun realtimeAudioStreamToJson(value: RealtimeAudioStream): JsonValue.Object = jsonObject(
        "codec" to jsonString(value.codec),
        "sample_rate" to jsonNumber(value.sampleRate),
        "channels" to jsonNumber(value.channels),
        "chunk_duration_ms" to jsonNumber(value.chunkDurationMs),
        "pacing" to jsonString(value.pacing.wireName),
    )

    private fun parseRealtimeAudioMessage(root: JsonValue.Object): RealtimeAudioMessage = when (root.requiredString("type")) {
        "binary" -> RealtimeAudioMessage.Binary
        "text" -> RealtimeAudioMessage.Text(root.requiredString("value"))
        "json" -> RealtimeAudioMessage.Json(root.requiredValue("value"))
        else -> invalid("realtime audio message type is invalid")
    }

    private fun realtimeAudioMessageToJson(value: RealtimeAudioMessage): JsonValue.Object = when (value) {
        RealtimeAudioMessage.Binary -> jsonObject("type" to jsonString("binary"))
        is RealtimeAudioMessage.Text -> jsonObject("type" to jsonString("text"), "value" to jsonString(value.value))
        is RealtimeAudioMessage.Json -> jsonObject("type" to jsonString("json"), "value" to value.value)
    }

    private fun parseRealtimeCompletion(root: JsonValue.Object): RealtimeCompletion = RealtimeCompletion(
        event = root.optionalString("event"),
        path = root.optionalString("path"),
        equals = root.optionalString("equals"),
    )

    private fun realtimeCompletionToJson(value: RealtimeCompletion): JsonValue.Object = jsonObject(
        optional("event", value.event),
        optional("path", value.path),
        optional("equals", value.equals),
    )

    private fun parameterType(value: String): ParameterType = ParameterType.fromWire(value)
        ?: invalid("parameter.type is invalid")

    private fun audioDelivery(value: String): AudioDeliveryType = AudioDeliveryType.fromWire(value)
        ?: invalid("audio.delivery.type is invalid")

    private fun httpMethod(value: String): HttpMethod = HttpMethod.fromWire(value)
        ?: invalid("HTTP method is invalid")

    private fun streamFormat(value: String): StreamFormat = StreamFormat.fromWire(value)
        ?: invalid("stream format is invalid")

    private fun streamAction(value: String): StreamAction = StreamAction.fromWire(value)
        ?: invalid("stream action is invalid")

    private fun pollOperator(value: String): PollOperator = PollOperator.fromWire(value)
        ?: invalid("poll operator is invalid")

    private fun realtimeTransport(value: String): RealtimeTransport = RealtimeTransport.fromWire(value)
        ?: invalid("realtime transport is invalid")

    private fun realtimePacing(value: String): RealtimePacing = RealtimePacing.fromWire(value)
        ?: invalid("realtime pacing is invalid")

    private fun pauseBehavior(value: String): PauseBehavior = PauseBehavior.fromWire(value)
        ?: invalid("pause behavior is invalid")

    private fun JsonValue.requireObject(path: String): JsonValue.Object = this as? JsonValue.Object
        ?: invalid("$path must be an object")

    private fun JsonValue.requireInt(path: String): Int {
        val number = this as? JsonValue.Number ?: invalid("$path must be an integer")
        if (!number.isIntegerLiteral()) invalid("$path must be an integer")
        val value = try {
            BigInteger(number.literal)
        } catch (_: NumberFormatException) {
            invalid("$path must be an integer")
        }
        if (value < INT_MIN || value > INT_MAX) invalid("$path is out of range")
        return value.toInt()
    }

    private fun JsonValue.requireLong(path: String): Long {
        val number = this as? JsonValue.Number ?: invalid("$path must be an integer")
        if (!number.isIntegerLiteral()) invalid("$path must be an integer")
        val value = try {
            BigInteger(number.literal)
        } catch (_: NumberFormatException) {
            invalid("$path must be an integer")
        }
        if (value < LONG_MIN || value > LONG_MAX) invalid("$path is out of range")
        return value.toLong()
    }

    private fun JsonValue.Object.requiredValue(name: String): JsonValue = values[name]
        ?: invalid("$name is required")

    private fun JsonValue.Object.requiredObject(name: String): JsonValue.Object = requiredValue(name).requireObject(name)

    private fun JsonValue.Object.requiredArray(name: String): JsonValue.Array = requiredValue(name) as? JsonValue.Array
        ?: invalid("$name must be an array")

    private fun JsonValue.Object.requiredString(name: String): String =
        (requiredValue(name) as? JsonValue.Text)?.value ?: invalid("$name must be a string")

    private fun JsonValue.Object.requiredInt(name: String): Int = requiredValue(name).requireInt(name)

    private fun JsonValue.Object.requiredLong(name: String): Long = requiredValue(name).requireLong(name)

    private fun JsonValue.Object.optionalString(name: String): String? = when (val value = values[name]) {
        null,
        JsonValue.Null,
        -> null

        is JsonValue.Text -> value.value
        else -> invalid("$name must be a string")
    }

    private fun JsonValue.Object.optionalObject(name: String): JsonValue.Object? = when (val value = values[name]) {
        null,
        JsonValue.Null,
        -> null

        is JsonValue.Object -> value
        else -> invalid("$name must be an object")
    }

    /**
     * `#[serde(default)]` fills in a missing non-Option field only.  Unlike an
     * `Option<T>`, an explicit JSON null is a type error and must not be
     * silently normalized into a default Android value.
     */
    private fun JsonValue.Object.defaultObject(name: String): JsonValue.Object? = when (val value = values[name]) {
        null -> null
        is JsonValue.Object -> value
        else -> invalid("$name must be an object")
    }

    private fun JsonValue.Object.optionalArray(name: String): JsonValue.Array? = when (val value = values[name]) {
        null -> null
        is JsonValue.Array -> value
        else -> invalid("$name must be an array")
    }

    private fun JsonValue.Object.defaultString(name: String): String? = when (val value = values[name]) {
        null -> null
        is JsonValue.Text -> value.value
        else -> invalid("$name must be a string")
    }

    private fun JsonValue.Object.defaultLong(name: String): Long? = when (val value = values[name]) {
        null -> null
        else -> value.requireLong(name)
    }

    private fun JsonValue.Object.booleanOrDefault(name: String, defaultValue: Boolean): Boolean = when (val value = values[name]) {
        null -> defaultValue
        is JsonValue.Bool -> value.value
        else -> invalid("$name must be a boolean")
    }

    private fun JsonValue.Object.stringMap(path: String): Map<String, String> = buildMap<String, String> {
        this@stringMap.values.forEach { (name, value) ->
            val text = (value as? JsonValue.Text)?.value ?: invalid("$path.$name must be a string")
            put(name, text)
        }
    }

    private fun JsonValue.Array.strings(path: String): List<String> = values.mapIndexed { index, value ->
        (value as? JsonValue.Text)?.value ?: invalid("$path[$index] must be a string")
    }

    private fun jsonObject(vararg fields: Pair<String, JsonValue>?): JsonValue.Object = JsonValue.Object(
        LinkedHashMap<String, JsonValue>().apply {
            fields.filterNotNull().forEach { (name, value) -> put(name, value) }
        },
    )

    private fun optional(name: String, value: String?): Pair<String, JsonValue>? =
        value?.let { name to jsonString(it) }

    private fun jsonString(value: String): JsonValue.Text = JsonValue.Text(value)

    private fun jsonNumber(value: Int): JsonValue.Number = JsonValue.Number(value.toString())

    private fun jsonNumber(value: Long): JsonValue.Number = JsonValue.Number(value.toString())

    private fun stringArray(values: List<String>): JsonValue.Array = JsonValue.Array(values.map(::jsonString))

    private fun stringMapToJson(values: Map<String, String>): JsonValue.Object = JsonValue.Object(
        LinkedHashMap<String, JsonValue>().apply {
            values.forEach { (name, value) -> put(name, jsonString(value)) }
        },
    )

    private fun invalid(message: String): Nothing = throw WorkflowParseException(message)

    private val INT_MIN = BigInteger.valueOf(Int.MIN_VALUE.toLong())
    private val INT_MAX = BigInteger.valueOf(Int.MAX_VALUE.toLong())
    private val LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE)
    private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
}

class WorkflowParseException(message: String) : IllegalArgumentException(message)
