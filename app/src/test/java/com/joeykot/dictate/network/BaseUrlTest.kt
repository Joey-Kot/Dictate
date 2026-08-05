package com.joeykot.dictate.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BaseUrlTest {
    @Test
    fun appendsV1WhenMissing() {
        assertEquals(
            "https://example.com/v1/audio/transcriptions",
            BaseUrl.transcriptionEndpoint("https://example.com"),
        )
    }

    @Test
    fun doesNotDuplicateV1() {
        assertEquals(
            "https://example.com/v1/audio/transcriptions",
            BaseUrl.transcriptionEndpoint("https://example.com/v1/"),
        )
    }

    @Test
    fun acceptsUppercaseScheme() {
        assertEquals(
            "https://example.com/v1/audio/transcriptions",
            BaseUrl.transcriptionEndpoint("HTTPS://example.com"),
        )
    }

    @Test
    fun preservesBasePathAndPort() {
        assertEquals(
            "http://localhost:8080/proxy/v1/audio/transcriptions",
            BaseUrl.transcriptionEndpoint("http://localhost:8080/proxy"),
        )
    }

    @Test
    fun rejectsCredentialsAndQueries() {
        assertThrows(IllegalArgumentException::class.java) {
            BaseUrl.transcriptionEndpoint("https://user:secret@example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BaseUrl.transcriptionEndpoint("https://example.com?token=secret")
        }
    }
}
