package com.joeykot.dictate.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import com.joeykot.dictate.DictateApplication
import com.joeykot.dictate.R
import com.joeykot.dictate.i18n.AppLocale
import com.joeykot.dictate.i18n.AppStrings
import com.joeykot.dictate.accessibility.DictateAccessibilityService
import com.joeykot.dictate.audio.RecordingService
import com.joeykot.dictate.model.AppSettings
import com.joeykot.dictate.model.AppLanguage
import com.joeykot.dictate.model.AudioCodec
import com.joeykot.dictate.model.AudioConfig
import com.joeykot.dictate.model.AudioContainer
import com.joeykot.dictate.model.DisplayConfig
import com.joeykot.dictate.model.InteractionConfig
import com.joeykot.dictate.model.OverlayColorScheme
import com.joeykot.dictate.model.OverlayPalette
import com.joeykot.dictate.model.ProviderConfig
import com.joeykot.dictate.model.RetryConfig
import com.joeykot.dictate.model.RuntimeSettings
import com.joeykot.dictate.settings.SettingsRepository
import com.joeykot.dictate.util.AccessibilityStatus
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.roundToInt

@SuppressLint("SetTextI18n")
class MainActivity : Activity() {
    private val app: DictateApplication
        get() = application as DictateApplication
    private val settingsRepository: SettingsRepository
        get() = app.settingsRepository

    private lateinit var accessibilityStatus: TextView
    private lateinit var microphoneStatus: TextView
    private lateinit var languageSpinner: Spinner

    private lateinit var bitDepthSpinner: Spinner
    private lateinit var sampleRateSpinner: Spinner
    private lateinit var codecSpinner: Spinner
    private lateinit var containerSpinner: Spinner
    private lateinit var bitrateSpinner: Spinner
    private lateinit var bitDepthRow: LinearLayout
    private lateinit var bitrateRow: LinearLayout
    private var displayedSampleRates: List<Int> = AudioConfig.SAMPLE_RATES
    private var displayedBitDepths: List<Int> = AudioConfig.BIT_DEPTHS
    private var linkingAudio = false
    private var displayedContainers: List<AudioContainer> = emptyList()
    private var displayedBitrates: List<Int> = emptyList()

    private lateinit var baseUrlInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var additionalJsonInput: EditText
    private lateinit var testButton: Button
    private lateinit var testResult: TextView
    private lateinit var postProcessingSection: PostProcessingSettingsView
    private var pendingIconEditorId: String? = null
    private var editorStateSaved = false

    private lateinit var retryEnabled: Switch
    private lateinit var maxRetriesInput: EditText
    private lateinit var initialBackoffInput: EditText
    private lateinit var alwaysCopyToClipboard: Switch
    private lateinit var longPressInput: EditText
    private lateinit var doubleTapInput: EditText

    private lateinit var buttonScaleSeekBar: SeekBar
    private lateinit var notificationsEnabled: Switch
    private lateinit var buttonScaleValue: TextView
    private lateinit var buttonOpacitySeekBar: SeekBar
    private lateinit var buttonOpacityValue: TextView
    private lateinit var colorSchemeSpinner: Spinner
    private lateinit var customColorsContainer: LinearLayout
    private lateinit var recordingPreview: View
    private lateinit var pausedPreview: View
    private lateinit var processingPreview: View
    private lateinit var recordingColorEditor: ColorEditor
    private lateinit var pausedColorEditor: ColorEditor
    private lateinit var processingColorEditor: ColorEditor
    private var customPaletteDraft = OverlayPalette.DEFAULT

    private lateinit var diagnosticsText: TextView
    private var loadingForm = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val settingsExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dictate-settings-save")
    }
    private var settingsWriteInProgress = false
    private var activityDestroyed = false

    private data class ColorEditor(
        val label: String,
        val row: LinearLayout,
        val swatch: View,
        val value: TextView,
    )

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
        loadSettingsIntoForm()
        pendingIconEditorId = savedInstanceState?.getString(STATE_ICON_EDITOR_ID)
        savedInstanceState?.getBundle(STATE_PROMPT_EDITOR)?.let(postProcessingSection::restoreEditorState)
        if (intent.getBooleanExtra(EXTRA_REQUEST_MICROPHONE, false)) {
            window.decorView.post { requestMicrophonePermissions() }
        }
    }

    override fun onResume() {
        super.onResume()
        editorStateSaved = false
        if (recreateForLanguageChange()) return
        refreshPermissionStatus()
    }

    private fun recreateForLanguageChange(): Boolean {
        val savedLanguage = AppLocale.readLanguage(this).tag
        if (resources.configuration.locales[0].language == Locale.forLanguageTag(savedLanguage).language) {
            return false
        }
        recreate()
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_ICON_EDITOR_ID, pendingIconEditorId)
        outState.putBundle(STATE_PROMPT_EDITOR, postProcessingSection.saveEditorState())
        editorStateSaved = true
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        activityDestroyed = true
        if (::postProcessingSection.isInitialized) {
            postProcessingSection.closeEditor(preserveDraft = isChangingConfigurations || (editorStateSaved && !isFinishing))
        }
        pendingIconEditorId = null
        settingsExecutor.shutdown()
        super.onDestroy()
    }

    @Deprecated("Uses the platform document picker for broad API 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ICON) {
            val editorId = pendingIconEditorId
            pendingIconEditorId = null
            if (resultCode == RESULT_OK && editorId != null) {
                data?.data?.let { postProcessingSection.onIconPicked(editorId, it) }
            }
            return
        }
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_EXPORT -> exportTo(uri)
            REQUEST_IMPORT -> importFrom(uri)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MICROPHONE) {
            refreshPermissionStatus()
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                toast(getString(R.string.main_microphone_denied))
            }
        }
    }

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(32))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.main_title)
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(20))
        }, matchWrap())

        root.addView(buildSetupPanel())
        root.addView(sectionTitle(getString(R.string.main_language)))
        root.addView(buildLanguageSection())
        root.addView(sectionTitle(getString(R.string.main_audio_section)))
        root.addView(buildAudioSection())
        root.addView(sectionTitle(getString(R.string.main_provider_section)))
        root.addView(buildProviderSection())
        root.addView(sectionTitle(getString(R.string.main_post_processing_section)))
        postProcessingSection = PostProcessingSettingsView(
            activity = this,
            repository = settingsRepository,
            enqueueWrite = { operation, onSuccess, onFailure ->
                enqueueSettingsWrite(operation, onSuccess, onFailure)
            },
            chooseIcon = { editorId -> beginIconImport(editorId) },
            testConnection = { config, apiKey, callback ->
                val runtime = settingsRepository.runtime()
                app.voiceJobController.testPostProcessingConnection(
                    runtime.copy(app = runtime.app.copy(postProcessing = config), postProcessingApiKey = apiKey),
                    callback,
                )
            },
            testPromptConnection = { prompt, apiKey, callback ->
                app.voiceJobController.testPromptConnection(settingsRepository.runtime(), prompt, apiKey, callback)
            },
            cancelPromptTest = app.voiceJobController::cancelPromptConnectionTest,
        )
        root.addView(postProcessingSection)
        root.addView(sectionTitle(getString(R.string.main_retry_section)))
        root.addView(buildRetrySection())
        root.addView(sectionTitle(getString(R.string.main_interaction_section)))
        root.addView(buildInteractionSection())
        root.addView(sectionTitle(getString(R.string.main_display_section)))
        root.addView(buildDisplaySection())

        root.addView(Button(this).apply {
            text = getString(R.string.main_save_settings)
            setOnClickListener { saveSettings(showConfirmation = true) }
        }, matchWrap(top = 20))

        val transferRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = getString(R.string.main_export_json)
                setOnClickListener { beginExport() }
            }, weighted())
            addView(Button(this@MainActivity).apply {
                text = getString(R.string.main_import_json)
                setOnClickListener { beginImport() }
            }, weighted(left = 8))
        }
        root.addView(transferRow, matchWrap(top = 8))

        val diagnosticsButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = getString(R.string.main_diagnostics_details)
                setOnClickListener {
                    diagnosticsText.text = app.diagnostics.snapshot().ifBlank { getString(R.string.main_no_diagnostics) }
                    diagnosticsText.visibility = if (diagnosticsText.visibility == View.VISIBLE) {
                        View.GONE
                    } else {
                        View.VISIBLE
                    }
                }
            }, weighted())
            addView(Button(this@MainActivity).apply {
                text = getString(R.string.main_clear_diagnostics)
                setOnClickListener {
                    app.diagnostics.clear()
                    diagnosticsText.text = getString(R.string.main_no_diagnostics)
                    toast(getString(R.string.main_diagnostics_cleared))
                }
            }, weighted(left = 8))
        }
        root.addView(diagnosticsButtons, matchWrap(top = 8))
        diagnosticsText = TextView(this).apply {
            visibility = View.GONE
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(diagnosticsText, matchWrap(top = 4))
        root.addView(sectionTitle(getString(R.string.main_about)))
        root.addView(buildAboutSection(), matchWrap())

        return scroll
    }

    private fun buildAboutSection(): View = TableLayout(this).apply {
        setColumnStretchable(1, true)
        setColumnShrinkable(1, true)
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        val entries = listOf(
            R.string.main_about_author to "Joey Kot",
            R.string.main_about_email to "joey.kot.x@gmail.com",
            R.string.main_about_license to "GPL-3.0-or-later",
            R.string.main_about_repo to "github.com/Joey-Kot/Dictate",
            R.string.main_about_version to version,
        )
        entries.forEach { (label, value) ->
            addView(TableRow(this@MainActivity).apply {
                addView(TextView(this@MainActivity).apply {
                    text = getString(label)
                    textSize = 14f
                    setTextColor(Color.GRAY)
                    setPadding(0, dp(6), dp(16), dp(6))
                })
                addView(TextView(this@MainActivity).apply {
                    text = value
                    textSize = 14f
                    setTextIsSelectable(true)
                    setPadding(0, dp(6), 0, dp(6))
                })
            })
        }
    }

    private fun buildSetupPanel(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setBackgroundColor(Color.argb(18, 21, 101, 192))

        accessibilityStatus = TextView(this@MainActivity)
        addView(accessibilityStatus)
        addView(Button(this@MainActivity).apply {
            text = getString(R.string.main_open_accessibility)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }, matchWrap(top = 6))

        microphoneStatus = TextView(this@MainActivity).apply { setPadding(0, dp(10), 0, 0) }
        addView(microphoneStatus)
        addView(Button(this@MainActivity).apply {
            text = getString(R.string.main_grant_microphone)
            setOnClickListener { requestMicrophonePermissions() }
        }, matchWrap(top = 6))
    }

    private fun buildLanguageSection(): View = verticalGroup().apply {
        languageSpinner = spinner(AppLanguage.entries.map { it.nativeName })
        addView(languageSpinner, matchWrap())
        addView(TextView(this@MainActivity).apply {
            text = getString(R.string.main_language_help)
            setTextColor(Color.GRAY)
            setPadding(0, dp(4), 0, 0)
        })
    }

    private fun buildAudioSection(): View = verticalGroup().apply {
        bitDepthSpinner = spinner(AudioConfig.BIT_DEPTHS.map { getString(R.string.main_bit_depth_value, it) })
        bitDepthRow = labeledRow(getString(R.string.main_bit_depth), bitDepthSpinner)
        addView(bitDepthRow)

        sampleRateSpinner = spinner(AudioConfig.SAMPLE_RATES.map(::formatSampleRate))
        addView(labeledRow(getString(R.string.main_sample_rate), sampleRateSpinner))

        codecSpinner = spinner(AudioCodec.entries.map { codecLabel(it) })
        addView(labeledRow(getString(R.string.main_codec), codecSpinner))

        containerSpinner = spinner(emptyList())
        addView(labeledRow(getString(R.string.main_container), containerSpinner))

        bitrateSpinner = spinner(emptyList())
        bitrateRow = labeledRow(getString(R.string.main_bitrate), bitrateSpinner)
        addView(bitrateRow)

        codecSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!loadingForm && !linkingAudio) {
                    // The initial callback can arrive after form loading, so preserve compatible choices.
                    updateAudioLinkage(
                        codec = AudioCodec.entries[position],
                        sampleRate = displayedSampleRates.getOrElse(sampleRateSpinner.selectedItemPosition) { AudioConfig.AUTO_SAMPLE_RATE },
                        desiredContainer = displayedContainers.getOrNull(
                            containerSpinner.selectedItemPosition,
                        ),
                        desiredBitrate = displayedBitrates.getOrNull(bitrateSpinner.selectedItemPosition),
                    )
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        sampleRateSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!loadingForm && !linkingAudio) {
                    updateAudioLinkage(
                        codec = AudioCodec.entries[codecSpinner.selectedItemPosition],
                        sampleRate = displayedSampleRates.getOrElse(position) { AudioConfig.AUTO_SAMPLE_RATE },
                        desiredContainer = displayedContainers.getOrNull(containerSpinner.selectedItemPosition),
                        desiredBitrate = displayedBitrates.getOrNull(bitrateSpinner.selectedItemPosition),
                    )
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        bitDepthSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!loadingForm && !linkingAudio) {
                    updateAudioLinkage(
                        AudioCodec.entries[codecSpinner.selectedItemPosition],
                        displayedSampleRates.getOrElse(sampleRateSpinner.selectedItemPosition) { AudioConfig.AUTO_SAMPLE_RATE },
                        displayedContainers.getOrNull(containerSpinner.selectedItemPosition),
                        displayedBitrates.getOrNull(bitrateSpinner.selectedItemPosition),
                    )
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun buildProviderSection(): View = verticalGroup().apply {
        addView(labeledRow(getString(R.string.main_provider), spinner(listOf("OpenAI Compatible")).apply { isEnabled = false }))
        baseUrlInput = editText(getString(R.string.main_base_url_hint))
        addView(labeledColumn(getString(R.string.main_base_url), baseUrlInput))

        apiKeyInput = editText(getString(R.string.main_api_key)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        addView(labeledColumn(getString(R.string.main_api_key), apiKeyInput))

        modelInput = editText(getString(R.string.main_model_hint))
        addView(labeledColumn(getString(R.string.main_model), modelInput))

        additionalJsonInput = editText(getString(R.string.main_additional_json_hint)).apply {
            setSingleLine(false)
            minLines = 4
            gravity = Gravity.TOP
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        addView(labeledColumn(getString(R.string.main_additional_json), additionalJsonInput))

        testButton = Button(this@MainActivity).apply {
            text = getString(R.string.main_test_connection)
            setOnClickListener { runConnectionTest() }
        }
        addView(testButton, matchWrap(top = 10))
        testResult = TextView(this@MainActivity).apply {
            setTextIsSelectable(true)
            setPadding(dp(4), dp(8), dp(4), 0)
        }
        addView(testResult)
    }

    private fun buildRetrySection(): View = verticalGroup().apply {
        retryEnabled = Switch(this@MainActivity).apply { text = getString(R.string.main_auto_retry) }
        addView(retryEnabled)
        maxRetriesInput = numericEditText()
        addView(labeledRow(getString(R.string.main_max_retries), maxRetriesInput))
        initialBackoffInput = decimalEditText()
        addView(labeledRow(getString(R.string.main_initial_backoff), initialBackoffInput))
    }

    private fun buildInteractionSection(): View = verticalGroup().apply {
        alwaysCopyToClipboard = Switch(this@MainActivity).apply {
            text = getString(R.string.main_always_copy)
        }
        addView(alwaysCopyToClipboard)
        longPressInput = numericEditText()
        addView(labeledRow(getString(R.string.main_long_press), longPressInput))
        doubleTapInput = numericEditText()
        addView(labeledRow(getString(R.string.main_double_tap), doubleTapInput))
        addView(TextView(this@MainActivity).apply {
            text = getString(R.string.main_gesture_help)
            setTextColor(Color.GRAY)
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun buildDisplaySection(): View = verticalGroup().apply {
        buttonScaleSeekBar = SeekBar(this@MainActivity).apply {
            max = BUTTON_SCALE_PROGRESS_MAX
        }
        buttonScaleValue = TextView(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(8), 0, 0, 0)
        }
        buttonScaleSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateButtonScaleValue(scaleFromProgress(progress))
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        addView(labeledRow(getString(R.string.main_button_size), sliderWithValue(buttonScaleSeekBar, buttonScaleValue)))

        buttonOpacitySeekBar = SeekBar(this@MainActivity).apply {
            max = BUTTON_OPACITY_PROGRESS_MAX
        }
        buttonOpacityValue = TextView(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(8), 0, 0, 0)
        }
        buttonOpacitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateButtonOpacityValue(opacityFromProgress(progress))
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        addView(labeledRow(getString(R.string.main_button_opacity), sliderWithValue(buttonOpacitySeekBar, buttonOpacityValue)))
        addView(TextView(this@MainActivity).apply {
            text = getString(R.string.main_opacity_help)
            setTextColor(Color.GRAY)
            setPadding(0, dp(4), 0, dp(4))
        })

        colorSchemeSpinner = spinner(OverlayColorScheme.entries.map(::colorSchemeLabel))
        addView(labeledRow(getString(R.string.main_color_scheme), colorSchemeSpinner))

        val previewRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        val recordingItem = colorPreviewItem(getString(R.string.main_recording))
        recordingPreview = recordingItem.first
        previewRow.addView(recordingItem.second, weighted())
        val pausedItem = colorPreviewItem(getString(R.string.main_paused))
        pausedPreview = pausedItem.first
        previewRow.addView(pausedItem.second, weighted(left = 8))
        val processingItem = colorPreviewItem(getString(R.string.main_processing))
        processingPreview = processingItem.first
        previewRow.addView(processingItem.second, weighted(left = 8))
        addView(previewRow)

        customColorsContainer = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, 0)
        }
        recordingColorEditor = colorEditor(
            label = getString(R.string.main_recording_color),
            currentColor = { customPaletteDraft.recordingColor },
        ) { color ->
            customPaletteDraft = customPaletteDraft.copy(recordingColor = color)
            updateColorSchemeUi()
        }
        customColorsContainer.addView(recordingColorEditor.row)
        pausedColorEditor = colorEditor(
            label = getString(R.string.main_paused_color),
            currentColor = { customPaletteDraft.pausedColor },
        ) { color ->
            customPaletteDraft = customPaletteDraft.copy(pausedColor = color)
            updateColorSchemeUi()
        }
        customColorsContainer.addView(pausedColorEditor.row)
        processingColorEditor = colorEditor(
            label = getString(R.string.main_processing_color),
            currentColor = { customPaletteDraft.processingColor },
        ) { color ->
            customPaletteDraft = customPaletteDraft.copy(processingColor = color)
            updateColorSchemeUi()
        }
        customColorsContainer.addView(processingColorEditor.row)
        addView(customColorsContainer)

        colorSchemeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!loadingForm) updateColorSchemeUi()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        notificationsEnabled = Switch(this@MainActivity).apply {
            text = getString(R.string.main_show_notifications)
            setOnClickListener {
                // Only the system can change visibility of foreground-service notifications.
                // Keep the switch truthful if the user returns without changing that setting.
                refreshNotificationStatus()
                openNotificationSettings()
            }
        }
        addView(notificationsEnabled, matchWrap(top = 12))
        addView(TextView(this@MainActivity).apply {
            text = getString(R.string.main_notifications_help)
            setTextColor(Color.GRAY)
            setPadding(0, dp(4), 0, 0)
        })
    }

    private fun loadSettingsIntoForm() {
        val settings = settingsRepository.get()
        loadingForm = true
        languageSpinner.setSelection(AppLanguage.entries.indexOf(settings.language).coerceAtLeast(0))
        codecSpinner.setSelection(AudioCodec.entries.indexOf(settings.audio.codec).coerceAtLeast(0))
        updateAudioLinkage(
            settings.audio.codec,
            settings.audio.sampleRate,
            settings.audio.container,
            settings.audio.bitrateBps,
            settings.audio.bitDepth,
        )
        baseUrlInput.setText(settings.provider.baseUrl)
        apiKeyInput.setText(settingsRepository.runtime().apiKey)
        modelInput.setText(settings.provider.model)
        additionalJsonInput.setText(settings.provider.additionalJson)
        postProcessingSection.load(settings.postProcessing, settingsRepository.runtime().postProcessingApiKey)
        retryEnabled.isChecked = settings.retry.enabled
        maxRetriesInput.setText(settings.retry.maxRetries.toString())
        initialBackoffInput.setText(settings.retry.initialBackoffSeconds.toString())
        alwaysCopyToClipboard.isChecked = settings.interaction.alwaysCopyToClipboard
        longPressInput.setText(settings.interaction.longPressMs.toString())
        doubleTapInput.setText(settings.interaction.doubleTapMs.toString())
        customPaletteDraft = settings.display.customPalette
        buttonScaleSeekBar.progress = scaleToProgress(settings.display.buttonScale)
        buttonOpacitySeekBar.progress = opacityToProgress(settings.display.buttonOpacity)
        colorSchemeSpinner.setSelection(
            OverlayColorScheme.entries.indexOf(settings.display.colorScheme).coerceAtLeast(0),
        )
        loadingForm = false
        updateColorSchemeUi()
    }

    private fun readRuntimeSettings(): RuntimeSettings {
        val codec = AudioCodec.entries[codecSpinner.selectedItemPosition]
        val container = displayedContainers.getOrNull(containerSpinner.selectedItemPosition)
            ?: AudioConfig.defaultContainer(codec)
        val settings = AppSettings(
            language = AppLanguage.entries.getOrElse(languageSpinner.selectedItemPosition) { AppLanguage.ENGLISH },
            audio = AudioConfig(
                bitDepth = displayedBitDepths.getOrElse(bitDepthSpinner.selectedItemPosition) { AudioConfig.DEFAULT_BIT_DEPTH },
                sampleRate = displayedSampleRates.getOrElse(sampleRateSpinner.selectedItemPosition) { AudioConfig.AUTO_SAMPLE_RATE },
                codec = codec,
                container = container,
                bitrateBps = displayedBitrates.getOrNull(bitrateSpinner.selectedItemPosition)
                    ?: AudioConfig.DEFAULT_BITRATE_BPS,
            ),
            provider = ProviderConfig(
                baseUrl = baseUrlInput.text.toString().trim(),
                model = modelInput.text.toString().trim(),
                additionalJson = additionalJsonInput.text.toString().trim(),
            ),
            postProcessing = postProcessingSection.readConfig(),
            retry = RetryConfig(
                enabled = retryEnabled.isChecked,
                maxRetries = maxRetriesInput.text.toString().toIntOrNull()
                    ?: throw IllegalArgumentException(getString(R.string.main_max_retries_integer)),
                initialBackoffSeconds = initialBackoffInput.text.toString().toDoubleOrNull()
                    ?: throw IllegalArgumentException(getString(R.string.main_backoff_number)),
            ),
            interaction = InteractionConfig(
                longPressMs = longPressInput.text.toString().toLongOrNull()
                    ?: throw IllegalArgumentException(getString(R.string.main_long_press_integer)),
                doubleTapMs = doubleTapInput.text.toString().toLongOrNull()
                    ?: throw IllegalArgumentException(getString(R.string.main_double_tap_integer)),
                alwaysCopyToClipboard = alwaysCopyToClipboard.isChecked,
            ),
            display = DisplayConfig(
                buttonScale = scaleFromProgress(buttonScaleSeekBar.progress),
                buttonOpacity = opacityFromProgress(buttonOpacitySeekBar.progress),
                colorScheme = selectedColorScheme(),
                customPalette = customPaletteDraft,
            ),
        )
        val errors = settingsRepository.validate(settings)
        if (errors.isNotEmpty()) throw IllegalArgumentException(errors.joinToString("\n"))
        return RuntimeSettings(settings, apiKeyInput.text.toString().trim(), postProcessingSection.readApiKey())
    }

    private fun saveSettings(
        showConfirmation: Boolean,
        onSaved: () -> Unit = {},
    ): Boolean {
        val runtime = try {
            readRuntimeSettings()
        } catch (error: Exception) {
            toast(error.message ?: getString(R.string.main_invalid_settings))
            return false
        }
        return enqueueSettingsWrite(
            operation = {
                val settings = runtime.app.copy(postProcessing = runtime.app.postProcessing.copy(
                    prompts = settingsRepository.get().postProcessing.prompts,
                ))
                settingsRepository.save(settings, runtime.apiKey, runtime.postProcessingApiKey)
            },
            onSuccess = {
                app.voiceJobController.refreshLanguage()
                refreshOverlayAppearance()
                if (showConfirmation) toast(AppStrings.get(R.string.main_settings_saved, "Settings saved"))
                onSaved()
                if (showConfirmation) recreateForLanguageChange()
            },
            onFailure = { error -> toast(error.message ?: getString(R.string.main_invalid_settings)) },
        )
    }

    private fun enqueueSettingsWrite(
        operation: () -> Unit,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit,
    ): Boolean {
        if (settingsWriteInProgress) {
            toast(getString(R.string.main_settings_saving))
            return false
        }
        settingsWriteInProgress = true
        return try {
            settingsExecutor.execute {
                val failure = try {
                    operation()
                    null
                } catch (error: Exception) {
                    error
                }
                mainHandler.post {
                    if (activityDestroyed) return@post
                    settingsWriteInProgress = false
                    if (failure == null) {
                        onSuccess()
                    } else {
                        onFailure(failure)
                    }
                }
            }
            true
        } catch (error: RejectedExecutionException) {
            settingsWriteInProgress = false
            if (!activityDestroyed) onFailure(error)
            false
        }
    }

    private fun runConnectionTest() {
        val runtime = try {
            readRuntimeSettings()
        } catch (error: IllegalArgumentException) {
            testResult.text = getString(R.string.main_test_failed, error.message)
            return
        }
        testButton.isEnabled = false
        testResult.text = getString(R.string.main_test_running)
        val accepted = app.voiceJobController.testConnection(runtime) { result ->
            testButton.isEnabled = true
            testResult.text = buildString {
                append(if (result.success) getString(R.string.main_success) else getString(R.string.main_failure))
                result.statusCode?.let { append("\n" + getString(R.string.main_test_http, it)) }
                result.elapsedMillis?.let { append("\n" + getString(R.string.main_test_elapsed, it)) }
                if (result.text.isNotBlank()) append("\n" + getString(R.string.main_test_text, result.text))
                if (result.message.isNotBlank()) append("\n" + getString(R.string.main_test_result, result.message))
                if (result.serverSummary.isNotBlank()) append("\n" + getString(R.string.main_test_summary, result.serverSummary))
            }
        }
        if (!accepted) testButton.isEnabled = true
    }

    private fun updateAudioLinkage(
        codec: AudioCodec,
        sampleRate: Int,
        desiredContainer: AudioContainer?,
        desiredBitrate: Int?,
        desiredDepth: Int = displayedBitDepths.getOrElse(bitDepthSpinner.selectedItemPosition) { AudioConfig.DEFAULT_BIT_DEPTH },
    ) {
        if (linkingAudio) return
        linkingAudio = true
        try {
            val config = AudioConfig(
                codec = codec, sampleRate = sampleRate, bitDepth = desiredDepth,
                container = desiredContainer ?: AudioConfig.defaultContainer(codec),
                bitrateBps = desiredBitrate ?: AudioConfig.DEFAULT_BITRATE_BPS,
            ).normalized()
            val rates = AudioConfig.compatibleSampleRates(codec)
            if (rates != displayedSampleRates) {
                displayedSampleRates = rates
                sampleRateSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, rates.map(::formatSampleRate))
            }
            sampleRateSpinner.setSelection(displayedSampleRates.indexOf(config.sampleRate).coerceAtLeast(0))
            val depths = AudioConfig.compatibleBitDepths(codec).ifEmpty { AudioConfig.BIT_DEPTHS }
            if (depths != displayedBitDepths) {
                displayedBitDepths = depths
                bitDepthSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, depths.map { getString(R.string.main_bit_depth_value, it) })
            }
            bitDepthSpinner.setSelection(displayedBitDepths.indexOf(config.bitDepth).coerceAtLeast(0))
            val containers = AudioConfig.compatibleContainers(codec, config.sampleRate, config.bitDepth)
            if (containers != displayedContainers) {
                displayedContainers = containers
                containerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, containers.map { it.value.uppercase() })
            }
            containerSpinner.setSelection(displayedContainers.indexOf(config.container).coerceAtLeast(0))
            val bitrates = AudioConfig.compatibleBitrates(codec, config.sampleRate)
            if (bitrates != displayedBitrates) {
                displayedBitrates = bitrates
                bitrateSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, bitrates.map {
                    java.math.BigDecimal(it).movePointLeft(3).stripTrailingZeros().toPlainString() + " kbps"
                })
            }
            bitrateSpinner.setSelection(displayedBitrates.indexOf(config.bitrateBps).coerceAtLeast(0))
            bitDepthRow.visibility = if (AudioConfig.compatibleBitDepths(codec).isNotEmpty()) View.VISIBLE else View.GONE
            bitrateRow.visibility = if (codec.usesBitrate) View.VISIBLE else View.GONE
        } finally {
            linkingAudio = false
        }
    }

    private fun refreshPermissionStatus() {
        accessibilityStatus.text = if (AccessibilityStatus.isEnabled(this)) {
            getString(R.string.main_accessibility_enabled)
        } else {
            getString(R.string.main_accessibility_disabled)
        }
        microphoneStatus.text = if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            getString(R.string.main_microphone_granted_status)
        } else {
            getString(R.string.main_microphone_denied_status)
        }
        refreshNotificationStatus()
    }

    private fun refreshNotificationStatus() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(RecordingService.CHANNEL_ID)
        notificationsEnabled.isChecked = manager.areNotificationsEnabled() &&
            channel?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun openNotificationSettings() {
        val manager = getSystemService(NotificationManager::class.java)
        val channelBlocked = manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(RecordingService.CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
        val intent = Intent(
            if (channelBlocked) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS,
        ).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        if (channelBlocked) intent.putExtra(Settings.EXTRA_CHANNEL_ID, RecordingService.CHANNEL_ID)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: ActivityNotFoundException) {
                toast(getString(R.string.main_notifications_unavailable))
            }
        }
    }

    private fun requestMicrophonePermissions() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            toast(getString(R.string.main_microphone_granted))
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
        }
    }

    @Suppress("DEPRECATION")
    private fun beginIconImport(editorId: String) {
        pendingIconEditorId = editorId
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/svg+xml", "image/png", "image/jpeg"))
            }, REQUEST_ICON)
        } catch (error: Exception) {
            pendingIconEditorId = null
            toast(getString(R.string.main_icon_picker_failed, error.message ?: getString(R.string.main_file_manager_unavailable)))
        }
    }

    @Suppress("DEPRECATION")
    private fun beginExport() {
        saveSettings(showConfirmation = false) {
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "dictate-settings.json")
                },
                REQUEST_EXPORT,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun beginImport() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
            },
            REQUEST_IMPORT,
        )
    }

    private fun exportTo(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use { writer ->
                writer.write(settingsRepository.exportJson())
            } ?: throw IOException(getString(R.string.main_export_open_failed))
            toast(getString(R.string.main_exported))
        } catch (error: Exception) {
            toast(getString(R.string.main_export_failed, error.message ?: error.javaClass.simpleName))
        }
    }

    private fun importFrom(uri: Uri) {
        try {
            val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                val content = reader.readText()
                if (content.length > MAX_IMPORT_CHARS) throw IllegalArgumentException(getString(R.string.main_import_too_large))
                content
            } ?: throw IOException(getString(R.string.main_import_read_failed))
            val preview = settingsRepository.previewImport(json)
            if (preview.hasApiKeys) {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.main_import_keys_title))
                    .setMessage(getString(R.string.main_import_keys_message))
                    .setPositiveButton(getString(R.string.main_import_confirm)) { _, _ -> applyImport(preview, true) }
                    .setNegativeButton(getString(R.string.main_cancel), null)
                    .show()
            } else {
                applyImport(preview, false)
            }
        } catch (error: Exception) {
            toast(getString(R.string.main_import_failed, error.message ?: error.javaClass.simpleName))
        }
    }

    private fun applyImport(preview: SettingsRepository.ImportPreview, allowApiKey: Boolean) {
        enqueueSettingsWrite(
            operation = { settingsRepository.applyImport(preview, allowApiKey) },
            onSuccess = {
                loadSettingsIntoForm()
                app.voiceJobController.refreshLanguage()
                refreshOverlayAppearance()
                toast(AppStrings.get(R.string.main_imported, "Settings imported"))
                recreateForLanguageChange()
            },
            onFailure = { error ->
                toast(getString(R.string.main_import_failed, error.message ?: error.javaClass.simpleName))
            },
        )
    }

    private fun sliderWithValue(seekBar: SeekBar, value: TextView): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                seekBar,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                value,
                LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

    private fun colorPreviewItem(label: String): Pair<View, LinearLayout> {
        val swatch = View(this).apply {
            contentDescription = getString(R.string.main_color_preview, label)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(swatch, LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 13f
                setPadding(dp(6), 0, 0, 0)
            })
        }
        return swatch to container
    }

    private fun colorEditor(
        label: String,
        currentColor: () -> Int,
        onColorSelected: (Int) -> Unit,
    ): ColorEditor {
        val swatch = View(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val value = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(8), 0, dp(8), 0)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            isClickable = true
            isFocusable = true
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 15f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(value, LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(swatch, LinearLayout.LayoutParams(dp(32), dp(32)))
            setOnClickListener {
                showColorPicker(label, currentColor(), onColorSelected)
            }
        }
        return ColorEditor(label, row, swatch, value)
    }

    private fun showColorPicker(
        label: String,
        currentColor: Int,
        onColorSelected: (Int) -> Unit,
    ) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val picker = CircularColorPickerView(this, currentColor)
        content.addView(
            picker,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(COLOR_PICKER_SIZE_DP),
            ),
        )
        val valueRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val preview = View(this)
        val value = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 16f
            setPadding(dp(12), 0, 0, 0)
        }
        valueRow.addView(preview, LinearLayout.LayoutParams(dp(36), dp(36)))
        valueRow.addView(value)
        content.addView(valueRow)

        content.addView(TextView(this).apply {
            text = getString(R.string.main_brightness)
            setPadding(0, dp(14), 0, 0)
        })
        val brightness = SeekBar(this).apply { max = 100 }
        content.addView(brightness, matchWrap())

        fun renderColor(color: Int) {
            setColorSwatch(preview, color)
            value.text = colorToHex(color)
        }

        picker.onColorChanged = ::renderColor
        brightness.progress = (picker.brightness * 100f).roundToInt().coerceIn(0, 100)
        brightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                picker.brightness = progress / 100f
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        renderColor(picker.color)

        AlertDialog.Builder(this)
            .setTitle(label)
            .setView(content)
            .setNegativeButton(getString(R.string.main_cancel), null)
            .setPositiveButton(getString(R.string.main_ok)) { _, _ -> onColorSelected(picker.color) }
            .show()
    }

    private fun updateColorSchemeUi() {
        val colorScheme = selectedColorScheme()
        customColorsContainer.visibility = if (colorScheme == OverlayColorScheme.CUSTOM) {
            View.VISIBLE
        } else {
            View.GONE
        }
        updateColorEditor(recordingColorEditor, customPaletteDraft.recordingColor)
        updateColorEditor(pausedColorEditor, customPaletteDraft.pausedColor)
        updateColorEditor(processingColorEditor, customPaletteDraft.processingColor)

        val palette = DisplayConfig(
            colorScheme = colorScheme,
            customPalette = customPaletteDraft,
        ).effectivePalette()
        setColorSwatch(recordingPreview, palette.recordingColor)
        setColorSwatch(pausedPreview, palette.pausedColor)
        setColorSwatch(processingPreview, palette.processingColor)
    }

    private fun updateColorEditor(editor: ColorEditor, color: Int) {
        editor.value.text = colorToHex(color)
        editor.row.contentDescription = getString(R.string.main_edit_color, editor.label, colorToHex(color))
        setColorSwatch(editor.swatch, color)
    }

    private fun setColorSwatch(view: View, color: Int) {
        val outline = if (isLightColor(color)) Color.DKGRAY else Color.argb(110, 255, 255, 255)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.rgb((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF))
            setStroke(dp(1), outline)
        }
    }

    private fun selectedColorScheme(): OverlayColorScheme =
        OverlayColorScheme.entries.getOrNull(colorSchemeSpinner.selectedItemPosition)
            ?: OverlayColorScheme.DEFAULT

    private fun updateButtonScaleValue(scale: Float) {
        buttonScaleValue.text = String.format(Locale.ROOT, "%.2f×", scale)
    }

    private fun updateButtonOpacityValue(opacity: Float) {
        buttonOpacityValue.text = "${(opacity * 100f).roundToInt()}%"
    }

    private fun scaleFromProgress(progress: Int): Float =
        DisplayConfig.MIN_BUTTON_SCALE + progress.coerceIn(0, BUTTON_SCALE_PROGRESS_MAX) / 100f

    private fun scaleToProgress(scale: Float): Int =
        ((scale.coerceIn(DisplayConfig.MIN_BUTTON_SCALE, DisplayConfig.MAX_BUTTON_SCALE) -
            DisplayConfig.MIN_BUTTON_SCALE) * 100f).roundToInt()

    private fun opacityFromProgress(progress: Int): Float =
        DisplayConfig.MIN_BUTTON_OPACITY + progress.coerceIn(0, BUTTON_OPACITY_PROGRESS_MAX) / 100f

    private fun opacityToProgress(opacity: Float): Int =
        ((opacity.coerceIn(DisplayConfig.MIN_BUTTON_OPACITY, DisplayConfig.MAX_BUTTON_OPACITY) -
            DisplayConfig.MIN_BUTTON_OPACITY) * 100f).roundToInt()

    private fun colorSchemeLabel(scheme: OverlayColorScheme): String = when (scheme) {
        OverlayColorScheme.DEFAULT -> getString(R.string.main_scheme_default)
        OverlayColorScheme.OCEAN -> getString(R.string.main_scheme_ocean)
        OverlayColorScheme.SUNSET -> getString(R.string.main_scheme_sunset)
        OverlayColorScheme.COLOR_BLIND -> getString(R.string.main_scheme_color_blind)
        OverlayColorScheme.CUSTOM -> getString(R.string.main_scheme_custom)
    }

    private fun colorToHex(color: Int): String = String.format(Locale.ROOT, "#%06X", color)

    private fun isLightColor(color: Int): Boolean {
        val red = (color shr 16) and 0xFF
        val green = (color shr 8) and 0xFF
        val blue = color and 0xFF
        return red * 299 + green * 587 + blue * 114 >= 160_000
    }

    private fun refreshOverlayAppearance() {
        DictateAccessibilityService.current()?.refreshOverlayAppearance()
    }

    private fun verticalGroup(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(2), 0, dp(8))
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(22), 0, dp(8))
    }

    private fun labeledRow(label: String, control: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 15f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f))
        addView(control, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f))
    }

    private fun labeledColumn(label: String, control: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@MainActivity).apply {
            text = label
            setPadding(0, dp(8), 0, dp(2))
        })
        addView(control, matchWrap())
    }

    private fun spinner(items: List<String>): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(
            this@MainActivity,
            android.R.layout.simple_spinner_dropdown_item,
            items,
        )
    }

    private fun editText(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        setSingleLine(true)
    }

    private fun numericEditText(): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine(true)
    }

    private fun decimalEditText(): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setSingleLine(true)
    }

    private fun codecLabel(codec: AudioCodec): String = codec.label

    private fun formatSampleRate(value: Int): String = when (value) {
        AudioConfig.AUTO_SAMPLE_RATE -> getString(R.string.main_sample_rate_auto)
        else -> java.math.BigDecimal(value).movePointLeft(3).stripTrailingZeros().toPlainString() + " kHz"
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWrap(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(top) }

    private fun weighted(left: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(left)
        }

    companion object {
        const val EXTRA_REQUEST_MICROPHONE = "request_microphone"
        private const val REQUEST_MICROPHONE = 100
        private const val REQUEST_EXPORT = 101
        private const val REQUEST_IMPORT = 102
        private const val REQUEST_ICON = 103
        private const val STATE_ICON_EDITOR_ID = "pending_icon_editor_id"
        private const val STATE_PROMPT_EDITOR = "prompt_editor_draft"
        private const val MAX_IMPORT_CHARS = 16 * 1_024 * 1_024
        private const val BUTTON_SCALE_PROGRESS_MAX = 150
        private const val BUTTON_OPACITY_PROGRESS_MAX = 70
        private const val COLOR_PICKER_SIZE_DP = 240
    }
}
