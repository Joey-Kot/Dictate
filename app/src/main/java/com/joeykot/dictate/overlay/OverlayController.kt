package com.joeykot.dictate.overlay

import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import com.joeykot.dictate.accessibility.DictateAccessibilityService
import com.joeykot.dictate.job.VoiceJobController
import com.joeykot.dictate.model.JobUiState
import com.joeykot.dictate.settings.SettingsRepository
import kotlin.math.roundToInt

class OverlayController(
    private val service: DictateAccessibilityService,
    private val voiceJobs: VoiceJobController,
    private val settingsRepository: SettingsRepository,
) : OverlayButtonView.DragDelegate {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var sizePx = buttonSizePx()
    private val layoutParams = WindowManager.LayoutParams(
        sizePx,
        sizePx,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "Dictate voice button"
    }
    private val view = OverlayButtonView(service, voiceJobs, settingsRepository, this)
    private val listener = VoiceJobController.Listener(::onStateChanged)

    private var shown = false
    private var dragStartX = 0
    private var dragStartY = 0

    init {
        view.setOnApplyWindowInsetsListener { _, insets ->
            view.post {
                if (shown) reclipAndPersistPosition()
            }
            insets
        }
    }

    fun show() {
        if (shown) return
        val bounds = safeBounds()
        val saved = settingsRepository.getOverlayPosition()
        layoutParams.x = saved?.x ?: bounds.maxX
        layoutParams.y = saved?.y ?: ((bounds.minY + bounds.maxY) / 2)
        clampPosition(bounds)
        windowManager.addView(view, layoutParams)
        shown = true
        voiceJobs.addListener(listener)
        view.setJobUiState(voiceJobs.currentState())
        view.post {
            if (shown) reclipAndPersistPosition()
        }
    }

    fun remove() {
        voiceJobs.removeListener(listener)
        view.cancelPendingGestures()
        if (shown) runCatching { windowManager.removeViewImmediate(view) }
        shown = false
    }

    fun onConfigurationChanged() {
        if (!shown) return
        view.post {
            if (!shown) return@post
            val newSize = buttonSizePx()
            val sizeChanged = newSize != sizePx
            if (sizeChanged) {
                sizePx = newSize
                layoutParams.width = sizePx
                layoutParams.height = sizePx
            }
            val positionChanged = clampPosition(safeBounds())
            if (sizeChanged || positionChanged) updateLayout()
            persistPosition()
        }
    }

    override fun onDragStarted() {
        dragStartX = layoutParams.x
        dragStartY = layoutParams.y
    }

    override fun onDragMoved(deltaX: Float, deltaY: Float) {
        layoutParams.x = dragStartX + deltaX.roundToInt()
        layoutParams.y = dragStartY + deltaY.roundToInt()
        clampPosition(safeBounds())
        updateLayout()
    }

    override fun onDragFinished() {
        clampPosition(safeBounds())
        updateLayout()
        persistPosition()
    }

    private fun onStateChanged(state: JobUiState) {
        view.setJobUiState(state)
    }

    private fun updateLayout() {
        if (shown) runCatching { windowManager.updateViewLayout(view, layoutParams) }
    }

    private fun reclipAndPersistPosition() {
        if (clampPosition(safeBounds())) updateLayout()
        persistPosition()
    }

    private fun clampPosition(bounds: OverlayBounds): Boolean {
        val clamped = bounds.clamp(layoutParams.x, layoutParams.y)
        val clampedX = clamped.x
        val clampedY = clamped.y
        if (clampedX == layoutParams.x && clampedY == layoutParams.y) return false
        layoutParams.x = clampedX
        layoutParams.y = clampedY
        return true
    }

    private fun persistPosition() {
        settingsRepository.setOverlayPosition(layoutParams.x, layoutParams.y)
    }

    private fun buttonSizePx(): Int {
        return (64f * service.resources.displayMetrics.density).roundToInt()
    }

    @Suppress("DEPRECATION")
    private fun safeBounds(): OverlayBounds {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val displayBounds = metrics.bounds
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            val minX = insets.left
            val minY = insets.top
            return OverlayBounds(
                minX = minX,
                minY = minY,
                maxX = maxOf(minX, displayBounds.width() - insets.right - sizePx),
                maxY = maxOf(minY, displayBounds.height() - insets.bottom - sizePx),
            )
        }

        view.rootWindowInsets?.let { windowInsets ->
            val cutoutInsets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                windowInsets.displayCutout?.let { cutout ->
                    Rect(
                        cutout.safeInsetLeft,
                        cutout.safeInsetTop,
                        cutout.safeInsetRight,
                        cutout.safeInsetBottom,
                    )
                } ?: Rect()
            } else {
                Rect()
            }
            val leftInset = maxOf(windowInsets.stableInsetLeft, cutoutInsets.left)
            val topInset = maxOf(windowInsets.stableInsetTop, cutoutInsets.top)
            val rightInset = maxOf(windowInsets.stableInsetRight, cutoutInsets.right)
            val bottomInset = maxOf(windowInsets.stableInsetBottom, cutoutInsets.bottom)
            val display = windowManager.defaultDisplay
            val real = android.util.DisplayMetrics()
            display.getRealMetrics(real)
            return OverlayBounds(
                minX = leftInset,
                minY = topInset,
                maxX = maxOf(leftInset, real.widthPixels - rightInset - sizePx),
                maxY = maxOf(topInset, real.heightPixels - bottomInset - sizePx),
            )
        }

        val display = windowManager.defaultDisplay
        val real = android.util.DisplayMetrics()
        display.getRealMetrics(real)
        val statusBar = systemDimension("status_bar_height")
        val navigationBar = systemDimension("navigation_bar_height")
        val cutoutInsets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            display.cutout?.let { cutout ->
                Rect(
                    cutout.safeInsetLeft,
                    cutout.safeInsetTop,
                    cutout.safeInsetRight,
                    cutout.safeInsetBottom,
                )
            } ?: Rect()
        } else {
            Rect()
        }
        val minX = cutoutInsets.left
        val minY = maxOf(statusBar, cutoutInsets.top)
        return OverlayBounds(
            minX = minX,
            minY = minY,
            maxX = maxOf(minX, real.widthPixels - cutoutInsets.right - sizePx),
            maxY = maxOf(minY, real.heightPixels - maxOf(navigationBar, cutoutInsets.bottom) - sizePx),
        )
    }

    @SuppressLint("DiscouragedApi")
    private fun systemDimension(name: String): Int {
        val identifier = service.resources.getIdentifier(name, "dimen", "android")
        return if (identifier == 0) 0 else service.resources.getDimensionPixelSize(identifier)
    }

}

internal data class OverlayBounds(
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
) {
    fun clamp(x: Int, y: Int): OverlayCoordinates = OverlayCoordinates(
        x = x.coerceIn(minX, maxX),
        y = y.coerceIn(minY, maxY),
    )
}

internal data class OverlayCoordinates(
    val x: Int,
    val y: Int,
)
