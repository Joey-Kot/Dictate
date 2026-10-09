package com.joeykot.dictate.audio

import com.joeykot.dictate.model.Pcm16Format
import com.joeykot.dictate.model.SegmentedUploadConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * A half-open interval on the original PCM timeline, measured in frames per
 * channel. Segment planning deliberately never works in encoded packet or
 * wall-clock coordinates, so every original PCM frame can be preserved.
 */
data class SourceInterval(
    val startFrame: Long,
    val endFrame: Long,
) {
    val frameCount: Long
        get() = if (endFrame > startFrame) endFrame - startFrame else 0L
}

/** A frozen, contiguous partition of one PCM source recording. */
data class SegmentPlan(
    val sourceRateHz: Int,
    val totalFrames: Long,
    val maxSegmentFrames: Long,
    val segments: List<SourceInterval>,
) {
    init {
        validate()
    }

    /**
     * Verify the invariants required by a later export or retranscription.
     * A frozen plan is intentionally independent from subsequently edited
     * settings, but it must still describe this exact PCM frame timeline.
     */
    fun validate() {
        require(sourceRateHz > 0) { "Segment plan source sample rate must be positive" }
        require(totalFrames > 0L) { "Segment plan must contain source frames" }
        require(maxSegmentFrames > 0L) { "Segment plan maximum length must be positive" }
        require(segments.isNotEmpty()) { "Segment plan must contain at least one segment" }

        var cursor = 0L
        for (segment in segments) {
            require(segment.startFrame == cursor) { "Segment plan must cover source frames without gaps or overlaps" }
            require(segment.startFrame >= 0L && segment.endFrame > segment.startFrame) {
                "Segment plan contains an empty or invalid interval"
            }
            require(segment.endFrame <= totalFrames) { "Segment plan interval exceeds the source recording" }
            require(segment.frameCount <= maxSegmentFrames) { "Segment plan interval exceeds the maximum length" }
            cursor = segment.endFrame
        }
        require(cursor == totalFrames) { "Segment plan does not cover the complete source recording" }
    }
}

/** Whether an analysis found requestable speech or a fully silent recording. */
sealed interface SegmentPlanOutcome {
    data object NoSpeech : SegmentPlanOutcome
    data class Plan(val value: SegmentPlan) : SegmentPlanOutcome
}

/**
 * Build a complete upload partition from already-qualified pause intervals.
 *
 * This is deliberately the same non-destructive decision layer as the Windows
 * implementation: pauses select a natural boundary, but no pause samples are
 * removed. All resulting ranges are half-open and cover [0, totalFrames)
 * exactly once.
 */
fun buildSegmentPlan(
    sourceRateHz: Int,
    totalFrames: Long,
    maxSegmentFrames: Long,
    silenceIntervals: List<SourceInterval>,
): SegmentPlanOutcome {
    require(sourceRateHz > 0) { "Segment plan source sample rate must be positive" }
    require(totalFrames >= 0L) { "Segment plan source frame count must not be negative" }
    require(maxSegmentFrames > 0L) { "Segment plan maximum length must be positive" }
    if (totalFrames == 0L) return SegmentPlanOutcome.NoSpeech

    val silences = normalizeSilences(totalFrames, silenceIntervals)
    if (silences.size == 1 && silences.single() == SourceInterval(0L, totalFrames)) {
        return SegmentPlanOutcome.NoSpeech
    }

    val segments = ArrayList<SourceInterval>()
    var cursor = 0L
    while (cursor < totalFrames) {
        val unsaturatedLimit = if (cursor > Long.MAX_VALUE - maxSegmentFrames) {
            Long.MAX_VALUE
        } else {
            cursor + maxSegmentFrames
        }
        val limit = minOf(unsaturatedLimit, totalFrames)
        val end = if (limit == totalFrames) {
            // A final range that already fits is never split merely because it
            // contains a pause. This matches split mode's max-span grouping.
            totalFrames
        } else {
            chooseSegmentBreak(cursor, limit, silences) ?: limit
        }
        check(end > cursor && end <= limit) { "Segment planner produced an invalid interval" }
        segments += SourceInterval(cursor, end)
        cursor = end
    }

    return SegmentPlanOutcome.Plan(
        SegmentPlan(
            sourceRateHz = sourceRateHz,
            totalFrames = totalFrames,
            maxSegmentFrames = maxSegmentFrames,
            segments = segments,
        ),
    )
}

private fun normalizeSilences(
    totalFrames: Long,
    silenceIntervals: List<SourceInterval>,
): List<SourceInterval> {
    val normalized = silenceIntervals.mapNotNull { interval ->
        if (interval.startFrame < 0L || interval.endFrame < 0L) return@mapNotNull null
        val start = interval.startFrame.coerceAtMost(totalFrames)
        val end = interval.endFrame.coerceAtMost(totalFrames)
        SourceInterval(start, end).takeIf { it.endFrame > it.startFrame }
    }.sortedWith(compareBy<SourceInterval> { it.startFrame }.thenBy { it.endFrame })

    val merged = ArrayList<SourceInterval>(normalized.size)
    for (interval in normalized) {
        val previous = merged.lastOrNull()
        if (previous != null && interval.startFrame <= previous.endFrame) {
            merged[merged.lastIndex] = previous.copy(endFrame = maxOf(previous.endFrame, interval.endFrame))
        } else {
            merged += interval
        }
    }
    return merged
}

/**
 * Select the latest pause that begins strictly after [cursor]. A pause which
 * crosses the strict [limit] is deliberately cut at [limit], preserving the
 * pause across the adjacent independent media files.
 */
private fun chooseSegmentBreak(
    cursor: Long,
    limit: Long,
    silences: List<SourceInterval>,
): Long? {
    var selected: Long? = null
    for (silence in silences) {
        if (silence.endFrame <= cursor) continue
        // This includes leading silence and the remainder of a pause used by
        // the preceding segment. Selecting either would make a short segment.
        if (silence.startFrame <= cursor) continue
        if (silence.startFrame > limit) break
        if (silence.endFrame >= limit) return limit
        selected = silence.endFrame
    }
    return selected
}

/** A pause-analysis result in the same source-frame coordinate system as [SegmentPlan]. */
data class PcmPauseAnalysis(
    val sourceRateHz: Int,
    val totalFrames: Long,
    val silenceIntervals: List<SourceInterval>,
)

/** A stable cancellation probe shared by PCM analysis and temporary export. */
fun interface SegmentedUploadCancellation {
    fun isCancellationRequested(): Boolean

    companion object {
        val NONE = SegmentedUploadCancellation { false }
    }
}

/** Provides pause intervals for an original PCM16 recording. */
fun interface PcmPauseAnalysisProvider {
    fun analyze(
        rawInput: File,
        inputFormat: Pcm16Format,
        minimumPauseDurationMillis: Long,
        cancellation: SegmentedUploadCancellation,
    ): PcmPauseAnalysis
}

/** Raised internally and by callers that need to distinguish cancellation from an upload failure. */
class SegmentedUploadCancellationException : CancellationException("Segmented upload preparation was cancelled")

/**
 * Pause analysis for Android's raw PCM16 recording files.
 *
 * It mirrors the Windows native bridge's adaptive amplitude thresholds and
 * keeps all coordinates in source frames. Android capture is normally mono;
 * if a future caller supplies interleaved multi-channel PCM, the analyzer uses
 * one arithmetic downmix value per source frame so frame coordinates remain
 * exact and no channel's samples shift the timeline.
 */
class PcmPauseAnalyzer : PcmPauseAnalysisProvider {
    override fun analyze(
        rawInput: File,
        inputFormat: Pcm16Format,
        minimumPauseDurationMillis: Long,
        cancellation: SegmentedUploadCancellation,
    ): PcmPauseAnalysis {
        throwIfCancelled(cancellation)
        val source = inspectPcmSource(rawInput, inputFormat)
        if (source.totalFrames == 0L) {
            return PcmPauseAnalysis(inputFormat.sampleRateHz, 0L, emptyList())
        }

        val volume = VolumeCapture()
        scanPcmFrames(rawInput, inputFormat, source, cancellation) { _, sample ->
            if (!sample.isFinite()) return@scanPcmFrames
            val absolute = abs(sample)
            volume.sumSquares += sample * sample
            if (absolute > volume.maxAbsolute) volume.maxAbsolute = absolute
            if (volume.sampleCount == Long.MAX_VALUE) {
                throw IllegalArgumentException("PCM source frame count overflow")
            }
            volume.sampleCount += 1L
            volume.hasMaximum = true
            volume.hasMean = true
        }

        val minimumPauseFrames = minimumPauseFrames(minimumPauseDurationMillis, inputFormat.sampleRateHz)
        val thresholds = adaptiveThresholds(volume)
        for (thresholdDb in thresholds) {
            throwIfCancelled(cancellation)
            val threshold = 10.0.pow(thresholdDb / 20.0)
            val silences = collectSilences(
                rawInput = rawInput,
                inputFormat = inputFormat,
                source = source,
                minimumPauseFrames = minimumPauseFrames,
                threshold = threshold,
                cancellation = cancellation,
            )
            if (silences.isNotEmpty()) {
                return PcmPauseAnalysis(inputFormat.sampleRateHz, source.totalFrames, silences)
            }
        }
        return PcmPauseAnalysis(inputFormat.sampleRateHz, source.totalFrames, emptyList())
    }

    private fun collectSilences(
        rawInput: File,
        inputFormat: Pcm16Format,
        source: PcmSource,
        minimumPauseFrames: Long,
        threshold: Double,
        cancellation: SegmentedUploadCancellation,
    ): List<SourceInterval> {
        val intervals = ArrayList<SourceInterval>()
        var pendingStart: Long? = null
        scanPcmFrames(rawInput, inputFormat, source, cancellation) { frame, sample ->
            val silent = !sample.isNaN() && abs(sample) <= threshold
            if (silent) {
                if (pendingStart == null) pendingStart = frame
            } else {
                val start = pendingStart
                if (start != null) {
                    if (frame - start >= minimumPauseFrames) {
                        intervals += SourceInterval(start, frame)
                    }
                    pendingStart = null
                }
            }
        }
        pendingStart?.let { start ->
            // A recording which was silent from frame zero has no speech to
            // preserve even when it is shorter than the configured minimum.
            if (start == 0L || source.totalFrames - start >= minimumPauseFrames) {
                intervals += SourceInterval(start, source.totalFrames)
            }
        }
        return intervals
    }

    private fun minimumPauseFrames(minimumPauseDurationMillis: Long, sourceRateHz: Int): Long {
        val milliseconds = minimumPauseDurationMillis.coerceAtLeast(1L)
        val product = try {
            Math.multiplyExact(milliseconds, sourceRateHz.toLong())
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("Minimum pause duration is too large", error)
        }
        return (product / MILLIS_PER_SECOND + if (product % MILLIS_PER_SECOND == 0L) 0L else 1L)
            .coerceAtLeast(1L)
    }

    private fun adaptiveThresholds(volume: VolumeCapture): List<Double> = buildList {
        if (volume.hasMaximum && volume.maxAbsolute > 0.0 && volume.maxAbsolute.isFinite()) {
            val maximumDb = 20.0 * log10(volume.maxAbsolute)
            PEAK_OFFSETS_DB.forEach { offset ->
                addDistinct(clampDb(maximumDb - offset))
            }
            return@buildList
        }
        if (
            volume.hasMean && volume.sampleCount > 0L && volume.sumSquares > 0.0 &&
            volume.sumSquares.isFinite()
        ) {
            val meanDb = 10.0 * log10(volume.sumSquares / volume.sampleCount.toDouble())
            val base = meanDb - MEAN_THRESHOLD_OFFSET_DB
            MEAN_OFFSETS_DB.forEach { offset ->
                addDistinct(clampDb(base + offset))
            }
            return@buildList
        }
        FALLBACK_THRESHOLDS_DB.forEach(::add)
    }

    private fun MutableList<Double>.addDistinct(value: Double) {
        if (lastOrNull() != value) add(value)
    }

    private data class VolumeCapture(
        var sumSquares: Double = 0.0,
        var maxAbsolute: Double = 0.0,
        var sampleCount: Long = 0L,
        var hasMaximum: Boolean = false,
        var hasMean: Boolean = false,
    )

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val MEAN_THRESHOLD_OFFSET_DB = 8.0
        val PEAK_OFFSETS_DB = doubleArrayOf(18.0, 16.0, 14.0, 12.0, 10.0)
        val MEAN_OFFSETS_DB = doubleArrayOf(0.0, 6.0, 12.0)
        val FALLBACK_THRESHOLDS_DB = doubleArrayOf(-35.0, -30.0, -25.0, -20.0)

        fun clampDb(value: Double): Double = value.coerceIn(-60.0, -10.0)
    }
}

/** Settings captured together with a plan so retries retain their original split contract. */
data class SegmentedUploadParameters(
    val maximumSegmentLengthSeconds: Long,
    val minimumPauseDurationMillis: Long,
    val concurrency: Int,
) {
    init {
        require(maximumSegmentLengthSeconds > 0L) { "Maximum segment length must be positive" }
        require(minimumPauseDurationMillis > 0L) { "Minimum pause duration must be positive" }
        require(concurrency in 1..SegmentedUploadConfig.MAX_CONCURRENCY) {
            "Segment upload concurrency must be between 1 and ${SegmentedUploadConfig.MAX_CONCURRENCY}"
        }
    }

    fun maximumSegmentFrames(sourceRateHz: Int): Long {
        require(sourceRateHz > 0) { "Source sample rate must be positive" }
        return try {
            Math.multiplyExact(maximumSegmentLengthSeconds, sourceRateHz.toLong())
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("Maximum segment length is too large", error)
        }
    }
}

/** Serializable retry state for one preserved original recording. */
data class SegmentedUploadSnapshot(
    val parameters: SegmentedUploadParameters,
    val plan: SegmentPlan,
) {
    init {
        plan.validate()
        require(parameters.maximumSegmentFrames(plan.sourceRateHz) == plan.maxSegmentFrames) {
            "Frozen segment plan does not match its maximum length setting"
        }
    }
}

/** Stable sidecar representation for [SegmentedUploadSnapshot]. */
object SegmentedUploadSnapshotCodec {
    const val VERSION = 1

    fun encode(snapshot: SegmentedUploadSnapshot): String = JSONObject().apply {
        put("version", VERSION)
        put("maximumSegmentLengthSeconds", snapshot.parameters.maximumSegmentLengthSeconds)
        put("minimumPauseDurationMillis", snapshot.parameters.minimumPauseDurationMillis)
        put("concurrency", snapshot.parameters.concurrency)
        put("sourceRateHz", snapshot.plan.sourceRateHz)
        put("totalFrames", snapshot.plan.totalFrames)
        put("maxSegmentFrames", snapshot.plan.maxSegmentFrames)
        put("segments", JSONArray().apply {
            snapshot.plan.segments.forEach { segment ->
                put(JSONObject().apply {
                    put("startFrame", segment.startFrame)
                    put("endFrame", segment.endFrame)
                })
            }
        })
    }.toString()

    fun decode(value: String): SegmentedUploadSnapshot {
        val root = JSONObject(value)
        require(requiredInt(root, "version") == VERSION) { "Unsupported segmented upload snapshot version" }
        val parameters = SegmentedUploadParameters(
            maximumSegmentLengthSeconds = requiredLong(root, "maximumSegmentLengthSeconds"),
            minimumPauseDurationMillis = requiredLong(root, "minimumPauseDurationMillis"),
            concurrency = requiredInt(root, "concurrency"),
        )
        val intervals = requiredArray(root, "segments").let { items ->
            List(items.length()) { index ->
                val item = items.opt(index) as? JSONObject
                    ?: throw IllegalArgumentException("Segmented upload snapshot segment $index must be an object")
                SourceInterval(
                    startFrame = requiredLong(item, "startFrame"),
                    endFrame = requiredLong(item, "endFrame"),
                )
            }
        }
        return SegmentedUploadSnapshot(
            parameters = parameters,
            plan = SegmentPlan(
                sourceRateHz = requiredInt(root, "sourceRateHz"),
                totalFrames = requiredLong(root, "totalFrames"),
                maxSegmentFrames = requiredLong(root, "maxSegmentFrames"),
                segments = intervals,
            ),
        )
    }

    private fun requiredArray(root: JSONObject, key: String): JSONArray = root.opt(key) as? JSONArray
        ?: throw IllegalArgumentException("Segmented upload snapshot $key must be an array")

    private fun requiredLong(root: JSONObject, key: String): Long = when (val value = root.opt(key)) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> throw IllegalArgumentException("Segmented upload snapshot $key must be an integer")
    }

    private fun requiredInt(root: JSONObject, key: String): Int {
        val value = requiredLong(root, key)
        require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "Segmented upload snapshot $key is out of range" }
        return value.toInt()
    }
}

/** Select whether a preparation attempt analyzes a new recording or reuses frozen retry state. */
sealed interface SegmentedUploadPlanSource {
    data class Analyze(val parameters: SegmentedUploadParameters) : SegmentedUploadPlanSource
    data class Frozen(val snapshot: SegmentedUploadSnapshot) : SegmentedUploadPlanSource
}

/** One independent FFmpeg conversion request generated from a segment range. */
data class SegmentMediaEncodeRequest(
    val operationId: Long,
    val rawInput: File,
    val inputFormat: Pcm16Format,
    val output: File,
    val encodingPlan: AudioEncodingPlan,
    val cancellation: SegmentedUploadCancellation,
)

sealed interface SegmentMediaEncodeResult {
    data object Success : SegmentMediaEncodeResult
    data class Failure(val message: String) : SegmentMediaEncodeResult
    data object Cancelled : SegmentMediaEncodeResult
}

/**
 * The export layer is independent from a particular FFmpeg process manager.
 * Each request carries its own operation ID so one parent job can safely
 * encode several planned files without reusing its job ID as a process key.
 */
fun interface SegmentMediaEncoder {
    fun encode(request: SegmentMediaEncodeRequest): SegmentMediaEncodeResult
}

interface CancellableSegmentMediaEncoder : SegmentMediaEncoder {
    fun cancel(operationId: Long)
}

/** Bridge the existing FFmpeg CLI chain into [SegmentMediaEncoder]. */
class AudioTranscoderSegmentMediaEncoder(
    private val transcoder: AudioTranscoder,
) : CancellableSegmentMediaEncoder {
    override fun encode(request: SegmentMediaEncodeRequest): SegmentMediaEncodeResult {
        if (request.cancellation.isCancellationRequested()) {
            return SegmentMediaEncodeResult.Cancelled
        }
        return when (
            val result = transcoder.transcode(
                jobId = request.operationId,
                rawInput = request.rawInput,
                inputFormat = request.inputFormat,
                output = request.output,
                plan = request.encodingPlan,
                cancellationRequested = request.cancellation::isCancellationRequested,
            )
        ) {
            is AudioTranscoder.Result.Success -> SegmentMediaEncodeResult.Success
            is AudioTranscoder.Result.Failure -> SegmentMediaEncodeResult.Failure(result.message)
            AudioTranscoder.Result.Cancelled -> SegmentMediaEncodeResult.Cancelled
        }
    }

    override fun cancel(operationId: Long) {
        transcoder.cancelRunning(operationId)
    }
}

/** Inputs for one temporary segmented-upload export attempt. */
data class SegmentedUploadPreparationRequest(
    val rawInput: File,
    val inputFormat: Pcm16Format,
    val encodingPlan: AudioEncodingPlan,
    val temporaryRoot: File,
    val planSource: SegmentedUploadPlanSource,
    /**
     * Must return an ID unique across every concurrently active FFmpeg call,
     * not merely unique relative to the parent voice-job ID.
     */
    val operationIdForSegment: (Int) -> Long,
    val encoder: SegmentMediaEncoder,
    val cancellation: SegmentedUploadCancellation = SegmentedUploadCancellation.NONE,
    /** Called exactly once for a newly analyzed plan, before its first export. */
    val onPlanFrozen: ((SegmentedUploadSnapshot) -> Unit)? = null,
    /** Called before the first export for new and frozen plans alike. */
    val onOperationsPlanned: ((List<Long>) -> Unit)? = null,
)

/** Files for one batch of independent, complete upload requests. */
data class PreparedSegmentedUpload(
    val directory: File,
    val snapshot: SegmentedUploadSnapshot,
    val files: List<File>,
    val operationIds: List<Long>,
) {
    /** Remove all temporary raw slices and encoded media from this one attempt. */
    fun cleanup() {
        directory.deleteRecursively()
    }
}

sealed interface SegmentedUploadPreparationResult {
    data object NoSpeech : SegmentedUploadPreparationResult
    data object Cancelled : SegmentedUploadPreparationResult
    data class Success(val upload: PreparedSegmentedUpload) : SegmentedUploadPreparationResult
    data class Failure(val message: String) : SegmentedUploadPreparationResult
}

/**
 * Analyze a raw PCM recording (unless retry state already froze a plan), copy
 * exact source-frame ranges to temporary PCM files, then encode each slice as
 * a standalone uploadable media file. Export is intentionally sequential: the
 * configured concurrency belongs to complete recognition workflows, not to
 * local FFmpeg process fan-out.
 */
class SegmentedUploadPreparer(
    private val pauseAnalyzer: PcmPauseAnalysisProvider = PcmPauseAnalyzer(),
) {
    fun prepare(request: SegmentedUploadPreparationRequest): SegmentedUploadPreparationResult {
        var directory: File? = null
        return try {
            throwIfCancelled(request.cancellation)
            val source = inspectPcmSource(request.rawInput, request.inputFormat)
            val snapshot = resolveSnapshot(request, source)
                ?: return SegmentedUploadPreparationResult.NoSpeech
            validateSnapshotForSource(snapshot, request.inputFormat, source)
            throwIfCancelled(request.cancellation)

            val operationIds = snapshot.plan.segments.indices.map(request.operationIdForSegment)
            require(operationIds.size == operationIds.toSet().size) {
                "Segment export operation IDs must be unique within one upload batch"
            }
            request.onOperationsPlanned?.invoke(operationIds)
            throwIfCancelled(request.cancellation)

            require(request.temporaryRoot.isDirectory || request.temporaryRoot.mkdirs()) {
                "Unable to create the segmented upload temporary directory"
            }
            directory = Files.createTempDirectory(request.temporaryRoot.toPath(), TEMP_DIRECTORY_PREFIX).toFile()
            val files = ArrayList<File>(snapshot.plan.segments.size)
            snapshot.plan.segments.forEachIndexed { index, interval ->
                throwIfCancelled(request.cancellation)
                val rawSegment = File(directory, "segment-${index.toString().padStart(6, '0')}.pcm")
                copyPcmInterval(
                    sourceFile = request.rawInput,
                    source = source,
                    inputFormat = request.inputFormat,
                    interval = interval,
                    destination = rawSegment,
                    cancellation = request.cancellation,
                )
                throwIfCancelled(request.cancellation)

                val media = File(directory, "segment-${index.toString().padStart(6, '0')}.${request.encodingPlan.extension}")
                when (
                    val result = request.encoder.encode(
                        SegmentMediaEncodeRequest(
                            operationId = operationIds[index],
                            rawInput = rawSegment,
                            inputFormat = request.inputFormat,
                            output = media,
                            encodingPlan = request.encodingPlan,
                            cancellation = request.cancellation,
                        ),
                    )
                ) {
                    SegmentMediaEncodeResult.Success -> {
                        require(media.isFile && media.length() > 0L) {
                            "Segment encoder reported success without producing segment ${index + 1}"
                        }
                        rawSegment.delete()
                        files += media
                    }
                    is SegmentMediaEncodeResult.Failure -> {
                        throw IllegalStateException(result.message.ifBlank { "Unable to encode segment ${index + 1}" })
                    }
                    SegmentMediaEncodeResult.Cancelled -> throw SegmentedUploadCancellationException()
                }
            }
            throwIfCancelled(request.cancellation)
            SegmentedUploadPreparationResult.Success(
                PreparedSegmentedUpload(
                    directory = requireNotNull(directory),
                    snapshot = snapshot,
                    files = files,
                    operationIds = operationIds,
                ),
            )
        } catch (_: SegmentedUploadCancellationException) {
            directory?.deleteRecursively()
            SegmentedUploadPreparationResult.Cancelled
        } catch (_: CancellationException) {
            directory?.deleteRecursively()
            SegmentedUploadPreparationResult.Cancelled
        } catch (error: Exception) {
            directory?.deleteRecursively()
            SegmentedUploadPreparationResult.Failure(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun resolveSnapshot(
        request: SegmentedUploadPreparationRequest,
        source: PcmSource,
    ): SegmentedUploadSnapshot? = when (val planSource = request.planSource) {
        is SegmentedUploadPlanSource.Frozen -> planSource.snapshot
        is SegmentedUploadPlanSource.Analyze -> {
            val analysis = pauseAnalyzer.analyze(
                rawInput = request.rawInput,
                inputFormat = request.inputFormat,
                minimumPauseDurationMillis = planSource.parameters.minimumPauseDurationMillis,
                cancellation = request.cancellation,
            )
            require(analysis.sourceRateHz == request.inputFormat.sampleRateHz) {
                "Pause analysis source sample rate does not match the PCM recording"
            }
            require(analysis.totalFrames == source.totalFrames) {
                "PCM source changed while analyzing pauses"
            }
            when (
                val outcome = buildSegmentPlan(
                    sourceRateHz = analysis.sourceRateHz,
                    totalFrames = analysis.totalFrames,
                    maxSegmentFrames = planSource.parameters.maximumSegmentFrames(analysis.sourceRateHz),
                    silenceIntervals = analysis.silenceIntervals,
                )
            ) {
                SegmentPlanOutcome.NoSpeech -> null
                is SegmentPlanOutcome.Plan -> SegmentedUploadSnapshot(planSource.parameters, outcome.value).also { snapshot ->
                    // Persist this before any local conversion: retry must
                    // retain boundaries even if FFmpeg fails part-way through.
                    request.onPlanFrozen?.invoke(snapshot)
                }
            }
        }
    }

    private fun validateSnapshotForSource(
        snapshot: SegmentedUploadSnapshot,
        inputFormat: Pcm16Format,
        source: PcmSource,
    ) {
        snapshot.plan.validate()
        require(snapshot.plan.sourceRateHz == inputFormat.sampleRateHz) {
            "Frozen segment plan sample rate does not match the PCM recording"
        }
        require(snapshot.plan.totalFrames == source.totalFrames) {
            "Frozen segment plan length does not match the PCM recording"
        }
    }

    private companion object {
        const val TEMP_DIRECTORY_PREFIX = "segmented-upload-"
    }
}

private data class PcmSource(
    val bytes: Long,
    val totalFrames: Long,
)

private fun inspectPcmSource(rawInput: File, inputFormat: Pcm16Format): PcmSource {
    require(rawInput.isFile) { "Original PCM recording is missing" }
    val bytes = rawInput.length()
    require(bytes >= 0L && bytes % inputFormat.bytesPerFrame == 0L) {
        "Original PCM recording does not end on a complete frame"
    }
    return PcmSource(bytes = bytes, totalFrames = bytes / inputFormat.bytesPerFrame)
}

private fun scanPcmFrames(
    rawInput: File,
    inputFormat: Pcm16Format,
    source: PcmSource,
    cancellation: SegmentedUploadCancellation,
    onFrame: (frame: Long, monoSample: Double) -> Unit,
) {
    throwIfCancelled(cancellation)
    require(rawInput.length() == source.bytes) { "PCM source changed while it was being read" }
    val framesPerBuffer = DEFAULT_SCAN_BUFFER_FRAMES
    val buffer = ByteArray(Math.multiplyExact(inputFormat.bytesPerFrame, framesPerBuffer))
    var frame = 0L
    FileInputStream(rawInput).use { input ->
        while (frame < source.totalFrames) {
            throwIfCancelled(cancellation)
            val frames = minOf(framesPerBuffer.toLong(), source.totalFrames - frame).toInt()
            val requestedBytes = frames * inputFormat.bytesPerFrame
            readFully(input, buffer, requestedBytes)
            var offset = 0
            repeat(frames) {
                onFrame(frame, downmixFrame(buffer, offset, inputFormat.channelCount))
                frame += 1L
                offset += inputFormat.bytesPerFrame
            }
        }
    }
    require(rawInput.length() == source.bytes) { "PCM source changed while it was being read" }
}

private fun downmixFrame(bytes: ByteArray, offset: Int, channelCount: Int): Double {
    var sum = 0.0
    repeat(channelCount) { channel ->
        val sampleOffset = offset + channel * Pcm16Format.BYTES_PER_SAMPLE
        val low = bytes[sampleOffset].toInt() and 0xff
        val high = bytes[sampleOffset + 1].toInt()
        val sample = ((high shl 8) or low).toShort().toInt()
        sum += sample.toDouble() / PCM16_NORMALIZATION
    }
    return sum / channelCount.toDouble()
}

private fun copyPcmInterval(
    sourceFile: File,
    source: PcmSource,
    inputFormat: Pcm16Format,
    interval: SourceInterval,
    destination: File,
    cancellation: SegmentedUploadCancellation,
) {
    require(interval.startFrame >= 0L && interval.endFrame > interval.startFrame) {
        "Segment export interval is invalid"
    }
    require(interval.endFrame <= source.totalFrames) { "Segment export interval exceeds the PCM recording" }
    throwIfCancelled(cancellation)
    require(sourceFile.length() == source.bytes) { "PCM source changed before segment export" }
    destination.parentFile?.mkdirs()
    destination.delete()
    val startByte = try {
        Math.multiplyExact(interval.startFrame, inputFormat.bytesPerFrame.toLong())
    } catch (error: ArithmeticException) {
        throw IllegalArgumentException("Segment start offset overflow", error)
    }
    var remaining = try {
        Math.multiplyExact(interval.frameCount, inputFormat.bytesPerFrame.toLong())
    } catch (error: ArithmeticException) {
        throw IllegalArgumentException("Segment length overflow", error)
    }
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    RandomAccessFile(sourceFile, "r").use { input ->
        input.seek(startByte)
        FileOutputStream(destination, false).use { output ->
            while (remaining > 0L) {
                throwIfCancelled(cancellation)
                val requested = minOf(remaining, buffer.size.toLong()).toInt()
                val read = input.read(buffer, 0, requested)
                if (read <= 0) throw IllegalStateException("Original PCM recording ended while exporting a segment")
                output.write(buffer, 0, read)
                remaining -= read.toLong()
            }
            output.flush()
        }
    }
    require(sourceFile.length() == source.bytes) { "PCM source changed while exporting a segment" }
}

private fun readFully(input: FileInputStream, buffer: ByteArray, byteCount: Int) {
    var offset = 0
    while (offset < byteCount) {
        val read = input.read(buffer, offset, byteCount - offset)
        if (read <= 0) throw IllegalStateException("Original PCM recording ended while it was being read")
        offset += read
    }
}

private fun throwIfCancelled(cancellation: SegmentedUploadCancellation) {
    if (cancellation.isCancellationRequested()) throw SegmentedUploadCancellationException()
}

private const val PCM16_NORMALIZATION = 32_768.0
private const val DEFAULT_SCAN_BUFFER_FRAMES = 4_096
private const val COPY_BUFFER_BYTES = 64 * 1_024
