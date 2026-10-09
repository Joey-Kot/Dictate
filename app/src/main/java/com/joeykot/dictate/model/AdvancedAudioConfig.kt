package com.joeykot.dictate.model

/**
 * Persisted settings for the opt-in declarative Advanced Audio API.
 *
 * The workflow remains its original JSON text here. Parsing and validating it
 * belongs to the advanced-audio protocol layer, so an incomplete disabled
 * draft cannot affect the existing OpenAI-compatible transcription route.
 * Values deliberately remain strings for schema v1/v2 compatibility.
 * Secrets are intentionally not part of this model; they live in
 * [com.joeykot.dictate.settings.SecureApiKeyStore].
 */
data class AdvancedAudioConfig(
    val enabled: Boolean = false,
    val workflowJson: String? = null,
    val values: Map<String, String> = emptyMap(),
    val remoteAudio: AdvancedRemoteAudioConfig = AdvancedRemoteAudioConfig.None,
) {
    override fun toString(): String =
        "AdvancedAudioConfig(enabled=$enabled, workflowJson=${if (workflowJson == null) "null" else "<present>"}, " +
            "values=$values, remoteAudio=$remoteAudio)"
}

/**
 * Non-sensitive remote-audio hosting settings.
 *
 * Credential values are referenced by internal IDs and are never kept in
 * SharedPreferences' plain configuration or an exported configuration file.
 */
sealed interface AdvancedRemoteAudioConfig {
    data object None : AdvancedRemoteAudioConfig

    data class WebDav(
        val uploadBaseUrl: String = "",
        val usernameSecretId: String = "",
        val passwordSecretId: String = "",
        val remotePathPrefix: String = "",
        val publicDownloadBaseUrl: String = "",
        val deleteAfterRecognition: Boolean = true,
    ) : AdvancedRemoteAudioConfig {
        override fun toString(): String = "WebDav(<redacted>)"
    }

    data class S3Compatible(
        val endpoint: String = "",
        val region: String = "",
        val bucket: String = "",
        val accessKeySecretId: String = "",
        val secretKeySecretId: String = "",
        val prefix: String = "",
        val publicUrlBase: String? = null,
        val presigned: Boolean = false,
        val deleteAfterRecognition: Boolean = true,
    ) : AdvancedRemoteAudioConfig {
        override fun toString(): String = "S3Compatible(<redacted>)"
    }

    data class AliyunOss(
        val endpoint: String = "",
        val bucket: String = "",
        val accessKeySecretId: String = "",
        val secretKeySecretId: String = "",
        val prefix: String = "",
        val publicUrlBase: String? = null,
        val presigned: Boolean = false,
        val deleteAfterRecognition: Boolean = true,
    ) : AdvancedRemoteAudioConfig {
        override fun toString(): String = "AliyunOss(<redacted>)"
    }
}

/**
 * Stable Keystore references used by newly configured remote-audio providers.
 *
 * Dots deliberately make these IDs invalid workflow identifiers, so a vendor
 * workflow can never declare or render a remote-storage credential. Existing
 * configurations may still contain custom legacy IDs; [forConfig] preserves
 * those values until the user saves the remote-audio editor again.
 */
object RemoteAudioCredentialIds {
    const val WEB_DAV_USERNAME = "remote.audio.webdav.username"
    const val WEB_DAV_PASSWORD = "remote.audio.webdav.password"
    const val S3_ACCESS_KEY = "remote.audio.s3.access_key"
    const val S3_SECRET_KEY = "remote.audio.s3.secret_key"
    const val OSS_ACCESS_KEY = "remote.audio.oss.access_key"
    const val OSS_SECRET_KEY = "remote.audio.oss.secret_key"

    fun forConfig(config: AdvancedRemoteAudioConfig): Set<String> = when (config) {
        AdvancedRemoteAudioConfig.None -> emptySet()
        is AdvancedRemoteAudioConfig.WebDav -> setOf(
            config.usernameSecretId,
            config.passwordSecretId,
        )
        is AdvancedRemoteAudioConfig.S3Compatible -> setOf(
            config.accessKeySecretId,
            config.secretKeySecretId,
        )
        is AdvancedRemoteAudioConfig.AliyunOss -> setOf(
            config.accessKeySecretId,
            config.secretKeySecretId,
        )
    }.filterTo(linkedSetOf()) { it.isNotBlank() }
}
