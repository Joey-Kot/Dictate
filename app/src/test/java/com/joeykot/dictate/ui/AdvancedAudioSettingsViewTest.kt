package com.joeykot.dictate.ui

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.Button
import android.widget.TextView
import android.os.Looper
import com.joeykot.dictate.DictateApplication
import com.joeykot.dictate.R
import com.joeykot.dictate.advanced_audio.AdvancedAudioWorkflowCodec
import com.joeykot.dictate.advanced_audio.WorkflowValidator
import com.joeykot.dictate.model.AdvancedAudioConfig
import com.joeykot.dictate.model.AdvancedRemoteAudioConfig
import com.joeykot.dictate.model.RemoteAudioCredentialIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DictateApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AdvancedAudioSettingsViewTest {
    @Test
    fun disablingAdvancedAudioHidesItsConfigurationWithoutDiscardingTheDraft() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val view = AdvancedAudioSettingsView(activity)
            activity.setContentView(view)
            val enabled = findViews<Switch>(view).single {
                it.text.toString() == activity.getString(R.string.advanced_audio_enabled)
            }
            val content = checkNotNull(view.findViewWithTag<LinearLayout>("advanced_audio_content"))
            val workflow = workflowInput(view)

            assertFalse(enabled.isChecked)
            assertEquals(View.GONE, content.visibility)

            enabled.isChecked = true
            assertEquals(View.VISIBLE, content.visibility)
            workflow.setText("{\"schema_version\": 2}")

            enabled.isChecked = false
            assertEquals(View.GONE, content.visibility)
            assertEquals("{\"schema_version\": 2}", workflow.text.toString())
            assertEquals("{\"schema_version\": 2}", view.readConfig().workflowJson)

            enabled.isChecked = true
            assertEquals(View.VISIBLE, content.visibility)
            assertSame(workflow, workflowInput(view))
            assertEquals("{\"schema_version\": 2}", workflowInput(view).text.toString())
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun remoteAudioSectionFollowsTheCurrentValidatedDeliveryAndRetainsItsDraftWhileHidden() {
        val publicUrlWorkflow = workflowWithRemoteDelivery("public_https_url", "public_url")
        val cloudUriWorkflow = workflowWithRemoteDelivery("cloud_uri", "cloud_uri")
        val localWorkflow = fixture("advanced_audio/workflows/valid-v2-typed-request.json")
        val remoteConfig = AdvancedRemoteAudioConfig.WebDav(
            uploadBaseUrl = "https://storage.example.test/dav",
            usernameSecretId = RemoteAudioCredentialIds.WEB_DAV_USERNAME,
            passwordSecretId = RemoteAudioCredentialIds.WEB_DAV_PASSWORD,
        )
        val credentials = mapOf(
            RemoteAudioCredentialIds.WEB_DAV_USERNAME to "alice",
            RemoteAudioCredentialIds.WEB_DAV_PASSWORD to "storage-password",
        )
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val view = AdvancedAudioSettingsView(activity)
            activity.setContentView(view)
            val remoteSection = checkNotNull(view.findViewWithTag<LinearLayout>("advanced_audio_remote_audio"))

            // No workflow has been successfully validated yet.
            assertEquals(View.GONE, remoteSection.visibility)

            view.load(
                AdvancedAudioConfig(
                    enabled = true,
                    workflowJson = publicUrlWorkflow,
                    remoteAudio = remoteConfig,
                ),
                credentials,
            )
            assertEquals(View.VISIBLE, remoteSection.visibility)

            // Both remote delivery forms expose the same configuration group.
            view.setWorkflowJson(cloudUriWorkflow, apply = true)
            assertEquals(View.VISIBLE, remoteSection.visibility)

            // Editing JSON makes the previously applied workflow stale until
            // the user validates it again.
            view.setWorkflowJson(cloudUriWorkflow.replace("Typed JSON request", "Edited workflow"))
            assertEquals(View.GONE, remoteSection.visibility)
            assertEquals(remoteConfig, view.readConfig().remoteAudio)
            assertEquals(credentials, view.readSecrets())

            assertTrue(view.validateAndApply())
            assertEquals(View.VISIBLE, remoteSection.visibility)

            // A successfully validated non-remote delivery hides the group
            // without discarding the existing storage draft or credentials.
            view.setWorkflowJson(localWorkflow, apply = true)
            assertEquals(View.GONE, remoteSection.visibility)
            assertEquals(remoteConfig, view.readConfig().remoteAudio)
            assertEquals(credentials, view.readSecrets())

            button(view, activity.getString(R.string.advanced_audio_reset)).performClick()
            assertEquals(View.GONE, remoteSection.visibility)
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun statusAreaCollapsesWhenEmptyAndBoundsLongMessagesWithoutWastingSpace() {
        var testCompletion: ((AdvancedAudioSettingsView.WorkflowTestResult) -> Unit)? = null
        val workflowJson = fixture("advanced_audio/workflows/valid-v2-typed-request.json")
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val view = AdvancedAudioSettingsView(
                activity,
                object : AdvancedAudioSettingsView.Callbacks() {
                    override fun onTestWorkflow(
                        request: AdvancedAudioSettingsView.WorkflowTestRequest,
                        completion: (AdvancedAudioSettingsView.WorkflowTestResult) -> Unit,
                    ) {
                        testCompletion = completion
                    }
                },
            )
            activity.setContentView(view)
            val statusArea = checkNotNull(view.findViewWithTag<ScrollView>("advanced_audio_status"))

            // No message must leave a fixed empty area between actions and Retry Settings.
            assertEquals(View.GONE, statusArea.visibility)

            view.load(
                AdvancedAudioConfig(enabled = true, workflowJson = workflowJson),
                secrets = mapOf("api_key" to "secret"),
            )
            assertEquals(View.GONE, statusArea.visibility)

            assertTrue(view.validateAndApply())
            assertEquals(View.VISIBLE, statusArea.visibility)
            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, statusArea.layoutParams.height)
            val shortHeight = measureAndLayout(view, statusArea)
            val maximumHeight = dp(activity, 120)
            assertTrue("Short status should use its natural height", shortHeight in 1 until maximumHeight)

            shadowOf(Looper.getMainLooper()).idle()
            button(view, activity.getString(R.string.advanced_audio_test_workflow)).performClick()
            testCompletion!!.invoke(
                AdvancedAudioSettingsView.WorkflowTestResult(
                    success = false,
                    message = (1..40).joinToString("\n") { "Detailed failure line $it" },
                ),
            )
            shadowOf(Looper.getMainLooper()).idle()

            val longHeight = measureAndLayout(view, statusArea)
            assertEquals(maximumHeight, longHeight)
            assertTrue(statusArea.isVerticalScrollBarEnabled)
            assertTrue(
                "Long status text should remain available through the status area's scroll container",
                statusArea.getChildAt(0).measuredHeight > longHeight,
            )
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun resetCancelsGenerationAndAStaleGeneratedDocumentCannotOverwriteTheDraft() {
        var cancelled = false
        var completion: ((Result<String>) -> Unit)? = null
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val view = AdvancedAudioSettingsView(
                activity,
                object : AdvancedAudioSettingsView.Callbacks() {
                    override fun onGenerateWorkflow(
                        request: AdvancedAudioSettingsView.WorkflowGenerationRequest,
                        completionCallback: (Result<String>) -> Unit,
                    ) {
                        completion = completionCallback
                    }

                    override fun onGenerationCancelled() {
                        cancelled = true
                    }
                },
            )
            activity.setContentView(view)

            button(view, activity.getString(R.string.advanced_audio_generate)).performClick()
            assertFalse(button(view, activity.getString(R.string.advanced_audio_generate)).isEnabled)
            assertFalse(workflowInput(view).isEnabled)

            button(view, activity.getString(R.string.advanced_audio_reset)).performClick()
            assertTrue(cancelled)
            assertTrue(button(view, activity.getString(R.string.advanced_audio_generate)).isEnabled)

            completion!!.invoke(Result.success(fixture("advanced_audio/workflows/valid-v2-typed-request.json")))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(null, view.readConfig().workflowJson)
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun testResultIsDiscardedWhenTheDraftChangesWhileTesting() {
        var completion: ((AdvancedAudioSettingsView.WorkflowTestResult) -> Unit)? = null
        val workflowJson = fixture("advanced_audio/workflows/valid-v2-typed-request.json")
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val view = AdvancedAudioSettingsView(
                activity,
                object : AdvancedAudioSettingsView.Callbacks() {
                    override fun onTestWorkflow(
                        request: AdvancedAudioSettingsView.WorkflowTestRequest,
                        completionCallback: (AdvancedAudioSettingsView.WorkflowTestResult) -> Unit,
                    ) {
                        completion = completionCallback
                    }
                },
            )
            activity.setContentView(view)
            view.load(
                AdvancedAudioConfig(enabled = true, workflowJson = workflowJson),
                secrets = mapOf("api_key" to "secret"),
            )
            assertTrue(view.validateAndApply())

            val test = button(view, activity.getString(R.string.advanced_audio_test_workflow))
            test.performClick()
            assertFalse(test.isEnabled)

            findViews<EditText>(requireView(view, "advanced_parameter:model")).single().setText("changed-model")
            completion!!.invoke(AdvancedAudioSettingsView.WorkflowTestResult(success = true, message = "stale result"))
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(test.isEnabled)
            assertFalse(
                findViews<TextView>(view).any {
                    it.text.toString().contains(activity.getString(R.string.advanced_audio_test_success))
                },
            )
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun testResultIsDiscardedWhenWorkflowGenerationBegins() {
        var testCompletion: ((AdvancedAudioSettingsView.WorkflowTestResult) -> Unit)? = null
        var generationCompletion: ((Result<String>) -> Unit)? = null
        val workflowJson = fixture("advanced_audio/workflows/valid-v2-typed-request.json")
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val view = AdvancedAudioSettingsView(
                activity,
                object : AdvancedAudioSettingsView.Callbacks() {
                    override fun onTestWorkflow(
                        request: AdvancedAudioSettingsView.WorkflowTestRequest,
                        completion: (AdvancedAudioSettingsView.WorkflowTestResult) -> Unit,
                    ) {
                        testCompletion = completion
                    }

                    override fun onGenerateWorkflow(
                        request: AdvancedAudioSettingsView.WorkflowGenerationRequest,
                        completion: (Result<String>) -> Unit,
                    ) {
                        generationCompletion = completion
                    }
                },
            )
            activity.setContentView(view)
            view.load(
                AdvancedAudioConfig(enabled = true, workflowJson = workflowJson),
                secrets = mapOf("api_key" to "secret"),
            )
            assertTrue(view.validateAndApply())

            val test = button(view, activity.getString(R.string.advanced_audio_test_workflow))
            test.performClick()
            assertFalse(test.isEnabled)

            button(view, activity.getString(R.string.advanced_audio_generate)).performClick()
            assertFalse(test.isEnabled)
            assertTrue(
                findViews<TextView>(view).any {
                    it.text.toString() == activity.getString(R.string.advanced_audio_generating)
                },
            )

            testCompletion!!.invoke(AdvancedAudioSettingsView.WorkflowTestResult(success = true, message = "stale result"))
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse(test.isEnabled)
            assertTrue(
                findViews<TextView>(view).any {
                    it.text.toString() == activity.getString(R.string.advanced_audio_generating)
                },
            )

            generationCompletion!!.invoke(Result.failure(IllegalStateException("test completion")))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(test.isEnabled)
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun dynamicControlsKeepHiddenValuesAndPersistAnExplicitEmptyMultiSelect() {
        val workflowJson = fixture("advanced_audio/workflows/valid-v2-typed-request.json")
        val workflowErrors = WorkflowValidator.validateWorkflow(AdvancedAudioWorkflowCodec.parse(workflowJson))
        assertTrue(workflowErrors.joinToString("\n"), workflowErrors.isEmpty())
        var testRequests = 0
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val view = AdvancedAudioSettingsView(
                activity,
                object : AdvancedAudioSettingsView.Callbacks() {
                    override fun onTestWorkflow(
                        request: AdvancedAudioSettingsView.WorkflowTestRequest,
                        completion: (AdvancedAudioSettingsView.WorkflowTestResult) -> Unit,
                    ) {
                        testRequests++
                    }
                },
            )
            activity.setContentView(view)
            shadowOf(Looper.getMainLooper()).idle()
            view.load(
                AdvancedAudioConfig(
                    enabled = true,
                    workflowJson = workflowJson,
                    values = mapOf(
                        "vocabulary" to "{\"wake_phrase\":\"Saved value\"}",
                        "language_hints" to "[\"zh\",\"en\"]",
                    ),
                ),
                secrets = mapOf("api_key" to "secret"),
            )

            assertTrue(view.validateAndApply())
            val vocabulary = requireView(view, "advanced_parameter:vocabulary")
            val vocabularyInput = findViews<EditText>(vocabulary).single()
            vocabularyInput.setText("{\"wake_phrase\":\"Retained value\"}")

            val enableContext = findViews<Switch>(requireView(view, "advanced_parameter:enable_context")).single()
            enableContext.isChecked = false
            assertEquals(View.GONE, vocabulary.visibility)
            assertEquals("{\"wake_phrase\":\"Retained value\"}", view.readConfig().values["vocabulary"])

            val hints = requireView(view, "advanced_parameter:language_hints")
            val hintsSelector = multiSelectSpinner(hints)
            assertEquals(activity.getString(R.string.advanced_audio_selected_count, 2), hintsSelector.selectedItem.toString())

            // Changes remain pending until Save.  Cancelling restores the
            // existing JSON array and its compact summary unchanged.
            hintsSelector.performClick()
            val cancelledDialog = checkNotNull(ShadowAlertDialog.getLatestAlertDialog())
            setDialogMultiChoice(cancelledDialog, 0, false)
            cancelledDialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("[\"zh\",\"en\"]", view.readConfig().values["language_hints"])
            assertEquals(activity.getString(R.string.advanced_audio_selected_count, 2), hintsSelector.selectedItem.toString())

            // Saving an empty selection must retain the explicit [] rather
            // than allowing the declaration default to return on rebuild.
            hintsSelector.performClick()
            val savedDialog = checkNotNull(ShadowAlertDialog.getLatestAlertDialog())
            assertNotSame(cancelledDialog, savedDialog)
            setDialogMultiChoice(savedDialog, 0, false)
            setDialogMultiChoice(savedDialog, 1, false)
            savedDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("[]", view.readConfig().values["language_hints"])
            assertEquals(activity.getString(R.string.advanced_audio_not_set), hintsSelector.selectedItem.toString())

            // Reapplying the form must preserve the explicit empty value,
            // rather than restoring this required field's nonempty default.
            assertTrue(view.validateAndApply())
            val rebuiltHints = requireView(view, "advanced_parameter:language_hints")
            assertEquals(activity.getString(R.string.advanced_audio_not_set), multiSelectSpinner(rebuiltHints).selectedItem.toString())

            // The persisted empty array must still fail required validation
            // instead of being silently replaced by the declaration default.
            button(view, activity.getString(R.string.advanced_audio_test_workflow)).performClick()
            assertEquals(0, testRequests)
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun editingOrChangingVisibilityKeepsExistingDynamicControlsStable() {
        val workflowJson = fixture("advanced_audio/workflows/valid-v2-typed-request.json")
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val view = AdvancedAudioSettingsView(activity)
            activity.setContentView(view)
            view.load(
                AdvancedAudioConfig(
                    enabled = true,
                    workflowJson = workflowJson,
                ),
                secrets = mapOf("api_key" to "secret"),
            )
            assertTrue(view.validateAndApply())

            val modelRow = requireView(view, "advanced_parameter:model")
            val modelInput = findViews<EditText>(modelRow).single()
            val languageRow = requireView(view, "advanced_parameter:language")
            val languageInput = findViews<Spinner>(languageRow).single()
            val hintsRow = requireView(view, "advanced_parameter:language_hints")
            val hintsSelector = multiSelectSpinner(hintsRow)

            modelInput.setText("qwen-audio-3.1-asr-flash")

            assertSame(modelInput, findViews<EditText>(requireView(view, "advanced_parameter:model")).single())
            assertSame(languageInput, findViews<Spinner>(requireView(view, "advanced_parameter:language")).single())
            assertSame(hintsSelector, multiSelectSpinner(requireView(view, "advanced_parameter:language_hints")))

            val enableContext = findViews<Switch>(requireView(view, "advanced_parameter:enable_context")).single()
            enableContext.isChecked = false

            assertEquals(View.GONE, requireView(view, "advanced_parameter:vocabulary").visibility)
            assertSame(modelInput, findViews<EditText>(requireView(view, "advanced_parameter:model")).single())
            assertSame(languageInput, findViews<Spinner>(requireView(view, "advanced_parameter:language")).single())
            assertSame(hintsSelector, multiSelectSpinner(requireView(view, "advanced_parameter:language_hints")))
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
            controller.pause().stop().destroy()
        }
    }

    private fun fixture(path: String): String = checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
        "Missing test fixture $path"
    }.bufferedReader().use { it.readText() }

    private fun workflowWithRemoteDelivery(delivery: String, placeholder: String): String =
        fixture("advanced_audio/workflows/valid-v2-typed-request.json")
            .replace("\"type\": \"base64\"", "\"type\": \"$delivery\"")
            .replace("{{audio:base64}}", "{{audio:$placeholder}}")

    private fun requireView(root: View, description: String): View = findByDescription(root, description)
        ?: throw AssertionError("Missing view $description")

    private fun workflowInput(root: View): EditText = findViews<EditText>(root).first { input ->
        input.contentDescription?.toString() == root.context.getString(R.string.advanced_audio_workflow)
    }

    private fun button(root: View, label: String): Button = findViews<Button>(root).single { it.text.toString() == label }

    private fun multiSelectSpinner(root: View): Spinner = findViews<Spinner>(root).single {
        it.contentDescription?.toString()?.startsWith("advanced_multi_select:") == true
    }

    private fun setDialogMultiChoice(dialog: AlertDialog, position: Int, checked: Boolean) {
        val root = checkNotNull(dialog.window).decorView
        findViews<CheckBox>(root)[position].isChecked = checked
    }

    private fun measureAndLayout(root: View, target: View): Int {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(4096, View.MeasureSpec.AT_MOST),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return target.measuredHeight
    }

    private fun dp(activity: Activity, value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private fun findByDescription(root: View, description: String): View? {
        if (root.contentDescription?.toString() == description) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findByDescription(root.getChildAt(index), description)?.let { return it }
            }
        }
        return null
    }

    private inline fun <reified T : View> findViews(root: View): List<T> = findViews(root, T::class.java)

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> findViews(root: View, type: Class<T>): List<T> = buildList {
        if (type.isInstance(root)) add(root as T)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) addAll(findViews(root.getChildAt(index), type))
        }
    }
}
