package com.joeykot.dictate.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** A circular HSV picker: angle controls hue, radius controls saturation, and brightness is external. */
class CircularColorPickerView(
    context: Context,
    initialColor: Int,
) : View(context) {
    var onColorChanged: ((Int) -> Unit)? = null

    private val hsv = FloatArray(3)
    private val diskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val brightnessOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.argb(120, 0, 0, 0)
    }
    private val markerOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Color.WHITE
    }
    private val markerInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.BLACK
    }

    private var diskBitmap: Bitmap? = null
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f
    private var tracking = false

    var brightness: Float
        get() = hsv[2]
        set(value) {
            val normalized = value.coerceIn(0f, 1f)
            if (normalized == hsv[2]) return
            hsv[2] = normalized
            invalidate()
            dispatchColorChanged()
        }

    val color: Int
        get() = Color.HSVToColor(hsv) and 0xFFFFFF

    init {
        Color.colorToHSV(
            Color.rgb(
                (initialColor shr 16) and 0xFF,
                (initialColor shr 8) and 0xFF,
                initialColor and 0xFF,
            ),
            hsv,
        )
        isClickable = true
        contentDescription = "圆形调色盘"
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        centerX = width / 2f
        centerY = height / 2f
        radius = (minOf(width, height) / 2f - dp(MARKER_CLEARANCE_DP)).coerceAtLeast(0f)
        rebuildDisk()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = diskBitmap ?: return
        canvas.drawBitmap(bitmap, centerX - radius, centerY - radius, diskPaint)
        brightnessOverlayPaint.alpha = ((1f - hsv[2]) * 255f).toInt().coerceIn(0, 255)
        if (brightnessOverlayPaint.alpha > 0) {
            canvas.drawCircle(centerX, centerY, radius, brightnessOverlayPaint)
        }
        canvas.drawCircle(centerX, centerY, radius, borderPaint)

        val hueRadians = hsv[0] / 180f * PI
        val markerRadius = hsv[1] * radius
        val markerX = centerX + (cos(hueRadians) * markerRadius).toFloat()
        val markerY = centerY + (sin(hueRadians) * markerRadius).toFloat()
        canvas.drawCircle(markerX, markerY, dp(MARKER_RADIUS_DP), markerOuterPaint)
        canvas.drawCircle(markerX, markerY, dp(MARKER_RADIUS_DP), markerInnerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            tracking = updateSelection(event.x, event.y, allowOutside = false)
            tracking
        }
        MotionEvent.ACTION_MOVE -> {
            if (tracking) updateSelection(event.x, event.y, allowOutside = true)
            tracking
        }
        MotionEvent.ACTION_UP -> {
            if (tracking) {
                updateSelection(event.x, event.y, allowOutside = true)
                performClick()
            }
            tracking = false
            true
        }
        MotionEvent.ACTION_CANCEL -> {
            tracking = false
            true
        }
        else -> super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateSelection(x: Float, y: Float, allowOutside: Boolean): Boolean {
        if (radius <= 0f) return false
        val deltaX = x - centerX
        val deltaY = y - centerY
        val distance = hypot(deltaX, deltaY)
        if (!allowOutside && distance > radius) return false
        hsv[0] = ((atan2(deltaY.toDouble(), deltaX.toDouble()) * 180.0 / PI + 360.0) % 360.0)
            .toFloat()
        hsv[1] = (distance / radius).coerceIn(0f, 1f)
        invalidate()
        dispatchColorChanged()
        return true
    }

    private fun rebuildDisk() {
        if (radius <= 0f) {
            diskBitmap = null
            return
        }
        val diameter = (radius * 2f).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(diameter, diameter, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(diameter * diameter)
        val bitmapRadius = diameter / 2f
        val pixelHsv = FloatArray(3).apply { this[2] = 1f }
        for (y in 0 until diameter) {
            val deltaY = y + 0.5f - bitmapRadius
            for (x in 0 until diameter) {
                val deltaX = x + 0.5f - bitmapRadius
                val distance = hypot(deltaX, deltaY)
                val index = y * diameter + x
                pixels[index] = if (distance <= bitmapRadius) {
                    val hue = (atan2(deltaY.toDouble(), deltaX.toDouble()) * 180.0 / PI + 360.0) %
                        360.0
                    pixelHsv[0] = hue.toFloat()
                    pixelHsv[1] = (distance / bitmapRadius).coerceIn(0f, 1f)
                    Color.HSVToColor(pixelHsv)
                } else {
                    Color.TRANSPARENT
                }
            }
        }
        bitmap.setPixels(pixels, 0, diameter, 0, 0, diameter, diameter)
        diskBitmap?.recycle()
        diskBitmap = bitmap
    }

    private fun dispatchColorChanged() {
        onColorChanged?.invoke(color)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val MARKER_RADIUS_DP = 9f
        const val MARKER_CLEARANCE_DP = 12f
    }
}
