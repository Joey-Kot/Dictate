package com.joeykot.dictate.model

/**
 * The client-side format of a raw PCM capture file.
 *
 * Android may capture at a different device format and resample before delivering samples to
 * AudioRecord. This describes the bytes written by the app and must therefore be used when the
 * file is decoded later.
 */
data class Pcm16Format(
    val sampleRateHz: Int,
    val channelCount: Int,
) {
    init {
        require(sampleRateHz > 0) { "PCM sample rate must be positive" }
        require(channelCount > 0) { "PCM channel count must be positive" }
    }

    val bytesPerFrame: Int = channelCount * BYTES_PER_SAMPLE
    val bytesPerSecond: Long = sampleRateHz.toLong() * bytesPerFrame

    fun minimumBytesFor(durationMillis: Long): Long {
        require(durationMillis >= 0L) { "Duration must not be negative" }
        return (bytesPerSecond * durationMillis + MILLIS_PER_SECOND - 1L) / MILLIS_PER_SECOND
    }

    fun summary(): String = "${sampleRateHz}Hz/${channelCount}ch/PCM16"

    companion object {
        const val BITS_PER_SAMPLE = 16
        const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8

        /** The format of the bundled connectivity-test PCM asset and legacy saved recordings. */
        val LEGACY_MONO_16_KHZ = Pcm16Format(sampleRateHz = 16_000, channelCount = 1)

        private const val MILLIS_PER_SECOND = 1_000L
    }
}
