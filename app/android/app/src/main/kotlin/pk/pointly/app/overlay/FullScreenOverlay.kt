package pk.pointly.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.View
import android.view.WindowManager

open class FullScreenOverlay(protected val context: Context, protected val view: View) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val location = IntArray(2)

    var isShowing = false
        private set

    val offsetX: Int
        get() {
            view.getLocationOnScreen(location)
            return location[0]
        }
    val offsetY: Int
        get() {
            view.getLocationOnScreen(location)
            return location[1]
        }

    fun show() {
        if (isShowing) return
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        lp.title = javaClass.simpleName
        wm.addView(view, lp)
        isShowing = true
    }

    fun hide() {
        if (!isShowing) return
        isShowing = false
        try {
            wm.removeViewImmediate(view)
        } catch (_: IllegalArgumentException) {
        }
    }
}
