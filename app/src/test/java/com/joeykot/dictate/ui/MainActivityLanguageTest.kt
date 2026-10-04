package com.joeykot.dictate.ui

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import com.joeykot.dictate.DictateApplication
import com.joeykot.dictate.i18n.AppStrings
import com.joeykot.dictate.model.AppLanguage
import com.joeykot.dictate.settings.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
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
@Config(sdk = [35], application = DictateApplication::class, qualifiers = "zh-rCN")
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityLanguageTest {
    private val application: DictateApplication
        get() = RuntimeEnvironment.getApplication() as DictateApplication

    @Before
    fun setUp() {
        clearPreferences()
        AppStrings.refresh(application)
    }

    @After
    fun tearDown() {
        clearPreferences()
        AppStrings.refresh(application)
    }

    @Test
    fun defaultsToEnglishEvenWhenTheSystemLanguageIsChinese() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertEquals(AppLanguage.ENGLISH, application.settingsRepository.get().language)
            assertEquals("en", activity.resources.configuration.locales[0].language)
            assertEquals("English", languageSpinner(activity.window.decorView).selectedItem)
            assertNotNull(findText(activity.window.decorView, "Audio Record Settings"))
            assertNotNull(findText(activity.window.decorView, "Add prompt"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun languageSelectorListsAllSixLanguagesInOrderAboveAudioOutput() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val root = controller.get().window.decorView
            val spinner = languageSpinner(root)
            assertEquals(
                listOf("English", "中文", "日本語", "Deutsch", "Français", "Русский"),
                (0 until spinner.count).map { spinner.adapter.getItem(it).toString() },
            )
            val languageHeader = findText(root, "Language")!!
            val audioHeader = findText(root, "Audio Record Settings")!!
            val sections = languageHeader.parent as ViewGroup
            assertSame(sections, audioHeader.parent)
            assertTrue(sections.indexOfChild(languageHeader) < sections.indexOfChild(audioHeader))
            val languagePanel = spinner.parent as ViewGroup
            assertSame(sections, languagePanel.parent)
            assertTrue(sections.indexOfChild(languageHeader) < sections.indexOfChild(languagePanel))
            assertTrue(sections.indexOfChild(languagePanel) < sections.indexOfChild(audioHeader))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun savingLanguageRecreatesTheInterfaceAndPersistsAcrossRecreationAndRelaunch() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val original = controller.get()
            languageSpinner(original.window.decorView).setSelection(3)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Deutsch", languageSpinner(original.window.decorView).selectedItem)
            assertEquals(AppLanguage.ENGLISH, application.settingsRepository.get().language)
            assertNotNull(findText(original.window.decorView, "Language"))

            (findText(original.window.decorView, "Save settings") as Button).performClick()
            assertTrue("Saving the language should rebuild the visible interface", waitUntil {
                controller.get() !== original &&
                    controller.get().resources.configuration.locales[0].language == "de"
            })
            assertEquals("de", application.settingsRepository.get().language.tag)
            assertEquals("de", application.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("app.language", null))
            assertGermanInterface(controller.get())

            val beforeRecreation = controller.get()
            controller.recreate()
            assertNotSame(beforeRecreation, controller.get())
            assertGermanInterface(controller.get())
        } finally {
            controller.pause().stop().destroy()
        }

        // Reopen both settings and the screen without the previous Activity or saved state.
        assertEquals("de", SettingsRepository(application).get().language.tag)
        val relaunched = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            assertGermanInterface(relaunched.get())
        } finally {
            relaunched.pause().stop().destroy()
        }
    }

    private fun assertGermanInterface(activity: MainActivity) {
        val root = activity.window.decorView
        assertEquals("de", activity.resources.configuration.locales[0].language)
        assertEquals("Deutsch", languageSpinner(root).selectedItem)
        assertNotNull(findText(root, "Sprache"))
        assertNotNull(findText(root, activity.getString(com.joeykot.dictate.R.string.main_audio_section)))
        assertNotNull(findText(root, "Prompt hinzufügen"))
    }

    private fun languageSpinner(root: View): Spinner = descendants(root)
        .filterIsInstance<Spinner>()
        .single { it.count == 6 && it.adapter.getItem(0) == "English" }

    private fun findText(root: View, text: String): TextView? = descendants(root)
        .filterIsInstance<TextView>()
        .firstOrNull { it.text.toString() == text }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
        }
    }

    private fun clearPreferences() {
        listOf("settings", "secure_settings").forEach {
            application.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        return condition()
    }
}
