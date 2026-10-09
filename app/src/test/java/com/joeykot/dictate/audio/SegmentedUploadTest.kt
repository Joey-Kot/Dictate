package com.joeykot.dictate.audio

import com.joeykot.dictate.model.AudioCodec
import com.joeykot.dictate.model.AudioConfig
import com.joeykot.dictate.model.AudioContainer
import com.joeykot.dictate.model.Pcm16Format
import com.joeykot.dictate.model.SegmentedUploadConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SegmentedUploadTest {
    private val sourceRateHz = 48_000

    @Test
    fun frozenParametersRejectConcurrencyAboveTheAndroidSafeUpperBound() {
        assertThrows(IllegalArgumentException::class.java) {
            SegmentedUploadParameters(
                maximumSegmentLengthSeconds = 1,
                minimumPauseDurationMillis = 200,
                concurrency = SegmentedUploadConfig.MAX_CONCURRENCY + 1,
            )
        }
    }

    @Test
    fun plannerMatchesTheWindowsPauseAndHardCutRules() {
        assertPlan(23, 5, emptyList(), listOf(0 to 5, 5 to 10, 10 to 15, 15 to 20, 20 to 23))
        assertPlan(180, 100, listOf(40 to 60), listOf(0 to 60, 60 to 160, 160 to 180))
        assertPlan(180, 100, listOf(20 to 30, 70 to 90), listOf(0 to 90, 90 to 180))
        assertPlan(200, 100, listOf(90 to 120), listOf(0 to 100, 100 to 200))
        assertPlan(100, 100, listOf(40 to 60), listOf(0 to 100))
        assertPlan(160, 100, listOf(0 to 10, 60 to 70, 150 to 160), listOf(0 to 70, 70 to 160))
        assertPlan(260, 60, listOf(10 to 200), listOf(0 to 60, 60 to 120, 120 to 180, 180 to 240, 240 to 260))
    }

    @Test
    fun plannerNormalizesMalformedAndAdjacentPauseReportsWithoutLosingFrames() {
        assertPlan(
            totalFrames = 250,
            maxSegmentFrames = 100,
            pauses = listOf(190 to 300, 40 to 60, 20 to 40, 60 to 80, 88 to 88, 180 to 190),
            expected = listOf(0 to 80, 80 to 180, 180 to 250),
        )
        assertPlan(
            totalFrames = 35,
            maxSegmentFrames = 10,
            pauses = listOf(9 to 9, 20 to 10, 99 to 100),
            expected = listOf(0 to 10, 10 to 20, 20 to 30, 30 to 35),
        )
    }

    @Test
    fun fullySilentInputDoesNotProduceAnUploadPlan() {
        val result = buildSegmentPlan(
            sourceRateHz = sourceRateHz,
            totalFrames = 100,
            maxSegmentFrames = 10,
            silenceIntervals = listOf(SourceInterval(50, 100), SourceInterval(0, 25), SourceInterval(25, 50)),
        )
        assertEquals(SegmentPlanOutcome.NoSpeech, result)
        assertEquals(
            SegmentPlanOutcome.NoSpeech,
            buildSegmentPlan(sourceRateHz, 0, 10, emptyList()),
        )
    }

    @Test
    fun pauseAnalyzerTreatsShortAllSilentPcmAsNoSpeech() = withTemporaryDirectory { root ->
        val format = Pcm16Format(sampleRateHz = 16_000, channelCount = 1)
        val raw = File(root, "all-silent.pcm")
        writePcm(raw, IntArray(1_600))

        val analysis = PcmPauseAnalyzer().analyze(raw, format, 700, SegmentedUploadCancellation.NONE)

        assertEquals(listOf(SourceInterval(0, 1_600)), analysis.silenceIntervals)
        assertEquals(
            SegmentPlanOutcome.NoSpeech,
            buildSegmentPlan(format.sampleRateHz, analysis.totalFrames, 16_000, analysis.silenceIntervals),
        )
    }

    @Test
    fun pauseAnalyzerRoundsMinimumPauseFramesUpAndUsesAdaptivePeakThresholds() = withTemporaryDirectory { root ->
        val format = Pcm16Format(sampleRateHz = 1_000, channelCount = 1)
        val raw = File(root, "adaptive.pcm")
        // With a near-full-scale peak, the first Windows-compatible candidate
        // is around -18 dB. The quiet middle is silence for that threshold,
        // but not for the fixed fallback thresholds.
        writePcm(raw, intArrayOf(32_767, 2_000, 2_000, 32_767))

        val analysis = PcmPauseAnalyzer().analyze(raw, format, 2, SegmentedUploadCancellation.NONE)
        assertEquals(listOf(SourceInterval(1, 3)), analysis.silenceIntervals)

        val rounded = PcmPauseAnalyzer().analyze(raw, format, 3, SegmentedUploadCancellation.NONE)
        assertTrue(rounded.silenceIntervals.isEmpty())
    }

    @Test
    fun preparerExportsIndependentCompleteFilesAndPreservesEveryPcmByte() = withTemporaryDirectory { root ->
        val format = Pcm16Format(sampleRateHz = 10, channelCount = 1)
        val samples = intArrayOf(10_000, 10_000, 10_000, 10_000, 10_000, 10_000, 0, 0) +
            IntArray(17) { 10_000 }
        val raw = File(root, "source.pcm")
        writePcm(raw, samples)
        val sourceBytes = raw.readBytes()
        val encoder = CopyingEncoder()
        var frozen: SegmentedUploadSnapshot? = null
        var plannedIds: List<Long>? = null

        val result = SegmentedUploadPreparer().prepare(
            SegmentedUploadPreparationRequest(
                rawInput = raw,
                inputFormat = format,
                encodingPlan = pcmEncodingPlan(format),
                temporaryRoot = root,
                planSource = SegmentedUploadPlanSource.Analyze(
                    SegmentedUploadParameters(
                        maximumSegmentLengthSeconds = 1,
                        minimumPauseDurationMillis = 200,
                        concurrency = 2,
                    ),
                ),
                operationIdForSegment = { 4_000L + it },
                encoder = encoder,
                onPlanFrozen = { frozen = it },
                onOperationsPlanned = { plannedIds = it },
            ),
        )

        val upload = assertSuccess(result)
        assertEquals(
            listOf(SourceInterval(0, 8), SourceInterval(8, 18), SourceInterval(18, 25)),
            upload.snapshot.plan.segments,
        )
        assertEquals(listOf(4_000L, 4_001L, 4_002L), upload.operationIds)
        assertEquals(upload.operationIds, plannedIds)
        assertEquals(upload.snapshot, frozen)
        assertEquals(upload.operationIds, encoder.requests.map { it.operationId })
        assertTrue(encoder.requests.all { !it.rawInput.exists() })
        assertArrayEquals(sourceBytes, upload.files.flatMap { it.readBytes().asIterable() }.toByteArray())

        val restored = SegmentedUploadSnapshotCodec.decode(SegmentedUploadSnapshotCodec.encode(upload.snapshot))
        assertEquals(upload.snapshot, restored)
        upload.cleanup()
        assertFalse(upload.directory.exists())
    }

    @Test
    fun frozenSnapshotReusesOriginalBoundariesWithoutReanalyzing() = withTemporaryDirectory { root ->
        val format = Pcm16Format(sampleRateHz = 10, channelCount = 1)
        val raw = File(root, "source.pcm")
        writePcm(raw, IntArray(25) { 10_000 })
        val snapshot = SegmentedUploadSnapshot(
            parameters = SegmentedUploadParameters(1, 200, 3),
            plan = SegmentPlan(
                sourceRateHz = 10,
                totalFrames = 25,
                maxSegmentFrames = 10,
                segments = listOf(SourceInterval(0, 8), SourceInterval(8, 18), SourceInterval(18, 25)),
            ),
        )
        val analyzer = PcmPauseAnalysisProvider { _, _, _, _ ->
            throw AssertionError("Frozen retries must not rerun pause analysis")
        }
        val encoder = CopyingEncoder()

        val result = SegmentedUploadPreparer(analyzer).prepare(
            SegmentedUploadPreparationRequest(
                rawInput = raw,
                inputFormat = format,
                encodingPlan = pcmEncodingPlan(format),
                temporaryRoot = root,
                planSource = SegmentedUploadPlanSource.Frozen(snapshot),
                operationIdForSegment = { 7_000L + it },
                encoder = encoder,
            ),
        )

        val upload = assertSuccess(result)
        assertEquals(snapshot, upload.snapshot)
        assertEquals(listOf(8L, 10L, 7L), upload.files.map { it.length() / format.bytesPerFrame })
        upload.cleanup()
    }

    @Test
    fun frozenSnapshotRejectsAChangedSourceFormatOrFrameCountBeforeFfmpegStarts() = withTemporaryDirectory { root ->
        val recordedFormat = Pcm16Format(sampleRateHz = 10, channelCount = 1)
        val raw = File(root, "source.pcm")
        writePcm(raw, IntArray(20) { 10_000 })
        val snapshot = SegmentedUploadSnapshot(
            SegmentedUploadParameters(1, 200, 1),
            SegmentPlan(10, 25, 10, listOf(SourceInterval(0, 10), SourceInterval(10, 20), SourceInterval(20, 25))),
        )
        var called = false
        val request = SegmentedUploadPreparationRequest(
            rawInput = raw,
            inputFormat = recordedFormat,
            encodingPlan = pcmEncodingPlan(recordedFormat),
            temporaryRoot = root,
            planSource = SegmentedUploadPlanSource.Frozen(snapshot),
            operationIdForSegment = { 7_500L + it },
            encoder = SegmentMediaEncoder {
                called = true
                SegmentMediaEncodeResult.Success
            },
        )

        val frameMismatch = SegmentedUploadPreparer().prepare(request)
        assertFalse(called)
        assertTrue((frameMismatch as SegmentedUploadPreparationResult.Failure).message.contains("length"))

        val rateMismatch = SegmentedUploadPreparer().prepare(
            request.copy(
                inputFormat = Pcm16Format(sampleRateHz = 20, channelCount = 1),
                encodingPlan = pcmEncodingPlan(Pcm16Format(sampleRateHz = 20, channelCount = 1)),
            ),
        )
        assertFalse(called)
        assertTrue((rateMismatch as SegmentedUploadPreparationResult.Failure).message.contains("sample rate"))
        assertTrue(root.listFiles().orEmpty().all { it == raw })
    }

    @Test
    fun newlyFrozenPlanSurvivesAnEncoderFailureWhileTemporaryFilesAreRemoved() = withTemporaryDirectory { root ->
        val format = Pcm16Format(sampleRateHz = 10, channelCount = 1)
        val raw = File(root, "source.pcm")
        writePcm(raw, IntArray(10) { 10_000 })
        var frozen: SegmentedUploadSnapshot? = null

        val result = SegmentedUploadPreparer().prepare(
            SegmentedUploadPreparationRequest(
                rawInput = raw,
                inputFormat = format,
                encodingPlan = pcmEncodingPlan(format),
                temporaryRoot = root,
                planSource = SegmentedUploadPlanSource.Analyze(SegmentedUploadParameters(1, 200, 1)),
                operationIdForSegment = { 7_800L + it },
                encoder = SegmentMediaEncoder { SegmentMediaEncodeResult.Failure("expected test failure") },
                onPlanFrozen = { frozen = it },
            ),
        )

        assertTrue(result is SegmentedUploadPreparationResult.Failure)
        assertEquals(listOf(SourceInterval(0, 10)), frozen?.plan?.segments)
        assertTrue(root.listFiles().orEmpty().all { it == raw })
    }

    @Test
    fun cancellationAfterTheFirstExportCleansTheWholeAttemptDirectory() = withTemporaryDirectory { root ->
        val format = Pcm16Format(sampleRateHz = 10, channelCount = 1)
        val raw = File(root, "source.pcm")
        writePcm(raw, IntArray(25) { 10_000 })
        val cancelled = AtomicBoolean(false)
        val encoder = object : SegmentMediaEncoder {
            override fun encode(request: SegmentMediaEncodeRequest): SegmentMediaEncodeResult {
                request.output.writeBytes(request.rawInput.readBytes())
                cancelled.set(true)
                return SegmentMediaEncodeResult.Success
            }
        }
        val snapshot = SegmentedUploadSnapshot(
            SegmentedUploadParameters(1, 200, 1),
            SegmentPlan(10, 25, 10, listOf(SourceInterval(0, 10), SourceInterval(10, 20), SourceInterval(20, 25))),
        )

        val result = SegmentedUploadPreparer().prepare(
            SegmentedUploadPreparationRequest(
                rawInput = raw,
                inputFormat = format,
                encodingPlan = pcmEncodingPlan(format),
                temporaryRoot = root,
                planSource = SegmentedUploadPlanSource.Frozen(snapshot),
                operationIdForSegment = { 8_000L + it },
                encoder = encoder,
                cancellation = SegmentedUploadCancellation { cancelled.get() },
            ),
        )

        assertEquals(SegmentedUploadPreparationResult.Cancelled, result)
        assertTrue(root.listFiles().orEmpty().all { it == raw })
    }

    @Test
    fun duplicateOperationIdsAreRejectedBeforeFfmpegStarts() = withTemporaryDirectory { root ->
        val format = Pcm16Format(sampleRateHz = 10, channelCount = 1)
        val raw = File(root, "source.pcm")
        writePcm(raw, IntArray(20) { 10_000 })
        var called = false
        val snapshot = SegmentedUploadSnapshot(
            SegmentedUploadParameters(1, 200, 1),
            SegmentPlan(10, 20, 10, listOf(SourceInterval(0, 10), SourceInterval(10, 20))),
        )
        val result = SegmentedUploadPreparer().prepare(
            SegmentedUploadPreparationRequest(
                rawInput = raw,
                inputFormat = format,
                encodingPlan = pcmEncodingPlan(format),
                temporaryRoot = root,
                planSource = SegmentedUploadPlanSource.Frozen(snapshot),
                operationIdForSegment = { 42L },
                encoder = SegmentMediaEncoder {
                    called = true
                    SegmentMediaEncodeResult.Success
                },
            ),
        )

        assertFalse(called)
        assertTrue((result as SegmentedUploadPreparationResult.Failure).message.contains("unique"))
        assertTrue(root.listFiles().orEmpty().all { it == raw })
    }

    private fun assertPlan(
        totalFrames: Long,
        maxSegmentFrames: Long,
        pauses: List<Pair<Int, Int>>,
        expected: List<Pair<Int, Int>>,
    ) {
        val result = buildSegmentPlan(
            sourceRateHz = sourceRateHz,
            totalFrames = totalFrames,
            maxSegmentFrames = maxSegmentFrames,
            silenceIntervals = pauses.map { (start, end) -> SourceInterval(start.toLong(), end.toLong()) },
        )
        val plan = (result as SegmentPlanOutcome.Plan).value
        assertEquals(
            expected.map { (start, end) -> SourceInterval(start.toLong(), end.toLong()) },
            plan.segments,
        )
        plan.validate()
    }

    private fun assertSuccess(result: SegmentedUploadPreparationResult): PreparedSegmentedUpload =
        (result as? SegmentedUploadPreparationResult.Success)?.upload
            ?: throw AssertionError("Expected preparation success, got $result")

    private fun pcmEncodingPlan(format: Pcm16Format): AudioEncodingPlan = AudioEncodingPlan.resolve(
        AudioConfig(codec = AudioCodec.PCM, container = AudioContainer.WAV),
        format,
    )

    private fun writePcm(file: File, samples: IntArray) {
        FileOutputStream(file).use { output ->
            samples.forEach { value ->
                val sample = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output.write(sample and 0xff)
                output.write((sample ushr 8) and 0xff)
            }
        }
    }

    private inline fun withTemporaryDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("dictate-segment-test-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private class CopyingEncoder : SegmentMediaEncoder {
        val requests = mutableListOf<SegmentMediaEncodeRequest>()

        override fun encode(request: SegmentMediaEncodeRequest): SegmentMediaEncodeResult {
            requests += request
            request.output.writeBytes(request.rawInput.readBytes())
            return SegmentMediaEncodeResult.Success
        }
    }
}
