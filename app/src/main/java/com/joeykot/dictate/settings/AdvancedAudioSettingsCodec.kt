package com.joeykot.dictate.settings

import com.joeykot.dictate.model.AdvancedAudioConfig
import com.joeykot.dictate.model.AdvancedRemoteAudioConfig
import org.json.JSONObject

/** JSON codec for the non-secret Advanced Audio API settings kept by [SettingsRepository]. */
internal object AdvancedAudioSettingsCodec {
    fun encode(config: AdvancedAudioConfig): JSONObject = JSONObject().apply {
        put("enabled", config.enabled)
        config.workflowJson?.let { put("workflow", it) }
        put("values", encodeStringMap(config.values))
        put("remoteAudio", encodeRemoteAudio(config.remoteAudio))
    }

    /**
     * Keep the configuration envelope forward-compatible with older drafts
     * that omitted default-valued fields. Unknown *fields* are ignored, but a
     * present known field still has to have its exact expected type.
     */
    fun decode(value: JSONObject, path: String = "advancedAudio"): AdvancedAudioConfig = AdvancedAudioConfig(
        enabled = optionalBoolean(value, "enabled", "$path.enabled", false),
        workflowJson = optionalNullableString(value, "workflow", "$path.workflow"),
        values = if (value.has("values")) {
            decodeStringMap(requiredObject(value, "values", "$path.values"), "$path.values")
        } else {
            emptyMap()
        },
        remoteAudio = if (value.has("remoteAudio")) {
            decodeRemoteAudio(
                requiredObject(value, "remoteAudio", "$path.remoteAudio"),
                "$path.remoteAudio",
            )
        } else {
            AdvancedRemoteAudioConfig.None
        },
    )

    fun encodeStringMap(values: Map<String, String>): JSONObject = JSONObject().apply {
        values.forEach { (key, value) -> put(key, value) }
    }

    fun decodeStringMap(value: JSONObject, path: String): Map<String, String> = buildMap {
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = value.opt(key)
            if (item !is String) throw IllegalArgumentException("$path.$key must be a string")
            put(key, item)
        }
    }

    private fun encodeRemoteAudio(config: AdvancedRemoteAudioConfig): JSONObject = when (config) {
        AdvancedRemoteAudioConfig.None -> JSONObject().put("type", "none")
        is AdvancedRemoteAudioConfig.WebDav -> JSONObject()
            .put("type", "webdav")
            .put("uploadBaseUrl", config.uploadBaseUrl)
            .put("usernameSecretId", config.usernameSecretId)
            .put("passwordSecretId", config.passwordSecretId)
            .put("remotePathPrefix", config.remotePathPrefix)
            .put("publicDownloadBaseUrl", config.publicDownloadBaseUrl)
            .put("deleteAfterRecognition", config.deleteAfterRecognition)
        is AdvancedRemoteAudioConfig.S3Compatible -> JSONObject()
            .put("type", "s3_compatible")
            .put("endpoint", config.endpoint)
            .put("region", config.region)
            .put("bucket", config.bucket)
            .put("accessKeySecretId", config.accessKeySecretId)
            .put("secretKeySecretId", config.secretKeySecretId)
            .put("prefix", config.prefix)
            .putOpt("publicUrlBase", config.publicUrlBase)
            .put("presigned", config.presigned)
            .put("deleteAfterRecognition", config.deleteAfterRecognition)
        is AdvancedRemoteAudioConfig.AliyunOss -> JSONObject()
            .put("type", "aliyun_oss")
            .put("endpoint", config.endpoint)
            .put("bucket", config.bucket)
            .put("accessKeySecretId", config.accessKeySecretId)
            .put("secretKeySecretId", config.secretKeySecretId)
            .put("prefix", config.prefix)
            .putOpt("publicUrlBase", config.publicUrlBase)
            .put("presigned", config.presigned)
            .put("deleteAfterRecognition", config.deleteAfterRecognition)
    }

    private fun decodeRemoteAudio(value: JSONObject, path: String): AdvancedRemoteAudioConfig = when (
        requiredString(value, "type", "$path.type")
    ) {
        "none" -> AdvancedRemoteAudioConfig.None
        "webdav" -> AdvancedRemoteAudioConfig.WebDav(
            uploadBaseUrl = optionalString(value, "uploadBaseUrl", "$path.uploadBaseUrl", ""),
            usernameSecretId = optionalString(
                value,
                "usernameSecretId",
                "$path.usernameSecretId",
                "",
            ),
            passwordSecretId = optionalString(
                value,
                "passwordSecretId",
                "$path.passwordSecretId",
                "",
            ),
            remotePathPrefix = optionalString(value, "remotePathPrefix", "$path.remotePathPrefix", ""),
            publicDownloadBaseUrl = optionalString(value, "publicDownloadBaseUrl", "$path.publicDownloadBaseUrl", ""),
            deleteAfterRecognition = optionalBoolean(value, "deleteAfterRecognition", "$path.deleteAfterRecognition", true),
        )
        "s3_compatible" -> AdvancedRemoteAudioConfig.S3Compatible(
            endpoint = optionalString(value, "endpoint", "$path.endpoint", ""),
            region = optionalString(value, "region", "$path.region", ""),
            bucket = optionalString(value, "bucket", "$path.bucket", ""),
            accessKeySecretId = optionalString(
                value,
                "accessKeySecretId",
                "$path.accessKeySecretId",
                "",
            ),
            secretKeySecretId = optionalString(
                value,
                "secretKeySecretId",
                "$path.secretKeySecretId",
                "",
            ),
            prefix = optionalString(value, "prefix", "$path.prefix", ""),
            publicUrlBase = optionalNullableString(value, "publicUrlBase", "$path.publicUrlBase"),
            presigned = optionalBoolean(value, "presigned", "$path.presigned", false),
            deleteAfterRecognition = optionalBoolean(value, "deleteAfterRecognition", "$path.deleteAfterRecognition", true),
        )
        "aliyun_oss" -> AdvancedRemoteAudioConfig.AliyunOss(
            endpoint = optionalString(value, "endpoint", "$path.endpoint", ""),
            bucket = optionalString(value, "bucket", "$path.bucket", ""),
            accessKeySecretId = optionalString(
                value,
                "accessKeySecretId",
                "$path.accessKeySecretId",
                "",
            ),
            secretKeySecretId = optionalString(
                value,
                "secretKeySecretId",
                "$path.secretKeySecretId",
                "",
            ),
            prefix = optionalString(value, "prefix", "$path.prefix", ""),
            publicUrlBase = optionalNullableString(value, "publicUrlBase", "$path.publicUrlBase"),
            presigned = optionalBoolean(value, "presigned", "$path.presigned", false),
            deleteAfterRecognition = optionalBoolean(value, "deleteAfterRecognition", "$path.deleteAfterRecognition", true),
        )
        else -> throw IllegalArgumentException("$path.type is unsupported")
    }

    private fun requiredObject(parent: JSONObject, key: String, path: String): JSONObject =
        requiredValue(parent, key, path) as? JSONObject
            ?: throw IllegalArgumentException("$path must be a JSON object")

    private fun requiredString(parent: JSONObject, key: String, path: String): String =
        requiredValue(parent, key, path) as? String
            ?: throw IllegalArgumentException("$path must be a string")

    private fun requiredBoolean(parent: JSONObject, key: String, path: String): Boolean =
        requiredValue(parent, key, path) as? Boolean
            ?: throw IllegalArgumentException("$path must be a boolean")

    private fun optionalString(parent: JSONObject, key: String, path: String, default: String): String =
        if (parent.has(key)) requiredString(parent, key, path) else default

    private fun optionalNullableString(parent: JSONObject, key: String, path: String): String? = when {
        !parent.has(key) || parent.isNull(key) -> null
        else -> requiredString(parent, key, path)
    }

    private fun optionalBoolean(parent: JSONObject, key: String, path: String, default: Boolean): Boolean =
        if (parent.has(key)) requiredBoolean(parent, key, path) else default

    private fun requiredValue(parent: JSONObject, key: String, path: String): Any {
        if (!parent.has(key) || parent.isNull(key)) throw IllegalArgumentException("$path is required")
        return parent.get(key)
    }
}
