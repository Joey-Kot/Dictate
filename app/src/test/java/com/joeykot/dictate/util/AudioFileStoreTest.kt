package com.joeykot.dictate.util

import android.content.Context
import android.content.ContextWrapper
import com.joeykot.dictate.model.Pcm16Format
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class AudioFileStoreTest {
    private val roots = mutableListOf<File>()

    @After
    fun tearDown() {
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun segmentedSnapshotBelongsOnlyToTheCurrentLastRecording() {
        val store = newStore()
        val first = promote(store, jobId = 1L)

        assertTrue(store.saveLastRecordingSegmentedUploadSnapshot(first, "{\"version\":1}"))
        assertEquals("{\"version\":1}", store.lastRecordingSegmentedUploadSnapshot(first))

        val replacement = promote(store, jobId = 2L)
        assertNull(store.lastRecordingSegmentedUploadSnapshot(replacement))
    }

    @Test
    fun missingSegmentedSnapshotIsLeftAbsent() {
        val store = newStore()
        val recording = promote(store, jobId = 1L)
        val sidecar = snapshotFile(recording)

        assertFalse(sidecar.exists())
        assertNull(store.lastRecordingSegmentedUploadSnapshot(recording))
        assertFalse(sidecar.exists())
    }

    @Test
    fun invalidSegmentedSnapshotFilesAreRemovedWhenRead() {
        val store = newStore()
        val recording = promote(store, jobId = 1L)
        val sidecar = snapshotFile(recording)

        sidecar.writeText("")
        assertNull(store.lastRecordingSegmentedUploadSnapshot(recording))
        assertFalse(sidecar.exists())

        sidecar.writeBytes(ByteArray(4 * 1024 * 1024 + 1) { 1 })
        assertNull(store.lastRecordingSegmentedUploadSnapshot(recording))
        assertFalse(sidecar.exists())

        assertTrue(sidecar.mkdir())
        assertNull(store.lastRecordingSegmentedUploadSnapshot(recording))
        assertFalse(sidecar.exists())
    }

    @Test
    fun conditionalSnapshotClearCannotDeleteANewerAtomicWrite() {
        val store = newStore()
        val recording = promote(store, jobId = 1L)
        val staleSnapshot = "{\"version\":1,\"generation\":1}"
        val currentSnapshot = "{\"version\":1,\"generation\":2}"

        assertTrue(store.saveLastRecordingSegmentedUploadSnapshot(recording, staleSnapshot))
        assertTrue(store.saveLastRecordingSegmentedUploadSnapshot(recording, currentSnapshot))

        assertFalse(store.clearLastRecordingSegmentedUploadSnapshotIfUnchanged(recording, staleSnapshot))
        assertEquals(currentSnapshot, store.lastRecordingSegmentedUploadSnapshot(recording))

        assertTrue(store.clearLastRecordingSegmentedUploadSnapshotIfUnchanged(recording, currentSnapshot))
        assertNull(store.lastRecordingSegmentedUploadSnapshot(recording))
    }

    @Test
    fun temporaryCleanupRecursivelyRemovesAbandonedSegmentDirectories() {
        val store = newStore()
        val abandoned = File(store.segmentedUploadTemporaryRoot(), "segmented-upload-abandoned").apply { mkdirs() }
        File(abandoned, "segment-000000.wav").writeBytes(byteArrayOf(1, 2, 3))

        store.cleanupTemporaryFiles()

        assertFalse(abandoned.exists())
    }

    private fun promote(store: AudioFileStore, jobId: Long): AudioFileStore.RawRecording {
        val raw = store.newRawFile(jobId).apply { writeBytes(ByteArray(6_400) { 7 }) }
        return checkNotNull(store.promoteToLast(raw, Pcm16Format.LEGACY_MONO_16_KHZ))
    }

    private fun snapshotFile(recording: AudioFileStore.RawRecording): File =
        File(recording.file.parentFile, "${recording.file.name}.segmented-upload.json")

    private fun newStore(): AudioFileStore {
        val root = Files.createTempDirectory("dictate-audio-store-test-").toFile()
        roots += root
        return AudioFileStore(TestContext(RuntimeEnvironment.getApplication(), root))
    }

    private class TestContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }

        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    }
}
