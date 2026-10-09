package com.joeykot.dictate.job

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.joeykot.dictate.DictateApplication
import com.joeykot.dictate.accessibility.TextDelivery
import com.joeykot.dictate.accessibility.DictateAccessibilityService
import com.joeykot.dictate.advanced_audio.AdvancedAudioClient
import com.joeykot.dictate.advanced_audio.AdvancedAudioWorkflow
import com.joeykot.dictate.advanced_audio.AdvancedAudioWorkflowCodec
import com.joeykot.dictate.advanced_audio.RealtimeSessionRecognition
import com.joeykot.dictate.advanced_audio.RealtimeWorkflow
import com.joeykot.dictate.advanced_audio.RuntimeTemplateValues
import com.joeykot.dictate.advanced_audio.WorkflowValidator
import com.joeykot.dictate.advanced_audio.http.AdvancedCancellationSource
import com.joeykot.dictate.advanced_audio.remote_audio.RemoteAudioConfigValidator
import com.joeykot.dictate.audio.AudioEncodingPlan
import com.joeykot.dictate.audio.AudioTranscoder
import com.joeykot.dictate.audio.AudioTranscoderSegmentMediaEncoder
import com.joeykot.dictate.audio.CancellableSegmentMediaEncoder
import com.joeykot.dictate.audio.PcmCaptureTee
import com.joeykot.dictate.audio.PcmPacketSink
import com.joeykot.dictate.audio.RecordingService
import com.joeykot.dictate.audio.SegmentMediaEncodeRequest
import com.joeykot.dictate.audio.SegmentMediaEncodeResult
import com.joeykot.dictate.audio.SegmentedUploadCancellation
import com.joeykot.dictate.audio.SegmentedUploadParameters
import com.joeykot.dictate.audio.SegmentedUploadPlanSource
import com.joeykot.dictate.audio.SegmentedUploadPreparationRequest
import com.joeykot.dictate.audio.SegmentedUploadPreparationResult
import com.joeykot.dictate.audio.SegmentedUploadPreparer
import com.joeykot.dictate.audio.SegmentedUploadSnapshot
import com.joeykot.dictate.audio.SegmentedUploadSnapshotCodec
import com.joeykot.dictate.advanced_audio.realtime.LivePcmTeeSource
import com.joeykot.dictate.advanced_audio.realtime.Pcm16ReplayChunkSource
import com.joeykot.dictate.advanced_audio.realtime.RealtimeSessionResult
import com.joeykot.dictate.advanced_audio.realtime.RealtimeSessionRunner
import com.joeykot.dictate.R
import com.joeykot.dictate.i18n.AppStrings
import com.joeykot.dictate.model.JobState
import com.joeykot.dictate.model.JobUiState
import com.joeykot.dictate.model.Pcm16Format
import com.joeykot.dictate.model.RemoteAudioCredentialIds
import com.joeykot.dictate.model.RuntimeSettings
import com.joeykot.dictate.model.PromptConfig
import com.joeykot.dictate.model.ResolvedPostProcessingApi
import com.joeykot.dictate.network.AdditionalParameters
import com.joeykot.dictate.network.BaseUrl
import com.joeykot.dictate.network.SegmentedUploadBatchRunner
import com.joeykot.dictate.network.TranscriptionClient
import com.joeykot.dictate.network.PostProcessingClient
import com.joeykot.dictate.settings.SettingsRepository
import com.joeykot.dictate.ui.MainActivity
import com.joeykot.dictate.util.AudioFileStore
import com.joeykot.dictate.util.Diagnostics
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.time.Instant
import java.util.UUID

class VoiceJobController(
    private val application: DictateApplication,
    private val settingsRepository: SettingsRepository,
    private val fileStore: AudioFileStore,
    private val diagnostics: Diagnostics,
) {
    fun interface Listener {
        fun onStateChanged(state: JobUiState)
    }

    data class ConnectionTestResult(
        val success: Boolean,
        val statusCode: Int? = null,
        val elapsedMillis: Long? = null,
        val text: String = "",
        val message: String = "",
        val serverSummary: String = "",
    )

    private enum class JobMode(val postProcessing: Boolean = false, val connectionTest: Boolean = false) {
        VOICE,
        CONNECTION_TEST(connectionTest = true),
        POST_PROCESSING(postProcessing = true),
        POST_PROCESSING_TEST(postProcessing = true, connectionTest = true),
        PROMPT_CONNECTION_TEST(postProcessing = true, connectionTest = true),
    }

    /**
     * Closes before a job stops being current.  The worker can therefore
     * never persist a frozen segment plan after a newer recording has been
     * promoted to the shared “last recording” location.
     */
    private class SegmentedSnapshotWriteGate {
        private var closed = false

        fun close() {
            synchronized(this) {
                closed = true
            }
        }

        fun ifOpen(action: () -> Unit) {
            synchronized(this) {
                if (!closed) action()
            }
        }
    }

    private data class ActiveJob(
        val id: Long,
        val mode: JobMode,
        var rawFile: File? = null,
        var runtimeSettings: RuntimeSettings?,
        var rawFormat: Pcm16Format? = null,
        var outputFile: File? = null,
        var encodingPlan: AudioEncodingPlan? = null,
        var recordingClosed: Boolean = false,
        var retryCount: Int = 0,
        var workerFuture: Future<*>? = null,
        var retryFuture: ScheduledFuture<*>? = null,
        var advancedCancellationSource: AdvancedCancellationSource? = null,
        @Volatile var legacyTranscriptionRegistration: TranscriptionClient.JobRegistration? = null,
        @Volatile var segmentedUploadSnapshot: SegmentedUploadSnapshot? = null,
        /**
         * A segmented attempt is allowed to drain after user cancellation so
         * advanced remote-audio cleanup runs. These fields are thread-safe
         * because preparation and batch workers read them off the main thread.
         */
        val segmentedCancellationRequested: AtomicBoolean = AtomicBoolean(false),
        val segmentedUploadInProgress: AtomicBoolean = AtomicBoolean(false),
        /** Planned IDs are retained for diagnostics; only active IDs are sent to FFmpeg cancel. */
        val segmentedEncodingOperationIds: MutableSet<Long> = ConcurrentHashMap.newKeySet(),
        val activeSegmentEncodingOperationIds: MutableSet<Long> = ConcurrentHashMap.newKeySet(),
        /** Serializes cancellation with the planned-to-active FFmpeg hand-off. */
        val segmentedEncodingLock: Any = Any(),
        val segmentedCancelInFlight: AtomicReference<(() -> Unit)?> = AtomicReference(null),
        val segmentedSnapshotWriteGate: SegmentedSnapshotWriteGate = SegmentedSnapshotWriteGate(),
        var realtimeRecording: RealtimeRecording? = null,
        val testCallback: ((ConnectionTestResult) -> Unit)? = null,
        val inputText: String = "",
        val prompt: PromptConfig? = null,
        val postProcessingApi: ResolvedPostProcessingApi? = null,
    )

    /**
     * One logical microphone recording can have several live websocket
     * sessions because a pause always finalizes its current session. Their
     * texts are usable only when every session reaches explicit completion;
     * otherwise the complete raw PCM file is replayed from zero.
     */
    private data class RealtimeRecording(
        val runtime: RuntimeSettings,
        val workflow: AdvancedAudioWorkflow,
        val captureFormat: Pcm16Format,
        val cancellation: AdvancedCancellationSource,
        val segments: MutableList<RealtimeSegment> = mutableListOf(),
        var activeSegment: RealtimeSegment? = null,
        var liveFailed: Boolean = false,
        var recordingCompleted: Boolean = false,
        var replayStarted: Boolean = false,
    )

    private data class RealtimeSegment(
        val tee: PcmCaptureTee,
        var closed: Boolean = false,
        var terminal: Boolean = false,
        var text: String? = null,
        var future: Future<*>? = null,
    )

    /**
     * Immutable state shared by all attempts of one complete local PCM replay.
     * Each attempt still creates a new source and websocket session, while
     * template runtime values remain stable for the logical replay.
     */
    private data class RealtimeReplayContext(
        val rawFile: File,
        val rawFormat: Pcm16Format,
        val realtime: RealtimeWorkflow,
        val runtime: RuntimeTemplateValues,
        val cancellation: AdvancedCancellationSource,
        val startedAtNanos: Long,
    )

    /** Terminal batch result before it is routed through the existing per-API handlers. */
    private sealed interface SegmentedRequestResult {
        data class Legacy(val result: TranscriptionClient.Result) : SegmentedRequestResult
        data class Advanced(val result: AdvancedAudioClient.Result) : SegmentedRequestResult
        data object Cancelled : SegmentedRequestResult
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dictate-voice-job")
    }
    private val realtimeWorker = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "dictate-realtime-session")
    }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dictate-retry-wait")
    }
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val nextJobId = AtomicLong(0L)
    private val transcoder = AudioTranscoder(application, diagnostics)
    private val client = TranscriptionClient(diagnostics)
    private val advancedAudioClient = AdvancedAudioClient()
    private val segmentedUploadPreparer = SegmentedUploadPreparer()
    private val segmentedUploadBatchRunner = SegmentedUploadBatchRunner()
    private val postProcessingClient = PostProcessingClient(diagnostics)
    private var postProcessingMenuListener: ((String, List<PromptConfig>) -> Unit)? = null
    private var selectionRequestId = 0L
    private val recordingServiceIntent = Intent(application, RecordingService::class.java)
    private val pendingPreserveAfterRecorderStops = mutableSetOf<Long>()

    @Volatile
    private var activeJob: ActiveJob? = null

    @Volatile
    private var uiState = JobUiState()

    private var uiMessage: (() -> String)? = { AppStrings.get(R.string.runtime_idle, "Idle") }

    @Volatile
    private var uiVersion = 0L

    @Volatile
    private var lastAmplitudeDispatchAt = 0L

    private var lastPromotedJobId = 0L
    /** Negative IDs cannot collide with regular positive job IDs in AudioTranscoder. */
    private val nextSegmentEncodingOperationId = AtomicLong(0L)

    fun addListener(listener: Listener) {
        listeners.add(listener)
        val version = uiVersion
        mainHandler.post {
            if (version == uiVersion) listener.onStateChanged(uiState)
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun currentState(): JobUiState = uiState

    fun refreshLanguage() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val message = uiMessage?.invoke() ?: return
        val state = uiState.copy(message = message)
        uiState = state
        // Changing the display language must not invalidate an in-flight selection read or job.
        listeners.forEach { it.onStateChanged(state) }
    }

    fun setPostProcessingMenuListener(listener: ((String, List<PromptConfig>) -> Unit)?) {
        selectionRequestId++
        postProcessingMenuListener = listener
    }

    fun handleSingleTap(expectedState: JobState) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (uiState.state != expectedState) return
        selectionRequestId++
        when (expectedState) {
            JobState.IDLE -> startRecording()
            JobState.RECORDING -> stopRecording()
            JobState.PAUSED,
            JobState.TRANSCODING,
            JobState.REQUESTING,
            JobState.RETRY_WAITING,
            -> Unit
        }
    }

    fun handleLongPress(expectedState: JobState) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (uiState.state != expectedState) return
        val requestId = ++selectionRequestId
        when (expectedState) {
            JobState.IDLE -> {
                val prompts = settingsRepository.get().postProcessing.prompts
                val service = DictateAccessibilityService.current()
                if (prompts.isEmpty() || service == null) {
                    resendLastRecording()
                    return
                }
                val version = uiVersion
                val isCurrentRead = {
                    requestId == selectionRequestId && version == uiVersion &&
                        uiState.state == JobState.IDLE && activeJob == null &&
                        DictateAccessibilityService.current() === service
                }
                service.readSelectedText(isCurrentRead) { selected ->
                    if (isCurrentRead()) {
                        if (shouldOpenPostProcessingMenu(selected, prompts.size)) {
                            postProcessingMenuListener?.invoke(checkNotNull(selected), prompts)
                        } else {
                            resendLastRecording()
                        }
                    }
                }
            }
            JobState.RECORDING -> pauseRecording()
            JobState.PAUSED -> resumeRecording()
            JobState.TRANSCODING,
            JobState.REQUESTING,
            JobState.RETRY_WAITING,
            -> Unit
        }
    }

    fun handleDoubleTap(expectedState: JobState) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (uiState.state != expectedState) return
        selectionRequestId++
        when (expectedState) {
            JobState.IDLE -> Unit
            JobState.RECORDING,
            JobState.PAUSED,
            JobState.TRANSCODING,
            JobState.REQUESTING,
            JobState.RETRY_WAITING,
            -> cancelActiveJob()
        }
    }

    fun testConnection(
        runtimeSettings: RuntimeSettings,
        callback: (ConnectionTestResult) -> Unit,
    ): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (uiState.state != JobState.IDLE || activeJob != null) {
            callback(ConnectionTestResult(false, message = AppStrings.get(R.string.runtime_busy, "A task is already running")))
            return false
        }
        val validationError = validateRuntimeSettings(runtimeSettings)
        if (validationError != null) {
            callback(ConnectionTestResult(false, message = validationError))
            return false
        }

        val jobId = nextJobId.incrementAndGet()
        val rawFile = fileStore.newConnectivityRawFile(jobId)
        val job = ActiveJob(
            id = jobId,
            mode = JobMode.CONNECTION_TEST,
            rawFile = rawFile,
            rawFormat = Pcm16Format.LEGACY_MONO_16_KHZ,
            runtimeSettings = runtimeSettings,
            recordingClosed = true,
            testCallback = callback,
        )
        activeJob = job
        updateUi(jobId, JobState.TRANSCODING) {
            AppStrings.get(R.string.runtime_preparing_test_audio, "Preparing connection test audio")
        }
        job.workerFuture = worker.submit {
            val prepared = runCatching {
                ConnectivityTestAudio.writeTo(application, rawFile)
                rawFile.isFile && rawFile.length() > 0L
            }.getOrDefault(false)
            if (!isCurrent(jobId)) {
                rawFile.delete()
                return@submit
            }
            mainHandler.post {
                if (!isCurrent(jobId)) return@post
                if (!prepared) {
                    finishFailure(jobId, AppStrings.get(R.string.runtime_test_audio_unreadable, "Unable to read the built-in test audio"))
                } else {
                    beginTranscode(jobId)
                }
            }
        }
        return true
    }

    fun startPostProcessing(input: String, promptId: String): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (uiState.state != JobState.IDLE || activeJob != null || input.isEmpty()) return false
        val runtime = settingsRepository.runtime()
        val prompt = runtime.app.postProcessing.prompts.firstOrNull { it.id == promptId }
        if (prompt == null) {
            showToast(AppStrings.get(R.string.runtime_prompt_deleted, "This prompt has been deleted"))
            return false
        }
        return beginPostProcessing(runtime, prompt, input, null)
    }

    fun testPostProcessingConnection(
        runtime: RuntimeSettings,
        callback: (ConnectionTestResult) -> Unit,
    ): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (uiState.state != JobState.IDLE || activeJob != null) {
            callback(ConnectionTestResult(false, message = AppStrings.get(R.string.runtime_busy, "A task is already running")))
            return false
        }
        return beginPostProcessing(
            runtime,
            PromptConfig(title = AppStrings.get(R.string.runtime_test_connection, "Test connection"), prompt = "Reply briefly with OK."),
            "Connection test.",
            callback,
        )
    }

    fun testPromptConnection(
        runtime: RuntimeSettings,
        prompt: PromptConfig,
        apiKey: String,
        callback: (ConnectionTestResult) -> Unit,
    ): Long? {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (uiState.state != JobState.IDLE || activeJob != null) {
            callback(ConnectionTestResult(false, message = AppStrings.get(R.string.runtime_busy, "A task is already running")))
            return null
        }
        val probe = PromptConfig(
            id = prompt.id,
            title = AppStrings.get(R.string.runtime_test_connection, "Test connection"),
            prompt = "Reply briefly with OK.",
            provider = prompt.provider,
            baseUrl = prompt.baseUrl,
            model = prompt.model,
        )
        val accepted = beginPostProcessing(
            runtime.copy(promptApiKeys = mapOf(prompt.id to apiKey)), probe, "Connection test.", callback, promptTest = true,
        )
        return if (accepted) activeJob?.id else null
    }

    fun cancelPromptConnectionTest(jobId: Long) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (activeJob?.let { it.id == jobId && it.mode == JobMode.PROMPT_CONNECTION_TEST } == true) cancelActiveJob()
    }

    private fun beginPostProcessing(
        runtime: RuntimeSettings,
        prompt: PromptConfig,
        input: String,
        callback: ((ConnectionTestResult) -> Unit)?,
        promptTest: Boolean = false,
    ): Boolean {
        val retryError = if (promptTest) null else runtime.app.retry.validate().firstOrNull()
        if (retryError != null) {
            if (callback != null) callback(ConnectionTestResult(false, message = retryError))
            else showToast(retryError)
            return false
        }
        val jobId = nextJobId.incrementAndGet()
        activeJob = ActiveJob(
            id = jobId,
            mode = when {
                promptTest -> JobMode.PROMPT_CONNECTION_TEST
                callback == null -> JobMode.POST_PROCESSING
                else -> JobMode.POST_PROCESSING_TEST
            },
            runtimeSettings = runtime,
            inputText = input,
            prompt = prompt,
            postProcessingApi = prompt.effectiveApi(
                runtime.app.postProcessing, runtime.postProcessingApiKey, runtime.promptApiKeys[prompt.id].orEmpty(),
            ),
            testCallback = callback,
        )
        startRequest(jobId)
        return true
    }

    /**
     * Called synchronously by [RecordingService] before AudioRecorder starts
     * sampling. A realtime workflow receives its bounded tee here;
     * ordinary workflows intentionally keep their existing end-of-recording
     * settings snapshot behavior.
     */
    fun onRecordingStarted(jobId: Long, format: Pcm16Format): PcmPacketSink? {
        val job = activeJob?.takeIf { it.id == jobId } ?: return null
        job.rawFormat = format
        diagnostics.info("recording", "job=$jobId started format=${format.summary()}")

        val runtime = settingsRepository.runtime()
        val workflow = realtimeWorkflow(runtime) ?: return null
        val recording = RealtimeRecording(
            runtime = runtime,
            workflow = workflow,
            captureFormat = format,
            cancellation = AdvancedCancellationSource(),
        )
        job.runtimeSettings = runtime
        job.realtimeRecording = recording
        job.advancedCancellationSource = recording.cancellation
        return startRealtimeLiveSegment(job, recording, format)
    }

    /** The service has closed this segment's tee at a clean PCM pause boundary. */
    fun onRecordingPaused(jobId: Long) {
        val job = activeJob?.takeIf { it.id == jobId } ?: return
        val recording = job.realtimeRecording ?: return
        recording.activeSegment?.let { segment ->
            segment.closed = true
            recording.activeSegment = null
        }
    }

    /**
     * Called before AudioRecord resumes. A prior pause must have finished its
     * websocket finalization before a fresh live session can safely begin.
     * If it has not, this capture continues locally and is replayed in full
     * after Stop instead of allowing concurrent live sessions to lose audio.
     */
    fun onRecordingResuming(jobId: Long, format: Pcm16Format): PcmPacketSink? {
        val job = activeJob?.takeIf { it.id == jobId } ?: return null
        val recording = job.realtimeRecording ?: return null
        if (recording.captureFormat != format) {
            markRealtimeLiveFailure(job, recording)
            return null
        }
        if (recording.liveFailed || recording.activeSegment != null ||
            recording.segments.any { !it.terminal }
        ) {
            markRealtimeLiveFailure(job, recording)
            return null
        }
        return startRealtimeLiveSegment(job, recording, format)
    }

    /** Resume could not start after a new tee was prepared. */
    fun onRecordingResumeFailed(jobId: Long) {
        val job = activeJob?.takeIf { it.id == jobId } ?: return
        job.realtimeRecording?.let { recording -> markRealtimeLiveFailure(job, recording) }
    }

    fun onRecordingAmplitude(jobId: Long, amplitude: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastAmplitudeDispatchAt < AMPLITUDE_INTERVAL_MS) return
        lastAmplitudeDispatchAt = now
        mainHandler.post {
            if (!isCurrent(jobId) || uiState.state != JobState.RECORDING) return@post
            updateUi(jobId, uiState.copy(amplitude = amplitude), uiMessage)
        }
    }

    fun onRecordingCompleted(
        jobId: Long,
        file: File,
        valid: Boolean,
        discarded: Boolean,
        unexpected: Boolean,
        format: Pcm16Format?,
    ) {
        mainHandler.post {
            if (pendingPreserveAfterRecorderStops.remove(jobId)) {
                if (valid && !discarded) promoteRecording(jobId, file, format) else file.delete()
                return@post
            }
            val job = activeJob
            if (job?.id != jobId) return@post
            job.recordingClosed = true
            job.rawFormat = format

            if (discarded) {
                file.delete()
                finishFailure(jobId, AppStrings.get(R.string.runtime_recording_discarded, "Recording discarded"), showToast = false)
                return@post
            }
            if (!valid) {
                file.delete()
                finishFailure(jobId, AppStrings.get(R.string.runtime_recording_too_short, "The recording is too short or contains no valid audio"))
                return@post
            }

            val promoted = promoteRecording(jobId, file, format)
            if (promoted == null) {
                finishFailure(
                    jobId,
                    if (format == null) {
                        AppStrings.get(R.string.runtime_recording_pcm_missing, "The PCM format of this recording is missing")
                    } else {
                        AppStrings.get(R.string.runtime_recording_save_failed, "Unable to save this recording")
                    },
                )
                return@post
            }
            job.rawFile = promoted.file
            job.rawFormat = promoted.format

            if (unexpected) {
                finishFailure(jobId, AppStrings.get(R.string.runtime_service_stopped_preserved, "The recording service stopped unexpectedly; usable audio was saved"))
                return@post
            }
            if (uiState.state != JobState.TRANSCODING) {
                finishFailure(jobId, AppStrings.get(R.string.runtime_state_invalid_preserved, "The recording entered an unexpected state; usable audio was saved"))
                return@post
            }
            job.realtimeRecording?.let { recording ->
                // AudioRecorder closes its tee before invoking this callback.
                // The runner can now flush the final converted packet, send
                // finish, and wait for explicit completion.
                recording.activeSegment?.let { segment ->
                    segment.closed = true
                    recording.activeSegment = null
                }
                recording.recordingCompleted = true
                updateUi(jobId, JobState.REQUESTING) {
                    AppStrings.get(R.string.runtime_requesting_transcription, "Requesting transcription")
                }
                maybeCompleteRealtimeRecording(job, recording)
                return@post
            }
            job.runtimeSettings = settingsRepository.runtime()
            beginTranscode(jobId)
        }
    }

    fun onRecordingFailed(
        jobId: Long,
        file: File,
        valid: Boolean,
        message: String,
        format: Pcm16Format?,
    ) {
        mainHandler.post {
            if (pendingPreserveAfterRecorderStops.remove(jobId)) {
                if (valid) promoteRecording(jobId, file, format) else file.delete()
                return@post
            }
            if (!isCurrent(jobId)) return@post
            if (valid) promoteRecording(jobId, file, format) else file.delete()
            finishFailure(jobId, message)
        }
    }

    fun onRecordingServiceFailed(jobId: Long, message: String) {
        mainHandler.post {
            val job = activeJob
            if (job?.id != jobId) return@post
            job.rawFile?.delete()
            finishFailure(jobId, message)
        }
    }

    private fun startRecording() {
        if (application.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showToast(AppStrings.get(R.string.runtime_microphone_permission, "Grant microphone permission first"))
            application.startActivity(
                Intent(application, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_REQUEST_MICROPHONE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        if (activeJob != null) return

        val jobId = nextJobId.incrementAndGet()
        val rawFile = fileStore.newRawFile(jobId)
        rawFile.delete()
        activeJob = ActiveJob(
            id = jobId,
            mode = JobMode.VOICE,
            rawFile = rawFile,
            runtimeSettings = null,
        )
        updateUi(jobId, JobState.RECORDING) { AppStrings.get(R.string.runtime_recording, "Recording") }

        try {
            val intent = RecordingService.startIntent(application, jobId, rawFile)
            application.startForegroundService(intent)
        } catch (error: Exception) {
            rawFile.delete()
            finishFailure(
                jobId,
                AppStrings.get(R.string.runtime_recording_service_start_failed, "Unable to start the recording service: %1\$s", error.message ?: error.javaClass.simpleName),
            )
        }
    }

    private fun pauseRecording() {
        val job = activeJob ?: return
        if (uiState.state != JobState.RECORDING) return
        try {
            application.startService(RecordingService.pauseIntent(application, job.id))
            updateUi(job.id, JobState.PAUSED) { AppStrings.get(R.string.runtime_paused, "Recording paused") }
        } catch (error: Exception) {
            abortRecorderWithPreservation(job, AppStrings.get(R.string.runtime_pause_failed, "Unable to pause recording: %1\$s", error.message ?: error.javaClass.simpleName))
        }
    }

    private fun resumeRecording() {
        val job = activeJob ?: return
        if (uiState.state != JobState.PAUSED) return
        try {
            application.startService(RecordingService.resumeIntent(application, job.id))
            updateUi(job.id, JobState.RECORDING) { AppStrings.get(R.string.runtime_recording, "Recording") }
        } catch (error: Exception) {
            abortRecorderWithPreservation(job, AppStrings.get(R.string.runtime_resume_failed, "Unable to resume recording: %1\$s", error.message ?: error.javaClass.simpleName))
        }
    }

    private fun stopRecording() {
        val job = activeJob ?: return
        if (uiState.state != JobState.RECORDING) return
        updateUi(job.id, JobState.TRANSCODING) {
            AppStrings.get(R.string.runtime_stopping_recording, "Finishing recording")
        }
        try {
            application.startService(RecordingService.stopIntent(application, job.id))
        } catch (error: Exception) {
            abortRecorderWithPreservation(job, AppStrings.get(R.string.runtime_stop_failed, "Unable to stop recording: %1\$s", error.message ?: error.javaClass.simpleName))
        }
    }

    private fun resendLastRecording() {
        if (activeJob != null) return
        val lastRecording = fileStore.lastRecording()
        if (lastRecording == null) {
            showToast(AppStrings.get(R.string.runtime_no_previous_recording, "No previous recording to resend"))
            return
        }
        val runtime = settingsRepository.runtime()
        val frozenSegmentedUpload = loadLastRecordingSegmentedUploadSnapshot(lastRecording)
        val jobId = nextJobId.incrementAndGet()
        activeJob = ActiveJob(
            id = jobId,
            mode = JobMode.VOICE,
            rawFile = lastRecording.file,
            rawFormat = lastRecording.format,
            runtimeSettings = runtime,
            recordingClosed = true,
            segmentedUploadSnapshot = frozenSegmentedUpload,
        )
        updateUi(jobId, JobState.TRANSCODING) {
            AppStrings.get(R.string.runtime_retranscoding, "Transcoding again with the current settings")
        }
        beginTranscode(jobId)
    }

    /**
     * A valid sidecar takes precedence over the current segmented-upload
     * settings for retranscription.  It freezes the original PCM partition
     * and concurrency contract; malformed or stale sidecars are discarded so
     * an ordinary retranscription can still use the current configuration.
     */
    private fun loadLastRecordingSegmentedUploadSnapshot(
        recording: AudioFileStore.RawRecording,
    ): SegmentedUploadSnapshot? {
        val encoded = fileStore.lastRecordingSegmentedUploadSnapshot(recording) ?: return null
        val snapshot = runCatching { SegmentedUploadSnapshotCodec.decode(encoded) }.getOrNull()
        val currentFrames = recording.file.length().takeIf {
            it >= 0L && it % recording.format.bytesPerFrame == 0L
        }?.div(recording.format.bytesPerFrame.toLong())
        if (
            snapshot == null ||
            snapshot.plan.sourceRateHz != recording.format.sampleRateHz ||
            snapshot.plan.totalFrames != currentFrames
        ) {
            fileStore.clearLastRecordingSegmentedUploadSnapshotIfUnchanged(recording, encoded)
            return null
        }
        return snapshot
    }

    private fun beginTranscode(jobId: Long) {
        val job = activeJob?.takeIf { it.id == jobId } ?: return
        val rawFile = job.rawFile ?: return
        val runtime = job.runtimeSettings ?: run {
            finishFailure(jobId, AppStrings.get(R.string.runtime_transcription_settings_missing, "Transcription settings are missing"))
            return
        }
        val rawFormat = job.rawFormat ?: run {
            finishFailure(jobId, AppStrings.get(R.string.runtime_raw_format_missing, "The original recording format is missing"))
            return
        }
        val validationError = validateRuntimeSettings(runtime)
        if (validationError != null) {
            finishFailure(jobId, validationError)
            return
        }

        // Realtime workflows always consume the authoritative raw PCM16
        // recording and its actual AudioRecord format. They must never use a
        // post-transcode file, including for the connection-test recording.
        realtimeWorkflow(runtime)?.let {
            val recording = ensureRealtimeRecording(job, runtime, rawFormat)
            if (recording == null) {
                finishFailure(jobId, ADVANCED_REALTIME_FAILURE_MESSAGE)
            } else {
                recording.recordingCompleted = true
                startRealtimeReplay(job, recording)
            }
            return
        }

        val plan = AudioEncodingPlan.resolve(runtime.app.audio, rawFormat)
        job.encodingPlan = plan
        diagnostics.info("ffmpeg", "job=$jobId requested=${runtime.app.audio} resolved=${plan.config} layout=${plan.layout.name}")
        if (shouldUseSegmentedUpload(job, runtime)) {
            startSegmentedUpload(job, runtime, rawFile, rawFormat, plan)
            return
        }
        val output = fileStore.newEncodedFile(jobId, plan.config.container)
        job.outputFile = output
        updateUi(jobId, JobState.TRANSCODING) {
            AppStrings.get(R.string.runtime_transcoding_to, "Transcoding to %1\$s", plan.config.container.value.uppercase())
        }
        job.workerFuture = worker.submit {
            if (!isCurrent(jobId)) return@submit
            val result = transcoder.transcode(jobId, rawFile, rawFormat, output, plan)
            mainHandler.post {
                if (!isCurrent(jobId)) return@post
                when (result) {
                    is AudioTranscoder.Result.Success -> startRequest(jobId)
                    is AudioTranscoder.Result.Failure -> finishFailure(jobId, result.message)
                    AudioTranscoder.Result.Cancelled -> Unit
                }
            }
        }
    }

    private fun startRequest(jobId: Long) {
        val job = activeJob?.takeIf { it.id == jobId } ?: return
        val runtime = job.runtimeSettings ?: return
        if (job.mode.postProcessing) {
            startPostProcessingRequest(job, runtime)
            return
        }
        if (runtime.app.advancedAudio.enabled && realtimeWorkflow(runtime) != null) {
            val rawFormat = job.rawFormat
            if (rawFormat == null) {
                finishFailure(jobId, ADVANCED_REALTIME_FAILURE_MESSAGE)
                return
            }
            val recording = ensureRealtimeRecording(job, runtime, rawFormat)
            if (recording == null) {
                finishFailure(jobId, ADVANCED_REALTIME_FAILURE_MESSAGE)
                return
            }
            recording.recordingCompleted = true
            startRealtimeReplay(job, recording)
            return
        }
        if (shouldUseSegmentedUpload(job, runtime)) {
            val rawFile = job.rawFile ?: run {
                finishFailure(jobId, AppStrings.get(R.string.runtime_raw_recording_missing, "The original recording file is missing or empty"))
                return
            }
            val rawFormat = job.rawFormat ?: run {
                finishFailure(jobId, AppStrings.get(R.string.runtime_raw_format_missing, "The original recording format is missing"))
                return
            }
            val encodingPlan = job.encodingPlan ?: AudioEncodingPlan.resolve(runtime.app.audio, rawFormat).also {
                job.encodingPlan = it
            }
            startSegmentedUpload(job, runtime, rawFile, rawFormat, encodingPlan)
            return
        }
        val output = job.outputFile ?: return
        if (runtime.app.advancedAudio.enabled) {
            startAdvancedAudioRequest(job, runtime, output)
            return
        }
        val request = try {
            TranscriptionClient.Request(
                endpoint = BaseUrl.transcriptionEndpoint(runtime.app.provider.baseUrl),
                apiKey = runtime.apiKey,
                model = runtime.app.provider.model.trim(),
                additionalFields = AdditionalParameters.parse(runtime.app.provider.additionalJson),
                audioFile = output,
                mimeType = requireNotNull(job.encodingPlan).mimeType,
                additionalJson = runtime.app.provider.additionalJson,
            )
        } catch (error: IllegalArgumentException) {
            finishFailure(jobId, error.message ?: AppStrings.get(R.string.runtime_invalid_request, "Invalid request settings"))
            return
        }

        val attempt = job.retryCount
        updateUi(jobId, JobState.REQUESTING) {
            if (attempt == 0) {
                AppStrings.get(R.string.runtime_requesting_transcription, "Requesting transcription")
            } else {
                AppStrings.get(R.string.runtime_retrying, "Retrying %1\$d/%2\$d", attempt, runtime.app.retry.maxRetries)
            }
        }
        val registration = client.registerJob(jobId)
        job.legacyTranscriptionRegistration = registration
        job.workerFuture = worker.submit {
            try {
                if (!isCurrent(jobId)) return@submit
                val result = client.transcribe(registration, request)
                mainHandler.post {
                    if (!isCurrent(jobId)) return@post
                    handleRequestResult(jobId, result)
                }
            } finally {
                registration.close()
                if (job.legacyTranscriptionRegistration === registration) {
                    job.legacyTranscriptionRegistration = null
                }
            }
        }
    }

    private fun startAdvancedAudioRequest(
        job: ActiveJob,
        runtime: RuntimeSettings,
        output: File,
    ) {
        val source = AdvancedCancellationSource()
        job.advancedCancellationSource = source
        updateUi(job.id, JobState.REQUESTING) {
            AppStrings.get(R.string.runtime_requesting_transcription, "Requesting transcription")
        }
        val request = AdvancedAudioClient.Request(
            config = runtime.app.advancedAudio,
            secrets = runtime.advancedAudioSecrets,
            audioFile = output,
            mimeType = requireNotNull(job.encodingPlan).mimeType,
            retry = runtime.app.retry,
            onPhase = { phase -> diagnostics.info("advanced-audio", "job=${job.id} $phase") },
        )
        job.workerFuture = worker.submit {
            if (!isCurrent(job.id)) return@submit
            val result = advancedAudioClient.transcribe(request, source.token)
            mainHandler.post {
                if (isCurrent(job.id)) handleAdvancedRequestResult(job.id, result)
            }
        }
    }

    /**
     * Realtime workflows are already diverted before this decision.  A frozen
     * sidecar is intentionally enough to re-enable segmentation for the last
     * recording even if the user later disables or edits the live controls.
     */
    private fun shouldUseSegmentedUpload(job: ActiveJob, runtime: RuntimeSettings): Boolean =
        !job.mode.postProcessing &&
            (job.segmentedUploadSnapshot != null || runtime.app.segmentedUpload.enabled)

    /**
     * Prepares independent media files on the controller worker, then runs
     * each complete non-realtime workflow through the bounded batch runner.
     * Its finally blocks own temporary-file deletion, so cancellation waits
     * for started Advanced workflows to run their remote-object cleanup.
     */
    private fun startSegmentedUpload(
        job: ActiveJob,
        runtime: RuntimeSettings,
        rawFile: File,
        rawFormat: Pcm16Format,
        encodingPlan: AudioEncodingPlan,
    ) {
        if (!job.segmentedUploadInProgress.compareAndSet(false, true)) return
        synchronized(job.segmentedEncodingLock) {
            job.segmentedCancellationRequested.set(false)
            job.segmentedEncodingOperationIds.clear()
            job.activeSegmentEncodingOperationIds.clear()
        }
        job.segmentedCancelInFlight.set(null)

        val planSource = job.segmentedUploadSnapshot?.let(SegmentedUploadPlanSource::Frozen)
            ?: SegmentedUploadPlanSource.Analyze(
                SegmentedUploadParameters(
                    maximumSegmentLengthSeconds = runtime.app.segmentedUpload.maximumSegmentLengthSeconds.toLong(),
                    minimumPauseDurationMillis = runtime.app.segmentedUpload.minimumPauseDurationMillis.toLong(),
                    concurrency = runtime.app.segmentedUpload.concurrency,
                ),
            )
        val cancellation = SegmentedUploadCancellation {
            isSegmentedUploadCancellationRequested(job)
        }
        val encoder = trackedSegmentMediaEncoder(job)

        updateUi(job.id, JobState.TRANSCODING) {
            AppStrings.get(
                R.string.runtime_transcoding_to,
                "Transcoding to %1\$s",
                encodingPlan.config.container.value.uppercase(),
            )
        }
        val registration = if (runtime.app.advancedAudio.enabled) {
            null
        } else {
            client.registerJob(job.id)
        }
        job.legacyTranscriptionRegistration = registration
        job.workerFuture = worker.submit {
            try {
                val preparation = segmentedUploadPreparer.prepare(
                    SegmentedUploadPreparationRequest(
                        rawInput = rawFile,
                        inputFormat = rawFormat,
                        encodingPlan = encodingPlan,
                        temporaryRoot = fileStore.segmentedUploadTemporaryRoot(),
                        planSource = planSource,
                        operationIdForSegment = { nextSegmentEncodingOperationId.decrementAndGet() },
                        encoder = encoder,
                        cancellation = cancellation,
                        onPlanFrozen = { snapshot ->
                            persistFrozenSegmentedUploadSnapshot(job, rawFile, rawFormat, snapshot)
                        },
                        onOperationsPlanned = { operationIds ->
                            synchronized(job.segmentedEncodingLock) {
                                job.segmentedEncodingOperationIds.addAll(operationIds)
                            }
                        },
                    ),
                )

                when (preparation) {
                    is SegmentedUploadPreparationResult.Success -> {
                        val requestResult = try {
                            if (isSegmentedUploadCancellationRequested(job)) {
                                SegmentedRequestResult.Cancelled
                            } else {
                                mainHandler.post {
                                    if (isCurrent(job.id)) {
                                        updateUi(job.id, JobState.REQUESTING) {
                                            AppStrings.get(R.string.runtime_requesting_transcription, "Requesting transcription")
                                        }
                                    }
                                }
                                runSegmentedRequests(job, runtime, encodingPlan, preparation.upload, registration)
                            }
                        } finally {
                            preparation.upload.cleanup()
                            finishSegmentedUploadAttempt(job)
                        }
                        mainHandler.post {
                            if (isCurrent(job.id)) handleSegmentedRequestResult(job.id, requestResult)
                        }
                    }

                    SegmentedUploadPreparationResult.NoSpeech -> {
                        finishSegmentedUploadAttempt(job)
                        mainHandler.post {
                            if (isCurrent(job.id)) handleSegmentedNoSpeech(job.id)
                        }
                    }

                    SegmentedUploadPreparationResult.Cancelled -> {
                        finishSegmentedUploadAttempt(job)
                        // User cancellation already invalidates activeJob and
                        // updates the UI. A current job cannot normally reach
                        // this result, but do not leave it stuck if an external
                        // cancellation raced before its state transition.
                        mainHandler.post {
                            if (isCurrent(job.id)) {
                                finishFailure(
                                    job.id,
                                    AppStrings.get(R.string.runtime_task_cancelled, "Task cancelled"),
                                    showToast = false,
                                )
                            }
                        }
                    }

                    is SegmentedUploadPreparationResult.Failure -> {
                        finishSegmentedUploadAttempt(job)
                        mainHandler.post {
                            if (isCurrent(job.id)) finishFailure(job.id, preparation.message)
                        }
                    }
                }
            } finally {
                registration?.close()
                if (job.legacyTranscriptionRegistration === registration) {
                    job.legacyTranscriptionRegistration = null
                }
            }
        }
    }

    /** Move each planned ID into the active set for the duration of its FFmpeg process. */
    private fun trackedSegmentMediaEncoder(job: ActiveJob): CancellableSegmentMediaEncoder {
        val delegate = AudioTranscoderSegmentMediaEncoder(transcoder)
        return object : CancellableSegmentMediaEncoder {
            override fun encode(request: SegmentMediaEncodeRequest): SegmentMediaEncodeResult {
                synchronized(job.segmentedEncodingLock) {
                    if (isSegmentedUploadCancellationRequested(job)) {
                        job.segmentedEncodingOperationIds.remove(request.operationId)
                        return SegmentMediaEncodeResult.Cancelled
                    }
                    job.segmentedEncodingOperationIds.remove(request.operationId)
                    job.activeSegmentEncodingOperationIds.add(request.operationId)
                }
                return try {
                    // Cancellation may win after the operation becomes active
                    // but before the adapter begins its FFmpeg call.  Do not
                    // create a retained AudioTranscoder cancel marker in that
                    // case; the adapter's operation-local cancellation probe
                    // also covers the remaining start-up race.
                    synchronized(job.segmentedEncodingLock) {
                        if (isSegmentedUploadCancellationRequested(job)) {
                            job.activeSegmentEncodingOperationIds.remove(request.operationId)
                            return SegmentMediaEncodeResult.Cancelled
                        }
                    }
                    delegate.encode(request)
                } finally {
                    synchronized(job.segmentedEncodingLock) {
                        job.activeSegmentEncodingOperationIds.remove(request.operationId)
                    }
                }
            }

            override fun cancel(operationId: Long) {
                delegate.cancel(operationId)
            }
        }
    }

    private fun persistFrozenSegmentedUploadSnapshot(
        job: ActiveJob,
        rawFile: File,
        rawFormat: Pcm16Format,
        snapshot: SegmentedUploadSnapshot,
    ) {
        job.segmentedSnapshotWriteGate.ifOpen {
            if (activeJob !== job || isSegmentedUploadCancellationRequested(job)) return@ifOpen
            job.segmentedUploadSnapshot = snapshot
            if (job.mode != JobMode.VOICE) return@ifOpen

            val recording = AudioFileStore.RawRecording(rawFile, rawFormat)
            val current = fileStore.lastRecording()
            if (!sameRawRecording(current, recording)) return@ifOpen
            if (!fileStore.saveLastRecordingSegmentedUploadSnapshot(recording, SegmentedUploadSnapshotCodec.encode(snapshot))) {
                diagnostics.error("segmented-upload", "job=${job.id} failed to persist frozen segment plan")
            }
        }
    }

    private fun runSegmentedRequests(
        job: ActiveJob,
        runtime: RuntimeSettings,
        encodingPlan: AudioEncodingPlan,
        upload: com.joeykot.dictate.audio.PreparedSegmentedUpload,
        registration: TranscriptionClient.JobRegistration?,
    ): SegmentedRequestResult = if (runtime.app.advancedAudio.enabled) {
        runSegmentedAdvancedRequests(job, runtime, encodingPlan, upload)
    } else {
        runSegmentedLegacyRequests(job, runtime, encodingPlan, upload, requireNotNull(registration))
    }

    private fun runSegmentedLegacyRequests(
        job: ActiveJob,
        runtime: RuntimeSettings,
        encodingPlan: AudioEncodingPlan,
        upload: com.joeykot.dictate.audio.PreparedSegmentedUpload,
        registration: TranscriptionClient.JobRegistration,
    ): SegmentedRequestResult {
        val template = try {
            TranscriptionClient.Request(
                endpoint = BaseUrl.transcriptionEndpoint(runtime.app.provider.baseUrl),
                apiKey = runtime.apiKey,
                model = runtime.app.provider.model.trim(),
                additionalFields = AdditionalParameters.parse(runtime.app.provider.additionalJson),
                audioFile = upload.files.first(),
                mimeType = encodingPlan.mimeType,
                additionalJson = runtime.app.provider.additionalJson,
            )
        } catch (error: IllegalArgumentException) {
            return SegmentedRequestResult.Legacy(
                segmentedLegacyFailure(
                    TranscriptionClient.FailureKind.CONFIGURATION,
                    error.message ?: AppStrings.get(R.string.runtime_invalid_request, "Invalid request settings"),
                    retryable = false,
                ),
            )
        }

        val startedAt = System.nanoTime()
        val successes = ConcurrentHashMap<Int, TranscriptionClient.Result.Success>()
        val session = client.openJob(registration)
        val cancelInFlight: () -> Unit = { session.cancelInFlightOperations() }
        job.segmentedCancelInFlight.set(cancelInFlight)
        return try {
            when (
                val result = segmentedUploadBatchRunner.run(
                    items = upload.files.mapIndexed { index, file -> SegmentedUploadBatchRunner.Item(index, file) },
                    maxConcurrency = upload.snapshot.parameters.concurrency,
                    isParentCancellationRequested = {
                        isSegmentedUploadCancellationRequested(job) || session.isCancellationRequested()
                    },
                    cancelInFlight = cancelInFlight,
                    execute = { item ->
                        if (isSegmentedUploadCancellationRequested(job) || session.isCancellationRequested()) {
                            SegmentedUploadBatchRunner.TaskResult.Cancelled
                        } else {
                            val operation = session.newOperationId()
                            when (val response = session.transcribe(operation, template.copy(audioFile = item.value))) {
                                is TranscriptionClient.Result.Success -> {
                                    successes[item.index] = response
                                    SegmentedUploadBatchRunner.TaskResult.Success(response.text)
                                }

                                is TranscriptionClient.Result.Failure ->
                                    SegmentedUploadBatchRunner.TaskResult.Failure(response)

                                TranscriptionClient.Result.Cancelled -> SegmentedUploadBatchRunner.TaskResult.Cancelled
                            }
                        }
                    },
                )
            ) {
                is SegmentedUploadBatchRunner.Result.Success -> {
                    val final = successes[upload.files.lastIndex]
                    SegmentedRequestResult.Legacy(
                        TranscriptionClient.Result.Success(
                            text = result.text,
                            statusCode = final?.statusCode ?: 200,
                            elapsedMillis = elapsedMillis(startedAt),
                        ),
                    )
                }

                is SegmentedUploadBatchRunner.Result.Failure -> SegmentedRequestResult.Legacy(result.error)
                SegmentedUploadBatchRunner.Result.Cancelled -> SegmentedRequestResult.Cancelled
                SegmentedUploadBatchRunner.Result.Empty,
                SegmentedUploadBatchRunner.Result.InvalidConcurrency,
                -> SegmentedRequestResult.Legacy(
                    segmentedLegacyFailure(
                        TranscriptionClient.FailureKind.CONFIGURATION,
                        AppStrings.get(R.string.runtime_invalid_request, "Invalid request settings"),
                        retryable = false,
                    ),
                )

                is SegmentedUploadBatchRunner.Result.UnexpectedFailure -> SegmentedRequestResult.Legacy(
                    segmentedLegacyFailure(
                        TranscriptionClient.FailureKind.IO,
                        AppStrings.get(
                            R.string.val_request_error,
                            "Request failed: %1\$s",
                            diagnostics.sanitize(
                                result.cause.message ?: result.cause.javaClass.simpleName,
                                300,
                                listOf(runtime.apiKey),
                            ),
                        ),
                        retryable = false,
                    ),
                )
            }
        } finally {
            job.segmentedCancelInFlight.compareAndSet(cancelInFlight, null)
            session.close()
        }
    }

    private fun runSegmentedAdvancedRequests(
        job: ActiveJob,
        runtime: RuntimeSettings,
        encodingPlan: AudioEncodingPlan,
        upload: com.joeykot.dictate.audio.PreparedSegmentedUpload,
    ): SegmentedRequestResult {
        val parentCancellation = AdvancedCancellationSource()
        val batchCancellation = AdvancedCancellationSource()
        job.advancedCancellationSource = parentCancellation
        val startedAt = System.nanoTime()
        val successes = ConcurrentHashMap<Int, AdvancedAudioClient.Result.Success>()
        val cancelInFlight: () -> Unit = { batchCancellation.cancel() }
        job.segmentedCancelInFlight.set(cancelInFlight)

        return try {
            when (
                val result = segmentedUploadBatchRunner.run(
                    items = upload.files.mapIndexed { index, file -> SegmentedUploadBatchRunner.Item(index, file) },
                    maxConcurrency = upload.snapshot.parameters.concurrency,
                    isParentCancellationRequested = {
                        isSegmentedUploadCancellationRequested(job) || parentCancellation.token.isCancelled()
                    },
                    cancelInFlight = cancelInFlight,
                    execute = { item ->
                        if (isSegmentedUploadCancellationRequested(job) || parentCancellation.token.isCancelled()) {
                            SegmentedUploadBatchRunner.TaskResult.Cancelled
                        } else {
                            val response = advancedAudioClient.transcribe(
                                AdvancedAudioClient.Request(
                                    config = runtime.app.advancedAudio,
                                    secrets = runtime.advancedAudioSecrets,
                                    audioFile = item.value,
                                    mimeType = encodingPlan.mimeType,
                                    retry = runtime.app.retry,
                                    onPhase = { phase ->
                                        diagnostics.info("advanced-audio", "job=${job.id} segment=${item.index} $phase")
                                    },
                                ),
                                batchCancellation.token,
                            )
                            when (response) {
                                is AdvancedAudioClient.Result.Success -> {
                                    successes[item.index] = response
                                    SegmentedUploadBatchRunner.TaskResult.Success(response.text)
                                }

                                is AdvancedAudioClient.Result.Failure ->
                                    SegmentedUploadBatchRunner.TaskResult.Failure(response)

                                is AdvancedAudioClient.Result.Cancelled -> SegmentedUploadBatchRunner.TaskResult.Cancelled
                            }
                        }
                    },
                )
            ) {
                is SegmentedUploadBatchRunner.Result.Success -> {
                    val final = successes[upload.files.lastIndex]
                    SegmentedRequestResult.Advanced(
                        AdvancedAudioClient.Result.Success(
                            text = result.text,
                            statusCode = final?.statusCode ?: 200,
                            elapsedMillis = elapsedMillis(startedAt),
                        ),
                    )
                }

                is SegmentedUploadBatchRunner.Result.Failure -> SegmentedRequestResult.Advanced(result.error)
                SegmentedUploadBatchRunner.Result.Cancelled -> SegmentedRequestResult.Cancelled
                SegmentedUploadBatchRunner.Result.Empty,
                SegmentedUploadBatchRunner.Result.InvalidConcurrency,
                -> SegmentedRequestResult.Advanced(
                    AdvancedAudioClient.Result.Failure(
                        message = AppStrings.get(R.string.runtime_invalid_request, "Invalid request settings"),
                        elapsedMillis = elapsedMillis(startedAt),
                    ),
                )

                is SegmentedUploadBatchRunner.Result.UnexpectedFailure -> SegmentedRequestResult.Advanced(
                    AdvancedAudioClient.Result.Failure(
                        message = AppStrings.get(
                            R.string.val_request_error,
                            "Request failed: %1\$s",
                            diagnostics.sanitize(result.cause.message ?: result.cause.javaClass.simpleName, 300),
                        ),
                        elapsedMillis = elapsedMillis(startedAt),
                    ),
                )
            }
        } finally {
            job.segmentedCancelInFlight.compareAndSet(cancelInFlight, null)
        }
    }

    private fun segmentedLegacyFailure(
        kind: TranscriptionClient.FailureKind,
        message: String,
        retryable: Boolean,
    ): TranscriptionClient.Result.Failure = TranscriptionClient.Result.Failure(
        kind = kind,
        message = message,
        retryable = retryable,
        elapsedMillis = 0L,
    )

    private fun handleSegmentedRequestResult(jobId: Long, result: SegmentedRequestResult) {
        when (result) {
            is SegmentedRequestResult.Legacy -> handleRequestResult(jobId, result.result)
            is SegmentedRequestResult.Advanced -> handleAdvancedRequestResult(jobId, result.result)
            SegmentedRequestResult.Cancelled -> Unit
        }
    }

    private fun handleSegmentedNoSpeech(jobId: Long) {
        val job = activeJob?.takeIf { it.id == jobId } ?: return
        val message = AppStrings.get(R.string.runtime_no_speech_detected, "No speech detected")
        if (job.mode.connectionTest) {
            completeJob(jobId, ConnectionTestResult(success = false, message = message))
        } else {
            finishFailure(jobId, message)
        }
    }

    private fun finishSegmentedUploadAttempt(job: ActiveJob) {
        job.segmentedCancelInFlight.getAndSet(null)
        synchronized(job.segmentedEncodingLock) {
            job.segmentedEncodingOperationIds.clear()
            job.activeSegmentEncodingOperationIds.clear()
        }
        job.segmentedUploadInProgress.set(false)
    }

    /** Signal the batch child source and the currently running FFmpeg export without interrupting drain. */
    private fun requestSegmentedUploadCancellation(job: ActiveJob) {
        synchronized(job.segmentedEncodingLock) {
            job.segmentedCancellationRequested.set(true)
            // Only an operation that has crossed the planned-to-active
            // hand-off can own a process.  `cancelRunning` deliberately does
            // not retain a pre-start marker for later slices.
            job.activeSegmentEncodingOperationIds.forEach { operationId ->
                transcoder.cancelRunning(operationId)
            }
        }
        job.segmentedCancelInFlight.get()?.let { cancel -> runCatching(cancel) }
    }

    private fun isSegmentedUploadCancellationRequested(job: ActiveJob): Boolean =
        job.segmentedCancellationRequested.get() || !isCurrent(job.id)

    private fun elapsedMillis(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND

    private fun startRealtimeLiveSegment(
        job: ActiveJob,
        recording: RealtimeRecording,
        format: Pcm16Format,
    ): PcmPacketSink? {
        if (recording.liveFailed || recording.replayStarted || recording.activeSegment != null) return null
        val realtime = (recording.workflow.recognition as? RealtimeSessionRecognition)?.realtime ?: run {
            markRealtimeLiveFailure(job, recording)
            return null
        }
        val segment = RealtimeSegment(PcmCaptureTee(LIVE_REALTIME_TEE_CAPACITY))
        recording.segments += segment
        recording.activeSegment = segment
        try {
            segment.future = realtimeWorker.submit {
                val result = runCatching {
                    RealtimeSessionRunner().run(
                        workflow = recording.workflow,
                        values = recording.runtime.app.advancedAudio.values,
                        secrets = workflowSecrets(recording.runtime, recording.workflow),
                        runtime = newRealtimeRuntimeValues(),
                        source = LivePcmTeeSource(segment.tee, format, realtime.audioStream),
                        cancellation = recording.cancellation.token,
                    )
                }
                mainHandler.post {
                    handleRealtimeLiveSegmentResult(job.id, recording, segment, result)
                }
            }
        } catch (_: Exception) {
            // Do not retain a tee without its consumer. Capture continues
            // locally and will use a complete replay once recording stops.
            recording.segments.remove(segment)
            if (recording.activeSegment === segment) recording.activeSegment = null
            markRealtimeLiveFailure(job, recording)
            maybeCompleteRealtimeRecording(job, recording)
            return null
        }
        return segment.tee
    }

    private fun handleRealtimeLiveSegmentResult(
        jobId: Long,
        recording: RealtimeRecording,
        segment: RealtimeSegment,
        result: Result<RealtimeSessionResult>,
    ) {
        val job = activeJob?.takeIf { it.id == jobId && it.realtimeRecording === recording } ?: return
        segment.terminal = true
        if (result.isSuccess && !recording.liveFailed) {
            segment.text = result.getOrThrow().text
        } else {
            markRealtimeLiveFailure(job, recording)
        }
        maybeCompleteRealtimeRecording(job, recording)
    }

    private fun markRealtimeLiveFailure(job: ActiveJob, recording: RealtimeRecording) {
        if (recording.liveFailed) return
        recording.liveFailed = true
        recording.segments.forEach { segment -> segment.text = null }
        recording.activeSegment?.tee?.close()
        // Every existing session belongs to the same logical recording. Once
        // one becomes incomplete, none of their partial texts may be used.
        recording.cancellation.cancel()
        diagnostics.info("advanced-audio", "job=${job.id} live realtime will replay local PCM")
    }

    private fun maybeCompleteRealtimeRecording(job: ActiveJob, recording: RealtimeRecording) {
        if (!recording.recordingCompleted || recording.replayStarted) return
        if (recording.segments.any { !it.terminal }) return

        if (recording.liveFailed || recording.segments.isEmpty()) {
            startRealtimeReplay(job, recording)
            return
        }

        // Windows preserves provider text exactly when concatenating successful
        // pause-delimited sessions; no separator is invented at this layer.
        deliverResult(job.id, recording.segments.joinToString(separator = "") { it.text.orEmpty() })
    }

    private fun startRealtimeReplay(job: ActiveJob, recording: RealtimeRecording) {
        if (recording.replayStarted) return
        val rawFile = job.rawFile ?: run {
            finishFailure(job.id, ADVANCED_REALTIME_FAILURE_MESSAGE)
            return
        }
        val rawFormat = job.rawFormat ?: run {
            finishFailure(job.id, ADVANCED_REALTIME_FAILURE_MESSAGE)
            return
        }
        val realtime = (recording.workflow.recognition as? RealtimeSessionRecognition)?.realtime ?: run {
            finishFailure(job.id, ADVANCED_REALTIME_FAILURE_MESSAGE)
            return
        }

        recording.replayStarted = true
        val replayCancellation = AdvancedCancellationSource()
        job.advancedCancellationSource = replayCancellation
        val replay = RealtimeReplayContext(
            rawFile = rawFile,
            rawFormat = rawFormat,
            realtime = realtime,
            runtime = newRealtimeRuntimeValues(),
            cancellation = replayCancellation,
            startedAtNanos = System.nanoTime(),
        )
        startRealtimeReplayAttempt(job, recording, replay)
    }

    /**
     * Runs one replay attempt. A retry never reuses a consumed PCM source or
     * an established websocket, so it always begins at byte zero with a clean
     * transcript accumulator, matching the Windows replay lifecycle.
     */
    private fun startRealtimeReplayAttempt(
        job: ActiveJob,
        recording: RealtimeRecording,
        replay: RealtimeReplayContext,
    ) {
        val attempt = job.retryCount
        updateUi(job.id, JobState.REQUESTING) {
            if (attempt == 0) {
                AppStrings.get(R.string.runtime_requesting_transcription, "Requesting transcription")
            } else {
                AppStrings.get(
                    R.string.runtime_retrying,
                    "Retrying %1\$d/%2\$d",
                    attempt,
                    recording.runtime.app.retry.maxRetries,
                )
            }
        }
        try {
            job.workerFuture = realtimeWorker.submit {
                if (!isCurrent(job.id) || replayCancellationWasRequested(recording, replay.cancellation)) {
                    return@submit
                }
                val result = runCatching {
                    RealtimeSessionRunner().run(
                        workflow = recording.workflow,
                        values = recording.runtime.app.advancedAudio.values,
                        secrets = workflowSecrets(recording.runtime, recording.workflow),
                        runtime = replay.runtime,
                        source = Pcm16ReplayChunkSource(
                            replay.rawFile,
                            replay.realtime.audioStream,
                            replay.rawFormat,
                        ),
                        cancellation = replay.cancellation.token,
                    )
                }
                mainHandler.post {
                    handleRealtimeReplayResult(job.id, recording, replay, result)
                }
            }
        } catch (_: Exception) {
            finishFailure(job.id, ADVANCED_REALTIME_FAILURE_MESSAGE)
        }
    }

    private fun handleRealtimeReplayResult(
        jobId: Long,
        recording: RealtimeRecording,
        replay: RealtimeReplayContext,
        result: Result<RealtimeSessionResult>,
    ) {
        val job = activeJob?.takeIf { it.id == jobId && it.realtimeRecording === recording } ?: return
        val elapsedMillis = (System.nanoTime() - replay.startedAtNanos) / NANOS_PER_MILLISECOND
        if (result.isSuccess) {
            val text = result.getOrThrow().text
            if (job.mode.connectionTest) {
                completeJob(
                    jobId,
                    ConnectionTestResult(
                        success = true,
                        elapsedMillis = elapsedMillis,
                        text = sanitizeAdvancedResult(recording.runtime, text),
                        message = AppStrings.get(R.string.runtime_connection_success, "Connection successful"),
                    ),
                )
            } else {
                deliverResult(jobId, text)
            }
            return
        }

        val failure = result.exceptionOrNull()
        if (replayCancellationWasRequested(recording, replay.cancellation) || failure is InterruptedException) return
        if (scheduleRealtimeReplayRetry(job, recording, replay)) return

        val message = (failure as? com.joeykot.dictate.advanced_audio.realtime.RealtimeSessionException)
            ?.message
            ?: ADVANCED_REALTIME_FAILURE_MESSAGE
        if (job.mode.connectionTest) {
            completeJob(
                jobId,
                ConnectionTestResult(success = false, elapsedMillis = elapsedMillis, message = message),
            )
        } else {
            finishFailure(jobId, message)
        }
    }

    /** Schedules the next replay attempt with the shared cancellable job policy. */
    private fun scheduleRealtimeReplayRetry(
        job: ActiveJob,
        recording: RealtimeRecording,
        replay: RealtimeReplayContext,
    ): Boolean {
        val retry = recording.runtime.app.retry
        if (!retry.enabled || job.retryCount >= retry.maxRetries.coerceAtLeast(0)) return false

        val retryNumber = job.retryCount + 1
        val delay = retry.delayMillis(retryNumber)
        job.retryCount = retryNumber
        updateUi(job.id, JobState.RETRY_WAITING) {
            AppStrings.get(
                R.string.runtime_retry_wait,
                "Retry %1\$d/%2\$d, continuing in %3\$s",
                retryNumber,
                retry.maxRetries,
                formatDelay(delay),
            )
        }
        return try {
            job.retryFuture = scheduler.schedule(
                {
                    mainHandler.post {
                        val current = activeJob?.takeIf {
                            it.id == job.id && it.realtimeRecording === recording
                        } ?: return@post
                        if (!replayCancellationWasRequested(recording, replay.cancellation)) {
                            startRealtimeReplayAttempt(current, recording, replay)
                        }
                    }
                },
                delay,
                TimeUnit.MILLISECONDS,
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun replayCancellationWasRequested(
        recording: RealtimeRecording,
        replayCancellation: AdvancedCancellationSource,
    ): Boolean =
        replayCancellation.token.isCancelled() ||
            recording.cancellation.token.isCancelled() && !recording.liveFailed

    private fun newRealtimeRuntimeValues(): RuntimeTemplateValues {
        val now = Instant.now()
        return RuntimeTemplateValues(
            uuid = UUID.randomUUID().toString(),
            unixSeconds = now.epochSecond.toString(),
            unixMillis = now.toEpochMilli().toString(),
        )
    }

    private fun startPostProcessingRequest(job: ActiveJob, runtime: RuntimeSettings) {
        val prompt = job.prompt ?: return
        val attempt = job.retryCount
        updateUi(job.id, JobState.REQUESTING) {
            val title = if (job.mode.connectionTest) {
                AppStrings.get(R.string.runtime_test_connection, "Test connection")
            } else {
                prompt.title
            }
            if (attempt == 0) {
                AppStrings.get(R.string.runtime_processing_prompt, "Processing: %1\$s", title)
            } else {
                AppStrings.get(R.string.runtime_retrying_prompt, "Retry %1\$d/%2\$d: %3\$s", attempt, runtime.app.retry.maxRetries, title)
            }
        }
        job.workerFuture = worker.submit {
            if (!isCurrent(job.id)) return@submit
            val result = postProcessingClient.execute(
                config = requireNotNull(job.postProcessingApi).config,
                apiKey = job.postProcessingApi.apiKey,
                prompt = prompt,
                input = job.inputText,
                shouldContinue = { isCurrent(job.id) },
            )
            mainHandler.post {
                if (isCurrent(job.id)) handleRequestResult(job.id, result)
            }
        }
    }

    private fun handleRequestResult(jobId: Long, result: TranscriptionClient.Result) {
        val job = activeJob?.takeIf { it.id == jobId } ?: return
        val runtime = job.runtimeSettings ?: return
        when (result) {
            is TranscriptionClient.Result.Success -> {
                if (job.mode.connectionTest) {
                    completeJob(
                        jobId,
                        ConnectionTestResult(
                            success = true,
                            statusCode = result.statusCode,
                            elapsedMillis = result.elapsedMillis,
                            text = diagnostics.sanitize(
                                result.text,
                                secrets = listOf(runtime.apiKey, runtime.postProcessingApiKey, job.postProcessingApi?.apiKey.orEmpty()),
                            ),
                            message = AppStrings.get(R.string.runtime_connection_success, "Connection successful"),
                        ),
                    )
                } else {
                    deliverResult(jobId, result.text)
                }
            }
            is TranscriptionClient.Result.Failure -> {
                val retry = runtime.app.retry
                if (job.mode != JobMode.PROMPT_CONNECTION_TEST && result.retryable && retry.enabled && job.retryCount < retry.maxRetries) {
                    val retryNumber = job.retryCount + 1
                    job.retryCount = retryNumber
                    val delay = retry.delayMillis(retryNumber)
                    updateUi(jobId, JobState.RETRY_WAITING) {
                        AppStrings.get(
                            R.string.runtime_retry_wait,
                            "Retry %1\$d/%2\$d, continuing in %3\$s",
                            retryNumber,
                            retry.maxRetries,
                            formatDelay(delay),
                        )
                    }
                    job.retryFuture = scheduler.schedule(
                        {
                            mainHandler.post {
                                if (isCurrent(jobId)) startRequest(jobId)
                            }
                        },
                        delay,
                        TimeUnit.MILLISECONDS,
                    )
                } else if (job.mode.connectionTest) {
                    completeJob(
                        jobId,
                        ConnectionTestResult(
                            success = false,
                            statusCode = result.statusCode,
                            elapsedMillis = result.elapsedMillis,
                            message = result.message,
                            serverSummary = result.serverSummary,
                        ),
                    )
                } else {
                    finishFailure(jobId, result.message)
                }
            }
            TranscriptionClient.Result.Cancelled -> Unit
        }
    }

    /**
     * Advanced workflows own their phase-level retry policy. In particular,
     * an async submit must never be replayed by the legacy whole-job retry
     * loop after an ambiguous network failure.
     */
    private fun handleAdvancedRequestResult(jobId: Long, result: AdvancedAudioClient.Result) {
        val job = activeJob?.takeIf { it.id == jobId } ?: return
        val runtime = job.runtimeSettings ?: return
        when (result) {
            is AdvancedAudioClient.Result.Success -> {
                if (job.mode.connectionTest) {
                    completeJob(
                        jobId,
                        ConnectionTestResult(
                            success = true,
                            statusCode = result.statusCode,
                            elapsedMillis = result.elapsedMillis,
                            text = sanitizeAdvancedResult(runtime, result.text),
                            message = AppStrings.get(R.string.runtime_connection_success, "Connection successful"),
                        ),
                    )
                } else {
                    deliverResult(jobId, result.text)
                }
            }

            is AdvancedAudioClient.Result.Failure -> {
                val message = sanitizeAdvancedResult(runtime, result.message)
                if (job.mode.connectionTest) {
                    completeJob(
                        jobId,
                        ConnectionTestResult(
                            success = false,
                            statusCode = result.statusCode,
                            elapsedMillis = result.elapsedMillis,
                            message = message,
                        ),
                    )
                } else {
                    finishFailure(jobId, message)
                }
            }

            is AdvancedAudioClient.Result.Cancelled -> Unit
        }
    }

    private fun deliverResult(jobId: Long, text: String) {
        val job = activeJob?.takeIf { it.id == jobId } ?: return
        val alwaysCopyToClipboard = job.runtimeSettings
            ?.app
            ?.interaction
            ?.alwaysCopyToClipboard
            ?: true
        updateUi(jobId, JobState.REQUESTING) { AppStrings.get(R.string.runtime_writing_result, "Inserting the result") }
        TextDelivery.deliver(
            context = application,
            text = text,
            alwaysCopyToClipboard = alwaysCopyToClipboard,
            shouldContinue = { isCurrent(jobId) },
        ) deliveryComplete@{ outcome ->
            if (!isCurrent(jobId)) return@deliveryComplete
            handleDeliveryOutcome(jobId, outcome)
        }
    }

    private fun handleDeliveryOutcome(jobId: Long, outcome: TextDelivery.Outcome) {
        val insertionSummary = outcome.insertion.diagnosticSummary()
        when {
            outcome.insertion.unconfirmed && outcome.copied -> {
                diagnostics.info(
                    "delivery",
                    "job=$jobId insertion could not be confirmed; copied to clipboard $insertionSummary",
                )
                showToast(
                    AppStrings.get(
                        R.string.runtime_delivery_unconfirmed_copied,
                        "Insertion was attempted but could not be confirmed; the result was copied to the clipboard",
                    ),
                )
                completeJob(jobId)
            }
            outcome.insertion.unconfirmed && outcome.copyAttempted -> {
                diagnostics.error(
                    "delivery",
                    "job=$jobId insertion could not be confirmed and clipboard copy failed " +
                        insertionSummary,
                )
                showToast(AppStrings.get(R.string.runtime_delivery_unconfirmed_copy_failed, "Insertion could not be confirmed, and copying to the clipboard failed"))
                completeJob(jobId)
            }
            outcome.insertion.unconfirmed -> {
                diagnostics.info(
                    "delivery",
                    "job=$jobId insertion could not be confirmed; clipboard copy not requested " +
                        insertionSummary,
                )
                showToast(AppStrings.get(R.string.runtime_delivery_unconfirmed, "Insertion was attempted but could not be confirmed"))
                completeJob(jobId)
            }
            outcome.inserted && outcome.copied -> {
                diagnostics.info(
                    "delivery",
                    "job=$jobId inserted into current focus and copied to clipboard $insertionSummary",
                )
                completeJob(jobId)
            }
            outcome.inserted && !outcome.copyAttempted -> {
                diagnostics.info("delivery", "job=$jobId inserted into current focus $insertionSummary")
                completeJob(jobId)
            }
            outcome.inserted -> {
                diagnostics.error(
                    "delivery",
                    "job=$jobId inserted into current focus but clipboard copy failed $insertionSummary",
                )
                showToast(AppStrings.get(R.string.runtime_delivery_inserted_copy_failed, "The result was inserted, but copying to the clipboard failed"))
                completeJob(jobId)
            }
            outcome.copied -> {
                diagnostics.info(
                    "delivery",
                    "job=$jobId copied to clipboard fallback $insertionSummary",
                )
                showToast(AppStrings.get(R.string.runtime_delivery_copied, "Unable to insert at the current focus; the result was copied to the clipboard"))
                completeJob(jobId)
            }
            else -> {
                diagnostics.error(
                    "delivery",
                    "job=$jobId focus insertion and clipboard both failed $insertionSummary",
                )
                finishFailure(jobId, AppStrings.get(R.string.runtime_delivery_failed, "Unable to insert the result or copy it to the clipboard"))
            }
        }
    }

    private fun cancelActiveJob() {
        val job = activeJob ?: return
        val stateAtCancellation = uiState.state

        // Invalidate the job before touching any cancellable component.
        closeSegmentedSnapshotWriteGate(job)
        activeJob = null
        updateUi(null, JobState.IDLE) { AppStrings.get(R.string.runtime_task_cancelled, "Task cancelled") }

        job.retryFuture?.cancel(true)
        cancelRealtimeRecording(job)
        job.advancedCancellationSource?.cancel()
        requestSegmentedUploadCancellation(job)

        when (stateAtCancellation) {
            JobState.RECORDING,
            JobState.PAUSED,
            -> {
                val delivered = runCatching {
                    application.startService(RecordingService.cancelIntent(application, job.id))
                }.isSuccess
                if (!delivered) application.stopService(recordingServiceIntent)
                job.rawFile?.delete()
            }
            JobState.TRANSCODING -> {
                if (!job.segmentedUploadInProgress.get() && job.recordingClosed && job.outputFile != null) {
                    transcoder.cancel(job.id)
                }
                if (job.mode == JobMode.VOICE && !job.recordingClosed) {
                    pendingPreserveAfterRecorderStops.add(job.id)
                    val delivered = runCatching {
                        application.startService(RecordingService.stopIntent(application, job.id))
                    }.isSuccess
                    if (!delivered) application.stopService(recordingServiceIntent)
                } else if (job.mode == JobMode.VOICE && hasValidRawAudio(job)) {
                    promoteRecording(job.id, checkNotNull(job.rawFile), job.rawFormat)
                }
            }
            JobState.REQUESTING -> {
                when {
                    job.mode.postProcessing -> postProcessingClient.cancel()
                    job.runtimeSettings?.app?.advancedAudio?.enabled == true -> Unit
                    else -> client.cancel(job.id)
                }
                if (job.mode == JobMode.VOICE && hasValidRawAudio(job)) {
                    promoteRecording(job.id, checkNotNull(job.rawFile), job.rawFormat)
                }
            }
            JobState.RETRY_WAITING -> {
                if (job.mode == JobMode.VOICE && hasValidRawAudio(job)) {
                    promoteRecording(job.id, checkNotNull(job.rawFile), job.rawFormat)
                }
            }
            JobState.IDLE -> Unit
        }

        // A segmented batch deliberately drains every started task after its
        // child cancellation is signalled. Interrupting this outer worker
        // would let Advanced remote-object cleanup lose that opportunity.
        if (!job.segmentedUploadInProgress.get()) {
            job.workerFuture?.cancel(true)
            // The worker may have been discarded before it could open the
            // registered HTTP session. It retains the registration if it did
            // start, so a cancellation racing that hand-off remains visible.
            job.legacyTranscriptionRegistration?.close()
        }
        job.outputFile?.delete()

        if (job.mode.connectionTest) {
            job.rawFile?.delete()
            job.testCallback?.invoke(ConnectionTestResult(false, message = AppStrings.get(R.string.runtime_test_cancelled, "Test cancelled")))
        }
        showToast(AppStrings.get(R.string.runtime_task_cancelled, "Task cancelled"))
    }

    private fun abortRecorderWithPreservation(job: ActiveJob, message: String) {
        pendingPreserveAfterRecorderStops.add(job.id)
        cancelRealtimeRecording(job)
        job.advancedCancellationSource?.cancel()
        closeSegmentedSnapshotWriteGate(job)
        activeJob = null
        updateUi(null, JobUiState(JobState.IDLE, message))
        val delivered = runCatching {
            application.startService(RecordingService.stopIntent(application, job.id))
        }.isSuccess
        if (!delivered) application.stopService(recordingServiceIntent)
        showToast(message)
    }

    private fun finishFailure(jobId: Long, message: String, showToast: Boolean = true) {
        val job = activeJob?.takeIf { it.id == jobId } ?: return
        diagnostics.error("job", "job=$jobId state=${uiState.state} $message")
        job.retryFuture?.cancel(true)
        cancelRealtimeRecording(job)
        job.advancedCancellationSource?.cancel()
        closeSegmentedSnapshotWriteGate(job)
        requestSegmentedUploadCancellation(job)
        job.outputFile?.delete()
        if (job.mode.connectionTest) job.rawFile?.delete()
        activeJob = null
        updateUi(null, JobUiState(JobState.IDLE, message))
        if (job.mode.connectionTest) {
            job.testCallback?.invoke(ConnectionTestResult(false, message = message))
        } else if (showToast) {
            showToast(message)
        }
    }

    private fun completeJob(jobId: Long, testResult: ConnectionTestResult? = null) {
        val job = activeJob?.takeIf { it.id == jobId } ?: return
        job.retryFuture?.cancel(false)
        cancelRealtimeRecording(job)
        job.advancedCancellationSource?.cancel()
        closeSegmentedSnapshotWriteGate(job)
        job.outputFile?.delete()
        if (job.mode.connectionTest) job.rawFile?.delete()
        activeJob = null
        updateUi(null, JobState.IDLE) { AppStrings.get(R.string.runtime_idle, "Idle") }
        if (testResult != null) job.testCallback?.invoke(testResult)
    }

    /**
     * A worker may still be unwinding while the main thread invalidates a
     * task.  Closing this per-job gate first makes any late `onPlanFrozen`
     * callback a no-op before another recording can become the shared last
     * recording.
     */
    private fun closeSegmentedSnapshotWriteGate(job: ActiveJob) {
        job.segmentedSnapshotWriteGate.close()
        synchronized(job.segmentedEncodingLock) {
            job.segmentedCancellationRequested.set(true)
        }
    }

    private fun promoteRecording(
        jobId: Long,
        file: File,
        format: Pcm16Format?,
    ): AudioFileStore.RawRecording? {
        if (format == null) {
            file.delete()
            diagnostics.error("recording", "job=$jobId cannot preserve raw audio without its PCM format")
            return null
        }
        val existingLastRecording = fileStore.lastRecording()
        if (jobId < lastPromotedJobId && existingLastRecording != null) {
            file.delete()
            return existingLastRecording
        }
        return try {
            fileStore.promoteToLast(file, format)?.also { lastPromotedJobId = jobId }
        } catch (error: Exception) {
            diagnostics.error(
                "recording",
                "job=$jobId failed to preserve raw audio: ${error.message ?: error.javaClass.simpleName}",
            )
            null
        }
    }

    private fun sameRawRecording(
        first: AudioFileStore.RawRecording?,
        second: AudioFileStore.RawRecording,
    ): Boolean {
        val current = first ?: return false
        return current.format == second.format &&
            runCatching { current.file.canonicalFile == second.file.canonicalFile }.getOrDefault(false)
    }

    private fun hasValidRawAudio(job: ActiveJob): Boolean {
        val rawFile = job.rawFile ?: return false
        val format = job.rawFormat ?: return false
        return fileStore.isValidRaw(rawFile, format)
    }

    private fun realtimeWorkflow(runtime: RuntimeSettings): AdvancedAudioWorkflow? {
        if (!runtime.app.advancedAudio.enabled) return null
        val document = runtime.app.advancedAudio.workflowJson?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { AdvancedAudioWorkflowCodec.parse(document) }
            .getOrNull()
            ?.takeIf { it.recognition is RealtimeSessionRecognition }
    }

    private fun ensureRealtimeRecording(
        job: ActiveJob,
        runtime: RuntimeSettings,
        captureFormat: Pcm16Format,
    ): RealtimeRecording? {
        job.realtimeRecording?.let { return it }
        val workflow = realtimeWorkflow(runtime) ?: return null
        return RealtimeRecording(
            runtime = runtime,
            workflow = workflow,
            captureFormat = captureFormat,
            cancellation = AdvancedCancellationSource(),
        ).also { recording ->
            job.realtimeRecording = recording
            job.advancedCancellationSource = recording.cancellation
        }
    }

    private fun cancelRealtimeRecording(job: ActiveJob) {
        val recording = job.realtimeRecording ?: return
        recording.liveFailed = true
        recording.segments.forEach { segment -> segment.text = null }
        recording.activeSegment?.tee?.close()
        recording.cancellation.cancel()
    }

    private fun validateRuntimeSettings(runtime: RuntimeSettings): String? {
        val errors = (
            runtime.app.audio.validate() +
                runtime.app.retry.validate() +
                runtime.app.segmentedUpload.validate()
            ).toMutableList()
        if (runtime.app.advancedAudio.enabled) {
            val config = runtime.app.advancedAudio
            val document = config.workflowJson?.trim().takeIf { !it.isNullOrEmpty() }
            if (document == null) {
                errors += "ADVANCED_AUDIO_API.workflow: is required when Advanced Audio API is enabled"
                return errors.firstOrNull()
            }
            val workflow = try {
                AdvancedAudioWorkflowCodec.parse(document)
            } catch (error: IllegalArgumentException) {
                errors += "ADVANCED_AUDIO_API.workflow: ${error.message ?: "is invalid"}"
                return errors.firstOrNull()
            }
            val workflowSecrets = workflowSecrets(runtime, workflow)
            errors += WorkflowValidator.validateExecutionInputs(
                workflow = workflow,
                values = config.values,
                secrets = workflowSecrets,
            ).map { it.toString() }
            errors += RemoteAudioConfigValidator.validate(
                config = config.remoteAudio,
                delivery = workflow.audio.delivery,
                secrets = runtime.advancedAudioSecrets,
            ).map { it.toString() }
            return errors.firstOrNull()
        }
        if (runtime.app.provider.baseUrl.isBlank()) errors.add(AppStrings.get(R.string.runtime_base_url_required, "Base URL must not be empty"))
        if (runtime.apiKey.isBlank()) errors.add(AppStrings.get(R.string.runtime_api_key_required, "API Key must not be empty"))
        try {
            BaseUrl.transcriptionEndpoint(runtime.app.provider.baseUrl)
            AdditionalParameters.transcriptionFields(
                runtime.app.provider.model,
                runtime.app.provider.additionalJson,
            )
        } catch (error: IllegalArgumentException) {
            errors.add(error.message ?: AppStrings.get(R.string.runtime_invalid_transcription, "Invalid transcription parameters"))
        }
        return errors.firstOrNull()
    }

    private fun sanitizeAdvancedResult(runtime: RuntimeSettings, value: String): String =
        diagnostics.sanitize(value, secrets = runtime.advancedAudioSecrets.values)

    /** Remote-storage credentials are never available to a workflow template. */
    private fun workflowSecrets(
        runtime: RuntimeSettings,
        workflow: AdvancedAudioWorkflow,
    ): Map<String, String> {
        val remoteCredentialIds = RemoteAudioCredentialIds.forConfig(runtime.app.advancedAudio.remoteAudio)
        return runtime.advancedAudioSecrets.filterKeys { id ->
            id !in remoteCredentialIds && workflow.secrets.any { it.id == id }
        }
    }

    private fun isCurrent(jobId: Long): Boolean = activeJob?.id == jobId

    private fun updateUi(jobId: Long?, state: JobState, message: () -> String) {
        updateUi(jobId, JobUiState(state, message()), message)
    }

    private fun updateUi(jobId: Long?, state: JobUiState, message: (() -> String)? = null) {
        if (jobId != null && !isCurrent(jobId)) return
        uiMessage = message
        uiState = state
        val version = ++uiVersion
        mainHandler.post {
            if (version != uiVersion) return@post
            val currentState = uiState
            listeners.forEach { it.onStateChanged(currentState) }
        }
    }

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(application, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun formatDelay(delayMillis: Long): String =
        if (delayMillis % 1_000L == 0L) {
            AppStrings.get(R.string.runtime_seconds, "%1\$s s", (delayMillis / 1_000L).toString())
        } else {
            AppStrings.get(R.string.runtime_seconds, "%1\$s s", (delayMillis / 1_000.0).toString())
        }

    private companion object {
        const val AMPLITUDE_INTERVAL_MS = 80L
        const val LIVE_REALTIME_TEE_CAPACITY = 128
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val ADVANCED_REALTIME_FAILURE_MESSAGE = "Realtime transcription failed"
    }
}

internal fun shouldOpenPostProcessingMenu(selectedText: String?, promptCount: Int): Boolean =
    !selectedText.isNullOrEmpty() && promptCount > 0
