package com.joeykot.dictate.advanced_audio.remote_audio

import com.joeykot.dictate.advanced_audio.AudioDeliveryType
import com.joeykot.dictate.advanced_audio.auth.AwsSigV4Signer
import com.joeykot.dictate.advanced_audio.auth.hexLower
import com.joeykot.dictate.advanced_audio.auth.hmacSha256
import com.joeykot.dictate.advanced_audio.auth.sha256Hex
import com.joeykot.dictate.advanced_audio.http.AdvancedCancellationToken
import com.joeykot.dictate.advanced_audio.http.AdvancedHttpMethod
import com.joeykot.dictate.advanced_audio.http.AdvancedHttpTransport
import com.joeykot.dictate.advanced_audio.http.HttpBodySpec
import com.joeykot.dictate.advanced_audio.http.HttpHeader
import com.joeykot.dictate.advanced_audio.http.HttpRequestSpec
import com.joeykot.dictate.advanced_audio.http.HttpTransportException
import com.joeykot.dictate.advanced_audio.http.PreparedAudioUpload
import com.joeykot.dictate.advanced_audio.http.RemoteStorageSigningPayload
import com.joeykot.dictate.model.AdvancedRemoteAudioConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.URI
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.TreeMap
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Publishes one prepared recording when a workflow explicitly uses either a
 * public HTTPS URL or a provider cloud URI.  It deliberately does not try to
 * convert one reference kind into the other.
 *
 * The caller owns the successful-recognition lifetime: after the whole
 * workflow finishes (including polling and result retrieval), call [cleanup].
 * Upload failures and cancellation already force best-effort cleanup here.
 */
class RemoteAudioPublisher(
    private val transport: AdvancedHttpTransport,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Streams [audio] to the configured remote store and returns the only
     * reference form the requested [delivery] permits.
     *
     * [secrets] is an in-memory map resolved by the settings layer.  It is
     * never retained in public results or included in an exception message.
     */
    @Throws(RemoteAudioException::class)
    fun publish(
        config: AdvancedRemoteAudioConfig,
        secrets: Map<String, String>,
        audio: PreparedAudioUpload,
        delivery: AudioDeliveryType,
        cancellation: AdvancedCancellationToken = AdvancedCancellationToken.none(),
    ): PublishedRemoteAudio = try {
        requirePublishConfiguration(config, delivery, secrets)
        ensureAudioAvailable(audio)
        cancellation.throwIfCancelled()
        when (config) {
            AdvancedRemoteAudioConfig.None -> throw RemoteAudioException.NotConfigured
            is AdvancedRemoteAudioConfig.WebDav -> publishWebDav(config, secrets, audio, delivery, cancellation)
            is AdvancedRemoteAudioConfig.S3Compatible -> publishS3(config, secrets, audio, delivery, cancellation)
            is AdvancedRemoteAudioConfig.AliyunOss -> publishAliyunOss(config, secrets, audio, delivery, cancellation)
        }
    } catch (error: RemoteAudioException) {
        throw error
    } catch (error: HttpTransportException) {
        throw mapTransportFailure(error)
    } catch (_: IllegalArgumentException) {
        // HttpUrl, headers, and cryptographic helpers can all reject malformed
        // configuration.  Their original text may contain a URL or credential.
        throw RemoteAudioException.InvalidConfiguration
    } catch (_: Exception) {
        // No provider response, endpoint, or storage credential is safe to
        // surface from this boundary.
        throw RemoteAudioException.RequestFailed
    }

    private fun requirePublishConfiguration(
        config: AdvancedRemoteAudioConfig,
        delivery: AudioDeliveryType,
        secrets: Map<String, String>,
    ) {
        if (delivery != AudioDeliveryType.PUBLIC_HTTPS_URL && delivery != AudioDeliveryType.CLOUD_URI) {
            throw RemoteAudioException.IncompatibleDelivery
        }
        val errors = validate(config, delivery, secrets)
        if (errors.isEmpty()) return
        if (config is AdvancedRemoteAudioConfig.WebDav && delivery == AudioDeliveryType.CLOUD_URI) {
            throw RemoteAudioException.IncompatibleDelivery
        }
        if (!credentialsAreComplete(config, secrets)) throw RemoteAudioException.MissingCredentials
        throw RemoteAudioException.InvalidConfiguration
    }

    /**
     * Cleans a published object without replacing the recognition result.
     * A failed/cancelled recognition passes `force = true`; a successful one
     * follows the store's `deleteAfterRecognition` setting.
     */
    fun cleanup(published: PublishedRemoteAudio, force: Boolean) {
        cleanupBestEffort(published.cleanup, force)
    }

    private fun cleanupBestEffort(cleanup: RemoteCleanup, force: Boolean) {
        if (!force && !cleanup.deleteAfterRecognition) return
        // A job cancellation must never prevent cleanup of a possibly exposed
        // object, so cleanup intentionally uses a fresh uncancelled token.
        try {
            val status = executeStatus(cleanup.deleteRequest(clock), AdvancedCancellationToken.none())
            if (!status.isSuccessfulOrNotFound()) throw RemoteAudioException.CleanupFailed
        } catch (_: Exception) {
            // Cleanup is best effort and must not replace the transcription
            // result, the original upload failure, or a cancellation outcome.
        }
    }

    private fun publishWebDav(
        settings: AdvancedRemoteAudioConfig.WebDav,
        secrets: Map<String, String>,
        audio: PreparedAudioUpload,
        delivery: AudioDeliveryType,
        cancellation: AdvancedCancellationToken,
    ): PublishedRemoteAudio {
        if (delivery != AudioDeliveryType.PUBLIC_HTTPS_URL) {
            throw RemoteAudioException.IncompatibleDelivery
        }
        val username = requiredSecret(secrets, settings.usernameSecretId)
        val password = requiredSecret(secrets, settings.passwordSecretId)
        val uploadBase = parseHttpUrl(settings.uploadBaseUrl)
        val publicBase = parseHttpsUrl(settings.publicDownloadBaseUrl)
        val key = objectKey(settings.remotePathPrefix, audio.filename)
        val uploadUrl = appendKey(uploadBase, key)
        val publicUrl = appendKey(publicBase, key)
        val authorization = basicAuthorization(username, password)
        val cleanup = WebDavCleanup(
            uploadUrl = uploadUrl.toString(),
            authorization = authorization,
            deleteAfterRecognition = settings.deleteAfterRecognition,
        )
        val request = HttpRequestSpec(
            method = AdvancedHttpMethod.PUT,
            url = uploadUrl.toString(),
            headers = audioHeaders(audio, authorization),
            body = HttpBodySpec.RawAudio(audio),
        )
        uploadThenPublish(request, cleanup, cancellation)
        return PublishedRemoteAudio(RemoteAudioReference.PublicHttpsUrl(publicUrl.toString()), cleanup)
    }

    private fun publishS3(
        settings: AdvancedRemoteAudioConfig.S3Compatible,
        secrets: Map<String, String>,
        audio: PreparedAudioUpload,
        delivery: AudioDeliveryType,
        cancellation: AdvancedCancellationToken,
    ): PublishedRemoteAudio {
        val endpoint = parseHttpUrl(settings.endpoint)
        val bucket = requiredSetting(settings.bucket)
        val region = requiredSetting(settings.region)
        val accessKey = requiredSecret(secrets, settings.accessKeySecretId)
        val secretKey = requiredSecret(secrets, settings.secretKeySecretId)
        val key = objectKey(settings.prefix, audio.filename)
        val objectUrl = s3ObjectUrl(endpoint, bucket, key)
        val reference = when (delivery) {
            AudioDeliveryType.PUBLIC_HTTPS_URL -> {
                val url = if (settings.presigned) {
                    if (!objectUrl.isHttps) throw RemoteAudioException.InvalidConfiguration
                    presignS3Get(objectUrl, region, accessKey, secretKey, clock)
                } else {
                    appendKey(parseHttpsUrl(settings.publicUrlBase.orEmpty()), key).toString()
                }
                RemoteAudioReference.PublicHttpsUrl(url)
            }

            AudioDeliveryType.CLOUD_URI -> RemoteAudioReference.S3Uri("s3://$bucket/$key")
            else -> throw RemoteAudioException.IncompatibleDelivery
        }
        val cleanup = S3Cleanup(
            endpoint = endpoint,
            region = region,
            bucket = bucket,
            key = key,
            accessKey = accessKey,
            secretKey = secretKey,
            deleteAfterRecognition = settings.deleteAfterRecognition,
        )
        val request = HttpRequestSpec(
            method = AdvancedHttpMethod.PUT,
            url = objectUrl.toString(),
            headers = audioHeaders(audio),
            body = HttpBodySpec.RawAudio(audio),
            signer = AwsSigV4Signer(
                region = region,
                service = S3_SERVICE,
                accessKey = accessKey,
                secretKey = secretKey,
            ),
            remoteStorageSigningPayload = RemoteStorageSigningPayload.UnsignedPayload,
        )
        uploadThenPublish(request, cleanup, cancellation)
        return PublishedRemoteAudio(reference, cleanup)
    }

    private fun publishAliyunOss(
        settings: AdvancedRemoteAudioConfig.AliyunOss,
        secrets: Map<String, String>,
        audio: PreparedAudioUpload,
        delivery: AudioDeliveryType,
        cancellation: AdvancedCancellationToken,
    ): PublishedRemoteAudio {
        val endpoint = parseHttpUrl(settings.endpoint)
        val bucket = requiredSetting(settings.bucket)
        val accessKey = requiredSecret(secrets, settings.accessKeySecretId)
        val secretKey = requiredSecret(secrets, settings.secretKeySecretId)
        val key = objectKey(settings.prefix, audio.filename)
        val objectUrl = ossObjectUrl(endpoint, bucket, key)
        val reference = when (delivery) {
            AudioDeliveryType.PUBLIC_HTTPS_URL -> {
                val url = if (settings.presigned) {
                    if (!objectUrl.isHttps) throw RemoteAudioException.InvalidConfiguration
                    presignOssGet(objectUrl, bucket, key, accessKey, secretKey, clock)
                } else {
                    appendKey(parseHttpsUrl(settings.publicUrlBase.orEmpty()), key).toString()
                }
                RemoteAudioReference.PublicHttpsUrl(url)
            }

            AudioDeliveryType.CLOUD_URI -> RemoteAudioReference.OssUri("oss://$bucket/$key")
            else -> throw RemoteAudioException.IncompatibleDelivery
        }
        val now = clock.instant()
        val date = HTTP_DATE.format(now)
        val authorization = ossAuthorization(
            method = AdvancedHttpMethod.PUT,
            contentType = audio.mimeType,
            date = date,
            bucket = bucket,
            key = key,
            accessKey = accessKey,
            secretKey = secretKey,
        )
        val cleanup = AliyunOssCleanup(
            endpoint = endpoint,
            bucket = bucket,
            key = key,
            accessKey = accessKey,
            secretKey = secretKey,
            deleteAfterRecognition = settings.deleteAfterRecognition,
        )
        val request = HttpRequestSpec(
            method = AdvancedHttpMethod.PUT,
            url = objectUrl.toString(),
            headers = audioHeaders(audio) + listOf(
                HttpHeader("Date", date),
                HttpHeader("Authorization", authorization),
            ),
            body = HttpBodySpec.RawAudio(audio),
        )
        uploadThenPublish(request, cleanup, cancellation)
        return PublishedRemoteAudio(reference, cleanup)
    }

    private fun uploadThenPublish(
        request: HttpRequestSpec,
        cleanup: RemoteCleanup,
        cancellation: AdvancedCancellationToken,
    ) {
        try {
            val status = executeStatus(request, cancellation)
            if (!status.isSuccessful()) throw RemoteAudioException.UploadFailed(status)
            cancellation.throwIfCancelled()
        } catch (error: RemoteAudioException) {
            forceCleanupAfterUploadFailure(cleanup)
            throw error
        } catch (error: HttpTransportException) {
            forceCleanupAfterUploadFailure(cleanup)
            throw mapTransportFailure(error)
        } catch (_: IllegalArgumentException) {
            forceCleanupAfterUploadFailure(cleanup)
            throw RemoteAudioException.InvalidConfiguration
        } catch (_: Exception) {
            forceCleanupAfterUploadFailure(cleanup)
            throw RemoteAudioException.RequestFailed
        }
    }

    private fun forceCleanupAfterUploadFailure(cleanup: RemoteCleanup) {
        // Preserve the upload/cancellation outcome even if cleanup itself
        // fails.  This mirrors the completed-workflow cleanup contract.
        cleanupBestEffort(cleanup, force = true)
    }

    private fun executeStatus(
        request: HttpRequestSpec,
        cancellation: AdvancedCancellationToken,
    ): Int = transport.execute(request, cancellation).use { response -> response.statusCode }

    private fun mapTransportFailure(error: HttpTransportException): RemoteAudioException = when (error) {
        is HttpTransportException.Cancelled -> RemoteAudioException.Cancelled
        else -> RemoteAudioException.RequestFailed
    }

    private fun ensureAudioAvailable(audio: PreparedAudioUpload) {
        if (!audio.file.isFile || !audio.file.canRead() || audio.sizeBytes < 0L ||
            audio.file.length() != audio.sizeBytes
        ) {
            throw RemoteAudioException.AudioUnavailable
        }
    }

    private fun requiredSecret(secrets: Map<String, String>, id: String): String =
        secrets[id]?.takeIf { it.isNotBlank() } ?: throw RemoteAudioException.MissingCredentials

    private fun requiredSetting(value: String): String =
        value.takeIf { it.isNotBlank() } ?: throw RemoteAudioException.InvalidConfiguration

    private fun parseHttpUrl(value: String): HttpUrl {
        val trimmed = value.trim()
        if (hasUserInfo(trimmed)) throw RemoteAudioException.InvalidConfiguration
        val url = trimmed.toHttpUrlOrNull() ?: throw RemoteAudioException.InvalidConfiguration
        if ((url.scheme != "http" && url.scheme != "https") || url.host.isBlank()) {
            throw RemoteAudioException.InvalidConfiguration
        }
        return url
    }

    private fun parseHttpsUrl(value: String): HttpUrl {
        val url = parseHttpUrl(value)
        if (!url.isHttps) throw RemoteAudioException.InvalidConfiguration
        return url
    }

    private fun audioHeaders(audio: PreparedAudioUpload, authorization: String? = null): List<HttpHeader> = buildList {
        add(HttpHeader("Content-Type", audio.mimeType))
        // Supplying this before S3 signs the request ensures the exact streamed
        // size is included in its canonical headers.
        add(HttpHeader("Content-Length", audio.sizeBytes.toString()))
        authorization?.let { add(HttpHeader("Authorization", it)) }
    }

    companion object {
        private const val S3_SERVICE = "s3"

        /**
         * Safe, connection-free validation for remote storage.  The result
         * deliberately names settings fields but never copies endpoint text,
         * authorization headers, secret values, or presigned query values.
         *
         * Remote settings are inert for all non-remote audio deliveries, just
         * as they are on Windows.
         */
        fun validate(
            config: AdvancedRemoteAudioConfig,
            delivery: AudioDeliveryType,
            secrets: Map<String, String>,
        ): List<RemoteAudioValidationError> {
            val errors = validateStoredConfiguration(config).toMutableList()
            if (delivery != AudioDeliveryType.PUBLIC_HTTPS_URL && delivery != AudioDeliveryType.CLOUD_URI) {
                return errors
            }
            when (config) {
                AdvancedRemoteAudioConfig.None -> errors += remoteValidationError(
                    "remote_audio",
                    "remote hosting is required by the selected audio delivery",
                )

                is AdvancedRemoteAudioConfig.WebDav -> {
                    if (delivery == AudioDeliveryType.CLOUD_URI) {
                        errors += remoteValidationError(
                            "remote_audio",
                            "WebDAV provides public HTTPS URLs, not cloud URIs",
                        )
                    }
                    if (!isAbsoluteHttpUrl(config.uploadBaseUrl)) {
                        errors += remoteValidationError("remote_audio.upload_base_url", "must be an absolute HTTP(S) URL")
                    }
                    if (!isAbsoluteHttpsUrl(config.publicDownloadBaseUrl)) {
                        errors += remoteValidationError(
                            "remote_audio.public_download_base_url",
                            "must be an absolute HTTPS URL",
                        )
                    }
                    if (!hasSecret(secrets, config.usernameSecretId)) {
                        errors += remoteValidationError("remote_audio.username", "credential is missing")
                    }
                    if (!hasSecret(secrets, config.passwordSecretId)) {
                        errors += remoteValidationError("remote_audio.password", "credential is missing")
                    }
                    validatePrefix(config.remotePathPrefix, "remote_audio.remote_path_prefix", errors)
                }

                is AdvancedRemoteAudioConfig.S3Compatible -> {
                    if (!isAbsoluteHttpUrl(config.endpoint)) {
                        errors += remoteValidationError("remote_audio.endpoint", "must be an absolute HTTP(S) URL")
                    }
                    if (config.region.isBlank()) {
                        errors += remoteValidationError("remote_audio.region", "must not be empty")
                    }
                    if (config.bucket.isBlank()) {
                        errors += remoteValidationError("remote_audio.bucket", "must not be empty")
                    }
                    if (!hasSecret(secrets, config.accessKeySecretId)) {
                        errors += remoteValidationError("remote_audio.access_key", "credential is missing")
                    }
                    if (!hasSecret(secrets, config.secretKeySecretId)) {
                        errors += remoteValidationError("remote_audio.secret_key", "credential is missing")
                    }
                    if (delivery == AudioDeliveryType.PUBLIC_HTTPS_URL) {
                        validatePublicAccess(
                            presigned = config.presigned,
                            endpoint = config.endpoint,
                            publicBase = config.publicUrlBase,
                            errors = errors,
                        )
                    }
                    validatePrefix(config.prefix, "remote_audio.prefix", errors)
                }

                is AdvancedRemoteAudioConfig.AliyunOss -> {
                    if (!isAbsoluteHttpUrl(config.endpoint)) {
                        errors += remoteValidationError("remote_audio.endpoint", "must be an absolute HTTP(S) URL")
                    }
                    if (config.bucket.isBlank()) {
                        errors += remoteValidationError("remote_audio.bucket", "must not be empty")
                    }
                    if (!hasSecret(secrets, config.accessKeySecretId)) {
                        errors += remoteValidationError("remote_audio.access_key", "credential is missing")
                    }
                    if (!hasSecret(secrets, config.secretKeySecretId)) {
                        errors += remoteValidationError("remote_audio.secret_key", "credential is missing")
                    }
                    if (delivery == AudioDeliveryType.PUBLIC_HTTPS_URL) {
                        validatePublicAccess(
                            presigned = config.presigned,
                            endpoint = config.endpoint,
                            publicBase = config.publicUrlBase,
                            errors = errors,
                        )
                    }
                    validatePrefix(config.prefix, "remote_audio.prefix", errors)
                }
            }
            return errors
        }

        /**
         * URL user-info is never a valid remote-storage setting.  Unlike
         * ordinary completeness checks, this also applies to disabled drafts
         * so a password cannot be retained in preferences or configuration
         * exports while a draft is inactive.
         */
        fun validateStoredConfiguration(config: AdvancedRemoteAudioConfig): List<RemoteAudioValidationError> = buildList {
            fun rejectUserInfo(value: String?, path: String) {
                if (!value.isNullOrBlank() && hasUserInfo(value.trim())) {
                    add(remoteValidationError(path, "must not contain URL user-info; store credentials in secrets"))
                }
            }
            when (config) {
                AdvancedRemoteAudioConfig.None -> Unit
                is AdvancedRemoteAudioConfig.WebDav -> {
                    rejectUserInfo(config.uploadBaseUrl, "remote_audio.upload_base_url")
                    rejectUserInfo(config.publicDownloadBaseUrl, "remote_audio.public_download_base_url")
                }
                is AdvancedRemoteAudioConfig.S3Compatible -> {
                    rejectUserInfo(config.endpoint, "remote_audio.endpoint")
                    rejectUserInfo(config.publicUrlBase, "remote_audio.public_url_base")
                }
                is AdvancedRemoteAudioConfig.AliyunOss -> {
                    rejectUserInfo(config.endpoint, "remote_audio.endpoint")
                    rejectUserInfo(config.publicUrlBase, "remote_audio.public_url_base")
                }
            }
        }

        private fun validatePublicAccess(
            presigned: Boolean,
            endpoint: String,
            publicBase: String?,
            errors: MutableList<RemoteAudioValidationError>,
        ) {
            if (presigned) {
                if (!isAbsoluteHttpsUrl(endpoint)) {
                    errors += remoteValidationError(
                        "remote_audio.endpoint",
                        "must use HTTPS when generating a public presigned URL",
                    )
                }
            } else if (!isAbsoluteHttpsUrl(publicBase.orEmpty())) {
                errors += remoteValidationError(
                    "remote_audio.public_url_base",
                    "must be an absolute HTTPS URL when presigning is disabled",
                )
            }
        }

        private fun validatePrefix(
            prefix: String,
            path: String,
            errors: MutableList<RemoteAudioValidationError>,
        ) {
            if (prefix.toByteArray(Charsets.UTF_8).size > MAX_PREFIX_BYTES || prefix.contains('\u0000')) {
                errors += remoteValidationError(path, "must contain at most $MAX_PREFIX_BYTES bytes and no NUL bytes")
            }
            if (prefix.split('/').any { it == ".." }) {
                errors += remoteValidationError(path, "must not contain parent path segments")
            }
        }

        private fun isAbsoluteHttpUrl(value: String): Boolean = value.toHttpUrlOrNull()
            ?.let { (it.scheme == "http" || it.scheme == "https") && it.host.isNotBlank() && !hasUserInfo(value) }
            ?: false

        private fun isAbsoluteHttpsUrl(value: String): Boolean = value.toHttpUrlOrNull()
            ?.let { it.isHttps && it.host.isNotBlank() && !hasUserInfo(value) }
            ?: false

        /** URI preserves the presence of even empty `userinfo@` authority syntax. */
        private fun hasUserInfo(value: String): Boolean {
            runCatching { URI(value).rawUserInfo }.getOrNull()?.let { return true }
            // Keep disabled drafts editable even when their URL is incomplete,
            // while still catching an authority that visibly embeds user-info.
            val schemeEnd = value.indexOf("://")
            if (schemeEnd < 0) return false
            val authority = value.substring(schemeEnd + 3).takeWhile { it != '/' && it != '?' && it != '#' }
            return '@' in authority
        }

        private fun hasSecret(secrets: Map<String, String>, id: String): Boolean =
            id.isNotBlank() && !secrets[id].isNullOrBlank()

        private fun credentialsAreComplete(
            config: AdvancedRemoteAudioConfig,
            secrets: Map<String, String>,
        ): Boolean = when (config) {
            AdvancedRemoteAudioConfig.None -> true
            is AdvancedRemoteAudioConfig.WebDav ->
                hasSecret(secrets, config.usernameSecretId) && hasSecret(secrets, config.passwordSecretId)

            is AdvancedRemoteAudioConfig.S3Compatible ->
                hasSecret(secrets, config.accessKeySecretId) && hasSecret(secrets, config.secretKeySecretId)

            is AdvancedRemoteAudioConfig.AliyunOss ->
                hasSecret(secrets, config.accessKeySecretId) && hasSecret(secrets, config.secretKeySecretId)
        }

        private fun remoteValidationError(path: String, message: String): RemoteAudioValidationError =
            RemoteAudioValidationError("ADVANCED_AUDIO_API.$path", message)
    }
}

/** A safe, UI-ready remote hosting validation diagnostic. */
data class RemoteAudioValidationError(
    val path: String,
    val message: String,
) {
    override fun toString(): String = "$path: $message"
}

/**
 * Connection-free validation entry point for the executor and settings UI.
 * It returns only safe field-level diagnostics and never echoes an endpoint,
 * URL query, authorization header, or secret value.
 */
object RemoteAudioConfigValidator {
    fun validate(
        config: AdvancedRemoteAudioConfig,
        delivery: AudioDeliveryType,
        secrets: Map<String, String>,
    ): List<RemoteAudioValidationError> = RemoteAudioPublisher.validate(config, delivery, secrets)

    /** Safety checks that apply even to disabled or otherwise incomplete drafts. */
    fun validateStoredConfiguration(config: AdvancedRemoteAudioConfig): List<RemoteAudioValidationError> =
        RemoteAudioPublisher.validateStoredConfiguration(config)
}

/**
 * A remote result intentionally redacts its URL in [toString].  The URL is
 * available only through the matching accessor needed by template rendering.
 */
sealed class RemoteAudioReference private constructor() {
    abstract fun publicHttpsUrl(): String?
    abstract fun cloudUri(): String?

    final override fun toString(): String = "RemoteAudioReference(<redacted>)"

    class PublicHttpsUrl internal constructor(private val value: String) : RemoteAudioReference() {
        override fun publicHttpsUrl(): String = value
        override fun cloudUri(): String? = null
    }

    class S3Uri internal constructor(private val value: String) : RemoteAudioReference() {
        override fun publicHttpsUrl(): String? = null
        override fun cloudUri(): String = value
    }

    class OssUri internal constructor(private val value: String) : RemoteAudioReference() {
        override fun publicHttpsUrl(): String? = null
        override fun cloudUri(): String = value
    }
}

/** A published reference plus its private, provider-specific cleanup handle. */
class PublishedRemoteAudio internal constructor(
    val reference: RemoteAudioReference,
    internal val cleanup: RemoteCleanup,
) {
    override fun toString(): String = "PublishedRemoteAudio(<redacted>)"
}

/** All public failure text is intentionally safe for UI and diagnostic output. */
sealed class RemoteAudioException(message: String) : IOException(message) {
    data object NotConfigured : RemoteAudioException("Remote audio hosting is not configured")
    data object IncompatibleDelivery : RemoteAudioException(
        "Remote audio hosting does not support the workflow audio delivery type",
    )

    data object InvalidConfiguration : RemoteAudioException("Remote audio hosting configuration is invalid")
    data object MissingCredentials : RemoteAudioException("Remote audio hosting credentials are incomplete")
    data object AudioUnavailable : RemoteAudioException("Audio file is unavailable for remote upload")
    data object Cancelled : RemoteAudioException("Remote audio upload was cancelled")
    data object RequestFailed : RemoteAudioException("Remote audio request failed")
    data object CleanupFailed : RemoteAudioException("Remote audio cleanup failed")

    class UploadFailed(status: Int) : RemoteAudioException("Remote audio upload returned HTTP $status")
}

/** Private cleanup metadata.  Its toString must never disclose URLs or credentials. */
internal sealed class RemoteCleanup(
    val deleteAfterRecognition: Boolean,
) {
    abstract fun deleteRequest(clock: Clock): HttpRequestSpec

    final override fun toString(): String = "RemoteCleanup(<redacted>)"
}

private class WebDavCleanup(
    private val uploadUrl: String,
    private val authorization: String,
    deleteAfterRecognition: Boolean,
) : RemoteCleanup(deleteAfterRecognition) {
    override fun deleteRequest(clock: Clock): HttpRequestSpec = HttpRequestSpec(
        method = AdvancedHttpMethod.DELETE,
        url = uploadUrl,
        headers = listOf(HttpHeader("Authorization", authorization)),
    )
}

private class S3Cleanup(
    private val endpoint: HttpUrl,
    private val region: String,
    private val bucket: String,
    private val key: String,
    private val accessKey: String,
    private val secretKey: String,
    deleteAfterRecognition: Boolean,
) : RemoteCleanup(deleteAfterRecognition) {
    override fun deleteRequest(clock: Clock): HttpRequestSpec = HttpRequestSpec(
        method = AdvancedHttpMethod.DELETE,
        url = s3ObjectUrl(endpoint, bucket, key).toString(),
        signer = AwsSigV4Signer(
            region = region,
            service = S3_SERVICE,
            accessKey = accessKey,
            secretKey = secretKey,
        ),
        remoteStorageSigningPayload = RemoteStorageSigningPayload.UnsignedPayload,
    )

    private companion object {
        const val S3_SERVICE = "s3"
    }
}

private class AliyunOssCleanup(
    private val endpoint: HttpUrl,
    private val bucket: String,
    private val key: String,
    private val accessKey: String,
    private val secretKey: String,
    deleteAfterRecognition: Boolean,
) : RemoteCleanup(deleteAfterRecognition) {
    override fun deleteRequest(clock: Clock): HttpRequestSpec {
        val date = HTTP_DATE.format(clock.instant())
        return HttpRequestSpec(
            method = AdvancedHttpMethod.DELETE,
            url = ossObjectUrl(endpoint, bucket, key).toString(),
            headers = listOf(
                HttpHeader("Date", date),
                HttpHeader(
                    "Authorization",
                    ossAuthorization(
                        method = AdvancedHttpMethod.DELETE,
                        contentType = "",
                        date = date,
                        bucket = bucket,
                        key = key,
                        accessKey = accessKey,
                        secretKey = secretKey,
                    ),
                ),
            ),
        )
    }
}

private fun objectKey(prefix: String, filename: String): String {
    val segments = prefix
        .split('/')
        .filter { it.isNotEmpty() && it != "." && it != ".." }
        .map(::encodePathSegment)
        .toMutableList()
    val extension = filename
        .substringAfterLast('.', missingDelimiterValue = "")
        .takeIf { value -> value.isNotEmpty() && value.length <= MAX_EXTENSION_LENGTH && value.all(Char::isLetterOrDigit) }
        ?.lowercase(Locale.US)
        ?.let { ".$it" }
        .orEmpty()
    segments += UUID.randomUUID().toString().replace("-", "") + extension
    return segments.joinToString("/")
}

private fun encodePathSegment(value: String): String = buildString(value.length) {
    value.toByteArray(Charsets.UTF_8).forEach { signed ->
        val byte = signed.toInt() and 0xff
        if (
            byte in 'a'.code..'z'.code ||
            byte in 'A'.code..'Z'.code ||
            byte in '0'.code..'9'.code ||
            byte == '-'.code || byte == '_'.code || byte == '.'.code || byte == '~'.code
        ) {
            append(byte.toChar())
        } else {
            append('%')
            append(UPPER_HEX[byte ushr 4])
            append(UPPER_HEX[byte and 0x0f])
        }
    }
}

private fun appendKey(base: HttpUrl, key: String): HttpUrl {
    val existing = base.encodedPath.trimEnd('/')
    val path = if (existing.isEmpty() || existing == "/") "/$key" else "$existing/$key"
    return base.newBuilder().encodedPath(path).build()
}

private fun s3ObjectUrl(endpoint: HttpUrl, bucket: String, key: String): HttpUrl =
    appendKey(appendKey(endpoint, encodePathSegment(bucket)), key)

private fun ossObjectUrl(endpoint: HttpUrl, bucket: String, key: String): HttpUrl {
    val host = endpoint.host
    if (host.equals("localhost", ignoreCase = true) || isIpLiteral(host)) {
        return appendKey(appendKey(endpoint, encodePathSegment(bucket)), key)
    }
    val lowerHost = host.lowercase(Locale.US)
    val lowerBucket = bucket.lowercase(Locale.US)
    val withBucket = if (lowerHost == lowerBucket || lowerHost.startsWith("$lowerBucket.")) {
        endpoint
    } else {
        endpoint.newBuilder().host("$bucket.$host").build()
    }
    return appendKey(withBucket, key)
}

private fun isIpLiteral(host: String): Boolean {
    if (host.contains(':')) return true
    val segments = host.split('.')
    return segments.size == 4 && segments.all { part ->
        part.toIntOrNull()?.let { it in 0..255 } == true
    }
}

private fun basicAuthorization(username: String, password: String): String {
    val payload = "$username:$password".toByteArray(Charsets.UTF_8)
    return "Basic ${Base64.getEncoder().encodeToString(payload)}"
}

private fun presignS3Get(
    objectUrl: HttpUrl,
    region: String,
    accessKey: String,
    secretKey: String,
    clock: Clock,
): String {
    val now = clock.instant()
    val timestamp = AWS_TIMESTAMP.format(now)
    val date = AWS_DATE.format(now)
    val scope = "$date/$region/s3/aws4_request"
    val parameters = TreeMap<String, String>()
    repeat(objectUrl.querySize) { index ->
        parameters[objectUrl.queryParameterName(index)] = objectUrl.queryParameterValue(index).orEmpty()
    }
    parameters["X-Amz-Algorithm"] = "AWS4-HMAC-SHA256"
    parameters["X-Amz-Credential"] = "$accessKey/$scope"
    parameters["X-Amz-Date"] = timestamp
    parameters["X-Amz-Expires"] = S3_PRESIGN_EXPIRY_SECONDS.toString()
    parameters["X-Amz-SignedHeaders"] = "host"
    val canonicalQuery = canonicalS3Pairs(parameters)
    val canonicalRequest = buildString {
        append("GET\n")
        append(canonicalS3Path(objectUrl))
        append('\n')
        append(canonicalQuery)
        append("\nhost:")
        append(hostHeader(objectUrl))
        append("\n\nhost\nUNSIGNED-PAYLOAD")
    }
    val stringToSign = "AWS4-HMAC-SHA256\n$timestamp\n$scope\n${sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))}"
    parameters["X-Amz-Signature"] = hexLower(s3SigningKey(secretKey, date, region, stringToSign))
    return objectUrl.newBuilder().encodedQuery(canonicalS3Pairs(parameters)).build().toString()
}

private fun s3SigningKey(secretKey: String, date: String, region: String, stringToSign: String): ByteArray {
    val dateKey = hmacSha256("AWS4$secretKey".toByteArray(Charsets.UTF_8), date)
    val regionKey = hmacSha256(dateKey, region)
    val serviceKey = hmacSha256(regionKey, "s3")
    val signingKey = hmacSha256(serviceKey, "aws4_request")
    return hmacSha256(signingKey, stringToSign)
}

private fun canonicalS3Path(url: HttpUrl): String = url.encodedPath
    .split('/')
    .joinToString("/", transform = ::encodeS3PathSegment)
    .ifEmpty { "/" }

private fun encodeS3PathSegment(value: String): String = buildString(value.length) {
    var index = 0
    while (index < value.length) {
        val valueAtIndex = value[index]
        if (
            valueAtIndex.isAsciiLetterOrDigit() ||
            valueAtIndex == '-' || valueAtIndex == '_' || valueAtIndex == '.' || valueAtIndex == '~'
        ) {
            append(valueAtIndex)
            index += 1
        } else if (
            valueAtIndex == '%' && index + 2 < value.length &&
            value[index + 1].digitToIntOrNull(16) != null && value[index + 2].digitToIntOrNull(16) != null
        ) {
            append('%')
            append(value[index + 1].uppercaseChar())
            append(value[index + 2].uppercaseChar())
            index += 3
        } else {
            valueAtIndex.toString().toByteArray(Charsets.UTF_8).forEach { signed ->
                val byte = signed.toInt() and 0xff
                append('%')
                append(UPPER_HEX[byte ushr 4])
                append(UPPER_HEX[byte and 0x0f])
            }
            index += 1
        }
    }
}

private fun canonicalS3Pairs(values: Map<String, String>): String = values.entries
    .joinToString("&") { (name, value) -> "${encodePathSegment(name)}=${encodePathSegment(value)}" }

private fun hostHeader(url: HttpUrl): String {
    val host = if (url.host.contains(':')) "[${url.host}]" else url.host
    val defaultPort = if (url.isHttps) 443 else 80
    return if (url.port == defaultPort) host else "$host:${url.port}"
}

private fun presignOssGet(
    objectUrl: HttpUrl,
    bucket: String,
    key: String,
    accessKey: String,
    secretKey: String,
    clock: Clock,
): String {
    val expires = clock.instant().epochSecond + OSS_PRESIGN_EXPIRY_SECONDS
    val stringToSign = "GET\n\n\n$expires\n/${bucket}/${key}"
    val signature = hmacSha1Base64(secretKey, stringToSign)
    return objectUrl.newBuilder()
        .addQueryParameter("OSSAccessKeyId", accessKey)
        .addQueryParameter("Expires", expires.toString())
        .addQueryParameter("Signature", signature)
        .build()
        .toString()
}

private fun ossAuthorization(
    method: AdvancedHttpMethod,
    contentType: String,
    date: String,
    bucket: String,
    key: String,
    accessKey: String,
    secretKey: String,
): String {
    val stringToSign = "${method.wireName}\n\n$contentType\n$date\n/${bucket}/${key}"
    return "OSS $accessKey:${hmacSha1Base64(secretKey, stringToSign)}"
}

private fun hmacSha1Base64(secret: String, value: String): String = try {
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
    Base64.getEncoder().encodeToString(mac.doFinal(value.toByteArray(Charsets.UTF_8)))
} catch (_: Exception) {
    throw RemoteAudioException.InvalidConfiguration
}

private fun Int.isSuccessful(): Boolean = this in 200..299

private fun Int.isSuccessfulOrNotFound(): Boolean = isSuccessful() || this == 404

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private const val MAX_EXTENSION_LENGTH = 16
private const val MAX_PREFIX_BYTES = 1_024
private const val UPPER_HEX = "0123456789ABCDEF"
private const val S3_PRESIGN_EXPIRY_SECONDS = 900L
private const val OSS_PRESIGN_EXPIRY_SECONDS = 900L
private val AWS_TIMESTAMP: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyyMMdd'T'HHmmss'Z'")
    .withZone(ZoneOffset.UTC)
private val AWS_DATE: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyyMMdd")
    .withZone(ZoneOffset.UTC)
private val HTTP_DATE: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)
