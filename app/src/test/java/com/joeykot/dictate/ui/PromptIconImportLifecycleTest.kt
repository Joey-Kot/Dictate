package com.joeykot.dictate.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.joeykot.dictate.DictateApplication
import com.joeykot.dictate.i18n.AppStrings
import com.joeykot.dictate.model.PromptConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DictateApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PromptIconImportLifecycleTest {
    private val application: DictateApplication
        get() = RuntimeEnvironment.getApplication() as DictateApplication
    private val iconsDirectory: File
        get() = File(application.filesDir, "icons")
    private val sources = mutableListOf<File>()

    @Before
    fun setUp() {
        clearPreferences()
        application.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putString("app.language", "zh").commit()
        AppStrings.refresh(application)
        iconsDirectory.deleteRecursively()
    }

    @After
    fun tearDown() {
        sources.forEach { it.delete() }
        iconsDirectory.deleteRecursively()
        clearPreferences()
    }

    @Test
    fun iconPickerResultAndPromptDraftSurviveActivityRecreation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val original = controller.get()
            val dialog = openEditor(original)
            fillDraft(dialog)
            launchPicker(original, dialog)

            controller.recreate()
            val restored = ShadowAlertDialog.getLatestAlertDialog()
            assertNotSame(dialog, restored)
            assertDraft(restored)

            deliverIconResult(controller.get(), Uri.fromFile(svgSource()))
            saveAndAssertImportedDraft(restored)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun savedInstanceStateRestoresPendingPickerAfterOldActivityIsDestroyed() {
        val originalController = Robolectric.buildActivity(MainActivity::class.java).setup()
        val savedState = Bundle()
        try {
            val original = originalController.get()
            val dialog = openEditor(original)
            fillDraft(dialog)
            launchPicker(original, dialog)
            originalController.pause().saveInstanceState(savedState).stop()
        } finally {
            originalController.destroy()
        }

        val restoredController = Robolectric.buildActivity(MainActivity::class.java).setup(savedState)
        try {
            val restored = ShadowAlertDialog.getLatestAlertDialog()
            assertDraft(restored)
            deliverIconResult(restoredController.get(), Uri.fromFile(svgSource()))
            saveAndAssertImportedDraft(restored)
        } finally {
            restoredController.pause().stop().destroy()
        }
    }

    @Test
    fun slowDocumentProviderDoesNotBlockPickerResultDelivery() {
        val provider = registerSlowProvider()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val dialog = openEditor(activity)
            fillDraft(dialog)
            launchPicker(activity, dialog)

            deliverIconResult(activity, provider.uri)
            awaitProviderRead(provider.started, "The import should start reading the document")
            assertFalse("Picker result delivery waited for the blocked document provider", provider.waitTimedOut.get())
            assertNotSame("Document I/O must run away from the UI thread", Looper.getMainLooper().thread, provider.readThread)
            assertEquals(1L, provider.release.count)

            // The editor remains usable while the document source is still waiting.
            input(dialog, "标题").setText("等待图标期间修改的标题")
            provider.release.countDown()
            assertTrue(waitUntil { customPreview(dialog).visibility == View.VISIBLE })
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(waitUntil { application.settingsRepository.get().postProcessing.prompts.size == 1 })
            val saved = application.settingsRepository.get().postProcessing.prompts.single()
            assertEquals("等待图标期间修改的标题", saved.title)
            assertTrue(File(iconsDirectory, saved.customIcon!!).isFile)
        } finally {
            provider.release.countDown()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun ongoingImportRestartsAfterRecreationAndCleansUpTheOldWorkerCopy() {
        val provider = registerSlowProvider()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val original = controller.get()
            val dialog = openEditor(original)
            fillDraft(dialog)
            launchPicker(original, dialog)
            deliverIconResult(original, provider.uri)
            awaitProviderRead(provider.started, "The original editor should start its icon import")

            controller.recreate()
            val restored = ShadowAlertDialog.getLatestAlertDialog()
            assertNotSame(dialog, restored)
            assertDraft(restored)
            awaitProviderRead(provider.restarted, "The restored editor must resume its pending URI import")
            assertEquals(2, provider.openCount.get())
            assertFalse(provider.waitTimedOut.get())
            provider.release.countDown()

            saveAndAssertImportedDraft(restored)
            val saved = application.settingsRepository.get().postProcessing.prompts.single()
            assertTrue("Only the restored editor's saved icon should remain", waitUntil {
                iconsDirectory.listFiles().orEmpty().map { it.name }.toSet() == setOf(saved.customIcon)
            })
        } finally {
            provider.release.countDown()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun pendingDeletionDoesNotRestoreAnEditorForTheDeletedPrompt() {
        val prompt = PromptConfig(title = "即将删除的提示词", prompt = "请总结")
        var setupFailure: Throwable? = null
        Thread {
            try {
                application.settingsRepository.savePrompts(listOf(prompt))
            } catch (error: Throwable) {
                setupFailure = error
            }
        }.apply { start(); join() }
        setupFailure?.let { throw it }

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val executor = ReflectionHelpers.getField<ExecutorService>(controller.get(), "settingsExecutor")
        val writerBlocked = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        try {
            // Hold the real persistence queue so recreation happens before deletion is committed.
            executor.execute {
                writerBlocked.countDown()
                releaseWriter.await(10, TimeUnit.SECONDS)
            }
            assertTrue(writerBlocked.await(5, TimeUnit.SECONDS))
            findByDescription(controller.get().window.decorView, "编辑提示词：${prompt.title}")!!.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            assertTrue(dialog.isShowing)
            assertFalse(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled)
            assertEquals(listOf(prompt), application.settingsRepository.get().postProcessing.prompts)

            controller.recreate()
            assertFalse("The old editor should be dismissed during recreation", dialog.isShowing)
            assertFalse("A prompt being deleted must not be restored as an editable draft",
                ShadowAlertDialog.getLatestAlertDialog().isShowing)

            releaseWriter.countDown()
            assertTrue(waitUntil { application.settingsRepository.get().postProcessing.prompts.isEmpty() })
            assertFalse(ShadowAlertDialog.getLatestAlertDialog().isShowing)
        } finally {
            releaseWriter.countDown()
            controller.pause().stop().destroy()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun lateImportIsDiscardedAfterOpeningAnotherPromptEditor() {
        val provider = registerSlowProvider()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val oldDialog = openEditor(activity)
            fillDraft(oldDialog)
            launchPicker(activity, oldDialog)
            deliverIconResult(activity, provider.uri)
            awaitProviderRead(provider.started, "The old editor should start its icon import")
            assertFalse(provider.waitTimedOut.get())
            provider.release.countDown()

            // Let the worker produce its file, but hold UI delivery until the editor changes.
            assertTrue(waitUntil(idleMainLooper = false) { iconsDirectory.listFiles().orEmpty().isNotEmpty() })
            // cancel() closes the window now; the negative button posts a later dismiss message.
            oldDialog.cancel()
            assertFalse(oldDialog.isShowing)
            val newDialog = openEditor(activity)
            assertNotSame(oldDialog, newDialog)
            input(newDialog, "标题").setText("新的提示词")
            input(newDialog, "提示词内容").setText("请翻译")

            assertTrue(waitUntil { iconsDirectory.listFiles().orEmpty().isEmpty() })
            assertEquals(View.GONE, customPreview(newDialog).visibility)
            newDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(waitUntil { application.settingsRepository.get().postProcessing.prompts.size == 1 })
            val saved = application.settingsRepository.get().postProcessing.prompts.single()
            assertEquals("新的提示词", saved.title)
            assertEquals(null, saved.customIcon)
            assertTrue(iconsDirectory.listFiles().orEmpty().isEmpty())
        } finally {
            provider.release.countDown()
            controller.pause().stop().destroy()
        }
    }

    private fun openEditor(activity: MainActivity): AlertDialog {
        findText(activity.window.decorView, "新增提示词")!!.performClick()
        return ShadowAlertDialog.getLatestAlertDialog().also { assertTrue(it.isShowing) }
    }

    private fun fillDraft(dialog: AlertDialog) {
        input(dialog, "标题").setText(DRAFT_TITLE)
        input(dialog, "提示词内容").setText(DRAFT_PROMPT)
        input(dialog, "附加参数 JSON").setText(DRAFT_JSON)
    }

    private fun assertDraft(dialog: AlertDialog) {
        assertTrue("The prompt editor should be restored", dialog.isShowing)
        assertEquals(DRAFT_TITLE, input(dialog, "标题").text.toString())
        assertEquals(DRAFT_PROMPT, input(dialog, "提示词内容").text.toString())
        assertEquals(DRAFT_JSON, input(dialog, "附加参数 JSON").text.toString())
    }

    private fun launchPicker(activity: MainActivity, dialog: AlertDialog) {
        findText(dialog.window!!.decorView, "导入图标（SVG / PNG / JPG）")!!.performClick()
        val request = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
        assertEquals(103, request.requestCode)
    }

    private fun deliverIconResult(activity: MainActivity, uri: Uri) {
        // Exercise the same framework entry point Android invokes on the replacement Activity.
        MainActivity::class.java.getDeclaredMethod(
            "onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java,
        ).apply { isAccessible = true }.invoke(activity, 103, Activity.RESULT_OK, Intent().setData(uri))
    }

    private fun saveAndAssertImportedDraft(dialog: AlertDialog) {
        assertTrue("The returned SVG should become the selected icon", waitUntil {
            customPreview(dialog).visibility == View.VISIBLE
        })
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(waitUntil { application.settingsRepository.get().postProcessing.prompts.size == 1 })
        val saved = application.settingsRepository.get().postProcessing.prompts.single()
        assertEquals(DRAFT_TITLE, saved.title)
        assertEquals(DRAFT_PROMPT, saved.prompt)
        assertEquals(DRAFT_JSON, saved.additionalJson)
        assertTrue("Saving the restored draft must retain the imported file", File(iconsDirectory, saved.customIcon!!).isFile)
    }

    private fun svgSource(): File = File.createTempFile("prompt-icon", ".svg", application.cacheDir).also {
        sources += it
        it.writeText("""<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24"><rect width="24" height="24" fill="blue"/></svg>""")
    }

    private fun registerSlowProvider(): SlowIconProvider = SlowIconProvider(svgSource()).also {
        it.attachInfo(application, ProviderInfo().apply { authority = SlowIconProvider.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(SlowIconProvider.AUTHORITY, it)
    }

    private fun awaitProviderRead(latch: CountDownLatch, message: String) {
        if (!latch.await(5, TimeUnit.SECONDS)) {
            shadowOf(Looper.getMainLooper()).idle()
            fail("$message; latest UI error: ${ShadowToast.getTextOfLatestToast()}")
        }
    }

    private fun customPreview(dialog: AlertDialog): View = findByDescription(dialog.window!!.decorView, "自定义图标")!!

    private fun input(dialog: AlertDialog, label: String): EditText {
        val parent = findText(dialog.window!!.decorView, label)!!.parent as ViewGroup
        return (0 until parent.childCount).map(parent::getChildAt).filterIsInstance<EditText>().single()
    }

    private fun findText(root: View, text: String): TextView? {
        if (root is TextView && root.text.toString() == text) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) {
            findText(root.getChildAt(index), text)?.let { return it }
        }
        return null
    }

    private fun findByDescription(root: View, description: String): View? {
        if (root.contentDescription?.toString() == description) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) {
            findByDescription(root.getChildAt(index), description)?.let { return it }
        }
        return null
    }

    private fun waitUntil(idleMainLooper: Boolean = true, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(10)
            if (idleMainLooper) shadowOf(Looper.getMainLooper()).idle()
        }
        return condition()
    }

    private fun clearPreferences() {
        listOf("settings", "secure_settings").forEach {
            application.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private class SlowIconProvider(private val source: File) : ContentProvider() {
        val uri: Uri = Uri.parse("content://$AUTHORITY/icon.svg")
        val started = CountDownLatch(1)
        val restarted = CountDownLatch(1)
        val openCount = AtomicInteger(0)
        val release = CountDownLatch(1)
        val waitTimedOut = AtomicBoolean(false)
        @Volatile var readThread: Thread? = null

        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/svg+xml"
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            readThread = Thread.currentThread()
            if (openCount.incrementAndGet() == 2) restarted.countDown()
            started.countDown()
            if (!release.await(10, TimeUnit.SECONDS)) waitTimedOut.set(true)
            return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

        companion object {
            const val AUTHORITY = "com.joeykot.dictate.test.slow-icon"
        }
    }

    private companion object {
        const val DRAFT_TITLE = "等待导入的草稿"
        const val DRAFT_PROMPT = "请总结用户提供的文本。"
        const val DRAFT_JSON = "{\"model\":\"other-model\",\"nested\":{\"remove\":null}}"
    }
}
