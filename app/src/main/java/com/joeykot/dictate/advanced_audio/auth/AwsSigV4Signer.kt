package com.joeykot.dictate.advanced_audio.auth

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** AWS Signature Version 4 header signer used by workflow stages and S3 later. */
class AwsSigV4Signer(
    region: String,
    service: String,
    accessKey: String,
    secretKey: String,
    sessionToken: String? = null,
) : HttpRequestSigner {
    private val region = nonEmptyConfiguration("AWS SigV4", "region", region)
    private val service = nonEmptyConfiguration("AWS SigV4", "service", service)
    private val accessKey = nonEmptyCredential("AWS SigV4", "access key", accessKey)
    private val secretKey = nonEmptyCredential("AWS SigV4", "secret key", secretKey)
    private val sessionToken = sessionToken?.let {
        nonEmptyCredential("AWS SigV4", "session token", it)
    }

    override fun sign(request: SigningRequest) {
        val timestamp = AWS_TIMESTAMP.format(request.timestamp)
        val date = AWS_DATE.format(request.timestamp)
        val payloadHash = request.payloadSha256()

        setHostHeader(request)
        request.headers.set("x-amz-date", timestamp)
        request.headers.set("x-amz-content-sha256", payloadHash)
        sessionToken?.let { request.headers.set("x-amz-security-token", it) }

        val canonicalHeaders = canonicalAllHeaders(request.headers)
        val canonical = canonicalRequest(request, canonicalHeaders)
        val scope = "$date/$region/$service/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$timestamp\n$scope\n${sha256Hex(canonical.toByteArray(Charsets.UTF_8))}"
        val signature = hexLower(signingKey(date, stringToSign))
        val authorization = "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, " +
            "SignedHeaders=${canonicalHeaders.signedNames}, Signature=$signature"
        request.headers.set("authorization", authorization)
    }

    private fun signingKey(date: String, stringToSign: String): ByteArray {
        val dateKey = hmacSha256("AWS4$secretKey".toByteArray(Charsets.UTF_8), date)
        val regionKey = hmacSha256(dateKey, region)
        val serviceKey = hmacSha256(regionKey, service)
        val signingKey = hmacSha256(serviceKey, "aws4_request")
        return hmacSha256(signingKey, stringToSign)
    }

    private companion object {
        val AWS_TIMESTAMP: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
        val AWS_DATE: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC)
    }
}
