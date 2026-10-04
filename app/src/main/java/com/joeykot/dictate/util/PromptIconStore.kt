package com.joeykot.dictate.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import com.caverock.androidsvg.SVG
import com.joeykot.dictate.R
import com.joeykot.dictate.i18n.AppStrings
import com.joeykot.dictate.model.PromptConfig
import java.io.File
import java.util.UUID

/** Shared by the prompt editor and the floating action menu. */
object PromptIconStore {
    data class Builtin(val id: String, val labelRes: Int, val fallbackLabel: String, val color: Int) {
        val label: String get() = AppStrings.get(labelRes, fallbackLabel)
    }
    data class ImportedIcon(val name: String, val drawable: Drawable)

    val builtins = listOf(
        Builtin("document", R.string.prompt_icon_document, "Document", 0xFF477DAD.toInt()),
        Builtin("sparkles", R.string.prompt_icon_sparkles, "Polish", 0xFF627B51.toInt()),
        Builtin("translate", R.string.prompt_icon_translate, "Translate", 0xFF82709B.toInt()),
        Builtin("checklist", R.string.prompt_icon_checklist, "To-do", 0xFF947153.toInt()),
        Builtin("chat", R.string.prompt_icon_chat, "Reply", 0xFF4D86A3.toInt()),
        Builtin("edit", R.string.prompt_icon_edit, "Write", 0xFF876795.toInt()),
        Builtin("tag", R.string.prompt_icon_tag, "Title", 0xFF487E99.toInt()),
        Builtin("book", R.string.prompt_icon_book, "Explain", 0xFF518967.toInt()),
    )

    fun load(context: Context, prompt: PromptConfig): Drawable {
        prompt.customIcon?.let { name ->
            runCatching { decode(context, file(context, name)) }.getOrNull()?.let { return it }
        }
        return BuiltinDrawable(builtins.firstOrNull { it.id == prompt.icon } ?: builtins.first())
    }

    /** Imports only after validation, leaving the currently saved icon untouched. */
    fun import(context: Context, uri: Uri): String = importDecoded(context, uri).name

    /** Returns the validated preview so callers do not decode the same file again on the UI thread. */
    fun importDecoded(context: Context, uri: Uri): ImportedIcon {
        val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            val data = stream.readBoundedBytes(MAX_FILE_BYTES + 1)
            require(data.size <= MAX_FILE_BYTES) { context.getString(R.string.prompt_icon_file_too_large) }
            data
        } ?: throw IllegalArgumentException(context.getString(R.string.prompt_icon_unreadable))
        require(bytes.isNotEmpty()) { context.getString(R.string.prompt_icon_empty) }
        val extension = when {
            bytes.size >= 8 && bytes[0] == 0x89.toByte() &&
                bytes.copyOfRange(1, 4).contentEquals("PNG".toByteArray()) -> "png"
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
            bytes.toString(Charsets.UTF_8).contains(Regex("<svg(?:\\s|>)", RegexOption.IGNORE_CASE)) -> "svg"
            else -> throw IllegalArgumentException(context.getString(R.string.prompt_icon_unsupported))
        }
        val directory = File(context.filesDir, "icons").apply { mkdirs() }
        val existingSize = directory.listFiles()?.sumOf { it.length() } ?: 0L
        require(existingSize + bytes.size <= MAX_TOTAL_BYTES) { context.getString(R.string.prompt_icons_too_large) }
        val destination = File(directory, "${UUID.randomUUID()}.$extension")
        try {
            destination.writeBytes(bytes)
            return ImportedIcon(destination.name, decode(context, destination))
        } catch (error: Exception) {
            destination.delete()
            throw IllegalArgumentException(context.getString(R.string.prompt_icon_read_error, error.message ?: context.getString(R.string.prompt_icon_invalid_image)), error)
        }
    }

    fun deleteIfUnused(context: Context, name: String?, prompts: List<PromptConfig>) {
        if (name != null && prompts.none { it.customIcon == name }) {
            runCatching { file(context, name).delete() }
        }
    }

    private fun file(context: Context, name: String): File {
        require(SAFE_FILENAME.matches(name)) { context.getString(R.string.prompt_icon_invalid_filename) }
        return File(File(context.filesDir, "icons"), name)
    }

    private fun decode(context: Context, source: File): Drawable {
        require(source.isFile && source.length() <= MAX_FILE_BYTES) { context.getString(R.string.prompt_icon_missing_or_large) }
        if (source.extension.equals("svg", ignoreCase = true)) {
            val svg = source.inputStream().use { SVG.getFromInputStream(it) }
            val size = (64 * context.resources.displayMetrics.density).toInt().coerceAtLeast(64)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawPicture(svg.renderToPicture(size, size))
            return BitmapDrawable(context.resources, bitmap)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { context.getString(R.string.prompt_icon_invalid_image) }
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (bounds.outWidth / inSampleSize > 256 || bounds.outHeight / inSampleSize > 256) {
                inSampleSize = if (inSampleSize <= 0) 2 else inSampleSize * 2
            }
        }
        val bitmap = BitmapFactory.decodeFile(source.path, options)
            ?: throw IllegalArgumentException(context.getString(R.string.prompt_icon_decode_failed))
        return BitmapDrawable(context.resources, bitmap)
    }

    // InputStream.readBytes() has no size limit; bound reads before allocating the next chunk.
    private fun java.io.InputStream.readBoundedBytes(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (output.size() < limit) {
            val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private class BuiltinDrawable(private val icon: Builtin) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / 48f, bounds.height() / 48f)
            paint.style = Paint.Style.FILL
            paint.color = icon.color
            canvas.drawCircle(24f, 24f, 23f, paint)
            paint.color = Color.WHITE
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            when (icon.id) {
                "document" -> {
                    canvas.drawRoundRect(16f, 13f, 32f, 35f, 1.5f, 1.5f, paint)
                    line(canvas, 20f, 19f, 28f, 19f)
                    line(canvas, 20f, 24f, 28f, 24f)
                    line(canvas, 20f, 29f, 25f, 29f)
                }
                "sparkles" -> {
                    star(canvas, 22f, 23f, 10f)
                    star(canvas, 33f, 15f, 4f)
                    star(canvas, 33f, 33f, 4f)
                }
                "translate" -> {
                    paint.style = Paint.Style.FILL
                    paint.textSize = 19f
                    canvas.drawText("文", 11f, 26f, paint)
                    paint.textSize = 16f
                    canvas.drawText("A", 26f, 35f, paint)
                }
                "checklist" -> {
                    canvas.drawRoundRect(14f, 14f, 34f, 34f, 2f, 2f, paint)
                    path(canvas, 19f to 24f, 23f to 28f, 30f to 20f)
                }
                "chat" -> {
                    path(canvas, 15f to 16f, 33f to 16f, 35f to 19f, 35f to 28f,
                        32f to 31f, 23f to 31f, 16f to 36f, 18f to 30f, 13f to 27f,
                        13f to 19f, 15f to 16f)
                    line(canvas, 19f, 23f, 29f, 23f)
                }
                "edit" -> {
                    path(canvas, 31f to 14f, 35f to 18f, 23f to 30f, 18f to 31f,
                        19f to 26f, 31f to 14f)
                    path(canvas, 15f to 20f, 13f to 20f, 13f to 35f, 29f to 35f, 29f to 32f)
                }
                "tag" -> {
                    path(canvas, 13f to 14f, 25f to 14f, 36f to 25f, 25f to 36f,
                        13f to 24f, 13f to 14f)
                    canvas.drawCircle(19f, 20f, 1.5f, paint)
                }
                "book" -> {
                    path(canvas, 24f to 16f, 19f to 13f, 12f to 13f, 12f to 32f,
                        19f to 32f, 24f to 35f, 29f to 32f, 36f to 32f, 36f to 13f,
                        29f to 13f, 24f to 16f, 24f to 35f)
                }
            }
            canvas.restoreToCount(save)
        }

        private fun line(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) =
            canvas.drawLine(x1, y1, x2, y2, paint)

        private fun path(canvas: Canvas, vararg points: Pair<Float, Float>) {
            canvas.drawPath(Path().apply {
                moveTo(points.first().first, points.first().second)
                points.drop(1).forEach { lineTo(it.first, it.second) }
            }, paint)
        }

        private fun star(canvas: Canvas, x: Float, y: Float, size: Float) {
            paint.style = Paint.Style.FILL
            canvas.drawPath(Path().apply {
                moveTo(x, y - size)
                quadTo(x + size * .25f, y - size * .25f, x + size, y)
                quadTo(x + size * .25f, y + size * .25f, x, y + size)
                quadTo(x - size * .25f, y + size * .25f, x - size, y)
                quadTo(x - size * .25f, y - size * .25f, x, y - size)
            }, paint)
            paint.style = Paint.Style.STROKE
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = 48
        override fun getIntrinsicHeight(): Int = 48
    }

    private val SAFE_FILENAME = Regex("[A-Za-z0-9_-]{1,128}\\.(?:png|jpg|jpeg|svg)")
    private const val MAX_FILE_BYTES = 1_048_576
    private const val MAX_TOTAL_BYTES = 8_388_608L
}
