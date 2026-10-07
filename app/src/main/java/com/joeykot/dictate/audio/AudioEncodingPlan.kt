package com.joeykot.dictate.audio

import com.joeykot.dictate.model.AudioCodec
import com.joeykot.dictate.model.AudioConfig
import com.joeykot.dictate.model.AudioContainer
import com.joeykot.dictate.model.Pcm16Format
import java.io.File

/** One resolved output description shared by transcoding, filenames and HTTP metadata. */
data class AudioEncodingPlan(val config: AudioConfig, val layout: OutputChannelLayout) {
    val mimeType: String get() = config.mimeType()
    val extension: String get() = config.container.extension

    fun command(executable: File, input: File, inputFormat: Pcm16Format, output: File): List<String> = buildList {
        add(executable.absolutePath)
        addAll(listOf("-hide_banner", "-nostdin", "-y", "-f", "s16le", "-ar", inputFormat.sampleRateHz.toString(), "-ac", inputFormat.channelCount.toString()))
        addAll(listOf("-i", input.absolutePath, "-vn"))
        val filters = mutableListOf<String>()
        if (layout.channels > 1) {
            require(inputFormat.channelCount == 1) { "Channel duplication requires a mono input" }
            filters += layout.duplicateMonoFilter()
        }
        filters += if ((config.codec == AudioCodec.PCM && config.bitDepth == 8) || config.codec == AudioCodec.PCM_S8) {
            "aresample=${config.sampleRate}:osf=u8:dither_method=triangular"
        } else "aresample=${config.sampleRate}"
        addAll(listOf("-af", filters.joinToString(","), "-ar", config.sampleRate.toString(), "-channel_layout", layout.name))
        addAll(listOf("-c:a", config.encoder()))
        if (config.codec.usesBitrate) addAll(listOf("-b:a", config.bitrateBps.toString()))
        when (config.codec) {
            AudioCodec.OPUS -> addAll(listOf("-application", "voip", "-vbr", "on"))
            AudioCodec.FLAC, AudioCodec.ALAC, AudioCodec.WAVPACK -> {
                val base = if (config.bitDepth <= 16) "s16" else "s32"
                val sampleFormat = if (config.codec == AudioCodec.FLAC) base else "${base}p"
                addAll(listOf("-sample_fmt", sampleFormat, "-bits_per_raw_sample", config.bitDepth.toString()))
            }
            else -> Unit
        }
        if (config.container in listOf(AudioContainer.M4A, AudioContainer.MP4, AudioContainer.MOV)) {
            addAll(listOf("-movflags", "+faststart"))
        }
        addAll(listOf("-f", config.container.muxer, output.absolutePath))
    }

    companion object {
        fun resolve(config: AudioConfig, input: Pcm16Format): AudioEncodingPlan =
            AudioEncodingPlan(config.resolvedForInput(input.sampleRateHz), OutputChannelLayout.select(supportedLayouts(config.codec)))

        // Every currently offered encoder accepts mono. Keep layout selection explicit so
        // an encoder requiring multiple channels can use the same conversion path.
        private fun supportedLayouts(codec: AudioCodec): List<OutputChannelLayout> = when (codec) {
            AudioCodec.AMR, AudioCodec.AMR_WB -> listOf(OutputChannelLayout.MONO)
            else -> listOf(OutputChannelLayout.MONO, OutputChannelLayout.STEREO)
        }
    }
}

data class OutputChannelLayout(val name: String, val channels: Int) {
    fun duplicateMonoFilter(): String = "pan=$name|" + (0 until channels).joinToString("|") { "c$it=c0" }

    companion object {
        val MONO = OutputChannelLayout("mono", 1)
        val STEREO = OutputChannelLayout("stereo", 2)
        fun select(supported: List<OutputChannelLayout>): OutputChannelLayout =
            requireNotNull(supported.filter { it.channels > 0 }.minByOrNull { it.channels }) { "No supported channel layout" }
    }
}
