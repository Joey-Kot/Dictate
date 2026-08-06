package com.joeykot.dictate.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.joeykot.dictate.DictateApplication
import com.joeykot.dictate.overlay.OverlayController

class DictateAccessibilityService : AccessibilityService() {
    private var overlayController: OverlayController? = null

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
        super.onDestroy()
    }

    fun tryInsertAtCurrentCursor(text: String): Boolean {
        val focused = findCurrentInputFocus() ?: return false
        return try {
            if (!focused.isEditable || !focused.isEnabled) return false
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
            }
            inserted
        } catch (_: Exception) {
            false
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

    companion object {
        @Volatile
        private var currentInstance: DictateAccessibilityService? = null

        fun current(): DictateAccessibilityService? = currentInstance
    }
}
