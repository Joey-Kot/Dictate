package com.joeykot.dictate.settings

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.joeykot.dictate.model.AppSettings
import com.joeykot.dictate.model.AudioCodec
import com.joeykot.dictate.model.AudioConfig
import com.joeykot.dictate.model.AudioContainer
import com.joeykot.dictate.model.InteractionConfig
import com.joeykot.dictate.model.ProviderConfig
import com.joeykot.dictate.model.RetryConfig
import com.joeykot.dictate.model.RuntimeSettings
import com.joeykot.dictate.network.AdditionalParameters
import com.joeykot.dictate.network.BaseUrl
import org.json.JSONException
import org.json.JSONObject

class SettingsRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureApiKeyStore = SecureApiKeyStore(context, preferences)

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
        return AppSettings(
            audio = AudioConfig(
                bitDepth = preferences.getInt(KEY_BIT_DEPTH, AudioConfig.DEFAULT_BIT_DEPTH),
                sampleRate = preferences.getInt(KEY_SAMPLE_RATE, AudioConfig.DEFAULT_SAMPLE_RATE),
                codec = codec,
                container = container,
                bitrateKbps = preferences.getInt(KEY_BITRATE, AudioConfig.DEFAULT_BITRATE_KBPS),
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
            interaction = InteractionConfig(
                longPressMs = preferences.getLong(KEY_LONG_PRESS, 1_500L),
                doubleTapMs = preferences.getLong(KEY_DOUBLE_TAP, 500L),
                alwaysCopyToClipboard = preferences.getBoolean(KEY_ALWAYS_COPY_TO_CLIPBOARD, true),
            ),
        )
    }

    @Synchronized
    fun runtime(): RuntimeSettings = RuntimeSettings(get(), secureApiKeyStore.get())

    @SuppressLint("ApplySharedPref")
    @Synchronized
    fun save(settings: AppSettings, apiKey: String) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "设置写入不能在主线程执行" }
        val errors = validate(settings)
        require(errors.isEmpty()) { errors.joinToString("；") }

        val normalized = settings.copy(audio = settings.audio.normalized())
        val editor = preferences.edit()
            .putInt(KEY_BIT_DEPTH, normalized.audio.bitDepth)
            .putInt(KEY_SAMPLE_RATE, normalized.audio.sampleRate)
            .putString(KEY_CODEC, normalized.audio.codec.name)
            .putString(KEY_CONTAINER, normalized.audio.container.name)
            .putInt(KEY_BITRATE, normalized.audio.bitrateKbps)
            .putString(KEY_BASE_URL, normalized.provider.baseUrl.trim())
            .putString(KEY_MODEL, normalized.provider.model.trim())
            .putString(KEY_ADDITIONAL_JSON, normalized.provider.additionalJson.trim())
            .putBoolean(KEY_RETRY_ENABLED, normalized.retry.enabled)
            .putInt(KEY_MAX_RETRIES, normalized.retry.maxRetries)
            .putLong(
                KEY_INITIAL_BACKOFF,
                java.lang.Double.doubleToRawLongBits(normalized.retry.initialBackoffSeconds),
            )
            .putLong(KEY_LONG_PRESS, normalized.interaction.longPressMs)
            .putLong(KEY_DOUBLE_TAP, normalized.interaction.doubleTapMs)
            .putBoolean(KEY_ALWAYS_COPY_TO_CLIPBOARD, normalized.interaction.alwaysCopyToClipboard)
        secureApiKeyStore.stage(editor, apiKey.trim())
        // The settings and encrypted API Key must become durable as one transaction.
        check(editor.commit()) { "设置写入失败" }
        secureApiKeyStore.clearLegacyValue()
    }

    fun validate(settings: AppSettings): List<String> = buildList {
        addAll(settings.audio.validate())
        addAll(settings.retry.validate())
        addAll(settings.interaction.validate())
        if (settings.provider.baseUrl.isNotBlank()) {
            try {
                BaseUrl.transcriptionEndpoint(settings.provider.baseUrl)
            } catch (error: IllegalArgumentException) {
                add(error.message ?: "Base URL 无效")
            }
        }
        try {
            AdditionalParameters.parse(settings.provider.additionalJson)
        } catch (error: IllegalArgumentException) {
            add(error.message ?: "附加参数无效")
        }
    }

    fun exportJson(): String {
        val settings = get()
        val root = JSONObject()
        root.put("schemaVersion", 1)
        root.put(
            "audioOutput",
            JSONObject()
                .put("channels", 1)
                .put("bitDepth", settings.audio.bitDepth)
                .put("sampleRate", settings.audio.sampleRate)
                .put("codec", settings.audio.codec.value)
                .put("container", settings.audio.container.value)
                .put("bitrateKbps", settings.audio.bitrateKbps),
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
        root.put(
            "retry",
            JSONObject()
                .put("enabled", settings.retry.enabled)
                .put("maxRetries", settings.retry.maxRetries)
                .put("initialBackoffSeconds", settings.retry.initialBackoffSeconds),
        )
        root.put(
            "interaction",
            JSONObject()
                .put("longPressMs", settings.interaction.longPressMs)
                .put("doubleTapMs", settings.interaction.doubleTapMs)
                .put("alwaysCopyToClipboard", settings.interaction.alwaysCopyToClipboard),
        )
        return root.toString(2)
    }

    fun previewImport(json: String): ImportPreview {
        val root = try {
            JSONObject(json)
        } catch (_: JSONException) {
            throw IllegalArgumentException("导入文件不是有效的 JSON 对象")
        }
        if (requiredInt(root, "schemaVersion", "schemaVersion") != 1) {
            throw IllegalArgumentException("不支持的配置 schemaVersion")
        }

        val audioObject = requiredObject(root, "audioOutput", "audioOutput")
        val providerObject = requiredObject(root, "openAICompatible", "openAICompatible")
        val retryObject = requiredObject(root, "retry", "retry")
        val interactionObject = requiredObject(root, "interaction", "interaction")

        if (requiredInt(audioObject, "channels", "audioOutput.channels") != 1) {
            throw IllegalArgumentException("audioOutput.channels 只能为 1")
        }

        val codecName = requiredString(audioObject, "codec", "audioOutput.codec")
        val codec = AudioCodec.entries.find { it.value == codecName }
            ?: throw IllegalArgumentException("audioOutput.codec 无效")
        val containerName = requiredString(audioObject, "container", "audioOutput.container")
        val container = AudioContainer.entries.find { it.value == containerName }
            ?: throw IllegalArgumentException("audioOutput.container 无效")

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

        val imported = AppSettings(
            audio = AudioConfig(
                bitDepth = requiredInt(audioObject, "bitDepth", "audioOutput.bitDepth"),
                sampleRate = requiredInt(audioObject, "sampleRate", "audioOutput.sampleRate"),
                codec = codec,
                container = container,
                bitrateKbps = requiredInt(audioObject, "bitrateKbps", "audioOutput.bitrateKbps"),
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
        )

        val errors = validate(imported)
        if (errors.isNotEmpty()) throw IllegalArgumentException(errors.joinToString("；"))

        return ImportPreview(imported, importedApiKey)
    }

    fun applyImport(preview: ImportPreview, allowApiKey: Boolean) {
        if (preview.apiKey != null && !allowApiKey) {
            throw IllegalArgumentException("导入文件包含 API Key，需要明确确认")
        }
        save(preview.settings, preview.apiKey ?: secureApiKeyStore.get())
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
            ?: throw IllegalArgumentException("$path 必须是 JSON 对象")

    private fun requiredString(parent: JSONObject, key: String, path: String): String =
        requiredValue(parent, key, path) as? String
            ?: throw IllegalArgumentException("$path 必须是字符串")

    private fun requiredBoolean(parent: JSONObject, key: String, path: String): Boolean =
        requiredValue(parent, key, path) as? Boolean
            ?: throw IllegalArgumentException("$path 必须是布尔值")

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
            throw IllegalArgumentException("$path 必须是整数")
        }
        return value.toInt()
    }

    private fun requiredLong(parent: JSONObject, key: String, path: String): Long {
        val number = requiredNumber(parent, key, path)
        val value = number.toDouble()
        if (!value.isFinite() || value % 1.0 != 0.0 ||
            value < Long.MIN_VALUE.toDouble() || value > Long.MAX_VALUE.toDouble()
        ) {
            throw IllegalArgumentException("$path 必须是整数")
        }
        return value.toLong()
    }

    private fun requiredDouble(parent: JSONObject, key: String, path: String): Double {
        val value = requiredNumber(parent, key, path).toDouble()
        if (!value.isFinite()) throw IllegalArgumentException("$path 必须是有限数字")
        return value
    }

    private fun requiredNumber(parent: JSONObject, key: String, path: String): Number =
        requiredValue(parent, key, path) as? Number
            ?: throw IllegalArgumentException("$path 必须是数字")

    private fun requiredValue(parent: JSONObject, key: String, path: String): Any {
        if (!parent.has(key)) throw IllegalArgumentException("缺少字段 $path")
        val value = parent.get(key)
        if (value == JSONObject.NULL) throw IllegalArgumentException("$path 不能为 null")
        return value
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    data class ImportPreview(
        val settings: AppSettings,
        val apiKey: String?,
    )

    data class OverlayPosition(
        val x: Int,
        val y: Int,
    )

    private companion object {
        const val PREFS_NAME = "settings"
        const val KEY_BIT_DEPTH = "audio.bit_depth"
        const val KEY_SAMPLE_RATE = "audio.sample_rate"
        const val KEY_CODEC = "audio.codec"
        const val KEY_CONTAINER = "audio.container"
        const val KEY_BITRATE = "audio.bitrate"
        const val KEY_BASE_URL = "provider.base_url"
        const val KEY_MODEL = "provider.model"
        const val KEY_ADDITIONAL_JSON = "provider.additional_json"
        const val KEY_RETRY_ENABLED = "retry.enabled"
        const val KEY_MAX_RETRIES = "retry.max_retries"
        const val KEY_INITIAL_BACKOFF = "retry.initial_backoff"
        const val KEY_LONG_PRESS = "interaction.long_press"
        const val KEY_DOUBLE_TAP = "interaction.double_tap"
        const val KEY_ALWAYS_COPY_TO_CLIPBOARD = "interaction.always_copy_to_clipboard"
        const val KEY_OVERLAY_X = "overlay.x"
        const val KEY_OVERLAY_Y = "overlay.y"
    }
}
