package com.joeykot.dictate.advanced_audio

import com.joeykot.dictate.advanced_audio.http.HttpHeader

/**
 * HTTP field validation shared by declarative HTTP stages and websocket
 * handshakes.  Keep it independent of OkHttp so a rendered workflow is
 * rejected before a transport has a chance to open a connection.
 */
internal object HttpHeaderSafety {
    fun isValidName(value: String): Boolean = value.isNotEmpty() && value.all(::isTokenCharacter)

    fun hasUnsafeValueCharacters(value: String): Boolean =
        value.contains('\r') || value.contains('\n') || value.contains('\u0000')

    fun isSafe(name: String, value: String): Boolean =
        isValidName(name) && !hasUnsafeValueCharacters(value)

    fun requireSafe(headers: Iterable<HttpHeader>) {
        if (headers.any { header -> !isSafe(header.name, header.value) }) {
            // Never include a rendered header or its value: either can contain
            // a credential, a captured presigned URL, or injected line data.
            throw HeaderSafetyException
        }
    }

    private fun isTokenCharacter(value: Char): Boolean =
        value in '0'..'9' ||
            value in 'a'..'z' ||
            value in 'A'..'Z' ||
            value in TOKEN_PUNCTUATION

    private const val TOKEN_PUNCTUATION = "!#$%&'*+-.^_`|~"
}

/** A safe internal marker; callers map it to their own public error type. */
internal data object HeaderSafetyException : IllegalArgumentException("invalid HTTP header")
