package com.joeykot.dictate.advanced_audio.auth

import com.joeykot.dictate.advanced_audio.http.AdvancedHttpMethod
import com.joeykot.dictate.advanced_audio.http.HttpHeader
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class RequestSignerTest {
    @Test
    fun awsSigV4MatchesTheWindowsCanonicalVector() {
        val request = SigningRequest(
            method = AdvancedHttpMethod.POST,
            url = "https://example.amazonaws.com:8443/a%20b/%7E?z=last&dup=b&dup=a&space=one+two".toHttpUrl(),
            headers = SigningHeaders(
                listOf(
                    HttpHeader("Content-Type", " application/json; charset=utf-8 "),
                    HttpHeader("X-Custom", "\t alpha   beta \t"),
                ),
            ),
            payload = "{\"message\":\"hello\"}".toByteArray(),
            timestamp = Instant.parse("2024-01-02T03:04:05Z"),
        )

        AwsSigV4Signer(
            region = "us-east-1",
            service = "execute-api",
            accessKey = "AKIDEXAMPLE",
            secretKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            sessionToken = "session-token-123",
        ).sign(request)

        val canonicalHeaders = canonicalAllHeaders(request.headers)
        assertEquals(
            "POST\n/a%20b/~\ndup=a&dup=b&space=one%2Btwo&z=last\n" +
                "content-type:application/json; charset=utf-8\n" +
                "host:example.amazonaws.com:8443\n" +
                "x-amz-content-sha256:9b2d43affbf49a367028df2e1414f84c0e099ac98c3d54a8a80157fd7771af25\n" +
                "x-amz-date:20240102T030405Z\n" +
                "x-amz-security-token:session-token-123\n" +
                "x-custom:alpha beta\n\n" +
                "content-type;host;x-amz-content-sha256;x-amz-date;x-amz-security-token;x-custom\n" +
                "9b2d43affbf49a367028df2e1414f84c0e099ac98c3d54a8a80157fd7771af25",
            canonicalRequest(request, canonicalHeaders),
        )
        assertEquals("session-token-123", request.headers.values("x-amz-security-token").single())
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20240102/us-east-1/execute-api/aws4_request, " +
                "SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date;x-amz-security-token;x-custom, " +
                "Signature=cd9812200d4fb80c4679363ebc5ccffd4266989077c4bf7b6a43aced489c4fdc",
            request.headers.values("authorization").single(),
        )
    }

    @Test
    fun tencentTc3MatchesTheWindowsCanonicalVector() {
        val request = SigningRequest(
            method = AdvancedHttpMethod.POST,
            url = "https://cvm.tencentcloudapi.com/?z=last&dup=b&dup=a".toHttpUrl(),
            headers = SigningHeaders(
                listOf(
                    HttpHeader("Content-Type", " application/json; charset=utf-8 "),
                    HttpHeader("X-TC-Action", "DescribeInstances"),
                    HttpHeader("X-TC-Version", "2017-03-12"),
                ),
            ),
            payload = "{\"Limit\":1}".toByteArray(),
            timestamp = Instant.parse("2019-02-25T16:44:25Z"),
        )

        TencentTc3Signer(
            service = "cvm",
            secretId = "AKIDEXAMPLE",
            secretKey = "test-secret-key",
        ).sign(request)

        val canonicalHeaders = canonicalSelectedHeaders(request.headers, listOf("content-type", "host"))
        assertEquals(
            "POST\n/\ndup=a&dup=b&z=last\n" +
                "content-type:application/json; charset=utf-8\n" +
                "host:cvm.tencentcloudapi.com\n\n" +
                "content-type;host\n" +
                "55522f708dcfebccb7bd3e8d0001a53ecaf2beca9ca801f1e9161e24215faa99",
            canonicalRequest(request, canonicalHeaders),
        )
        assertEquals("1551113065", request.headers.values("x-tc-timestamp").single())
        assertEquals(
            "TC3-HMAC-SHA256 Credential=AKIDEXAMPLE/2019-02-25/cvm/tc3_request, " +
                "SignedHeaders=content-type;host, " +
                "Signature=c69f8f90d66a7c61beaaf99c3a84420f03bac07e2d94b5b46f719edcfd005b0a",
            request.headers.values("authorization").single(),
        )
    }
}
