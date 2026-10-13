package pk.rehnuma.app.session

import pk.rehnuma.app.net.PlanRequest
import pk.rehnuma.app.net.PlanResponse
import pk.rehnuma.app.net.PlannerClient
import pk.rehnuma.app.overlay.BubbleState
import pk.rehnuma.app.overlay.PointerTarget
import pk.rehnuma.app.screen.ScreenSnapshot

/** SPEC 5.5 states. */
enum class SessionState {
    IDLE,
    LISTENING,
    TRANSCRIBING,
    READING_SCREEN,
    SENSITIVE_PAUSE,
    PLANNING,
    POINTING,
    WAITING_FOR_USER,
    ASKING,
    COMPLETED,
    ERROR,
}

/**
 * An accessibility event reduced to plain data in the service, so the session never holds an
 * AccessibilityNodeInfo and can be unit-tested on the JVM.
 */
data class ScreenEvent(
    val type: Type,
    val packageName: String?,
    val windowId: Int = -1,
    val source: SourceInfo? = null,
) {
    enum class Type { CLICKED, TEXT_CHANGED, FOCUSED, SCROLLED, WINDOW_STATE, WINDOW_CONTENT }
}

/** What the event's source node looked like: masked, normalized label and screen bounds. */
data class SourceInfo(
    val viewId: String? = null,
    val label: String? = null,
    val bounds: List<Int>? = null,
    val editable: Boolean = false,
)

fun interface ScreenSource {
    /** Current screen, or null when no readable window is found. */
    suspend fun read(): ScreenSnapshot?
}

fun interface PlanSource {
    suspend fun plan(request: PlanRequest, onRetry: () -> Unit): PlannerClient.Result<PlanResponse>
}

/** Everything the session shows on screen. Implemented with the overlays in the service. */
interface SessionUi {
    fun bubble(state: BubbleState, showClose: Boolean)

    /** Ring, hand and caption. [onArrived] runs when the hand reaches the target (or right away). */
    fun point(target: PointerTarget, caption: String?, withHand: Boolean, onArrived: () -> Unit)

    /** Caption card only (no target). Null hides it. */
    fun caption(text: String?)

    /** Fly the hand in for a step that started without it (HINT/SILENT levels). */
    fun revealHand()

    /** Remove pointer, ring and caption. */
    fun clear()

    /** Mirror the state to Flutter (SPEC 10.2). */
    fun emit(state: SessionState, goalText: String?, step: Int?, captionRoman: String?, error: String?)
}

interface SessionVoice {
    /** Speaks [urdu] (captions are handled separately). [onDone] runs when speech ends or fails. */
    fun speak(urdu: String, roman: String, onDone: () -> Unit = {})

    fun stop()
}
