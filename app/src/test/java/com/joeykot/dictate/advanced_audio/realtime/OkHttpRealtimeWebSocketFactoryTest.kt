package com.joeykot.dictate.advanced_audio.realtime

import com.joeykot.dictate.advanced_audio.http.AdvancedCancellationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OkHttpRealtimeWebSocketFactoryTest {
    @Test
    fun handshakeThatNeverUpgradesFailsAtTheBoundedDeadline() {
        HangingHandshakeServer().use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val failure = executor.submit<Throwable> {
                    captureFailure {
                        OkHttpRealtimeWebSocketFactory(handshakeTimeoutMillis = 250L).connect(
                            RealtimeWebSocketRequest("http://127.0.0.1:${server.port}/realtime", emptyList()),
                            AdvancedCancellationSource().token,
                        )
                    }
                }

                assertTrue(server.accepted.await(3L, TimeUnit.SECONDS))
                assertEquals(
                    RealtimeSessionException.WebSocketConnectionFailed,
                    failure.get(3L, TimeUnit.SECONDS),
                )
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun cancellationInterruptsAHandshakeBeforeItsDeadline() {
        HangingHandshakeServer().use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val cancellation = AdvancedCancellationSource()
            try {
                val failure = executor.submit<Throwable> {
                    captureFailure {
                        OkHttpRealtimeWebSocketFactory(handshakeTimeoutMillis = 10_000L).connect(
                            RealtimeWebSocketRequest("http://127.0.0.1:${server.port}/realtime", emptyList()),
                            cancellation.token,
                        )
                    }
                }

                assertTrue(server.accepted.await(3L, TimeUnit.SECONDS))
                cancellation.cancel()
                assertEquals(
                    RealtimeSessionException.SessionCancelled,
                    failure.get(3L, TimeUnit.SECONDS),
                )
            } finally {
                executor.shutdownNow()
            }
        }
    }

    private fun captureFailure(block: () -> Unit): Throwable = try {
        block()
        error("operation unexpectedly completed")
    } catch (error: Throwable) {
        error
    }

    private class HangingHandshakeServer : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int = server.localPort
        val accepted = java.util.concurrent.CountDownLatch(1)

        @Volatile
        private var activeSocket: Socket? = null

        private val thread = Thread(
            {
                try {
                    server.accept().use { socket ->
                        activeSocket = socket
                        readHeaders(socket)
                        accepted.countDown()
                        while (!socket.isClosed) Thread.sleep(25L)
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (_: Exception) {
                    // Closing the server or cancelling the client ends this
                    // intentionally incomplete handshake.
                }
            },
            "realtime-websocket-handshake-test",
        ).apply {
            isDaemon = true
            start()
        }

        override fun close() {
            activeSocket?.close()
            server.close()
            thread.interrupt()
        }

        private fun readHeaders(socket: Socket) {
            val output = ByteArrayOutputStream()
            val input = socket.getInputStream()
            while (true) {
                val value = input.read()
                check(value >= 0) { "connection closed before websocket request headers" }
                output.write(value)
                check(output.size() <= 16_384) { "websocket request headers exceed test limit" }
                if (output.toString(Charsets.ISO_8859_1.name()).endsWith("\r\n\r\n")) return
            }
        }
    }
}
