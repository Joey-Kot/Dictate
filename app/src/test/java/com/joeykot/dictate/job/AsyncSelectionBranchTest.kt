package com.joeykot.dictate.job

import com.joeykot.dictate.DictateApplication
import com.joeykot.dictate.accessibility.DictateAccessibilityService
import com.joeykot.dictate.model.AppSettings
import com.joeykot.dictate.model.JobState
import com.joeykot.dictate.model.Pcm16Format
import com.joeykot.dictate.model.PostProcessingConfig
import com.joeykot.dictate.model.PromptConfig
import com.joeykot.dictate.model.ProviderConfig
import com.joeykot.dictate.model.RuntimeSettings
import com.joeykot.dictate.settings.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = DictateApplication::class,
    shadows = [DeferredSelectionServiceShadow::class, SelectionRuntimeSettingsShadow::class],
)
@LooperMode(LooperMode.Mode.PAUSED)
class AsyncSelectionBranchTest {
    private val application: DictateApplication
        get() = RuntimeEnvironment.getApplication() as DictateApplication

    private val controller: VoiceJobController
        get() = application.voiceJobController

    private val prompt = PromptConfig(id = "rewrite", title = "Rewrite", prompt = "Rewrite the text.")
    private val shownMenus = mutableListOf<Pair<String, List<PromptConfig>>>()
    private val releaseWorker = CountDownLatch(1)
    private lateinit var service: DictateAccessibilityService

    @Before
    fun setUp() {
        DeferredSelectionServiceShadow.requests.clear()
        service = Robolectric.buildService(DictateAccessibilityService::class.java).create().get()
        ReflectionHelpers.setStaticField(
            DictateAccessibilityService::class.java,
            "currentInstance",
            service,
        )
        val settings = AppSettings(
            provider = ProviderConfig(baseUrl = "https://asr.example/v1", model = "audio-model"),
            postProcessing = PostProcessingConfig(
                baseUrl = "https://text.example/v1",
                model = "text-model",
                prompts = listOf(prompt),
            ),
        )
        executor("worker").submit {
            application.settingsRepository.save(settings, "", "")
        }.get(5L, TimeUnit.SECONDS)
        val raw = application.audioFileStore.newRawFile(700L).apply {
            writeBytes(ByteArray(6_400) { (it % 127).toByte() })
        }
        checkNotNull(application.audioFileStore.promoteToLast(raw, Pcm16Format.LEGACY_MONO_16_KHZ))
        controller.setPostProcessingMenuListener { text, prompts -> shownMenus += text to prompts }

        // Keep requests queued so assertions do not depend on native FFmpeg or network timing.
        val workerBlocked = CountDownLatch(1)
        executor("worker").submit {
            workerBlocked.countDown()
            releaseWorker.await()
        }
        assertTrue(workerBlocked.await(5L, TimeUnit.SECONDS))
    }

    @After
    fun tearDown() {
        if (controller.currentState().state != JobState.IDLE) {
            controller.handleDoubleTap(controller.currentState().state)
        }
        controller.setPostProcessingMenuListener(null)
        executor("worker").shutdownNow()
        executor("scheduler").shutdownNow()
        releaseWorker.countDown()
        service.onDestroy()
        DeferredSelectionServiceShadow.requests.clear()
    }

    @Test
    fun delayedSelectionOpensMenuWithoutFirstResendingRecording() {
        controller.handleLongPress(JobState.IDLE)

        val pending = DeferredSelectionServiceShadow.requests.single()
        assertTrue(pending.shouldContinue())
        assertEquals(JobState.IDLE, controller.currentState().state)
        assertTrue(shownMenus.isEmpty())

        pending.callback("selected text")

        assertEquals(listOf("selected text" to listOf(prompt)), shownMenus)
        assertEquals(JobState.IDLE, controller.currentState().state)
    }

    @Test
    fun missingSelectionResendsRecordingOnlyAfterReadingCompletes() {
        controller.handleLongPress(JobState.IDLE)
        assertEquals(JobState.IDLE, controller.currentState().state)

        DeferredSelectionServiceShadow.requests.single().callback(null)

        assertEquals(JobState.TRANSCODING, controller.currentState().state)
        assertTrue(shownMenus.isEmpty())
    }

    @Test
    fun newJobInvalidatesSelectionEvenIfItReturnsAfterCancellationToIdle() {
        controller.handleLongPress(JobState.IDLE)
        val pending = DeferredSelectionServiceShadow.requests.single()
        val results = mutableListOf<VoiceJobController.ConnectionTestResult>()

        assertTrue(controller.testPostProcessingConnection(application.settingsRepository.runtime()) {
            results += it
        })
        assertEquals(JobState.REQUESTING, controller.currentState().state)
        assertFalse(pending.shouldContinue())
        controller.handleDoubleTap(JobState.REQUESTING)
        assertEquals(JobState.IDLE, controller.currentState().state)
        assertFalse(pending.shouldContinue())

        // Deliberately deliver a callback that raced with cancellation.
        pending.callback("stale selection")

        assertTrue(shownMenus.isEmpty())
        assertEquals(JobState.IDLE, controller.currentState().state)
        assertEquals(1, results.size)
        assertFalse(results.single().success)
    }

    @Test
    fun replacingListenerAndRepeatingLongPressDiscardEarlierSelectionReads() {
        controller.handleLongPress(JobState.IDLE)
        val detached = DeferredSelectionServiceShadow.requests.single()
        val replacementMenus = mutableListOf<String>()
        controller.setPostProcessingMenuListener { text, _ -> replacementMenus += text }

        assertFalse(detached.shouldContinue())
        detached.callback("detached menu selection")
        assertTrue(shownMenus.isEmpty())
        assertTrue(replacementMenus.isEmpty())

        controller.handleLongPress(JobState.IDLE)
        val superseded = DeferredSelectionServiceShadow.requests.last()
        controller.handleLongPress(JobState.IDLE)
        val current = DeferredSelectionServiceShadow.requests.last()

        assertFalse(superseded.shouldContinue())
        assertTrue(current.shouldContinue())
        superseded.callback(null)
        assertEquals(JobState.IDLE, controller.currentState().state)
        assertTrue(replacementMenus.isEmpty())

        current.callback("latest selection")

        assertEquals(listOf("latest selection"), replacementMenus)
        assertTrue(shownMenus.isEmpty())
        assertEquals(JobState.IDLE, controller.currentState().state)
    }

    private fun executor(name: String): ExecutorService = ReflectionHelpers.getField(controller, name)
}

@Implements(value = DictateAccessibilityService::class, isInAndroidSdk = false)
class DeferredSelectionServiceShadow : ShadowAccessibilityService() {
    data class PendingRead(
        val shouldContinue: () -> Boolean,
        val callback: (String?) -> Unit,
    )

    @Implementation
    fun readSelectedText(shouldContinue: () -> Boolean, callback: (String?) -> Unit) {
        requests += PendingRead(shouldContinue, callback)
    }

    companion object {
        val requests = mutableListOf<PendingRead>()
    }
}

@Implements(value = SettingsRepository::class, isInAndroidSdk = false)
class SelectionRuntimeSettingsShadow {
    @RealObject
    private lateinit var repository: SettingsRepository

    @Implementation
    fun runtime(): RuntimeSettings = RuntimeSettings(
        app = repository.get(),
        apiKey = "local-test-key",
        postProcessingApiKey = "local-test-key",
    )
}
