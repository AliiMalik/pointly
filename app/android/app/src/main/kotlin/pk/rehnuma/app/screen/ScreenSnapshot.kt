package pk.rehnuma.app.screen

import kotlinx.serialization.Serializable

/** One element of the current screen as sent to the planner (SPEC 5.2). Strings are already masked. */
@Serializable
data class ScreenElement(
    val id: Int,
    val text: String? = null,         // node.text, trimmed, max 80 chars
    val desc: String? = null,         // contentDescription, max 80 chars
    val hint: String? = null,         // hintText
    val label: String? = null,        // derived label for unlabeled clickables
    val viewId: String? = null,       // short id after ":id/"
    val cls: String,                  // simplified class name
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checked: Boolean? = null,
    val selected: Boolean? = null,
    val bounds: List<Int>,            // [left, top, right, bottom] in screen pixels
) {
    /** What a user would read on this element. */
    val displayLabel: String?
        get() = text ?: desc ?: label ?: hint

    val left get() = bounds[0]
    val top get() = bounds[1]
    val right get() = bounds[2]
    val bottom get() = bounds[3]
    val centerX get() = (bounds[0] + bounds[2]) / 2
    val centerY get() = (bounds[1] + bounds[3]) / 2

    fun containsPoint(x: Int, y: Int) = x in left..right && y in top..bottom
}

@Serializable
data class ScreenSnapshot(
    val packageName: String,
    val windowTitle: String? = null,
    val screenWidth: Int,
    val screenHeight: Int,
    val elements: List<ScreenElement>,
    val signature: String,
    val capturedAtMs: Long,
    val readMs: Long,
    /** True when any visible node reported `isPassword` (SafetyGuard rule a). */
    val hasPasswordField: Boolean = false,
    /** Accessibility window id; a change means a new window. */
    val windowId: Int = -1,
) {
    fun element(id: Int): ScreenElement? = elements.firstOrNull { it.id == id }
}

/**
 * What the StepVerifier and PlanCache remember about a target (SPEC 5.7, 9.2).
 * Never holds a live AccessibilityNodeInfo.
 */
@Serializable
data class TargetSignature(
    val packageName: String,
    val viewId: String? = null,
    val normLabel: String? = null,
    val cls: String,
    val bounds: List<Int>,
) {
    /** Key used by LearningStore and PlanCache: viewId if present, else normalized label. */
    val targetKey: String
        get() = viewId ?: normLabel ?: cls

    /** Finds the element on [snapshot] that is the same target (viewId first, then label). */
    fun findIn(snapshot: ScreenSnapshot): ScreenElement? {
        if (snapshot.packageName != packageName) return null
        viewId?.let { id ->
            val byId = snapshot.elements.filter { it.viewId == id }
            if (byId.size == 1) return byId[0]
            if (byId.size > 1) {
                // Lists reuse ids per row; disambiguate by label.
                byId.firstOrNull { normLabel != null && Labels.normalize(it.displayLabel) == normLabel }?.let { return it }
            }
        }
        if (normLabel != null) {
            return snapshot.elements.firstOrNull { Labels.normalize(it.displayLabel) == normLabel }
        }
        return null
    }

    companion object {
        fun of(packageName: String, e: ScreenElement) = TargetSignature(
            packageName = packageName,
            viewId = e.viewId,
            normLabel = Labels.normalize(e.displayLabel),
            cls = e.cls,
            bounds = e.bounds,
        )
    }
}

object Labels {
    private val SPACES = Regex("\\s+")

    /** Lowercase, trimmed, single-spaced label used for matching targets across screens. */
    fun normalize(s: String?): String? {
        val t = s?.trim()?.lowercase()?.replace(SPACES, " ")
        return if (t.isNullOrEmpty()) null else t
    }
}
