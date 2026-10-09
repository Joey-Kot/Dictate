package com.joeykot.dictate.advanced_audio

import java.net.URI

data class WorkflowValidationError(
    val path: String,
    val message: String,
) {
    override fun toString(): String = "$path: $message"
}

class WorkflowValidationException(
    val errors: List<WorkflowValidationError>,
) : IllegalArgumentException(errors.joinToString("; "))

/**
 * Network-independent validation for the portable workflow document. This is
 * intentionally the one protocol gate used by UI and later execution code.
 */
object WorkflowValidator {
    const val MAX_WORKFLOW_JSON_BYTES = 256 * 1024
    const val MAX_TEMPLATE_BYTES = 32 * 1024

    fun validateWorkflow(workflow: AdvancedAudioWorkflow): List<WorkflowValidationError> = Collector().also {
        validateWorkflowInto(workflow, it)
    }.errors

    fun requireValidWorkflow(workflow: AdvancedAudioWorkflow) {
        val errors = validateWorkflow(workflow)
        if (errors.isNotEmpty()) throw WorkflowValidationException(errors)
    }

    /**
     * Validates values and secrets in addition to the portable workflow. The
     * caller decides enabled/disabled policy; disabled drafts should not call
     * this method so they remain inert just as on Windows.
     */
    fun validateExecutionInputs(
        workflow: AdvancedAudioWorkflow,
        values: Map<String, String>,
        secrets: Map<String, String>,
    ): List<WorkflowValidationError> = Collector().also {
        validateWorkflowInto(workflow, it)
        validateConfiguredValues(workflow, values, secrets, it)
    }.errors

    fun requireValidExecutionInputs(
        workflow: AdvancedAudioWorkflow,
        values: Map<String, String>,
        secrets: Map<String, String>,
    ) {
        val errors = validateExecutionInputs(workflow, values, secrets)
        if (errors.isNotEmpty()) throw WorkflowValidationException(errors)
    }

    private fun validateWorkflowInto(workflow: AdvancedAudioWorkflow, errors: Collector) {
        val serializedBytes = JsonValueCodec.stringify(AdvancedAudioWorkflowCodec.toJson(workflow)).toByteArray(Charsets.UTF_8).size
        if (serializedBytes > MAX_WORKFLOW_JSON_BYTES) {
            errors.add("workflow", "serialized workflow exceeds $MAX_WORKFLOW_JSON_BYTES bytes")
        }
        if (!isSupportedWorkflowSchemaVersion(workflow.schemaVersion)) {
            errors.add(
                "schema_version",
                "Unsupported Workflow Schema Version ${workflow.schemaVersion}; this build supports $LEGACY_WORKFLOW_SCHEMA_VERSION and $CURRENT_WORKFLOW_SCHEMA_VERSION",
            )
        }
        validateText(workflow.name, "name", required = true, maximum = 256, errors)

        val parameterIds = linkedSetOf<String>()
        if (workflow.parameters.size > MAX_DECLARATIONS) {
            errors.add("parameters", "must contain at most $MAX_DECLARATIONS entries")
        }
        workflow.parameters.forEachIndexed { index, definition ->
            val path = "parameters[$index]"
            validateDefinition(definition.id, definition.label, definition.description, path, parameterIds, errors)
            validateParameterDefinition(definition, workflow.schemaVersion, path, errors)
        }
        if (workflow.schemaVersion == CURRENT_WORKFLOW_SCHEMA_VERSION) {
            workflow.parameters.forEachIndexed { index, definition ->
                definition.visibleWhen?.let { condition ->
                    validateVisibilityCondition(condition, workflow.parameters.take(index), index, errors)
                }
            }
        }

        val secretIds = linkedSetOf<String>()
        if (workflow.secrets.size > MAX_DECLARATIONS) {
            errors.add("secrets", "must contain at most $MAX_DECLARATIONS entries")
        }
        workflow.secrets.forEachIndexed { index, definition ->
            validateDefinition(definition.id, definition.label, definition.description, "secrets[$index]", secretIds, errors)
        }
        parameterIds.intersect(secretIds).forEach { duplicate ->
            errors.add("parameters/secrets", "'$duplicate' is declared as both a value and a secret")
        }

        val baseScope = TemplateScope(
            parameters = parameterIds,
            secrets = secretIds,
            captures = emptySet(),
            audioDelivery = workflow.audio.delivery,
            realtime = false,
            audioAccess = AudioTemplateAccess.DELIVERY,
        )
        when (val recognition = workflow.recognition) {
            is RequestRecognition -> {
                validateNonRealtimeAudio(workflow.audio, "audio", errors)
                val captures = linkedSetOf<String>()
                validateHttpStage(recognition.request, "recognition.request", baseScope, captures, errors)
                validateExtractor(recognition.finalText, "recognition.final_text", errors)
                validateAudioDeliveryUsage(workflow.audio, listOf(recognition.request), "audio.delivery", errors)
                validateProviderUpload(null, null, workflow.audio, errors)
            }

            is RequestStreamRecognition -> {
                validateNonRealtimeAudio(workflow.audio, "audio", errors)
                val captures = linkedSetOf<String>()
                validateHttpStage(recognition.request, "recognition.request", baseScope, captures, errors)
                recognition.request.captures.forEachIndexed { index, capture ->
                    if (capture.from !is ResponseExtractor.Header && capture.from != ResponseExtractor.Status) {
                        errors.add(
                            "recognition.request.captures[$index].from",
                            "request_stream captures may only use header or status extractors",
                        )
                    }
                }
                validateStream(recognition.stream, "recognition.stream", baseScope, requireComplete = true, errors)
                validateAudioDeliveryUsage(workflow.audio, listOf(recognition.request), "audio.delivery", errors)
                validateProviderUpload(null, null, workflow.audio, errors)
            }

            is AsyncPollRecognition -> {
                validateNonRealtimeAudio(workflow.audio, "audio", errors)
                if (recognition.resultSteps.size > 2) {
                    errors.add("recognition.result_steps", "must contain at most 2 stages")
                }
                val captures = linkedSetOf<String>()
                recognition.prepare?.let { stage ->
                    validateHttpStage(stage, "recognition.prepare", baseScope, captures, errors)
                }
                validateHttpStage(recognition.submit, "recognition.submit", baseScope, captures, errors)
                recognition.poll?.let { stage ->
                    validatePollStage(stage, "recognition.poll", baseScope, captures, errors)
                }
                recognition.resultSteps.forEachIndexed { index, stage ->
                    validateHttpStage(stage, "recognition.result_steps[$index]", baseScope, captures, errors)
                }
                validateExtractor(recognition.finalText, "recognition.final_text", errors)
                validateAudioDeliveryUsage(
                    workflow.audio,
                    listOfNotNull(recognition.prepare, recognition.submit),
                    "audio.delivery",
                    errors,
                )
                validateProviderUpload(recognition.prepare, recognition.submit, workflow.audio, errors)
            }

            is RealtimeSessionRecognition -> {
                if (workflow.audio.delivery != AudioDeliveryType.REALTIME_CHUNKS) {
                    errors.add("audio.delivery", "realtime_session requires audio delivery type realtime_chunks")
                }
                validateRealtime(recognition.realtime, "recognition.realtime", baseScope, errors)
            }
        }
    }

    private fun validateDefinition(
        id: String,
        label: String,
        description: String?,
        path: String,
        seen: MutableSet<String>,
        errors: Collector,
    ) {
        if (!isWorkflowIdentifier(id)) {
            errors.add("$path.id", "must be an ASCII identifier")
        } else if (!seen.add(id)) {
            errors.add("$path.id", "duplicate declaration '$id'")
        }
        validateText(label, "$path.label", required = true, maximum = 256, errors)
        description?.let { validateText(it, "$path.description", required = false, maximum = 2_048, errors) }
    }

    private fun validateParameterDefinition(
        definition: ParameterDefinition,
        schemaVersion: Int,
        path: String,
        errors: Collector,
    ) {
        when (schemaVersion) {
            LEGACY_WORKFLOW_SCHEMA_VERSION -> {
                if (definition.parameterType != null) errors.add("$path.type", "is only supported by workflow schema version 2")
                if (definition.options.isNotEmpty()) errors.add("$path.options", "is only supported by workflow schema version 2")
                if (definition.visibleWhen != null) errors.add("$path.visible_when", "is only supported by workflow schema version 2")
                definition.defaultValue?.let { validateLiteralDefault(it, "$path.default", errors) }
            }

            CURRENT_WORKFLOW_SCHEMA_VERSION -> {
                val parameterType = definition.parameterType
                if (parameterType == null) {
                    errors.add("$path.type", "is required for workflow schema version 2")
                    return
                }
                validateParameterOptions(definition, parameterType, path, errors)
                definition.defaultValue?.let { value ->
                    validateLiteralParameterValue(definition, schemaVersion, value, "$path.default", errors)
                }
            }
        }
    }

    private fun validateParameterOptions(
        definition: ParameterDefinition,
        type: ParameterType,
        path: String,
        errors: Collector,
    ) {
        val selection = type == ParameterType.SELECT || type == ParameterType.MULTI_SELECT
        if (!selection) {
            if (definition.options.isNotEmpty()) errors.add("$path.options", "is allowed only for select or multi_select parameters")
            return
        }
        if (definition.options.isEmpty()) {
            errors.add("$path.options", "must contain at least one option for a selection parameter")
            return
        }
        val values = mutableSetOf<String>()
        definition.options.forEachIndexed { index, option ->
            val optionPath = "$path.options[$index]"
            validateText(option.value, "$optionPath.value", required = true, maximum = 256, errors)
            validateText(option.label, "$optionPath.label", required = true, maximum = 256, errors)
            if (!values.add(option.value)) errors.add("$optionPath.value", "duplicate option value '${option.value}'")
        }
    }

    private fun validateVisibilityCondition(
        condition: VisibilityCondition,
        preceding: List<ParameterDefinition>,
        index: Int,
        errors: Collector,
    ) {
        val path = "parameters[$index].visible_when"
        if (!isWorkflowIdentifier(condition.parameter)) errors.add("$path.parameter", "must be an ASCII identifier")
        val hasEquals = condition.equals != null
        val hasOneOf = condition.oneOf.isNotEmpty()
        if (hasEquals == hasOneOf) {
            errors.add(path, "must contain exactly one of equals or a nonempty one_of array")
            return
        }
        val source = preceding.lastOrNull { it.id == condition.parameter }
        if (source == null) {
            errors.add("$path.parameter", "must reference an earlier unconditional boolean or select parameter")
            return
        }
        if (source.visibleWhen != null) errors.add("$path.parameter", "must reference an unconditional parameter")
        if (source.defaultValue == null) errors.add("$path.parameter", "must reference a parameter with a default value")
        val type = source.parameterType
        if (type == null || (type != ParameterType.BOOLEAN && type != ParameterType.SELECT)) {
            errors.add("$path.parameter", "must reference a boolean or select parameter")
            return
        }
        val comparisonValues = if (hasEquals) listOf(condition.equals.orEmpty()) else condition.oneOf
        val seen = mutableSetOf<String>()
        comparisonValues.forEachIndexed { valueIndex, value ->
            val valuePath = if (hasEquals) "$path.equals" else "$path.one_of[$valueIndex]"
            if (!seen.add(value)) {
                errors.add(valuePath, "must not contain duplicate comparison values")
            } else {
                val valid = when (type) {
                    ParameterType.BOOLEAN -> value == "true" || value == "false"
                    ParameterType.SELECT -> source.options.any { it.value == value }
                    else -> false
                }
                if (!valid) errors.add(valuePath, "must be a declared value of the referenced parameter")
            }
        }
    }

    private fun validateLiteralDefault(value: String, path: String, errors: Collector) {
        if (utf8Bytes(value) > MAX_TEMPLATE_BYTES) {
            errors.add(path, "template exceeds $MAX_TEMPLATE_BYTES bytes")
            return
        }
        try {
            val template = Template.parse(value)
            if (template.placeholders.isNotEmpty()) {
                errors.add(path, "must be a literal; parameter defaults may not contain template placeholders")
            }
        } catch (error: TemplateException) {
            errors.add(path, error.message.orEmpty())
        }
    }

    private fun validateLiteralParameterValue(
        definition: ParameterDefinition,
        schemaVersion: Int,
        value: String,
        path: String,
        errors: Collector,
    ) {
        if (definition.parameterType == ParameterType.TEXT || definition.parameterType == ParameterType.SELECT) {
            validateLiteralDefault(value, path, errors)
        }
        try {
            definition.parseValue(schemaVersion, value)
        } catch (error: ParameterValueException) {
            errors.add(path, error.message.orEmpty())
        }
    }

    private fun validateConfiguredValues(
        workflow: AdvancedAudioWorkflow,
        values: Map<String, String>,
        secrets: Map<String, String>,
        errors: Collector,
    ) {
        workflow.parameters.forEach { parameter ->
            val path = "ADVANCED_AUDIO_API.values.${parameter.id}"
            val effective = values[parameter.id] ?: parameter.defaultValue
            if (effective != null) {
                if (workflow.schemaVersion == CURRENT_WORKFLOW_SCHEMA_VERSION) {
                    try {
                        parameter.parseValue(workflow.schemaVersion, effective)
                    } catch (error: ParameterValueException) {
                        errors.add(path, error.message.orEmpty())
                    }
                    if (parameter.required && isEmptyRequiredValue(parameter, effective)) {
                        errors.add(path, "required value must not be empty")
                    }
                }
            } else if (parameter.required) {
                errors.add(path, "required value is missing")
            }
        }
        workflow.secrets.forEach { secret ->
            if (secret.required && secrets[secret.id].isNullOrEmpty()) {
                errors.add("ADVANCED_AUDIO_API.secrets.${secret.id}", "required secret is missing")
            }
        }
        val declaredValues = workflow.parameters.mapTo(mutableSetOf()) { it.id }
        values.keys.filterNot(declaredValues::contains).forEach { unknown ->
            errors.add("ADVANCED_AUDIO_API.values.$unknown", "is not declared by the workflow")
        }
        val declaredSecrets = workflow.secrets.mapTo(mutableSetOf()) { it.id }
        secrets.keys.filterNot(declaredSecrets::contains).forEach { unknown ->
            errors.add("ADVANCED_AUDIO_API.secrets.$unknown", "is not declared by the workflow")
        }
    }

    private fun isEmptyRequiredValue(parameter: ParameterDefinition, value: String): Boolean = when (parameter.parameterType) {
        ParameterType.TEXT -> value.isEmpty()
        ParameterType.MULTI_SELECT -> (runCatching {
            parameter.parseValue(CURRENT_WORKFLOW_SCHEMA_VERSION, value)
        }.getOrNull() as? JsonValue.Array)?.values?.isEmpty() == true

        ParameterType.INTEGER,
        ParameterType.NUMBER,
        ParameterType.BOOLEAN,
        ParameterType.SELECT,
        ParameterType.JSON_OBJECT,
        ParameterType.JSON_ARRAY,
        null,
        -> false
    }

    private fun validateNonRealtimeAudio(audio: AudioSpec, path: String, errors: Collector) {
        if (audio.delivery == AudioDeliveryType.REALTIME_CHUNKS) {
            errors.add(path, "realtime_chunks is only valid for realtime_session")
        }
        audio.mime?.let { validateText(it, "$path.mime", required = true, maximum = 256, errors) }
    }

    private fun validateAudioDeliveryUsage(
        audio: AudioSpec,
        stages: List<HttpStage>,
        path: String,
        errors: Collector,
    ) {
        val used = when (audio.delivery) {
            AudioDeliveryType.MULTIPART_FILE -> stages.any(::stageHasAudioFile)
            AudioDeliveryType.RAW_AUDIO -> stages.any(::stageHasRawAudio)
            AudioDeliveryType.BASE64 -> stages.any { stageReferencesAudio(it, AudioPlaceholder.BASE64) }
            AudioDeliveryType.DATA_URI -> stages.any { stageReferencesAudio(it, AudioPlaceholder.DATA_URI) }
            AudioDeliveryType.PUBLIC_HTTPS_URL -> stages.any { stageReferencesAudio(it, AudioPlaceholder.PUBLIC_URL) }
            AudioDeliveryType.CLOUD_URI -> stages.any { stageReferencesAudio(it, AudioPlaceholder.CLOUD_URI) }
            AudioDeliveryType.PROVIDER_UPLOAD,
            AudioDeliveryType.REALTIME_CHUNKS,
            -> true
        }
        if (!used) errors.add(path, "${audioDeliveryName(audio.delivery)} must be used by a pre-recognition HTTP stage")
    }

    private fun validateProviderUpload(
        prepare: HttpStage?,
        submit: HttpStage?,
        audio: AudioSpec,
        errors: Collector,
    ) {
        if (audio.delivery != AudioDeliveryType.PROVIDER_UPLOAD) return
        if (prepare == null) {
            errors.add("audio.delivery", "provider_upload requires an async_poll prepare upload stage")
            return
        }
        if (submit == null) {
            errors.add("audio.delivery", "provider_upload requires an async_poll submit stage")
            return
        }
        if (!stageTransfersLocalAudio(prepare)) {
            errors.add(
                "recognition.prepare.body",
                "provider_upload prepare stage must upload typed multipart audio or raw_audio",
            )
        }
        if (prepare.captures.isEmpty()) {
            errors.add("recognition.prepare.captures", "provider_upload prepare stage must capture its provider audio reference")
            return
        }
        val prepareCaptures = prepare.captures.mapTo(mutableSetOf()) { it.id }
        if (!stageReferencesCaptureIds(submit, prepareCaptures)) {
            errors.add("recognition.submit", "provider_upload submit stage must reference a capture from prepare")
        }
    }

    private fun validateHttpStage(
        stage: HttpStage,
        path: String,
        baseScope: TemplateScope,
        captures: MutableSet<String>,
        errors: Collector,
    ) {
        val scope = baseScope.withCaptures(captures)
        validateTemplateString(stage.url, "$path.url", scope, errors)
        validateHttpUrlTemplate(stage.url, "$path.url", errors)
        validatePairs(stage.query, "$path.query", scope, MAX_QUERY, errors)
        validateHeaderPairs(stage.headers, "$path.headers", scope, errors)
        if (stage.acceptedStatuses.isEmpty() || stage.acceptedStatuses.size > 32) {
            errors.add("$path.accepted_statuses", "must contain 1..=32 HTTP statuses")
        }
        stage.acceptedStatuses.forEachIndexed { index, status ->
            if (status !in 100..599) errors.add("$path.accepted_statuses[$index]", "must be in HTTP range 100..=599")
        }
        validateSigner(stage.signer, "$path.signer", baseScope.secrets, errors)
        validateHttpBody(stage.body, "$path.body", scope, errors)
        // OkHttp deliberately refuses a GET request with a body.  Treat this
        // as a portable workflow-schema restriction so a document cannot pass
        // validation and then fail only after audio preparation/network setup.
        if (stage.method == HttpMethod.GET && stage.body !is HttpBody.None) {
            errors.add("$path.body", "GET stages must use a none body")
        }
        if (stage.signer != SignerConfig.None && (stage.body is HttpBody.Multipart || stage.body == HttpBody.RawAudio)) {
            errors.add(
                "$path.signer",
                "cannot be used with multipart or raw_audio bodies because those uploads remain streaming",
            )
        }
        if (stage.captures.size > MAX_CAPTURES) errors.add("$path.captures", "must contain at most $MAX_CAPTURES captures")
        val local = linkedSetOf<String>()
        stage.captures.forEachIndexed { index, capture ->
            val capturePath = "$path.captures[$index]"
            if (!isWorkflowIdentifier(capture.id)) {
                errors.add("$capturePath.id", "must be an ASCII identifier")
            } else if (capture.id in captures || !local.add(capture.id)) {
                errors.add("$capturePath.id", "capture '${capture.id}' is already defined")
            }
            validateExtractor(capture.from, "$capturePath.from", errors)
        }
        captures += local
    }

    private fun validateHttpUrlTemplate(value: String, path: String, errors: Collector) {
        if (captureOnlyHttpUrlTemplateId(value) != null) return
        validateUrlTemplate(value, path, listOf("http", "https"), errors)
    }

    /** A complete earlier capture is the only dynamic HTTP stage URL shape. */
    fun captureOnlyHttpUrlTemplateId(value: String): String? = value
        .removePrefix("{{capture:")
        .takeIf { value.startsWith("{{capture:") && value.endsWith("}}") }
        ?.removeSuffix("}}")
        ?.takeIf(::isWorkflowIdentifier)

    fun isAbsoluteUrl(value: String, schemes: Set<String>): Boolean = try {
        val uri = URI(value)
        uri.isAbsolute && uri.host != null && uri.scheme.lowercase() in schemes
    } catch (_: Exception) {
        false
    }

    private fun validateUrlTemplate(value: String, path: String, schemes: List<String>, errors: Collector) {
        val prefix = value.substringBefore("{{")
        val valid = schemes.any { scheme ->
            prefix.equals("$scheme://", ignoreCase = true) || prefix.startsWith("$scheme://", ignoreCase = true)
        }
        if (!valid) errors.add(path, "must start with one of ${schemes.joinToString(", ")}://")
    }

    private fun validatePairs(
        values: Map<String, String>,
        path: String,
        scope: TemplateScope,
        maximum: Int,
        errors: Collector,
    ) {
        if (values.size > maximum) errors.add(path, "must contain at most $maximum entries")
        values.forEach { (name, value) ->
            validateText(name, "$path.$name", required = true, maximum = 256, errors)
            validateTemplateString(value, "$path.$name", scope, errors)
        }
    }

    /**
     * Header names are protocol syntax, not arbitrary form-field keys. Values
     * can contain templates, but their literal part may never split a request
     * line. Rendered values receive the same check again at execution time.
     */
    private fun validateHeaderPairs(
        values: Map<String, String>,
        path: String,
        scope: TemplateScope,
        errors: Collector,
    ) {
        if (values.size > MAX_HEADERS) errors.add(path, "must contain at most $MAX_HEADERS entries")
        values.entries.forEachIndexed { index, (name, value) ->
            val entryPath = if (HttpHeaderSafety.isValidName(name)) "$path.$name" else "$path[$index]"
            if (utf8Bytes(name) > 256) {
                errors.add("$entryPath.name", "must contain at most 256 bytes")
            }
            if (!HttpHeaderSafety.isValidName(name)) {
                errors.add("$entryPath.name", "must be a non-empty HTTP header token")
            }
            if (HttpHeaderSafety.hasUnsafeValueCharacters(value)) {
                errors.add("$entryPath.value", "must not contain line breaks or NUL bytes")
            }
            validateTemplateString(value, "$entryPath.value", scope, errors)
        }
    }

    private fun validateHttpBody(body: HttpBody, path: String, scope: TemplateScope, errors: Collector) {
        when (body) {
            HttpBody.None -> Unit
            HttpBody.RawAudio -> if (scope.audioDelivery !in setOf(AudioDeliveryType.RAW_AUDIO, AudioDeliveryType.PROVIDER_UPLOAD)) {
                errors.add(path, "raw_audio body is incompatible with the selected audio delivery")
            }

            is HttpBody.RawBytes -> validateTemplateString(body.value, "$path.value", scope, errors)
            is HttpBody.FormUrlencoded -> validatePairs(body.fields, "$path.fields", scope, MAX_QUERY, errors)
            is HttpBody.Json -> validateJsonTemplates(body.value, "$path.value", scope, errors)
            is HttpBody.Multipart -> {
                if (body.fields.isEmpty() || body.fields.size > MAX_MULTIPART_FIELDS) {
                    errors.add(path, "must contain 1..=$MAX_MULTIPART_FIELDS fields")
                }
                val names = mutableSetOf<String>()
                var audioFields = 0
                body.fields.forEachIndexed { index, field ->
                    val fieldPath = "$path.fields[$index]"
                    validateText(field.name, "$fieldPath.name", required = true, maximum = 256, errors)
                    if (!names.add(field.name)) errors.add("$fieldPath.name", "duplicate multipart field")
                    when (val fieldValue = field.value) {
                        is MultipartValue.Text -> validateTemplateString(fieldValue.value, "$fieldPath.value", scope, errors)
                        is MultipartValue.Bytes -> validateTemplateString(fieldValue.value, "$fieldPath.value", scope, errors)
                        MultipartValue.AudioFile -> audioFields += 1
                    }
                }
                if (audioFields > 1) errors.add(path, "may contain at most one typed audio_file field")
                if (audioFields > 0 && scope.audioDelivery !in setOf(AudioDeliveryType.MULTIPART_FILE, AudioDeliveryType.PROVIDER_UPLOAD)) {
                    errors.add(path, "typed multipart audio_file is incompatible with the selected audio delivery")
                }
            }
        }
    }

    private fun validateJsonTemplates(value: JsonValue, path: String, scope: TemplateScope, errors: Collector) {
        when (value) {
            is JsonValue.Text -> validateTemplateString(value.value, path, scope, errors)
            is JsonValue.Array -> value.values.forEachIndexed { index, item ->
                validateJsonTemplates(item, "$path[$index]", scope, errors)
            }

            is JsonValue.Object -> value.values.forEach { (name, item) ->
                validateJsonTemplates(item, "$path.$name", scope, errors)
            }

            JsonValue.Null,
            is JsonValue.Bool,
            is JsonValue.Number,
            -> Unit
        }
    }

    private fun validateTemplateString(value: String, path: String, scope: TemplateScope, errors: Collector) {
        if (utf8Bytes(value) > MAX_TEMPLATE_BYTES) {
            errors.add(path, "template exceeds $MAX_TEMPLATE_BYTES bytes")
            return
        }
        val template = try {
            Template.parse(value)
        } catch (error: TemplateException) {
            errors.add(path, error.message.orEmpty())
            return
        }
        template.placeholders.forEach { placeholder ->
            when (placeholder) {
                is Placeholder.Variable -> if (placeholder.id !in scope.parameters) {
                    errors.add(path, "references undeclared variable '${placeholder.id}'")
                }

                is Placeholder.Secret -> if (placeholder.id !in scope.secrets) {
                    errors.add(path, "references undeclared secret '${placeholder.id}'")
                }

                is Placeholder.Capture -> if (placeholder.id !in scope.captures) {
                    errors.add(path, "references capture '${placeholder.id}' before it is created")
                }

                is Placeholder.Audio -> if (!audioPlaceholderAllowed(placeholder.type, scope)) {
                    errors.add(path, "${placeholder.display()} is incompatible with this audio delivery")
                }

                is Placeholder.Runtime -> Unit
            }
        }
    }

    private fun audioPlaceholderAllowed(audio: AudioPlaceholder, scope: TemplateScope): Boolean = when (scope.audioAccess) {
        AudioTemplateAccess.NONE -> false
        AudioTemplateAccess.REALTIME_CHUNK_ONLY -> audio == AudioPlaceholder.CHUNK_BASE64
        AudioTemplateAccess.DELIVERY -> when (audio) {
            AudioPlaceholder.FILENAME,
            AudioPlaceholder.MIME,
            AudioPlaceholder.SIZE,
            -> true

            AudioPlaceholder.BASE64 -> scope.audioDelivery == AudioDeliveryType.BASE64
            AudioPlaceholder.DATA_URI -> scope.audioDelivery == AudioDeliveryType.DATA_URI
            AudioPlaceholder.PUBLIC_URL -> scope.audioDelivery == AudioDeliveryType.PUBLIC_HTTPS_URL
            AudioPlaceholder.CLOUD_URI -> scope.audioDelivery == AudioDeliveryType.CLOUD_URI
            AudioPlaceholder.CHUNK_BASE64 -> scope.realtime && scope.audioDelivery == AudioDeliveryType.REALTIME_CHUNKS
        }
    }

    private fun validateExtractor(extractor: ResponseExtractor, path: String, errors: Collector) {
        when (extractor) {
            is ResponseExtractor.JsonPath -> try {
                JsonPath.parse(extractor.path)
            } catch (error: JsonPathException) {
                errors.add(path, error.message.orEmpty())
            }

            is ResponseExtractor.Header -> if (!HttpHeaderSafety.isValidName(extractor.name) || utf8Bytes(extractor.name) > 256) {
                errors.add(path, "header name must be a non-empty HTTP header name")
            }

            ResponseExtractor.PlainBody,
            ResponseExtractor.Status,
            -> Unit
        }
    }

    private fun validateStream(
        stream: StreamResponse,
        path: String,
        scope: TemplateScope,
        requireComplete: Boolean,
        errors: Collector,
    ) {
        if (stream.rules.isEmpty() || stream.rules.size > MAX_STREAM_RULES) {
            errors.add("$path.rules", "must contain 1..=$MAX_STREAM_RULES rules")
        }
        var hasComplete = false
        stream.rules.forEachIndexed { index, rule ->
            validateStreamRule(rule, "$path.rules[$index]", scope, errors)
            hasComplete = hasComplete || rule.action == StreamAction.COMPLETE
        }
        if (requireComplete && !hasComplete) errors.add("$path.rules", "must include a complete action")
    }

    private fun validateStreamRule(rule: StreamRule, path: String, scope: TemplateScope, errors: Collector) {
        rule.event?.let { validateText(it, "$path.event", required = true, maximum = 256, errors) }
        rule.path?.let { jsonPath ->
            try {
                JsonPath.parse(jsonPath)
            } catch (error: JsonPathException) {
                errors.add("$path.path", error.message.orEmpty())
            }
        }
        if (rule.action in TEXT_STREAM_ACTIONS && rule.path == null) {
            errors.add("$path.path", "is required for this transcript action")
        }
        if (rule.equals != null && rule.path == null) errors.add("$path.equals", "requires path so a value can be compared")
    }

    private fun validatePollStage(
        stage: PollStage,
        path: String,
        baseScope: TemplateScope,
        captures: MutableSet<String>,
        errors: Collector,
    ) {
        if (stage.request.method != HttpMethod.GET && stage.request.method != HttpMethod.POST) {
            errors.add("$path.request.method", "poll requests support only GET or POST")
        }
        if (stage.intervalMs < MIN_POLL_INTERVAL_MS) errors.add("$path.interval_ms", "must be at least $MIN_POLL_INTERVAL_MS")
        if (stage.timeoutMs < stage.intervalMs || stage.timeoutMs > MAX_POLL_TIMEOUT_MS) {
            errors.add("$path.timeout_ms", "must be between interval_ms and $MAX_POLL_TIMEOUT_MS")
        }
        validateHttpStage(stage.request, "$path.request", baseScope, captures, errors)
        if (stageTransfersLocalAudio(stage.request) || stageReferencesAudio(stage.request, null)) {
            errors.add("$path.request", "poll requests must not resend audio or an audio reference")
        }
        validateConditionGroup(stage.pending, "$path.pending", errors)
        validateConditionGroup(stage.success, "$path.success", errors)
        validateConditionGroup(stage.failure, "$path.failure", errors)
        if (stage.pending.isEmpty() || stage.success.isEmpty() || stage.failure.isEmpty()) {
            errors.add(path, "must declare at least one pending, success, and failure condition")
        }
    }

    private fun validateConditionGroup(conditions: List<PollCondition>, path: String, errors: Collector) {
        if (conditions.size > MAX_STREAM_RULES) errors.add(path, "must contain at most $MAX_STREAM_RULES conditions")
        conditions.forEachIndexed { index, condition ->
            val conditionPath = "$path[$index]"
            validateExtractor(condition.from, "$conditionPath.from", errors)
            when (condition.operator) {
                PollOperator.EQ,
                PollOperator.NE,
                -> if (condition.value == null) errors.add("$conditionPath.value", "is required for eq/ne")

                PollOperator.IN -> if (condition.values.isEmpty()) errors.add("$conditionPath.values", "is required for in")
                PollOperator.EXISTS,
                PollOperator.NOT_EXISTS,
                PollOperator.IS_TRUE,
                PollOperator.IS_FALSE,
                -> if (condition.value != null || condition.values.isNotEmpty()) {
                    errors.add(conditionPath, "does not accept value(s) for this operator")
                }
            }
        }
    }

    private fun validateSigner(signer: SignerConfig, path: String, secrets: Set<String>, errors: Collector) {
        fun check(id: String, key: String) {
            if (id !in secrets) errors.add("$path.$key", "references undeclared secret '$id'")
        }
        when (signer) {
            SignerConfig.None -> Unit
            is SignerConfig.AwsSigv4 -> {
                validateText(signer.region, "$path.region", required = true, maximum = 256, errors)
                validateText(signer.service, "$path.service", required = true, maximum = 256, errors)
                check(signer.accessKeySecret, "access_key_secret")
                check(signer.secretKeySecret, "secret_key_secret")
                signer.sessionTokenSecret?.let { check(it, "session_token_secret") }
            }

            is SignerConfig.TencentTc3 -> {
                validateText(signer.service, "$path.service", required = true, maximum = 256, errors)
                check(signer.secretIdSecret, "secret_id_secret")
                check(signer.secretKeySecret, "secret_key_secret")
            }
        }
    }

    private fun validateRealtime(realtime: RealtimeWorkflow, path: String, baseScope: TemplateScope, errors: Collector) {
        if (realtime.transport != RealtimeTransport.WEBSOCKET) {
            errors.add("$path.transport", "Unsupported transport; this build implements only websocket")
        }
        val connectionScope = baseScope.realtime().withoutAudio()
        val audioMessageScope = baseScope.realtimeChunkOnly()
        validateTemplateString(realtime.connect.url, "$path.connect.url", connectionScope, errors)
        validateUrlTemplate(realtime.connect.url, "$path.connect.url", listOf("ws", "wss"), errors)
        validatePairs(realtime.connect.query, "$path.connect.query", connectionScope, MAX_QUERY, errors)
        validateHeaderPairs(realtime.connect.headers, "$path.connect.headers", connectionScope, errors)
        validateSigner(realtime.connect.signer, "$path.connect.signer", connectionScope.secrets, errors)
        realtime.connect.subprotocol?.let {
            validateText(it, "$path.connect.subprotocol", required = true, maximum = 256, errors)
            validateTemplateString(it, "$path.connect.subprotocol", connectionScope, errors)
        }
        validateMessages(realtime.initialMessages, "$path.initial_messages", connectionScope, errors)
        validateMessages(realtime.finishMessages, "$path.finish_messages", connectionScope, errors)
        validateRealtimeAudio(realtime, path, errors)
        when (val message = realtime.audioMessage) {
            RealtimeAudioMessage.Binary -> Unit
            is RealtimeAudioMessage.Text -> {
                val messagePath = "$path.audio_message.value"
                validateTemplateString(message.value, messagePath, audioMessageScope, errors)
                if (!containsChunkPlaceholder(message.value)) {
                    errors.add(messagePath, "must reference {{audio:chunk_base64}} for each audio chunk")
                }
            }

            is RealtimeAudioMessage.Json -> {
                val messagePath = "$path.audio_message.value"
                validateJsonTemplates(message.value, messagePath, audioMessageScope, errors)
                if (!jsonContainsChunkPlaceholder(message.value)) {
                    errors.add(messagePath, "must reference {{audio:chunk_base64}} for each audio chunk")
                }
            }
        }
        validateStream(
            StreamResponse(StreamFormat.JSON_CHUNKS, realtime.receiveRules),
            "$path.receive",
            connectionScope,
            requireComplete = !realtimeCompletionCanComplete(realtime.completion),
            errors,
        )
        validateRealtimeCompletion(realtime.completion, "$path.completion", errors)
        if (realtime.pauseBehavior != PauseBehavior.RESTART_SESSION) {
            errors.add("$path.pause_behavior", "Unsupported pause behavior; this build implements only restart_session")
        }
        if (realtime.finalizationTimeoutMs !in 1..MAX_POLL_TIMEOUT_MS) {
            errors.add("$path.finalization_timeout_ms", "must be in 1..=86400000")
        }
    }

    private fun validateMessages(messages: List<RealtimeMessage>, path: String, scope: TemplateScope, errors: Collector) {
        if (messages.size > MAX_MESSAGES) errors.add(path, "must contain at most $MAX_MESSAGES messages")
        messages.forEachIndexed { index, message ->
            when (message) {
                is RealtimeMessage.Json -> validateJsonTemplates(message.value, "$path[$index]", scope, errors)
                is RealtimeMessage.Text -> validateTemplateString(message.value, "$path[$index]", scope, errors)
                is RealtimeMessage.Binary -> validateTemplateString(message.value, "$path[$index]", scope, errors)
            }
        }
    }

    private fun validateRealtimeAudio(realtime: RealtimeWorkflow, path: String, errors: Collector) {
        val audio = realtime.audioStream
        validateText(audio.codec, "$path.audio_stream.codec", required = true, maximum = 128, errors)
        if (!audio.codec.equals("pcm_s16le", ignoreCase = true)) {
            errors.add("$path.audio_stream.codec", "Unsupported codec; this build implements only pcm_s16le realtime audio")
        }
        if (audio.sampleRate !in 1..384_000) errors.add("$path.audio_stream.sample_rate", "must be in 1..=384000")
        if (audio.channels !in 1..8) errors.add("$path.audio_stream.channels", "must be in 1..=8")
        if (audio.chunkDurationMs !in MIN_CHUNK_DURATION_MS..MAX_CHUNK_DURATION_MS) {
            errors.add("$path.audio_stream.chunk_duration_ms", "must be in $MIN_CHUNK_DURATION_MS..=$MAX_CHUNK_DURATION_MS")
        }
        if (audio.pacing != RealtimePacing.REALTIME) {
            errors.add("$path.audio_stream.pacing", "Unsupported pacing; this build implements only realtime")
        }
    }

    private fun validateRealtimeCompletion(completion: RealtimeCompletion, path: String, errors: Collector) {
        completion.event?.let { validateText(it, "$path.event", required = true, maximum = 256, errors) }
        completion.path?.let { jsonPath ->
            try {
                JsonPath.parse(jsonPath)
            } catch (error: JsonPathException) {
                errors.add("$path.path", error.message.orEmpty())
            }
        }
        if (completion.equals != null && completion.path == null) {
            errors.add("$path.equals", "requires completion.path so a value can be compared")
        }
    }

    private fun validateText(value: String, path: String, required: Boolean, maximum: Int, errors: Collector) {
        if (required && value.trim().isEmpty()) errors.add(path, "must not be empty")
        if (utf8Bytes(value) > maximum) errors.add(path, "must contain at most $maximum bytes")
        if (value.contains('\r') || value.contains('\n') || value.contains('\u0000')) {
            errors.add(path, "must not contain line breaks or NUL bytes")
        }
    }

    private fun stageHasAudioFile(stage: HttpStage): Boolean =
        (stage.body as? HttpBody.Multipart)?.fields?.any { it.value == MultipartValue.AudioFile } == true

    private fun stageHasRawAudio(stage: HttpStage): Boolean = stage.body == HttpBody.RawAudio

    private fun stageTransfersLocalAudio(stage: HttpStage): Boolean = stageHasAudioFile(stage) || stageHasRawAudio(stage)

    private fun stageReferencesAudio(stage: HttpStage, wanted: AudioPlaceholder?): Boolean =
        templateReferencesAudio(stage.url, wanted) ||
            stage.query.values.any { templateReferencesAudio(it, wanted) } ||
            stage.headers.values.any { templateReferencesAudio(it, wanted) } ||
            bodyReferencesAudio(stage.body, wanted)

    private fun bodyReferencesAudio(body: HttpBody, wanted: AudioPlaceholder?): Boolean = when (body) {
        HttpBody.None,
        HttpBody.RawAudio,
        -> false

        is HttpBody.RawBytes -> templateReferencesAudio(body.value, wanted)
        is HttpBody.FormUrlencoded -> body.fields.values.any { templateReferencesAudio(it, wanted) }
        is HttpBody.Multipart -> body.fields.any { field ->
            when (val value = field.value) {
                is MultipartValue.Text -> templateReferencesAudio(value.value, wanted)
                is MultipartValue.Bytes -> templateReferencesAudio(value.value, wanted)
                MultipartValue.AudioFile -> false
            }
        }

        is HttpBody.Json -> jsonReferencesAudio(body.value, wanted)
    }

    private fun jsonReferencesAudio(value: JsonValue, wanted: AudioPlaceholder?): Boolean = when (value) {
        is JsonValue.Text -> templateReferencesAudio(value.value, wanted)
        is JsonValue.Array -> value.values.any { jsonReferencesAudio(it, wanted) }
        is JsonValue.Object -> value.values.values.any { jsonReferencesAudio(it, wanted) }
        JsonValue.Null,
        is JsonValue.Bool,
        is JsonValue.Number,
        -> false
    }

    private fun templateReferencesAudio(value: String, wanted: AudioPlaceholder?): Boolean = runCatching {
        Template.parse(value).placeholders.any { placeholder ->
            placeholder is Placeholder.Audio && (wanted == null || placeholder.type == wanted)
        }
    }.getOrDefault(false)

    private fun stageReferencesCaptureIds(stage: HttpStage, wanted: Set<String>): Boolean =
        templateReferencesCaptureIds(stage.url, wanted) ||
            stage.query.values.any { templateReferencesCaptureIds(it, wanted) } ||
            stage.headers.values.any { templateReferencesCaptureIds(it, wanted) } ||
            bodyReferencesCaptureIds(stage.body, wanted)

    private fun bodyReferencesCaptureIds(body: HttpBody, wanted: Set<String>): Boolean = when (body) {
        HttpBody.None,
        HttpBody.RawAudio,
        -> false

        is HttpBody.RawBytes -> templateReferencesCaptureIds(body.value, wanted)
        is HttpBody.FormUrlencoded -> body.fields.values.any { templateReferencesCaptureIds(it, wanted) }
        is HttpBody.Multipart -> body.fields.any { field ->
            when (val value = field.value) {
                is MultipartValue.Text -> templateReferencesCaptureIds(value.value, wanted)
                is MultipartValue.Bytes -> templateReferencesCaptureIds(value.value, wanted)
                MultipartValue.AudioFile -> false
            }
        }

        is HttpBody.Json -> jsonReferencesCaptureIds(body.value, wanted)
    }

    private fun jsonReferencesCaptureIds(value: JsonValue, wanted: Set<String>): Boolean = when (value) {
        is JsonValue.Text -> templateReferencesCaptureIds(value.value, wanted)
        is JsonValue.Array -> value.values.any { jsonReferencesCaptureIds(it, wanted) }
        is JsonValue.Object -> value.values.values.any { jsonReferencesCaptureIds(it, wanted) }
        JsonValue.Null,
        is JsonValue.Bool,
        is JsonValue.Number,
        -> false
    }

    private fun templateReferencesCaptureIds(value: String, wanted: Set<String>): Boolean = runCatching {
        Template.parse(value).placeholders.any { it is Placeholder.Capture && it.id in wanted }
    }.getOrDefault(false)

    private fun containsChunkPlaceholder(value: String): Boolean = templateReferencesAudio(value, AudioPlaceholder.CHUNK_BASE64)

    private fun jsonContainsChunkPlaceholder(value: JsonValue): Boolean = when (value) {
        is JsonValue.Text -> containsChunkPlaceholder(value.value)
        is JsonValue.Array -> value.values.any(::jsonContainsChunkPlaceholder)
        is JsonValue.Object -> value.values.values.any(::jsonContainsChunkPlaceholder)
        JsonValue.Null,
        is JsonValue.Bool,
        is JsonValue.Number,
        -> false
    }

    private fun realtimeCompletionCanComplete(completion: RealtimeCompletion): Boolean =
        completion.event != null || completion.path != null

    private fun audioDeliveryName(delivery: AudioDeliveryType): String = when (delivery) {
        AudioDeliveryType.MULTIPART_FILE -> "multipart_file delivery"
        AudioDeliveryType.RAW_AUDIO -> "raw_audio delivery"
        AudioDeliveryType.BASE64 -> "base64 delivery"
        AudioDeliveryType.DATA_URI -> "data_uri delivery"
        AudioDeliveryType.PUBLIC_HTTPS_URL -> "public_https_url delivery"
        AudioDeliveryType.CLOUD_URI -> "cloud_uri delivery"
        AudioDeliveryType.PROVIDER_UPLOAD -> "provider_upload delivery"
        AudioDeliveryType.REALTIME_CHUNKS -> "realtime_chunks delivery"
    }

    private fun utf8Bytes(value: String): Int = value.toByteArray(Charsets.UTF_8).size

    private const val MAX_DECLARATIONS = 64
    private const val MAX_HEADERS = 64
    private const val MAX_QUERY = 64
    private const val MAX_CAPTURES = 64
    private const val MAX_MULTIPART_FIELDS = 64
    private const val MAX_STREAM_RULES = 64
    private const val MAX_MESSAGES = 32
    private const val MAX_POLL_TIMEOUT_MS = 24 * 60 * 60 * 1_000L
    private const val MIN_POLL_INTERVAL_MS = 100L
    private const val MIN_CHUNK_DURATION_MS = 10
    private const val MAX_CHUNK_DURATION_MS = 1_000
    private val TEXT_STREAM_ACTIONS = setOf(
        StreamAction.APPEND_DELTA,
        StreamAction.REPLACE_PARTIAL,
        StreamAction.COMMIT_SEGMENT,
        StreamAction.SET_FINAL_TEXT,
        StreamAction.FAIL,
    )

    private class Collector {
        val errors = mutableListOf<WorkflowValidationError>()
        fun add(path: String, message: String) {
            errors += WorkflowValidationError(path, message)
        }
    }

    private enum class AudioTemplateAccess {
        DELIVERY,
        NONE,
        REALTIME_CHUNK_ONLY,
    }

    private data class TemplateScope(
        val parameters: Set<String>,
        val secrets: Set<String>,
        val captures: Set<String>,
        val audioDelivery: AudioDeliveryType,
        val realtime: Boolean,
        val audioAccess: AudioTemplateAccess,
    ) {
        fun withCaptures(updated: Set<String>): TemplateScope = copy(captures = updated.toSet())

        fun realtime(): TemplateScope = copy(realtime = true)

        fun withoutAudio(): TemplateScope = copy(audioAccess = AudioTemplateAccess.NONE)

        fun realtimeChunkOnly(): TemplateScope = copy(
            realtime = true,
            audioAccess = AudioTemplateAccess.REALTIME_CHUNK_ONLY,
        )
    }
}
