package pk.pointly.app.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
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
    private var snapAnimator: ValueAnimator? = null
    private var added = false

    var isShowing = false
        private set

    var state: BubbleState = BubbleState.IDLE
        set(value) {
            if (field == value) return
            view.onStateChanged(field, value)
            field = value
        }

    var showClose: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            view.onCloseChanged(value)
        }

    fun show() {
        if (isShowing) return
        isShowing = true
        view.animate().cancel()
        if (!added) {
            val (w, h) = screenSize()
            val onRight = prefs.getBoolean("right", true)
            lp.x = if (onRight) w - sizePx + edgeInset() else -edgeInset()
            lp.y = prefs.getInt("y", (h * 0.45f).toInt()).coerceIn(0, h - sizePx)
            view.onRight = onRight
            view.alpha = 0f
            view.scaleX = 0.4f
            view.scaleY = 0.4f
            wm.addView(view, lp)
            added = true
        }
        view.onStateChanged(state, state)
        view.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(if (Motion.enabled) 460 else 0)
            .setInterpolator(OvershootInterpolator(1.8f))
            .start()
    }

    fun hide() {
        if (!isShowing) return
        isShowing = false
        snapAnimator?.cancel()
        view.stopTicking()
        view.cancelTimers()
        view.animate().cancel()
        view.animate()
            .alpha(0f).scaleX(0.6f).scaleY(0.6f)
            .setDuration(if (Motion.enabled) 160 else 0)
            .setInterpolator(Motion.accelerate)
            .withEndAction {
                if (!isShowing && added) {
                    try {
                        wm.removeViewImmediate(view)
                    } catch (_: IllegalArgumentException) {
                    }
                    added = false
                }
            }
            .start()
    }

    fun bubbleCenterOnScreen(): Point {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return Point(loc[0] + (view.circleCx * view.scaleX).toInt(), loc[1] + (view.circleCy * view.scaleY).toInt())
    }

    private fun edgeInset() = (6 * density).toInt()

    private fun screenSize(): Pair<Int, Int> {
        val m = context.resources.displayMetrics
        val bounds = if (android.os.Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds else null
        return (bounds?.width() ?: m.widthPixels) to (bounds?.height() ?: m.heightPixels)
    }

    private fun snapToEdge() {
        val (w, h) = screenSize()
        val toRight = lp.x + sizePx / 2 > w / 2
        val targetX = if (toRight) w - sizePx + edgeInset() else -edgeInset()
        val fromX = lp.x
        val fromY = lp.y
        val targetY = lp.y.coerceIn(0, h - sizePx)
        view.onRight = toRight
        prefs.edit().putBoolean("right", toRight).putInt("y", targetY).apply()
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (Motion.enabled) 520 else 0
            addUpdateListener {
                if (!isShowing || !added) return@addUpdateListener
                val f = Motion.spring(it.animatedFraction, damping = 0.5f, frequency = 1.2f)
                lp.x = (fromX + (targetX - fromX) * f).toInt()
                lp.y = (fromY + (targetY - fromY) * Motion.easeOutCubic(it.animatedFraction)).toInt()
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
        val circleCy get() = height / 2f + 5 * d
        private val radius = 30 * d
        private val chipRadius = 12 * d
        private val chipCx get() = if (onRight) chipRadius + 4 * d else width - chipRadius - 4 * d
        private val chipCy get() = chipRadius + 3 * d

        private val body = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rim = stroke(Palette.alpha(Color.WHITE, 0.28f), 1f * d)
        private val white = fill(Color.WHITE)
        private val whiteStroke = stroke(Color.WHITE, 3 * d).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val signal = fill(Palette.signal)
        private val ripple = stroke(Palette.coral, 2.5f * d)
        private val comet = stroke(Palette.signal, 3.5f * d).apply { strokeCap = Paint.Cap.ROUND }
        private val chipFill = fill(Palette.ink)
        private val chipShadow = fill(Palette.alpha(Color.BLACK, 0.18f))
        private val badgeFill = fill(Palette.signal)
        private val badgeRing = fill(Color.WHITE)
        private val badgeStroke = stroke(Palette.ink, 2.2f * d).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val r = RectF()
        private val path = Path()

        private var prevState = BubbleState.IDLE
        private var stateChangedAt = 0L
        private var fromColors = colorsFor(BubbleState.IDLE)
        private var closeChangedAt = 0L
        private var closeVisible = false
        private var pressed = false
        private var lift = 0f
        private var ticking = false

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

        init {
            isClickable = true
            isLongClickable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean = when (action) {
            android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK -> {
                if (closeVisible) listener.onClose() else listener.onTap()
                true
            }
            android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK -> {
                listener.onLongPress()
                true
            }
            else -> super.performAccessibilityAction(action, arguments)
        }

        private fun fill(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }
        private fun stroke(c: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c
            style = Paint.Style.STROKE
            strokeWidth = w
        }

        private fun colorsFor(s: BubbleState): Pair<Int, Int> = when (s) {
            BubbleState.LISTENING -> Palette.coral to Palette.coralDeep
            BubbleState.PAUSED -> Palette.slate to Palette.slateDeep
            else -> Palette.brandBright to Palette.brandDeep
        }

        fun onStateChanged(old: BubbleState, new: BubbleState) {
            fromColors = currentColors()
            prevState = old
            stateChangedAt = SystemClock.uptimeMillis()
            contentDescription = when (new) {
                BubbleState.IDLE -> "Pointly. Hold to talk, tap for quick tasks"
                BubbleState.LISTENING -> "Pointly is listening"
                BubbleState.THINKING -> "Pointly is thinking"
                BubbleState.SPEAKING -> "Pointly is speaking"
                BubbleState.PAUSED -> "Pointly is paused on a private screen"
                BubbleState.ERROR -> "Something went wrong. Tap to try again"
            }
            if (new == BubbleState.LISTENING) {
                performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
            }
            startTicking()
        }

        fun onCloseChanged(visible: Boolean) {
            closeVisible = visible
            closeChangedAt = SystemClock.uptimeMillis()
            startTicking()
        }

        private fun startTicking() {
            ticking = true
            postInvalidateOnAnimation()
        }

        fun stopTicking() {
            ticking = false
        }

        private fun progress(since: Long, ms: Long): Float {
            if (!Motion.enabled) return 1f
            return ((SystemClock.uptimeMillis() - since) / ms.toFloat()).coerceIn(0f, 1f)
        }

        private fun currentColors(): Pair<Int, Int> {
            val target = colorsFor(state)
            val t = progress(stateChangedAt, COLOR_MS)
            return ColorUtils.blendARGB(fromColors.first, target.first, t) to ColorUtils.blendARGB(fromColors.second, target.second, t)
        }

        override fun onDraw(canvas: Canvas) {
            val now = SystemClock.uptimeMillis()
            val cx = circleCx
            val cy = circleCy
            val animated = Motion.enabled
            val liftTarget = if (dragging) 1f else 0f
            lift += (liftTarget - lift) * 0.25f

            val shadowY = cy + (3 + 4 * lift) * d
            val shadowR = min(radius + (7 + 6 * lift) * d, height - shadowY)
            shadow.shader = RadialGradient(
                cx, shadowY, shadowR,
                intArrayOf(Palette.alpha(Color.BLACK, 0.24f + 0.08f * lift), Palette.alpha(Color.BLACK, 0.07f), Color.TRANSPARENT),
                floatArrayOf(0.7f, 0.86f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, shadowY, shadowR, shadow)

            if (state == BubbleState.LISTENING) {
                for (k in 0..1) {
                    val p = if (animated) (((now % RIPPLE_MS) / RIPPLE_MS.toFloat()) + k * 0.5f) % 1f else 0.35f
                    ripple.alpha = (200 * (1f - p)).toInt()
                    val maxR = min(min(cx, width - cx), min(cy, height - cy)) - ripple.strokeWidth
                    canvas.drawCircle(cx, cy, min(radius + 2 * d + Motion.easeOutCubic(p) * 11 * d, maxR), ripple)
                }
            }

            val (top, bottom) = currentColors()
            body.shader = RadialGradient(cx - radius * 0.45f, cy - radius * 0.55f, radius * 2.1f, top, bottom, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, radius, body)
            sheen.shader = LinearGradient(0f, cy - radius, 0f, cy, Palette.alpha(Color.WHITE, 0.20f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, radius * 0.95f, sheen)
            canvas.drawCircle(cx, cy, radius - 0.5f * d, rim)

            if (state == BubbleState.THINKING && animated) {
                val angle = (now % COMET_MS) / COMET_MS.toFloat() * 360f
                comet.shader = SweepGradient(cx, cy, intArrayOf(Color.TRANSPARENT, Palette.alpha(Palette.signal, 0.15f), Palette.signal), floatArrayOf(0f, 0.55f, 1f))
                canvas.save()
                canvas.rotate(angle, cx, cy)
                val rr = radius + 4.5f * d
                r.set(cx - rr, cy - rr, cx + rr, cy + rr)
                canvas.drawArc(r, 0f, 300f, false, comet)
                canvas.restore()
            }

            val t = progress(stateChangedAt, GLYPH_MS)
            if (t < 1f) drawGlyph(canvas, prevState, cx, cy, now, alpha = 1f - t, scale = 1f - 0.25f * t)
            drawGlyph(canvas, state, cx, cy, now, alpha = t, scale = 0.75f + 0.25f * Motion.easeOutBack(t))

            val chipT = progress(closeChangedAt, CHIP_MS)
            val chipScale = if (closeVisible) Motion.easeOutBack(chipT) else 1f - Motion.easeOutCubic(chipT)
            if (chipScale > 0.01f) drawChip(canvas, chipScale)

            val busy = state == BubbleState.LISTENING || state == BubbleState.THINKING || state == BubbleState.SPEAKING
            val settling = t < 1f || chipT < 1f || progress(stateChangedAt, COLOR_MS) < 1f || abs(lift - liftTarget) > 0.01f
            if (ticking && ((busy && animated) || settling)) postInvalidateOnAnimation()
        }

        private fun drawGlyph(c: Canvas, s: BubbleState, cx: Float, cy: Float, now: Long, alpha: Float, scale: Float) {
            if (alpha <= 0.01f) return
            val a = (alpha * 255).toInt().coerceIn(0, 255)
            white.alpha = a
            whiteStroke.alpha = a
            signal.alpha = a
            c.save()
            c.scale(scale, scale, cx, cy)
            when (s) {
                BubbleState.IDLE, BubbleState.ERROR -> drawMark(c, cx, cy, 1f)
                BubbleState.THINKING -> {
                    val pulse = if (Motion.enabled) (sin((now % 900) / 900.0 * 2 * Math.PI).toFloat() * 0.5f + 0.5f) else 0.5f
                    drawMark(c, cx, cy, 0.8f + 0.35f * pulse)
                }
                BubbleState.LISTENING -> drawMic(c, cx, cy)
                BubbleState.SPEAKING -> drawBars(c, cx, cy, now)
                BubbleState.PAUSED -> drawLock(c, cx, cy)
            }
            c.restore()
            if (s == BubbleState.ERROR) drawBadge(c, cx, cy, alpha)
            white.alpha = 255
            whiteStroke.alpha = 255
            signal.alpha = 255
            whiteStroke.strokeWidth = 3 * d
        }

        private fun drawMark(c: Canvas, cx: Float, cy: Float, dotScale: Float) {
            whiteStroke.strokeWidth = radius * 0.15f
            c.drawCircle(cx, cy, radius * 0.5f, whiteStroke)
            c.drawCircle(cx, cy, radius * 0.21f * dotScale, signal)
        }

        private fun drawMic(c: Canvas, cx: Float, cy: Float) {
            r.set(cx - 5.5f * d, cy - 13 * d, cx + 5.5f * d, cy + 3 * d)
            c.drawRoundRect(r, 5.5f * d, 5.5f * d, white)
            whiteStroke.strokeWidth = 2.6f * d
            r.set(cx - 9.5f * d, cy - 6 * d, cx + 9.5f * d, cy + 8 * d)
            c.drawArc(r, 0f, 180f, false, whiteStroke)
            c.drawLine(cx, cy + 8 * d, cx, cy + 12.5f * d, whiteStroke)
        }

        private fun drawBars(c: Canvas, cx: Float, cy: Float, now: Long) {
            whiteStroke.strokeWidth = 4 * d
            val sec = now / 1000.0
            for (i in 0..3) {
                val wave = if (Motion.enabled) {
                    (0.55 * sin(sec * 7.1 + i * 1.3) + 0.45 * sin(sec * 11.3 + i * 2.1)).toFloat() * 0.5f + 0.5f
                } else {
                    floatArrayOf(0.4f, 0.9f, 0.7f, 0.5f)[i]
                }
                val h = (6 + 15 * wave) * d
                val x = cx + (i - 1.5f) * 8 * d
                c.drawLine(x, cy - h / 2, x, cy + h / 2, whiteStroke)
            }
        }

        private fun drawLock(c: Canvas, cx: Float, cy: Float) {
            r.set(cx - 9.5f * d, cy - 2 * d, cx + 9.5f * d, cy + 12 * d)
            c.drawRoundRect(r, 3.5f * d, 3.5f * d, white)
            whiteStroke.strokeWidth = 2.8f * d
            path.reset()
            path.moveTo(cx - 6 * d, cy - 2 * d)
            path.lineTo(cx - 6 * d, cy - 6 * d)
            path.cubicTo(cx - 6 * d, cy - 14 * d, cx + 6 * d, cy - 14 * d, cx + 6 * d, cy - 6 * d)
            path.lineTo(cx + 6 * d, cy - 2 * d)
            c.drawPath(path, whiteStroke)
        }

        private fun drawBadge(c: Canvas, cx: Float, cy: Float, alpha: Float) {
            val bx = cx + radius * 0.7f
            val by = cy + radius * 0.7f
            val br = 10.5f * d
            badgeFill.alpha = (alpha * 255).toInt()
            badgeStroke.alpha = (alpha * 255).toInt()
            badgeRing.alpha = (alpha * 255).toInt()
            c.drawCircle(bx, by, br + 2 * d, badgeRing)
            c.drawCircle(bx, by, br, badgeFill)
            val ar = 5 * d
            r.set(bx - ar, by - ar, bx + ar, by + ar)
            c.drawArc(r, -60f, 280f, false, badgeStroke)
            path.reset()
            path.moveTo(bx + ar * 0.95f, by - ar * 1.15f)
            path.lineTo(bx + ar * 0.55f, by - ar * 0.2f)
            path.lineTo(bx + ar * 1.45f, by - ar * 0.35f)
            c.drawPath(path, badgeStroke)
        }

        private fun drawChip(c: Canvas, scale: Float) {
            c.save()
            c.scale(scale, scale, chipCx, chipCy)
            c.drawCircle(chipCx, chipCy + 1.5f * d, chipRadius + 1 * d, chipShadow)
            c.drawCircle(chipCx, chipCy, chipRadius, chipFill)
            val k = 4.2f * d
            whiteStroke.strokeWidth = 2.2f * d
            c.drawLine(chipCx - k, chipCy - k, chipCx + k, chipCy + k, whiteStroke)
            c.drawLine(chipCx - k, chipCy + k, chipCx + k, chipCy - k, whiteStroke)
            whiteStroke.strokeWidth = 3 * d
            c.restore()
        }

        private fun press(down: Boolean) {
            if (pressed == down) return
            pressed = down
            animate().cancel()
            if (down) {
                animate().alpha(1f).scaleX(0.9f).scaleY(0.9f).setDuration(if (Motion.enabled) 140 else 0).setInterpolator(Motion.decelerate).start()
            } else {
                animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(if (Motion.enabled) 420 else 0).setInterpolator(OvershootInterpolator(3f)).start()
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!isShowing) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    holding = false
                    snapAnimator?.cancel()
                    onChip = closeVisible && hypot(e.x - chipCx, e.y - chipCy) <= chipRadius + 10 * d &&
                        hypot(e.x - circleCx, e.y - circleCy) > radius
                    if (!onChip) {
                        press(true)
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
                        pressed = false
                        animate().cancel()
                        animate().alpha(1f).scaleX(1.08f).scaleY(1.08f).setDuration(if (Motion.enabled) 180 else 0).setInterpolator(Motion.decelerate).start()
                        startTicking()
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
                        dragging -> {
                            dragging = false
                            pressed = true
                            press(false)
                            snapToEdge()
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            startTicking()
                        }
                        onChip -> if (!cancelled && hypot(e.x - chipCx, e.y - chipCy) <= chipRadius + 10 * d) {
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            listener.onClose()
                        }
                        holding -> {
                            press(false)
                            listener.onHoldEnd()
                        }
                        !cancelled && abs(e.eventTime - e.downTime) < HOLD_MS -> {
                            press(false)
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            performClick()
                            listener.onTap()
                        }
                        else -> press(false)
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

        fun cancelTimers() {
            handler.removeCallbacks(holdRunnable)
            handler.removeCallbacks(longPressRunnable)
        }

        override fun onDetachedFromWindow() {
            cancelTimers()
            stopTicking()
            super.onDetachedFromWindow()
        }
    }

    companion object {
        const val HOLD_MS = 250L
        const val LONG_PRESS_MS = 1500L
        private const val VIEW_DP = 88
        private const val GLYPH_MS = 220L
        private const val COLOR_MS = 260L
        private const val CHIP_MS = 240L
        private const val RIPPLE_MS = 1400L
        private const val COMET_MS = 1100L
    }
}
