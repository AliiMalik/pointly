package pk.pointly.app.session

import pk.pointly.app.net.PlanResponse
import pk.pointly.app.screen.ScreenElement
import pk.pointly.app.screen.Labels
import pk.pointly.app.screen.ScreenSnapshot
import pk.pointly.app.screen.sameControlNear
import pk.pointly.app.screen.TargetSignature

private const val MIN_ROWS = 2
private const val MOVE_PX = 48

class StepVerifier(
    val action: String,
    target: ScreenElement?,
    val before: ScreenSnapshot,
) {
    var target: ScreenElement? = target
        private set
    enum class Click { MATCH, MISMATCH, UNKNOWN }

    enum class Verdict {
        UNCHANGED,

        WRONG_TAP,

        PROGRESS,

        DEVIATION,
    }

    val signature: TargetSignature? = target?.let { TargetSignature.of(before.packageName, it) }

    var sawMismatchClick = false
        private set

    fun retarget(moved: ScreenElement) {
        target = moved
    }
    var typedOnTarget = false
        private set

    fun onClick(packageName: String?, src: SourceInfo?): Click {
        if (action != PlanResponse.TAP || target == null) return Click.UNKNOWN
        if (src == null) return Click.UNKNOWN
        val verdict = matchClick(packageName, src)
        if (verdict == Click.MISMATCH) sawMismatchClick = true
        return verdict
    }

    private fun matchClick(packageName: String?, src: SourceInfo): Click {
        val t = target ?: return Click.UNKNOWN
        if (packageName != null && packageName != before.packageName) return Click.MISMATCH
        val b = src.bounds
        if (b != null && t.centerX in b[0]..b[2] && t.centerY in b[1]..b[3]) return Click.MATCH
        val sig = signature!!
        if (sig.viewId != null && src.viewId == sig.viewId) {
            val differentRow = src.label != null && sig.normLabel != null && src.label != sig.normLabel
            return if (differentRow) Click.MISMATCH else Click.MATCH
        }
        if (src.label != null && src.label == sig.normLabel) return Click.MATCH
        return Click.MISMATCH
    }

    fun onTextChanged(src: SourceInfo?): Boolean {
        if (action != PlanResponse.TYPE || target == null) return false
        if (src == null || isTarget(src)) typedOnTarget = true
        return typedOnTarget
    }

    fun onFocused(src: SourceInfo?): Boolean =
        action == PlanResponse.TYPE && typedOnTarget && src != null && !isTarget(src)

    fun onScrolled(src: SourceInfo?): Boolean {
        val t = target
        if (action != PlanResponse.SCROLL || t == null) return false
        val b = src?.bounds ?: return true
        return b[0] < t.right && b[2] > t.left && b[1] < t.bottom && b[3] > t.top
    }

    private fun isTarget(src: SourceInfo): Boolean {
        val t = target ?: return false
        val sig = signature!!
        if (sig.viewId != null && src.viewId == sig.viewId) return true
        val b = src.bounds ?: return false
        return t.centerX in b[0]..b[2] && t.centerY in b[1]..b[3]
    }

    internal fun listMoved(after: ScreenSnapshot): Boolean {
        val box = target ?: return false
        fun inside(e: ScreenElement) =
            e.bounds != box.bounds && e.centerX in box.left..box.right && e.centerY in box.top..box.bottom
        fun key(e: ScreenElement) = Labels.normalize(e.displayLabel)?.takeIf { label -> label.any { it.isLetter() } }
        val beforeRows = before.elements.filter(::inside).mapNotNull { e -> key(e)?.let { it to e } }.toMap()
        val afterRows = after.elements.filter(::inside).mapNotNull { e -> key(e)?.let { it to e } }.toMap()
        if (beforeRows.size < MIN_ROWS) return after.signature != before.signature
        val common = beforeRows.keys intersect afterRows.keys
        if (common.size < beforeRows.size / 2) return true
        val moved = common.count { k ->
            val a = beforeRows.getValue(k)
            val b = afterRows.getValue(k)
            maxOf(kotlin.math.abs(b.top - a.top), kotlin.math.abs(b.left - a.left)) >= MOVE_PX
        }
        return moved * 2 >= common.size
    }

    internal fun sliderMoved(after: ScreenSnapshot): Boolean {
        val t = target ?: return false
        val now = t.viewId?.let { id -> after.elements.firstOrNull { it.viewId == id } }
            ?: after.sameControlNear(t)
            ?: return false
        return sliderValue(now) != sliderValue(t)
    }

    private fun sliderValue(e: ScreenElement): String? = e.range?.getOrNull(2)?.toString() ?: e.text ?: e.desc

    fun judge(after: ScreenSnapshot): Verdict {
        val windowChanged = after.packageName != before.packageName || after.windowId != before.windowId
        val sameScreen = !windowChanged && after.signature == before.signature
        val targetStill = signature?.findIn(after)
        return when (action) {
            PlanResponse.TAP -> when {
                windowChanged || (target != null && targetStill == null) ->
                    if (sawMismatchClick) Verdict.DEVIATION else Verdict.PROGRESS
                sawMismatchClick -> Verdict.WRONG_TAP
                else -> Verdict.UNCHANGED
            }
            PlanResponse.TYPE -> if (windowChanged) Verdict.PROGRESS else Verdict.UNCHANGED
            PlanResponse.SCROLL -> if (windowChanged || listMoved(after)) Verdict.PROGRESS else Verdict.UNCHANGED
            PlanResponse.DRAG -> if (windowChanged || sliderMoved(after)) Verdict.PROGRESS else Verdict.UNCHANGED
            PlanResponse.BACK, PlanResponse.HOME -> if (windowChanged) Verdict.PROGRESS else Verdict.UNCHANGED
            else -> Verdict.UNCHANGED
        }
    }
}
