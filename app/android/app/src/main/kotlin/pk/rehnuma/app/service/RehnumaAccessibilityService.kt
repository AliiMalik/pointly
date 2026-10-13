package pk.rehnuma.app.service

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pk.rehnuma.app.bridge.RehnumaEvents
import pk.rehnuma.app.config.AppConfig
import pk.rehnuma.app.net.PlannerClient
import pk.rehnuma.app.overlay.BubbleOverlay
import pk.rehnuma.app.overlay.BubbleState
import pk.rehnuma.app.overlay.DebugBoxesOverlay
import pk.rehnuma.app.overlay.PointerOverlay
import pk.rehnuma.app.overlay.PointerTarget
import pk.rehnuma.app.overlay.QuickPanel
import pk.rehnuma.app.screen.Labels
import pk.rehnuma.app.screen.ScreenReader
import pk.rehnuma.app.screen.ScreenSnapshot
import pk.rehnuma.app.screen.SnapshotSaver
import pk.rehnuma.app.screen.recycleCompat
import pk.rehnuma.app.session.GuidanceSession
import pk.rehnuma.app.session.ScreenEvent
import pk.rehnuma.app.session.SessionState
import pk.rehnuma.app.session.SessionUi
import pk.rehnuma.app.session.SessionVoice
import pk.rehnuma.app.session.SourceInfo

/**
 * Entry point of the Kotlin core (SPEC 5.1). Reads the screen only while a guidance session is
 * active (rule 4). It never performs actions or gestures for the user (rule 1).
 */
class RehnumaAccessibilityService : AccessibilityService() {

    private lateinit var scope: CoroutineScope
    private lateinit var notification: SessionNotification
    private lateinit var reader: ScreenReader
    private lateinit var debugBoxes: DebugBoxesOverlay
    private lateinit var bubble: BubbleOverlay
    private lateinit var pointer: PointerOverlay
    private lateinit var panel: QuickPanel
    private lateinit var planner: PlannerClient
    private lateinit var session: GuidanceSession
    private var config = AppConfig()
    private var bubbleVisible = false
    private var debugRefresh: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val readDispatcher = Dispatchers.Default.limitedParallelism(1)

    override fun onServiceConnected() {
        super.onServiceConnected()
        scope = MainScope()
        config = AppConfig.load(this)
        notification = SessionNotification(this) { hideBubble() }
        reader = ScreenReader(this)
        debugBoxes = DebugBoxesOverlay(this)
        pointer = PointerOverlay(this)
        bubble = BubbleOverlay(this, bubbleListener)
        panel = QuickPanel(this, panelListener)
        planner = PlannerClient({ config })
        session = GuidanceSession(
            scope = scope,
            screen = { readScreen() },
            planner = { request, onRetry -> planner.plan(request, onRetry) },
            ui = sessionUi,
            voice = captionVoice,
            config = { config },
            ownPackage = packageName,
            clock = { SystemClock.uptimeMillis() },
        )
        instance = this
        Log.i(TAG, "service connected")
        if (config.debugBoxes) setDebugBoxes(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::session.isInitialized) return
        val active = session.isActive
        // Rule 4: ignore everything unless the user started a session (or turned on the debug overlay).
        if (!active && !debugBoxes.isShowing) return
        if (debugBoxes.isShowing) scheduleDebugRefresh()
        if (!active) return
        val type = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> ScreenEvent.Type.CLICKED
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> ScreenEvent.Type.TEXT_CHANGED
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> ScreenEvent.Type.FOCUSED
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> ScreenEvent.Type.SCROLLED
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> ScreenEvent.Type.WINDOW_STATE
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> ScreenEvent.Type.WINDOW_CONTENT
            else -> return // TYPE_WINDOWS_CHANGED also fires for our own overlays; reads catch real changes
        }
        val pkg = event.packageName?.toString()
        val source = if (type == ScreenEvent.Type.WINDOW_STATE || type == ScreenEvent.Type.WINDOW_CONTENT || pkg == packageName) {
            null
        } else {
            sourceInfo(event)
        }
        session.onEvent(ScreenEvent(type, pkg, event.windowId, source))
    }

    /** Just enough of the event's node to match it against the target; the node is released at once. */
    private fun sourceInfo(event: AccessibilityEvent): SourceInfo? {
        val node = try {
            event.source
        } catch (_: RuntimeException) {
            null
        } ?: return null
        return try {
            val r = Rect()
            node.getBoundsInScreen(r)
            val text = if (node.isShowingHintText) null else node.text
            SourceInfo(
                viewId = node.viewIdResourceName?.substringAfter(":id/"),
                label = Labels.normalize(ScreenReader.clean(text ?: node.contentDescription)),
                bounds = if (r.isEmpty) null else listOf(r.left, r.top, r.right, r.bottom),
                editable = node.isEditable,
            )
        } finally {
            node.recycleCompat()
        }
    }

    override fun onInterrupt() {
        // Nothing to interrupt: Rehnuma gives no spoken accessibility feedback of its own.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "service unbound")
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "service destroyed")
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (instance === this) instance = null
        if (::session.isInitialized) session.cancel()
        bubbleVisible = false
        if (::debugBoxes.isInitialized) debugBoxes.hide()
        if (::pointer.isInitialized) pointer.hideAll()
        if (::panel.isInitialized) panel.hide()
        if (::bubble.isInitialized) bubble.hide()
        if (::notification.isInitialized) notification.release()
        if (::scope.isInitialized) scope.cancel()
        RehnumaEvents.emit(SessionState.IDLE.name)
    }

    /** Reads the current screen off the main thread. */
    suspend fun readScreen(): ScreenSnapshot? = withContext(readDispatcher) {
        try {
            reader.read()
        } catch (e: RuntimeException) {
            Log.w(TAG, "screen read failed", e)
            null
        }
    }

    // ---- Session ports ----

    private val sessionUi = object : SessionUi {
        override fun bubble(state: BubbleState, showClose: Boolean) {
            if (!bubbleVisible) return
            bubble.state = state
            bubble.showClose = showClose
        }

        override fun point(target: PointerTarget, caption: String?, withHand: Boolean, onArrived: () -> Unit) {
            pointer.show(
                target, caption, urduScript = config.caption == AppConfig.CAPTION_URDU,
                from = bubble.bubbleCenterOnScreen(), withHand = withHand, onArrived = onArrived,
            )
        }

        override fun caption(text: String?) {
            if (text == null) pointer.hideAll() else pointer.showCaption(text, config.caption == AppConfig.CAPTION_URDU)
        }

        override fun revealHand() = pointer.revealHand(bubble.bubbleCenterOnScreen())

        override fun clear() = pointer.hideAll()

        override fun emit(state: SessionState, goalText: String?, step: Int?, captionRoman: String?, error: String?) =
            RehnumaEvents.emit(state.name, goalText, step, captionRoman, error)
    }

    /** Phase 4 stand-in for speech: captions only, "done" after a reading-time estimate. */
    private val captionVoice = object : SessionVoice {
        private val handler = Handler(Looper.getMainLooper())

        override fun speak(urdu: String, roman: String, onDone: () -> Unit) {
            handler.postDelayed({ onDone() }, (roman.length * 60L).coerceIn(1200L, 4000L))
        }

        override fun stop() = handler.removeCallbacksAndMessages(null)
    }

    // ---- Bubble and panel ----

    private val bubbleListener = object : BubbleOverlay.Listener {
        override fun onHoldStart() {
            bubble.state = BubbleState.LISTENING
        }

        override fun onHoldEnd() {
            // Voice input arrives in Phase 5; until then a hold opens the text box.
            bubble.state = BubbleState.IDLE
            panel.show()
        }

        override fun onTap() {
            if (session.state == SessionState.IDLE) panel.show() else session.onBubbleTap()
        }

        override fun onLongPress() {}

        override fun onClose() = session.cancel()
    }

    private val panelListener = object : QuickPanel.Listener {
        override fun onGoal(goal: String) = startGoal(goal)

        override fun onDebugPoint(elementId: Int) {
            session.cancel()
            scope.launch {
                delay(400) // let the panel close and the app below settle
                val e = readScreen()?.element(elementId)
                if (e == null) {
                    toast("#$elementId nahi mila")
                    return@launch
                }
                bubble.showClose = true
                pointer.show(
                    PointerTarget.Element(e.bounds), "#${e.id} ${e.displayLabel ?: e.cls}",
                    urduScript = false, from = bubble.bubbleCenterOnScreen(),
                )
            }
        }

        override fun onSnapshot() {
            scope.launch {
                delay(400)
                saveSnapshotNow()
            }
        }
    }

    private fun startGoal(goal: String) {
        pointer.hideAll()
        scope.launch {
            delay(PANEL_CLOSE_MS) // the panel or keyboard must be gone before the screen is read
            session.submitGoal(goal)
        }
    }

    // ---- Debug tools (SPEC 11) ----

    fun setDebugBoxes(enabled: Boolean) {
        config = config.copy(debugBoxes = enabled)
        if (enabled) {
            debugBoxes.show()
            scheduleDebugRefresh(0)
        } else {
            debugRefresh?.cancel()
            debugBoxes.update(null)
            debugBoxes.hide()
        }
    }

    private fun scheduleDebugRefresh(delayMs: Long = 300) {
        debugRefresh?.cancel()
        debugRefresh = scope.launch {
            delay(delayMs)
            debugBoxes.update(readScreen())
        }
    }

    fun saveSnapshotAfter(delayMs: Long) {
        scope.launch {
            toast("Snapshot ${delayMs / 1000}s mein")
            delay(delayMs)
            saveSnapshotNow()
        }
    }

    suspend fun saveSnapshotNow() {
        val snapshot = readScreen()
        if (snapshot == null || snapshot.packageName == packageName) {
            toast("Koi screen nahi mili")
            return
        }
        val result = withContext(Dispatchers.IO) { SnapshotSaver.save(this@RehnumaAccessibilityService, snapshot) }
        when (result) {
            is SnapshotSaver.Result.Sensitive -> toast("Yeh screen save nahi hogi (PIN/password)")
            is SnapshotSaver.Result.Saved -> {
                toast("Saved: ${result.file.name}")
                SnapshotSaver.share(this, result.file)
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ---- Called from the Flutter bridge (main thread) ----

    fun showBubble() {
        bubbleVisible = true
        bubble.show()
        notification.show()
    }

    fun hideBubble() {
        Log.i(TAG, "hideBubble")
        session.cancel()
        bubbleVisible = false
        pointer.hideAll()
        panel.hide()
        bubble.hide()
        notification.hide()
    }

    fun isBubbleVisible(): Boolean = bubbleVisible

    fun startQuickTask(goal: String) {
        if (!bubbleVisible) showBubble()
        startGoal(goal)
    }

    fun onConfigChanged(newConfig: AppConfig) {
        if (newConfig.debugBoxes != debugBoxes.isShowing) setDebugBoxes(newConfig.debugBoxes)
        config = newConfig
    }

    fun metricsSummary(): Map<String, Any?> = metricsSummary(this)

    companion object {
        private const val TAG = "Rehnuma"
        private const val PANEL_CLOSE_MS = 400L

        @Volatile
        var instance: RehnumaAccessibilityService? = null
            private set

        /** True when the service is running or enabled in system settings. */
        fun isEnabled(context: Context): Boolean {
            if (instance != null) return true
            val expected = ComponentName(context, RehnumaAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }

        fun metricsSummary(context: Context): Map<String, Any?> = emptyMap()

        fun exportMetrics(activity: Activity): String? = null

        fun resetLearning(context: Context) {}
    }
}
