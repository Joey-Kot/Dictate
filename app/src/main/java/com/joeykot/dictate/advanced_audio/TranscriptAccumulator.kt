package com.joeykot.dictate.advanced_audio

/** A normalized transcript event accepted by [TranscriptAccumulator]. */
sealed interface TranscriptAction {
    val wireName: String

    data object Ignore : TranscriptAction {
        override val wireName: String = "ignore"
    }

    data class AppendDelta(val text: String) : TranscriptAction {
        override val wireName: String = "append_delta"
    }

    data class ReplacePartial(val text: String) : TranscriptAction {
        override val wireName: String = "replace_partial"
    }

    data class CommitSegment(val text: String) : TranscriptAction {
        override val wireName: String = "commit_segment"
    }

    data class SetFinalText(val text: String) : TranscriptAction {
        override val wireName: String = "set_final_text"
    }

    data object Complete : TranscriptAction {
        override val wireName: String = "complete"
    }

    data class Fail(val message: String) : TranscriptAction {
        override val wireName: String = "fail"
    }
}

/** Public lifecycle state of a [TranscriptAccumulator]. */
enum class TranscriptAccumulatorState {
    OPEN,
    COMPLETE,
    FAILED,
}

/** Errors from transcript assembly and finalization. */
sealed class TranscriptAccumulatorException(message: String) : IllegalStateException(message) {
    data object IncompleteStream : TranscriptAccumulatorException(
        "cannot return transcript: stream ended without an explicit `complete` action",
    )

    data class StreamFailed(val failureMessage: String) :
        TranscriptAccumulatorException("stream failed: $failureMessage")

    data class ActionAfterComplete(val action: String) :
        TranscriptAccumulatorException("cannot apply `$action`: stream is already complete")

    data class ActionAfterFailure(
        val action: String,
        val failureMessage: String,
    ) : TranscriptAccumulatorException(
        "cannot apply `$action`: stream has already failed: $failureMessage",
    )
}

/**
 * Collects normalized stream events into one final transcript.
 *
 * No spaces or separators are inserted: providers decide whether their
 * deltas and committed segments contain surrounding whitespace. Text is only
 * available after an explicit [TranscriptAction.Complete] action.
 */
class TranscriptAccumulator {
    private val committed = StringBuilder()
    private val partial = StringBuilder()
    private var authoritativeFinal: String? = null
    private var internalState: InternalState = InternalState.Open

    val state: TranscriptAccumulatorState
        get() = when (internalState) {
            InternalState.Open -> TranscriptAccumulatorState.OPEN
            InternalState.Complete -> TranscriptAccumulatorState.COMPLETE
            is InternalState.Failed -> TranscriptAccumulatorState.FAILED
        }

    val isComplete: Boolean
        get() = internalState === InternalState.Complete

    val committedText: String
        get() = committed.toString()

    val partialText: String
        get() = partial.toString()

    val authoritativeFinalText: String?
        get() = authoritativeFinal

    val failureMessage: String?
        get() = (internalState as? InternalState.Failed)?.message

    fun apply(action: TranscriptAction) {
        ensureOpen(action.wireName)
        when (action) {
            TranscriptAction.Ignore -> Unit
            is TranscriptAction.AppendDelta -> partial.append(action.text)
            is TranscriptAction.ReplacePartial -> partial.replace(0, partial.length, action.text)
            is TranscriptAction.CommitSegment -> {
                committed.append(action.text)
                partial.setLength(0)
            }

            is TranscriptAction.SetFinalText -> authoritativeFinal = action.text
            TranscriptAction.Complete -> internalState = InternalState.Complete
            is TranscriptAction.Fail -> {
                val message = nonemptyFailureMessage(action.message)
                internalState = InternalState.Failed(message)
                throw TranscriptAccumulatorException.StreamFailed(message)
            }
        }
    }

    fun appendDelta(text: String) = apply(TranscriptAction.AppendDelta(text))

    fun replacePartial(text: String) = apply(TranscriptAction.ReplacePartial(text))

    fun commitSegment(text: String) = apply(TranscriptAction.CommitSegment(text))

    fun setFinalText(text: String) = apply(TranscriptAction.SetFinalText(text))

    fun complete() = apply(TranscriptAction.Complete)

    fun fail(message: String) = apply(TranscriptAction.Fail(message))

    fun finalText(): String = when (val state = internalState) {
        InternalState.Open -> throw TranscriptAccumulatorException.IncompleteStream
        InternalState.Complete -> authoritativeFinal ?: accumulatedText()
        is InternalState.Failed -> throw TranscriptAccumulatorException.StreamFailed(state.message)
    }

    /** Kotlin does not consume the receiver; the result matches Rust's owned finalization API. */
    fun intoFinalText(): String = finalText()

    private fun ensureOpen(action: String) {
        when (val state = internalState) {
            InternalState.Open -> Unit
            InternalState.Complete -> throw TranscriptAccumulatorException.ActionAfterComplete(action)
            is InternalState.Failed -> throw TranscriptAccumulatorException.ActionAfterFailure(action, state.message)
        }
    }

    private fun accumulatedText(): String = buildString {
        append(committed)
        append(partial)
    }

    private sealed interface InternalState {
        data object Open : InternalState

        data object Complete : InternalState

        data class Failed(val message: String) : InternalState
    }
}

private fun nonemptyFailureMessage(message: String): String = if (message.trim().isEmpty()) {
    "server reported stream failure without a message"
} else {
    message
}
