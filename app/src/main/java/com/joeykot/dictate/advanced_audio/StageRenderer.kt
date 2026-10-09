package com.joeykot.dictate.advanced_audio

import com.joeykot.dictate.advanced_audio.auth.AwsSigV4Signer
import com.joeykot.dictate.advanced_audio.auth.HttpRequestSigner
import com.joeykot.dictate.advanced_audio.auth.TencentTc3Signer
import com.joeykot.dictate.advanced_audio.http.AdvancedHttpMethod
import com.joeykot.dictate.advanced_audio.http.HttpBodySpec
import com.joeykot.dictate.advanced_audio.http.HttpHeader
import com.joeykot.dictate.advanced_audio.http.HttpQueryParameter
import com.joeykot.dictate.advanced_audio.http.HttpRequestSpec
import com.joeykot.dictate.advanced_audio.http.MultipartPart
import com.joeykot.dictate.advanced_audio.http.PreparedAudioUpload
import java.io.File
import java.util.Base64

/**
 * The local recording metadata available to one non-realtime workflow.
 *
 * This object never exposes a local path to templates.  A workflow can only
 * use the finite `audio:*` placeholders declared by the cross-platform
 * contract.  Base64/Data URI delivery is deliberately bounded because it is
 * the one delivery form that materialises the file in memory.
 */
class WorkflowAudio(
    val upload: PreparedAudioUpload,
    val publicHttpsUrl: String? = null,
    val cloudUri: String? = null,
) {
    init {
        require(upload.file.isFile && upload.file.canRead()) { "Advanced audio file is unavailable" }
        require(upload.sizeBytes >= 0L) { "Advanced audio size is invalid" }
    }

    fun withRemoteReference(publicHttpsUrl: String?, cloudUri: String?): WorkflowAudio = WorkflowAudio(
        upload = upload,
        publicHttpsUrl = publicHttpsUrl,
        cloudUri = cloudUri,
    )

    fun templateValues(includeEncodedAudio: Boolean): AudioTemplateValues {
        val base = AudioTemplateValues(
            filename = upload.filename,
            mime = upload.mimeType,
            size = upload.sizeBytes.toString(),
            publicUrl = publicHttpsUrl,
            cloudUri = cloudUri,
        )
        if (!includeEncodedAudio) return base
        if (upload.sizeBytes > MAX_BASE64_AUDIO_BYTES) {
            throw StageRenderingException.AudioTooLargeForBase64(upload.sizeBytes)
        }
        val bytes = try {
            upload.file.readBytes()
        } catch (_: Exception) {
            throw StageRenderingException.AudioUnavailable
        }
        val encoded = Base64.getEncoder().encodeToString(bytes)
        return base.copy(
            base64 = encoded,
            dataUri = "data:${upload.mimeType};base64,$encoded",
        )
    }

    companion object {
        const val MAX_BASE64_AUDIO_BYTES: Long = 16L * 1024L * 1024L

        fun fromFile(file: File, mimeType: String): WorkflowAudio = WorkflowAudio(
            PreparedAudioUpload(
                file = file,
                filename = file.name.ifBlank { "audio" },
                mimeType = mimeType,
                sizeBytes = file.length(),
            ),
        )
    }
}

/**
 * Turns one already-validated workflow stage into transport primitives.
 * The transport intentionally receives rendered data only; it therefore
 * cannot change template or schema-v2 typed-value semantics.
 */
class WorkflowStageRenderer(
    private val workflow: AdvancedAudioWorkflow,
    private val values: Map<String, String>,
    private val secrets: Map<String, String>,
    private val runtime: RuntimeTemplateValues,
) {
    fun render(
        stage: HttpStage,
        audio: WorkflowAudio,
        captures: Map<String, String>,
    ): HttpRequestSpec {
        val context = templateContext(audio, captures)
        val url = renderText(stage.url, context)
        val query = stage.query.map { (name, value) ->
            HttpQueryParameter(name, renderText(value, context))
        }
        val headers = stage.headers.map { (name, value) ->
            HttpHeader(name, renderText(value, context))
        }
        requireSafeHeaders(headers)
        return HttpRequestSpec(
            method = stage.method.toTransportMethod(),
            url = url,
            query = query,
            headers = headers,
            body = renderBody(stage.body, context, audio.upload),
            acceptedStatuses = stage.acceptedStatuses.toSet(),
            signer = signerFor(stage.signer),
        )
    }

    /**
     * Checks every workflow header which can be rendered before a remote audio
     * object is published. Captures and remote references are represented by
     * safe sentinels here; their actual values still receive the final check
     * in [render] immediately before the corresponding HTTP request.
     */
    fun preflightHeadersBeforeRemoteUpload(stages: Iterable<HttpStage>, audio: WorkflowAudio) {
        stages.forEach { stage ->
            stage.headers.forEach { (name, value) ->
                requireSafeHeaders(listOf(HttpHeader(name, "")))
                val captures = try {
                    Template.parse(value).placeholders
                        .filterIsInstance<Placeholder.Capture>()
                        .associate { placeholder -> placeholder.id to PREFLIGHT_CAPTURE_VALUE }
                } catch (error: IllegalArgumentException) {
                    throw StageRenderingException.Template(error.message ?: "Unable to render a workflow template")
                }
                val rendered = renderText(value, templateContext(audio, captures, previewRemoteReferences = true))
                requireSafeHeaders(listOf(HttpHeader(name, rendered)))
            }
        }
    }

    private fun templateContext(
        audio: WorkflowAudio,
        captures: Map<String, String>,
        previewRemoteReferences: Boolean = false,
    ): TemplateContext {
        val audioValues = audio.templateValues(
            includeEncodedAudio = workflow.audio.delivery == AudioDeliveryType.BASE64 ||
                workflow.audio.delivery == AudioDeliveryType.DATA_URI,
        ).let { values ->
            if (!previewRemoteReferences) values else values.copy(
                publicUrl = values.publicUrl ?: PREFLIGHT_PUBLIC_URL,
                cloudUri = values.cloudUri ?: PREFLIGHT_CLOUD_URI,
            )
        }
        return TemplateContext(
            values = values,
            secrets = secrets,
            captures = captures,
            audio = audioValues,
            runtime = runtime,
        )
    }

    private fun renderBody(
        body: HttpBody,
        context: TemplateContext,
        upload: PreparedAudioUpload,
    ): HttpBodySpec = when (body) {
        HttpBody.None -> HttpBodySpec.None
        is HttpBody.Json -> HttpBodySpec.Json(
            JsonValueCodec.stringify(
                TypedTemplateRenderer.renderJson(
                    body.value,
                    context,
                    workflow.parameters,
                    workflow.schemaVersion,
                ),
            ),
        )
        is HttpBody.FormUrlencoded -> HttpBodySpec.FormUrlEncoded(
            body.fields.map { (name, value) -> HttpQueryParameter(name, renderText(value, context)) },
        )
        is HttpBody.Multipart -> HttpBodySpec.Multipart(
            fields = body.fields.map { field ->
                MultipartPart(
                    name = field.name,
                    value = when (val value = field.value) {
                        is MultipartValue.Text -> com.joeykot.dictate.advanced_audio.http.MultipartValue.Text(
                            renderText(value.value, context),
                        )
                        is MultipartValue.Bytes -> com.joeykot.dictate.advanced_audio.http.MultipartValue.Bytes(
                            decodeBase64Bytes(renderText(value.value, context)),
                        )
                        MultipartValue.AudioFile -> com.joeykot.dictate.advanced_audio.http.MultipartValue.AudioFile
                    },
                )
            },
            audio = upload,
        )
        HttpBody.RawAudio -> HttpBodySpec.RawAudio(upload)
        is HttpBody.RawBytes -> HttpBodySpec.RawBytes(renderText(body.value, context).toByteArray(Charsets.UTF_8))
    }

    private fun renderText(value: String, context: TemplateContext): String = try {
        TypedTemplateRenderer.renderText(value, context, workflow.parameters, workflow.schemaVersion)
    } catch (error: IllegalArgumentException) {
        throw StageRenderingException.Template(error.message ?: "Unable to render a workflow template")
    }

    private fun requireSafeHeaders(headers: List<HttpHeader>) {
        try {
            HttpHeaderSafety.requireSafe(headers)
        } catch (_: HeaderSafetyException) {
            throw StageRenderingException.InvalidHttpHeader
        }
    }

    private fun decodeBase64Bytes(value: String): ByteArray = try {
        Base64.getDecoder().decode(value)
    } catch (_: IllegalArgumentException) {
        throw StageRenderingException.InvalidBase64Bytes
    }

    private fun signerFor(config: SignerConfig): HttpRequestSigner? = when (config) {
        SignerConfig.None -> null
        is SignerConfig.AwsSigv4 -> AwsSigV4Signer(
            region = config.region,
            service = config.service,
            accessKey = requiredSecret(config.accessKeySecret),
            secretKey = requiredSecret(config.secretKeySecret),
            sessionToken = config.sessionTokenSecret?.let(::requiredSecret),
        )
        is SignerConfig.TencentTc3 -> TencentTc3Signer(
            service = config.service,
            secretId = requiredSecret(config.secretIdSecret),
            secretKey = requiredSecret(config.secretKeySecret),
        )
    }

    private fun requiredSecret(id: String): String = secrets[id]
        ?.takeIf { it.isNotEmpty() }
        ?: throw StageRenderingException.MissingSecret(id)

    private fun HttpMethod.toTransportMethod(): AdvancedHttpMethod = when (this) {
        HttpMethod.GET -> AdvancedHttpMethod.GET
        HttpMethod.POST -> AdvancedHttpMethod.POST
        HttpMethod.PUT -> AdvancedHttpMethod.PUT
        HttpMethod.PATCH -> AdvancedHttpMethod.PATCH
        HttpMethod.DELETE -> AdvancedHttpMethod.DELETE
    }

    private companion object {
        const val PREFLIGHT_CAPTURE_VALUE = "capture"
        const val PREFLIGHT_PUBLIC_URL = "https://preflight.invalid/audio"
        const val PREFLIGHT_CLOUD_URI = "cloud://preflight/audio"
    }
}

sealed class StageRenderingException(message: String) : IllegalArgumentException(message) {
    data class AudioTooLargeForBase64(val size: Long) : StageRenderingException(
        "Audio file is $size bytes; Base64 and Data URI delivery is limited to ${WorkflowAudio.MAX_BASE64_AUDIO_BYTES} bytes",
    )

    data object AudioUnavailable : StageRenderingException("Advanced audio file is unavailable")
    data object InvalidBase64Bytes : StageRenderingException("Multipart bytes value is not valid Base64")
    data object InvalidHttpHeader : StageRenderingException("Workflow contains an invalid HTTP header")
    data class MissingSecret(val id: String) : StageRenderingException("Missing secret '$id'")
    data class Template(val detail: String) : StageRenderingException(detail)
}
