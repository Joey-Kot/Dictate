package com.joeykot.dictate.ui

import android.content.Context
import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import com.joeykot.dictate.DictateApplication
import com.joeykot.dictate.i18n.AppStrings
import com.joeykot.dictate.model.AudioCodec
import com.joeykot.dictate.model.AudioConfig
import com.joeykot.dictate.model.AudioContainer
import com.joeykot.dictate.model.PromptConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DictateApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityTest {
    private val application: DictateApplication
        get() = RuntimeEnvironment.getApplication() as DictateApplication

    @Before
    fun setUp() {
        clearPreferences()
        application.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("app.language", "zh")
            .putInt(KEY_BIT_DEPTH, AudioConfig.DEFAULT_BIT_DEPTH)
            .putInt(KEY_SAMPLE_RATE, AudioConfig.DEFAULT_SAMPLE_RATE)
            .putString(KEY_CODEC, AudioCodec.OPUS.name)
            .putString(KEY_CONTAINER, AudioContainer.OGG.name)
            .putInt(KEY_BITRATE, AudioConfig.DEFAULT_BITRATE_KBPS)
            .commit()
        AppStrings.refresh(application)
    }

    @After
    fun tearDown() {
        clearPreferences()
    }

    @Test
    fun delayedCodecSelectionCallbackPreservesOggContainer() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            layout(activity.window.decorView)
            shadowOf(Looper.getMainLooper()).idle()

            val codecSpinner = spinnerForLabel(activity.window.decorView, "编码")
            val containerSpinner = spinnerForLabel(activity.window.decorView, "容器")
            codecSpinner.onItemSelectedListener!!.onItemSelected(
                codecSpinner,
                null,
                codecSpinner.selectedItemPosition,
                codecSpinner.selectedItemId,
            )
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(AudioContainer.OGG.value.uppercase(), containerSpinner.selectedItem)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun codecSelectionReconcilesRatesContainersAndControlVisibility() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val root = controller.get().window.decorView
            layout(root)
            shadowOf(Looper.getMainLooper()).idle()
            val codecs = spinnerForLabel(root, "编码")
            val rates = spinnerForLabel(root, "输出采样率")
            val containers = spinnerForLabel(root, "容器")
            val bitrate = spinnerForLabel(root, "码率")
            val depths = spinnerForLabel(root, "位深度")
            for (codec in listOf(AudioCodec.AMR_WB, AudioCodec.FLAC, AudioCodec.PCM, AudioCodec.OPUS)) {
                codecs.setSelection(AudioCodec.entries.indexOf(codec))
                codecs.onItemSelectedListener!!.onItemSelected(codecs, null, codecs.selectedItemPosition, codecs.selectedItemId)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(AudioConfig.compatibleSampleRates(codec).size, rates.count)
                assertEquals(AudioConfig.defaultContainer(codec).value.uppercase(), containers.selectedItem)
                assertEquals(if (codec.usesBitrate) View.VISIBLE else View.GONE, (bitrate.parent as View).visibility)
                assertEquals(if (AudioConfig.compatibleBitDepths(codec).isNotEmpty()) View.VISIBLE else View.GONE, (depths.parent as View).visibility)
                if (codec == AudioCodec.AMR_WB) assertTrue((0 until bitrate.count).any { bitrate.getItemAtPosition(it) == "23.85 kbps" })
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun pcmDepthChangeReconcilesRawContainerWithoutResettingSampleRate() {
        application.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CODEC, AudioCodec.PCM.name).putString(KEY_CONTAINER, AudioContainer.S24LE.name)
            .putInt(KEY_BIT_DEPTH, 24).putInt(KEY_SAMPLE_RATE, 44100).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val root = controller.get().window.decorView
            layout(root)
            shadowOf(Looper.getMainLooper()).idle()
            val depth = spinnerForLabel(root, "位深度")
            depth.setSelection(1) // 16 bits
            depth.onItemSelectedListener!!.onItemSelected(depth, null, 1, depth.selectedItemId)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("WAV", spinnerForLabel(root, "容器").selectedItem)
            assertEquals("44.1 kHz", spinnerForLabel(root, "输出采样率").selectedItem)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun saveButtonPersistsThroughBackgroundWriter() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val preferences = application.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            preferences.edit().clear().commit()

            val saveButton = findTextView(activity.window.decorView, "保存设置") as Button
            saveButton.performClick()

            assertTrue(
                waitUntil {
                    preferences.getString(KEY_CONTAINER, null) == AudioContainer.OGG.name &&
                        preferences.getFloat(KEY_BUTTON_SCALE, -1f) == 1f &&
                        preferences.getFloat(KEY_BUTTON_OPACITY, -1f) == 1f
                },
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun displaySectionProvidesSlidersAndCustomColorControls() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val root = activity.window.decorView
            layout(root)
            shadowOf(Looper.getMainLooper()).idle()

            val scale = seekBarForLabel(root, "按钮大小")
            scale.progress = 75
            assertEquals("1.25×", findTextView(root, "1.25×")?.text?.toString())

            val opacity = seekBarForLabel(root, "按钮不透明度")
            opacity.progress = 20
            assertEquals("50%", findTextView(root, "50%")?.text?.toString())

            val scheme = spinnerForLabel(root, "色系搭配")
            assertEquals("自定义", scheme.adapter.getItem(scheme.count - 1))
            val recordingColor = findTextView(root, "录制颜色")
                ?: throw AssertionError("找不到录制颜色设置项")
            assertTrue(!recordingColor.isShown)

            scheme.setSelection(scheme.count - 1)
            layout(root)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(recordingColor.isShown)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun promptEditorRejectsInvalidJsonAndPreservesNullDeletionInstructions() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            (findTextView(activity.window.decorView, "新增提示词") as Button).performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val title = editTextForLabel(dialog.window!!.decorView, "标题")
            val prompt = editTextForLabel(dialog.window!!.decorView, "提示词内容")
            val json = editTextForLabel(dialog.window!!.decorView, "附加参数 JSON")
            title.setText("总结重点")
            prompt.setText("总结用户提供的文本")
            json.setText("{invalid")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(dialog.isShowing)
            assertTrue(json.error != null)
            assertTrue(application.settingsRepository.get().postProcessing.prompts.isEmpty())

            val parameters = "{\"model\":\"other-model\",\"nested\":{\"remove\":null},\"array\":[1,2]}"
            json.setText(parameters)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(waitUntil { application.settingsRepository.get().postProcessing.prompts.size == 1 })
            shadowOf(Looper.getMainLooper()).idle()
            val saved = application.settingsRepository.get().postProcessing.prompts.single()
            assertEquals("总结重点", saved.title)
            assertEquals(parameters, saved.additionalJson)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun promptReorderingSurvivesWholeFormSaveAndEditingAllowsDelete() {
        val first = PromptConfig(title = "第一个", prompt = "总结")
        val second = PromptConfig(title = "第二个", prompt = "翻译")
        writePrompts(listOf(first, second))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val root = activity.window.decorView
            findViewWithDescription(root, "上移第二个")!!.performClick()
            // Wait for the writer's main-thread completion, not just its preferences commit.
            assertTrue(waitUntil {
                application.settingsRepository.get().postProcessing.prompts.first().id == second.id &&
                    findViewWithDescription(root, "上移第二个")?.isEnabled == false
            })

            (findTextView(root, "保存设置") as Button).performClick()
            assertTrue(waitUntil {
                application.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                    .contains(KEY_BUTTON_SCALE) && ShadowToast.getTextOfLatestToast() == "设置已保存"
            })
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(second.id, first.id),
                application.settingsRepository.get().postProcessing.prompts.map { it.id })

            findViewWithDescription(root, "编辑提示词：第二个")!!.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            assertTrue(waitUntil { application.settingsRepository.get().postProcessing.prompts.size == 1 })
            assertEquals(first.id, application.settingsRepository.get().postProcessing.prompts.single().id)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun cancelPromptEditingKeepsSavedEntryUnchanged() {
        val original = PromptConfig(title = "原始标题", prompt = "原始提示词")
        writePrompts(listOf(original))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            findViewWithDescription(controller.get().window.decorView, "编辑提示词：原始标题")!!.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            editTextForLabel(dialog.window!!.decorView, "标题").setText("尚未保存")
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(original, application.settingsRepository.get().postProcessing.prompts.single())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun writePrompts(prompts: List<PromptConfig>) {
        var failure: Throwable? = null
        Thread {
            try {
                application.settingsRepository.savePrompts(prompts)
            } catch (error: Throwable) {
                failure = error
            }
        }.apply { start(); join() }
        failure?.let { throw it }
    }

    private fun editTextForLabel(root: View, label: String): EditText {
        val parent = findTextView(root, label)!!.parent as ViewGroup
        return (0 until parent.childCount).map(parent::getChildAt).filterIsInstance<EditText>().single()
    }

    private fun findViewWithDescription(root: View, description: String): View? {
        if (root.contentDescription?.toString() == description) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findViewWithDescription(root.getChildAt(index), description)?.let { return it }
            }
        }
        return null
    }

    private fun clearPreferences() {
        application.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        application.getSharedPreferences(LEGACY_SECURE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun layout(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1_080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1_920, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1_080, 1_920)
    }

    private fun spinnerForLabel(root: View, label: String): Spinner {
        val labelView = findTextView(root, label)
            ?: throw AssertionError("找不到 $label 设置项")
        val row = labelView.parent as? ViewGroup
            ?: throw AssertionError("$label 设置项没有容器")
        return (0 until row.childCount)
            .map(row::getChildAt)
            .filterIsInstance<Spinner>()
            .single()
    }

    private fun seekBarForLabel(root: View, label: String): SeekBar {
        val labelView = findTextView(root, label)
            ?: throw AssertionError("找不到 $label 设置项")
        val row = labelView.parent as? ViewGroup
            ?: throw AssertionError("$label 设置项没有容器")
        return findSeekBars(row).single()
    }

    private fun findSeekBars(root: View): List<SeekBar> = buildList {
        if (root is SeekBar) add(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                addAll(findSeekBars(root.getChildAt(index)))
            }
        }
    }

    private fun findTextView(root: View, text: String): TextView? {
        if (root is TextView && root.text.toString() == text) return root
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) {
            findTextView(root.getChildAt(index), text)?.let { return it }
        }
        return null
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        return condition()
    }

    private companion object {
        const val SETTINGS_PREFS = "settings"
        const val LEGACY_SECURE_PREFS = "secure_settings"
        const val KEY_BIT_DEPTH = "audio.bit_depth"
        const val KEY_SAMPLE_RATE = "audio.sample_rate"
        const val KEY_CODEC = "audio.codec"
        const val KEY_CONTAINER = "audio.container"
        const val KEY_BITRATE = "audio.bitrate"
        const val KEY_BUTTON_SCALE = "display.button_scale"
        const val KEY_BUTTON_OPACITY = "display.button_opacity"
    }
}
