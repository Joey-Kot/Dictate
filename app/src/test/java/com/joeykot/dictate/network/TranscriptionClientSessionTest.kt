package com.joeykot.dictate.network

import com.joeykot.dictate.util.Diagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptionClientSessionTest {
    @Test
    fun cancellationBeforeOpeningAJobIsObservedByTheNewSession() {
        val client = newClient()
        val registration = client.registerJob(42L)

        client.cancel(42L)
        // The controller closes a queued registration when Future.cancel()
        // discards its worker. A worker that already captured it must still
        // see that cancellation if dispatch wins the race.
        registration.close()
        val session = client.openJob(registration)
        try {
            assertTrue(session.isCancellationRequested())
        } finally {
            session.close()
        }
    }

    @Test
    fun discardedQueuedCancellationDoesNotAffectANewJobWithTheSameId() {
        val client = newClient()
        val discarded = client.registerJob(42L)

        client.cancel(42L)
        discarded.close()

        val retry = client.registerJob(42L)
        val session = client.openJob(retry)
        try {
            assertFalse(session.isCancellationRequested())
        } finally {
            session.close()
            retry.close()
        }
    }

    @Test
    fun batchAbortDoesNotMasqueradeAsParentCancellationAndBlocksLateRequests() {
        val client = newClient()
        val session = client.openJob(43L)
        val audio = File.createTempFile("transcription-session-", ".wav", RuntimeEnvironment.getApplication().cacheDir)
        try {
            audio.writeBytes(byteArrayOf(1))
            val firstOperation = session.newOperationId()
            val secondOperation = session.newOperationId()
            assertEquals(43L, firstOperation.parentJobId)
            assertEquals(43L, secondOperation.parentJobId)
            assertNotEquals(firstOperation.requestId, secondOperation.requestId)

            session.cancelInFlightOperations()

            assertFalse(session.isCancellationRequested())
            assertEquals(
                TranscriptionClient.Result.Cancelled,
                session.transcribe(
                    secondOperation,
                    TranscriptionClient.Request(
                        endpoint = "not-an-endpoint",
                        apiKey = "key",
                        model = "model",
                        additionalFields = linkedMapOf(),
                        audioFile = audio,
                        mimeType = "audio/wav",
                    ),
                ),
            )
        } finally {
            audio.delete()
            session.close()
        }
    }

    private fun newClient(): TranscriptionClient =
        TranscriptionClient(Diagnostics(RuntimeEnvironment.getApplication()))
}
