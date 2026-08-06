package com.joeykot.dictate.accessibility

internal enum class TextInsertionMethod(val diagnosticName: String) {
    INPUT_CONNECTION("input_connection"),
    SET_TEXT("set_text"),
    PASTE("paste"),
}

internal enum class TextInsertionState(val diagnosticName: String) {
    CONFIRMED("confirmed"),
    FAILED("failed"),
    UNCONFIRMED("unconfirmed"),
}

internal data class TextInsertionResult(
    val state: TextInsertionState,
    val method: TextInsertionMethod?,
    val attempts: List<String>,
) {
    val inserted: Boolean
        get() = state == TextInsertionState.CONFIRMED

    val unconfirmed: Boolean
        get() = state == TextInsertionState.UNCONFIRMED

    init {
        require((state == TextInsertionState.FAILED) == (method == null)) {
            "Confirmed and unconfirmed insertion results must identify their method"
        }
    }

    fun followedBy(fallback: TextInsertionResult): TextInsertionResult {
        // An unconfirmed commit may still arrive later, so another insertion could duplicate it.
        if (state != TextInsertionState.FAILED) return this
        return fallback.copy(attempts = attempts + fallback.attempts)
    }

    fun diagnosticSummary(): String {
        val selectedMethod = method?.diagnosticName ?: "none"
        val attemptSummary = attempts.ifEmpty { listOf("none") }.joinToString(",")
        return "state=${state.diagnosticName} method=$selectedMethod attempts=$attemptSummary"
    }

    companion object {
        fun success(method: TextInsertionMethod, attempt: String): TextInsertionResult =
            TextInsertionResult(
                state = TextInsertionState.CONFIRMED,
                method = method,
                attempts = listOf(attempt),
            )

        fun unconfirmed(method: TextInsertionMethod, attempt: String): TextInsertionResult =
            TextInsertionResult(
                state = TextInsertionState.UNCONFIRMED,
                method = method,
                attempts = listOf(attempt),
            )

        fun failure(attempt: String): TextInsertionResult = TextInsertionResult(
            state = TextInsertionState.FAILED,
            method = null,
            attempts = listOf(attempt),
        )
    }
}

internal data class TextInsertionSnapshot(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val offset: Int,
) {
    val absoluteSelectionStart: Int
        get() = offset + selectionStart

    private val hasValidSelection: Boolean
        get() = selectionStart in 0..text.length &&
            selectionEnd in selectionStart..text.length

    fun isValid(): Boolean = offset >= 0 && hasValidSelection
}

internal enum class TextInsertionObservation(val diagnosticName: String) {
    CONFIRMED("confirmed"),
    UNCHANGED("unchanged"),
    CHANGED("changed"),
    UNAVAILABLE("unavailable"),
}

internal interface PendingTextInsertionVerification {
    fun start()

    fun destroy()
}

internal fun observeTextInsertion(
    before: TextInsertionSnapshot?,
    after: TextInsertionSnapshot?,
    insertedText: String,
): TextInsertionObservation {
    if (before == null || after == null || insertedText.isEmpty()) {
        return TextInsertionObservation.UNAVAILABLE
    }
    if (!before.isValid() || !after.isValid()) return TextInsertionObservation.UNAVAILABLE

    val expectedCursor = before.absoluteSelectionStart + insertedText.length
    val actualCursor = after.absoluteSelectionStart
    val confirmationSuffix = insertedText.takeLast(MAX_CONFIRMATION_SUFFIX_LENGTH)
    val textBeforeCursor = after.text.substring(0, after.selectionStart)
    if (
        after.selectionStart == after.selectionEnd &&
        actualCursor == expectedCursor &&
        textBeforeCursor.endsWith(confirmationSuffix)
    ) {
        return TextInsertionObservation.CONFIRMED
    }

    return if (after == before) {
        TextInsertionObservation.UNCHANGED
    } else {
        TextInsertionObservation.CHANGED
    }
}

internal fun shouldFallbackAfterObservations(
    observations: List<TextInsertionObservation>,
): Boolean {
    if (observations.lastOrNull() != TextInsertionObservation.UNCHANGED) return false
    val available = observations.filter { it != TextInsertionObservation.UNAVAILABLE }
    return available.isNotEmpty() && available.all { it == TextInsertionObservation.UNCHANGED }
}

internal fun editableTextForInsertion(
    nodeText: CharSequence?,
    isShowingHintText: Boolean,
): String = if (isShowingHintText) "" else nodeText?.toString().orEmpty()

private const val MAX_CONFIRMATION_SUFFIX_LENGTH = 256
