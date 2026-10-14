package pk.pointly.app.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

enum class BubbleState { IDLE, LISTENING, THINKING, SPEAKING, PAUSED, ERROR }

class BubbleOverlay(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onHoldStart()

        fun onHoldEnd()

        fun onTap()

        fun onLongPress()

        fun onClose()
    }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val prefs = context.getSharedPreferences("pointly_bubble", Context.MODE_PRIVATE)
    private val view = BubbleView(context)
    private val sizePx = (VIEW_DP * density).toInt()
    private val lp = WindowManager.LayoutParams(
        sizePx, sizePx,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "PointlyBubble"
    }

    var isShowing = false
        private set

    var state: BubbleState = BubbleState.IDLE
        set(value) {
            field = value
            view.onStateChanged()
        }

    var showClose: Boolean = false
        set(value) {
            field = value
            view.invalidate()
        }

    fun show() {
        if (isShowing) return
        val (w, h) = screenSize()
        val onRight = prefs.getBoolean("right", true)
        lp.x = if (onRight) w - sizePx else 0
        lp.y = prefs.getInt("y", (h * 0.45f).toInt()).coerceIn(0, h - sizePx)
        view.onRight = onRight
        wm.addView(view, lp)
        isShowing = true
        view.onStateChanged()
    }

    fun hide() {
        if (!isShowing) return
        isShowing = false
        view.stopAnimation()
        try {
            wm.removeViewImmediate(view)
        } catch (_: IllegalArgumentException) {
        }
    }

    fun bubbleCenterOnScreen(): Point {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return Point(loc[0] + view.circleCx.toInt(), loc[1] + view.circleCy.toInt())
    }

    private fun screenSize(): Pair<Int, Int> {
        val m = context.resources.displayMetrics
        val bounds = if (android.os.Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds else null
        return (bounds?.width() ?: m.widthPixels) to (bounds?.height() ?: m.heightPixels)
    }

    private fun snapToEdge() {
        val (w, h) = screenSize()
        val toRight = lp.x + sizePx / 2 > w / 2
        val targetX = if (toRight) w - sizePx else 0
        lp.y = lp.y.coerceIn(0, h - sizePx)
        view.onRight = toRight
        prefs.edit().putBoolean("right", toRight).putInt("y", lp.y).apply()
        ValueAnimator.ofInt(lp.x, targetX).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                if (!isShowing) return@addUpdateListener
                lp.x = it.animatedValue as Int
                wm.updateViewLayout(view, lp)
            }
            start()
        }
    }

    @SuppressLint("ViewConstructor")
    private inner class BubbleView(context: Context) : View(context) {
        var onRight = true
            set(value) {
                field = value
                invalidate()
            }

        private val d = density
        val circleCx get() = width / 2f
        val circleCy get() = height / 2f + 6 * d
        private val radius = 32 * d
        private val chipRadius = 12 * d
        private val chipCx get() = if (onRight) chipRadius + 2 * d else width - chipRadius - 2 * d
        private val chipCy get() = chipRadius + 2 * d

        private val green = Color.parseColor("#0B7A3E")
        private val shadow = paint(Color.argb(90, 0, 0, 0))
        private val base = paint(green)
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3 * d
        }
        private val white = paint(Color.WHITE)
        private val whiteStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3 * d
            strokeCap = Paint.Cap.ROUND
        }
        private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E53935")
            style = Paint.Style.STROKE
        }
        private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 34 * d
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val chip = paint(Color.parseColor("#DD212121"))
        private val badge = paint(Color.parseColor("#E53935"))
        private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 14 * d
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val r = RectF()

        private var phase = 0f
        private var animator: ValueAnimator? = null

        private val handler = Handler(Looper.getMainLooper())
        private val slop = ViewConfiguration.get(context).scaledTouchSlop * 1.5f
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var holding = false
        private var onChip = false
        private val holdRunnable = Runnable {
            holding = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener.onHoldStart()
        }
        private val longPressRunnable = Runnable { listener.onLongPress() }

        private fun paint(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }

        fun onStateChanged() {
            base.color = if (state == BubbleState.PAUSED) Color.parseColor("#455A64") else green
            val animated = state == BubbleState.LISTENING || state == BubbleState.THINKING || state == BubbleState.SPEAKING
            if (animated) startAnimation() else stopAnimation()
            contentDescription = when (state) {
                BubbleState.IDLE -> "Pointly"
                BubbleState.LISTENING -> "Pointly is listening"
                BubbleState.THINKING -> "Pointly is thinking"
                BubbleState.SPEAKING -> "Pointly is speaking"
                BubbleState.PAUSED -> "Pointly is paused"
                BubbleState.ERROR -> "Try again"
            }
            invalidate()
        }

        private fun startAnimation() {
            if (animator != null) return
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1200
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stopAnimation() {
            animator?.cancel()
            animator = null
            phase = 0f
        }

        override fun onDraw(canvas: Canvas) {
            val cx = circleCx
            val cy = circleCy
            canvas.drawCircle(cx, cy + 2 * d, radius + 1 * d, shadow)
            if (state == BubbleState.LISTENING) {
                val grow = sin(phase * Math.PI * 2).toFloat() * 0.5f + 0.5f
                red.strokeWidth = (3 + 2 * grow) * d
                red.alpha = (255 * (1f - 0.5f * grow)).toInt()
                canvas.drawCircle(cx, cy, radius - 2 * d + grow * 3 * d, red)
            }
            canvas.drawCircle(cx, cy, radius - 3 * d, base)
            canvas.drawCircle(cx, cy, radius - 3 * d, border)

            when (state) {
                BubbleState.IDLE, BubbleState.ERROR -> {
                    val y = cy - (letter.descent() + letter.ascent()) / 2
                    canvas.drawText("P", cx, y, letter)
                }
                BubbleState.LISTENING -> drawMic(canvas, cx, cy)
                BubbleState.THINKING -> drawDots(canvas, cx, cy)
                BubbleState.SPEAKING -> drawWaves(canvas, cx, cy)
                BubbleState.PAUSED -> drawLock(canvas, cx, cy)
            }
            if (state == BubbleState.ERROR) {
                val bx = cx + radius * 0.62f
                val by = cy + radius * 0.62f
                canvas.drawCircle(bx, by, 11 * d, badge)
                canvas.drawText("↻", bx, by - (badgeText.descent() + badgeText.ascent()) / 2, badgeText)
            }
            if (showClose) {
                canvas.drawCircle(chipCx, chipCy, chipRadius, chip)
                val k = 4.5f * d
                whiteStroke.strokeWidth = 2.2f * d
                canvas.drawLine(chipCx - k, chipCy - k, chipCx + k, chipCy + k, whiteStroke)
                canvas.drawLine(chipCx - k, chipCy + k, chipCx + k, chipCy - k, whiteStroke)
                whiteStroke.strokeWidth = 3 * d
            }
        }

        private fun drawMic(c: Canvas, cx: Float, cy: Float) {
            r.set(cx - 6 * d, cy - 14 * d, cx + 6 * d, cy + 4 * d)
            c.drawRoundRect(r, 6 * d, 6 * d, white)
            r.set(cx - 10 * d, cy - 6 * d, cx + 10 * d, cy + 9 * d)
            c.drawArc(r, 0f, 180f, false, whiteStroke)
            c.drawLine(cx, cy + 9 * d, cx, cy + 14 * d, whiteStroke)
        }

        private fun drawDots(c: Canvas, cx: Float, cy: Float) {
            for (i in 0..2) {
                val t = ((phase - i * 0.18f) % 1f + 1f) % 1f
                val lift = (sin(t * Math.PI * 2).toFloat()).coerceAtLeast(0f)
                white.alpha = (140 + 115 * lift).toInt()
                c.drawCircle(cx + (i - 1) * 11 * d, cy - lift * 5 * d, 4.2f * d, white)
            }
            white.alpha = 255
        }

        private fun drawWaves(c: Canvas, cx: Float, cy: Float) {
            for (i in 0..3) {
                val t = sin((phase + i * 0.23f) * Math.PI * 2).toFloat() * 0.5f + 0.5f
                val h = (6 + 12 * t) * d
                val x = cx + (i - 1.5f) * 8 * d
                c.drawLine(x, cy - h / 2, x, cy + h / 2, whiteStroke)
            }
        }

        private fun drawLock(c: Canvas, cx: Float, cy: Float) {
            r.set(cx - 10 * d, cy - 3 * d, cx + 10 * d, cy + 13 * d)
            c.drawRoundRect(r, 3 * d, 3 * d, white)
            r.set(cx - 7 * d, cy - 15 * d, cx + 7 * d, cy + 1 * d)
            c.drawArc(r, 180f, 180f, false, whiteStroke)
            c.drawLine(cx - 7 * d, cy - 7 * d, cx - 7 * d, cy - 3 * d, whiteStroke)
            c.drawLine(cx + 7 * d, cy - 7 * d, cx + 7 * d, cy - 3 * d, whiteStroke)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    holding = false
                    onChip = showClose && hypot(e.x - chipCx, e.y - chipCy) <= chipRadius + 8 * d
                    if (!onChip) {
                        handler.postDelayed(holdRunnable, HOLD_MS)
                        handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!dragging && !holding && !onChip && hypot(dx, dy) > slop) {
                        dragging = true
                        cancelTimers()
                    }
                    if (dragging) {
                        lp.x = (startX + dx).toInt()
                        lp.y = (startY + dy).toInt()
                        wm.updateViewLayout(this, lp)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelTimers()
                    val cancelled = e.actionMasked == MotionEvent.ACTION_CANCEL
                    when {
                        dragging -> snapToEdge()
                        onChip -> if (!cancelled && hypot(e.x - chipCx, e.y - chipCy) <= chipRadius + 8 * d) listener.onClose()
                        holding -> listener.onHoldEnd()
                        !cancelled && abs(e.eventTime - e.downTime) < HOLD_MS -> {
                            performClick()
                            listener.onTap()
                        }
                    }
                    dragging = false
                    holding = false
                    onChip = false
                    return true
                }
            }
            return super.onTouchEvent(e)
        }

        override fun performClick(): Boolean = super.performClick()

        private fun cancelTimers() {
            handler.removeCallbacks(holdRunnable)
            handler.removeCallbacks(longPressRunnable)
        }

        override fun onDetachedFromWindow() {
            cancelTimers()
            stopAnimation()
            super.onDetachedFromWindow()
        }
    }

    companion object {
        const val HOLD_MS = 250L
        const val LONG_PRESS_MS = 1500L
        private const val VIEW_DP = 80
    }
}
