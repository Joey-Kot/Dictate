package com.joeykot.dictate.accessibility

import android.content.Context
import com.joeykot.dictate.util.ClipboardFallback

internal object TextDelivery {
    data class Outcome(
        val insertion: TextInsertionResult,
        val copied: Boolean,
        val copyAttempted: Boolean,
    ) {
        val inserted: Boolean
            get() = insertion.inserted
    }

    fun deliver(
        context: Context,
        text: String,
        alwaysCopyToClipboard: Boolean,
        shouldContinue: () -> Boolean,
        callback: (Outcome) -> Unit,
    ) {
        if (!shouldContinue()) return
        val service = DictateAccessibilityService.current()
        if (service == null) {
            completeDelivery(
                context = context,
                text = text,
                alwaysCopyToClipboard = alwaysCopyToClipboard,
                service = null,
                insertion = TextInsertionResult.failure("service=unavailable"),
                shouldContinue = shouldContinue,
                callback = callback,
            )
            return
        }
        service.tryInsertAtCurrentCursor(text, shouldContinue) { insertion ->
            completeDelivery(
                context = context,
                text = text,
                alwaysCopyToClipboard = alwaysCopyToClipboard,
                service = service,
                insertion = insertion,
                shouldContinue = shouldContinue,
                callback = callback,
            )
        }
    }

    private fun completeDelivery(
        context: Context,
        text: String,
        alwaysCopyToClipboard: Boolean,
        service: DictateAccessibilityService?,
        insertion: TextInsertionResult,
        shouldContinue: () -> Boolean,
        callback: (Outcome) -> Unit,
    ) {
        if (!shouldContinue()) return
        var finalInsertion = insertion
        val copyAttempted = shouldCopyToClipboard(alwaysCopyToClipboard, insertion.inserted)
        val copied = copyAttempted && ClipboardFallback.copy(context, text)
        val serviceAvailable = service != null && DictateAccessibilityService.current() === service
        if (
            shouldTryPasteFallback(
                directInsertionFailed = insertion.state == TextInsertionState.FAILED,
                copiedToClipboard = copied,
                serviceAvailable = serviceAvailable,
            )
        ) {
            finalInsertion = insertion.followedBy(checkNotNull(service).tryPasteAtCurrentCursor())
        }
        if (shouldContinue()) callback(Outcome(finalInsertion, copied, copyAttempted))
    }
}

internal fun shouldCopyToClipboard(alwaysCopyToClipboard: Boolean, inserted: Boolean): Boolean =
    alwaysCopyToClipboard || !inserted

internal fun shouldTryPasteFallback(
    directInsertionFailed: Boolean,
    copiedToClipboard: Boolean,
    serviceAvailable: Boolean,
): Boolean = directInsertionFailed && copiedToClipboard && serviceAvailable
