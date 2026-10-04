package com.joeykot.dictate.overlay

import android.annotation.TargetApi
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.joeykot.dictate.R
import java.util.function.Consumer
import kotlin.math.roundToInt

/** A non-interactive window bounds the cross-window blur to the animated menu panel. */
@TargetApi(Build.VERSION_CODES.S)
internal class PostProcessingMenuBackdrop(
    context: Context,
    private val onBlurEnabledChanged: (Boolean) -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val dialog = Dialog(context, R.style.Theme_Dictate_MenuBackdrop)
    private val window = requireNotNull(dialog.window)
    private val background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
    private val blurRadius = (20f * context.resources.displayMetrics.density).roundToInt()
    private var listening = false
    private var disposed = false
    private val blurListener = Consumer<Boolean> { enabled ->
        if (!disposed) onBlurEnabledChanged(enabled)
    }

    init {
        dialog.setCancelable(false)
        dialog.setContentView(View(context))
        window.setBackgroundDrawable(background)
        window.decorView.apply {
            setPadding(0, 0, 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        window.attributes = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // These are absolute screen coordinates, including on RTL devices.
            @Suppress("RtlHardcoded")
            gravity = Gravity.TOP or Gravity.LEFT
            title = "Dictate menu background"
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            setFitInsetsTypes(0)
        }
    }

    fun show() {
        try {
            dialog.show()
            onBlurEnabledChanged(windowManager.isCrossWindowBlurEnabled)
            listening = true
            windowManager.addCrossWindowBlurEnabledListener(blurListener)
        } catch (failure: RuntimeException) {
            dispose()
            throw failure
        }
    }

    fun updatePanel(bounds: Rect, cornerRadius: Float, expansion: Float) {
        if (disposed) return
        background.cornerRadius = cornerRadius
        window.attributes = window.attributes.apply {
            x = bounds.left
            y = bounds.top
            width = bounds.width().coerceAtLeast(1)
            height = bounds.height().coerceAtLeast(1)
        }
        window.setBackgroundBlurRadius((blurRadius * expansion).roundToInt())
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        if (listening) {
            runCatching { windowManager.removeCrossWindowBlurEnabledListener(blurListener) }
            listening = false
        }
        onBlurEnabledChanged(false)
        // A failing system call must not prevent removal of the remaining overlay windows.
        runCatching { window.setBackgroundBlurRadius(0) }
        runCatching { dialog.dismiss() }
    }
}
