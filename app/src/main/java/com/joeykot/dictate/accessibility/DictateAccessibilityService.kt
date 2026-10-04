package com.joeykot.dictate.accessibility

import android.accessibilityservice.AccessibilityService
import android.annotation.TargetApi
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.joeykot.dictate.DictateApplication
import com.joeykot.dictate.overlay.OverlayController

class DictateAccessibilityService : AccessibilityService() {
    private var overlayController: OverlayController? = null
    private var activeVerification: PendingTextInsertionVerification? = null
    private var activeVerificationToken: Any? = null
    private var selectionSource: AccessibilityNodeInfo? = null
    private var activeSelectionRead: Api33SelectedTextReader? = null
    private var selectionReadToken: Any? = null
    private var selectionRevision = 0L

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                selectionRevision++
                clearSelectionSource()
                // Keep only a node handle, never an old text snapshot. selectedText refreshes it.
                selectionSource = runCatching { event.source }.getOrNull()
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                selectionRevision++
                val source = runCatching { event.source }.getOrNull()
                if (source != selectionSource) clearSelectionSource()
                source?.recycleBeforeApi33()
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            -> {
                val source = selectionSource
                if (source != null && !runCatching { source.refresh() }.getOrDefault(false)) {
                    clearSelectionSource()
                }
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        overlayController?.onConfigurationChanged()
    }

    fun refreshOverlayAppearance() {
        overlayController?.refreshAppearance()
    }

    override fun onDestroy() {
        selectionReadToken = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activeSelectionRead?.destroy()
            activeSelectionRead = null
        }
        clearSelectionSource()
        overlayController?.remove()
        overlayController = null
        if (currentInstance === this) currentInstance = null
        activeVerification?.destroy()
        activeVerification = null
        activeVerificationToken = null
        super.onDestroy()
    }

    /** Custom editors may expose their selection only through the current input connection. */
    fun readSelectedText(shouldContinue: () -> Boolean, callback: (String?) -> Unit) {
        selectionReadToken = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activeSelectionRead?.destroy()
            activeSelectionRead = null
        }
        if (!shouldContinue()) return
        val selected = selectedText()
        if (selected != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            callback(selected)
            return
        }

        val token = Any()
        val revision = selectionRevision
        var originalWindows: Set<Int>? = null
        selectionReadToken = token
        val reader = Api33SelectedTextReader(
            service = this,
            shouldContinue = {
                selectionReadToken === token && selectionRevision == revision && shouldContinue()
            },
            currentInputFocus = ::findCurrentInputFocus,
            isEditorInCurrentWindow = { packageName ->
                val windows = currentSelectionEditorWindows(packageName)
                if (originalWindows == null) originalWindows = windows
                windows != null && windows == originalWindows
            },
            callback = { result ->
                if (selectionReadToken === token) {
                    selectionReadToken = null
                    activeSelectionRead = null
                    callback(result)
                }
            },
        )
        activeSelectionRead = reader
        reader.start()
    }

    private fun currentSelectionEditorWindows(packageName: String): Set<Int>? {
        val roots = currentTextRoots()
        try {
            val windowIds = roots.filter { it.packageName?.toString() == packageName }.map { it.windowId }.toSet()
            if (windowIds.isEmpty()) return null
            val source = selectionSource ?: return windowIds
            if (source.windowId !in windowIds || !source.refresh()) return null
            // A selection event from a different, read-only node must not resurrect an old
            // selection in the editor that happens to retain input focus in this window.
            val focused = findCurrentInputFocus()
            return try {
                val isCurrentFocus = source == focused || (focused == null && source.isFocused)
                windowIds.takeIf {
                    isCurrentFocus && source.isVisibleToUser && !source.isPassword && !source.isShowingHintText
                }
            } finally {
                focused?.recycleBeforeApi33()
            }
        } catch (_: RuntimeException) {
            return null
        } finally {
            roots.forEach { it.recycleBeforeApi33() }
        }
    }

    /** Returns a snapshot of the current selection without changing the user's clipboard. */
    fun selectedText(): String? {
        val roots = currentTextRoots()
        try {
            val source = selectionSource
            if (source != null) {
                val current = runCatching {
                    roots.any { it.windowId == source.windowId } && source.refresh()
                }.getOrDefault(false)
                // A collapsed latest selection is authoritative too: do not fall through to
                // another node that still exposes an unrelated old selection in this window.
                if (current) return source.currentSelectedText()
                clearSelectionSource()
            }
            for (root in roots) {
                for (focusType in intArrayOf(
                    AccessibilityNodeInfo.FOCUS_INPUT,
                    AccessibilityNodeInfo.FOCUS_ACCESSIBILITY,
                )) {
                    val focused = runCatching { root.findFocus(focusType) }.getOrNull() ?: continue
                    try {
                        focused.currentSelectedText(refreshFirst = true)?.let { return it }
                    } finally {
                        focused.recycleBeforeApi33()
                    }
                }
                // Read-only selectable TextViews and WebViews need not hold input focus.
                findSelectedTextInTree(root)?.let { return it }
            }
            return null
        } finally {
            roots.forEach { it.recycleBeforeApi33() }
        }
    }

    private fun AccessibilityNodeInfo.currentSelectedText(refreshFirst: Boolean = false): String? = runCatching {
        // Focus lookups can return a cached node with no range even though it now has a selection.
        if (refreshFirst && !refresh()) return@runCatching null
        if (!isVisibleToUser || isPassword || isShowingHintText) return@runCatching null
        val selected = selectedTextInRange(text, textSelectionStart, textSelectionEnd) ?: return@runCatching null
        if (refreshFirst) return@runCatching selected
        // Nodes returned by window/tree lookups may themselves come from Android's cache.
        if (!refresh() || !isVisibleToUser || isPassword || isShowingHintText) return@runCatching null
        selectedTextInRange(text, textSelectionStart, textSelectionEnd)
    }.getOrNull()

    private fun findSelectedTextInTree(root: AccessibilityNodeInfo): String? {
        var remaining = MAX_SELECTION_SEARCH_NODES
        fun visit(node: AccessibilityNodeInfo, depth: Int): String? {
            if (remaining-- <= 0 || depth > MAX_SELECTION_SEARCH_DEPTH) return null
            node.currentSelectedText()?.let { return it }
            for (index in 0 until node.childCount) {
                if (remaining <= 0) break
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                try {
                    visit(child, depth + 1)?.let { return it }
                } finally {
                    child.recycleBeforeApi33()
                }
            }
            return null
        }
        return runCatching { visit(root, 0) }.getOrNull()
    }

    private fun clearSelectionSource() {
        selectionSource?.recycleBeforeApi33()
        selectionSource = null
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
            currentInputFocus = ::findCurrentInputFocus,
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
            val replacement = replaceCurrentSelection(
                existing = existing,
                selectionStart = focused.textSelectionStart,
                selectionEnd = focused.textSelectionEnd,
                insertedText = text,
            ) ?: return TextInsertionResult.failure("set_text=selection_unavailable $metadata")
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    replacement.text,
                )
            }
            val inserted = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (inserted) {
                val newCursor = replacement.cursor
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
        val roots = currentTextRoots()
        try {
            for (root in roots) {
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
            }
        } finally {
            roots.forEach { it.recycleBeforeApi33() }
        }
        return null
    }

    private fun currentTextRoots(): List<AccessibilityNodeInfo> {
        val visibleWindows = runCatching { windows.orEmpty() }.getOrDefault(emptyList())
        try {
            val contentWindows = visibleWindows
                .filter {
                    it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                        it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD
                }
                .sortedByDescending { it.layer }
            val current = contentWindows.filter { it.isActive }
                .ifEmpty { contentWindows.filter { it.isFocused } }
                .ifEmpty {
                    contentWindows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.take(1)
                }
            val roots = current.mapNotNull { runCatching { it.root }.getOrNull() }
            if (roots.isNotEmpty()) return roots
            val root = runCatching { rootInActiveWindow }.getOrNull() ?: return emptyList()
            val window = visibleWindows.firstOrNull { it.id == root.windowId }
            if (window != null && (
                    window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                        window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                    )
            ) {
                root.recycleBeforeApi33()
                return emptyList()
            }
            return listOf(root)
        } finally {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                visibleWindows.forEach { it.recycle() }
            }
        }
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
        private const val MAX_SELECTION_SEARCH_NODES = 1_024
        private const val MAX_SELECTION_SEARCH_DEPTH = 64

        @Volatile
        private var currentInstance: DictateAccessibilityService? = null

        fun current(): DictateAccessibilityService? = currentInstance
    }
}
