package com.joeykot.dictate.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [32],
    application = Application::class,
    shadows = [SelectionServiceShadow::class, SelectionNodeShadow::class],
)
class DictateAccessibilityServiceTest {
    private lateinit var service: DictateAccessibilityService

    @Before
    fun setUp() {
        SelectionServiceShadow.root = null
        SelectionNodeShadow.focusedNode = null
        SelectionNodeShadow.onRefresh = null
        service = Robolectric.buildService(DictateAccessibilityService::class.java).create().get()
    }

    @After
    fun tearDown() {
        service.onDestroy()
        SelectionServiceShadow.root = null
        SelectionNodeShadow.focusedNode = null
        SelectionNodeShadow.onRefresh = null
    }

    @Test
    fun setTextFallbackReplacesReverseSelectionAndRestoresCursor() {
        val editor = node("before selected after", 15, 7, editable = true)
        SelectionServiceShadow.root = editor
        SelectionNodeShadow.focusedNode = editor
        var outcome: TextInsertionResult? = null

        service.tryInsertAtCurrentCursor("RESULT", { true }) { outcome = it }

        assertTrue(outcome?.inserted == true)
        val actions = shadowOf(editor).performedActionsWithArgs
        val text = actions.single { it.first == AccessibilityNodeInfo.ACTION_SET_TEXT }.second
        assertEquals(
            "before RESULT after",
            text.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE).toString(),
        )
        val selection = actions.single { it.first == AccessibilityNodeInfo.ACTION_SET_SELECTION }.second
        assertEquals(13, selection.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT))
        assertEquals(13, selection.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT))
    }

    @Test
    fun deliveryUsesCurrentFocusAfterSelectionWasReadElsewhere() {
        val original = node("original selection", 0, 8, editable = true)
        SelectionServiceShadow.root = original
        SelectionNodeShadow.focusedNode = original
        assertEquals("original", service.selectedText())

        val current = node("new editor", 4, 4, editable = true)
        SelectionServiceShadow.root = current
        SelectionNodeShadow.focusedNode = current
        service.tryInsertAtCurrentCursor("RESULT", { true }) {}

        assertTrue(shadowOf(original).performedActions.isEmpty())
        val action = shadowOf(current).performedActionsWithArgs
            .single { it.first == AccessibilityNodeInfo.ACTION_SET_TEXT }
        assertEquals(
            "new RESULTeditor",
            action.second.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE).toString(),
        )
    }

    @Test
    fun readsReadonlySelectionWithoutInputFocusAndDoesNotReuseCollapsedSelection() {
        val selected = node("readonly selected text", 9, 17)
        SelectionServiceShadow.root = selected

        assertEquals("selected", service.selectedText())
        selected.setTextSelection(17, 17)
        assertNull(service.selectedText())
    }

    @Test
    fun hiddenPasswordAndDetachedNodesAreNotSelectionSources() {
        val selected = node("secret", 0, 6)
        SelectionServiceShadow.root = selected
        selected.isPassword = true
        assertNull(service.selectedText())
        selected.isPassword = false
        selected.isVisibleToUser = false
        assertNull(service.selectedText())
        selected.isVisibleToUser = true
        shadowOf(selected).setRefreshReturnValue(false)
        assertNull(service.selectedText())
    }

    @Test
    fun latestCollapsedSelectionDoesNotResurrectAnotherNodesOldSelection() {
        SelectionServiceShadow.root = node("old selected text", 0, 3)
        val latestSelectionSource = node("current editor", 4, 4, editable = true)
        ReflectionHelpers.setField(service, "selectionSource", latestSelectionSource)

        assertNull(service.selectedText())
    }

    @Test
    fun refreshesFocusedNodeBeforeRejectingCachedMissingSelection() {
        val editor = node("", -1, -1, editable = true)
        SelectionServiceShadow.root = editor
        SelectionNodeShadow.focusedNode = editor
        SelectionNodeShadow.onRefresh = {
            it.text = "current selection"
            it.setTextSelection(8, 17)
        }

        assertEquals("selection", service.selectedText())
    }

    @Suppress("DEPRECATION")
    private fun node(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        editable: Boolean = false,
    ): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        this.text = text
        setTextSelection(selectionStart, selectionEnd)
        isVisibleToUser = true
        isEnabled = true
        isEditable = editable
        if (editable) addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)
    }
}

@Implements(AccessibilityService::class)
class SelectionServiceShadow : ShadowAccessibilityService() {
    @Implementation
    @Suppress("DEPRECATION")
    fun getRootInActiveWindow(): AccessibilityNodeInfo? = root?.let(AccessibilityNodeInfo::obtain)

    companion object {
        var root: AccessibilityNodeInfo? = null
    }
}

@Implements(AccessibilityNodeInfo::class)
class SelectionNodeShadow : ShadowAccessibilityNodeInfo() {
    @RealObject
    private lateinit var realNode: AccessibilityNodeInfo

    @Implementation
    override fun refresh(): Boolean {
        if (!super.refresh()) return false
        onRefresh?.invoke(realNode)
        return true
    }

    @Implementation
    @Suppress("DEPRECATION")
    fun findFocus(focusType: Int): AccessibilityNodeInfo? =
        if (focusType == AccessibilityNodeInfo.FOCUS_INPUT) {
            focusedNode?.let(AccessibilityNodeInfo::obtain)
        } else {
            null
        }

    companion object {
        var focusedNode: AccessibilityNodeInfo? = null
        var onRefresh: ((AccessibilityNodeInfo) -> Unit)? = null
    }
}
