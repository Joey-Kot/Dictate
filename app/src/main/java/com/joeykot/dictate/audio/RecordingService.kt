package com.joeykot.dictate.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.joeykot.dictate.DictateApplication
import com.joeykot.dictate.R
import com.joeykot.dictate.ui.MainActivity
import java.io.File

class RecordingService : Service() {
    private val applicationState: DictateApplication
        get() = application as DictateApplication

    private var activeJobId: Long = NO_JOB
    private var recorder: AudioRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var unexpectedShutdown = false
    private var capturePaused = false
    private var sawActiveRecordingConfiguration = false
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            val currentRecorder = recorder ?: return
            if (!currentRecorder.isActive() || capturePaused) return
            val sessionId = currentRecorder.audioSessionId()
            val ownConfiguration = configs.firstOrNull { it.clientAudioSessionId == sessionId }
            if (ownConfiguration != null) {
                sawActiveRecordingConfiguration = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ownConfiguration.isClientSilenced) {
                    currentRecorder.fail("麦克风被其他应用或系统占用")
                }
            } else if (sawActiveRecordingConfiguration) {
                currentRecorder.fail("麦克风录音被系统中断")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        runCatching {
            getSystemService(AudioManager::class.java).registerAudioRecordingCallback(
                recordingCallback,
                Handler(Looper.getMainLooper()),
            )
        }.onFailure { error ->
            applicationState.diagnostics.error(
                "recording",
                "Unable to register microphone interruption callback: ${error.javaClass.simpleName}",
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        val jobId = intent.getLongExtra(EXTRA_JOB_ID, NO_JOB)
        when (action) {
            ACTION_START -> startCapture(jobId, intent.getStringExtra(EXTRA_RAW_PATH))
            ACTION_PAUSE -> if (jobId == activeJobId) pauseCapture()
            ACTION_RESUME -> if (jobId == activeJobId) resumeCapture()
            ACTION_STOP -> if (jobId == activeJobId) recorder?.stop(discard = false)
            ACTION_CANCEL -> if (jobId == activeJobId) recorder?.stop(discard = true)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onRecorderStarted(jobId: Long, source: Int, sourceRecorder: AudioRecorder) {
        if (activeJobId != jobId || recorder !== sourceRecorder) return
        val sourceName = if (source == MediaRecorder.AudioSource.VOICE_RECOGNITION) {
            "VOICE_RECOGNITION"
        } else {
            "MIC"
        }
        applicationState.diagnostics.info("recording", "job=$jobId source=$sourceName")
        applicationState.voiceJobController.onRecordingStarted(jobId)
    }

    private fun onRecorderAmplitude(jobId: Long, amplitude: Float, sourceRecorder: AudioRecorder) {
        if (activeJobId != jobId || recorder !== sourceRecorder) return
        applicationState.voiceJobController.onRecordingAmplitude(jobId, amplitude)
    }

    private fun onRecorderCompleted(
        jobId: Long,
        file: File,
        valid: Boolean,
        discarded: Boolean,
        sourceRecorder: AudioRecorder,
    ) {
        if (activeJobId != jobId || recorder !== sourceRecorder) return
        val wasUnexpected = unexpectedShutdown
        clearCaptureState()
        applicationState.voiceJobController.onRecordingCompleted(
            jobId = jobId,
            file = file,
            valid = valid,
            discarded = discarded,
            unexpected = wasUnexpected,
        )
        stopSelf()
    }

    private fun onRecorderFailed(
        jobId: Long,
        message: String,
        file: File,
        valid: Boolean,
        sourceRecorder: AudioRecorder,
    ) {
        if (activeJobId != jobId || recorder !== sourceRecorder) return
        applicationState.diagnostics.error("recording", "job=$jobId $message")
        clearCaptureState()
        applicationState.voiceJobController.onRecordingFailed(jobId, file, valid, message)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (recorder != null) {
            unexpectedShutdown = true
            recorder?.stop(discard = false)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        runCatching {
            getSystemService(AudioManager::class.java).unregisterAudioRecordingCallback(recordingCallback)
        }
        if (recorder != null) {
            unexpectedShutdown = true
            recorder?.stop(discard = false)
        }
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startCapture(jobId: Long, rawPath: String?) {
        if (jobId == NO_JOB || rawPath.isNullOrBlank()) {
            applicationState.voiceJobController.onRecordingServiceFailed(jobId, "录音服务缺少任务参数")
            stopSelf()
            return
        }
        if (recorder != null) recorder?.stop(discard = true)
        activeJobId = jobId
        unexpectedShutdown = false
        capturePaused = false
        sawActiveRecordingConfiguration = false

        try {
            val notification = buildNotification(paused = false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            acquireWakeLock()
        } catch (error: Exception) {
            clearCaptureState()
            applicationState.voiceJobController.onRecordingServiceFailed(
                jobId,
                "无法启动前台麦克风服务：${error.message ?: error.javaClass.simpleName}",
            )
            stopSelf()
            return
        }

        lateinit var createdRecorder: AudioRecorder
        val callback = object : AudioRecorder.Callback {
            override fun onStarted(source: Int) {
                onRecorderStarted(jobId, source, createdRecorder)
            }

            override fun onAmplitude(amplitude: Float) {
                onRecorderAmplitude(jobId, amplitude, createdRecorder)
            }

            override fun onCompleted(file: File, valid: Boolean, discarded: Boolean) {
                onRecorderCompleted(jobId, file, valid, discarded, createdRecorder)
            }

            override fun onFailed(message: String, file: File, valid: Boolean) {
                onRecorderFailed(jobId, message, file, valid, createdRecorder)
            }
        }
        createdRecorder = AudioRecorder(File(rawPath), callback)
        recorder = createdRecorder
        createdRecorder.start()
    }

    private fun pauseCapture() {
        capturePaused = true
        if (recorder?.pause() == true) {
            updateNotification(paused = true)
        } else {
            capturePaused = false
        }
    }

    private fun resumeCapture() {
        sawActiveRecordingConfiguration = false
        if (recorder?.resume() == true) {
            capturePaused = false
            updateNotification(paused = false)
        }
    }

    private fun clearCaptureState() {
        recorder = null
        activeJobId = NO_JOB
        capturePaused = false
        sawActiveRecordingConfiguration = false
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:recording",
        ).apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Voice recording",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows when Dictate is actively using the microphone"
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification(paused: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(paused))
    }

    private fun buildNotification(paused: Boolean): Notification {
        val openSettings = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openSettings,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (paused) "Dictate 录音已暂停" else "Dictate 正在录音")
            .setContentText(if (paused) "长按悬浮按钮恢复" else "麦克风正在使用中")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val ACTION_START = "com.joeykot.dictate.action.START_RECORDING"
        private const val ACTION_PAUSE = "com.joeykot.dictate.action.PAUSE_RECORDING"
        private const val ACTION_RESUME = "com.joeykot.dictate.action.RESUME_RECORDING"
        private const val ACTION_STOP = "com.joeykot.dictate.action.STOP_RECORDING"
        private const val ACTION_CANCEL = "com.joeykot.dictate.action.CANCEL_RECORDING"
        private const val EXTRA_JOB_ID = "job_id"
        private const val EXTRA_RAW_PATH = "raw_path"
        private const val CHANNEL_ID = "dictate_recording"
        private const val NOTIFICATION_ID = 41
        private const val NO_JOB = -1L
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1_000L

        fun startIntent(context: Context, jobId: Long, rawFile: File): Intent =
            Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_JOB_ID, jobId)
                .putExtra(EXTRA_RAW_PATH, rawFile.absolutePath)

        fun pauseIntent(context: Context, jobId: Long): Intent = commandIntent(context, ACTION_PAUSE, jobId)
        fun resumeIntent(context: Context, jobId: Long): Intent = commandIntent(context, ACTION_RESUME, jobId)
        fun stopIntent(context: Context, jobId: Long): Intent = commandIntent(context, ACTION_STOP, jobId)
        fun cancelIntent(context: Context, jobId: Long): Intent = commandIntent(context, ACTION_CANCEL, jobId)

        private fun commandIntent(context: Context, action: String, jobId: Long): Intent =
            Intent(context, RecordingService::class.java)
                .setAction(action)
                .putExtra(EXTRA_JOB_ID, jobId)
    }
}
