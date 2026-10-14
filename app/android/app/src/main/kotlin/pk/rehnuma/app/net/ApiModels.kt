package pk.rehnuma.app.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pk.rehnuma.app.screen.Masking
import pk.rehnuma.app.screen.ScreenElement
import pk.rehnuma.app.screen.ScreenSnapshot

/**
 * JSON shapes of `/v1/plan` (SPEC 6.2, 6.3). Also used for debug snapshot files (SPEC 11).
 * Defaults (false flags, empty history) are left out of uploads to keep requests small.
 */
val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

@Serializable
data class ApiElement(
    val id: Int,
    val text: String? = null,
    val desc: String? = null,
    val hint: String? = null,
    val label: String? = null,
    @SerialName("view_id") val viewId: String? = null,
    val cls: String,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checked: Boolean? = null,
    val selected: Boolean? = null,
    val bounds: List<Int>,
    val range: List<Float>? = null,
)

@Serializable
data class ApiScreen(
    @SerialName("package") val packageName: String,
    @SerialName("window_title") val windowTitle: String? = null,
    val width: Int,
    val height: Int,
    val elements: List<ApiElement>,
)

@Serializable
data class HistoryItem(
    val step: Int,
    @SerialName("instruction_roman") val instructionRoman: String,
    @SerialName("target_label") val targetLabel: String? = null,
    val outcome: String, // "done" | "deviated" | "skipped"
)

@Serializable
data class Prefs(val caption: String = "roman")

@Serializable
data class PlanRequest(
    @SerialName("session_id") val sessionId: String,
    @SerialName("goal_text") val goalText: String,
    @SerialName("step_index") val stepIndex: Int,
    val history: List<HistoryItem> = emptyList(),
    val screen: ApiScreen,
    val prefs: Prefs = Prefs(),
)

@Serializable
data class PlanResponse(
    val action: String,
    @SerialName("element_id") val elementId: Int? = null,
    @SerialName("scroll_direction") val scrollDirection: String? = null,
    @SerialName("say_urdu") val sayUrdu: String = "",
    @SerialName("say_roman") val sayRoman: String = "",
    @SerialName("goal_key") val goalKey: String? = null,
    val confidence: Double = 0.0,
    val reason: String? = null,
    @SerialName("latency_ms") val latencyMs: Long? = null,
) {
    companion object {
        const val TAP = "tap"
        const val TYPE = "type"
        const val SCROLL = "scroll"
        const val DRAG = "drag"
        const val BACK = "back"
        const val HOME = "home"
        const val ASK = "ask"
        const val DONE = "done"
        const val CANT_HELP = "cant_help"
        val NEEDS_ELEMENT = setOf(TAP, TYPE, SCROLL, DRAG)
    }
}

@Serializable
data class SttResponse(
    @SerialName("text_urdu") val textUrdu: String? = null,
    @SerialName("text_roman") val textRoman: String? = null,
    @SerialName("latency_ms") val latencyMs: Long? = null,
)

@Serializable
data class TtsRequest(val text: String)

/** One acceptable answer for an evaluation snapshot (filled in by the team, SPEC 11). */
@Serializable
data class ExpectedStep(
    val action: String,
    @SerialName("element_id") val elementId: Int? = null,
)

/** File written by "Save snapshot": the planner's screen format plus fields for labelling. */
@Serializable
data class DebugSnapshotFile(
    @SerialName("goal_text") val goalText: String = "",
    val expected: List<ExpectedStep> = emptyList(),
    val history: List<HistoryItem> = emptyList(),
    val screen: ApiScreen,
    @SerialName("captured_at_ms") val capturedAtMs: Long,
    @SerialName("read_ms") val readMs: Long,
)

/** Converts a snapshot to the upload format, masking every string once more (rule 6). */
fun ScreenSnapshot.toApi(): ApiScreen = ApiScreen(
    packageName = packageName,
    windowTitle = Masking.mask(windowTitle),
    width = screenWidth,
    height = screenHeight,
    elements = elements.map { it.toApi() },
)

fun ScreenElement.toApi(): ApiElement = ApiElement(
    id = id,
    text = Masking.mask(text),
    desc = Masking.mask(desc),
    hint = Masking.mask(hint),
    label = Masking.mask(label),
    viewId = viewId,
    cls = cls,
    clickable = clickable,
    editable = editable,
    scrollable = scrollable,
    checked = checked,
    selected = selected,
    bounds = bounds,
    range = range,
)
