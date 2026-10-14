package pk.rehnuma.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** What the hand points at. Bounds are screen coordinates [left, top, right, bottom]. */
sealed class PointerTarget {
    data class Element(val bounds: List<Int>) : PointerTarget()
    data class Scroll(val bounds: List<Int>, val direction: String) : PointerTarget()
    data class Nav(val kind: String) : PointerTarget() // "back" | "home"

    /** A slider: the finger starts at the thumb ([start], 0..1 along the track) and moves [direction]. */
    data class Drag(val bounds: List<Int>, val direction: String, val start: Float) : PointerTarget()
}

/**
 * Full-screen, never-touchable overlay that draws the pulsing ring, the animated hand and the caption
 * card (SPEC 5.9). Every touch passes through to the app below.
 */
class PointerOverlay(context: Context) : FullScreenOverlay(context, FrameLayout(context)) {

    private val density = context.resources.displayMetrics.density
    private val root = view as FrameLayout
    private val pointer = PointerView(context)
    private val caption = TextView(context)
    private val handler = Handler(Looper.getMainLooper())
    private var arrived: Runnable? = null

    init {
        root.addView(pointer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        caption.apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f) // sp: follows the system font scale
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            val p = (16 * density).toInt()
            setPadding(p, (12 * density).toInt(), p, (12 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 18 * density
                setColor(Color.parseColor("#E6202124"))
                setStroke((2 * density).toInt(), Color.parseColor("#FFC107"))
            }
            visibility = View.GONE
        }
        root.addView(caption, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    /**
     * Points at [target] (or shows only the caption when [target] is null). When [withHand] is false
     * only the caption and ring are shown; call [revealHand] later (HINT and SILENT levels, SPEC 9.1).
     * [onArrived] runs when the hand reaches the target, or right away without a hand.
     */
    fun show(
        target: PointerTarget?,
        captionText: String?,
        urduScript: Boolean,
        from: Point?,
        withHand: Boolean = true,
        withRing: Boolean = true,
        onArrived: (() -> Unit)? = null,
    ) {
        show()
        arrived?.let { handler.removeCallbacks(it) }
        setCaption(captionText, urduScript, target)
        root.post {
            val ox = offsetX
            val oy = offsetY
            pointer.configure(target, from, ox, oy, withHand, withRing)
            val done = Runnable { onArrived?.invoke() }
            arrived = done
            handler.postDelayed(done, if (target != null && withHand) FLY_MS else 0L)
        }
    }

    /** Shows only a caption (thinking, listening partials, pauses). */
    fun showCaption(text: String?, urduScript: Boolean) = show(null, text, urduScript, null)

    /** Shows [text] in the caption card for [ms], then puts the previous caption back. */
    fun flashCaption(text: String, urduScript: Boolean, ms: Long = FLASH_MS) {
        if (!isShowing) {
            showCaption(text, urduScript)
            return
        }
        val previous = caption.text?.toString()
        val wasVisible = caption.visibility == View.VISIBLE
        caption.text = text
        caption.textDirection = if (urduScript) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
        caption.visibility = View.VISIBLE
        handler.removeCallbacks(restoreCaption)
        restoreCaption = Runnable {
            if (wasVisible && previous != null) caption.text = previous else caption.visibility = View.GONE
        }
        handler.postDelayed(restoreCaption, ms)
    }

    private var restoreCaption = Runnable {}

    /** Moves ring and hand to [target] without the fly-in (the element moved on screen). */
    fun moveTo(target: PointerTarget) {
        if (!isShowing) return
        root.post { pointer.moveTo(target, offsetX, offsetY) }
    }

    /** The user hesitated: fly the hand in from the bubble (SPEC 9.1). */
    fun revealHand(from: Point?) {
        if (!isShowing) return
        pointer.reveal(from)
    }

    fun hideAll() {
        handler.removeCallbacks(restoreCaption)
        arrived?.let { handler.removeCallbacks(it) }
        arrived = null
        pointer.clear()
        caption.visibility = View.GONE
        hide()
    }

    /** Top of the on-screen keyboard in screen pixels, or null when it is closed. Set before each show. */
    var keyboardTop: Int? = null

    private fun setCaption(text: String?, urdu: Boolean, target: PointerTarget?) {
        if (text.isNullOrBlank()) {
            caption.visibility = View.GONE
            return
        }
        caption.text = text
        caption.textDirection = if (urdu) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
        caption.typeface = if (urdu) UrduFont.typeface(context) else Typeface.DEFAULT_BOLD
        caption.textLocale = if (urdu) UrduFont.LOCALE else java.util.Locale.getDefault()
        // Opposite half of the screen from the target (SPEC 5.9); bottom when there is no target.
        val screenH = root.resources.displayMetrics.heightPixels
        val targetTop = when (target) {
            is PointerTarget.Element -> (target.bounds[1] + target.bounds[3]) / 2 < screenH / 2
            is PointerTarget.Scroll -> (target.bounds[1] + target.bounds[3]) / 2 < screenH / 2
            is PointerTarget.Drag -> (target.bounds[1] + target.bounds[3]) / 2 < screenH / 2
            is PointerTarget.Nav -> false
            null -> false
        }
        val lp = caption.layoutParams as FrameLayout.LayoutParams
        val m = (16 * density).toInt()
        var bottom = (112 * density).toInt()
        keyboardTop?.let { kb ->
            // Sit just above the keyboard instead of covering its keys.
            val overlayH = root.height.takeIf { it > 0 } ?: screenH
            bottom = maxOf(bottom, overlayH - (kb - offsetY) + (12 * density).toInt())
        }
        lp.setMargins(m, (56 * density).toInt(), m, bottom)
        lp.gravity = if (targetTop) Gravity.BOTTOM else Gravity.TOP
        if (target == null) lp.gravity = Gravity.BOTTOM
        caption.layoutParams = lp
        caption.visibility = View.VISIBLE
    }

    @SuppressLint("ViewConstructor")
    private class PointerView(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private var target: PointerTarget? = null
        private val box = RectF() // target bounds in view coordinates
        private var fromX = 0f
        private var fromY = 0f
        private var handVisible = false
        private var ringVisible = false
        private var handStart = 0L
        private val hand = HandPainter(d)

        private val ringDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(170, 0, 0, 0)
            strokeWidth = 8 * d
        }
        private val ringYellow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#FFC107")
            strokeWidth = 4 * d
        }
        private val ripple = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#FFC107")
            strokeWidth = 3 * d
        }
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#FFC107")
            strokeWidth = 6 * d
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val arrowDark = Paint(arrowPaint).apply {
            color = Color.argb(170, 0, 0, 0)
            strokeWidth = 10 * d
        }
        private val rr = RectF()
        private val path = Path()

        fun configure(t: PointerTarget?, from: Point?, ox: Int, oy: Int, withHand: Boolean, withRing: Boolean) {
            target = t
            when (t) {
                is PointerTarget.Element -> box.set(t.bounds[0] - ox.toFloat(), t.bounds[1] - oy.toFloat(), t.bounds[2] - ox.toFloat(), t.bounds[3] - oy.toFloat())
                is PointerTarget.Scroll -> box.set(t.bounds[0] - ox.toFloat(), t.bounds[1] - oy.toFloat(), t.bounds[2] - ox.toFloat(), t.bounds[3] - oy.toFloat())
                is PointerTarget.Drag -> box.set(t.bounds[0] - ox.toFloat(), t.bounds[1] - oy.toFloat(), t.bounds[2] - ox.toFloat(), t.bounds[3] - oy.toFloat())
                is PointerTarget.Nav -> box.set(width / 2f - 60 * d, height - 72 * d, width / 2f + 60 * d, height.toFloat())
                null -> box.setEmpty()
            }
            setFrom(from, ox, oy)
            ringVisible = withRing && t != null
            handVisible = withHand && t != null
            handStart = SystemClock.uptimeMillis()
            postInvalidateOnAnimation()
        }

        fun moveTo(t: PointerTarget, ox: Int, oy: Int) {
            target = t
            when (t) {
                is PointerTarget.Element -> box.set(t.bounds[0] - ox.toFloat(), t.bounds[1] - oy.toFloat(), t.bounds[2] - ox.toFloat(), t.bounds[3] - oy.toFloat())
                is PointerTarget.Scroll -> box.set(t.bounds[0] - ox.toFloat(), t.bounds[1] - oy.toFloat(), t.bounds[2] - ox.toFloat(), t.bounds[3] - oy.toFloat())
                is PointerTarget.Drag -> box.set(t.bounds[0] - ox.toFloat(), t.bounds[1] - oy.toFloat(), t.bounds[2] - ox.toFloat(), t.bounds[3] - oy.toFloat())
                else -> Unit
            }
            // Skip the fly-in: the hand jumps to the new spot and keeps tapping.
            if (SystemClock.uptimeMillis() - handStart < FLY_MS) handStart = SystemClock.uptimeMillis() - FLY_MS
            postInvalidateOnAnimation()
        }

        fun reveal(from: Point?) {
            if (target == null || handVisible) return
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            setFrom(from, loc[0], loc[1])
            ringVisible = true
            handVisible = true
            handStart = SystemClock.uptimeMillis()
            postInvalidateOnAnimation()
        }

        private fun setFrom(from: Point?, ox: Int, oy: Int) {
            fromX = (from?.x ?: (width / 2)).toFloat() - ox
            fromY = (from?.y ?: (height / 2)).toFloat() - oy
        }

        fun clear() {
            target = null
            handVisible = false
            ringVisible = false
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val t = target ?: return
            val now = SystemClock.uptimeMillis()
            val pulse = (sin((now % PULSE_MS) / PULSE_MS.toFloat() * 2 * PI).toFloat() * 0.5f + 0.5f)

            if (ringVisible && t !is PointerTarget.Nav) {
                val inflate = (8 + 5 * pulse) * d
                val edge = 3 * d // keep all four sides on screen for full-width rows
                rr.set(
                    (box.left - inflate).coerceAtLeast(edge),
                    (box.top - inflate).coerceAtLeast(edge),
                    (box.right + inflate).coerceAtMost(width - edge),
                    (box.bottom + inflate).coerceAtMost(height - edge),
                )
                val radius = min(16 * d, min(rr.width(), rr.height()) / 2)
                ringYellow.alpha = (255 * (0.65f + 0.35f * pulse)).toInt()
                canvas.drawRoundRect(rr, radius, radius, ringDark)
                canvas.drawRoundRect(rr, radius, radius, ringYellow)
            }
            if (t is PointerTarget.Nav) drawNavArrow(canvas, t.kind)

            if (handVisible) {
                val elapsed = now - handStart
                when (t) {
                    is PointerTarget.Element -> drawTapHand(canvas, elapsed, box.centerX(), box.centerY())
                    is PointerTarget.Nav -> drawTapHand(canvas, elapsed, box.centerX(), height - 36 * d)
                    is PointerTarget.Scroll -> drawScrollHand(canvas, elapsed, t.direction)
                    is PointerTarget.Drag -> drawDragHand(canvas, elapsed, t)
                }
            }
            postInvalidateOnAnimation()
        }

        /** Fly from the bubble in 500 ms (ease-out), then tap in place every 1.2 s. */
        private fun drawTapHand(c: Canvas, elapsed: Long, tx: Float, ty: Float) {
            val (x, y) = if (elapsed < FLY_MS) {
                val f = elapsed / FLY_MS.toFloat()
                val e = 1f - (1f - f) * (1f - f) * (1f - f)
                (fromX + (tx - fromX) * e) to (fromY + (ty - fromY) * e)
            } else {
                tx to ty
            }
            var scale = 1f
            if (elapsed >= FLY_MS) {
                val cycle = ((elapsed - FLY_MS) % TAP_MS) / TAP_MS.toFloat()
                scale = when {
                    cycle < 0.15f -> 1f - 0.15f * (cycle / 0.15f)
                    cycle < 0.30f -> 0.85f + 0.15f * ((cycle - 0.15f) / 0.15f)
                    else -> 1f
                }
                if (cycle < 0.6f) {
                    ripple.alpha = (255 * (1f - cycle / 0.6f)).toInt()
                    c.drawCircle(tx, ty, (6 + 30 * cycle) * d, ripple)
                }
            }
            hand.draw(c, x, y, scale, mirror = tx + 48 * d > width, flip = ty + 88 * d > height)
        }

        /** Swipe across the list in the direction that reveals more content, repeating. */
        private fun drawScrollHand(c: Canvas, elapsed: Long, direction: String) {
            val cycle = (elapsed % SWIPE_MS) / SWIPE_MS.toFloat()
            val f = (cycle / 0.7f).coerceAtMost(1f)
            val inset = 0.25f
            val (x0, y0, x1, y1) = when (direction) {
                // "down" means show what is further down: the finger moves up.
                "up" -> listOf(box.centerX(), box.top + box.height() * inset, box.centerX(), box.bottom - box.height() * inset)
                "left" -> listOf(box.left + box.width() * inset, box.centerY(), box.right - box.width() * inset, box.centerY())
                "right" -> listOf(box.right - box.width() * inset, box.centerY(), box.left + box.width() * inset, box.centerY())
                else -> listOf(box.centerX(), box.bottom - box.height() * inset, box.centerX(), box.top + box.height() * inset)
            }
            val x = x0 + (x1 - x0) * f
            val y = y0 + (y1 - y0) * f
            ripple.alpha = 200
            c.drawLine(x0, y0, x, y, ripple)
            hand.alpha = if (cycle > 0.85f) ((1f - cycle) / 0.15f * 255).toInt() else 255
            hand.draw(c, x, y, 1f, mirror = x + 48 * d > width, flip = y + 88 * d > height)
            hand.alpha = 255
        }

        /** Press on the slider's thumb, slide the way the user should move it, lift; repeating. */
        private fun drawDragHand(c: Canvas, elapsed: Long, t: PointerTarget.Drag) {
            val cycle = (elapsed % SWIPE_MS) / SWIPE_MS.toFloat()
            val start = t.start.coerceIn(0.03f, 0.97f)
            val (x0, y0, x1, y1) = when (t.direction) {
                "left" -> listOf(box.left + box.width() * start, box.centerY(), box.left + box.width() * 0.03f, box.centerY())
                "up" -> listOf(box.centerX(), box.bottom - box.height() * start, box.centerX(), box.top + box.height() * 0.03f)
                "down" -> listOf(box.centerX(), box.bottom - box.height() * start, box.centerX(), box.bottom - box.height() * 0.03f)
                else -> listOf(box.left + box.width() * start, box.centerY(), box.right - box.width() * 0.03f, box.centerY())
            }
            // 0-0.15 press on the thumb, 0.15-0.75 slide, then fade out.
            val f = ((cycle - 0.15f) / 0.6f).coerceIn(0f, 1f)
            val x = x0 + (x1 - x0) * f
            val y = y0 + (y1 - y0) * f
            ripple.alpha = 200
            c.drawLine(x0, y0, x, y, ripple)
            if (cycle < 0.15f) c.drawCircle(x0, y0, (8 + 40 * cycle) * d, ripple)
            hand.alpha = if (cycle > 0.85f) ((1f - cycle) / 0.15f * 255).toInt() else 255
            hand.draw(c, x, y, if (cycle < 0.15f) 0.9f else 1f, mirror = x + 48 * d > width, flip = y + 88 * d > height)
            hand.alpha = 255
        }

        private fun drawNavArrow(c: Canvas, kind: String) {
            // Beside the hand (which taps the bottom centre), not underneath it.
            val cx = width / 2f - 110 * d
            val y = height - 120 * d
            path.reset()
            if (kind == "home") {
                path.moveTo(cx, y + 50 * d); path.lineTo(cx, y)
                path.moveTo(cx - 18 * d, y + 18 * d); path.lineTo(cx, y); path.lineTo(cx + 18 * d, y + 18 * d)
            } else {
                path.moveTo(cx + 40 * d, y + 20 * d); path.lineTo(cx - 40 * d, y + 20 * d)
                path.moveTo(cx - 22 * d, y + 2 * d); path.lineTo(cx - 40 * d, y + 20 * d); path.lineTo(cx - 22 * d, y + 38 * d)
            }
            c.drawPath(path, arrowDark)
            c.drawPath(path, arrowPaint)
        }
    }

    /** A pointing hand drawn from rounded shapes; the index fingertip is the anchor point. */
    private class HandPainter(private val d: Float) {
        var alpha = 255
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#202124")
            style = Paint.Style.STROKE
            strokeWidth = 3.5f * d
            setShadowLayer(4 * d, 0f, 2 * d, Color.argb(120, 0, 0, 0))
        }
        private val cuff = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFC107") }
        private val r = RectF()

        // Shapes in dp, in a 64 x 84 box; fingertip at (22, 0).
        private val parts = listOf(
            floatArrayOf(15f, 0f, 29f, 46f, 7f),   // index finger
            floatArrayOf(28f, 26f, 41f, 52f, 6.5f), // middle
            floatArrayOf(40f, 30f, 52f, 54f, 6f),   // ring
            floatArrayOf(51f, 35f, 62f, 56f, 5.5f), // little
            floatArrayOf(13f, 40f, 62f, 76f, 13f),  // palm
        )
        private val thumb = floatArrayOf(1f, 44f, 17f, 66f, 7.5f)

        fun draw(c: Canvas, tipX: Float, tipY: Float, scale: Float, mirror: Boolean, flip: Boolean) {
            fill.alpha = alpha
            outline.alpha = alpha
            cuff.alpha = alpha
            c.save()
            c.translate(tipX, tipY)
            c.scale(if (mirror) -scale else scale, if (flip) -scale else scale)
            c.translate(-22 * d, 0f)
            // Outline pass, then fill pass, so overlapping shapes read as one hand.
            for (p in listOf(outline, fill)) {
                drawThumb(c, p)
                for (s in parts) {
                    r.set(s[0] * d, s[1] * d, s[2] * d, s[3] * d)
                    c.drawRoundRect(r, s[4] * d, s[4] * d, p)
                }
            }
            r.set(17 * d, 74 * d, 58 * d, 84 * d)
            c.drawRoundRect(r, 3 * d, 3 * d, cuff)
            c.restore()
        }

        private fun drawThumb(c: Canvas, p: Paint) {
            c.save()
            c.rotate(-32f, 9 * d, 55 * d)
            r.set(thumb[0] * d, thumb[1] * d, thumb[2] * d, thumb[3] * d)
            c.drawRoundRect(r, thumb[4] * d, thumb[4] * d, p)
            c.restore()
        }
    }

    companion object {
        const val FLY_MS = 500L
        const val TAP_MS = 1200L
        const val PULSE_MS = 1200L
        const val SWIPE_MS = 1600L
        const val FLASH_MS = 3500L
    }
}
