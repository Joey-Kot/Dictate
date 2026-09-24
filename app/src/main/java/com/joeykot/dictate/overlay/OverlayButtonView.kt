package com.joeykot.dictate.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.joeykot.dictate.job.VoiceJobController
import com.joeykot.dictate.model.DisplayConfig
import com.joeykot.dictate.model.JobState
import com.joeykot.dictate.model.JobUiState
import com.joeykot.dictate.settings.SettingsRepository
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class OverlayButtonView(
    context: Context,
    private val voiceJobs: VoiceJobController,
    private val settingsRepository: SettingsRepository,
    private val dragDelegate: DragDelegate,
) : View(context) {
    interface DragDelegate {
        fun onDragStarted()

        fun onDragMoved(deltaX: Float, deltaY: Float)

        fun onDragFinished()
    }

    private class PendingTap(
        val releasedAt: Long,
        val state: JobState,
        val doubleTapMs: Long,
    ) {
        lateinit var action: Runnable
    }

    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val density = resources.displayMetrics.density
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = density * 3f
        style = Paint.Style.STROKE
    }

    private var jobUiState = JobUiState()
    private var downRawX = 0f
    private var downRawY = 0f
    private var downAt = 0L
    private var downState = JobState.IDLE
    private var downLongPressMs = 1_500L
    private var downDoubleTapMs = 500L
    private var dragging = false
    private var gestureCancelled = false
    private var pendingTap: PendingTap? = null
    private var secondTapCandidate = false
    private var smoothedAmplitude = 0f
    private var displayConfig = DisplayConfig()

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        updateElevation()
    }

    fun setDisplayConfig(config: DisplayConfig) {
        displayConfig = config.normalized()
        updateElevation()
        invalidate()
    }

    fun setJobUiState(state: JobUiState) {
        if (state.state != jobUiState.state) {
            cancelPendingTap()
        }
        jobUiState = state
        smoothedAmplitude = smoothedAmplitude * 0.65f + state.amplitude * 0.35f
        invalidate()
    }

    fun cancelPendingGestures() {
        cancelPendingTap()
        gestureCancelled = true
        if (dragging) dragDelegate.onDragFinished()
        dragging = false
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resolveExpiredPendingTap(event.eventTime)
                downRawX = event.rawX
                downRawY = event.rawY
                downAt = event.eventTime
                downState = jobUiState.state
                val interaction = settingsRepository.get().interaction
                downLongPressMs = interaction.longPressMs
                downDoubleTapMs = interaction.doubleTapMs
                dragging = false
                gestureCancelled = false
                suspendPendingTapForSecondPress(event.eventTime, downState)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - downRawX
                val deltaY = event.rawY - downRawY
                if (!gestureCancelled && !dragging && hypot(deltaX, deltaY) > touchSlop) {
                    dragging = true
                    cancelPendingTap()
                    dragDelegate.onDragStarted()
                }
                if (dragging) dragDelegate.onDragMoved(deltaX, deltaY)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    dragDelegate.onDragFinished()
                } else if (!gestureCancelled) {
                    // Classify only after release so a late move can still outrank a long press.
                    val heldFor = (event.eventTime - downAt).coerceAtLeast(0L)
                    if (heldFor >= downLongPressMs) {
                        cancelPendingTap()
                        voiceJobs.handleLongPress(downState)
                    } else {
                        handleShortTap(event.eventTime, downDoubleTapMs, downState)
                    }
                }
                gestureCancelled = false
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                gestureCancelled = true
                cancelPendingTap()
                if (dragging) dragDelegate.onDragFinished()
                dragging = false
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                gestureCancelled = true
                cancelPendingTap()
                if (dragging) dragDelegate.onDragFinished()
                dragging = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = minOf(width, height) * 0.46f
        val now = SystemClock.uptimeMillis()

        val palette = displayConfig.effectivePalette()
        val baseColor = when (jobUiState.state) {
            JobState.IDLE -> Color.rgb(95, 99, 104)
            JobState.RECORDING -> rgb(palette.recordingColor)
            JobState.PAUSED -> rgb(palette.pausedColor)
            JobState.TRANSCODING,
            JobState.REQUESTING,
            JobState.RETRY_WAITING,
            -> rgb(palette.processingColor)
        }
        val processing = jobUiState.state in setOf(
            JobState.TRANSCODING,
            JobState.REQUESTING,
            JobState.RETRY_WAITING,
        )
        val breathing = if (processing) {
            0.82f + 0.18f * ((sin(now / 750.0 * 2.0 * PI) + 1.0) / 2.0).toFloat()
        } else {
            1f
        }
        val stateOpacity = if (jobUiState.state == JobState.IDLE) 140f / 255f else 1f
        circlePaint.color = baseColor
        circlePaint.alpha = (255f * stateOpacity * displayConfig.buttonOpacity).toInt().coerceIn(0, 255)
        circlePaint.style = Paint.Style.FILL
        canvas.drawCircle(centerX, centerY, radius * breathing, circlePaint)

        symbolPaint.color = if (isLightColor(baseColor)) Color.rgb(32, 33, 36) else Color.WHITE
        symbolPaint.alpha = 255

        when (jobUiState.state) {
            JobState.IDLE -> drawMicrophone(canvas, centerX, centerY, radius)
            JobState.RECORDING -> drawWaveform(canvas, centerX, centerY, radius, now)
            JobState.PAUSED -> drawPause(canvas, centerX, centerY, radius)
            JobState.TRANSCODING,
            JobState.REQUESTING,
            JobState.RETRY_WAITING,
            -> drawProcessing(canvas, centerX, centerY, radius, breathing)
        }

        if (jobUiState.state == JobState.RECORDING || processing) postInvalidateOnAnimation()
    }

    private fun handleShortTap(releasedAt: Long, doubleTapMs: Long, stateAtRelease: JobState) {
        if (secondTapCandidate) {
            val firstTap = pendingTap
            clearPendingTap()
            if (firstTap != null) {
                if (releasedAt - firstTap.releasedAt <= firstTap.doubleTapMs) {
                    voiceJobs.handleDoubleTap(firstTap.state)
                } else {
                    dispatchSingleTap(firstTap.state)
                }
            }
            return
        }

        cancelPendingTap()
        val tap = PendingTap(releasedAt, stateAtRelease, doubleTapMs)
        tap.action = Runnable {
            if (pendingTap !== tap || secondTapCandidate) return@Runnable
            pendingTap = null
            dispatchSingleTap(tap.state)
        }
        pendingTap = tap
        handler.postDelayed(tap.action, doubleTapMs)
    }

    private fun suspendPendingTapForSecondPress(pressedAt: Long, stateAtPress: JobState) {
        val firstTap = pendingTap ?: run {
            secondTapCandidate = false
            return
        }
        val elapsed = pressedAt - firstTap.releasedAt
        if (firstTap.state != stateAtPress || elapsed !in 0L..firstTap.doubleTapMs) {
            cancelPendingTap()
            return
        }
        handler.removeCallbacks(firstTap.action)
        secondTapCandidate = true
    }

    private fun resolveExpiredPendingTap(now: Long) {
        val tap = pendingTap ?: return
        if (!secondTapCandidate && now - tap.releasedAt > tap.doubleTapMs) {
            handler.removeCallbacks(tap.action)
            pendingTap = null
            dispatchSingleTap(tap.state)
        }
    }

    private fun dispatchSingleTap(expectedState: JobState) {
        performClick()
        voiceJobs.handleSingleTap(expectedState)
    }

    private fun cancelPendingTap() {
        pendingTap?.let { handler.removeCallbacks(it.action) }
        clearPendingTap()
    }

    private fun clearPendingTap() {
        pendingTap = null
        secondTapCandidate = false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun drawMicrophone(canvas: Canvas, x: Float, y: Float, radius: Float) {
        symbolPaint.style = Paint.Style.STROKE
        symbolPaint.strokeWidth = density * 2.7f * displayConfig.buttonScale
        // The microphone's visible bounds extend farther below its drawing origin than above it.
        // Raise the whole symbol by half of that difference so it is centered in the button.
        val centeredY = y - radius * 0.11f
        val mic = RectF(
            x - radius * 0.22f,
            centeredY - radius * 0.48f,
            x + radius * 0.22f,
            centeredY + radius * 0.18f,
        )
        canvas.drawRoundRect(mic, radius * 0.22f, radius * 0.22f, symbolPaint)
        canvas.drawArc(
            RectF(
                x - radius * 0.42f,
                centeredY - radius * 0.15f,
                x + radius * 0.42f,
                centeredY + radius * 0.46f,
            ),
            0f,
            180f,
            false,
            symbolPaint,
        )
        val stemTop = centeredY + radius * 0.46f
        val stemBottom = centeredY + radius * 0.7f
        // A butt cap lets the stem meet the outer arc cleanly without a round bump inside it.
        symbolPaint.strokeCap = Paint.Cap.BUTT
        canvas.drawLine(x, stemTop, x, stemBottom, symbolPaint)
        symbolPaint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(
            x - radius * 0.23f,
            stemBottom,
            x + radius * 0.23f,
            stemBottom,
            symbolPaint,
        )
    }

    private fun drawWaveform(canvas: Canvas, x: Float, y: Float, radius: Float, now: Long) {
        symbolPaint.style = Paint.Style.STROKE
        symbolPaint.strokeWidth = density * 3f * displayConfig.buttonScale
        val animated = 0.18f + smoothedAmplitude.coerceAtLeast(0.04f) * 1.8f
        val spacing = radius * 0.28f
        for (index in -2..2) {
            val phase = now / 160.0 + index * 0.8
            val variation = 0.55f + 0.45f * ((sin(phase) + 1.0) / 2.0).toFloat()
            val halfHeight = radius * (animated * variation).coerceIn(0.16f, 0.72f)
            val barX = x + index * spacing
            canvas.drawLine(barX, y - halfHeight, barX, y + halfHeight, symbolPaint)
        }
    }

    private fun drawPause(canvas: Canvas, x: Float, y: Float, radius: Float) {
        symbolPaint.style = Paint.Style.FILL
        val width = radius * 0.22f
        val gap = radius * 0.18f
        val top = y - radius * 0.52f
        val bottom = y + radius * 0.52f
        canvas.drawRoundRect(RectF(x - gap - width, top, x - gap, bottom), width / 3, width / 3, symbolPaint)
        canvas.drawRoundRect(RectF(x + gap, top, x + gap + width, bottom), width / 3, width / 3, symbolPaint)
    }

    private fun drawProcessing(canvas: Canvas, x: Float, y: Float, radius: Float, breathing: Float) {
        symbolPaint.style = Paint.Style.STROKE
        symbolPaint.strokeWidth = density * 3f * displayConfig.buttonScale
        symbolPaint.alpha = (200 + 55 * breathing).toInt().coerceIn(0, 255)
        canvas.drawCircle(x, y, radius * (0.25f + 0.16f * breathing), symbolPaint)
        symbolPaint.alpha = 255
    }

    private fun updateElevation() {
        elevation = density * 8f * displayConfig.buttonScale
    }

    private fun rgb(color: Int): Int = Color.rgb(
        (color shr 16) and 0xFF,
        (color shr 8) and 0xFF,
        color and 0xFF,
    )

    private fun isLightColor(color: Int): Boolean {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return red * 299 + green * 587 + blue * 114 >= 160_000
    }
}
