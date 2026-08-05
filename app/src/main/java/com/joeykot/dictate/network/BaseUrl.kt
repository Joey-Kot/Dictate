package com.joeykot.dictate.network

import java.net.URI

object BaseUrl {
    fun transcriptionEndpoint(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { "Base URL 不能为空" }

        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            throw IllegalArgumentException("Base URL 格式无效")
        }

        val scheme = uri.scheme?.lowercase()
        require(scheme == "https" || scheme == "http") {
            "Base URL 必须使用 http 或 https"
        }
        require(!uri.host.isNullOrBlank()) { "Base URL 缺少有效主机名" }
        require(uri.userInfo == null) { "Base URL 不能包含用户名或密码" }
        require(uri.query == null && uri.fragment == null) { "Base URL 不能包含查询参数或片段" }

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
