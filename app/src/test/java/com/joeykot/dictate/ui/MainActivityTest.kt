package com.joeykot.dictate.ui

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import com.joeykot.dictate.DictateApplication
import com.joeykot.dictate.model.AudioCodec
import com.joeykot.dictate.model.AudioConfig
import com.joeykot.dictate.model.AudioContainer
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
            .putInt(KEY_BIT_DEPTH, AudioConfig.DEFAULT_BIT_DEPTH)
            .putInt(KEY_SAMPLE_RATE, AudioConfig.DEFAULT_SAMPLE_RATE)
            .putString(KEY_CODEC, AudioCodec.OPUS.name)
            .putString(KEY_CONTAINER, AudioContainer.OGG.name)
            .putInt(KEY_BITRATE, AudioConfig.DEFAULT_BITRATE_KBPS)
            .commit()
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
                    preferences.getString(KEY_CONTAINER, null) == AudioContainer.OGG.name
                },
            )
        } finally {
            controller.pause().stop().destroy()
        }
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
    }
}
