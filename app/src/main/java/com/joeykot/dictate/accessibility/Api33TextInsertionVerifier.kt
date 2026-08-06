package com.joeykot.dictate.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.annotation.TargetApi
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

@TargetApi(Build.VERSION_CODES.TIRAMISU)
internal class Api33TextInsertionVerifier(
    private val service: AccessibilityService,
    private val text: String,
    private val shouldContinue: () -> Boolean,
    private val callback: (TextInsertionResult) -> Unit,
) : PendingTextInsertionVerification {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dictate-text-verification").apply { isDaemon = true }
    }

    @Volatile
    private var active = true

    private var completed = false

    override fun start() {
        if (!ensureActiveOnMain()) return
        val accessibilityInputMethod = service.inputMethod
        if (accessibilityInputMethod == null) {
            complete(TextInsertionResult.failure("input_connection=method_unavailable"))
            return
        }
        val connection = accessibilityInputMethod.currentInputConnection
        if (connection == null) {
            complete(TextInsertionResult.failure("input_connection=no_active_connection"))
            return
        }

        val beforeLength = (text.length + CONFIRMATION_CONTEXT_PADDING)
            .coerceIn(MIN_CONFIRMATION_CONTEXT_LENGTH, MAX_CONFIRMATION_CONTEXT_LENGTH)
        executor.execute {
            if (!canContinue()) {
                mainHandler.post(::abandon)
                return@execute
            }
            val before = captureSnapshot(connection, beforeLength)
            mainHandler.post {
                if (!ensureActiveOnMain()) return@post
                val commitError = runCatching {
                    connection.commitText(text, 1, null)
                }.exceptionOrNull()
                if (commitError != null) {
                    complete(
                        TextInsertionResult.failure(
                            "input_connection=exception:${commitError.diagnosticName()}",
                        ),
                    )
                    return@post
                }
                if (before == null || !before.isValid() || text.isEmpty()) {
                    complete(
                        TextInsertionResult.unconfirmed(
                            TextInsertionMethod.INPUT_CONNECTION,
                            "input_connection=unconfirmed baseline=unavailable",
                        ),
                    )
                    return@post
                }
                scheduleVerification(
                    connection = connection,
                    beforeLength = beforeLength,
                    before = before,
                    index = 0,
                    observations = emptyList(),
                )
            }
        }
    }

    override fun destroy() {
        if (completed) return
        val shouldReportFailure = shouldContinue()
        active = false
        completed = true
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        if (shouldReportFailure) {
            callback(TextInsertionResult.failure("input_connection=service_destroyed"))
        }
    }

    private fun scheduleVerification(
        connection: InputMethod.AccessibilityInputConnection,
        beforeLength: Int,
        before: TextInsertionSnapshot?,
        index: Int,
        observations: List<TextInsertionObservation>,
    ) {
        if (!ensureActiveOnMain()) return
        mainHandler.postDelayed(
            {
                if (!ensureActiveOnMain()) return@postDelayed
                executor.execute {
                    if (!canContinue()) {
                        mainHandler.post(::abandon)
                        return@execute
                    }
                    val after = captureSnapshot(connection, beforeLength)
                    val observation = observeTextInsertion(before, after, text)
                    mainHandler.post {
                        handleObservation(
                            connection = connection,
                            beforeLength = beforeLength,
                            before = before,
                            index = index,
                            observations = observations + observation,
                        )
                    }
                }
            },
            INPUT_CONFIRMATION_DELAYS_MS[index],
        )
    }

    private fun handleObservation(
        connection: InputMethod.AccessibilityInputConnection,
        beforeLength: Int,
        before: TextInsertionSnapshot?,
        index: Int,
        observations: List<TextInsertionObservation>,
    ) {
        if (!ensureActiveOnMain()) return
        if (observations.last() == TextInsertionObservation.CONFIRMED) {
            complete(
                TextInsertionResult.success(
                    TextInsertionMethod.INPUT_CONNECTION,
                    "input_connection=confirmed observations=${observations.diagnosticSummary()}",
                ),
            )
            return
        }
        if (index < INPUT_CONFIRMATION_DELAYS_MS.lastIndex) {
            scheduleVerification(
                connection = connection,
                beforeLength = beforeLength,
                before = before,
                index = index + 1,
                observations = observations,
            )
            return
        }

        val result = if (shouldFallbackAfterObservations(observations)) {
            TextInsertionResult.failure(
                "input_connection=not_applied observations=${observations.diagnosticSummary()}",
            )
        } else {
            TextInsertionResult.unconfirmed(
                TextInsertionMethod.INPUT_CONNECTION,
                "input_connection=unconfirmed observations=${observations.diagnosticSummary()}",
            )
        }
        complete(result)
    }

    private fun captureSnapshot(
        connection: InputMethod.AccessibilityInputConnection,
        beforeLength: Int,
    ): TextInsertionSnapshot? = runCatching {
        connection.getSurroundingText(beforeLength, CONFIRMATION_AFTER_LENGTH, 0)?.let {
            TextInsertionSnapshot(
                text = it.text.toString(),
                selectionStart = it.selectionStart,
                selectionEnd = it.selectionEnd,
                offset = it.offset,
            )
        }
    }.getOrNull()

    private fun canContinue(): Boolean = active && shouldContinue()

    private fun ensureActiveOnMain(): Boolean {
        if (!active || completed) return false
        if (!shouldContinue()) {
            abandon()
            return false
        }
        return true
    }

    private fun complete(result: TextInsertionResult) {
        if (!ensureActiveOnMain()) return
        active = false
        completed = true
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        callback(result)
    }

    private fun abandon() {
        if (completed) return
        active = false
        completed = true
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        callback(TextInsertionResult.failure("input_connection=cancelled"))
    }

    private fun Throwable.diagnosticName(): String = javaClass.simpleName.ifBlank { "Throwable" }

    private fun List<TextInsertionObservation>.diagnosticSummary(): String =
        joinToString("|") { it.diagnosticName }

    private companion object {
        const val CONFIRMATION_CONTEXT_PADDING = 32
        const val MIN_CONFIRMATION_CONTEXT_LENGTH = 128
        const val MAX_CONFIRMATION_CONTEXT_LENGTH = 2_048
        const val CONFIRMATION_AFTER_LENGTH = 64
        val INPUT_CONFIRMATION_DELAYS_MS = longArrayOf(0L, 100L, 300L)
    }
}
