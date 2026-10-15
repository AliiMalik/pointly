package pk.pointly.app.overlay

import android.animation.ValueAnimator
import android.graphics.Color
import android.view.animation.PathInterpolator

object Palette {
    val ink = Color.parseColor("#13201B")
    val inkSoft = Color.parseColor("#55615B")
    val paper = Color.parseColor("#F6F4EF")
    val line = Color.parseColor("#E6E1D6")
    val brand = Color.parseColor("#0E5E43")
    val brandDeep = Color.parseColor("#0A4632")
    val brandBright = Color.parseColor("#178A62")
    val brandSoft = Color.parseColor("#E2EEE8")
    val signal = Color.parseColor("#F2A93B")
    val signalDeep = Color.parseColor("#D98914")
    val coral = Color.parseColor("#E8574A")
    val coralDeep = Color.parseColor("#B8322A")
    val slate = Color.parseColor("#6B7772")
    val slateDeep = Color.parseColor("#3E4844")
    val danger = Color.parseColor("#B42318")

    fun alpha(color: Int, a: Float): Int = Color.argb((Color.alpha(color) * a.coerceIn(0f, 1f)).toInt(), Color.red(color), Color.green(color), Color.blue(color))
}

object Motion {
    val emphasized = PathInterpolator(0.2f, 0f, 0f, 1f)
    val decelerate = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    val accelerate = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

    val enabled: Boolean get() = ValueAnimator.areAnimatorsEnabled()

    fun spring(t: Float, damping: Float = 0.55f, frequency: Float = 1.6f): Float {
        if (t >= 1f) return 1f
        val decay = Math.exp((-6.0 * t / damping)).toFloat()
        return 1f - decay * Math.cos(2 * Math.PI * frequency * t).toFloat()
    }

    fun easeOutBack(t: Float, s: Float = 1.7f): Float {
        val x = t - 1f
        return x * x * ((s + 1) * x + s) + 1f
    }

    fun easeOutCubic(t: Float): Float {
        val x = 1f - t.coerceIn(0f, 1f)
        return 1f - x * x * x
    }

    fun easeInOutCubic(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) 4 * x * x * x else 1 - Math.pow((-2.0 * x + 2), 3.0).toFloat() / 2
    }
}
