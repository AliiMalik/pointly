package pk.pointly.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import java.util.Locale

object UrduFont {
    val LOCALE: Locale = Locale("ur", "PK")
    private var cached: Typeface? = null
    private var looked = false

    @SuppressLint("DiscouragedApi")
    fun typeface(context: Context): Typeface {
        if (!looked) {
            looked = true
            val id = context.resources.getIdentifier("noto_nastaliq_urdu", "font", context.packageName)
            cached = if (id != 0) runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() else null
        }
        return cached ?: Typeface.DEFAULT_BOLD
    }
}
