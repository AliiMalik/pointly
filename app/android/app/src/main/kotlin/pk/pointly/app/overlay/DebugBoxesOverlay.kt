package pk.pointly.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import pk.pointly.app.screen.ScreenSnapshot

class DebugBoxesOverlay(context: Context) : FullScreenOverlay(context, BoxesView(context)) {

    fun update(snapshot: ScreenSnapshot?) {
        (view as BoxesView).snapshot = snapshot
        view.invalidate()
    }

    private class BoxesView(context: Context) : View(context) {
        var snapshot: ScreenSnapshot? = null
        private val density = resources.displayMetrics.density
        private val location = IntArray(2)
        private val interactive = stroke(Color.argb(230, 0, 200, 83))
        private val text = stroke(Color.argb(200, 41, 121, 255))
        private val labelBg = Paint().apply { color = Color.argb(200, 0, 0, 0) }
        private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 10 * density
        }
        private val info = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 12 * density
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }
        private val r = RectF()

        private fun stroke(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }

        override fun onDraw(canvas: Canvas) {
            val s = snapshot ?: return
            getLocationOnScreen(location)
            val dx = -location[0].toFloat()
            val dy = -location[1].toFloat()
            for (e in s.elements) {
                r.set(e.left + dx, e.top + dy, e.right + dx, e.bottom + dy)
                val paint = if (e.clickable || e.editable || e.scrollable) interactive else text
                canvas.drawRect(r, paint)
                val label = "#${e.id}"
                val w = labelText.measureText(label) + 4 * density
                val h = labelText.textSize + 2 * density
                canvas.drawRect(r.left, r.top, r.left + w, r.top + h, labelBg)
                canvas.drawText(label, r.left + 2 * density, r.top + labelText.textSize, labelText)
            }
            val footer = "${s.packageName} · ${s.elements.size} el · ${s.readMs} ms · off ${location[0]},${location[1]}"
            canvas.drawText(footer, 8 * density, height - 48 * density, info)
        }
    }
}
