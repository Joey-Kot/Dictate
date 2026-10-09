package com.joeykot.dictate.network

import com.joeykot.dictate.model.SegmentedUploadConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SegmentedUploadBatchRunnerTest {
    @Test
    fun rejectsOutOfRangeConcurrencyBeforeCreatingAnyWorkerThreads() {
        var executorCreated = false
        val executed = AtomicInteger()
        val runner = SegmentedUploadBatchRunner {
            executorCreated = true
            Executors.newSingleThreadExecutor()
        }

        val result = runner.run(
            items = listOf(SegmentedUploadBatchRunner.Item(0, Unit)),
            maxConcurrency = SegmentedUploadConfig.MAX_CONCURRENCY + 1,
            isParentCancellationRequested = { false },
            cancelInFlight = {},
        ) {
            executed.incrementAndGet()
            SegmentedUploadBatchRunner.TaskResult.Success("unexpected")
        }

        assertEquals(SegmentedUploadBatchRunner.Result.InvalidConcurrency, result)
        assertFalse(executorCreated)
        assertEquals(0, executed.get())
    }

    @Test
    fun parentCancellationBeforeDispatchStartsNoTasks() {
        val executed = AtomicInteger()
        val result = SegmentedUploadBatchRunner().run(
            items = listOf(SegmentedUploadBatchRunner.Item(0, Unit)),
            maxConcurrency = 1,
            isParentCancellationRequested = { true },
            cancelInFlight = {},
        ) {
            executed.incrementAndGet()
            SegmentedUploadBatchRunner.TaskResult.Success("unexpected")
        }

        assertEquals(SegmentedUploadBatchRunner.Result.Cancelled, result)
        assertEquals(0, executed.get())
    }

    @Test
    fun limitsCompleteWorkflowsAndConcatenatesCompletedTextInSourceOrder() {
        val runner = SegmentedUploadBatchRunner()
        val firstWaveStarted = CountDownLatch(2)
        val releaseFirstWave = CountDownLatch(1)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val outer = Executors.newSingleThreadExecutor()
        try {
            val future = outer.submit<SegmentedUploadBatchRunner.Result<String>> {
                runner.run(
                    items = (0..3).map { SegmentedUploadBatchRunner.Item(it, it) },
                    maxConcurrency = 2,
                    isParentCancellationRequested = { false },
                    cancelInFlight = {},
                ) { item ->
                    val current = active.incrementAndGet()
                    peak.accumulateAndGet(current, ::maxOf)
                    try {
                        if (item.index < 2) {
                            firstWaveStarted.countDown()
                            check(releaseFirstWave.await(3, TimeUnit.SECONDS))
                        }
                        SegmentedUploadBatchRunner.TaskResult.Success(item.index.toString())
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }

            assertTrue(firstWaveStarted.await(3, TimeUnit.SECONDS))
            assertEquals(2, peak.get())
            releaseFirstWave.countDown()

            val result = future.get(3, TimeUnit.SECONDS)
            assertEquals("0123", (result as SegmentedUploadBatchRunner.Result.Success).text)
            assertTrue("peak=${peak.get()}", peak.get() <= 2)
        } finally {
            releaseFirstWave.countDown()
            outer.shutdownNow()
        }
    }

    @Test
    fun failureStopsNewWorkCancelsStartedSiblingsAndDrainsThem() {
        val runner = SegmentedUploadBatchRunner()
        val started = mutableListOf<Int>()
        val lock = Any()
        val initialTasksStarted = CountDownLatch(2)
        val releaseFailure = CountDownLatch(1)
        val releaseSibling = CountDownLatch(1)
        val cancelCalls = AtomicInteger()
        val outer = Executors.newSingleThreadExecutor()
        try {
            val future = outer.submit<SegmentedUploadBatchRunner.Result<String>> {
                runner.run(
                    items = (0..2).map { SegmentedUploadBatchRunner.Item(it, it) },
                    maxConcurrency = 2,
                    isParentCancellationRequested = { false },
                    cancelInFlight = {
                        cancelCalls.incrementAndGet()
                        releaseSibling.countDown()
                    },
                ) { item ->
                    synchronized(lock) { started += item.index }
                    when (item.index) {
                        0 -> {
                            initialTasksStarted.countDown()
                            check(releaseFailure.await(3, TimeUnit.SECONDS))
                            SegmentedUploadBatchRunner.TaskResult.Failure("segment zero failed")
                        }

                        1 -> {
                            initialTasksStarted.countDown()
                            check(releaseSibling.await(3, TimeUnit.SECONDS))
                            SegmentedUploadBatchRunner.TaskResult.Cancelled
                        }

                        else -> error("A task was submitted after the first failure")
                    }
                }
            }

            assertTrue(initialTasksStarted.await(3, TimeUnit.SECONDS))
            releaseFailure.countDown()
            val result = future.get(3, TimeUnit.SECONDS)

            result as SegmentedUploadBatchRunner.Result.Failure
            assertEquals(0, result.index)
            assertEquals("segment zero failed", result.error)
            assertEquals(1, cancelCalls.get())
            assertEquals(listOf(0, 1), synchronized(lock) { started.sorted() })
        } finally {
            releaseFailure.countDown()
            releaseSibling.countDown()
            outer.shutdownNow()
        }
    }

    @Test
    fun realFailureOverridesSiblingCancellationThatCompletesFirst() {
        val runner = SegmentedUploadBatchRunner()
        val tasksStarted = CountDownLatch(2)
        val releaseCancelledTask = CountDownLatch(1)
        val releaseFailure = CountDownLatch(1)
        val outer = Executors.newSingleThreadExecutor()
        try {
            val future = outer.submit<SegmentedUploadBatchRunner.Result<String>> {
                runner.run(
                    items = (0..1).map { SegmentedUploadBatchRunner.Item(it, it) },
                    maxConcurrency = 2,
                    isParentCancellationRequested = { false },
                    cancelInFlight = { releaseFailure.countDown() },
                ) { item ->
                    tasksStarted.countDown()
                    when (item.index) {
                        0 -> {
                            check(releaseFailure.await(3, TimeUnit.SECONDS))
                            SegmentedUploadBatchRunner.TaskResult.Failure("actual failure")
                        }

                        1 -> {
                            check(releaseCancelledTask.await(3, TimeUnit.SECONDS))
                            SegmentedUploadBatchRunner.TaskResult.Cancelled
                        }

                        else -> error("Unexpected segment")
                    }
                }
            }

            assertTrue(tasksStarted.await(3, TimeUnit.SECONDS))
            releaseCancelledTask.countDown()
            val result = future.get(3, TimeUnit.SECONDS)

            result as SegmentedUploadBatchRunner.Result.Failure
            assertEquals(0, result.index)
            assertEquals("actual failure", result.error)
        } finally {
            releaseCancelledTask.countDown()
            releaseFailure.countDown()
            outer.shutdownNow()
        }
    }

    @Test
    fun parentCancellationWinsOverAnEarlierSegmentFailureAfterDrain() {
        val runner = SegmentedUploadBatchRunner()
        val parentCancelled = AtomicBoolean(false)
        val cancellationRequestedAfterFailure = CountDownLatch(1)
        val siblingReleased = CountDownLatch(1)
        val tasksStarted = CountDownLatch(2)
        val outer = Executors.newSingleThreadExecutor()
        try {
            val future = outer.submit<SegmentedUploadBatchRunner.Result<String>> {
                runner.run(
                    items = (0..1).map { SegmentedUploadBatchRunner.Item(it, it) },
                    maxConcurrency = 2,
                    isParentCancellationRequested = parentCancelled::get,
                    cancelInFlight = { cancellationRequestedAfterFailure.countDown() },
                ) { item ->
                    tasksStarted.countDown()
                    when (item.index) {
                        0 -> {
                            SegmentedUploadBatchRunner.TaskResult.Failure("network failure")
                        }

                        1 -> {
                            check(siblingReleased.await(3, TimeUnit.SECONDS))
                            SegmentedUploadBatchRunner.TaskResult.Cancelled
                        }

                        else -> error("Unexpected segment")
                    }
                }
            }

            assertTrue(tasksStarted.await(3, TimeUnit.SECONDS))
            assertTrue(cancellationRequestedAfterFailure.await(3, TimeUnit.SECONDS))
            parentCancelled.set(true)
            siblingReleased.countDown()
            assertEquals(SegmentedUploadBatchRunner.Result.Cancelled, future.get(3, TimeUnit.SECONDS))
        } finally {
            siblingReleased.countDown()
            outer.shutdownNow()
        }
    }
}
