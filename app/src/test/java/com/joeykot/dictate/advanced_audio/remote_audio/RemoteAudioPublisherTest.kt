package com.joeykot.dictate.advanced_audio.remote_audio

import com.joeykot.dictate.advanced_audio.AudioDeliveryType
import com.joeykot.dictate.advanced_audio.auth.AwsSigV4Signer
import com.joeykot.dictate.advanced_audio.http.AdvancedCancellationToken
import com.joeykot.dictate.advanced_audio.http.AdvancedHttpMethod
import com.joeykot.dictate.advanced_audio.http.AdvancedHttpTransport
import com.joeykot.dictate.advanced_audio.http.HttpBodySpec
import com.joeykot.dictate.advanced_audio.http.HttpHeader
import com.joeykot.dictate.advanced_audio.http.HttpRequestSpec
import com.joeykot.dictate.advanced_audio.http.HttpResponse
import com.joeykot.dictate.advanced_audio.http.HttpTransportException
import com.joeykot.dictate.advanced_audio.http.PreparedAudioUpload
import com.joeykot.dictate.advanced_audio.http.RemoteStorageSigningPayload
import com.joeykot.dictate.model.AdvancedRemoteAudioConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.ArrayDeque

class RemoteAudioPublisherTest {
    @Test
    fun urlUserInfoIsRejectedForEveryRemoteStorageUrlEvenWhenTheWorkflowDoesNotUseRemoteAudio() {
        val unsafe = "https://alice:password@example.test/storage"
        val configurations = listOf(
            AdvancedRemoteAudioConfig.WebDav(
                uploadBaseUrl = unsafe,
                publicDownloadBaseUrl = unsafe,
            ),
            AdvancedRemoteAudioConfig.S3Compatible(
                endpoint = unsafe,
                publicUrlBase = unsafe,
            ),
            AdvancedRemoteAudioConfig.AliyunOss(
                endpoint = unsafe,
                publicUrlBase = unsafe,
            ),
        )

        configurations.forEach { config ->
            val errors = RemoteAudioConfigValidator.validate(
                config = config,
                delivery = AudioDeliveryType.BASE64,
                secrets = emptyMap(),
            )
            assertTrue(errors.isNotEmpty())
            assertTrue(errors.all { it.message.contains("user-info") })
            assertFalse(errors.joinToString("\n").contains("alice:password"))
        }
    }

    @Test
    fun configurationValidationIsConnectionFreeAndNeverEchoesSensitiveValues() {
        val errors = RemoteAudioConfigValidator.validate(
            config = AdvancedRemoteAudioConfig.S3Compatible(
                endpoint = "http://storage.example.test/private?signature=private-token",
                region = "",
                bucket = "",
                accessKeySecretId = "access",
                secretKeySecretId = "secret",
                presigned = false,
                publicUrlBase = null,
                prefix = "../private",
            ),
            delivery = AudioDeliveryType.PUBLIC_HTTPS_URL,
            secrets = mapOf("access" to "access-value", "secret" to "secret-value"),
        )

        assertTrue(errors.any { it.path.endsWith("remote_audio.region") })
        assertTrue(errors.any { it.path.endsWith("remote_audio.bucket") })
        assertTrue(errors.any { it.path.endsWith("remote_audio.public_url_base") })
        assertTrue(errors.any { it.path.endsWith("remote_audio.prefix") })
        val text = errors.joinToString("\n")
        assertFalse(text.contains("storage.example.test"))
        assertFalse(text.contains("private-token"))
        assertFalse(text.contains("access-value"))
        assertFalse(text.contains("secret-value"))
    }

    @Test
    fun webDavStreamsRandomizedObjectAndCleansAfterSuccessfulRecognition() {
        temporaryAudio("recording.WAV").use { audio ->
            val transport = RecordingTransport(statuses = listOf(201, 204))
            val publisher = RemoteAudioPublisher(transport)
            val published = publisher.publish(
                config = AdvancedRemoteAudioConfig.WebDav(
                    uploadBaseUrl = "http://upload.example.test/dav",
                    usernameSecretId = "dav-user",
                    passwordSecretId = "dav-password",
                    remotePathPrefix = "voice files/session",
                    publicDownloadBaseUrl = "https://cdn.example.test/audio",
                ),
                secrets = mapOf("dav-user" to "user", "dav-password" to "pass"),
                audio = audio.upload,
                delivery = AudioDeliveryType.PUBLIC_HTTPS_URL,
            )

            val publicUrl = requireNotNull(published.reference.publicHttpsUrl())
            assertTrue(publicUrl.matches(Regex("https://cdn\\.example\\.test/audio/voice%20files/session/[0-9a-f]{32}\\.wav")))
            assertNull(published.reference.cloudUri())
            assertFalse(publicUrl.contains("recording"))
            assertEquals("RemoteAudioReference(<redacted>)", published.reference.toString())
            assertFalse(published.toString().contains("cdn.example.test"))

            val upload = transport.requests.single()
            assertEquals(AdvancedHttpMethod.PUT, upload.method)
            assertTrue(upload.url.matches(Regex("http://upload\\.example\\.test/dav/voice%20files/session/[0-9a-f]{32}\\.wav")))
            assertTrue(upload.body is HttpBodySpec.RawAudio)
            assertEquals("Basic dXNlcjpwYXNz", upload.headerValue("Authorization"))
            assertEquals(audio.upload.sizeBytes.toString(), upload.headerValue("Content-Length"))

            publisher.cleanup(published, force = false)
            assertEquals(2, transport.requests.size)
            val delete = transport.requests.last()
            assertEquals(AdvancedHttpMethod.DELETE, delete.method)
            assertEquals(upload.url, delete.url)
            assertEquals("Basic dXNlcjpwYXNz", delete.headerValue("Authorization"))
        }
    }

    @Test
    fun webDavNeverConvertsItsHttpsReferenceToCloudUri() {
        temporaryAudio("recording.wav").use { audio ->
            val transport = RecordingTransport(statuses = emptyList())
            val failure = captureFailure {
                RemoteAudioPublisher(transport).publish(
                    config = AdvancedRemoteAudioConfig.WebDav(
                        uploadBaseUrl = "https://upload.example.test/dav",
                        usernameSecretId = "dav-user",
                        passwordSecretId = "dav-password",
                        publicDownloadBaseUrl = "https://cdn.example.test/audio",
                    ),
                    secrets = mapOf(
                        "dav-user" to "user",
                        "dav-password" to "pass",
                    ),
                    audio = audio.upload,
                    delivery = AudioDeliveryType.CLOUD_URI,
                )
            }
            assertTrue(failure is RemoteAudioException.IncompatibleDelivery)
            assertTrue(transport.requests.isEmpty())
        }
    }

    @Test
    fun s3CompatibleCloudUriUsesPathStyleAndUnsignedStreamingSignature() {
        temporaryAudio("spoken.ogg").use { audio ->
            val transport = RecordingTransport(statuses = listOf(200, 404))
            val publisher = RemoteAudioPublisher(transport)
            val published = publisher.publish(
                config = AdvancedRemoteAudioConfig.S3Compatible(
                    endpoint = "http://127.0.0.1:9000/storage",
                    region = "us-east-1",
                    bucket = "audio",
                    accessKeySecretId = "s3-access",
                    secretKeySecretId = "s3-secret",
                    prefix = "day one/./batch",
                    deleteAfterRecognition = false,
                ),
                secrets = mapOf("s3-access" to "access", "s3-secret" to "secret"),
                audio = audio.upload,
                delivery = AudioDeliveryType.CLOUD_URI,
            )

            val cloudUri = requireNotNull(published.reference.cloudUri())
            assertTrue(cloudUri.matches(Regex("s3://audio/day%20one/batch/[0-9a-f]{32}\\.ogg")))
            assertNull(published.reference.publicHttpsUrl())
            val upload = transport.requests.single()
            assertEquals(AdvancedHttpMethod.PUT, upload.method)
            assertTrue(upload.url.matches(Regex("http://127\\.0\\.0\\.1:9000/storage/audio/day%20one/batch/[0-9a-f]{32}\\.ogg")))
            assertTrue(upload.body is HttpBodySpec.RawAudio)
            assertTrue(upload.signer is AwsSigV4Signer)
            assertEquals(RemoteStorageSigningPayload.UnsignedPayload, upload.remoteStorageSigningPayload)
            assertEquals(audio.upload.sizeBytes.toString(), upload.headerValue("Content-Length"))

            // Successful recognition respects retention when force is false.
            publisher.cleanup(published, force = false)
            assertEquals(1, transport.requests.size)
            // Failed/cancelled recognition forces deletion even with retention enabled.
            publisher.cleanup(published, force = true)
            assertEquals(2, transport.requests.size)
            val delete = transport.requests.last()
            assertEquals(AdvancedHttpMethod.DELETE, delete.method)
            assertEquals(RemoteStorageSigningPayload.UnsignedPayload, delete.remoteStorageSigningPayload)
            assertTrue(delete.signer is AwsSigV4Signer)
        }
    }

    @Test
    fun ossUsesPathStyleForLocalEndpointAndSignsUpload() {
        temporaryAudio("spoken.mp3").use { audio ->
            val transport = RecordingTransport(statuses = listOf(200, 204))
            val published = RemoteAudioPublisher(
                transport = transport,
                clock = Clock.fixed(Instant.parse("2024-01-02T03:04:05Z"), ZoneOffset.UTC),
            ).publish(
                config = AdvancedRemoteAudioConfig.AliyunOss(
                    endpoint = "http://127.0.0.1:9000/oss",
                    bucket = "audio",
                    accessKeySecretId = "oss-access",
                    secretKeySecretId = "oss-secret",
                    prefix = "daily",
                ),
                secrets = mapOf("oss-access" to "access-id", "oss-secret" to "secret-key"),
                audio = audio.upload,
                delivery = AudioDeliveryType.CLOUD_URI,
            )
            val cloudUri = requireNotNull(published.reference.cloudUri())
            assertTrue(cloudUri.matches(Regex("oss://audio/daily/[0-9a-f]{32}\\.mp3")))
            val upload = transport.requests.single()
            assertTrue(upload.url.matches(Regex("http://127\\.0\\.0\\.1:9000/oss/audio/daily/[0-9a-f]{32}\\.mp3")))
            assertEquals("Tue, 2 Jan 2024 03:04:05 GMT", upload.headerValue("Date"))
            assertTrue(requireNotNull(upload.headerValue("Authorization")).startsWith("OSS access-id:"))
            assertTrue(upload.body is HttpBodySpec.RawAudio)
        }
    }

    @Test
    fun publicS3PresignUsesHttpsAndDoesNotRequireAPublicBase() {
        temporaryAudio("spoken.flac").use { audio ->
            val transport = RecordingTransport(statuses = listOf(200))
            val publisher = RemoteAudioPublisher(
                transport,
                Clock.fixed(Instant.parse("2024-01-02T03:04:05Z"), ZoneOffset.UTC),
            )
            val published = publisher.publish(
                config = AdvancedRemoteAudioConfig.S3Compatible(
                    endpoint = "https://storage.example.test/root",
                    region = "us-east-1",
                    bucket = "audio",
                    accessKeySecretId = "access-id",
                    secretKeySecretId = "secret-key",
                    prefix = "daily",
                    presigned = true,
                ),
                secrets = mapOf("access-id" to "ACCESS", "secret-key" to "SECRET"),
                audio = audio.upload,
                delivery = AudioDeliveryType.PUBLIC_HTTPS_URL,
            )

            val publicUrl = requireNotNull(published.reference.publicHttpsUrl())
            assertTrue(publicUrl.startsWith("https://storage.example.test/root/audio/daily/"))
            assertTrue(publicUrl.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"))
            assertTrue(publicUrl.contains("X-Amz-Expires=900"))
            assertTrue(publicUrl.contains("X-Amz-Signature="))
            assertFalse(publicUrl.contains("SECRET"))
        }
    }

    @Test
    fun failedUploadAndNetworkFailureDoNotLeakRemoteUrlOrCredentialsAndForceCleanup() {
        temporaryAudio("spoken.wav").use { audio ->
            val transport = RecordingTransport(
                failures = listOf(
                    HttpTransportException.NetworkFailure(
                        IOException("https://upload.example.test/?signature=private-token"),
                    ),
                    null,
                ),
            )
            val failure = captureFailure {
                RemoteAudioPublisher(transport).publish(
                    config = AdvancedRemoteAudioConfig.WebDav(
                        uploadBaseUrl = "https://upload.example.test/private?signature=private-token",
                        usernameSecretId = "username",
                        passwordSecretId = "password",
                        publicDownloadBaseUrl = "https://cdn.example.test/public",
                        deleteAfterRecognition = false,
                    ),
                    secrets = mapOf("username" to "alice", "password" to "very-secret"),
                    audio = audio.upload,
                    delivery = AudioDeliveryType.PUBLIC_HTTPS_URL,
                )
            }
            assertTrue(failure is RemoteAudioException.RequestFailed)
            val message = failure.toString()
            assertFalse(message.contains("upload.example.test"))
            assertFalse(message.contains("private-token"))
            assertFalse(message.contains("very-secret"))
            assertNull(failure.cause)
            assertEquals(2, transport.requests.size)
            assertEquals(AdvancedHttpMethod.PUT, transport.requests.first().method)
            assertEquals(AdvancedHttpMethod.DELETE, transport.requests.last().method)
        }
    }

    @Test
    fun cancelledUploadStillForcesCleanupWithAFreshCancellationToken() {
        temporaryAudio("spoken.wav").use { audio ->
            val transport = RecordingTransport(
                failures = listOf(HttpTransportException.Cancelled(), null),
                statuses = listOf(204),
            )
            val failure = captureFailure {
                RemoteAudioPublisher(transport).publish(
                    config = AdvancedRemoteAudioConfig.WebDav(
                        uploadBaseUrl = "https://upload.example.test/private",
                        usernameSecretId = "dav-user",
                        passwordSecretId = "dav-password",
                        publicDownloadBaseUrl = "https://cdn.example.test/public",
                    ),
                    secrets = mapOf(
                        "dav-user" to "user",
                        "dav-password" to "pass",
                    ),
                    audio = audio.upload,
                    delivery = AudioDeliveryType.PUBLIC_HTTPS_URL,
                )
            }
            assertTrue(failure is RemoteAudioException.Cancelled)
            assertEquals(2, transport.requests.size)
            assertEquals(AdvancedHttpMethod.PUT, transport.requests.first().method)
            assertEquals(AdvancedHttpMethod.DELETE, transport.requests.last().method)
        }
    }

    private fun captureFailure(block: () -> Unit): Throwable = try {
        block()
        error("operation unexpectedly succeeded")
    } catch (error: Throwable) {
        error
    }

    private fun temporaryAudio(filename: String): TemporaryAudio {
        val directory = Files.createTempDirectory("remote-audio-test-").toFile()
        val file = File(directory, filename).apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        return TemporaryAudio(directory, PreparedAudioUpload(file, filename, "audio/wav"))
    }

    private class TemporaryAudio(
        private val directory: File,
        val upload: PreparedAudioUpload,
    ) : AutoCloseable {
        override fun close() {
            upload.file.delete()
            directory.delete()
        }
    }

    private class RecordingTransport(
        statuses: List<Int> = emptyList(),
        failures: List<HttpTransportException?> = emptyList(),
    ) : AdvancedHttpTransport {
        private val statuses = ArrayDeque(statuses)
        private val failures = failures.toMutableList()
        val requests = mutableListOf<HttpRequestSpec>()

        override fun execute(
            request: HttpRequestSpec,
            cancellation: AdvancedCancellationToken,
            callTimeoutMillis: Long?,
        ): HttpResponse {
            requests += request
            cancellation.throwIfCancelled()
            val failure = if (failures.isEmpty()) null else failures.removeAt(0)
            if (failure != null) throw failure
            if (statuses.isEmpty()) error("missing fake response status")
            return FixedResponse(statuses.removeFirst())
        }
    }

    private class FixedResponse(
        override val statusCode: Int,
    ) : HttpResponse {
        override val headers: List<HttpHeader> = emptyList()

        override fun readBody(maxBytes: Int): ByteArray = ByteArray(0)

        override fun openBodyStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun close() = Unit
    }

    private fun HttpRequestSpec.headerValue(name: String): String? = headers
        .firstOrNull { it.name.equals(name, ignoreCase = true) }
        ?.value
}
