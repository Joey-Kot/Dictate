package com.joeykot.dictate.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.joeykot.dictate.model.PromptConfig
import com.joeykot.dictate.util.PromptIconStore
import kotlin.math.roundToInt

/** A non-focusable window supplies the backdrop; only this panel draws visible pixels. */
@SuppressLint("ViewConstructor")
internal class PostProcessingMenuView(
    context: Context,
    private val prompts: List<PromptConfig>,
    private val buttonScreenBounds: Rect,
    private val buttonImage: Bitmap,
    private val availableScreenBounds: () -> Rect,
    private val onSelect: (PromptConfig) -> Unit,
    private val onDismiss: () -> Unit,
    private val onPanelChanged: (Rect, Float, Float) -> Unit = { _, _, _ -> },
) : FrameLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val panel = RectF()
    private val anchor = RectF()
    private val target = RectF()
    private var expansion = 0f
    private var animator: ValueAnimator? = null
    private var started = false
    private var acceptingInput = true
    private var backgroundBlurEnabled = false
    var isClosing = false
        private set

    private val rows = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(10), dp(10), dp(10))
        isFocusable = false
    }
    private val scroll = ScrollView(context).apply {
        isFocusable = false
        isFocusableInTouchMode = false
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
        scrollBarStyle = SCROLLBARS_INSIDE_INSET
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(rows, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    init {
        setWillNotDraw(false)
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = true
        clipChildren = false
        prompts.forEachIndexed { index, prompt ->
            if (index > 0) rows.addView(View(context).apply {
                setBackgroundColor(Color.argb(40, 255, 255, 255))
            }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            })
            rows.addView(createRow(prompt), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(64)))
        }
        addView(scroll, LayoutParams(1, 1))
        setOnApplyWindowInsetsListener { _, insets ->
            post { if (!isClosing) positionPanel() }
            insets
        }
    }

    private fun createRow(prompt: PromptConfig): View = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(6))
        isFocusable = false
        background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(45, 255, 255, 255)),
            null,
            GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.WHITE) },
        )
        addView(ImageView(context).apply {
            setImageDrawable(PromptIconStore.load(context, prompt))
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(TextView(context).apply {
            text = prompt.title
            textSize = 16f * MENU_SCALE
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(14), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        setOnClickListener {
            if (acceptingInput && !isClosing) {
                acceptingInput = false
                onSelect(prompt)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        positionPanel()
        if (!started && w > 0 && h > 0) {
            started = true
            animateExpansion(1f, null)
        }
    }

    private fun positionPanel() {
        if (width <= 0 || height <= 0) return
        val origin = IntArray(2)
        getLocationOnScreen(origin)
        anchor.set(buttonScreenBounds)
        anchor.offset(-origin[0].toFloat(), -origin[1].toFloat())
        val available = availableScreenBounds().apply {
            offset(-origin[0], -origin[1])
            left = left.coerceIn(0, width - 1)
            top = top.coerceIn(0, height - 1)
            right = right.coerceIn(left + 1, width)
            bottom = bottom.coerceIn(top + 1, height)
        }
        val margin = minOf(dp(8), (available.width() - 1) / 2, (available.height() - 1) / 2)
        available.inset(margin, margin)
        val result = placeOverlayMenu(
            OverlayMenuRect(anchor.left.roundToInt(), anchor.top.roundToInt(), anchor.width().roundToInt(), anchor.height().roundToInt()),
            OverlayMenuRect(available.left, available.top, available.width(), available.height()),
            preferredWidth = dp(270),
            preferredHeight = minOf(dp(360), dp(20) + prompts.size * dp(64) + (prompts.size - 1).coerceAtLeast(0) * dp(1)),
        )
        target.set(result.x.toFloat(), result.y.toFloat(), (result.x + result.width).toFloat(), (result.y + result.height).toFloat())
        scroll.layoutParams = LayoutParams(result.width, result.height).apply {
            leftMargin = result.x
            topMargin = result.y
        }
        updatePanel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = Color.rgb(45, 50, 57)
        val backgroundAlpha = if (backgroundBlurEnabled) 150 else 200
        paint.alpha = (backgroundAlpha * (expansion * 4f).coerceAtMost(1f)).roundToInt()
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(panel, cornerRadius(), cornerRadius(), paint)
        paint.color = Color.WHITE
        paint.alpha = (40 * expansion).roundToInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1).toFloat()
        canvas.drawRoundRect(panel, cornerRadius(), cornerRadius(), paint)
        if (expansion < 0.5f && !buttonImage.isRecycled) {
            paint.style = Paint.Style.FILL
            paint.alpha = (255 * (1f - expansion * 2f)).roundToInt()
            canvas.drawBitmap(buttonImage, null, anchor, paint)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val saved = canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(panel, cornerRadius(), cornerRadius(), Path.Direction.CW)
        canvas.clipPath(clipPath)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(saved)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (isClosing || !acceptingInput) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !target.contains(event.x, event.y)) {
            acceptingInput = false
            onDismiss()
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    fun close(onClosed: () -> Unit) {
        if (isClosing) return
        isClosing = true
        acceptingInput = false
        animateExpansion(0f, onClosed)
    }

    fun setBackgroundBlurEnabled(enabled: Boolean) {
        backgroundBlurEnabled = enabled
        invalidate()
    }

    fun dispose() {
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
        if (!buttonImage.isRecycled) buttonImage.recycle()
    }

    private fun animateExpansion(end: Float, onFinished: (() -> Unit)?) {
        animator?.removeAllListeners()
        animator?.cancel()
        animator = ValueAnimator.ofFloat(expansion, end).apply {
            duration = if (end == 1f) 220L else 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                expansion = it.animatedValue as Float
                updatePanel()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onFinished?.invoke()
                }
            })
            start()
        }
    }

    private fun updatePanel() {
        // OverlayButtonView draws its circle at 46% of the button's width.
        val anchorInset = anchor.width() * 0.04f
        val from = RectF(anchor).apply { inset(anchorInset, anchorInset) }
        panel.set(
            interpolate(from.left, target.left),
            interpolate(from.top, target.top),
            interpolate(from.right, target.right),
            interpolate(from.bottom, target.bottom),
        )
        scroll.alpha = expansion * expansion
        val origin = IntArray(2)
        getLocationOnScreen(origin)
        onPanelChanged(
            Rect(
                panel.left.roundToInt() + origin[0],
                panel.top.roundToInt() + origin[1],
                panel.right.roundToInt() + origin[0],
                panel.bottom.roundToInt() + origin[1],
            ),
            cornerRadius(),
            expansion,
        )
        invalidate()
    }

    private fun cornerRadius(): Float = interpolate(anchor.width() * 0.46f, dp(22).toFloat())

    private fun interpolate(from: Float, to: Float): Float = from + (to - from) * expansion

    private fun dp(value: Int): Int = (value * MENU_SCALE * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val MENU_SCALE = 0.792f
    }
}

internal data class OverlayMenuRect(val x: Int, val y: Int, val width: Int, val height: Int)

internal fun placeOverlayMenu(
    button: OverlayMenuRect,
    viewport: OverlayMenuRect,
    preferredWidth: Int,
    preferredHeight: Int,
): OverlayMenuRect {
    val width = preferredWidth.coerceIn(1, viewport.width.coerceAtLeast(1))
    val height = preferredHeight.coerceIn(1, viewport.height.coerceAtLeast(1))
    val fromRight = button.x + button.width / 2 >= viewport.x + viewport.width / 2
    val desiredX = if (fromRight) button.x + button.width - width else button.x
    return OverlayMenuRect(
        x = desiredX.coerceIn(viewport.x, viewport.x + viewport.width - width),
        y = (button.y + button.height / 2 - height / 2).coerceIn(viewport.y, viewport.y + viewport.height - height),
        width = width,
        height = height,
    )
}
