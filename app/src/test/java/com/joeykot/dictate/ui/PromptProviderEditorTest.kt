package com.joeykot.dictate.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import com.joeykot.dictate.DictateApplication
import com.joeykot.dictate.R
import com.joeykot.dictate.job.VoiceJobController
import com.joeykot.dictate.model.JobState
import com.joeykot.dictate.model.PostProcessingProvider
import com.joeykot.dictate.model.PromptConfig
import com.joeykot.dictate.settings.SettingsRepository
import com.joeykot.dictate.settings.TestAndroidKeyStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowAlertDialog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DictateApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PromptProviderEditorTest {
    private lateinit var host: ActivityController<Activity>
    private lateinit var section: PostProcessingSettingsView
    private lateinit var repository: SettingsRepository
    private val activity get() = host.get()
    private val requests = mutableListOf<PendingTest>()
    private val cancelled = mutableListOf<Long>()

    private class PendingTest(val prompt: PromptConfig, val key: String,
        val callback: (VoiceJobController.ConnectionTestResult) -> Unit, val id: Long)

    @Before fun setup() {
        RuntimeEnvironment.getApplication().getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        TestAndroidKeyStore.install()
        host = Robolectric.buildActivity(Activity::class.java).setup()
        repository = SettingsRepository(activity)
        section = newSection()
    }

    @After fun cleanup() {
        section.closeEditor()
        host.pause().stop().destroy()
        TestAndroidKeyStore.remove()
    }

    @Test fun newPromptDefaultsToMainAndRetainsHiddenApiAcrossSaveAndReopen() {
        var dialog = openEditor()
        assertEquals(activity.getString(R.string.prompt_same_provider), provider(dialog).selectedItem)
        assertFalse(input(dialog, R.string.prompt_base_url).isShown)
        assertFalse(testButton(dialog).isShown)
        selectProvider(dialog, PostProcessingProvider.ANTHROPIC)
        assertTrue(input(dialog, R.string.prompt_base_url).isShown)
        assertTrue(testButton(dialog).isShown)
        fillApi(dialog)
        selectProvider(dialog, null)
        input(dialog, R.string.prompt_title).setText("Saved prompt")
        input(dialog, R.string.prompt_content).setText("Summarize")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()

        val saved = repository.get().postProcessing.prompts.single()
        assertNull(saved.provider)
        assertEquals("draft-key", repository.promptApiKey(saved.id))
        val description = activity.getString(R.string.prompt_edit_description, saved.title)
        views(section).first { it.contentDescription == description }.performClick()
        dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertFalse(input(dialog, R.string.prompt_base_url).isShown)
        selectProvider(dialog, PostProcessingProvider.ANTHROPIC)
        assertApiDraft(dialog)
    }

    @Test fun savingAllowsIncompleteApiButStillRequiresPromptContentAndValidJson() {
        val dialog = openEditor()
        selectProvider(dialog, PostProcessingProvider.GOOGLE)
        input(dialog, R.string.prompt_title).setText("Incomplete API")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertNotNull(input(dialog, R.string.prompt_content).error)
        input(dialog, R.string.prompt_content).setText("Summarize")
        input(dialog, R.string.prompt_additional_json).setText("invalid json")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertNotNull(input(dialog, R.string.prompt_additional_json).error)
        assertTrue(repository.get().postProcessing.prompts.isEmpty())
        input(dialog, R.string.prompt_additional_json).setText("")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(PostProcessingProvider.GOOGLE, repository.get().postProcessing.prompts.single().provider)
        assertEquals("", repository.get().postProcessing.prompts.single().baseUrl)
    }

    @Test fun testUsesUnsavedFieldsAndApiEditsCancelOnlyItsOwnedJobAndDiscardStaleResults() {
        val dialog = openEditor()
        selectProvider(dialog, PostProcessingProvider.ANTHROPIC)
        fillApi(dialog)
        input(dialog, R.string.prompt_additional_json).setText("invalid json")
        for (label in listOf(R.string.prompt_base_url, R.string.prompt_api_key, R.string.prompt_model)) {
            testButton(dialog).performClick()
            val pending = requests.last()
            assertFalse(testButton(dialog).isEnabled)
            assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            assertEquals(input(dialog, R.string.prompt_api_key).text.toString(), pending.key)
            assertEquals(input(dialog, R.string.prompt_base_url).text.toString(), pending.prompt.baseUrl)
            assertEquals(input(dialog, R.string.prompt_model).text.toString(), pending.prompt.model)
            // Prompt fields do not change the API being tested.
            input(dialog, R.string.prompt_title).setText("Changed while testing")
            assertFalse(testButton(dialog).isEnabled)
            input(dialog, label).append("-changed")
            assertEquals(pending.id, cancelled.last())
            assertTrue(testButton(dialog).isEnabled)
            pending.callback(VoiceJobController.ConnectionTestResult(true, text = "stale-result"))
            assertFalse(views(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.contains("stale-result") })
        }
        testButton(dialog).performClick()
        val pending = requests.last()
        selectProvider(dialog, null)
        assertEquals(pending.id, cancelled.last())
        assertFalse(testButton(dialog).isShown)
        assertEquals(requests.map { it.id }, cancelled)
        assertTrue(repository.get().postProcessing.prompts.isEmpty())
    }

    @Test fun lateResultsAndDismissalFromPreviousEditorDoNotAffectTheNewTest() {
        val oldDialog = openEditor()
        selectProvider(oldDialog, PostProcessingProvider.ANTHROPIC)
        fillApi(oldDialog)
        testButton(oldDialog).performClick()
        val old = requests.single()
        oldDialog.cancel()
        val nextDialog = openEditor()
        selectProvider(nextDialog, PostProcessingProvider.GOOGLE)
        fillApi(nextDialog)
        testButton(nextDialog).performClick()
        val next = requests.last()
        old.callback(VoiceJobController.ConnectionTestResult(true, text = "old-result"))
        idle()
        assertEquals(listOf(old.id), cancelled)
        assertFalse(testButton(nextDialog).isEnabled)
        assertFalse(views(nextDialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.contains("old-result") })
        next.callback(VoiceJobController.ConnectionTestResult(true, statusCode = 200, elapsedMillis = 25, text = "new-result"))
        assertTrue(testButton(nextDialog).isEnabled)
        assertTrue(views(nextDialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.contains("new-result") })
    }

    @Test fun activityRecreationCancelsTestAndRestoresUnsavedApiWithoutRunningItAgain() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val app = RuntimeEnvironment.getApplication() as DictateApplication
        val jobController = app.voiceJobController
        val worker = VoiceJobController::class.java.getDeclaredField("worker").apply { isAccessible = true }
            .get(jobController) as ExecutorService
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val jobIds = VoiceJobController::class.java.getDeclaredField("nextJobId").apply { isAccessible = true }
            .get(jobController) as java.util.concurrent.atomic.AtomicLong
        worker.submit { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            text(controller.get().window.decorView, R.string.prompt_add).performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            selectProvider(dialog, PostProcessingProvider.ANTHROPIC)
            fillApi(dialog)
            input(dialog, R.string.prompt_title).setText("Unsaved title")
            input(dialog, R.string.prompt_content).setText("Unsaved content")
            testButton(dialog).performClick()
            assertEquals(JobState.REQUESTING, jobController.currentState().state)
            val startedId = jobIds.get()

            controller.recreate()
            idle()
            val restored = ShadowAlertDialog.getLatestAlertDialog()
            assertNotSame(dialog, restored)
            assertEquals(JobState.IDLE, jobController.currentState().state)
            assertEquals(startedId, jobIds.get())
            assertEquals(PostProcessingProvider.ANTHROPIC.ordinal + 1, provider(restored).selectedItemPosition)
            assertApiDraft(restored)
            assertEquals("Unsaved title", input(restored, R.string.prompt_title).text.toString())
            assertEquals("Unsaved content", input(restored, R.string.prompt_content).text.toString())
            assertTrue(testButton(restored).isEnabled)
            assertTrue(app.settingsRepository.get().postProcessing.prompts.isEmpty())
        } finally {
            release.countDown()
            controller.pause().stop().destroy()
            worker.shutdownNow()
            (VoiceJobController::class.java.getDeclaredField("scheduler").apply { isAccessible = true }
                .get(jobController) as ExecutorService).shutdownNow()
        }
    }

    private fun newSection() = PostProcessingSettingsView(activity, repository,
        enqueueWrite = { operation, success, failure ->
            val task = FutureTask(operation, Unit)
            Thread(task).start()
            try { task.get(5, TimeUnit.SECONDS); success() } catch (error: Exception) { failure(error) }
            true
        }, chooseIcon = {}, testConnection = { _, _, _ -> false },
        testPromptConnection = { prompt, key, callback ->
            val id = requests.size.toLong() + 1
            requests += PendingTest(prompt, key, callback, id)
            id
        }, cancelPromptTest = { cancelled += it },
    ).also { activity.setContentView(it); it.load(repository.get().postProcessing, "") }

    private fun openEditor(): AlertDialog {
        text(section, R.string.prompt_add).performClick()
        return ShadowAlertDialog.getLatestAlertDialog().also { assertTrue(it.isShowing) }
    }

    private fun fillApi(dialog: AlertDialog) {
        input(dialog, R.string.prompt_base_url).setText("https://draft.example")
        input(dialog, R.string.prompt_api_key).setText("draft-key")
        input(dialog, R.string.prompt_model).setText("draft-model")
    }

    private fun assertApiDraft(dialog: AlertDialog) {
        assertEquals("https://draft.example", input(dialog, R.string.prompt_base_url).text.toString())
        assertEquals("draft-key", input(dialog, R.string.prompt_api_key).text.toString())
        assertEquals("draft-model", input(dialog, R.string.prompt_model).text.toString())
    }

    private fun selectProvider(dialog: AlertDialog, value: PostProcessingProvider?) {
        val spinner = provider(dialog)
        spinner.setSelection(value?.ordinal?.plus(1) ?: 0)
        spinner.onItemSelectedListener!!.onItemSelected(spinner, null, spinner.selectedItemPosition, spinner.selectedItemId)
    }
    private fun provider(dialog: AlertDialog) = control(dialog, R.string.prompt_provider) as Spinner
    private fun input(dialog: AlertDialog, label: Int) = control(dialog, label) as EditText
    private fun control(dialog: AlertDialog, label: Int) = (text(dialog.window!!.decorView, label).parent as ViewGroup).getChildAt(1)
    private fun testButton(dialog: AlertDialog) = text(dialog.window!!.decorView, R.string.prompt_test_connection) as Button
    private fun text(root: View, id: Int): TextView = views(root).filterIsInstance<TextView>()
        .first { it.text.toString() == activity.getString(id) }
    private fun views(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(views(root.getChildAt(index)))
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
