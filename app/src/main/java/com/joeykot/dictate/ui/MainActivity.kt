package com.joeykot.dictate.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
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
import android.widget.TextView
import android.widget.Toast
import com.joeykot.dictate.DictateApplication
import com.joeykot.dictate.accessibility.DictateAccessibilityService
import com.joeykot.dictate.model.AppSettings
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

    private lateinit var bitDepthSpinner: Spinner
    private lateinit var sampleRateSpinner: Spinner
    private lateinit var codecSpinner: Spinner
    private lateinit var containerSpinner: Spinner
    private lateinit var bitrateSpinner: Spinner
    private lateinit var bitDepthRow: LinearLayout
    private lateinit var bitrateRow: LinearLayout
    private var displayedContainers: List<AudioContainer> = emptyList()
    private var displayedBitrates: List<Int> = emptyList()

    private lateinit var baseUrlInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var additionalJsonInput: EditText
    private lateinit var testButton: Button
    private lateinit var testResult: TextView

    private lateinit var retryEnabled: Switch
    private lateinit var maxRetriesInput: EditText
    private lateinit var initialBackoffInput: EditText
    private lateinit var alwaysCopyToClipboard: Switch
    private lateinit var longPressInput: EditText
    private lateinit var doubleTapInput: EditText

    private lateinit var buttonScaleSeekBar: SeekBar
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
        loadSettingsIntoForm()
        if (intent.getBooleanExtra(EXTRA_REQUEST_MICROPHONE, false)) {
            window.decorView.post { requestMicrophonePermissions() }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
    }

    override fun onDestroy() {
        activityDestroyed = true
        settingsExecutor.shutdown()
        super.onDestroy()
    }

    @Deprecated("Uses the platform document picker for broad API 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
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
                toast("未授予麦克风权限，无法开始录音")
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
            text = "Dictate"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "语音转写增强层，不注册或抢占系统输入法。"
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(0, dp(4), 0, dp(16))
        })

        root.addView(buildSetupPanel())
        root.addView(sectionTitle("1. 音频输出"))
        root.addView(buildAudioSection())
        root.addView(sectionTitle("2. OpenAI Compatible"))
        root.addView(buildProviderSection())
        root.addView(sectionTitle("3. 重试"))
        root.addView(buildRetrySection())
        root.addView(sectionTitle("4. 交互"))
        root.addView(buildInteractionSection())
        root.addView(sectionTitle("5. 显示"))
        root.addView(buildDisplaySection())

        root.addView(Button(this).apply {
            text = "保存设置"
            setOnClickListener { saveSettings(showConfirmation = true) }
        }, matchWrap(top = 20))

        val transferRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = "导出 JSON"
                setOnClickListener { beginExport() }
            }, weighted())
            addView(Button(this@MainActivity).apply {
                text = "导入 JSON"
                setOnClickListener { beginImport() }
            }, weighted(left = 8))
        }
        root.addView(transferRow, matchWrap(top = 8))

        val diagnosticsButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = "诊断详情"
                setOnClickListener {
                    diagnosticsText.text = app.diagnostics.snapshot().ifBlank { "暂无诊断信息" }
                    diagnosticsText.visibility = if (diagnosticsText.visibility == View.VISIBLE) {
                        View.GONE
                    } else {
                        View.VISIBLE
                    }
                }
            }, weighted())
            addView(Button(this@MainActivity).apply {
                text = "清理诊断"
                setOnClickListener {
                    app.diagnostics.clear()
                    diagnosticsText.text = "暂无诊断信息"
                    toast("诊断信息已清理；设置和上一条录音未受影响")
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

        return scroll
    }

    private fun buildSetupPanel(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setBackgroundColor(Color.argb(18, 21, 101, 192))

        accessibilityStatus = TextView(this@MainActivity)
        addView(accessibilityStatus)
        addView(Button(this@MainActivity).apply {
            text = "打开无障碍设置"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }, matchWrap(top = 6))

        microphoneStatus = TextView(this@MainActivity).apply { setPadding(0, dp(10), 0, 0) }
        addView(microphoneStatus)
        addView(Button(this@MainActivity).apply {
            text = "授予麦克风权限"
            setOnClickListener { requestMicrophonePermissions() }
        }, matchWrap(top = 6))
    }

    private fun buildAudioSection(): View = verticalGroup().apply {
        bitDepthSpinner = spinner(AudioConfig.BIT_DEPTHS.map { "$it 位" })
        bitDepthRow = labeledRow("位深度", bitDepthSpinner)
        addView(bitDepthRow)

        sampleRateSpinner = spinner(AudioConfig.SAMPLE_RATES.map(::formatSampleRate))
        addView(labeledRow("采样率", sampleRateSpinner))

        codecSpinner = spinner(AudioCodec.entries.map { codecLabel(it) })
        addView(labeledRow("编码", codecSpinner))

        containerSpinner = spinner(emptyList())
        addView(labeledRow("容器", containerSpinner))

        bitrateSpinner = spinner(emptyList())
        bitrateRow = labeledRow("码率", bitrateSpinner)
        addView(bitrateRow)

        codecSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!loadingForm) {
                    // The initial callback can arrive after form loading, so preserve compatible choices.
                    updateAudioLinkage(
                        codec = AudioCodec.entries[position],
                        sampleRate = AudioConfig.SAMPLE_RATES[sampleRateSpinner.selectedItemPosition],
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
                if (!loadingForm) {
                    updateAudioLinkage(
                        codec = AudioCodec.entries[codecSpinner.selectedItemPosition],
                        sampleRate = AudioConfig.SAMPLE_RATES[position],
                        desiredContainer = displayedContainers.getOrNull(containerSpinner.selectedItemPosition),
                        desiredBitrate = displayedBitrates.getOrNull(bitrateSpinner.selectedItemPosition),
                    )
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun buildProviderSection(): View = verticalGroup().apply {
        addView(labeledRow("Provider", spinner(listOf("OpenAI Compatible")).apply { isEnabled = false }))
        baseUrlInput = editText("https://example.com 或 https://example.com/v1")
        addView(labeledColumn("Base URL", baseUrlInput))

        apiKeyInput = editText("API Key").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        addView(labeledColumn("API Key", apiKeyInput))

        modelInput = editText("例如 whisper-1")
        addView(labeledColumn("Model", modelInput))

        additionalJsonInput = editText("可留空，或填写扁平 JSON 对象").apply {
            setSingleLine(false)
            minLines = 4
            gravity = Gravity.TOP
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        addView(labeledColumn("附加参数 JSON", additionalJsonInput))

        testButton = Button(this@MainActivity).apply {
            text = "测试连接（真实转写请求）"
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
        retryEnabled = Switch(this@MainActivity).apply { text = "自动重试（默认关闭）" }
        addView(retryEnabled)
        maxRetriesInput = numericEditText()
        addView(labeledRow("最大重试次数", maxRetriesInput))
        initialBackoffInput = decimalEditText()
        addView(labeledRow("初始退避（秒）", initialBackoffInput))
    }

    private fun buildInteractionSection(): View = verticalGroup().apply {
        alwaysCopyToClipboard = Switch(this@MainActivity).apply {
            text = "转写成功后始终复制到剪贴板（默认开启）"
        }
        addView(alwaysCopyToClipboard)
        longPressInput = numericEditText()
        addView(labeledRow("长按阈值（ms）", longPressInput))
        doubleTapInput = numericEditText()
        addView(labeledRow("双击最大间隔（ms）", doubleTapInput))
        addView(TextView(this@MainActivity).apply {
            text = "长按在抬起时确认；移动超过系统阈值始终优先判为拖动。双击按两次抬起的时间差判定。"
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
        addView(labeledRow("按钮大小", sliderWithValue(buttonScaleSeekBar, buttonScaleValue)))

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
        addView(labeledRow("按钮不透明度", sliderWithValue(buttonOpacitySeekBar, buttonOpacityValue)))
        addView(TextView(this@MainActivity).apply {
            text = "透明度会在现有空闲、工作、按压与处理中动画的透明度基础上统一叠乘。"
            setTextColor(Color.GRAY)
            setPadding(0, dp(4), 0, dp(4))
        })

        colorSchemeSpinner = spinner(OverlayColorScheme.entries.map(::colorSchemeLabel))
        addView(labeledRow("色系搭配", colorSchemeSpinner))

        val previewRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        val recordingItem = colorPreviewItem("录制")
        recordingPreview = recordingItem.first
        previewRow.addView(recordingItem.second, weighted())
        val pausedItem = colorPreviewItem("暂停")
        pausedPreview = pausedItem.first
        previewRow.addView(pausedItem.second, weighted(left = 8))
        val processingItem = colorPreviewItem("处理中")
        processingPreview = processingItem.first
        previewRow.addView(processingItem.second, weighted(left = 8))
        addView(previewRow)

        customColorsContainer = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, 0)
        }
        recordingColorEditor = colorEditor(
            label = "录制颜色",
            currentColor = { customPaletteDraft.recordingColor },
        ) { color ->
            customPaletteDraft = customPaletteDraft.copy(recordingColor = color)
            updateColorSchemeUi()
        }
        customColorsContainer.addView(recordingColorEditor.row)
        pausedColorEditor = colorEditor(
            label = "暂停颜色",
            currentColor = { customPaletteDraft.pausedColor },
        ) { color ->
            customPaletteDraft = customPaletteDraft.copy(pausedColor = color)
            updateColorSchemeUi()
        }
        customColorsContainer.addView(pausedColorEditor.row)
        processingColorEditor = colorEditor(
            label = "处理中颜色",
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
    }

    private fun loadSettingsIntoForm() {
        val settings = settingsRepository.get()
        loadingForm = true
        bitDepthSpinner.setSelection(AudioConfig.BIT_DEPTHS.indexOf(settings.audio.bitDepth).coerceAtLeast(0))
        sampleRateSpinner.setSelection(AudioConfig.SAMPLE_RATES.indexOf(settings.audio.sampleRate).coerceAtLeast(0))
        codecSpinner.setSelection(AudioCodec.entries.indexOf(settings.audio.codec).coerceAtLeast(0))
        updateAudioLinkage(
            settings.audio.codec,
            settings.audio.sampleRate,
            settings.audio.container,
            settings.audio.bitrateKbps,
        )
        baseUrlInput.setText(settings.provider.baseUrl)
        apiKeyInput.setText(settingsRepository.runtime().apiKey)
        modelInput.setText(settings.provider.model)
        additionalJsonInput.setText(settings.provider.additionalJson)
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
            audio = AudioConfig(
                bitDepth = AudioConfig.BIT_DEPTHS[bitDepthSpinner.selectedItemPosition],
                sampleRate = AudioConfig.SAMPLE_RATES[sampleRateSpinner.selectedItemPosition],
                codec = codec,
                container = container,
                bitrateKbps = displayedBitrates.getOrNull(bitrateSpinner.selectedItemPosition)
                    ?: AudioConfig.DEFAULT_BITRATE_KBPS,
            ),
            provider = ProviderConfig(
                baseUrl = baseUrlInput.text.toString().trim(),
                model = modelInput.text.toString().trim(),
                additionalJson = additionalJsonInput.text.toString().trim(),
            ),
            retry = RetryConfig(
                enabled = retryEnabled.isChecked,
                maxRetries = maxRetriesInput.text.toString().toIntOrNull()
                    ?: throw IllegalArgumentException("最大重试次数必须是整数"),
                initialBackoffSeconds = initialBackoffInput.text.toString().toDoubleOrNull()
                    ?: throw IllegalArgumentException("初始退避时间必须是数字"),
            ),
            interaction = InteractionConfig(
                longPressMs = longPressInput.text.toString().toLongOrNull()
                    ?: throw IllegalArgumentException("长按阈值必须是整数"),
                doubleTapMs = doubleTapInput.text.toString().toLongOrNull()
                    ?: throw IllegalArgumentException("双击间隔必须是整数"),
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
        if (errors.isNotEmpty()) throw IllegalArgumentException(errors.joinToString("；"))
        return RuntimeSettings(settings, apiKeyInput.text.toString().trim())
    }

    private fun saveSettings(
        showConfirmation: Boolean,
        onSaved: () -> Unit = {},
    ): Boolean {
        val runtime = try {
            readRuntimeSettings()
        } catch (error: Exception) {
            toast(error.message ?: "设置无效")
            return false
        }
        return enqueueSettingsWrite(
            operation = { settingsRepository.save(runtime.app, runtime.apiKey) },
            onSuccess = {
                refreshOverlayAppearance()
                if (showConfirmation) toast("设置已保存")
                onSaved()
            },
            onFailure = { error -> toast(error.message ?: "设置无效") },
        )
    }

    private fun enqueueSettingsWrite(
        operation: () -> Unit,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit,
    ): Boolean {
        if (settingsWriteInProgress) {
            toast("设置正在保存")
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
            testResult.text = "失败：${error.message}"
            return
        }
        testButton.isEnabled = false
        testResult.text = "正在执行真实转写请求…"
        val accepted = app.voiceJobController.testConnection(runtime) { result ->
            testButton.isEnabled = true
            testResult.text = buildString {
                append(if (result.success) "成功" else "失败")
                result.statusCode?.let { append("\nHTTP：$it") }
                result.elapsedMillis?.let { append("\n耗时：$it ms") }
                if (result.text.isNotBlank()) append("\ntext：${result.text}")
                if (result.message.isNotBlank()) append("\n结果：${result.message}")
                if (result.serverSummary.isNotBlank()) append("\n服务端摘要：${result.serverSummary}")
            }
        }
        if (!accepted) testButton.isEnabled = true
    }

    private fun updateAudioLinkage(
        codec: AudioCodec,
        sampleRate: Int,
        desiredContainer: AudioContainer?,
        desiredBitrate: Int?,
    ) {
        displayedContainers = AudioConfig.compatibleContainers(codec)
        containerSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            displayedContainers.map { it.value.uppercase() },
        )
        val selected = desiredContainer?.takeIf { it in displayedContainers }
            ?: AudioConfig.defaultContainer(codec)
        containerSpinner.setSelection(displayedContainers.indexOf(selected).coerceAtLeast(0))

        displayedBitrates = AudioConfig.compatibleBitrates(codec, sampleRate)
        bitrateSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            displayedBitrates.map { "$it kbps" },
        )
        val selectedBitrate = desiredBitrate?.takeIf { it in displayedBitrates }
            ?: AudioConfig.defaultBitrate(codec, sampleRate)
        bitrateSpinner.setSelection(displayedBitrates.indexOf(selectedBitrate).coerceAtLeast(0))
        bitDepthRow.visibility = if (codec == AudioCodec.PCM) View.VISIBLE else View.GONE
        bitrateRow.visibility = if (codec == AudioCodec.PCM) View.GONE else View.VISIBLE
    }

    private fun refreshPermissionStatus() {
        accessibilityStatus.text = if (AccessibilityStatus.isEnabled(this)) {
            "无障碍服务：已启用"
        } else {
            "无障碍服务：未启用。启用后才会显示悬浮按钮。"
        }
        microphoneStatus.text = if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            "麦克风权限：已授予"
        } else {
            "麦克风权限：未授予"
        }
    }

    private fun requestMicrophonePermissions() {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (permissions.isEmpty()) {
            toast("麦克风权限已授予")
        } else {
            requestPermissions(permissions.toTypedArray(), REQUEST_MICROPHONE)
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
            } ?: throw IOException("无法打开导出文件")
            toast("配置已导出；文件不包含 API Key")
        } catch (error: Exception) {
            toast("导出失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun importFrom(uri: Uri) {
        try {
            val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                val content = reader.readText()
                if (content.length > MAX_IMPORT_CHARS) throw IllegalArgumentException("导入文件过大")
                content
            } ?: throw IOException("无法读取导入文件")
            val preview = settingsRepository.previewImport(json)
            if (preview.apiKey != null) {
                AlertDialog.Builder(this)
                    .setTitle("导入 API Key？")
                    .setMessage("此配置文件包含 API Key。确认后，密钥会写入 Android Keystore 支持的安全存储。")
                    .setPositiveButton("确认导入") { _, _ -> applyImport(preview, true) }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                applyImport(preview, false)
            }
        } catch (error: Exception) {
            toast("导入失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun applyImport(preview: SettingsRepository.ImportPreview, allowApiKey: Boolean) {
        enqueueSettingsWrite(
            operation = { settingsRepository.applyImport(preview, allowApiKey) },
            onSuccess = {
                loadSettingsIntoForm()
                refreshOverlayAppearance()
                toast("配置已导入")
            },
            onFailure = { error ->
                toast("导入失败：${error.message ?: error.javaClass.simpleName}")
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
            contentDescription = "${label}颜色预览"
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
            text = "亮度"
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
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ -> onColorSelected(picker.color) }
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
        editor.row.contentDescription = "编辑${editor.label}，当前为 ${colorToHex(color)}"
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
        OverlayColorScheme.DEFAULT -> "默认（当前配色）"
        OverlayColorScheme.OCEAN -> "海洋"
        OverlayColorScheme.SUNSET -> "日落"
        OverlayColorScheme.COLOR_BLIND -> "色盲友好"
        OverlayColorScheme.CUSTOM -> "Custom"
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

    private fun codecLabel(codec: AudioCodec): String = when (codec) {
        AudioCodec.OPUS -> "Opus"
        AudioCodec.MP3 -> "MP3"
        AudioCodec.AAC -> "AAC"
        AudioCodec.PCM -> "PCM"
    }

    private fun formatSampleRate(value: Int): String = when (value) {
        44_100 -> "44.1 kHz"
        else -> "${value / 1_000} kHz"
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
        private const val MAX_IMPORT_CHARS = 1_000_000
        private const val BUTTON_SCALE_PROGRESS_MAX = 150
        private const val BUTTON_OPACITY_PROGRESS_MAX = 70
        private const val COLOR_PICKER_SIZE_DP = 240
    }
}
