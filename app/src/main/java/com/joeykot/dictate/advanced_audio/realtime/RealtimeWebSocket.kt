package com.joeykot.dictate.advanced_audio.realtime

import com.joeykot.dictate.advanced_audio.http.AdvancedCancellationToken
import com.joeykot.dictate.advanced_audio.http.CancellationRegistration
import com.joeykot.dictate.advanced_audio.http.HttpHeader
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Rendered websocket handshake data. Its string representation deliberately
 * hides the URL and headers because either can contain credentials.
 */
class RealtimeWebSocketRequest(
    val url: String,
    headers: List<HttpHeader>,
) {
    val headers: List<HttpHeader> = headers.map { HttpHeader(it.name, it.value) }

    override fun toString(): String = "RealtimeWebSocketRequest(redacted)"
}

sealed interface RealtimeWebSocketEvent {
    class Text(val value: String) : RealtimeWebSocketEvent {
        override fun toString(): String = "RealtimeWebSocketEvent.Text(redacted)"
    }

    class Binary(val value: ByteArray) : RealtimeWebSocketEvent {
        override fun toString(): String = "RealtimeWebSocketEvent.Binary(redacted)"
    }

    data object Closed : RealtimeWebSocketEvent
    data object Failed : RealtimeWebSocketEvent
}

/** A small testable boundary around an established websocket connection. */
interface RealtimeWebSocket : AutoCloseable {
    /** Returns false when the socket can no longer accept a frame. */
    fun sendText(value: String): Boolean

    /** Returns false when the socket can no longer accept a frame. */
    fun sendBinary(value: ByteArray): Boolean

    /** Returns null only when no event arrives before [timeoutMillis]. */
    fun receive(timeoutMillis: Long): RealtimeWebSocketEvent?

    override fun close()
}

/** Factory injection keeps realtime protocol tests independent of a network server. */
fun interface RealtimeWebSocketFactory {
    fun connect(
        request: RealtimeWebSocketRequest,
        cancellation: AdvancedCancellationToken,
    ): RealtimeWebSocket
}

/**
 * OkHttp-backed websocket factory. It reports only finite, redacted transport
 * outcomes to the runner; response bodies, handshake URLs, headers, close
 * reasons, and listener throwables stay outside the public error boundary.
 */
class OkHttpRealtimeWebSocketFactory(
    private val client: OkHttpClient = newDefaultClient(),
    private val handshakeTimeoutMillis: Long = DEFAULT_HANDSHAKE_TIMEOUT_MILLIS,
) : RealtimeWebSocketFactory {
    init {
        require(handshakeTimeoutMillis > 0L)
    }

    override fun connect(
        request: RealtimeWebSocketRequest,
        cancellation: AdvancedCancellationToken,
    ): RealtimeWebSocket {
        if (cancellation.isCancelled()) throw RealtimeSessionException.SessionCancelled
        val okRequest = try {
            val headers = Headers.Builder().apply {
                request.headers.forEach { header -> add(header.name, header.value) }
            }.build()
            Request.Builder().url(request.url).headers(headers).build()
        } catch (_: IllegalArgumentException) {
            throw RealtimeSessionException.InvalidConfiguration
        }

        val events = BoundedEventQueue()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                events.offerOrCancel(QueuedEvent.Open, webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val bytes = text.toByteArray(Charsets.UTF_8).size
                if (bytes > MAX_WEBSOCKET_MESSAGE_BYTES) {
                    events.failAndCancel(webSocket)
                } else {
                    events.offerOrCancel(QueuedEvent.Message(RealtimeWebSocketEvent.Text(text), bytes), webSocket)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size.toLong() > MAX_WEBSOCKET_MESSAGE_BYTES.toLong()) {
                    events.failAndCancel(webSocket)
                } else {
                    events.offerOrCancel(
                        QueuedEvent.Message(RealtimeWebSocketEvent.Binary(bytes.toByteArray()), bytes.size.toInt()),
                        webSocket,
                    )
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Do not retain or surface the server-provided close reason.
                webSocket.close(code, "")
                events.offerOrCancel(QueuedEvent.Closed, webSocket)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                events.offerOrCancel(QueuedEvent.Closed, webSocket)
            }

            override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
                // Throwable and optional HTTP response may contain a rendered
                // endpoint or server text. The session only sees Failed.
                events.offerOrCancel(QueuedEvent.Failed, webSocket)
            }
        }

        val webSocket = try {
            client.newWebSocket(okRequest, listener)
        } catch (_: RuntimeException) {
            throw RealtimeSessionException.WebSocketConnectionFailed
        }
        val registration = cancellation.onCancellation { webSocket.cancel() }
        val handshakeDeadline = HandshakeDeadline.after(handshakeTimeoutMillis)
        try {
            while (true) {
                if (cancellation.isCancelled()) throw RealtimeSessionException.SessionCancelled
                if (events.isOverflowed()) throw RealtimeSessionException.WebSocketConnectionFailed
                val remainingMillis = handshakeDeadline.remainingMillis()
                    ?: throw RealtimeSessionException.WebSocketConnectionFailed
                val event = poll(events, minOf(CONNECT_WAIT_SLICE_MILLIS, remainingMillis))
                // Cancelling a socket commonly produces OkHttp's onFailure
                // callback immediately. Cancellation owns that race rather
                // than surfacing a synthetic connection failure.
                if (cancellation.isCancelled()) throw RealtimeSessionException.SessionCancelled
                when (event) {
                    null -> Unit
                    QueuedEvent.Open -> return OkHttpRealtimeWebSocket(webSocket, events, registration)
                    is QueuedEvent.Message -> {
                        // A peer cannot validly send application data before
                        // completing the handshake. Treat it as a connection
                        // failure rather than exposing its content.
                        throw RealtimeSessionException.WebSocketConnectionFailed
                    }

                    QueuedEvent.Closed,
                    QueuedEvent.Failed,
                    -> throw RealtimeSessionException.WebSocketConnectionFailed
                }
            }
        } catch (error: RealtimeSessionException) {
            registration.close()
            webSocket.cancel()
            throw error
        }
    }

    private class OkHttpRealtimeWebSocket(
        private val webSocket: WebSocket,
        private val events: BoundedEventQueue,
        private val cancellationRegistration: CancellationRegistration,
    ) : RealtimeWebSocket {
        private val closed = AtomicBoolean(false)

        override fun sendText(value: String): Boolean = try {
            webSocket.send(value)
        } catch (_: RuntimeException) {
            false
        }

        override fun sendBinary(value: ByteArray): Boolean = try {
            webSocket.send(value.toByteString())
        } catch (_: RuntimeException) {
            false
        }

        override fun receive(timeoutMillis: Long): RealtimeWebSocketEvent? {
            if (events.isOverflowed()) return RealtimeWebSocketEvent.Failed
            while (true) {
                when (val event = poll(events, timeoutMillis)) {
                    null -> return null
                    QueuedEvent.Open -> Unit
                    is QueuedEvent.Message -> return event.value
                    QueuedEvent.Closed -> return RealtimeWebSocketEvent.Closed
                    QueuedEvent.Failed -> return RealtimeWebSocketEvent.Failed
                }
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                webSocket.close(NORMAL_CLOSURE, "")
            } catch (_: RuntimeException) {
                // Teardown must not replace the completed session outcome.
            } finally {
                cancellationRegistration.close()
            }
        }
    }

    /**
     * `OkHttpClient.connectTimeout` ends only TCP/TLS establishment. A peer
     * can still accept that connection and never answer the HTTP upgrade, so
     * the websocket handshake needs its own bounded monotonic deadline.
     */
    private class HandshakeDeadline private constructor(
        private val startedAtNanos: Long,
        private val durationNanos: Long,
    ) {
        fun remainingMillis(): Long? {
            val elapsed = System.nanoTime() - startedAtNanos
            if (elapsed >= durationNanos) return null
            val remainingNanos = durationNanos - elapsed
            return (remainingNanos + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND
        }

        companion object {
            fun after(millis: Long): HandshakeDeadline = HandshakeDeadline(
                startedAtNanos = System.nanoTime(),
                durationNanos = when {
                    millis <= 0L -> 0L
                    millis > Long.MAX_VALUE / NANOS_PER_MILLISECOND -> Long.MAX_VALUE
                    else -> millis * NANOS_PER_MILLISECOND
                },
            )
        }
    }

    private sealed interface QueuedEvent {
        val sizeBytes: Int

        data object Open : QueuedEvent {
            override val sizeBytes: Int = 0
        }

        data class Message(
            val value: RealtimeWebSocketEvent,
            override val sizeBytes: Int,
        ) : QueuedEvent

        data object Closed : QueuedEvent {
            override val sizeBytes: Int = 0
        }

        data object Failed : QueuedEvent {
            override val sizeBytes: Int = 0
        }
    }

    /**
     * Listener callbacks may outrun a paused lifecycle worker. Bound both the
     * number of frames and their aggregate payload size; an overflow cancels
     * the socket and becomes one redacted receive failure.
     */
    private class BoundedEventQueue {
        private val queue = LinkedBlockingQueue<QueuedEvent>(MAX_PENDING_EVENTS)
        private val queuedBytes = AtomicLong()
        private val overflowed = AtomicBoolean(false)
        private val lock = Any()

        fun offerOrCancel(event: QueuedEvent, webSocket: WebSocket) {
            if (!offer(event)) webSocket.cancel()
        }

        fun failAndCancel(webSocket: WebSocket) {
            overflowed.set(true)
            webSocket.cancel()
        }

        fun isOverflowed(): Boolean = overflowed.get()

        fun poll(timeoutMillis: Long): QueuedEvent? {
            val event = try {
                queue.poll(timeoutMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw RealtimeSessionException.SessionCancelled
            }
            if (event != null && event.sizeBytes != 0) queuedBytes.addAndGet(-event.sizeBytes.toLong())
            return event
        }

        private fun offer(event: QueuedEvent): Boolean = synchronized(lock) {
            if (overflowed.get()) return false
            val bytes = event.sizeBytes.toLong()
            if (bytes > MAX_PENDING_MESSAGE_BYTES || queuedBytes.get() > MAX_PENDING_MESSAGE_BYTES - bytes) {
                overflowed.set(true)
                return false
            }
            if (!queue.offer(event)) {
                overflowed.set(true)
                return false
            }
            if (bytes != 0L) queuedBytes.addAndGet(bytes)
            true
        }
    }

    private companion object {
        const val MAX_WEBSOCKET_MESSAGE_BYTES = 32 * 1024 * 1024
        const val MAX_PENDING_MESSAGE_BYTES = 32L * 1024L * 1024L
        const val MAX_PENDING_EVENTS = 64
        const val CONNECT_WAIT_SLICE_MILLIS = 50L
        const val DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 15_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NORMAL_CLOSURE = 1000

        fun poll(
            events: BoundedEventQueue,
            timeoutMillis: Long,
        ): QueuedEvent? = events.poll(timeoutMillis)

        fun newDefaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // A realtime session is finalized by the workflow timeout, not an
            // arbitrary per-socket read timeout.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}
