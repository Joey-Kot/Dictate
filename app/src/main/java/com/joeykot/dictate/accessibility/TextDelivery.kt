package com.joeykot.dictate.accessibility

import android.content.Context
import com.joeykot.dictate.util.ClipboardFallback

object TextDelivery {
    data class Outcome(
        val inserted: Boolean,
        val copied: Boolean,
        val copyAttempted: Boolean,
    )

    fun deliver(
        context: Context,
        text: String,
        alwaysCopyToClipboard: Boolean,
    ): Outcome {
        val inserted = DictateAccessibilityService.current()
            ?.tryInsertAtCurrentCursor(text) == true
        val copyAttempted = shouldCopyToClipboard(alwaysCopyToClipboard, inserted)
        val copied = copyAttempted && ClipboardFallback.copy(context, text)
        return Outcome(inserted, copied, copyAttempted)
    }
}

internal fun shouldCopyToClipboard(alwaysCopyToClipboard: Boolean, inserted: Boolean): Boolean =
    alwaysCopyToClipboard || !inserted
