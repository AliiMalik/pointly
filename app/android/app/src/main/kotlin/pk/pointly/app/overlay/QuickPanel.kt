package pk.pointly.app.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import pk.pointly.app.config.AppConfig
import pk.pointly.app.voice.Phrase
import pk.pointly.app.voice.QuickTasks

class QuickPanel(
    private val context: Context,
    private val language: () -> String,
    private val showDebug: () -> Boolean,
    private val listener: Listener,
) {

    interface Listener {
        fun onGoal(goal: String)
        fun onDebugPoint(elementId: Int)
        fun onSnapshot()
    }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val d = context.resources.displayMetrics.density
    private var root: FrameLayout? = null
    private var card: View? = null

    val isShowing get() = root != null

    fun show() {
        if (root != null) return
        val lang = language()
        val urdu = lang == AppConfig.LANG_UR
        val scrim = FrameLayout(context).apply {
            setBackgroundColor(Palette.alpha(Palette.ink, 0.45f))
            setOnClickListener { hide() }
            layoutDirection = if (urdu) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * d).toInt()
            setPadding(p, (22 * d).toInt(), p, p)
            background = GradientDrawable().apply {
                cornerRadius = 28 * d
                setColor(Palette.paper)
            }
            elevation = 24 * d
            isClickable = true
        }
        sheet.addView(text(Phrase.ASK_GOAL.line.pick(lang), 24f, bold = true, color = Palette.ink, urdu = urdu))
        sheet.addView(text(if (urdu) "کوئی کام چنیں یا لکھیں" else "Pick a task or type your own", 16f, color = Palette.inkSoft, urdu = urdu).apply {
            setPadding(0, (2 * d).toInt(), 0, (14 * d).toInt())
        })
        for (task in QuickTasks.ALL) {
            sheet.addView(taskRow(task.pick(lang), urdu) {
                if (isShowing) {
                    hide()
                    listener.onGoal(task.en)
                }
            })
        }
        val input = EditText(context).apply {
            hint = if (urdu) "یہاں لکھیں" else "Type here"
            setHintTextColor(Palette.inkSoft)
            setTextColor(Palette.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_GO
            minHeight = (56 * d).toInt()
            val h = (16 * d).toInt()
            setPaddingRelative(h, 0, h, 0)
            background = GradientDrawable().apply {
                cornerRadius = 14 * d
                setColor(Color.WHITE)
                setStroke((1.5f * d).toInt(), Palette.line)
            }
            setOnFocusChangeListener { v, focused ->
                (v.background as GradientDrawable).setStroke(((if (focused) 2f else 1.5f) * d).toInt(), if (focused) Palette.brand else Palette.line)
            }
        }
        val submit = {
            val t = input.text.toString().trim()
            if (t.isNotEmpty() && isShowing) {
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
        sheet.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (10 * d).toInt()
        })
        sheet.addView(primaryButton(if (urdu) "چلیں" else "Go") { submit() })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (4 * d).toInt(), 0, 0)
        }
        if (showDebug()) {
            row.addView(textButton("Snapshot") {
                if (isShowing) {
                    hide()
                    listener.onSnapshot()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        row.addView(textButton(if (urdu) "بند کریں" else "Close") { hide() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sheet.addView(row)

        val scroll = ScrollView(context).apply {
            addView(sheet)
            clipToPadding = false
            isVerticalScrollBarEnabled = false
        }
        scrim.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP,
        ).apply {
            val m = (12 * d).toInt()
            setMargins(m, (36 * d).toInt(), m, m)
        })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "PointlyPanel"
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        wm.addView(scrim, lp)
        root = scrim
        card = scroll
        if (Motion.enabled) {
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(200).setInterpolator(Motion.decelerate).start()
            scroll.translationY = -28 * d
            scroll.scaleX = 0.97f
            scroll.scaleY = 0.97f
            scroll.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(Motion.emphasized).start()
        }
    }

    fun hide() {
        val r = root ?: return
        root = null
        val c = card
        card = null
        val remove = {
            try {
                wm.removeViewImmediate(r)
            } catch (_: IllegalArgumentException) {
            }
        }
        if (!Motion.enabled || c == null) {
            remove()
            return
        }
        try {
            val lp = r.layoutParams as WindowManager.LayoutParams
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            wm.updateViewLayout(r, lp)
        } catch (_: IllegalArgumentException) {
        }
        c.animate().translationY(-16 * d).setDuration(150).setInterpolator(Motion.accelerate).start()
        r.animate().alpha(0f).setDuration(150).setInterpolator(Motion.accelerate).withEndAction { remove() }.start()
    }

    private fun text(s: String, sp: Float, bold: Boolean = false, medium: Boolean = false, color: Int, urdu: Boolean) = TextView(context).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (urdu) sp + 1 else sp)
        typeface = when {
            urdu -> UrduFont.typeface(context)
            bold -> Typeface.create("sans-serif-medium", Typeface.BOLD)
            medium -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.create("sans-serif", Typeface.NORMAL)
        }
        if (urdu) setLineSpacing(4 * d, 1f)
        letterSpacing = if (bold && !urdu) -0.01f else 0f
    }

    private fun pressFeedback(v: View) {
        v.setOnTouchListener { view, e ->
            if (Motion.enabled) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.98f).scaleY(0.98f).setDuration(90).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                }
            }
            false
        }
    }

    private fun taskRow(label: String, urdu: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (64 * d).toInt()
            val h = (16 * d).toInt()
            setPaddingRelative(h, (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            val shape = GradientDrawable().apply {
                cornerRadius = 18 * d
                setColor(Color.WHITE)
                setStroke((1 * d).toInt(), Palette.line)
            }
            background = RippleDrawable(ColorStateList.valueOf(Palette.alpha(Palette.brand, 0.12f)), shape, null)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (8 * d).toInt()
            }
        }
        val dot = View(context).apply {
            val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.brandSoft) }
            val ring = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke((2.5f * d).toInt(), Palette.brand)
            }
            val core = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.signal) }
            background = LayerDrawable(arrayOf(bg, ring, core)).apply {
                val i1 = (7 * d).toInt()
                val i2 = (11.5f * d).toInt()
                setLayerInset(1, i1, i1, i1, i1)
                setLayerInset(2, i2, i2, i2, i2)
            }
        }
        dot.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        row.addView(dot, LinearLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt()).apply { marginEnd = (14 * d).toInt() })
        row.addView(text(label, 18f, medium = true, color = Palette.ink, urdu = urdu), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text("›", 26f, color = Color.parseColor("#8A938E"), urdu = false).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        pressFeedback(row)
        return row
    }

    private fun primaryButton(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        minHeight = (58 * d).toInt()
        val shape = GradientDrawable().apply {
            cornerRadius = 16 * d
            setColor(Palette.brand)
        }
        background = RippleDrawable(ColorStateList.valueOf(Palette.alpha(Color.WHITE, 0.25f)), shape, null)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (12 * d).toInt()
        }
        pressFeedback(this)
    }

    private fun textButton(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Palette.brand)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        minHeight = (52 * d).toInt()
        background = RippleDrawable(
            ColorStateList.valueOf(Palette.alpha(Palette.brand, 0.12f)),
            null,
            GradientDrawable().apply { cornerRadius = 14 * d; setColor(Color.WHITE) },
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }
}
