package pk.pointly.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import pk.pointly.app.screen.ScreenSnapshot

class DebugBoxesOverlay(context: Context) : FullScreenOverlay(context, BoxesView(context)) {

    fun update(snapshot: ScreenSnapshot?) {
        (view as BoxesView).set(snapshot)
    }

    private class BoxesView(context: Context) : View(context) {
        private var snapshot: ScreenSnapshot? = null
        private var shownAt = 0L
        private val d = resources.displayMetrics.density
        private val location = IntArray(2)
        private val interactiveStroke = stroke(Palette.brandBright, 1.6f)
        private val interactiveFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.alpha(Palette.brandBright, 0.10f) }
        private val textStroke = stroke(Color.parseColor("#2E6BE6"), 1.2f).apply {
            pathEffect = DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f)
        }
        private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.alpha(Palette.ink, 0.88f) }
        private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 10 * d
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private val footerBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.alpha(Palette.ink, 0.9f) }
        private val footerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 12 * d
            typeface = Typeface.MONOSPACE
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.signal }
        private val r = RectF()

        private fun stroke(c: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c
            style = Paint.Style.STROKE
            strokeWidth = w * d
        }

        fun set(s: ScreenSnapshot?) {
            if (snapshot == null && s != null) shownAt = SystemClock.uptimeMillis()
            snapshot = s
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val s = snapshot ?: return
            getLocationOnScreen(location)
            val dx = -location[0].toFloat()
            val dy = -location[1].toFloat()
            val t = if (Motion.enabled) ((SystemClock.uptimeMillis() - shownAt) / 260f).coerceIn(0f, 1f) else 1f
            val a = Motion.easeOutCubic(t)
            val radius = 6 * d
            for (e in s.elements) {
                r.set(e.left + dx, e.top + dy, e.right + dx, e.bottom + dy)
                val interactive = e.clickable || e.editable || e.scrollable
                if (interactive) {
                    interactiveFill.alpha = (26 * a).toInt()
                    interactiveStroke.alpha = (235 * a).toInt()
                    canvas.drawRoundRect(r, radius, radius, interactiveFill)
                    canvas.drawRoundRect(r, radius, radius, interactiveStroke)
                } else {
                    textStroke.alpha = (200 * a).toInt()
                    canvas.drawRoundRect(r, radius, radius, textStroke)
                }
                val label = "${e.id}"
                val w = labelText.measureText(label) + 10 * d
                val h = labelText.textSize + 6 * d
                labelBg.alpha = (225 * a).toInt()
                canvas.drawRoundRect(r.left + 2 * d, r.top + 2 * d, r.left + 2 * d + w, r.top + 2 * d + h, h / 2, h / 2, labelBg)
                canvas.drawText(label, r.left + 7 * d, r.top + 2 * d + labelText.textSize + 1.5f * d, labelText)
            }
            val footer = "${s.packageName} · ${s.elements.size} el · ${s.readMs} ms · off ${location[0]},${location[1]}"
            val fw = footerText.measureText(footer) + 34 * d
            val fh = 32 * d
            val fx = 12 * d
            val fy = height - 64 * d
            canvas.drawRoundRect(fx, fy, fx + fw, fy + fh, fh / 2, fh / 2, footerBg)
            canvas.drawCircle(fx + 15 * d, fy + fh / 2, 4 * d, dot)
            canvas.drawText(footer, fx + 26 * d, fy + fh / 2 + footerText.textSize * 0.35f, footerText)
            if (t < 1f) postInvalidateOnAnimation()
        }
    }
}
