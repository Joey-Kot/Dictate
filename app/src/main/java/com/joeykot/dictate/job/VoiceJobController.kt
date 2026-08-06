package com.joeykot.dictate.job

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.joeykot.dictate.DictateApplication
import com.joeykot.dictate.accessibility.TextDelivery
import com.joeykot.dictate.audio.AudioTranscoder
import com.joeykot.dictate.audio.RecordingService
import com.joeykot.dictate.model.JobState
import com.joeykot.dictate.model.JobUiState
import com.joeykot.dictate.model.RuntimeSettings
import com.joeykot.dictate.network.AdditionalParameters
import com.joeykot.dictate.network.BaseUrl
import com.joeykot.dictate.network.TranscriptionClient
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

    private enum class JobMode {
        VOICE,
        CONNECTION_TEST,
    }

    private data class ActiveJob(
        val id: Long,
        val mode: JobMode,
        var rawFile: File,
        var runtimeSettings: RuntimeSettings?,
        var outputFile: File? = null,
        var recordingClosed: Boolean = false,
        var retryCount: Int = 0,
        var workerFuture: Future<*>? = null,
        var retryFuture: ScheduledFuture<*>? = null,
        val testCallback: ((ConnectionTestResult) -> Unit)? = null,
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
    private val recordingServiceIntent = Intent(application, RecordingService::class.java)
    private val pendingPreserveAfterRecorderStops = mutableSetOf<Long>()

    @Volatile
    private var activeJob: ActiveJob? = null

    @Volatile
    private var uiState = JobUiState()

    @Volatile
    private var uiVersion = 0L

    @Volatile
    private var lastAmplitudeDispatchAt = 0L

    private var lastPromotedJobId = 0L

    fun addListener(listener: Listener) {
        listeners.add(listener)
        val version = uiVersion
        val state = uiState
        mainHandler.post {
            if (version == uiVersion) listener.onStateChanged(state)
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun currentState(): JobUiState = uiState

    fun handleSingleTap(expectedState: JobState) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (uiState.state != expectedState) return
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
        when (expectedState) {
            JobState.IDLE -> resendLastRecording()
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
            callback(ConnectionTestResult(false, message = "当前有语音任务正在运行"))
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
            runtimeSettings = runtimeSettings,
            recordingClosed = true,
            testCallback = callback,
        )
        activeJob = job
        updateUi(jobId, JobUiState(JobState.TRANSCODING, "正在准备连通性测试音频"))
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
                    finishFailure(jobId, "无法读取内置测试音频")
                } else {
                    beginTranscode(jobId)
                }
            }
        }
        return true
    }

    fun onRecordingStarted(jobId: Long) {
        mainHandler.post {
            if (!isCurrent(jobId)) return@post
            diagnostics.info("recording", "job=$jobId started")
        }
    }

    fun onRecordingAmplitude(jobId: Long, amplitude: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastAmplitudeDispatchAt < AMPLITUDE_INTERVAL_MS) return
        lastAmplitudeDispatchAt = now
        mainHandler.post {
            if (!isCurrent(jobId) || uiState.state != JobState.RECORDING) return@post
            updateUi(jobId, uiState.copy(amplitude = amplitude))
        }
    }

    fun onRecordingCompleted(
        jobId: Long,
        file: File,
        valid: Boolean,
        discarded: Boolean,
        unexpected: Boolean,
    ) {
        mainHandler.post {
            if (pendingPreserveAfterRecorderStops.remove(jobId)) {
                if (valid && !discarded) promoteRecording(jobId, file) else file.delete()
                return@post
            }
            val job = activeJob
            if (job?.id != jobId) return@post
            job.recordingClosed = true

            if (discarded) {
                file.delete()
                finishFailure(jobId, "本次录音已丢弃", showToast = false)
                return@post
            }
            if (!valid) {
                file.delete()
                finishFailure(jobId, "录音过短或未形成有效音频")
                return@post
            }

            val promoted = promoteRecording(jobId, file)
            if (promoted == null) {
                finishFailure(jobId, "无法保存本次录音")
                return@post
            }
            job.rawFile = promoted

            if (unexpected) {
                finishFailure(jobId, "录音服务意外终止，已保留可用录音")
                return@post
            }
            if (uiState.state != JobState.TRANSCODING) {
                finishFailure(jobId, "录音状态异常，已保留可用录音")
                return@post
            }
            job.runtimeSettings = settingsRepository.runtime()
            beginTranscode(jobId)
        }
    }

    fun onRecordingFailed(jobId: Long, file: File, valid: Boolean, message: String) {
        mainHandler.post {
            if (pendingPreserveAfterRecorderStops.remove(jobId)) {
                if (valid) promoteRecording(jobId, file) else file.delete()
                return@post
            }
            if (!isCurrent(jobId)) return@post
            if (valid) promoteRecording(jobId, file) else file.delete()
            finishFailure(jobId, message)
        }
    }

    fun onRecordingServiceFailed(jobId: Long, message: String) {
        mainHandler.post {
            val job = activeJob
            if (job?.id != jobId) return@post
            job.rawFile.delete()
            finishFailure(jobId, message)
        }
    }

    private fun startRecording() {
        if (application.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showToast("请先授予麦克风权限")
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
        updateUi(jobId, JobUiState(JobState.RECORDING, "录制中"))

        try {
            val intent = RecordingService.startIntent(application, jobId, rawFile)
            application.startForegroundService(intent)
        } catch (error: Exception) {
            rawFile.delete()
            finishFailure(
                jobId,
                "无法启动录音服务：${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    private fun pauseRecording() {
        val job = activeJob ?: return
        if (uiState.state != JobState.RECORDING) return
        try {
            application.startService(RecordingService.pauseIntent(application, job.id))
            updateUi(job.id, JobUiState(JobState.PAUSED, "录音已暂停"))
        } catch (error: Exception) {
            abortRecorderWithPreservation(job, "无法暂停录音：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun resumeRecording() {
        val job = activeJob ?: return
        if (uiState.state != JobState.PAUSED) return
        try {
            application.startService(RecordingService.resumeIntent(application, job.id))
            updateUi(job.id, JobUiState(JobState.RECORDING, "录制中"))
        } catch (error: Exception) {
            abortRecorderWithPreservation(job, "无法恢复录音：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun stopRecording() {
        val job = activeJob ?: return
        if (uiState.state != JobState.RECORDING) return
        updateUi(job.id, JobUiState(JobState.TRANSCODING, "正在结束录音"))
        try {
            application.startService(RecordingService.stopIntent(application, job.id))
        } catch (error: Exception) {
            abortRecorderWithPreservation(job, "无法停止录音：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun resendLastRecording() {
        if (activeJob != null) return
        if (!fileStore.hasLastRecording()) {
            showToast("没有可重发的上一条录音")
            return
        }
        val runtime = settingsRepository.runtime()
        val jobId = nextJobId.incrementAndGet()
        activeJob = ActiveJob(
            id = jobId,
            mode = JobMode.VOICE,
            rawFile = fileStore.lastRecordingFile,
            runtimeSettings = runtime,
            recordingClosed = true,
        )
        updateUi(jobId, JobUiState(JobState.TRANSCODING, "正在按当前配置重新转码"))
        beginTranscode(jobId)
    }

    private fun beginTranscode(jobId: Long) {
        val job = activeJob?.takeIf { it.id == jobId } ?: return
        val runtime = job.runtimeSettings ?: run {
            finishFailure(jobId, "缺少转写设置")
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
        updateUi(jobId, JobUiState(JobState.TRANSCODING, "正在转码为 ${audio.container.value.uppercase()}"))
        job.workerFuture = worker.submit {
            if (!isCurrent(jobId)) return@submit
            val result = transcoder.transcode(jobId, job.rawFile, output, audio)
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
        val output = job.outputFile ?: return
        val request = try {
            TranscriptionClient.Request(
                endpoint = BaseUrl.transcriptionEndpoint(runtime.app.provider.baseUrl),
                apiKey = runtime.apiKey,
                model = runtime.app.provider.model.trim(),
                additionalFields = AdditionalParameters.parse(runtime.app.provider.additionalJson),
                audioFile = output,
                mimeType = runtime.app.audio.normalized().container.mimeType,
            )
        } catch (error: IllegalArgumentException) {
            finishFailure(jobId, error.message ?: "请求配置无效")
            return
        }

        val attemptText = if (job.retryCount == 0) {
            "正在请求转写"
        } else {
            "正在执行重试 ${job.retryCount}/${runtime.app.retry.maxRetries}"
        }
        updateUi(jobId, JobUiState(JobState.REQUESTING, attemptText))
        job.workerFuture = worker.submit {
            if (!isCurrent(jobId)) return@submit
            val result = client.transcribe(jobId, request)
            mainHandler.post {
                if (!isCurrent(jobId)) return@post
                handleRequestResult(jobId, result)
            }
        }
    }

    private fun handleRequestResult(jobId: Long, result: TranscriptionClient.Result) {
        val job = activeJob?.takeIf { it.id == jobId } ?: return
        val runtime = job.runtimeSettings ?: return
        when (result) {
            is TranscriptionClient.Result.Success -> {
                if (job.mode == JobMode.CONNECTION_TEST) {
                    completeJob(
                        jobId,
                        ConnectionTestResult(
                            success = true,
                            statusCode = result.statusCode,
                            elapsedMillis = result.elapsedMillis,
                            text = diagnostics.sanitize(
                                result.text,
                                secrets = listOf(runtime.apiKey),
                            ),
                            message = "连接成功",
                        ),
                    )
                } else {
                    deliverTranscription(jobId, result.text)
                }
            }
            is TranscriptionClient.Result.Failure -> {
                val retry = runtime.app.retry
                if (result.retryable && retry.enabled && job.retryCount < retry.maxRetries) {
                    val retryNumber = job.retryCount + 1
                    job.retryCount = retryNumber
                    val delay = retry.delayMillis(retryNumber)
                    updateUi(
                        jobId,
                        JobUiState(
                            JobState.RETRY_WAITING,
                            "重试 $retryNumber/${retry.maxRetries}，${formatDelay(delay)} 后继续",
                        ),
                    )
                    job.retryFuture = scheduler.schedule(
                        {
                            mainHandler.post {
                                if (isCurrent(jobId)) startRequest(jobId)
                            }
                        },
                        delay,
                        TimeUnit.MILLISECONDS,
                    )
                } else if (job.mode == JobMode.CONNECTION_TEST) {
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

    private fun deliverTranscription(jobId: Long, text: String) {
        val job = activeJob?.takeIf { it.id == jobId } ?: return
        val alwaysCopyToClipboard = job.runtimeSettings
            ?.app
            ?.interaction
            ?.alwaysCopyToClipboard
            ?: true
        updateUi(jobId, JobUiState(JobState.REQUESTING, "正在写入转写结果"))
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
                diagnostics.error(
                    "delivery",
                    "job=$jobId insertion could not be confirmed; copied to clipboard $insertionSummary",
                )
                showToast("已尝试写入当前焦点，但无法确认；转写结果已复制到剪贴板")
                completeJob(jobId)
            }
            outcome.insertion.unconfirmed -> {
                diagnostics.error(
                    "delivery",
                    "job=$jobId insertion could not be confirmed and clipboard copy failed " +
                        insertionSummary,
                )
                showToast("已尝试写入当前焦点，但无法确认，且复制到剪贴板失败")
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
                showToast("转写结果已写入当前焦点，但复制到剪贴板失败")
                completeJob(jobId)
            }
            outcome.copied -> {
                diagnostics.info(
                    "delivery",
                    "job=$jobId copied to clipboard fallback $insertionSummary",
                )
                showToast("当前焦点不可写入，转写结果已复制到剪贴板")
                completeJob(jobId)
            }
            else -> {
                diagnostics.error(
                    "delivery",
                    "job=$jobId focus insertion and clipboard both failed $insertionSummary",
                )
                finishFailure(jobId, "无法写入当前焦点，也无法写入剪贴板")
            }
        }
    }

    private fun cancelActiveJob() {
        val job = activeJob ?: return
        val stateAtCancellation = uiState.state

        // Invalidate the job before touching any cancellable component.
        activeJob = null
        updateUi(null, JobUiState(JobState.IDLE, "任务已取消"))

        job.retryFuture?.cancel(true)

        when (stateAtCancellation) {
            JobState.RECORDING,
            JobState.PAUSED,
            -> {
                val delivered = runCatching {
                    application.startService(RecordingService.cancelIntent(application, job.id))
                }.isSuccess
                if (!delivered) application.stopService(recordingServiceIntent)
                job.rawFile.delete()
            }
            JobState.TRANSCODING -> {
                if (job.recordingClosed && job.outputFile != null) transcoder.cancel(job.id)
                if (job.mode == JobMode.VOICE && !job.recordingClosed) {
                    pendingPreserveAfterRecorderStops.add(job.id)
                    val delivered = runCatching {
                        application.startService(RecordingService.stopIntent(application, job.id))
                    }.isSuccess
                    if (!delivered) application.stopService(recordingServiceIntent)
                } else if (job.mode == JobMode.VOICE && fileStore.isValidRaw(job.rawFile)) {
                    promoteRecording(job.id, job.rawFile)
                }
            }
            JobState.REQUESTING -> {
                client.cancel(job.id)
                if (job.mode == JobMode.VOICE && fileStore.isValidRaw(job.rawFile)) {
                    promoteRecording(job.id, job.rawFile)
                }
            }
            JobState.RETRY_WAITING -> {
                if (job.mode == JobMode.VOICE && fileStore.isValidRaw(job.rawFile)) {
                    promoteRecording(job.id, job.rawFile)
                }
            }
            JobState.IDLE -> Unit
        }

        job.workerFuture?.cancel(true)
        job.outputFile?.delete()

        if (job.mode == JobMode.CONNECTION_TEST) {
            job.rawFile.delete()
            job.testCallback?.invoke(ConnectionTestResult(false, message = "测试已取消"))
        }
        showToast("任务已取消")
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
        if (job.mode == JobMode.CONNECTION_TEST) job.rawFile.delete()
        activeJob = null
        updateUi(null, JobUiState(JobState.IDLE, message))
        if (job.mode == JobMode.CONNECTION_TEST) {
            job.testCallback?.invoke(ConnectionTestResult(false, message = message))
        } else if (showToast) {
            showToast(message)
        }
    }

    private fun completeJob(jobId: Long, testResult: ConnectionTestResult? = null) {
        val job = activeJob?.takeIf { it.id == jobId } ?: return
        job.retryFuture?.cancel(false)
        job.outputFile?.delete()
        if (job.mode == JobMode.CONNECTION_TEST) job.rawFile.delete()
        activeJob = null
        updateUi(null, JobUiState(JobState.IDLE, "空闲"))
        if (testResult != null) job.testCallback?.invoke(testResult)
    }

    private fun promoteRecording(jobId: Long, file: File): File? {
        if (jobId < lastPromotedJobId && fileStore.hasLastRecording()) {
            file.delete()
            return fileStore.lastRecordingFile
        }
        return try {
            fileStore.promoteToLast(file)?.also { lastPromotedJobId = jobId }
        } catch (error: Exception) {
            diagnostics.error(
                "recording",
                "job=$jobId failed to preserve raw audio: ${error.message ?: error.javaClass.simpleName}",
            )
            null
        }
    }

    private fun validateRuntimeSettings(runtime: RuntimeSettings): String? {
        val errors = settingsRepository.validate(runtime.app).toMutableList()
        if (runtime.app.provider.baseUrl.isBlank()) errors.add("Base URL 不能为空")
        if (runtime.apiKey.isBlank()) errors.add("API Key 不能为空")
        if (runtime.app.provider.model.isBlank()) errors.add("Model 不能为空")
        return errors.firstOrNull()
    }

    private fun isCurrent(jobId: Long): Boolean = activeJob?.id == jobId

    private fun updateUi(jobId: Long?, state: JobUiState) {
        if (jobId != null && !isCurrent(jobId)) return
        uiState = state
        val version = ++uiVersion
        mainHandler.post {
            if (version != uiVersion) return@post
            listeners.forEach { it.onStateChanged(state) }
        }
    }

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(application, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun formatDelay(delayMillis: Long): String =
        if (delayMillis % 1_000L == 0L) {
            "${delayMillis / 1_000L} 秒"
        } else {
            "${delayMillis / 1_000.0} 秒"
        }

    private companion object {
        const val AMPLITUDE_INTERVAL_MS = 80L
    }
}
