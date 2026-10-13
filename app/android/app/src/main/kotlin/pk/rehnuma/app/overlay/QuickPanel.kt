package pk.rehnuma.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import pk.rehnuma.app.voice.Phrase
import pk.rehnuma.app.voice.QuickTasks

/**
 * Panel opened from the bubble (SPEC 5.8): the five quick tasks and a Roman Urdu text box for noisy
 * rooms. Typing `#12` points at element 12 of the current screen (manual test mode, SPEC 13 Phase 2).
 */
class QuickPanel(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onGoal(goal: String)
        fun onDebugPoint(elementId: Int)
        fun onSnapshot()
    }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val d = context.resources.displayMetrics.density
    private var root: View? = null

    val isShowing get() = root != null

    fun show() {
        if (root != null) return
        val scrim = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setOnClickListener { hide() }
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * d).toInt()
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                cornerRadius = 20 * d
                setColor(Color.WHITE)
            }
            isClickable = true // keep taps on the card from closing the panel
        }
        card.addView(text(Phrase.ASK_GOAL.roman, 22f, bold = true))
        for (goal in QuickTasks.ALL) {
            card.addView(button(goal, filled = true) {
                hide()
                listener.onGoal(goal)
            })
        }
        val input = EditText(context).apply {
            hint = "Yahan likhein (Roman Urdu)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_GO
            minHeight = (56 * d).toInt()
        }
        val submit = {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) {
                hide()
                val debugId = Regex("""^#(\d{1,3})$""").find(t)?.groupValues?.get(1)?.toInt()
                if (debugId != null) listener.onDebugPoint(debugId) else listener.onGoal(t)
            }
        }
        input.setOnEditorActionListener { _, actionId, event ->
            val enterDown = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enterDown) {
                submit()
                true
            } else {
                false
            }
        }
        card.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (12 * d).toInt()
        })
        card.addView(button("Chalein", filled = true) { submit() })
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Snapshot", filled = false) {
            hide()
            listener.onSnapshot()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("Band karein", filled = false) { hide() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)

        val scroll = ScrollView(context).apply { addView(card) }
        // Top of the screen: overlay windows are not resized for the keyboard, so a centered card
        // would hide the text box behind it.
        scrim.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP,
        ).apply {
            val m = (16 * d).toInt()
            setMargins(m, (40 * d).toInt(), m, m)
        })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "RehnumaPanel"
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        wm.addView(scrim, lp)
        root = scrim
    }

    fun hide() {
        val r = root ?: return
        root = null
        try {
            wm.removeViewImmediate(r)
        } catch (_: IllegalArgumentException) {
            // Already removed.
        }
    }

    private fun text(s: String, sp: Float, bold: Boolean = false) = TextView(context).apply {
        text = s
        setTextColor(Color.parseColor("#111111"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 0, 0, (8 * d).toInt())
    }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        minHeight = (56 * d).toInt()
        setTextColor(if (filled) Color.WHITE else Color.parseColor("#0B7A3E"))
        background = GradientDrawable().apply {
            cornerRadius = 14 * d
            if (filled) setColor(Color.parseColor("#0B7A3E")) else {
                setColor(Color.WHITE)
                setStroke((2 * d).toInt(), Color.parseColor("#0B7A3E"))
            }
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (8 * d).toInt()
            marginStart = (2 * d).toInt()
            marginEnd = (2 * d).toInt()
        }
        setOnClickListener { onClick() }
    }
}
