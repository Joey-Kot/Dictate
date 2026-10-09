package com.joeykot.dictate.advanced_audio.http

import java.io.IOException

/**
 * Transport errors intentionally do not embed rendered URLs, headers, bodies,
 * or local file paths.  Those values can contain workflow secrets or
 * presigned URLs and must remain outside user-facing diagnostics.
 */
sealed class HttpTransportException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {
    class Cancelled : HttpTransportException("Advanced HTTP request was cancelled")
    class InvalidUrl : HttpTransportException("Advanced HTTP stage URL must be an absolute HTTP(S) URL")
    class InvalidRequest : HttpTransportException("Advanced HTTP request could not be constructed")
    class InvalidMediaType : HttpTransportException("Advanced HTTP request contains an invalid media type")
    class AudioFileUnavailable(cause: Throwable? = null) :
        HttpTransportException("Advanced HTTP audio file is unavailable", cause)

    class SignerRequiresMaterializedBody : HttpTransportException(
        "Dynamic request signing requires a materialized request body; multipart and raw audio remain streaming",
    )

    class SigningFailed(cause: Throwable) : HttpTransportException("Advanced HTTP request signing failed", cause)
    class NetworkFailure(cause: Throwable) : HttpTransportException("Advanced HTTP request failed", cause)
    class ResponseReadFailure(cause: Throwable) : HttpTransportException("Advanced HTTP response could not be read", cause)
    class ResponseTooLarge(val maximumBytes: Int) :
        HttpTransportException("Advanced HTTP response exceeds the $maximumBytes-byte limit")

    class ResponseAlreadyConsumed : HttpTransportException("Advanced HTTP response body was already consumed")
}
