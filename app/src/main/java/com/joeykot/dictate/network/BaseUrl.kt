package com.joeykot.dictate.network

import com.joeykot.dictate.R
import com.joeykot.dictate.i18n.AppStrings
import com.joeykot.dictate.model.PostProcessingProvider
import java.net.URI

object BaseUrl {
    fun postProcessingEndpoint(input: String, provider: PostProcessingProvider, model: String): String {
        val trimmed = input.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { AppStrings.get(R.string.val_base_url_empty, "Base URL cannot be empty") }
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            throw IllegalArgumentException(AppStrings.get(R.string.val_base_url_invalid, "Invalid Base URL format"))
        }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "https" || scheme == "http") { AppStrings.get(R.string.val_base_url_scheme, "Base URL must use http or https") }
        require(!uri.host.isNullOrBlank()) { AppStrings.get(R.string.val_base_url_host, "Base URL must contain a valid hostname") }
        require(uri.userInfo == null) { AppStrings.get(R.string.val_base_url_credentials, "Base URL cannot contain a username or password") }
        require(uri.query == null && uri.fragment == null) { AppStrings.get(R.string.val_base_url_query, "Base URL cannot contain query parameters or a fragment") }

        var path = (uri.path ?: "").trimEnd('/')
        if (provider == PostProcessingProvider.GOOGLE) {
            val modelId = model.removePrefix("models/")
            require(modelId.isNotBlank() && modelId.none { it == '/' || it == '?' || it == '#' }) {
                AppStrings.get(R.string.val_google_model, "Google Model must be a model name or models/model-name")
            }
            path = path.substringBefore("/models/").trimEnd('/')
            if (path.isEmpty()) path = "/v1beta"
            path += "/models/$modelId:generateContent"
        } else {
            val suffix = when (provider) {
                PostProcessingProvider.OPENAI_RESPONSES -> "/responses"
                PostProcessingProvider.ANTHROPIC -> "/messages"
                else -> "/chat/completions"
            }
            if (path.isEmpty()) {
                path = when (provider) {
                    PostProcessingProvider.DEEPSEEK -> ""
                    PostProcessingProvider.QWEN -> "/compatible-mode/v1"
                    PostProcessingProvider.GLM -> "/api/paas/v4"
                    else -> "/v1"
                }
            }
            if (!path.endsWith(suffix)) path += suffix
        }
        return URI(scheme, null, uri.host, uri.port, path, null, null).toASCIIString()
    }

    fun transcriptionEndpoint(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { AppStrings.get(R.string.val_base_url_empty, "Base URL cannot be empty") }

        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            throw IllegalArgumentException(AppStrings.get(R.string.val_base_url_invalid, "Invalid Base URL format"))
        }

        val scheme = uri.scheme?.lowercase()
        require(scheme == "https" || scheme == "http") {
            AppStrings.get(R.string.val_base_url_scheme, "Base URL must use http or https")
        }
        require(!uri.host.isNullOrBlank()) { AppStrings.get(R.string.val_base_url_host, "Base URL must contain a valid hostname") }
        require(uri.userInfo == null) { AppStrings.get(R.string.val_base_url_credentials, "Base URL cannot contain a username or password") }
        require(uri.query == null && uri.fragment == null) { AppStrings.get(R.string.val_base_url_query, "Base URL cannot contain query parameters or a fragment") }

        val basePath = (uri.rawPath ?: "").trimEnd('/')
        val endpointPath = if (basePath.endsWith("/v1")) {
            "$basePath/audio/transcriptions"
        } else {
            "$basePath/v1/audio/transcriptions"
        }.replace(Regex("/{2,}"), "/")

        return URI(
            scheme,
            null,
            uri.host,
            uri.port,
            endpointPath,
            null,
            null,
        ).toASCIIString()
    }
}
