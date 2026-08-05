package com.joeykot.dictate.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestPolicyTest {
    @Test
    fun reservedMultipartFieldsAreCaseInsensitive() {
        assertTrue(AdditionalParameters.isReservedField("file"))
        assertTrue(AdditionalParameters.isReservedField("FILE"))
        assertTrue(AdditionalParameters.isReservedField("Model"))
        assertFalse(AdditionalParameters.isReservedField("language"))
    }

    @Test
    fun retryableHttpStatusesMatchTheSpecification() {
        listOf(408, 429, 500, 503, 599).forEach { status ->
            assertTrue("HTTP $status should be retryable", isRetryableHttpStatus(status))
        }
        listOf(200, 400, 401, 403, 404, 499, 600).forEach { status ->
            assertFalse("HTTP $status should not be retryable", isRetryableHttpStatus(status))
        }
    }
}
