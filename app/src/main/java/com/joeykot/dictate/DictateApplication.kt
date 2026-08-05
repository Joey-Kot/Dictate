package com.joeykot.dictate

import android.app.Application
import com.joeykot.dictate.job.VoiceJobController
import com.joeykot.dictate.settings.SettingsRepository
import com.joeykot.dictate.util.AudioFileStore
import com.joeykot.dictate.util.Diagnostics

class DictateApplication : Application() {
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var diagnostics: Diagnostics
        private set
    lateinit var audioFileStore: AudioFileStore
        private set
    lateinit var voiceJobController: VoiceJobController
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        diagnostics = Diagnostics(this)
        audioFileStore = AudioFileStore(this).also { it.cleanupTemporaryFiles() }
        voiceJobController = VoiceJobController(
            application = this,
            settingsRepository = settingsRepository,
            fileStore = audioFileStore,
            diagnostics = diagnostics,
        )
    }
}

