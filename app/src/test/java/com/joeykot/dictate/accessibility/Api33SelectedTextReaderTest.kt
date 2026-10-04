package com.joeykot.dictate.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.app.Application
import android.os.Looper
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.SurroundingText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [33],
    application = Application::class,
    shadows = [
        SelectedTextServiceShadow::class,
        SelectedTextInputMethodShadow::class,
        SelectedTextConnectionShadow::class,
    ],
)
class Api33SelectedTextReaderTest {
    private lateinit var service: SelectedTextTestService
    private lateinit var editor: EditorInfo
    private var focus: AccessibilityNodeInfo? = null
    private var currentWindowPackage = PACKAGE_NAME
    private var canContinue = true
    private val readers = mutableListOf<Api33SelectedTextReader>()
    private val results = mutableListOf<String?>()

    @Before
    fun setUp() {
        SelectedTextConnectionShadow.reset()
        service = Robolectric.buildService(SelectedTextTestService::class.java).create().get()
        SelectedTextServiceShadow.method = InputMethod(service)
        SelectedTextInputMethodShadow.connection =
            Shadow.newInstanceOf(InputMethod.AccessibilityInputConnection::class.java)
        editor = EditorInfo().apply {
            packageName = PACKAGE_NAME
            inputType = InputType.TYPE_CLASS_TEXT
        }
        SelectedTextInputMethodShadow.editor = editor
        focus = node()
    }

    @After
    fun tearDown() {
        readers.forEach(Api33SelectedTextReader::destroy)
        SelectedTextConnectionShadow.releaseRead?.countDown()
        readers.forEach { assertTrue(executor(it).awaitTermination(5, TimeUnit.SECONDS)) }
        shadowOf(Looper.getMainLooper()).idle()
        SelectedTextServiceShadow.method = null
        SelectedTextInputMethodShadow.editor = null
        SelectedTextInputMethodShadow.connection = null
        SelectedTextConnectionShadow.reset()
        service.onDestroy()
    }

    @Test
    fun readsInputConnectionSelectionWhenAccessibilityNodeHasNoText() {
        assertNull(focus!!.text)
        SelectedTextConnectionShadow.surroundingText = SurroundingText("selected", 0, 8, -1)

        read()

        assertEquals(listOf("selected"), results)
        assertEquals(listOf(listOf(0, 0, 0)), SelectedTextConnectionShadow.requests)
        assertTrue(SelectedTextConnectionShadow.readThread!!.isDaemon)
        assertFalse(SelectedTextConnectionShadow.readThread === Looper.getMainLooper().thread)
    }

    @Test
    fun selectionIndicesAreRelativeToSurroundingTextAndDoNotUseAbsoluteOffset() {
        SelectedTextConnectionShadow.surroundingText =
            SurroundingText("before selected after", 7, 15, 60_000)

        read()

        assertEquals(listOf("selected"), results)
    }

    @Test
    fun readsFullLongSelectionWithoutTruncation() {
        val selected = "长文本\n".repeat(5_000)
        SelectedTextConnectionShadow.surroundingText =
            SurroundingText(selected, 0, selected.length, -1)

        read()

        assertEquals(listOf(selected), results)
    }

    @Test
    fun preservesWhitespaceSelectionAndAllowsMissingAccessibilityFocus() {
        focus = null
        SelectedTextConnectionShadow.surroundingText = SurroundingText(" \n\t", 0, 3, -1)

        read()

        assertEquals(listOf(" \n\t"), results)
    }

    @Test
    fun collapsedOrUnavailableSelectionReturnsNullWithoutReadingInitialEditorText() {
        editor.initialSelStart = 0
        editor.initialSelEnd = 5
        editor.setInitialSurroundingText("stale selection")
        SelectedTextConnectionShadow.surroundingText = SurroundingText("current", 4, 4, 0)
        read()
        SelectedTextConnectionShadow.surroundingText = null
        read()
        SelectedTextConnectionShadow.failRead = true
        read()

        assertEquals(listOf(null, null, null), results)
    }

    @Test
    fun excludesEveryPasswordInputTypeBeforeCallingInputConnection() {
        val passwordTypes = listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
        for (inputType in passwordTypes) {
            editor.inputType = inputType
            read()
        }

        assertEquals(List(passwordTypes.size) { null }, results)
        assertTrue(SelectedTextConnectionShadow.requests.isEmpty())
    }

    @Test
    fun rejectsPasswordHiddenHintDisabledAndDetachedNodes() {
        focus = node().apply { isPassword = true }
        read()
        focus = node().apply { isVisibleToUser = false }
        read()
        focus = node().apply { isShowingHintText = true }
        read()
        focus = node().apply { isEnabled = false }
        read()
        focus = node().also { shadowOf(it).setRefreshReturnValue(false) }
        read()

        assertEquals(List(5) { null }, results)
        assertTrue(SelectedTextConnectionShadow.requests.isEmpty())
    }

    @Test
    fun missingInputMethodEditorOrConnectionReturnsNull() {
        val method = SelectedTextServiceShadow.method
        SelectedTextServiceShadow.method = null
        read()
        SelectedTextServiceShadow.method = method
        SelectedTextInputMethodShadow.editor = null
        read()
        SelectedTextInputMethodShadow.editor = editor
        SelectedTextInputMethodShadow.connection = null
        read()

        assertEquals(listOf(null, null, null), results)
        assertTrue(SelectedTextConnectionShadow.requests.isEmpty())
    }

    @Test
    fun rejectsEditorOutsideCurrentWindowAndMismatchedFocusPackage() {
        currentWindowPackage = "another.application"
        read()
        currentWindowPackage = PACKAGE_NAME
        focus!!.packageName = "another.application"
        read()

        assertEquals(listOf(null, null), results)
        assertTrue(SelectedTextConnectionShadow.requests.isEmpty())
    }

    @Test
    fun switchingEditorBeforeResultArrivesDoesNotReturnNullOrOldSelection() {
        val reader = startAndWaitForRead()
        SelectedTextInputMethodShadow.editor = EditorInfo().apply {
            packageName = PACKAGE_NAME
            inputType = InputType.TYPE_CLASS_TEXT
        }

        finish(reader)

        assertTrue(results.isEmpty())
    }

    @Test
    fun switchingFocusBeforeResultArrivesDiscardsSelection() {
        val reader = startAndWaitForRead()
        focus = node()

        finish(reader)

        assertTrue(results.isEmpty())
    }

    @Test
    fun switchingWindowBeforeResultArrivesDiscardsSelection() {
        val reader = startAndWaitForRead()
        currentWindowPackage = "another.application"

        finish(reader)

        assertTrue(results.isEmpty())
    }

    @Test
    fun cancelledRequestDoesNotTriggerFallbackCallback() {
        val reader = startAndWaitForRead()
        canContinue = false

        finish(reader)

        assertTrue(results.isEmpty())
    }

    @Test
    fun destroyingReaderWhileInputConnectionIsBlockedPreventsCallback() {
        SelectedTextConnectionShadow.releaseRead = CountDownLatch(1)
        val reader = reader()
        reader.start()
        assertTrue(SelectedTextConnectionShadow.readStarted.await(5, TimeUnit.SECONDS))

        reader.destroy()
        SelectedTextConnectionShadow.releaseRead!!.countDown()
        assertTrue(executor(reader).awaitTermination(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(results.isEmpty())
    }

    private fun reader(): Api33SelectedTextReader = Api33SelectedTextReader(
        service = service,
        shouldContinue = { canContinue },
        currentInputFocus = { focus },
        isEditorInCurrentWindow = { it == currentWindowPackage },
        callback = {
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            results += it
        },
    ).also(readers::add)

    private fun read() {
        val reader = reader()
        reader.start()
        waitForWorker(reader)
        finish(reader)
    }

    private fun startAndWaitForRead(): Api33SelectedTextReader {
        SelectedTextConnectionShadow.surroundingText = SurroundingText("selected", 0, 8, -1)
        return reader().also {
            it.start()
            waitForWorker(it)
        }
    }

    private fun waitForWorker(reader: Api33SelectedTextReader) {
        val worker = executor(reader)
        if (!worker.isShutdown) worker.submit {}.get(5, TimeUnit.SECONDS)
    }

    private fun finish(reader: Api33SelectedTextReader) {
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(executor(reader).awaitTermination(5, TimeUnit.SECONDS))
    }

    private fun executor(reader: Api33SelectedTextReader): ExecutorService =
        ReflectionHelpers.getField(reader, "executor")

    @Suppress("DEPRECATION")
    private fun node(): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        packageName = PACKAGE_NAME
        isVisibleToUser = true
        isEnabled = true
        isEditable = true
    }

    private companion object {
        const val PACKAGE_NAME = "com.example.customeditor"
    }
}

class SelectedTextTestService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}

@Implements(AccessibilityService::class)
class SelectedTextServiceShadow : ShadowAccessibilityService() {
    @Implementation
    fun getInputMethod(): InputMethod? = method

    companion object {
        var method: InputMethod? = null
    }
}

@Implements(InputMethod::class)
class SelectedTextInputMethodShadow {
    @Implementation
    fun getCurrentInputEditorInfo(): EditorInfo? = editor

    @Implementation
    fun getCurrentInputConnection(): InputMethod.AccessibilityInputConnection? = connection

    companion object {
        var editor: EditorInfo? = null
        var connection: InputMethod.AccessibilityInputConnection? = null
    }
}

@Implements(InputMethod.AccessibilityInputConnection::class)
class SelectedTextConnectionShadow {
    @Implementation
    fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? {
        requests += listOf(beforeLength, afterLength, flags)
        readThread = Thread.currentThread()
        readStarted.countDown()
        releaseRead?.let { check(it.await(5, TimeUnit.SECONDS)) }
        check(!failRead)
        return surroundingText
    }

    companion object {
        var surroundingText: SurroundingText? = null
        var failRead = false
        val requests = CopyOnWriteArrayList<List<Int>>()
        var readThread: Thread? = null
        var readStarted = CountDownLatch(1)
        var releaseRead: CountDownLatch? = null

        fun reset() {
            surroundingText = null
            failRead = false
            requests.clear()
            readThread = null
            readStarted = CountDownLatch(1)
            releaseRead = null
        }
    }
}
