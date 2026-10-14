package pk.rehnuma.app.screen

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Builds a [ScreenSnapshot] of the app the user is looking at from the accessibility node tree
 * (SPEC 5.2). Text, labels and bounds only; no screen capture of any kind (rule 2).
 * Safe to call from a background thread. No AccessibilityNodeInfo outlives one [read].
 */
class ScreenReader(private val service: AccessibilityService) {

    private class Picked(val root: AccessibilityNodeInfo?, val windowId: Int, val title: String?)

    private class Frame(val node: AccessibilityNodeInfo, val depth: Int, val covered: Set<String>)

    fun read(): ScreenSnapshot? {
        val start = SystemClock.elapsedRealtime()
        val picked = pickRoot() ?: return null
        val root = picked.root ?: return ownAppSnapshot(start)
        try {
            val pkg = root.packageName?.toString() ?: return null
            val (width, height) = screenSize()
            val screen = Rect(0, 0, width, height)
            val raw = ArrayList<RawElement>()
            var hasPassword = false
            var visited = 0
            val rect = Rect()

            val stack = ArrayDeque<Frame>()
            stack.addLast(Frame(root, 0, emptySet()))
            var first = true
            while (stack.isNotEmpty()) {
                val frame = stack.removeLast()
                val node = frame.node
                val isRoot = first
                first = false
                try {
                    if (visited >= MAX_NODES) continue
                    visited++
                    if (frame.depth > MAX_DEPTH || !node.isVisibleToUser) continue
                    node.getBoundsInScreen(rect)
                    if (!rect.isEmpty && !Rect.intersects(rect, screen)) continue

                    if (node.isPassword) hasPassword = true

                    var covered = frame.covered
                    if (!rect.isEmpty) {
                        val text = if (node.isShowingHintText) null else clean(node.text)
                        val desc = clean(node.contentDescription)
                        val editable = node.isEditable
                        val clickable = node.isClickable || node.isLongClickable
                        val checkable = node.isCheckable
                        val scrollable = node.isScrollable
                        val range = node.rangeInfo?.let { listOf(it.min, it.max, it.current) }
                        val interactive = clickable || editable || checkable || scrollable || range != null
                        val own = text ?: desc
                        val keep = interactive || (own != null && own !in covered)
                        if (keep) {
                            var label: String? = null
                            val added = HashSet<String>()
                            text?.let { added += it }
                            desc?.let { added += it }
                            // Only unlabeled clickables borrow their children's text (SPEC 5.2); a list
                            // or input doing so would hide the real labels inside it.
                            if ((clickable || checkable) && !editable && own == null) {
                                val parts = descendantTexts(node)
                                if (parts.isNotEmpty()) {
                                    label = parts.joinToString(" · ").take(MAX_LABEL)
                                    added += parts
                                }
                            }
                            if (added.isNotEmpty()) covered = covered + added
                            val bounds = Rect(rect).apply { intersect(screen) }
                            raw += RawElement(
                                text = text,
                                desc = desc,
                                hint = clean(node.hintText),
                                label = label,
                                viewId = node.viewIdResourceName?.substringAfter(":id/")?.takeIf { it.isNotBlank() },
                                cls = simpleClass(node.className, editable, scrollable),
                                clickable = clickable || checkable,
                                editable = editable,
                                scrollable = scrollable,
                                checked = if (checkable) node.isChecked else null,
                                selected = if (node.isSelected) true else null,
                                interactive = interactive,
                                bounds = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
                                range = range,
                            )
                        }
                    }
                    // Push children in reverse so the first child is visited first.
                    for (i in node.childCount - 1 downTo 0) {
                        val child = node.getChild(i) ?: continue
                        stack.addLast(Frame(child, frame.depth + 1, covered))
                    }
                } finally {
                    if (!isRoot) node.recycleCompat()
                }
            }
            // Anything left (node cap reached) still has to be released.
            while (stack.isNotEmpty()) stack.removeLast().node.recycleCompat()

            val elements = ElementOrdering.finalize(raw)
            val readMs = SystemClock.elapsedRealtime() - start
            if (readMs > SLOW_READ_MS) Log.w(TAG, "slow screen read: ${readMs}ms, $visited nodes")
            return ScreenSnapshot(
                packageName = pkg,
                windowTitle = clean(picked.title),
                screenWidth = width,
                screenHeight = height,
                elements = elements,
                signature = ElementOrdering.signature(pkg, elements),
                capturedAtMs = System.currentTimeMillis(),
                readMs = readMs,
                hasPasswordField = hasPassword,
                windowId = picked.windowId,
            )
        } finally {
            root.recycleCompat()
        }
    }

    /** SPEC 5.2 window selection: skip our overlays and the keyboard, prefer the active app. */
    private fun pickRoot(): Picked? {
        val own = service.packageName
        val windows = try {
            service.windows.orEmpty()
        } catch (e: RuntimeException) {
            emptyList()
        }
        val candidates = windows
            .filter {
                it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                    it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
            }
            .sortedByDescending { it.layer }

        var ownInFront = false
        fun tryWindow(w: AccessibilityWindowInfo): Picked? {
            val root = w.root ?: return null
            if (root.packageName?.toString() == own) {
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION && (w.isFocused || w.isActive)) ownInFront = true
                root.recycleCompat()
                return null
            }
            return Picked(root, w.id, w.title?.toString())
        }

        val systemOnTop = candidates.filter { it.type == AccessibilityWindowInfo.TYPE_SYSTEM && it.isActive }
        val activeApps = candidates.filter {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isFocused || it.isActive)
        }
        val apps = candidates.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        for (w in systemOnTop) tryWindow(w)?.let { return it }
        for (w in activeApps) tryWindow(w)?.let { return it }
        // Rehnuma's own screen is in front: never read it, just say so.
        if (ownInFront) return OWN_APP
        for (w in apps) tryWindow(w)?.let { return it }

        val fallback = service.rootInActiveWindow ?: return null
        if (fallback.packageName?.toString() == own) {
            fallback.recycleCompat()
            return null
        }
        return Picked(fallback, fallback.windowId, null)
    }

    private fun ownAppSnapshot(start: Long): ScreenSnapshot {
        val (width, height) = screenSize()
        return ScreenSnapshot(
            packageName = service.packageName,
            screenWidth = width,
            screenHeight = height,
            elements = emptyList(),
            signature = "own-app",
            capturedAtMs = System.currentTimeMillis(),
            readMs = SystemClock.elapsedRealtime() - start,
        )
    }

    /** First 3 non-blank texts below an unlabeled clickable (SPEC 5.2). */
    private fun descendantTexts(node: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>(3)
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it to 1) }
        var seen = 0
        while (queue.isNotEmpty()) {
            val (n, depth) = queue.removeFirst()
            try {
                if (out.size >= 3 || seen++ >= LABEL_SEARCH_NODES || !n.isVisibleToUser) continue
                val t = (if (n.isShowingHintText) null else clean(n.text)) ?: clean(n.contentDescription)
                if (t != null && t !in out) out += t
                if (depth < LABEL_SEARCH_DEPTH) {
                    for (i in 0 until n.childCount) n.getChild(i)?.let { queue.addLast(it to depth + 1) }
                }
            } finally {
                n.recycleCompat()
            }
        }
        return out
    }

    private fun screenSize(): Pair<Int, Int> {
        val dm = service.getSystemService(DisplayManager::class.java)
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics)
        if (metrics.widthPixels > 0 && metrics.heightPixels > 0) return metrics.widthPixels to metrics.heightPixels
        val r = service.resources.displayMetrics
        return r.widthPixels to r.heightPixels
    }

    companion object {
        private const val TAG = "RehnumaScreenReader"

        /** Marker: Rehnuma's own activity is the active window. Its content is never read. */
        private val OWN_APP = Picked(null, -1, null)
        const val MAX_DEPTH = 40
        const val MAX_NODES = 1500
        const val MAX_TEXT = 80
        const val MAX_LABEL = 60
        private const val LABEL_SEARCH_NODES = 30
        private const val LABEL_SEARCH_DEPTH = 6
        private const val SLOW_READ_MS = 150L
        private val SPACES = Regex("\\s+")

        /** Trim, collapse whitespace, mask long numbers (rule 6), then cap the length. */
        fun clean(s: CharSequence?): String? {
            val t = s?.toString()?.replace(SPACES, " ")?.trim()
            if (t.isNullOrEmpty()) return null
            return Masking.mask(t)!!.take(MAX_TEXT)
        }

        fun simpleClass(name: CharSequence?, editable: Boolean, scrollable: Boolean): String {
            val n = name?.toString()?.substringAfterLast('.').orEmpty()
            return when {
                editable || n.endsWith("EditText") -> "EditText"
                n.endsWith("ImageButton") -> "ImageButton"
                n.endsWith("CheckBox") -> "CheckBox"
                n.endsWith("RadioButton") -> "RadioButton"
                n.contains("Switch") -> "Switch"
                n.endsWith("ToggleButton") -> "ToggleButton"
                n.endsWith("Button") -> "Button"
                n.endsWith("CheckedTextView") -> "CheckBox"
                n.endsWith("TextView") -> "TextView"
                n.endsWith("ImageView") -> "ImageView"
                n.endsWith("SeekBar") -> "SeekBar"
                n.endsWith("Spinner") -> "Spinner"
                n.endsWith("WebView") -> "WebView"
                scrollable || n.endsWith("RecyclerView") || n.endsWith("ListView") ||
                    n.endsWith("ScrollView") || n.endsWith("GridView") || n.endsWith("ViewPager") -> "List"
                else -> "View"
            }
        }
    }
}

/** Nodes are pooled before Android 13; from 13 on, recycle() is a no-op. */
@Suppress("DEPRECATION")
internal fun AccessibilityNodeInfo.recycleCompat() {
    if (Build.VERSION.SDK_INT < 33) {
        try {
            recycle()
        } catch (_: IllegalStateException) {
            // Already recycled.
        }
    }
}
