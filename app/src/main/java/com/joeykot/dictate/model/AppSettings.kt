package com.joeykot.dictate.model

import kotlin.math.pow

enum class AudioCodec(val value: String) {
    OPUS("opus"),
    MP3("mp3"),
    AAC("aac"),
    PCM("pcm"),
}

enum class AudioContainer(val value: String, val extension: String, val mimeType: String) {
    OPUS("opus", "opus", "audio/opus"),
    OGG("ogg", "ogg", "audio/ogg"),
    MP3("mp3", "mp3", "audio/mpeg"),
    M4A("m4a", "m4a", "audio/mp4"),
    WAV("wav", "wav", "audio/wav"),
}

data class AudioConfig(
    val bitDepth: Int = DEFAULT_BIT_DEPTH,
    val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    val codec: AudioCodec = AudioCodec.MP3,
    val container: AudioContainer = AudioContainer.MP3,
    val bitrateKbps: Int = DEFAULT_BITRATE_KBPS,
) {
    fun normalized(): AudioConfig {
        val compatible = compatibleContainers(codec)
        val bitrates = compatibleBitrates(codec, sampleRate)
        return copy(
            container = container.takeIf { it in compatible } ?: defaultContainer(codec),
            bitrateKbps = if (codec == AudioCodec.PCM) {
                DEFAULT_BITRATE_KBPS
            } else {
                bitrateKbps.takeIf { it in bitrates } ?: defaultBitrate(codec, sampleRate)
            },
        )
    }

    fun validate(): List<String> = buildList {
        if (bitDepth !in BIT_DEPTHS) add("位深必须为 ${BIT_DEPTHS.joinToString()} 位之一")
        if (sampleRate !in SAMPLE_RATES) add("采样率不受支持")
        if (codec != AudioCodec.PCM && bitrateKbps !in compatibleBitrates(codec, sampleRate)) {
            add("${codec.value} 编码在当前采样率下不支持该码率")
        }
        if (container !in compatibleContainers(codec)) {
            add("${codec.value} 编码不能使用 ${container.value} 容器")
        }
    }

    companion object {
        const val INPUT_SAMPLE_RATE = 16_000
        const val DEFAULT_BIT_DEPTH = 16
        const val DEFAULT_SAMPLE_RATE = 16_000
        const val DEFAULT_BITRATE_KBPS = 128

        val BIT_DEPTHS = listOf(8, 16, 24, 32)
        val SAMPLE_RATES = listOf(8_000, 16_000, 24_000, 32_000, 44_100, 48_000)
        val BITRATES_KBPS = listOf(16, 32, 64, 128, 192, 256, 320)

        fun compatibleContainers(codec: AudioCodec): List<AudioContainer> = when (codec) {
            AudioCodec.OPUS -> listOf(AudioContainer.OPUS, AudioContainer.OGG)
            AudioCodec.MP3 -> listOf(AudioContainer.MP3)
            AudioCodec.AAC -> listOf(AudioContainer.M4A)
            AudioCodec.PCM -> listOf(AudioContainer.WAV)
        }

        fun compatibleBitrates(codec: AudioCodec, sampleRate: Int): List<Int> = when (codec) {
            AudioCodec.OPUS -> BITRATES_KBPS.filter { it <= 256 }
            AudioCodec.MP3 -> when (sampleRate) {
                8_000 -> BITRATES_KBPS.filter { it <= 64 }
                16_000, 24_000 -> BITRATES_KBPS.filter { it <= 128 }
                else -> BITRATES_KBPS.filter { it >= 32 }
            }
            AudioCodec.AAC -> BITRATES_KBPS.filter { it * 1_000 <= sampleRate * 6 }
            AudioCodec.PCM -> emptyList()
        }

        fun defaultBitrate(codec: AudioCodec, sampleRate: Int): Int {
            val available = compatibleBitrates(codec, sampleRate)
            return DEFAULT_BITRATE_KBPS.takeIf { it in available }
                ?: available.lastOrNull()
                ?: DEFAULT_BITRATE_KBPS
        }

        fun defaultContainer(codec: AudioCodec): AudioContainer = compatibleContainers(codec).first()
    }
}

data class ProviderConfig(
    val baseUrl: String = "",
    val model: String = "",
    val additionalJson: String = "",
)

data class RetryConfig(
    val enabled: Boolean = false,
    val maxRetries: Int = 2,
    val initialBackoffSeconds: Double = 0.5,
) {
    fun delayMillis(retryNumber: Int): Long {
        require(retryNumber >= 1)
        return (initialBackoffSeconds * 1_000.0 * 2.0.pow(retryNumber - 1)).toLong()
    }

    fun validate(): List<String> = buildList {
        if (maxRetries !in 0..10) add("最大重试次数必须在 0 到 10 之间")
        if (!initialBackoffSeconds.isFinite() || initialBackoffSeconds !in 0.1..60.0) {
            add("初始退避时间必须在 0.1 到 60 秒之间")
        }
    }
}

data class InteractionConfig(
    val longPressMs: Long = 1_500L,
    val doubleTapMs: Long = 500L,
    val alwaysCopyToClipboard: Boolean = true,
) {
    fun validate(): List<String> = buildList {
        if (longPressMs !in 300L..5_000L) add("长按阈值必须在 300 到 5000 ms 之间")
        if (doubleTapMs !in 150L..1_000L) add("双击间隔必须在 150 到 1000 ms 之间")
    }
}

enum class OverlayColorScheme(val value: String) {
    DEFAULT("default"),
    OCEAN("ocean"),
    SUNSET("sunset"),
    COLOR_BLIND("color_blind"),
    CUSTOM("custom"),
}

/**
 * An opaque RGB color represented as an integer in the range 0x000000..0xFFFFFF.
 * The overlay owns alpha separately so color schemes and button opacity cannot conflict.
 */
data class OverlayPalette(
    val recordingColor: Int = DEFAULT_RECORDING_COLOR,
    val pausedColor: Int = DEFAULT_PAUSED_COLOR,
    val processingColor: Int = DEFAULT_PROCESSING_COLOR,
) {
    fun normalized(): OverlayPalette = copy(
        recordingColor = recordingColor.takeIf { isValidRgb(it) } ?: DEFAULT_RECORDING_COLOR,
        pausedColor = pausedColor.takeIf { isValidRgb(it) } ?: DEFAULT_PAUSED_COLOR,
        processingColor = processingColor.takeIf { isValidRgb(it) } ?: DEFAULT_PROCESSING_COLOR,
    )

    fun validate(): List<String> = buildList {
        if (!isValidRgb(recordingColor)) add("录制颜色必须是 #RRGGBB")
        if (!isValidRgb(pausedColor)) add("暂停颜色必须是 #RRGGBB")
        if (!isValidRgb(processingColor)) add("处理颜色必须是 #RRGGBB")
    }

    companion object {
        const val DEFAULT_RECORDING_COLOR = 0xDC2626
        const val DEFAULT_PAUSED_COLOR = 0x16A34A
        const val DEFAULT_PROCESSING_COLOR = 0x2563EB

        val DEFAULT = OverlayPalette()
        val OCEAN = OverlayPalette(
            recordingColor = 0x0F766E,
            pausedColor = 0x0369A1,
            processingColor = 0x4338CA,
        )
        val SUNSET = OverlayPalette(
            recordingColor = 0xC2410C,
            pausedColor = 0xA16207,
            processingColor = 0x7E22CE,
        )
        val COLOR_BLIND = OverlayPalette(
            recordingColor = 0xD55E00,
            pausedColor = 0x009E73,
            processingColor = 0x0072B2,
        )

        fun isValidRgb(color: Int): Boolean = color in 0x000000..0xFFFFFF
    }
}

data class DisplayConfig(
    val buttonScale: Float = DEFAULT_BUTTON_SCALE,
    val buttonOpacity: Float = DEFAULT_BUTTON_OPACITY,
    val colorScheme: OverlayColorScheme = OverlayColorScheme.DEFAULT,
    val customPalette: OverlayPalette = OverlayPalette.DEFAULT,
) {
    fun normalized(): DisplayConfig = copy(
        buttonScale = buttonScale.takeIf { it.isFinite() }
            ?.coerceIn(MIN_BUTTON_SCALE, MAX_BUTTON_SCALE)
            ?: DEFAULT_BUTTON_SCALE,
        buttonOpacity = buttonOpacity.takeIf { it.isFinite() }
            ?.coerceIn(MIN_BUTTON_OPACITY, MAX_BUTTON_OPACITY)
            ?: DEFAULT_BUTTON_OPACITY,
        customPalette = customPalette.normalized(),
    )

    fun validate(): List<String> = buildList {
        if (!buttonScale.isFinite() || buttonScale !in MIN_BUTTON_SCALE..MAX_BUTTON_SCALE) {
            add("按钮大小必须在 $MIN_BUTTON_SCALE 到 $MAX_BUTTON_SCALE 之间")
        }
        if (!buttonOpacity.isFinite() || buttonOpacity !in MIN_BUTTON_OPACITY..MAX_BUTTON_OPACITY) {
            add("按钮不透明度必须在 $MIN_BUTTON_OPACITY 到 $MAX_BUTTON_OPACITY 之间")
        }
        addAll(customPalette.validate())
    }

    fun effectivePalette(): OverlayPalette = when (colorScheme) {
        OverlayColorScheme.DEFAULT -> OverlayPalette.DEFAULT
        OverlayColorScheme.OCEAN -> OverlayPalette.OCEAN
        OverlayColorScheme.SUNSET -> OverlayPalette.SUNSET
        OverlayColorScheme.COLOR_BLIND -> OverlayPalette.COLOR_BLIND
        OverlayColorScheme.CUSTOM -> customPalette
    }

    companion object {
        const val MIN_BUTTON_SCALE = 0.5f
        const val MAX_BUTTON_SCALE = 2f
        const val DEFAULT_BUTTON_SCALE = 1f
        const val MIN_BUTTON_OPACITY = 0.3f
        const val MAX_BUTTON_OPACITY = 1f
        const val DEFAULT_BUTTON_OPACITY = 1f
    }
}

data class AppSettings(
    val audio: AudioConfig = AudioConfig(),
    val provider: ProviderConfig = ProviderConfig(),
    val retry: RetryConfig = RetryConfig(),
    val interaction: InteractionConfig = InteractionConfig(),
    val display: DisplayConfig = DisplayConfig(),
)

data class RuntimeSettings(
    val app: AppSettings,
    val apiKey: String,
)

enum class JobState {
    IDLE,
    RECORDING,
    PAUSED,
    TRANSCODING,
    REQUESTING,
    RETRY_WAITING,
}

data class JobUiState(
    val state: JobState = JobState.IDLE,
    val message: String = "空闲",
    val amplitude: Float = 0f,
)
