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
import com.joeykot.dictate.audio.AudioTranscoder
import com.joeykot.dictate.audio.RecordingService
import com.joeykot.dictate.R
import com.joeykot.dictate.i18n.AppStrings
import com.joeykot.dictate.model.JobState
import com.joeykot.dictate.model.JobUiState
import com.joeykot.dictate.model.Pcm16Format
import com.joeykot.dictate.model.RuntimeSettings
import com.joeykot.dictate.model.PromptConfig
import com.joeykot.dictate.network.AdditionalParameters
import com.joeykot.dictate.network.BaseUrl
import com.joeykot.dictate.network.TranscriptionClient
import com.joeykot.dictate.network.PostProcessingClient
import com.joeykot.dictate.settings.SettingsRepository
import com.joeykot.dictate.ui.MainActivity
import com.joeykot.dictate.util.AudioFileStore
import com.joeykot.dictate.util.Diagnostics
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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
    }

    private data class ActiveJob(
        val id: Long,
        val mode: JobMode,
        var rawFile: File? = null,
        var runtimeSettings: RuntimeSettings?,
        var rawFormat: Pcm16Format? = null,
        var outputFile: File? = null,
        var recordingClosed: Boolean = false,
        var retryCount: Int = 0,
        var workerFuture: Future<*>? = null,
        var retryFuture: ScheduledFuture<*>? = null,
        val testCallback: ((ConnectionTestResult) -> Unit)? = null,
        val inputText: String = "",
        val prompt: PromptConfig? = null,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dictate-voice-job")
    }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dictate-retry-wait")
    }
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val nextJobId = AtomicLong(0L)
    private val transcoder = AudioTranscoder(application, diagnostics)
    private val client = TranscriptionClient(diagnostics)
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

    private fun beginPostProcessing(
        runtime: RuntimeSettings,
        prompt: PromptConfig,
        input: String,
        callback: ((ConnectionTestResult) -> Unit)?,
    ): Boolean {
        val retryError = runtime.app.retry.validate().firstOrNull()
        if (retryError != null) {
            if (callback != null) callback(ConnectionTestResult(false, message = retryError))
            else showToast(retryError)
            return false
        }
        val jobId = nextJobId.incrementAndGet()
        activeJob = ActiveJob(
            id = jobId,
            mode = if (callback == null) JobMode.POST_PROCESSING else JobMode.POST_PROCESSING_TEST,
            runtimeSettings = runtime,
            inputText = input,
            prompt = prompt,
            testCallback = callback,
        )
        startRequest(jobId)
        return true
    }

    fun onRecordingStarted(jobId: Long, format: Pcm16Format) {
        mainHandler.post {
            val job = activeJob?.takeIf { it.id == jobId } ?: return@post
            job.rawFormat = format
            diagnostics.info("recording", "job=$jobId started format=${format.summary()}")
        }
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
        val jobId = nextJobId.incrementAndGet()
        activeJob = ActiveJob(
            id = jobId,
            mode = JobMode.VOICE,
            rawFile = lastRecording.file,
            rawFormat = lastRecording.format,
            runtimeSettings = runtime,
            recordingClosed = true,
        )
        updateUi(jobId, JobState.TRANSCODING) {
            AppStrings.get(R.string.runtime_retranscoding, "Transcoding again with the current settings")
        }
        beginTranscode(jobId)
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

        val audio = runtime.app.audio.normalized()
        val output = fileStore.newEncodedFile(jobId, audio.container)
        job.outputFile = output
        updateUi(jobId, JobState.TRANSCODING) {
            AppStrings.get(R.string.runtime_transcoding_to, "Transcoding to %1\$s", audio.container.value.uppercase())
        }
        job.workerFuture = worker.submit {
            if (!isCurrent(jobId)) return@submit
            val result = transcoder.transcode(jobId, rawFile, rawFormat, output, audio)
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
        val output = job.outputFile ?: return
        val request = try {
            TranscriptionClient.Request(
                endpoint = BaseUrl.transcriptionEndpoint(runtime.app.provider.baseUrl),
                apiKey = runtime.apiKey,
                model = runtime.app.provider.model.trim(),
                additionalFields = AdditionalParameters.parse(runtime.app.provider.additionalJson),
                audioFile = output,
                mimeType = runtime.app.audio.normalized().container.mimeType,
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
        job.workerFuture = worker.submit {
            if (!isCurrent(jobId)) return@submit
            val result = client.transcribe(jobId, request)
            mainHandler.post {
                if (!isCurrent(jobId)) return@post
                handleRequestResult(jobId, result)
            }
        }
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
                config = runtime.app.postProcessing,
                apiKey = runtime.postProcessingApiKey,
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
                                secrets = listOf(runtime.apiKey, runtime.postProcessingApiKey),
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
                if (result.retryable && retry.enabled && job.retryCount < retry.maxRetries) {
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
        activeJob = null
        updateUi(null, JobState.IDLE) { AppStrings.get(R.string.runtime_task_cancelled, "Task cancelled") }

        job.retryFuture?.cancel(true)

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
                if (job.recordingClosed && job.outputFile != null) transcoder.cancel(job.id)
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
                if (job.mode.postProcessing) postProcessingClient.cancel() else client.cancel(job.id)
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

        job.workerFuture?.cancel(true)
        job.outputFile?.delete()

        if (job.mode.connectionTest) {
            job.rawFile?.delete()
            job.testCallback?.invoke(ConnectionTestResult(false, message = AppStrings.get(R.string.runtime_test_cancelled, "Test cancelled")))
        }
        showToast(AppStrings.get(R.string.runtime_task_cancelled, "Task cancelled"))
    }

    private fun abortRecorderWithPreservation(job: ActiveJob, message: String) {
        pendingPreserveAfterRecorderStops.add(job.id)
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
        job.outputFile?.delete()
        if (job.mode.connectionTest) job.rawFile?.delete()
        activeJob = null
        updateUi(null, JobState.IDLE) { AppStrings.get(R.string.runtime_idle, "Idle") }
        if (testResult != null) job.testCallback?.invoke(testResult)
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

    private fun hasValidRawAudio(job: ActiveJob): Boolean {
        val rawFile = job.rawFile ?: return false
        val format = job.rawFormat ?: return false
        return fileStore.isValidRaw(rawFile, format)
    }

    private fun validateRuntimeSettings(runtime: RuntimeSettings): String? {
        val errors = (runtime.app.audio.validate() + runtime.app.retry.validate()).toMutableList()
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
    }
}

internal fun shouldOpenPostProcessingMenu(selectedText: String?, promptCount: Int): Boolean =
    !selectedText.isNullOrEmpty() && promptCount > 0
