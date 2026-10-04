package com.joeykot.dictate.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.joeykot.dictate.model.PromptConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PromptIconStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val imports = mutableListOf<String>()
    private val sources = mutableListOf<File>()

    @After
    fun cleanUp() {
        imports.forEach { PromptIconStore.deleteIfUnused(context, it, emptyList()) }
        sources.forEach { it.delete() }
    }

    @Test
    fun svgPngAndJpgAreImportedAndRetainedWhileReferenced() {
        val svg = File.createTempFile("icon", ".svg", context.cacheDir).also {
            sources += it
            it.writeText("""<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24"><rect width="24" height="24" fill="blue"/></svg>""")
        }
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        val png = File.createTempFile("icon", ".png", context.cacheDir).also {
            sources += it
            it.outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
        }
        val jpg = File.createTempFile("icon", ".jpg", context.cacheDir).also {
            sources += it
            it.outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output) }
        }
        listOf(svg, png, jpg).forEach { source ->
            val name = PromptIconStore.import(context, Uri.fromFile(source)).also { imports += it }
            val prompt = PromptConfig(customIcon = name)
            assertNotNull(PromptIconStore.load(context, prompt))
            PromptIconStore.deleteIfUnused(context, name, listOf(prompt))
            assertTrue(File(context.filesDir, "icons/$name").isFile)
            PromptIconStore.deleteIfUnused(context, name, emptyList())
            assertTrue(!File(context.filesDir, "icons/$name").exists())
            assertTrue(source.isFile)
        }
    }

    @Test
    fun missingCustomIconFallsBackToBuiltin() {
        val drawable = PromptIconStore.load(context, PromptConfig(customIcon = "missing.svg", icon = "chat"))
        assertTrue(drawable.intrinsicWidth > 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedFileIsRejectedBeforeImport() {
        val source = File.createTempFile("large-icon", ".png", context.cacheDir).also {
            sources += it
            it.writeBytes(ByteArray(1_048_577))
        }
        PromptIconStore.import(context, Uri.fromFile(source))
    }

    @Test
    fun failedSvgDecodeDoesNotLeaveAnImportedFile() {
        val directory = File(context.filesDir, "icons")
        val existing = directory.listFiles().orEmpty().map { it.name }.toSet()
        val source = File.createTempFile("invalid-icon", ".svg", context.cacheDir).also {
            sources += it
            it.writeText("""<svg xmlns="http://www.w3.org/2000/svg"><rect""")
        }

        try {
            PromptIconStore.importDecoded(context, Uri.fromFile(source))
            fail("Malformed SVG must not produce an imported icon")
        } catch (_: IllegalArgumentException) {
            assertEquals(existing, directory.listFiles().orEmpty().map { it.name }.toSet())
            assertTrue(source.isFile)
        }
    }
}
