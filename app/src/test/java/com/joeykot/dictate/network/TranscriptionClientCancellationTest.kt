package com.joeykot.dictate.network

import com.joeykot.dictate.util.Diagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptionClientCancellationTest {
    @Test
    fun parentCancellationCancelsEveryConcurrentOperationInOneSession() {
        val context = RuntimeEnvironment.getApplication()
        val audio = File.createTempFile("transcription-cancel-", ".wav", context.cacheDir).apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val workers = Executors.newFixedThreadPool(2)
        try {
            HoldingHttpServer(2).use { server ->
                val client = TranscriptionClient(Diagnostics(context))
                val session = client.openJob(81L)
                try {
                    val request = TranscriptionClient.Request(
                        endpoint = "${server.url}/v1/audio/transcriptions",
                        apiKey = "key",
                        model = "model",
                        additionalFields = linkedMapOf(),
                        audioFile = audio,
                        mimeType = "audio/wav",
                    )
                    val first = workers.submit<TranscriptionClient.Result> {
                        session.transcribe(session.newOperationId(), request)
                    }
                    val second = workers.submit<TranscriptionClient.Result> {
                        session.transcribe(session.newOperationId(), request)
                    }

                    assertTrue(server.requestsReceived.await(3, TimeUnit.SECONDS))
                    client.cancel(81L)
                    // The legacy single-connection map could only disconnect
                    // one operation here. Let any missed connection receive a
                    // valid response; it must still observe parent cancellation.
                    server.releaseResponses.countDown()

                    assertEquals(TranscriptionClient.Result.Cancelled, first.get(3, TimeUnit.SECONDS))
                    assertEquals(TranscriptionClient.Result.Cancelled, second.get(3, TimeUnit.SECONDS))
                } finally {
                    session.close()
                }
            }
        } finally {
            workers.shutdownNow()
            audio.delete()
        }
    }

    private class HoldingHttpServer(expectedRequests: Int) : AutoCloseable {
        private val server = ServerSocket(0, expectedRequests, InetAddress.getByName("127.0.0.1"))
        private val sockets = ConcurrentLinkedQueue<Socket>()
        val url = "http://127.0.0.1:${server.localPort}"
        val requestsReceived = CountDownLatch(expectedRequests)
        val releaseResponses = CountDownLatch(1)

        init {
            Thread({
                runCatching {
                    repeat(expectedRequests) {
                        val socket = server.accept()
                        sockets += socket
                        Thread({ handle(socket) }, "transcription-cancellation-server").apply {
                            isDaemon = true
                        }.start()
                    }
                }
            }, "transcription-cancellation-accept").apply {
                isDaemon = true
            }.start()
        }

        private fun handle(socket: Socket) {
            try {
                socket.soTimeout = 5_000
                consumeRequest(socket)
                requestsReceived.countDown()
                releaseResponses.await(5, TimeUnit.SECONDS)
                val body = "{\"text\":\"late\"}".toByteArray(Charsets.UTF_8)
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                    write(body)
                    flush()
                }
            } catch (_: Exception) {
                // Client disconnects are expected after cancellation.
            } finally {
                socket.close()
            }
        }

        private fun consumeRequest(socket: Socket) {
            val input = socket.getInputStream()
            val header = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                check(value >= 0)
                header.write(value)
                if (header.toString(Charsets.UTF_8.name()).endsWith("\r\n\r\n")) break
            }
            val length = header.toString(Charsets.UTF_8.name())
                .lineSequence()
                .first { it.startsWith("Content-Length:", ignoreCase = true) }
                .substringAfter(':')
                .trim()
                .toInt()
            var remaining = length
            val buffer = ByteArray(8 * 1024)
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                check(count > 0)
                remaining -= count
            }
        }

        override fun close() {
            releaseResponses.countDown()
            sockets.forEach { socket -> runCatching { socket.close() } }
            server.close()
        }
    }
}
