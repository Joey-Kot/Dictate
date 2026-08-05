package com.joeykot.dictate.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RetryConfigTest {
    @Test
    fun exponentialBackoffMatchesSpecification() {
        val retry = RetryConfig(enabled = true, maxRetries = 4, initialBackoffSeconds = 0.5)
        assertEquals(500L, retry.delayMillis(1))
        assertEquals(1_000L, retry.delayMillis(2))
        assertEquals(2_000L, retry.delayMillis(3))
        assertEquals(4_000L, retry.delayMillis(4))
    }
}

