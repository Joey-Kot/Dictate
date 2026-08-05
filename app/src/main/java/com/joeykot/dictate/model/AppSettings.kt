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

data class AppSettings(
    val audio: AudioConfig = AudioConfig(),
    val provider: ProviderConfig = ProviderConfig(),
    val retry: RetryConfig = RetryConfig(),
    val interaction: InteractionConfig = InteractionConfig(),
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
