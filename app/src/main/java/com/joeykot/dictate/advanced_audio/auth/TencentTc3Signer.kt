package com.joeykot.dictate.advanced_audio.auth

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Tencent Cloud TC3-HMAC-SHA256 header signer. */
class TencentTc3Signer(
    service: String,
    secretId: String,
    secretKey: String,
) : HttpRequestSigner {
    private val service = nonEmptyConfiguration("Tencent TC3", "service", service)
    private val secretId = nonEmptyCredential("Tencent TC3", "secret ID", secretId)
    private val secretKey = nonEmptyCredential("Tencent TC3", "secret key", secretKey)

    override fun sign(request: SigningRequest) {
        val timestamp = request.timestamp.epochSecond.toString()
        val date = TC3_DATE.format(request.timestamp)
        setHostHeader(request)
        request.headers.set("x-tc-timestamp", timestamp)

        val names = if (request.headers.contains("content-type")) {
            listOf("content-type", "host")
        } else {
            listOf("host")
        }
        val canonicalHeaders = canonicalSelectedHeaders(request.headers, names)
        val canonical = canonicalRequest(request, canonicalHeaders)
        val scope = "$date/$service/tc3_request"
        val stringToSign = "TC3-HMAC-SHA256\n$timestamp\n$scope\n${sha256Hex(canonical.toByteArray(Charsets.UTF_8))}"
        val signature = hexLower(signingKey(date, stringToSign))
        val authorization = "TC3-HMAC-SHA256 Credential=$secretId/$scope, " +
            "SignedHeaders=${canonicalHeaders.signedNames}, Signature=$signature"
        request.headers.set("authorization", authorization)
    }

    private fun signingKey(date: String, stringToSign: String): ByteArray {
        val dateKey = hmacSha256("TC3$secretKey".toByteArray(Charsets.UTF_8), date)
        val serviceKey = hmacSha256(dateKey, service)
        val signingKey = hmacSha256(serviceKey, "tc3_request")
        return hmacSha256(signingKey, stringToSign)
    }

    private companion object {
        val TC3_DATE: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd")
            .withZone(ZoneOffset.UTC)
    }
}
