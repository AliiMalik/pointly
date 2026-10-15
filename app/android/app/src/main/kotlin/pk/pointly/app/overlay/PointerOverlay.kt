package pk.pointly.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

sealed class PointerTarget {
    data class Element(val bounds: List<Int>) : PointerTarget()
    data class Scroll(val bounds: List<Int>, val direction: String) : PointerTarget()
    data class Nav(val kind: String) : PointerTarget()

    data class Drag(val bounds: List<Int>, val direction: String, val start: Float) : PointerTarget()
}

class PointerOverlay(context: Context) : FullScreenOverlay(context, FrameLayout(context)) {

    private val density = context.resources.displayMetrics.density
    private val root = view as FrameLayout
    private val pointer = PointerView(context)
    private val caption = TextView(context)
    private val handler = Handler(Looper.getMainLooper())
    private var arrived: Runnable? = null
    private var captionShown = false
    private var captionText: String? = null
    private var lastSwapAt = 0L

    init {
        root.addView(pointer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        caption.apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setLineSpacing(2 * density, 1f)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            val h = (20 * density).toInt()
            setPaddingRelative(h, (16 * density).toInt(), h, (16 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 22 * density
                setColor(Palette.alpha(Palette.ink, 0.96f))
                setStroke((1 * density).toInt(), Palette.alpha(Color.WHITE, 0.08f))
            }
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Palette.signal)
                setSize((10 * density).toInt(), (10 * density).toInt())
            }
            setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
            compoundDrawablePadding = (14 * density).toInt()
            elevation = 14 * density
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, 22 * density)
                    o.alpha = 0.5f
                }
            }
            visibility = View.GONE
        }
        root.clipChildren = false
        root.addView(caption, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }

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
        handler.removeCallbacks(restoreCaption)
        setCaption(captionText, urduScript, target)
        root.post {
            val ox = offsetX
            val oy = offsetY
            pointer.configure(target, from, ox, oy, withHand, withRing)
            val done = Runnable { onArrived?.invoke() }
            arrived = done
            handler.postDelayed(done, if (target != null && withHand && Motion.enabled) FLY_MS else 0L)
        }
    }

    fun showCaption(text: String?, urduScript: Boolean) = show(null, text, urduScript, null)

    fun flashCaption(text: String, urduScript: Boolean, ms: Long = FLASH_MS) {
        if (!isShowing) {
            showCaption(text, urduScript)
            return
        }
        val previous = captionText
        val wasVisible = captionShown
        applyDirection(urduScript)
        swapText(text)
        handler.removeCallbacks(restoreCaption)
        restoreCaption = Runnable {
            if (wasVisible && previous != null) swapText(previous) else hideCaption()
        }
        handler.postDelayed(restoreCaption, ms)
    }

    private var restoreCaption = Runnable {}

    fun moveTo(target: PointerTarget) {
        if (!isShowing) return
        root.post { pointer.moveTo(target, offsetX, offsetY) }
    }

    fun revealHand(from: Point?) {
        if (!isShowing) return
        pointer.reveal(from)
    }

    fun hideAll() {
        handler.removeCallbacks(restoreCaption)
        arrived?.let { handler.removeCallbacks(it) }
        arrived = null
        pointer.clear()
        caption.animate().cancel()
        caption.visibility = View.GONE
        captionShown = false
        captionText = null
        hide()
    }

    var keyboardTop: Int? = null
        set(value) {
            field = value
            pointer.keyboardTopInView = value?.let { (it - offsetY).toFloat() }
        }

    private fun applyDirection(urdu: Boolean) {
        caption.textDirection = if (urdu) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
        caption.layoutDirection = if (urdu) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        caption.typeface = if (urdu) UrduFont.typeface(context) else Typeface.create("sans-serif-medium", Typeface.NORMAL)
        caption.textLocale = if (urdu) UrduFont.LOCALE else java.util.Locale.getDefault()
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (urdu) 21f else 20f)
    }

    private fun swapText(text: String) {
        val now = SystemClock.uptimeMillis()
        val rapid = now - lastSwapAt < RAPID_MS
        lastSwapAt = now
        captionText = text
        if (!captionShown || caption.visibility != View.VISIBLE || !Motion.enabled) {
            caption.text = text
            enterCaption()
            return
        }
        if (caption.text?.toString() == text) return
        caption.animate().cancel()
        if (rapid) {
            caption.alpha = 1f
            caption.translationY = 0f
            caption.text = text
            return
        }
        caption.animate().alpha(0f).setDuration(90).setInterpolator(Motion.accelerate).withEndAction {
            caption.text = text
            caption.animate().alpha(1f).translationY(0f).setDuration(180).setInterpolator(Motion.decelerate).start()
        }.start()
    }

    private fun enterCaption() {
        val wasShown = captionShown
        captionShown = true
        if (wasShown && caption.visibility == View.VISIBLE) {
            caption.animate().cancel()
            caption.animate().alpha(1f).translationY(0f).setDuration(if (Motion.enabled) 160 else 0).start()
            return
        }
        caption.visibility = View.VISIBLE
        if (!Motion.enabled) {
            caption.alpha = 1f
            caption.translationY = 0f
            return
        }
        val lp = caption.layoutParams as FrameLayout.LayoutParams
        val fromBelow = lp.gravity and Gravity.BOTTOM == Gravity.BOTTOM
        caption.alpha = 0f
        caption.translationY = (if (fromBelow) 18 else -18) * density
        caption.animate().alpha(1f).translationY(0f).setDuration(280).setInterpolator(Motion.decelerate).start()
    }

    private fun hideCaption() {
        captionShown = false
        captionText = null
        caption.animate().cancel()
        if (!Motion.enabled) {
            caption.visibility = View.GONE
            return
        }
        caption.animate().alpha(0f).setDuration(140).setInterpolator(Motion.accelerate).withEndAction {
            caption.visibility = View.GONE
        }.start()
    }

    private fun setCaption(text: String?, urdu: Boolean, target: PointerTarget?) {
        if (text.isNullOrBlank()) {
            hideCaption()
            return
        }
        applyDirection(urdu)
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
            val overlayH = root.height.takeIf { it > 0 } ?: screenH
            bottom = maxOf(bottom, overlayH - (kb - offsetY) + (12 * density).toInt())
        }
        lp.setMargins(m, (56 * density).toInt(), m, bottom)
        val newGravity = if (target == null || targetTop) Gravity.BOTTOM else Gravity.TOP
        val moved = lp.gravity != newGravity
        lp.gravity = newGravity
        caption.layoutParams = lp
        if (moved && caption.visibility == View.VISIBLE) {
            caption.animate().cancel()
            caption.visibility = View.GONE
            captionShown = false
            captionText = text
            caption.text = text
            enterCaption()
        } else {
            swapText(text)
        }
    }

    @SuppressLint("ViewConstructor")
    private class PointerView(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private var target: PointerTarget? = null
        private val box = RectF()
        private var from = PointF()
        private var bubble: PointF? = null
        var keyboardTopInView: Float? = null
        private val holes = Path()
        private var handVisible = false
        private var ringVisible = false
        private var handStart = 0L
        private var lockStart = 0L
        private val hand = HandSprite(d)

        private val glow = stroke(Palette.alpha(Palette.signal, 0.22f), 12 * d)
        private val halo = stroke(Palette.alpha(Color.WHITE, 0.92f), 7 * d)
        private val frame = stroke(Palette.signal, 3.5f * d)
        private val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.ink }
        private val rippleA = stroke(Palette.signal, 3 * d)
        private val rippleB = stroke(Color.WHITE, 2 * d)
        private val press = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.alpha(Palette.signal, 0.35f) }
        private val trail = stroke(Palette.signal, 7 * d).apply { strokeCap = Paint.Cap.ROUND }
        private val trailHalo = stroke(Palette.alpha(Color.WHITE, 0.85f), 11 * d).apply { strokeCap = Paint.Cap.ROUND }
        private val arrowPaint = stroke(Palette.signal, 6 * d).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val arrowHalo = stroke(Palette.alpha(Color.WHITE, 0.92f), 11 * d).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val rr = RectF()
        private val path = Path()
        private val scrimPath = Path()

        private fun stroke(c: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c
            style = Paint.Style.STROKE
            strokeWidth = w
        }

        private fun setBox(t: PointerTarget?, ox: Int, oy: Int) {
            when (t) {
                is PointerTarget.Element -> box.set(t.bounds[0] - ox.toFloat(), t.bounds[1] - oy.toFloat(), t.bounds[2] - ox.toFloat(), t.bounds[3] - oy.toFloat())
                is PointerTarget.Scroll -> box.set(t.bounds[0] - ox.toFloat(), t.bounds[1] - oy.toFloat(), t.bounds[2] - ox.toFloat(), t.bounds[3] - oy.toFloat())
                is PointerTarget.Drag -> box.set(t.bounds[0] - ox.toFloat(), t.bounds[1] - oy.toFloat(), t.bounds[2] - ox.toFloat(), t.bounds[3] - oy.toFloat())
                is PointerTarget.Nav -> box.set(width / 2f - 60 * d, height - 72 * d, width / 2f + 60 * d, height.toFloat())
                null -> box.setEmpty()
            }
        }

        fun configure(t: PointerTarget?, src: Point?, ox: Int, oy: Int, withHand: Boolean, withRing: Boolean) {
            target = t
            setBox(t, ox, oy)
            setFrom(src, ox, oy)
            bubble = src?.let { PointF(it.x - ox.toFloat(), it.y - oy.toFloat()) }
            ringVisible = withRing && t != null
            handVisible = withHand && t != null
            handStart = SystemClock.uptimeMillis()
            lockStart = handStart + if (handVisible) FLY_MS / 2 else 0L
            postInvalidateOnAnimation()
        }

        fun moveTo(t: PointerTarget, ox: Int, oy: Int) {
            target = t
            if (t !is PointerTarget.Nav) setBox(t, ox, oy)
            val now = SystemClock.uptimeMillis()
            if (now - handStart < FLY_MS) handStart = now - FLY_MS
            if (now - lockStart < LOCK_MS) lockStart = now - LOCK_MS
            postInvalidateOnAnimation()
        }

        fun reveal(src: Point?) {
            if (target == null || handVisible) return
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            setFrom(src, loc[0], loc[1])
            ringVisible = true
            handVisible = true
            handStart = SystemClock.uptimeMillis()
            postInvalidateOnAnimation()
        }

        private fun setFrom(src: Point?, ox: Int, oy: Int) {
            from.set((src?.x ?: (width / 2)).toFloat() - ox, (src?.y ?: height).toFloat() - oy)
        }

        fun clear() {
            target = null
            handVisible = false
            ringVisible = false
            invalidate()
        }

        private fun progress(since: Long, ms: Long, now: Long): Float =
            if (!Motion.enabled) 1f else ((now - since) / ms.toFloat()).coerceIn(0f, 1f)

        override fun onDraw(canvas: Canvas) {
            val t = target ?: return
            val now = SystemClock.uptimeMillis()
            val animated = Motion.enabled
            val pulse = if (animated) sin((now % PULSE_MS) / PULSE_MS.toFloat() * 2 * PI).toFloat() * 0.5f + 0.5f else 0.5f
            val lock = progress(lockStart, LOCK_MS, now)

            if (t !is PointerTarget.Nav) frameRect(lock, pulse)

            val large = box.width() * box.height() > 0.45f * width * height
            if (handVisible && t !is PointerTarget.Nav && !large) {
                val dim = Motion.easeOutCubic(progress(lockStart, SCRIM_MS, now)) * SCRIM_ALPHA
                if (dim > 0f) drawScrim(canvas, dim)
            }

            if (ringVisible && t !is PointerTarget.Nav && !large) {
                val a = (lock * 1.8f).coerceAtMost(1f)
                val r = min(18 * d, min(rr.width(), rr.height()) / 2)
                glow.alpha = (255 * a * (0.12f + 0.16f * pulse)).toInt()
                halo.alpha = (235 * a).toInt()
                frame.alpha = (255 * a).toInt()
                canvas.drawRoundRect(rr, r, r, glow)
                canvas.drawRoundRect(rr, r, r, halo)
                canvas.drawRoundRect(rr, r, r, frame)
            }
            if (t is PointerTarget.Nav) drawNavArrow(canvas, t.kind, now)

            if (handVisible) {
                val elapsed = if (animated) now - handStart else FLY_MS + TAP_MS / 2
                when (t) {
                    is PointerTarget.Element -> drawTapHand(canvas, elapsed, box.centerX(), box.centerY())
                    is PointerTarget.Nav -> drawTapHand(canvas, elapsed, box.centerX(), height - 40 * d)
                    is PointerTarget.Scroll -> drawSwipe(canvas, elapsed, scrollPath(t.direction))
                    is PointerTarget.Drag -> drawSwipe(canvas, elapsed, dragPath(t), grab = true)
                }
            }
            if (animated && (ringVisible || handVisible || t is PointerTarget.Nav)) postInvalidateOnAnimation()
        }

        private fun frameRect(lock: Float, pulse: Float) {
            val inflate = (7 + 3 * pulse) * d + (1f - Motion.easeOutBack(lock, 1.4f)) * 20 * d
            val edge = 4 * d
            rr.set(
                (box.left - inflate).coerceAtLeast(edge),
                (box.top - inflate).coerceAtLeast(edge),
                (box.right + inflate).coerceAtMost(width - edge),
                (box.bottom + inflate).coerceAtMost(height - edge),
            )
        }

        private fun drawScrim(c: Canvas, alpha: Float) {
            val r = min(18 * d, min(rr.width(), rr.height()) / 2)
            holes.reset()
            holes.addRoundRect(rr, r, r, Path.Direction.CW)
            bubble?.let { holes.addCircle(it.x, it.y, 34 * d, Path.Direction.CW) }
            scrimPath.reset()
            scrimPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            scrimPath.op(holes, Path.Op.DIFFERENCE)
            scrim.alpha = (255 * alpha).toInt()
            val kb = keyboardTopInView
            if (kb != null && kb > 0f && kb < height) {
                c.save()
                c.clipRect(0f, 0f, width.toFloat(), kb)
                c.drawPath(scrimPath, scrim)
                c.restore()
            } else {
                c.drawPath(scrimPath, scrim)
            }
        }

        private fun drawTapHand(c: Canvas, elapsed: Long, tx: Float, ty: Float) {
            val mirror = tx + 52 * d > width
            val flip = ty + 92 * d > height
            if (elapsed < FLY_MS) {
                val f = Motion.easeInOutCubic(elapsed / FLY_MS.toFloat())
                val dx = tx - from.x
                val dy = ty - from.y
                val dist = hypot(dx, dy).coerceAtLeast(1f)
                val bend = 0.22f * dist * (if (from.x > tx) 1f else -1f)
                val cx = (from.x + tx) / 2 + (-dy / dist) * bend
                val cy = (from.y + ty) / 2 + (dx / dist) * bend
                val u = 1 - f
                val x = u * u * from.x + 2 * u * f * cx + f * f * tx
                val y = u * u * from.y + 2 * u * f * cy + f * f * ty
                val vx = 2 * u * (cx - from.x) + 2 * f * (tx - cx)
                val vy = 2 * u * (cy - from.y) + 2 * f * (ty - cy)
                val sway = (atan2(vx, -vy) * 180 / PI).toFloat().coerceIn(-40f, 40f) * (1 - f) * 0.35f
                val appear = (f / 0.25f).coerceAtMost(1f)
                hand.draw(c, x, y, scale = 0.7f + 0.3f * Motion.easeOutCubic(appear), alpha = appear, mirror = mirror, flip = flip, extraTilt = sway)
                return
            }
            val cycle = ((elapsed - FLY_MS) % TAP_MS) / TAP_MS.toFloat()
            val scale = when {
                cycle < 0.12f -> 1f - 0.12f * Motion.easeOutCubic(cycle / 0.12f)
                cycle < 0.32f -> 0.88f + 0.12f * Motion.easeOutBack((cycle - 0.12f) / 0.2f, 2.2f)
                else -> 1f
            }
            if (cycle < 0.3f) {
                press.alpha = (90 * (1f - cycle / 0.3f)).toInt()
                c.drawCircle(tx, ty, 12 * d, press)
            }
            drawRipple(c, tx, ty, cycle, rippleA, 0.08f, 34f)
            drawRipple(c, tx, ty, cycle, rippleB, 0.18f, 26f)
            hand.draw(c, tx, ty, scale = scale, alpha = 1f, mirror = mirror, flip = flip)
        }

        private fun drawRipple(c: Canvas, x: Float, y: Float, cycle: Float, paint: Paint, lag: Float, reach: Float) {
            val p = (cycle - lag) / 0.55f
            if (p < 0f || p > 1f) return
            paint.alpha = (255 * (1f - p) * 0.95f).toInt()
            c.drawCircle(x, y, (8 + reach * Motion.easeOutCubic(p)) * d, paint)
        }

        private fun scrollPath(direction: String): FloatArray {
            val inset = 0.25f
            return when (direction) {
                "up" -> floatArrayOf(box.centerX(), box.top + box.height() * inset, box.centerX(), box.bottom - box.height() * inset)
                "left" -> floatArrayOf(box.left + box.width() * inset, box.centerY(), box.right - box.width() * inset, box.centerY())
                "right" -> floatArrayOf(box.right - box.width() * inset, box.centerY(), box.left + box.width() * inset, box.centerY())
                else -> floatArrayOf(box.centerX(), box.bottom - box.height() * inset, box.centerX(), box.top + box.height() * inset)
            }
        }

        private fun dragPath(t: PointerTarget.Drag): FloatArray {
            val start = t.start.coerceIn(0.03f, 0.97f)
            return when (t.direction) {
                "left" -> floatArrayOf(box.left + box.width() * start, box.centerY(), box.left + box.width() * 0.03f, box.centerY())
                "up" -> floatArrayOf(box.centerX(), box.bottom - box.height() * start, box.centerX(), box.top + box.height() * 0.03f)
                "down" -> floatArrayOf(box.centerX(), box.bottom - box.height() * start, box.centerX(), box.bottom - box.height() * 0.03f)
                else -> floatArrayOf(box.left + box.width() * start, box.centerY(), box.right - box.width() * 0.03f, box.centerY())
            }
        }

        private fun drawSwipe(c: Canvas, elapsed: Long, p: FloatArray, grab: Boolean = false) {
            val (x0, y0, x1, y1) = p
            val cycle = (elapsed % SWIPE_MS) / SWIPE_MS.toFloat()
            val f = Motion.easeInOutCubic(((cycle - 0.18f) / 0.55f).coerceIn(0f, 1f))
            val x = x0 + (x1 - x0) * f
            val y = y0 + (y1 - y0) * f
            val fade = when {
                cycle < 0.08f -> cycle / 0.08f
                cycle > 0.82f -> ((1f - cycle) / 0.18f).coerceIn(0f, 1f)
                else -> 1f
            }
            if (f > 0f) {
                trail.shader = LinearGradient(x0, y0, x, y, Palette.alpha(Palette.signal, 0f), Palette.alpha(Palette.signal, 0.9f * fade), Shader.TileMode.CLAMP)
                trailHalo.alpha = (150 * fade).toInt()
                c.drawLine(x0, y0, x, y, trailHalo)
                c.drawLine(x0, y0, x, y, trail)
            }
            val touchdown = (cycle / 0.18f).coerceIn(0f, 1f)
            if (cycle < 0.3f) {
                press.alpha = (110 * (1f - (cycle / 0.3f))).toInt()
                c.drawCircle(x0, y0, (10 + (if (grab) 26 else 14) * Motion.easeOutCubic(touchdown)) * d, press)
            }
            val scale = if (cycle < 0.18f) 1f - 0.1f * Motion.easeOutCubic(touchdown) else 0.9f + 0.1f * f
            hand.draw(c, x, y, scale = scale, alpha = fade, mirror = x + 52 * d > width, flip = y + 92 * d > height)
        }

        private fun drawNavArrow(c: Canvas, kind: String, now: Long) {
            val bounce = if (Motion.enabled) sin((now % 900) / 900.0 * 2 * PI).toFloat() * 7 * d else 0f
            val cx = width / 2f - 110 * d
            val y = height - 124 * d
            path.reset()
            if (kind == "home") {
                val yy = y + bounce
                path.moveTo(cx, yy + 54 * d); path.lineTo(cx, yy)
                path.moveTo(cx - 18 * d, yy + 18 * d); path.lineTo(cx, yy); path.lineTo(cx + 18 * d, yy + 18 * d)
            } else {
                val xx = cx + bounce
                path.moveTo(xx + 40 * d, y + 20 * d); path.lineTo(xx - 40 * d, y + 20 * d)
                path.moveTo(xx - 22 * d, y + 2 * d); path.lineTo(xx - 40 * d, y + 20 * d); path.lineTo(xx - 22 * d, y + 38 * d)
            }
            c.drawPath(path, arrowHalo)
            c.drawPath(path, arrowPaint)
        }
    }

    private class HandSprite(private val d: Float) {
        private val pad = 14 * d
        private val bitmap: Bitmap
        private val tipX: Float
        private val tipY: Float
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val m = Matrix()

        init {
            val u = d
            val w = (58 * u + pad * 2).toInt()
            val h = (84 * u + pad * 2).toInt()
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bitmap)
            c.translate(pad, pad)
            val shape = silhouette(u)
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Palette.alpha(Color.BLACK, 0.28f)
                maskFilter = BlurMaskFilter(6 * u, BlurMaskFilter.Blur.NORMAL)
            }
            c.save()
            c.translate(2.5f * u, 5 * u)
            c.drawPath(shape, shadow)
            c.restore()
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, 58 * u, 84 * u, Color.WHITE, Color.parseColor("#EFEBE4"), Shader.TileMode.CLAMP)
            }
            c.drawPath(shape, fill)
            val crease = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#CFC9BE")
                strokeWidth = 1.6f * u
                strokeCap = Paint.Cap.ROUND
            }
            c.drawLine(38.5f * u, 35 * u, 38.5f * u, 43 * u, crease)
            c.drawLine(48.5f * u, 39 * u, 48.5f * u, 46 * u, crease)
            c.save()
            c.clipPath(shape)
            c.drawRect(0f, 72 * u, 58 * u, 84 * u, Paint().apply { color = Palette.signal })
            c.restore()
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2.4f * u
                strokeJoin = Paint.Join.ROUND
                color = Palette.ink
            }
            c.drawPath(shape, outline)
            tipX = pad + 20 * u
            tipY = pad
        }

        private fun silhouette(u: Float): Path {
            fun rr(l: Float, t: Float, r: Float, b: Float, rad: Float) =
                Path().apply { addRoundRect(RectF(l * u, t * u, r * u, b * u), rad * u, rad * u, Path.Direction.CW) }
            val p = rr(13f, 0f, 27f, 46f, 7f)
            for (part in listOf(rr(26f, 27f, 40f, 52f, 7f), rr(38f, 31f, 50f, 54f, 6f), rr(48f, 36f, 58f, 56f, 5f), rr(11f, 38f, 58f, 84f, 15f))) {
                p.op(part, Path.Op.UNION)
            }
            val thumb = rr(-1f, 44f, 16f, 66f, 8f)
            thumb.transform(Matrix().apply { setRotate(-32f, 9 * u, 55 * u) })
            p.op(thumb, Path.Op.UNION)
            return p
        }

        fun draw(c: Canvas, x: Float, y: Float, scale: Float, alpha: Float, mirror: Boolean, flip: Boolean, extraTilt: Float = 0f) {
            if (alpha <= 0.01f) return
            paint.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
            m.reset()
            m.postTranslate(-tipX, -tipY)
            m.postRotate(-12f + extraTilt)
            m.postScale(if (mirror) -scale else scale, if (flip) -scale else scale)
            m.postTranslate(x, y)
            c.drawBitmap(bitmap, m, paint)
        }
    }

    companion object {
        const val FLY_MS = 560L
        const val TAP_MS = 1400L
        const val PULSE_MS = 1600L
        const val SWIPE_MS = 1900L
        const val FLASH_MS = 3500L
        private const val LOCK_MS = 420L
        private const val SCRIM_MS = 360L
        private const val SCRIM_ALPHA = 0.30f
        private const val RAPID_MS = 400L
    }
}
