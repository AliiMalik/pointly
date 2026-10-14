package pk.pointly.app.session

import pk.pointly.app.net.PlanRequest
import pk.pointly.app.net.PlanResponse
import pk.pointly.app.net.PlannerClient
import pk.pointly.app.overlay.BubbleState
import pk.pointly.app.overlay.PointerTarget
import pk.pointly.app.screen.ScreenSnapshot
import pk.pointly.app.voice.Line

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

data class ScreenEvent(
    val type: Type,
    val packageName: String?,
    val windowId: Int = -1,
    val source: SourceInfo? = null,
) {
    enum class Type { CLICKED, TEXT_CHANGED, FOCUSED, SCROLLED, WINDOW_STATE, WINDOW_CONTENT }
}

data class SourceInfo(
    val viewId: String? = null,
    val label: String? = null,
    val bounds: List<Int>? = null,
    val editable: Boolean = false,
)

fun interface ScreenSource {
    suspend fun read(): ScreenSnapshot?
}

fun interface PlanSource {
    suspend fun plan(request: PlanRequest, onRetry: () -> Unit): PlannerClient.Result<PlanResponse>
}

interface SessionUi {
    fun bubble(state: BubbleState, showClose: Boolean)

    fun point(target: PointerTarget, caption: String?, withHand: Boolean, onArrived: () -> Unit)

    fun caption(text: String?)

    fun revealHand()

    fun movePointer(target: PointerTarget)

    fun flashCaption(text: String)

    fun clear()

    fun emit(state: SessionState, goalText: String?, step: Int?, caption: String?, error: String?)
}

interface SessionVoice {
    fun speak(line: Line, onDone: () -> Unit = {})

    fun stop()
}
