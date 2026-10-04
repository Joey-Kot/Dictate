package com.joeykot.dictate.accessibility

import android.accessibilityservice.AccessibilityService
import android.annotation.TargetApi
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import java.util.concurrent.Executors

/** Reads custom editors whose accessibility node does not expose its text or selection. */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
internal class Api33SelectedTextReader(
    private val service: AccessibilityService,
    private val shouldContinue: () -> Boolean,
    private val currentInputFocus: () -> AccessibilityNodeInfo?,
    private val isEditorInCurrentWindow: (String) -> Boolean,
    private val callback: (String?) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dictate-selection-reader").apply { isDaemon = true }
    }

    @Volatile
    private var active = true

    private var started = false
    private var initialEditorInfo: EditorInfo? = null
    private var initialFocus: AccessibilityNodeInfo? = null

    fun start() {
        if (started || !ensureActive()) return
        started = true
        val inputMethod = runCatching { service.inputMethod }.getOrNull()
        val editor = runCatching { inputMethod?.currentInputEditorInfo }.getOrNull()
        val connection = runCatching { inputMethod?.currentInputConnection }.getOrNull()
        val focus = runCatching(currentInputFocus).getOrNull()
        if (editor == null || connection == null || !isReadableTarget(editor, focus)) {
            complete(null)
            return
        }
        initialEditorInfo = editor
        initialFocus = focus

        executor.execute {
            if (!active) return@execute
            val selected = runCatching {
                // The selected range is always included. Zero context avoids both fetching
                // unrelated text and imposing a length limit on the user's selection.
                connection.getSurroundingText(0, 0, 0)?.let {
                    // These indices are local to text; offset may legitimately be unknown (-1).
                    selectedTextInRange(it.text, it.selectionStart, it.selectionEnd)
                }
            }.getOrNull()
            if (!active) return@execute
            mainHandler.post {
                if (!ensureActive()) return@post
                if (!isCurrentInputTarget()) {
                    // A stale null must not be treated as "no selection" and resend audio.
                    destroy()
                    return@post
                }
                complete(selected)
            }
        }
    }

    fun destroy() {
        active = false
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        initialEditorInfo = null
        initialFocus = null
    }

    private fun ensureActive(): Boolean {
        if (!active) return false
        if (!shouldContinue()) {
            destroy()
            return false
        }
        return true
    }

    private fun complete(selected: String?) {
        if (!ensureActive()) return
        destroy()
        callback(selected)
    }

    private fun isCurrentInputTarget(): Boolean = runCatching {
        val editor = initialEditorInfo ?: return@runCatching false
        if (service.inputMethod?.currentInputEditorInfo !== editor) return@runCatching false
        val focus = currentInputFocus()
        focus == initialFocus && isReadableTarget(editor, focus)
    }.getOrDefault(false)

    private fun isReadableTarget(editor: EditorInfo, focus: AccessibilityNodeInfo?): Boolean =
        runCatching {
            val packageName = editor.packageName?.takeIf(String::isNotBlank)
                ?: return@runCatching false
            if (editor.hasPasswordInputType() || !isEditorInCurrentWindow(packageName)) {
                return@runCatching false
            }
            if (focus == null) return@runCatching true
            focus.refresh() && focus.isVisibleToUser && focus.isEnabled &&
                !focus.isPassword && !focus.isShowingHintText &&
                (focus.packageName == null || focus.packageName.toString() == packageName)
        }.getOrDefault(false)

    private fun EditorInfo.hasPasswordInputType(): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }
}
