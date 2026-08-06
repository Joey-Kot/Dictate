package com.joeykot.dictate.accessibility

import android.accessibilityservice.AccessibilityService
import android.annotation.TargetApi
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.joeykot.dictate.DictateApplication
import com.joeykot.dictate.overlay.OverlayController

class DictateAccessibilityService : AccessibilityService() {
    private var overlayController: OverlayController? = null
    private var activeVerification: PendingTextInsertionVerification? = null
    private var activeVerificationToken: Any? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        currentInstance = this
        val app = application as DictateApplication
        overlayController = OverlayController(
            service = this,
            voiceJobs = app.voiceJobController,
            settingsRepository = app.settingsRepository,
        ).also { it.show() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        overlayController?.onConfigurationChanged()
    }

    override fun onDestroy() {
        overlayController?.remove()
        overlayController = null
        if (currentInstance === this) currentInstance = null
        activeVerification?.destroy()
        activeVerification = null
        activeVerificationToken = null
        super.onDestroy()
    }

    internal fun tryInsertAtCurrentCursor(
        text: String,
        shouldContinue: () -> Boolean,
        callback: (TextInsertionResult) -> Unit,
    ) {
        if (!shouldContinue()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            tryCommitViaInputConnection(text, shouldContinue, callback)
            return
        }
        callback(trySetTextAtCurrentCursor(text))
    }

    internal fun tryPasteAtCurrentCursor(): TextInsertionResult {
        val focused = findCurrentInputFocus()
            ?: return TextInsertionResult.failure("paste=no_input_focus")
        return try {
            val actionSupported = focused.supportsAction(AccessibilityNodeInfo.ACTION_PASTE)
            val metadata = focused.diagnosticMetadata(actionSupported)
            if (!focused.isEnabled) {
                TextInsertionResult.failure("paste=node_disabled $metadata")
            } else if (!focused.isEditable && !actionSupported) {
                TextInsertionResult.failure("paste=node_not_editable $metadata")
            } else {
                val pasted = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                if (pasted) {
                    TextInsertionResult.success(
                        TextInsertionMethod.PASTE,
                        "paste=success $metadata",
                    )
                } else {
                    TextInsertionResult.failure("paste=rejected $metadata")
                }
            }
        } catch (error: Exception) {
            TextInsertionResult.failure("paste=exception:${error.diagnosticName()}")
        } finally {
            focused.recycleBeforeApi33()
        }
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun tryCommitViaInputConnection(
        text: String,
        shouldContinue: () -> Boolean,
        callback: (TextInsertionResult) -> Unit,
    ) {
        if (activeVerification != null) {
            callback(
                TextInsertionResult.failure("input_connection=busy")
                    .followedBy(trySetTextAtCurrentCursor(text)),
            )
            return
        }

        val token = Any()
        activeVerificationToken = token
        val verification: PendingTextInsertionVerification = Api33TextInsertionVerifier(
            service = this,
            text = text,
            shouldContinue = shouldContinue,
            callback = verificationComplete@{ result ->
                if (activeVerificationToken !== token) return@verificationComplete
                activeVerification = null
                activeVerificationToken = null
                if (!shouldContinue()) return@verificationComplete
                val finalResult = if (
                    result.state == TextInsertionState.FAILED && currentInstance === this
                ) {
                    result.followedBy(trySetTextAtCurrentCursor(text))
                } else {
                    result
                }
                callback(finalResult)
            },
        )
        activeVerification = verification
        verification.start()
    }

    private fun trySetTextAtCurrentCursor(text: String): TextInsertionResult {
        val focused = findCurrentInputFocus()
            ?: return TextInsertionResult.failure("set_text=no_input_focus")
        return try {
            val actionSupported = focused.supportsAction(AccessibilityNodeInfo.ACTION_SET_TEXT)
            val metadata = focused.diagnosticMetadata(actionSupported)
            if (!focused.isEnabled) {
                return TextInsertionResult.failure("set_text=node_disabled $metadata")
            }
            if (!focused.isEditable && !actionSupported) {
                return TextInsertionResult.failure("set_text=node_not_editable $metadata")
            }
            val existing = editableTextForInsertion(
                nodeText = focused.text,
                isShowingHintText = focused.isShowingHintText,
            )
            val selectionStart = focused.textSelectionStart
            val cursor = if (selectionStart in 0..existing.length) selectionStart else existing.length
            val updated = existing.substring(0, cursor) + text + existing.substring(cursor)
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    updated,
                )
            }
            val inserted = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (inserted) {
                val newCursor = cursor + text.length
                val selection = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursor)
                }
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
                TextInsertionResult.success(
                    TextInsertionMethod.SET_TEXT,
                    "set_text=success $metadata",
                )
            } else {
                TextInsertionResult.failure("set_text=rejected $metadata")
            }
        } catch (error: Exception) {
            TextInsertionResult.failure("set_text=exception:${error.diagnosticName()}")
        } finally {
            focused.recycleBeforeApi33()
        }
    }

    private fun findCurrentInputFocus(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { root ->
            try {
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
            } finally {
                root.recycleBeforeApi33()
            }
        }
        for (window in windows.orEmpty().sortedByDescending { it.layer }) {
            val root = window.root ?: continue
            val focused = try {
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            } finally {
                root.recycleBeforeApi33()
            }
            if (focused != null) return focused
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.recycleBeforeApi33() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) recycle()
    }

    private fun AccessibilityNodeInfo.supportsAction(actionId: Int): Boolean =
        actionList.any { it.id == actionId }

    private fun AccessibilityNodeInfo.diagnosticMetadata(actionSupported: Boolean): String {
        val packageName = packageName.diagnosticIdentifier()
        val className = className.diagnosticIdentifier()
        return "package=$packageName class=$className editable=$isEditable " +
            "enabled=$isEnabled action_supported=$actionSupported"
    }

    private fun CharSequence?.diagnosticIdentifier(): String = this
        ?.toString()
        ?.take(MAX_DIAGNOSTIC_IDENTIFIER_LENGTH)
        ?.ifBlank { "unknown" }
        ?: "unknown"

    private fun Throwable.diagnosticName(): String = javaClass.simpleName.ifBlank { "Throwable" }

    companion object {
        private const val MAX_DIAGNOSTIC_IDENTIFIER_LENGTH = 120

        @Volatile
        private var currentInstance: DictateAccessibilityService? = null

        fun current(): DictateAccessibilityService? = currentInstance
    }
}
