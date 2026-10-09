package com.joeykot.dictate.advanced_audio.auth

import com.joeykot.dictate.advanced_audio.http.AdvancedHttpMethod
import com.joeykot.dictate.advanced_audio.http.HttpHeader
import okhttp3.HttpUrl
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.SortedMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Built-in request signing boundary for Advanced Audio API stages.  Workflow
 * JSON can select a finite signer, but cannot provide executable signing code.
 */
fun interface HttpRequestSigner {
    @Throws(RequestSigningException::class)
    fun sign(request: SigningRequest)
}

/**
 * Mutable request data supplied to a signer after URL, headers, and body are
 * final.  Signers may only add or replace headers; they cannot rewrite the URL
 * or payload they are authenticating.
 */
class SigningRequest internal constructor(
    val method: AdvancedHttpMethod,
    val url: HttpUrl,
    val headers: SigningHeaders,
    private val payload: ByteArray,
    private val payloadHashOverride: String? = null,
    val timestamp: Instant,
) {
    fun payloadSha256(): String = payloadHashOverride ?: sha256Hex(payload)
}

/**
 * Case-insensitive request header collection that preserves repeated header
 * values for canonical signing.  It deliberately does not expose a string
 * representation, because values may contain credentials.
 */
class SigningHeaders internal constructor(initial: List<HttpHeader>) {
    private val entries = initial.toMutableList()

    fun contains(name: String): Boolean = entries.any { it.name.equals(name, ignoreCase = true) }

    fun values(name: String): List<String> = entries
        .asSequence()
        .filter { it.name.equals(name, ignoreCase = true) }
        .map { it.value }
        .toList()

    fun set(name: String, value: String) {
        entries.removeAll { it.name.equals(name, ignoreCase = true) }
        entries += HttpHeader(name, value)
    }

    internal fun toList(): List<HttpHeader> = entries.toList()
}

sealed class RequestSigningException(message: String) : Exception(message) {
    class InvalidConfiguration(signer: String, field: String) :
        RequestSigningException("$signer signer configuration field '$field' is empty")

    class InvalidCredential(signer: String, credential: String) :
        RequestSigningException("$signer $credential credential is empty")

    class MissingHost : RequestSigningException("Request URL does not contain a host")
}

internal data class CanonicalHeaders(
    val value: String,
    val signedNames: String,
)

internal fun canonicalRequest(request: SigningRequest, headers: CanonicalHeaders): String = buildString {
    append(request.method.wireName)
    append('\n')
    append(canonicalUri(request.url))
    append('\n')
    append(canonicalQuery(request.url))
    append('\n')
    append(headers.value)
    append('\n')
    append(headers.signedNames)
    append('\n')
    append(request.payloadSha256())
}

internal fun canonicalAllHeaders(headers: SigningHeaders): CanonicalHeaders {
    val grouped = sortedMapOf<String, MutableList<String>>()
    headers.toList().forEach { header ->
        if (header.name.equals("authorization", ignoreCase = true)) return@forEach
        grouped.getOrPut(header.name.lowercase(Locale.US)) { mutableListOf() }
            .add(normalizeHeaderValue(header.value))
    }
    return canonicalizeHeaders(grouped)
}

internal fun canonicalSelectedHeaders(headers: SigningHeaders, names: List<String>): CanonicalHeaders {
    val grouped = sortedMapOf<String, MutableList<String>>()
    names.forEach { name ->
        val values = headers.values(name)
        if (values.isNotEmpty()) {
            grouped[name.lowercase(Locale.US)] = values.mapTo(mutableListOf(), ::normalizeHeaderValue)
        }
    }
    return canonicalizeHeaders(grouped)
}

internal fun setHostHeader(request: SigningRequest) {
    val host = request.url.host.ifBlank { throw RequestSigningException.MissingHost() }
    val bracketed = if (host.contains(':')) "[$host]" else host
    val defaultPort = when (request.url.scheme) {
        "http" -> 80
        "https" -> 443
        else -> null
    }
    val value = if (request.url.port == defaultPort) bracketed else "$bracketed:${request.url.port}"
    request.headers.set("host", value)
}

internal fun sha256Hex(bytes: ByteArray): String = hexLower(MessageDigest.getInstance("SHA-256").digest(bytes))

internal fun hmacSha256(key: ByteArray, value: String): ByteArray = Mac.getInstance("HmacSHA256")
    .apply { init(SecretKeySpec(key, "HmacSHA256")) }
    .doFinal(value.toByteArray(Charsets.UTF_8))

internal fun hexLower(bytes: ByteArray): String = buildString(bytes.size * 2) {
    bytes.forEach { value ->
        append(HEX[value.toInt().ushr(4) and 0x0f])
        append(HEX[value.toInt() and 0x0f])
    }
}

internal fun canonicalUri(url: HttpUrl): String {
    val encodedPath = url.encodedPath
    return uriEncode(percentDecode(encodedPath), preserveSlash = true).ifEmpty { "/" }
}

internal fun canonicalQuery(url: HttpUrl): String {
    val query = url.encodedQuery ?: return ""
    if (query.isEmpty()) return ""
    return query
        .split('&')
        .map { pair ->
            val separator = pair.indexOf('=')
            val name = if (separator < 0) pair else pair.substring(0, separator)
            val value = if (separator < 0) "" else pair.substring(separator + 1)
            uriEncode(percentDecode(name), preserveSlash = false) to
                uriEncode(percentDecode(value), preserveSlash = false)
        }
        .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
        .joinToString("&") { (name, value) -> "$name=$value" }
}

private fun canonicalizeHeaders(headers: SortedMap<String, MutableList<String>>): CanonicalHeaders {
    if (headers.isEmpty()) throw RequestSigningException.MissingHost()
    val canonical = buildString {
        headers.forEach { (name, values) ->
            append(name)
            append(':')
            append(values.joinToString(","))
            append('\n')
        }
    }
    return CanonicalHeaders(canonical, headers.keys.joinToString(";"))
}

private fun normalizeHeaderValue(value: String): String = value
    .trim()
    .split(HEADER_WHITESPACE)
    .filter(String::isNotEmpty)
    .joinToString(" ")

private fun percentDecode(value: String): ByteArray {
    val output = ByteArrayOutputStream(value.length)
    var index = 0
    while (index < value.length) {
        if (value[index] == '%' && index + 2 < value.length) {
            val high = value[index + 1].digitToIntOrNull(16)
            val low = value[index + 2].digitToIntOrNull(16)
            if (high != null && low != null) {
                output.write((high shl 4) or low)
                index += 3
                continue
            }
        }
        val codePoint = value.codePointAt(index)
        output.write(String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8))
        index += Character.charCount(codePoint)
    }
    return output.toByteArray()
}

private fun uriEncode(bytes: ByteArray, preserveSlash: Boolean): String = buildString(bytes.size * 3) {
    bytes.forEach { signed ->
        val value = signed.toInt() and 0xff
        val unreserved = value in 'a'.code..'z'.code ||
            value in 'A'.code..'Z'.code ||
            value in '0'.code..'9'.code ||
            value == '-'.code || value == '_'.code || value == '.'.code || value == '~'.code
        when {
            unreserved -> append(value.toChar())
            preserveSlash && value == '/'.code -> append('/')
            else -> {
                append('%')
                append(UPPER_HEX[value.ushr(4)])
                append(UPPER_HEX[value and 0x0f])
            }
        }
    }
}

internal fun nonEmptyConfiguration(signer: String, field: String, value: String): String {
    if (value.trim().isEmpty()) throw RequestSigningException.InvalidConfiguration(signer, field)
    return value
}

internal fun nonEmptyCredential(signer: String, credential: String, value: String): String {
    if (value.trim().isEmpty()) throw RequestSigningException.InvalidCredential(signer, credential)
    return value
}

private val HEADER_WHITESPACE = Regex("\\s+")
private const val HEX = "0123456789abcdef"
private const val UPPER_HEX = "0123456789ABCDEF"
