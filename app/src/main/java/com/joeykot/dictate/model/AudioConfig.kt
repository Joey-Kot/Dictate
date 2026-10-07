package com.joeykot.dictate.model

import com.joeykot.dictate.R
import com.joeykot.dictate.i18n.AppStrings
import kotlin.math.abs

/** Names already stored in preferences and exported JSON remain stable. */
enum class AudioCodec(val value: String, val label: String, val encoder: String, val usesBitrate: Boolean = true) {
    OPUS("opus", "Opus", "libopus"),
    MP3("mp3", "MP3", "libmp3lame"),
    AAC("aac", "AAC", "aac"),
    PCM("pcm", "PCM", "pcm_s16le", false),
    VORBIS("vorbis", "Vorbis", "libvorbis"),
    FLAC("flac", "FLAC", "flac", false),
    ALAC("alac", "ALAC", "alac", false),
    AC3("ac3", "AC-3", "ac3"),
    EAC3("eac3", "E-AC-3", "eac3"),
    MP2("mp2", "MP2", "mp2"),
    ADPCM("adpcm", "ADPCM (MS)", "adpcm_ms", false),
    AMR("amr", "AMR-NB", "libopencore_amrnb"),
    AMR_WB("amr_wb", "AMR-WB", "libvo_amrwbenc"),
    SPEEX("speex", "Speex", "libspeex"),
    WAVPACK("wavpack", "WavPack", "wavpack", false),
    WMAV1("wmav1", "WMA v1", "wmav1"),
    WMAV2("wmav2", "WMA v2", "wmav2"),
    PCM_S8("pcm_s8", "PCM 8-bit (signed)", "pcm_s8", false),
    PCM_ALAW("pcm_alaw", "PCM A-law", "pcm_alaw", false),
    PCM_MULAW("pcm_mulaw", "PCM μ-law", "pcm_mulaw", false),
    PCM_F32LE("pcm_f32le", "PCM Float 32-bit (LE)", "pcm_f32le", false),
    PCM_F64LE("pcm_f64le", "PCM Float 64-bit (LE)", "pcm_f64le", false),
    PCM_S64LE("pcm_s64le", "PCM 64-bit (LE)", "pcm_s64le", false),
    PCM_S16BE("pcm_s16be", "PCM 16-bit (BE)", "pcm_s16be", false),
    PCM_S24BE("pcm_s24be", "PCM 24-bit (BE)", "pcm_s24be", false),
    PCM_S32BE("pcm_s32be", "PCM 32-bit (BE)", "pcm_s32be", false),
    PCM_F32BE("pcm_f32be", "PCM Float 32-bit (BE)", "pcm_f32be", false),
    PCM_F64BE("pcm_f64be", "PCM Float 64-bit (BE)", "pcm_f64be", false),
}

enum class AudioContainer(val value: String, val extension: String, val mimeType: String, val muxer: String = value) {
    OPUS("opus", "opus", "audio/opus"), OGG("ogg", "ogg", "audio/ogg"),
    MP3("mp3", "mp3", "audio/mpeg"), M4A("m4a", "m4a", "audio/mp4", "ipod"),
    WAV("wav", "wav", "audio/wav"), WEBM("webm", "webm", "audio/webm"),
    MP4("mp4", "mp4", "audio/mp4"), MKV("mkv", "mkv", "audio/x-matroska", "matroska"),
    MKA("mka", "mka", "audio/x-matroska", "matroska"), AAC("aac", "aac", "audio/aac", "adts"),
    FLV("flv", "flv", "video/x-flv"), MOV("mov", "mov", "video/quicktime"),
    AVI("avi", "avi", "video/x-msvideo"), MPEG("mpeg", "mpeg", "video/mpeg"),
    FLAC("flac", "flac", "audio/flac"), AC3("ac3", "ac3", "audio/ac3"),
    EAC3("eac3", "eac3", "audio/eac3"), AMR("amr", "amr", "audio/amr"),
    SPX("spx", "spx", "audio/ogg"), WV("wv", "wv", "audio/x-wavpack"),
    WMA("wma", "wma", "audio/x-ms-wma", "asf"), ASF("asf", "asf", "video/x-ms-asf"),
    AIFF("aiff", "aiff", "audio/aiff"),
    S8("s8", "s8", "application/octet-stream"),
    S16LE("s16le", "s16le", "application/octet-stream"),
    S24LE("s24le", "s24le", "application/octet-stream"),
    S32LE("s32le", "s32le", "application/octet-stream"),
    S16BE("s16be", "s16be", "application/octet-stream"),
    S24BE("s24be", "s24be", "application/octet-stream"),
    S32BE("s32be", "s32be", "application/octet-stream"),
    F32LE("f32le", "f32le", "application/octet-stream"),
    F64LE("f64le", "f64le", "application/octet-stream"),
    F32BE("f32be", "f32be", "application/octet-stream"),
    F64BE("f64be", "f64be", "application/octet-stream"),
    ALAW("alaw", "alaw", "application/octet-stream"),
    MULAW("mulaw", "mulaw", "application/octet-stream"),
}

data class AudioConfig(
    val bitDepth: Int = DEFAULT_BIT_DEPTH,
    val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    val codec: AudioCodec = AudioCodec.MP3,
    val container: AudioContainer = AudioContainer.MP3,
    val bitrateBps: Int = DEFAULT_BITRATE_BPS,
) {
    /** Reconcile explicit UI changes and old preferences, never an imported invalid configuration. */
    fun normalized(): AudioConfig {
        val rate = sampleRate.takeIf { it in compatibleSampleRates(codec) } ?: AUTO_SAMPLE_RATE
        return normalizedAt(rate)
    }

    fun resolvedForInput(inputSampleRateHz: Int): AudioConfig {
        require(inputSampleRateHz > 0) { AppStrings.get(R.string.settings_input_sample_rate, "Input sample rate must be positive") }
        val errors = validate()
        require(errors.isEmpty()) { errors.joinToString("; ") }
        val rate = if (sampleRate == AUTO_SAMPLE_RATE) {
            nearestSampleRate(codec, inputSampleRateHz.coerceAtMost(MAX_AUTO_OUTPUT_SAMPLE_RATE))
        } else sampleRate
        return normalizedAt(rate)
    }

    private fun normalizedAt(rate: Int): AudioConfig {
        val depths = compatibleBitDepths(codec)
        val depth = if (depths.isNotEmpty()) bitDepth.takeIf { it in depths } ?: depths.first() else bitDepth
        val containers = compatibleContainers(codec, rate, depth)
        val bitrates = compatibleBitrates(codec, rate)
        return copy(
            sampleRate = rate,
            bitDepth = depth,
            container = container.takeIf { it in containers } ?: containers.first(),
            bitrateBps = if (!codec.usesBitrate) bitrateBps else {
                bitrateBps.takeIf { it in bitrates } ?: defaultBitrate(codec, rate)
            },
        )
    }

    fun validate(): List<String> = buildList {
        val depths = compatibleBitDepths(codec).ifEmpty { BIT_DEPTHS }
        if (bitDepth !in depths) add(AppStrings.get(R.string.settings_bit_depth, "Bit depth must be one of: %1\$s bits", depths.joinToString()))
        if (sampleRate !in compatibleSampleRates(codec)) add(AppStrings.get(R.string.settings_sample_rate_unsupported, "Unsupported sample rate"))
        if (codec.usesBitrate && bitrateBps !in compatibleBitrates(codec, sampleRate)) {
            add(AppStrings.get(R.string.settings_codec_bitrate, "The %1\$s codec does not support this bitrate at the selected sample rate", codec.value))
        }
        if (container !in compatibleContainers(codec, sampleRate, bitDepth)) {
            add(AppStrings.get(R.string.settings_codec_container, "The %1\$s codec cannot use the %2\$s container", codec.value, container.value))
        }
    }

    fun encoder(): String = if (codec == AudioCodec.PCM) {
        if (bitDepth == 8) "pcm_u8" else "pcm_s${bitDepth}le"
    } else codec.encoder

    fun mimeType(): String = if (codec == AudioCodec.AMR_WB && container == AudioContainer.AMR) "audio/amr-wb" else container.mimeType

    companion object {
        const val AUTO_SAMPLE_RATE = 0
        const val DEFAULT_BIT_DEPTH = 16
        const val DEFAULT_SAMPLE_RATE = AUTO_SAMPLE_RATE
        const val DEFAULT_BITRATE_KBPS = 128 // Legacy preferences / schema versions 1–4.
        const val DEFAULT_BITRATE_BPS = 128_000
        const val MAX_AUTO_OUTPUT_SAMPLE_RATE = 48_000
        val BIT_DEPTHS = listOf(8, 16, 24, 32)
        val SAMPLE_RATES = listOf(0, 7350, 8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 64000, 88200, 96000, 176400, 192000)
        private val BITRATES = listOf(6, 8, 10, 12, 16, 20, 24, 28, 32, 40, 44, 48, 50, 56, 60, 64, 80, 96, 100, 112, 120, 128, 140, 144, 160, 180, 190, 192, 200, 224, 240, 250, 256, 288, 320, 352, 384, 448, 500, 510, 512, 576, 640)

        fun compatibleBitDepths(codec: AudioCodec): List<Int> = when (codec) {
            AudioCodec.PCM -> BIT_DEPTHS
            AudioCodec.FLAC, AudioCodec.ALAC -> listOf(16, 24)
            AudioCodec.WAVPACK -> listOf(16, 24, 32)
            else -> emptyList()
        }

        fun compatibleSampleRates(codec: AudioCodec): List<Int> = SAMPLE_RATES.filter { rate ->
            rate == AUTO_SAMPLE_RATE || when (codec) {
                AudioCodec.OPUS -> rate in listOf(8000, 12000, 16000, 24000, 48000)
                AudioCodec.MP3, AudioCodec.VORBIS, AudioCodec.WMAV1, AudioCodec.WMAV2 -> rate in 8000..48000
                AudioCodec.MP2 -> rate in listOf(16000, 22050, 24000, 32000, 44100, 48000)
                AudioCodec.AC3, AudioCodec.EAC3 -> rate in listOf(32000, 44100, 48000)
                AudioCodec.AMR -> rate == 8000
                AudioCodec.AMR_WB -> rate == 16000
                AudioCodec.SPEEX -> rate in listOf(8000, 16000, 32000)
                AudioCodec.AAC -> rate <= 96000
                else -> true
            }
        }

        fun nearestSampleRate(codec: AudioCodec, requested: Int): Int =
            compatibleSampleRates(codec).filter { it != AUTO_SAMPLE_RATE }
                .minWith(compareBy<Int> { abs(it.toLong() - requested) }.thenBy { it })

        fun compatibleContainers(codec: AudioCodec, sampleRate: Int = AUTO_SAMPLE_RATE, bitDepth: Int = DEFAULT_BIT_DEPTH): List<AudioContainer> {
            // Automatic MP3 output can resolve to a low capture rate. Offer the intersection
            // so a later route change cannot silently replace the selected container.
            val names = when (codec) {
                AudioCodec.OPUS -> "opus ogg webm mp4 mkv mka"
                AudioCodec.VORBIS -> "ogg webm mkv mka"
                // Raw AAC packets in WAV lose their boundaries and cannot reliably round-trip.
                AudioCodec.AAC -> "m4a mp4 aac flv mkv mka mov"
                AudioCodec.MP3 -> when {
                    sampleRate == 11025 -> "mp3 wav avi flv mkv mka mpeg"
                    sampleRate < 16000 -> "mp3 wav avi mkv mka mpeg"
                    sampleRate in listOf(22050, 44100, 48000) -> "mp3 wav mp4 avi flv mkv mka mpeg"
                    else -> "mp3 wav mp4 avi mkv mka mpeg"
                }
                AudioCodec.ALAC -> "m4a mp4 mov"
                AudioCodec.FLAC -> "flac ogg mkv mka"
                AudioCodec.AC3 -> "ac3 m4a mp4 wav avi mkv mka mpeg"
                AudioCodec.EAC3 -> "eac3 mp4 mkv mka"
                AudioCodec.AMR -> "amr wav"
                AudioCodec.AMR_WB -> "amr"
                AudioCodec.SPEEX -> "spx ogg"
                AudioCodec.WAVPACK -> "wv"
                AudioCodec.WMAV1, AudioCodec.WMAV2 -> "wma asf"
                AudioCodec.PCM_S8 -> "aiff s8"
                AudioCodec.PCM_ALAW -> "wav alaw"
                AudioCodec.PCM_MULAW -> "wav mulaw"
                AudioCodec.PCM_S64LE, AudioCodec.ADPCM -> "wav"
                AudioCodec.MP2 -> "wav mp4 mpeg"
                AudioCodec.PCM -> when (bitDepth) {
                    8 -> "wav"
                    24 -> "wav mp4 mov s24le"
                    32 -> "wav mp4 mov s32le"
                    else -> "wav mp4 mov avi s16le"
                }
                AudioCodec.PCM_F32LE -> "wav mp4 f32le"
                AudioCodec.PCM_F64LE -> "wav mp4 f64le"
                AudioCodec.PCM_S16BE -> "aiff mp4 s16be"
                AudioCodec.PCM_S24BE -> "aiff mp4 s24be"
                AudioCodec.PCM_S32BE -> "aiff mp4 s32be"
                AudioCodec.PCM_F32BE -> "aiff mp4 f32be"
                AudioCodec.PCM_F64BE -> "aiff mp4 f64be"
            }
            return names.split(' ').map { name -> AudioContainer.entries.first { it.value == name } }
        }

        /** Exact bits/s, including the fractional-kbps AMR modes. */
        fun compatibleBitrates(codec: AudioCodec, sampleRate: Int): List<Int> {
            if (!codec.usesBitrate) return emptyList()
            if (codec == AudioCodec.AMR) return listOf(4750, 5150, 5900, 6700, 7400, 7950, 10200, 12200)
            if (codec == AudioCodec.AMR_WB) return listOf(6600, 8850, 12650, 14250, 15850, 18250, 19850, 23050, 23850)
            val rate = if (sampleRate == AUTO_SAMPLE_RATE) nearestSampleRate(codec, MAX_AUTO_OUTPUT_SAMPLE_RATE) else sampleRate
            if (codec == AudioCodec.SPEEX) return when (rate) {
                8000 -> listOf(2150, 3950, 5950, 8000, 11000, 15000, 18200, 24600)
                16000 -> listOf(3950, 5750, 7750, 9800, 12800, 16800, 20600, 23800, 27800, 34200, 42200)
                else -> listOf(4150, 7550, 9550, 11600, 14600, 18600, 22400, 25600, 29600, 36000, 44000)
            }
            return BITRATES.filter { bitrate ->
                when (codec) {
                    AudioCodec.OPUS -> bitrate in 6..256
                    AudioCodec.MP3 -> when {
                        rate <= 8000 -> bitrate in listOf(8, 16, 24, 32, 40, 48, 56, 64)
                        rate < 32000 -> bitrate in listOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
                        else -> bitrate in listOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
                    }
                    AudioCodec.MP2 -> if (rate < 32000) bitrate in listOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
                        else bitrate in listOf(32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
                    AudioCodec.AAC -> bitrate >= 8 && bitrate * 1000L <= rate * 6L
                    AudioCodec.AC3 -> bitrate in listOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 448, 512, 576, 640)
                    AudioCodec.EAC3 -> bitrate >= 32 && bitrate * 1000L <= rate * 128L
                    AudioCodec.VORBIS -> bitrate in when {
                        rate < 9000 -> 8..42
                        rate < 15000 -> 12..50
                        rate < 19000 -> 16..100
                        rate < 26000 -> 16..90
                        rate < 40000 -> 30..190
                        else -> 32..240
                    }
                    AudioCodec.WMAV1, AudioCodec.WMAV2 -> bitrate in 24..320
                    else -> false
                }
            }.map { it * 1000 }
        }

        fun defaultBitrate(codec: AudioCodec, sampleRate: Int): Int {
            val available = compatibleBitrates(codec, sampleRate)
            return DEFAULT_BITRATE_BPS.takeIf { it in available } ?: available.lastOrNull() ?: DEFAULT_BITRATE_BPS
        }

        fun defaultContainer(codec: AudioCodec): AudioContainer = compatibleContainers(codec).first()
    }
}
