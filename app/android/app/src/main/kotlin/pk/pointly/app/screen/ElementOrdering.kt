package pk.pointly.app.screen

import java.security.MessageDigest

data class RawElement(
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
    val interactive: Boolean = clickable || editable || scrollable,
    val bounds: List<Int>,
    val range: List<Float>? = null,
)

object ElementOrdering {
    const val MAX_ELEMENTS = 80
    const val ROW_BUCKET_PX = 24
    const val SIGNATURE_LABELS = 15

    private val DIGITS = Regex("""\d+""")

    fun finalize(raw: List<RawElement>): List<ScreenElement> {
        val readingOrder = compareBy<RawElement>({ it.bounds[1] / ROW_BUCKET_PX }, { it.bounds[0] })
        val kept = if (raw.size <= MAX_ELEMENTS) {
            raw
        } else {
            val interactive = raw.filter { it.interactive }.sortedWith(readingOrder)
            val text = raw.filterNot { it.interactive }.sortedBy { it.bounds[1] }
            (interactive + text).take(MAX_ELEMENTS)
        }
        return kept.sortedWith(readingOrder).mapIndexed { i, r ->
            ScreenElement(
                id = i + 1,
                text = r.text,
                desc = r.desc,
                hint = r.hint,
                label = r.label,
                viewId = r.viewId,
                cls = r.cls,
                clickable = r.clickable,
                editable = r.editable,
                scrollable = r.scrollable,
                checked = r.checked,
                selected = r.selected,
                bounds = r.bounds,
                range = r.range,
            )
        }
    }

    fun signature(packageName: String, elements: List<ScreenElement>): String {
        val labels = elements.asSequence()
            .mapNotNull { Labels.normalize(it.displayLabel) }
            .map { DIGITS.replace(it, "#") }
            .take(SIGNATURE_LABELS)
            .sorted()
            .toList()
        val input = packageName + "\n" + labels.joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
