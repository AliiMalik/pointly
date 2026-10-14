package pk.pointly.app.screen

import kotlinx.serialization.Serializable

@Serializable
data class ScreenElement(
    val id: Int,
    val text: String? = null,
    val desc: String? = null,
    val hint: String? = null,
    val label: String? = null,
    val viewId: String? = null,
    val cls: String,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checked: Boolean? = null,
    val selected: Boolean? = null,
    val bounds: List<Int>,
    val range: List<Float>? = null,
) {
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
    val hasPasswordField: Boolean = false,
    val windowId: Int = -1,
) {
    fun element(id: Int): ScreenElement? = elements.firstOrNull { it.id == id }
}

@Serializable
data class TargetSignature(
    val packageName: String,
    val viewId: String? = null,
    val normLabel: String? = null,
    val cls: String,
    val bounds: List<Int>,
) {
    val targetKey: String
        get() = viewId ?: normLabel ?: cls

    fun findIn(snapshot: ScreenSnapshot): ScreenElement? {
        if (snapshot.packageName != packageName) return null
        viewId?.let { id ->
            val byId = snapshot.elements.filter { it.viewId == id }
            if (byId.size == 1) return byId[0]
            if (byId.size > 1) {
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

fun ScreenSnapshot.sameControlNear(e: ScreenElement, maxDistancePx: Int = 160): ScreenElement? =
    elements.filter { it.cls == e.cls }
        .minByOrNull { kotlin.math.abs(it.centerX - e.centerX) + kotlin.math.abs(it.centerY - e.centerY) }
        ?.takeIf { kotlin.math.abs(it.centerX - e.centerX) + kotlin.math.abs(it.centerY - e.centerY) <= maxDistancePx }

object Labels {
    private val SPACES = Regex("\\s+")

    fun normalize(s: String?): String? {
        val t = s?.trim()?.lowercase()?.replace(SPACES, " ")
        return if (t.isNullOrEmpty()) null else t
    }
}
