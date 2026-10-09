package com.joeykot.dictate.settings

import com.joeykot.dictate.R
import com.joeykot.dictate.advanced_audio.AdvancedAudioWorkflowCodec
import com.joeykot.dictate.advanced_audio.WorkflowValidator
import com.joeykot.dictate.advanced_audio.remote_audio.RemoteAudioConfigValidator
import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.joeykot.dictate.i18n.AppLocale
import com.joeykot.dictate.i18n.AppStrings
import com.joeykot.dictate.model.AdvancedAudioConfig
import com.joeykot.dictate.model.RemoteAudioCredentialIds
import com.joeykot.dictate.model.AppLanguage
import com.joeykot.dictate.model.AppSettings
import com.joeykot.dictate.model.AudioCodec
import com.joeykot.dictate.model.AudioConfig
import com.joeykot.dictate.model.AudioContainer
import com.joeykot.dictate.model.DisplayConfig
import com.joeykot.dictate.model.InteractionConfig
import com.joeykot.dictate.model.OverlayColorScheme
import com.joeykot.dictate.model.OverlayPalette
import com.joeykot.dictate.model.PostProcessingConfig
import com.joeykot.dictate.model.PostProcessingProvider
import com.joeykot.dictate.model.PromptConfig
import com.joeykot.dictate.model.ProviderConfig
import com.joeykot.dictate.model.RetryConfig
import com.joeykot.dictate.model.RuntimeSettings
import com.joeykot.dictate.model.SegmentedUploadConfig
import com.joeykot.dictate.network.AdditionalParameters
import com.joeykot.dictate.network.BaseUrl
import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.Locale

class SettingsRepository(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureApiKeyStore = SecureApiKeyStore(context, preferences)
    private val iconDirectory = File(context.filesDir, "icons")

    @Synchronized
    fun get(): AppSettings {
        val codec = enumValueOrDefault(
            preferences.getString(KEY_CODEC, null),
            AudioCodec.MP3,
        )
        val container = enumValueOrDefault(
            preferences.getString(KEY_CONTAINER, null),
            AudioConfig.defaultContainer(codec),
        )
        val storedSampleRate = preferences.getInt(KEY_SAMPLE_RATE, AudioConfig.DEFAULT_SAMPLE_RATE)
        val sampleRate = if (
            !preferences.getBoolean(KEY_SAMPLE_RATE_SELECTION_IS_CURRENT, false) &&
            preferences.contains(KEY_SAMPLE_RATE) &&
            storedSampleRate == LEGACY_DEFAULT_SAMPLE_RATE
        ) {
            // Older releases persisted their 16 kHz default. Treat it as the new automatic
            // default until the user explicitly saves an output-rate selection in this release.
            AudioConfig.AUTO_SAMPLE_RATE
        } else {
            storedSampleRate
        }
        return AppSettings(
            language = AppLocale.readLanguage(applicationContext),
            audio = AudioConfig(
                bitDepth = preferences.getInt(KEY_BIT_DEPTH, AudioConfig.DEFAULT_BIT_DEPTH),
                sampleRate = if (!preferences.contains(KEY_BITRATE_BPS)) legacyOutputRate(codec, sampleRate) else sampleRate,
                codec = codec,
                container = container,
                bitrateBps = preferences.getInt(
                    KEY_BITRATE_BPS,
                    preferences.getInt(KEY_BITRATE, AudioConfig.DEFAULT_BITRATE_KBPS) * 1000,
                ),
            ).normalized(),
            provider = ProviderConfig(
                baseUrl = preferences.getString(KEY_BASE_URL, "").orEmpty(),
                model = preferences.getString(KEY_MODEL, "").orEmpty(),
                additionalJson = preferences.getString(KEY_ADDITIONAL_JSON, "").orEmpty(),
            ),
            retry = RetryConfig(
                enabled = preferences.getBoolean(KEY_RETRY_ENABLED, false),
                maxRetries = preferences.getInt(KEY_MAX_RETRIES, 2),
                initialBackoffSeconds = java.lang.Double.longBitsToDouble(
                    preferences.getLong(
                        KEY_INITIAL_BACKOFF,
                        java.lang.Double.doubleToRawLongBits(0.5),
                    ),
                ),
            ),
            segmentedUpload = SegmentedUploadConfig(
                enabled = preferences.getBoolean(KEY_SEGMENTED_UPLOAD_ENABLED, false),
                maximumSegmentLengthSeconds = preferences.getInt(
                    KEY_SEGMENTED_UPLOAD_MAXIMUM_SEGMENT_LENGTH_SECONDS,
                    SegmentedUploadConfig.DEFAULT_MAXIMUM_SEGMENT_LENGTH_SECONDS,
                ).takeIf { it > 0 } ?: SegmentedUploadConfig.DEFAULT_MAXIMUM_SEGMENT_LENGTH_SECONDS,
                minimumPauseDurationMillis = preferences.getInt(
                    KEY_SEGMENTED_UPLOAD_MINIMUM_PAUSE_DURATION_MILLIS,
                    SegmentedUploadConfig.DEFAULT_MINIMUM_PAUSE_DURATION_MILLIS,
                ).takeIf { it > 0 } ?: SegmentedUploadConfig.DEFAULT_MINIMUM_PAUSE_DURATION_MILLIS,
                concurrency = preferences.getInt(
                    KEY_SEGMENTED_UPLOAD_CONCURRENCY,
                    SegmentedUploadConfig.DEFAULT_CONCURRENCY,
                ).takeIf { it in 1..SegmentedUploadConfig.MAX_CONCURRENCY }
                    ?: SegmentedUploadConfig.DEFAULT_CONCURRENCY,
            ),
            interaction = InteractionConfig(
                longPressMs = preferences.getLong(KEY_LONG_PRESS, 1_500L),
                doubleTapMs = preferences.getLong(KEY_DOUBLE_TAP, 500L),
                alwaysCopyToClipboard = preferences.getBoolean(KEY_ALWAYS_COPY_TO_CLIPBOARD, true),
            ),
            display = DisplayConfig(
                buttonScale = preferences.getFloat(
                    KEY_BUTTON_SCALE,
                    DisplayConfig.DEFAULT_BUTTON_SCALE,
                ),
                buttonOpacity = preferences.getFloat(
                    KEY_BUTTON_OPACITY,
                    DisplayConfig.DEFAULT_BUTTON_OPACITY,
                ),
                colorScheme = OverlayColorScheme.entries.find {
                    it.value == preferences.getString(KEY_COLOR_SCHEME, null)
                } ?: OverlayColorScheme.DEFAULT,
                customPalette = OverlayPalette(
                    recordingColor = preferences.getInt(
                        KEY_CUSTOM_RECORDING_COLOR,
                        OverlayPalette.DEFAULT_RECORDING_COLOR,
                    ),
                    pausedColor = preferences.getInt(
                        KEY_CUSTOM_PAUSED_COLOR,
                        OverlayPalette.DEFAULT_PAUSED_COLOR,
                    ),
                    processingColor = preferences.getInt(
                        KEY_CUSTOM_PROCESSING_COLOR,
                        OverlayPalette.DEFAULT_PROCESSING_COLOR,
                    ),
                ),
            ).normalized(),
            postProcessing = PostProcessingConfig(
                provider = enumValueOrDefault(
                    preferences.getString(KEY_POST_PROVIDER, null),
                    PostProcessingProvider.OPENAI_COMPATIBLE,
                ),
                baseUrl = preferences.getString(KEY_POST_BASE_URL, "").orEmpty(),
                model = preferences.getString(KEY_POST_MODEL, "").orEmpty(),
                prompts = preferences.getString(KEY_PROMPTS, null)?.let { json ->
                    runCatching { PostProcessingSettingsCodec.decodePrompts(JSONArray(json)) }
                        .getOrDefault(emptyList())
                } ?: emptyList(),
            ),
            advancedAudio = readAdvancedAudio(),
        )
    }

    @Synchronized
    fun runtime(): RuntimeSettings {
        val settings = get()
        return RuntimeSettings(
            settings,
            secureApiKeyStore.get(),
            secureApiKeyStore.getPostProcessing(),
            settings.postProcessing.prompts.filter { it.provider != null }.associate { it.id to secureApiKeyStore.getPrompt(it.id) },
            secureApiKeyStore.getAdvancedSecrets(),
        )
    }

    @Synchronized
    fun promptApiKey(id: String): String = secureApiKeyStore.getPrompt(id)

    @Synchronized
    fun advancedAudioSecret(id: String): String = secureApiKeyStore.getAdvancedSecret(id)

    @Synchronized
    fun advancedAudioSecrets(): Map<String, String> = secureApiKeyStore.getAdvancedSecrets()

    @SuppressLint("ApplySharedPref")
    @Synchronized
    fun save(
        settings: AppSettings,
        apiKey: String,
        postProcessingApiKey: String = secureApiKeyStore.getPostProcessing(),
        promptApiKeys: Map<String, String> = emptyMap(),
        advancedAudioSecrets: Map<String, String>? = null,
    ) {
        check(Looper.myLooper() != Looper.getMainLooper()) { AppStrings.get(R.string.settings_write_thread, "Settings cannot be written on the main thread") }
        val effectiveAdvancedSecrets = advancedAudioSecrets ?: secureApiKeyStore.getAdvancedSecrets()
        val errors = validate(settings, effectiveAdvancedSecrets)
        require(errors.isEmpty()) { errors.joinToString("；") }
        val previousIconNames = storedIconNames()

        val normalized = settings.copy(
            audio = settings.audio.normalized(),
            display = settings.display.normalized(),
        )
        val editor = preferences.edit()
            .putString(AppLocale.LANGUAGE_KEY, normalized.language.tag)
            .putInt(KEY_BIT_DEPTH, normalized.audio.bitDepth)
            .putInt(KEY_SAMPLE_RATE, normalized.audio.sampleRate)
            .putBoolean(KEY_SAMPLE_RATE_SELECTION_IS_CURRENT, true)
            .putString(KEY_CODEC, normalized.audio.codec.name)
            .putString(KEY_CONTAINER, normalized.audio.container.name)
            .putInt(KEY_BITRATE_BPS, normalized.audio.bitrateBps)
            .putString(KEY_BASE_URL, normalized.provider.baseUrl.trim())
            .putString(KEY_MODEL, normalized.provider.model.trim())
            .putString(KEY_ADDITIONAL_JSON, normalized.provider.additionalJson.trim())
            .putString(KEY_POST_PROVIDER, normalized.postProcessing.provider.name)
            .putString(KEY_POST_BASE_URL, normalized.postProcessing.baseUrl.trim())
            .putString(KEY_POST_MODEL, normalized.postProcessing.model.trim())
            .putString(KEY_PROMPTS, PostProcessingSettingsCodec.encodePrompts(normalized.postProcessing.prompts).toString())
            .putString(KEY_ADVANCED_AUDIO, AdvancedAudioSettingsCodec.encode(normalized.advancedAudio).toString())
            .putBoolean(KEY_RETRY_ENABLED, normalized.retry.enabled)
            .putInt(KEY_MAX_RETRIES, normalized.retry.maxRetries)
            .putLong(
                KEY_INITIAL_BACKOFF,
                java.lang.Double.doubleToRawLongBits(normalized.retry.initialBackoffSeconds),
            )
            .putBoolean(KEY_SEGMENTED_UPLOAD_ENABLED, normalized.segmentedUpload.enabled)
            .putInt(
                KEY_SEGMENTED_UPLOAD_MAXIMUM_SEGMENT_LENGTH_SECONDS,
                normalized.segmentedUpload.maximumSegmentLengthSeconds,
            )
            .putInt(
                KEY_SEGMENTED_UPLOAD_MINIMUM_PAUSE_DURATION_MILLIS,
                normalized.segmentedUpload.minimumPauseDurationMillis,
            )
            .putInt(KEY_SEGMENTED_UPLOAD_CONCURRENCY, normalized.segmentedUpload.concurrency)
            .putLong(KEY_LONG_PRESS, normalized.interaction.longPressMs)
            .putLong(KEY_DOUBLE_TAP, normalized.interaction.doubleTapMs)
            .putBoolean(KEY_ALWAYS_COPY_TO_CLIPBOARD, normalized.interaction.alwaysCopyToClipboard)
            .putFloat(KEY_BUTTON_SCALE, normalized.display.buttonScale)
            .putFloat(KEY_BUTTON_OPACITY, normalized.display.buttonOpacity)
            .putString(KEY_COLOR_SCHEME, normalized.display.colorScheme.value)
            .putInt(KEY_CUSTOM_RECORDING_COLOR, normalized.display.customPalette.recordingColor)
            .putInt(KEY_CUSTOM_PAUSED_COLOR, normalized.display.customPalette.pausedColor)
            .putInt(KEY_CUSTOM_PROCESSING_COLOR, normalized.display.customPalette.processingColor)
        secureApiKeyStore.stage(editor, apiKey.trim())
        secureApiKeyStore.stagePostProcessing(editor, postProcessingApiKey.trim())
        secureApiKeyStore.stagePrompts(editor, normalized.postProcessing.prompts.map { it.id }.toSet(), promptApiKeys)
        advancedAudioSecrets?.let { secureApiKeyStore.stageAdvancedSecrets(editor, it) }
        // Configuration and encrypted API keys become durable as one transaction.
        check(editor.commit()) { AppStrings.get(R.string.settings_write_failed, "Failed to save settings") }
        AppStrings.refresh(applicationContext)
        secureApiKeyStore.clearLegacyValue()
        removeReplacedIcons(previousIconNames, normalized.postProcessing.prompts)
    }

    /**
     * Saves an Advanced Audio API draft and its encrypted secrets without
     * requiring a complete ordinary provider configuration.
     */
    @SuppressLint("ApplySharedPref")
    @Synchronized
    fun saveAdvancedAudio(config: AdvancedAudioConfig, secrets: Map<String, String>) {
        check(Looper.myLooper() != Looper.getMainLooper()) { AppStrings.get(R.string.settings_write_thread, "Settings cannot be written on the main thread") }
        val errors = validateAdvancedAudio(config, secrets)
        require(errors.isEmpty()) { errors.joinToString("；") }
        val editor = preferences.edit()
            .putString(KEY_ADVANCED_AUDIO, AdvancedAudioSettingsCodec.encode(config).toString())
        secureApiKeyStore.stageAdvancedSecrets(editor, secrets)
        check(editor.commit()) { AppStrings.get(R.string.settings_write_failed, "Failed to save settings") }
    }

    /** Prompt editing is independent of unsaved or incomplete public provider configuration. */
    @SuppressLint("ApplySharedPref")
    @Synchronized
    fun savePrompts(prompts: List<PromptConfig>, apiKeyUpdates: Map<String, String> = emptyMap()) {
        check(Looper.myLooper() != Looper.getMainLooper()) { AppStrings.get(R.string.settings_write_thread, "Settings cannot be written on the main thread") }
        val errors = PostProcessingSettingsCodec.validatePrompts(prompts)
        require(errors.isEmpty()) { errors.joinToString("；") }
        val previousIconNames = storedIconNames()
        val editor = preferences.edit()
            .putString(KEY_PROMPTS, PostProcessingSettingsCodec.encodePrompts(prompts).toString())
        secureApiKeyStore.stagePrompts(editor, prompts.map { it.id }.toSet(), apiKeyUpdates)
        check(editor.commit()) { AppStrings.get(R.string.settings_prompt_save_failed, "Failed to save prompts") }
        removeReplacedIcons(previousIconNames, prompts)
    }

    private fun storedIconNames(): Set<String> = runCatching {
        PostProcessingSettingsCodec.decodePrompts(JSONArray(preferences.getString(KEY_PROMPTS, "[]")))
            .mapNotNull { it.customIcon }.filter(PromptIconAssets::isSafeFileName).toSet()
    }.getOrDefault(emptySet())

    /**
     * Advanced Audio is opt-in. A corrupt or future local draft must fall back
     * to the normal OpenAI-compatible route instead of preventing settings
     * from loading altogether.
     */
    private fun readAdvancedAudio(): AdvancedAudioConfig = runCatching {
        val config = preferences.getString(KEY_ADVANCED_AUDIO, null)
            ?.let { encoded -> AdvancedAudioSettingsCodec.decode(JSONObject(encoded)) }
            ?: AdvancedAudioConfig()
        require(RemoteAudioConfigValidator.validateStoredConfiguration(config.remoteAudio).isEmpty()) {
            "Advanced Audio remote storage URL contains user-info"
        }
        config
    }.getOrDefault(AdvancedAudioConfig())

    private fun removeReplacedIcons(previous: Set<String>, prompts: List<PromptConfig>) {
        val current = prompts.mapNotNull { it.customIcon }.toSet()
        (previous - current).forEach { name -> runCatching { File(iconDirectory, name).delete() } }
    }

    fun validate(
        settings: AppSettings,
        advancedAudioSecrets: Map<String, String> = secureApiKeyStore.getAdvancedSecrets(),
    ): List<String> = buildList {
        addAll(settings.audio.validate())
        addAll(settings.retry.validate())
        addAll(settings.segmentedUpload.validate())
        addAll(settings.interaction.validate())
        addAll(settings.display.validate())
        if (settings.provider.baseUrl.isNotBlank()) {
            try {
                BaseUrl.transcriptionEndpoint(settings.provider.baseUrl)
            } catch (error: IllegalArgumentException) {
                add(error.message ?: AppStrings.get(R.string.settings_base_url_invalid, "Invalid Base URL"))
            }
        }
        try {
            AdditionalParameters.parseObject(settings.provider.additionalJson)
        } catch (error: IllegalArgumentException) {
            add(error.message ?: AppStrings.get(R.string.settings_additional_invalid, "Invalid additional parameters"))
        }
        if (settings.postProcessing.baseUrl.isNotBlank()) {
            val uri = runCatching { URI(settings.postProcessing.baseUrl.trim()) }.getOrNull()
            if (uri == null || uri.scheme !in listOf("https", "http") || uri.host.isNullOrBlank() ||
                uri.rawUserInfo != null || uri.rawFragment != null
            ) {
                add(AppStrings.get(R.string.settings_post_base_url, "The post-processing Base URL must be a valid HTTP or HTTPS address"))
            }
        }
        addAll(PostProcessingSettingsCodec.validatePrompts(settings.postProcessing.prompts))
        addAll(validateAdvancedAudio(settings.advancedAudio, advancedAudioSecrets))
    }

    /**
     * Enabled Advanced Audio configurations must be runnable before they can
     * become durable.  Disabled drafts intentionally retain the old relaxed
     * policy, except for URL user-info which would otherwise leak credentials
     * into plain preferences and export files.
     */
    private fun validateAdvancedAudio(
        config: AdvancedAudioConfig,
        secrets: Map<String, String>,
    ): List<String> = buildList {
        addAll(RemoteAudioConfigValidator.validateStoredConfiguration(config.remoteAudio).map { it.toString() })
        if (!config.enabled) return@buildList

        val document = config.workflowJson?.trim().takeIf { !it.isNullOrEmpty() }
        if (document == null) {
            add("ADVANCED_AUDIO_API.workflow: is required when Advanced Audio API is enabled")
            return@buildList
        }
        val workflow = try {
            AdvancedAudioWorkflowCodec.parse(document)
        } catch (error: IllegalArgumentException) {
            add("ADVANCED_AUDIO_API.workflow: ${error.message ?: "is invalid"}")
            return@buildList
        }
        val remoteCredentialIds = RemoteAudioCredentialIds.forConfig(config.remoteAudio)
        val workflowSecrets = secrets.filterKeys { id ->
            id !in remoteCredentialIds && workflow.secrets.any { it.id == id }
        }
        addAll(
            WorkflowValidator.validateExecutionInputs(workflow, config.values, workflowSecrets)
                .map { it.toString() },
        )
        addAll(
            RemoteAudioConfigValidator.validate(
                config = config.remoteAudio,
                delivery = workflow.audio.delivery,
                secrets = secrets,
            ).map { it.toString() },
        )
    }.distinct()

    fun exportJson(): String {
        val settings = get()
        val root = JSONObject()
        root.put("schemaVersion", 8)
        root.put("language", settings.language.tag)
        root.put(
            "audioOutput",
            JSONObject()
                .put("channels", 1)
                .put("bitDepth", settings.audio.bitDepth)
                .put("sampleRate", settings.audio.sampleRate)
                .put("codec", settings.audio.codec.value)
                .put("container", settings.audio.container.value)
                .put("bitrateBps", settings.audio.bitrateBps),
        )
        val additional = if (settings.provider.additionalJson.isBlank()) {
            JSONObject()
        } else {
            JSONObject(settings.provider.additionalJson)
        }
        root.put(
            "openAICompatible",
            JSONObject()
                .put("baseUrl", settings.provider.baseUrl)
                .put("model", settings.provider.model)
                .put("additionalParameters", additional),
        )
        // Advanced Audio API secrets and remote-storage credentials are held only in Keystore.
        root.put("advancedAudio", AdvancedAudioSettingsCodec.encode(settings.advancedAudio))
        root.put(
            "retry",
            JSONObject()
                .put("enabled", settings.retry.enabled)
                .put("maxRetries", settings.retry.maxRetries)
                .put("initialBackoffSeconds", settings.retry.initialBackoffSeconds),
        )
        root.put(
            "segmentedUpload",
            JSONObject()
                .put("enabled", settings.segmentedUpload.enabled)
                .put("maximumSegmentLengthSeconds", settings.segmentedUpload.maximumSegmentLengthSeconds)
                .put("minimumPauseDurationMillis", settings.segmentedUpload.minimumPauseDurationMillis)
                .put("concurrency", settings.segmentedUpload.concurrency),
        )
        root.put("postProcessing", PostProcessingSettingsCodec.encode(settings.postProcessing))
        root.put("promptIconAssets", PromptIconAssets.encode(
            iconDirectory,
            settings.postProcessing.prompts.mapNotNull { it.customIcon }.toSet(),
        ))
        root.put(
            "interaction",
            JSONObject()
                .put("longPressMs", settings.interaction.longPressMs)
                .put("doubleTapMs", settings.interaction.doubleTapMs)
                .put("alwaysCopyToClipboard", settings.interaction.alwaysCopyToClipboard),
        )
        root.put(
            "display",
            JSONObject()
                .put("buttonScale", settings.display.buttonScale.toDouble())
                .put("buttonOpacity", settings.display.buttonOpacity.toDouble())
                .put("colorScheme", settings.display.colorScheme.value)
                .put(
                    "customColors",
                    JSONObject()
                        .put("recording", colorToHex(settings.display.customPalette.recordingColor))
                        .put("paused", colorToHex(settings.display.customPalette.pausedColor))
                        .put("processing", colorToHex(settings.display.customPalette.processingColor)),
                ),
        )
        return root.toString(2)
    }

    fun previewImport(json: String): ImportPreview {
        val root = try {
            JSONObject(json)
        } catch (_: JSONException) {
            throw IllegalArgumentException(AppStrings.get(R.string.settings_import_json, "The imported file is not a valid JSON object"))
        }
        val schemaVersion = requiredInt(root, "schemaVersion", "schemaVersion")
        if (schemaVersion !in SUPPORTED_SCHEMA_VERSIONS) {
            throw IllegalArgumentException(AppStrings.get(R.string.settings_schema_version, "Unsupported configuration schemaVersion"))
        }

        val audioObject = requiredObject(root, "audioOutput", "audioOutput")
        val providerObject = requiredObject(root, "openAICompatible", "openAICompatible")
        val retryObject = requiredObject(root, "retry", "retry")
        val segmentedUploadObject = if (schemaVersion >= 8) {
            requiredObject(root, "segmentedUpload", "segmentedUpload")
        } else {
            null
        }
        val interactionObject = requiredObject(root, "interaction", "interaction")
        val display = if (schemaVersion >= 2) {
            parseDisplay(requiredObject(root, "display", "display"))
        } else {
            DisplayConfig()
        }

        if (requiredInt(audioObject, "channels", "audioOutput.channels") != 1) {
            throw IllegalArgumentException(AppStrings.get(R.string.settings_channels, "audioOutput.channels must be 1"))
        }

        val codecName = requiredString(audioObject, "codec", "audioOutput.codec")
        val codec = AudioCodec.entries.find { it.value == codecName }
            ?: throw IllegalArgumentException(AppStrings.get(R.string.settings_codec_invalid, "Invalid audioOutput.codec"))
        val containerName = requiredString(audioObject, "container", "audioOutput.container")
        val container = AudioContainer.entries.find { it.value == containerName }
            ?: throw IllegalArgumentException(AppStrings.get(R.string.settings_container_invalid, "Invalid audioOutput.container"))

        val additionalObject = requiredObject(
            providerObject,
            "additionalParameters",
            "openAICompatible.additionalParameters",
        )
        val additionalJson = if (additionalObject.length() == 0) "" else additionalObject.toString()

        val importedApiKey = if (providerObject.has("apiKey")) {
            requiredString(providerObject, "apiKey", "openAICompatible.apiKey")
        } else {
            null
        }
        val postProcessingObject = if (schemaVersion >= 4) {
            requiredObject(root, "postProcessing", "postProcessing")
        } else {
            null
        }
        val postProcessing = postProcessingObject?.let(PostProcessingSettingsCodec::decode)
            ?: PostProcessingConfig()
        val importedPostProcessingApiKey = if (postProcessingObject?.has("apiKey") == true) {
            requiredString(postProcessingObject, "apiKey", "postProcessing.apiKey")
        } else {
            null
        }
        val importedPromptKeys = buildMap {
            val prompts = postProcessingObject?.getJSONArray("prompts")
            postProcessing.prompts.forEachIndexed { index, prompt ->
                val item = prompts!!.getJSONObject(index)
                if (item.has("apiKey")) put(prompt.id, requiredString(item, "apiKey", "postProcessing.prompts[$index].apiKey"))
            }
        }
        val advancedAudioObject = if (schemaVersion >= 7) {
            requiredObject(root, "advancedAudio", "advancedAudio")
        } else {
            null
        }
        val advancedAudio = advancedAudioObject?.let { AdvancedAudioSettingsCodec.decode(it) }
            ?: AdvancedAudioConfig()
        val hasAdvancedAudioSecrets = advancedAudioObject?.has("secrets") == true
        val importedAdvancedAudioSecrets = if (hasAdvancedAudioSecrets) {
            AdvancedAudioSettingsCodec.decodeStringMap(
                requiredObject(advancedAudioObject!!, "secrets", "advancedAudio.secrets"),
                "advancedAudio.secrets",
            )
        } else {
            emptyMap()
        }
        val iconAssetsObject = if (schemaVersion >= 4 && root.has("promptIconAssets")) {
            requiredObject(root, "promptIconAssets", "promptIconAssets")
        } else {
            null
        }
        val iconAssets = PromptIconAssets.decode(
            iconAssetsObject,
            postProcessing.prompts.mapNotNull { it.customIcon }.toSet(),
        )

        val imported = AppSettings(
            language = if (root.has("language")) {
                AppLanguage.fromTag(requiredString(root, "language", "language"))
                    ?: throw IllegalArgumentException(AppStrings.get(R.string.settings_language_unsupported, "Unsupported interface language"))
            } else {
                AppLanguage.ENGLISH
            },
            audio = AudioConfig(
                bitDepth = requiredInt(audioObject, "bitDepth", "audioOutput.bitDepth"),
                sampleRate = requiredInt(audioObject, "sampleRate", "audioOutput.sampleRate").let {
                    if (schemaVersion < 5) legacyOutputRate(codec, it) else it
                },
                codec = codec,
                container = container,
                bitrateBps = if (schemaVersion >= 5) {
                    requiredInt(audioObject, "bitrateBps", "audioOutput.bitrateBps")
                } else {
                    requiredInt(audioObject, "bitrateKbps", "audioOutput.bitrateKbps").let {
                        require(it in 1..(Int.MAX_VALUE / 1000)) {
                            AppStrings.get(R.string.settings_codec_bitrate, "The %1\$s codec does not support this bitrate at the selected sample rate", codec.value)
                        }
                        it * 1000
                    }
                },
            ),
            provider = ProviderConfig(
                baseUrl = requiredString(providerObject, "baseUrl", "openAICompatible.baseUrl"),
                model = requiredString(providerObject, "model", "openAICompatible.model"),
                additionalJson = additionalJson,
            ),
            retry = RetryConfig(
                enabled = requiredBoolean(retryObject, "enabled", "retry.enabled"),
                maxRetries = requiredInt(retryObject, "maxRetries", "retry.maxRetries"),
                initialBackoffSeconds = requiredDouble(
                    retryObject,
                    "initialBackoffSeconds",
                    "retry.initialBackoffSeconds",
                ),
            ),
            segmentedUpload = segmentedUploadObject?.let { segmentedUpload ->
                SegmentedUploadConfig(
                    enabled = requiredBoolean(segmentedUpload, "enabled", "segmentedUpload.enabled"),
                    maximumSegmentLengthSeconds = requiredInt(
                        segmentedUpload,
                        "maximumSegmentLengthSeconds",
                        "segmentedUpload.maximumSegmentLengthSeconds",
                    ),
                    minimumPauseDurationMillis = requiredInt(
                        segmentedUpload,
                        "minimumPauseDurationMillis",
                        "segmentedUpload.minimumPauseDurationMillis",
                    ),
                    concurrency = requiredInt(
                        segmentedUpload,
                        "concurrency",
                        "segmentedUpload.concurrency",
                    ),
                )
            } ?: SegmentedUploadConfig(),
            interaction = InteractionConfig(
                longPressMs = requiredLong(interactionObject, "longPressMs", "interaction.longPressMs"),
                doubleTapMs = requiredLong(interactionObject, "doubleTapMs", "interaction.doubleTapMs"),
                alwaysCopyToClipboard = optionalBoolean(
                    interactionObject,
                    "alwaysCopyToClipboard",
                    "interaction.alwaysCopyToClipboard",
                    default = true,
                ),
            ),
            display = display,
            postProcessing = postProcessing,
            advancedAudio = advancedAudio,
        )

        val advancedSecretsForValidation = when {
            hasAdvancedAudioSecrets -> importedAdvancedAudioSecrets
            get().advancedAudio == advancedAudio -> secureApiKeyStore.getAdvancedSecrets()
            else -> emptyMap()
        }
        val errors = validate(imported, advancedSecretsForValidation)
        if (errors.isNotEmpty()) throw IllegalArgumentException(errors.joinToString("；"))

        return ImportPreview(
            settings = imported,
            apiKey = importedApiKey,
            postProcessingApiKey = importedPostProcessingApiKey,
            iconAssets = iconAssets,
            promptApiKeys = importedPromptKeys,
            advancedAudioSecrets = importedAdvancedAudioSecrets,
            hasAdvancedAudioSecrets = hasAdvancedAudioSecrets,
        )
    }

    @Synchronized
    fun applyImport(preview: ImportPreview, allowApiKey: Boolean) {
        check(Looper.myLooper() != Looper.getMainLooper()) { AppStrings.get(R.string.settings_write_thread, "Settings cannot be written on the main thread") }
        if (preview.hasApiKeys && !allowApiKey) {
            throw IllegalArgumentException(AppStrings.get(R.string.settings_import_api_key, "The imported file contains API keys and requires explicit confirmation"))
        }
        val currentSettings = get()
        val advancedSecrets = when {
            preview.hasAdvancedAudioSecrets -> preview.advancedAudioSecrets
            currentSettings.advancedAudio == preview.settings.advancedAudio -> secureApiKeyStore.getAdvancedSecrets()
            else -> emptyMap()
        }
        val errors = validate(preview.settings, advancedSecrets)
        require(errors.isEmpty()) { errors.joinToString("；") }
        val currentPrompts = currentSettings.postProcessing.prompts.associateBy { it.id }
        val promptKeys = preview.settings.postProcessing.prompts.associate { prompt ->
            val previous = currentPrompts[prompt.id]
            val key = preview.promptApiKeys[prompt.id] ?: if (
                previous != null && previous.provider == prompt.provider && previous.baseUrl.trim() == prompt.baseUrl.trim()
            ) secureApiKeyStore.getPrompt(prompt.id) else ""
            prompt.id to key
        }
        val installedIcons = PromptIconAssets.write(iconDirectory, preview.iconAssets)
        val importedSettings = preview.settings.copy(postProcessing = preview.settings.postProcessing.copy(
            prompts = preview.settings.postProcessing.prompts.map { prompt ->
                // Do not bind an imported prompt to an unrelated existing file with the same name.
                prompt.copy(customIcon = prompt.customIcon?.let(installedIcons::get))
            },
        ))
        try {
            save(
                importedSettings,
                preview.apiKey ?: secureApiKeyStore.get(),
                preview.postProcessingApiKey ?: secureApiKeyStore.getPostProcessing(),
                promptKeys,
                advancedSecrets,
            )
        } catch (error: Exception) {
            installedIcons.values.forEach { File(iconDirectory, it).delete() }
            throw error
        }
    }

    @Synchronized
    fun getOverlayPosition(): OverlayPosition? {
        if (!preferences.contains(KEY_OVERLAY_X) || !preferences.contains(KEY_OVERLAY_Y)) return null
        return OverlayPosition(
            x = preferences.getInt(KEY_OVERLAY_X, 0),
            y = preferences.getInt(KEY_OVERLAY_Y, 0),
        )
    }

    @Synchronized
    fun setOverlayPosition(x: Int, y: Int) {
        preferences.edit()
            .putInt(KEY_OVERLAY_X, x)
            .putInt(KEY_OVERLAY_Y, y)
            .apply()
    }

    private fun requiredObject(parent: JSONObject, key: String, path: String): JSONObject =
        requiredValue(parent, key, path) as? JSONObject
            ?: throw IllegalArgumentException(AppStrings.get(R.string.settings_field_object, "%1\$s must be a JSON object", path))

    private fun requiredString(parent: JSONObject, key: String, path: String): String =
        requiredValue(parent, key, path) as? String
            ?: throw IllegalArgumentException(AppStrings.get(R.string.settings_field_string, "%1\$s must be a string", path))

    private fun requiredBoolean(parent: JSONObject, key: String, path: String): Boolean =
        requiredValue(parent, key, path) as? Boolean
            ?: throw IllegalArgumentException(AppStrings.get(R.string.settings_field_boolean, "%1\$s must be a boolean", path))

    private fun optionalBoolean(
        parent: JSONObject,
        key: String,
        path: String,
        default: Boolean,
    ): Boolean = if (parent.has(key)) requiredBoolean(parent, key, path) else default

    private fun requiredInt(parent: JSONObject, key: String, path: String): Int {
        val number = requiredNumber(parent, key, path)
        val value = number.toDouble()
        if (!value.isFinite() || value % 1.0 != 0.0 || value !in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
            throw IllegalArgumentException(AppStrings.get(R.string.settings_field_integer, "%1\$s must be an integer", path))
        }
        return value.toInt()
    }

    private fun requiredLong(parent: JSONObject, key: String, path: String): Long {
        val number = requiredNumber(parent, key, path)
        val value = number.toDouble()
        if (!value.isFinite() || value % 1.0 != 0.0 ||
            value < Long.MIN_VALUE.toDouble() || value > Long.MAX_VALUE.toDouble()
        ) {
            throw IllegalArgumentException(AppStrings.get(R.string.settings_field_integer, "%1\$s must be an integer", path))
        }
        return value.toLong()
    }

    private fun requiredDouble(parent: JSONObject, key: String, path: String): Double {
        val value = requiredNumber(parent, key, path).toDouble()
        if (!value.isFinite()) throw IllegalArgumentException(AppStrings.get(R.string.settings_field_finite, "%1\$s must be a finite number", path))
        return value
    }

    private fun requiredNumber(parent: JSONObject, key: String, path: String): Number =
        requiredValue(parent, key, path) as? Number
            ?: throw IllegalArgumentException(AppStrings.get(R.string.settings_field_number, "%1\$s must be a number", path))

    private fun parseDisplay(displayObject: JSONObject): DisplayConfig {
        val colorSchemeValue = requiredString(displayObject, "colorScheme", "display.colorScheme")
        val colorScheme = OverlayColorScheme.entries.find { it.value == colorSchemeValue }
            ?: throw IllegalArgumentException(AppStrings.get(R.string.settings_color_scheme_invalid, "Invalid display.colorScheme"))
        val customColors = requiredObject(displayObject, "customColors", "display.customColors")
        return DisplayConfig(
            buttonScale = requiredDouble(displayObject, "buttonScale", "display.buttonScale").toFloat(),
            buttonOpacity = requiredDouble(
                displayObject,
                "buttonOpacity",
                "display.buttonOpacity",
            ).toFloat(),
            colorScheme = colorScheme,
            customPalette = OverlayPalette(
                recordingColor = requiredRgbColor(customColors, "recording", "display.customColors.recording"),
                pausedColor = requiredRgbColor(customColors, "paused", "display.customColors.paused"),
                processingColor = requiredRgbColor(
                    customColors,
                    "processing",
                    "display.customColors.processing",
                ),
            ),
        )
    }

    private fun requiredRgbColor(parent: JSONObject, key: String, path: String): Int {
        val value = requiredString(parent, key, path)
        if (!RGB_HEX.matches(value)) throw IllegalArgumentException(AppStrings.get(R.string.settings_field_rgb, "%1\$s must use #RRGGBB", path))
        return value.substring(1).toInt(16)
    }

    private fun colorToHex(color: Int): String = String.format(Locale.ROOT, "#%06X", color)

    private fun requiredValue(parent: JSONObject, key: String, path: String): Any {
        if (!parent.has(key)) throw IllegalArgumentException(AppStrings.get(R.string.settings_field_missing, "Missing field %1\$s", path))
        val value = parent.get(key)
        if (value == JSONObject.NULL) throw IllegalArgumentException(AppStrings.get(R.string.settings_field_null, "%1\$s cannot be null", path))
        return value
    }

    // Old Opus settings offered 32/44.1 kHz but always encoded those selections at 48 kHz.
    private fun legacyOutputRate(codec: AudioCodec, rate: Int): Int =
        if (codec == AudioCodec.OPUS && rate in listOf(32_000, 44_100)) 48_000 else rate

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    data class ImportPreview(
        val settings: AppSettings,
        val apiKey: String?,
        val postProcessingApiKey: String? = null,
        val iconAssets: Map<String, ByteArray> = emptyMap(),
        val promptApiKeys: Map<String, String> = emptyMap(),
        val advancedAudioSecrets: Map<String, String> = emptyMap(),
        val hasAdvancedAudioSecrets: Boolean = false,
    ) {
        val hasApiKeys: Boolean get() = apiKey != null || postProcessingApiKey != null ||
            promptApiKeys.isNotEmpty() || hasAdvancedAudioSecrets

        override fun toString(): String =
            "ImportPreview(settings=$settings, apiKey=<redacted>, postProcessingApiKey=<redacted>, " +
                "iconAssets=${iconAssets.keys}, promptApiKeys=<redacted>, advancedAudioSecrets=<redacted>, " +
                "hasAdvancedAudioSecrets=$hasAdvancedAudioSecrets)"
    }

    data class OverlayPosition(
        val x: Int,
        val y: Int,
    )

    private companion object {
        const val PREFS_NAME = "settings"
        const val KEY_BIT_DEPTH = "audio.bit_depth"
        const val KEY_SAMPLE_RATE = "audio.sample_rate"
        const val KEY_SAMPLE_RATE_SELECTION_IS_CURRENT = "audio.sample_rate_selection_is_current"
        const val KEY_CODEC = "audio.codec"
        const val KEY_CONTAINER = "audio.container"
        const val KEY_BITRATE = "audio.bitrate"
        const val KEY_BITRATE_BPS = "audio.bitrateBps"
        const val KEY_BASE_URL = "provider.base_url"
        const val KEY_MODEL = "provider.model"
        const val KEY_ADDITIONAL_JSON = "provider.additional_json"
        const val KEY_POST_PROVIDER = "post_processing.provider"
        const val KEY_POST_BASE_URL = "post_processing.base_url"
        const val KEY_POST_MODEL = "post_processing.model"
        const val KEY_PROMPTS = "post_processing.prompts"
        const val KEY_ADVANCED_AUDIO = "advanced_audio.config"
        const val KEY_RETRY_ENABLED = "retry.enabled"
        const val KEY_MAX_RETRIES = "retry.max_retries"
        const val KEY_INITIAL_BACKOFF = "retry.initial_backoff"
        const val KEY_SEGMENTED_UPLOAD_ENABLED = "segmented_upload.enabled"
        const val KEY_SEGMENTED_UPLOAD_MAXIMUM_SEGMENT_LENGTH_SECONDS = "segmented_upload.maximum_segment_length_seconds"
        const val KEY_SEGMENTED_UPLOAD_MINIMUM_PAUSE_DURATION_MILLIS = "segmented_upload.minimum_pause_duration_millis"
        const val KEY_SEGMENTED_UPLOAD_CONCURRENCY = "segmented_upload.concurrency"
        const val KEY_LONG_PRESS = "interaction.long_press"
        const val KEY_DOUBLE_TAP = "interaction.double_tap"
        const val KEY_ALWAYS_COPY_TO_CLIPBOARD = "interaction.always_copy_to_clipboard"
        const val KEY_BUTTON_SCALE = "display.button_scale"
        const val KEY_BUTTON_OPACITY = "display.button_opacity"
        const val KEY_COLOR_SCHEME = "display.color_scheme"
        const val KEY_CUSTOM_RECORDING_COLOR = "display.custom_recording_color"
        const val KEY_CUSTOM_PAUSED_COLOR = "display.custom_paused_color"
        const val KEY_CUSTOM_PROCESSING_COLOR = "display.custom_processing_color"
        const val KEY_OVERLAY_X = "overlay.x"
        const val KEY_OVERLAY_Y = "overlay.y"
        const val LEGACY_DEFAULT_SAMPLE_RATE = 16_000
        val SUPPORTED_SCHEMA_VERSIONS = setOf(1, 2, 3, 4, 5, 6, 7, 8)
        val RGB_HEX = Regex("#[0-9A-Fa-f]{6}")
    }
}
