package pk.rehnuma.app.service

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import java.io.File
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
import pk.rehnuma.app.metrics.MetricsLogger
import pk.rehnuma.app.session.StepMetrics
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
import pk.rehnuma.app.session.LearningStore
import pk.rehnuma.app.session.PlanCache
import pk.rehnuma.app.session.SessionObserver
import pk.rehnuma.app.session.ScreenEvent
import pk.rehnuma.app.session.SessionState
import pk.rehnuma.app.session.SessionUi
import pk.rehnuma.app.session.SourceInfo
import pk.rehnuma.app.voice.SpeechInput
import pk.rehnuma.app.voice.SpeechOutput
import pk.rehnuma.app.voice.SpeechResult

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
    private lateinit var speechOut: SpeechOutput
    private lateinit var speechIn: SpeechInput
    private lateinit var learning: LearningStore
    private lateinit var planCache: PlanCache
    private lateinit var metrics: MetricsLogger
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
        speechOut = SpeechOutput(this, { config }, planner, scope)
        speechIn = SpeechInput(this, { config }, planner, scope)
        learning = LearningStore(File(filesDir, LEARNING_FILE)) { System.currentTimeMillis() }
        planCache = PlanCache(File(filesDir, PLAN_CACHE_FILE))
        metrics = MetricsLogger(File(filesDir, MetricsLogger.FILE_NAME))
        session = GuidanceSession(
            scope = scope,
            screen = { readScreen() },
            planner = { request, onRetry -> planner.plan(request, onRetry) },
            ui = sessionUi,
            voice = speechOut,
            config = { config },
            ownPackage = packageName,
            clock = { SystemClock.uptimeMillis() },
            learning = learning,
            cache = planCache,
            observer = sessionObserver,
        )
        speechOut.onSpeakStart = { session.onSpeechStarted() }
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
        if (::speechIn.isInitialized) speechIn.cancel()
        if (::session.isInitialized) session.cancel()
        if (::speechOut.isInitialized) speechOut.shutdown()
        bubbleVisible = false
        if (::debugBoxes.isInitialized) debugBoxes.hide()
        if (::pointer.isInitialized) pointer.hideAll()
        if (::panel.isInitialized) panel.hide()
        if (::bubble.isInitialized) bubble.hide()
        if (::notification.isInitialized) notification.release()
        if (::scope.isInitialized) scope.cancel()
        RehnumaEvents.emit(SessionState.IDLE.name)
    }

    /** Top edge of the visible keyboard window, if any (only its bounds are read, never its content). */
    private fun keyboardTop(): Int? = try {
        windows.orEmpty()
            .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { w -> Rect().also { w.getBoundsInScreen(it) }.takeIf { !it.isEmpty }?.top }
    } catch (_: RuntimeException) {
        null
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
            pointer.keyboardTop = keyboardTop()
            // HINT/SILENT levels start without hand or ring; both appear if the user hesitates.
            pointer.show(
                target, caption, urduScript = config.caption == AppConfig.CAPTION_URDU,
                from = bubble.bubbleCenterOnScreen(), withHand = withHand, withRing = withHand, onArrived = onArrived,
            )
        }

        override fun caption(text: String?) {
            pointer.keyboardTop = keyboardTop()
            if (text == null) pointer.hideAll() else pointer.showCaption(text, config.caption == AppConfig.CAPTION_URDU)
        }

        override fun revealHand() = pointer.revealHand(bubble.bubbleCenterOnScreen())

        override fun movePointer(target: PointerTarget) = pointer.moveTo(target)

        override fun flashCaption(text: String) = pointer.flashCaption(text, config.caption == AppConfig.CAPTION_URDU)

        override fun clear() = pointer.hideAll()

        override fun emit(state: SessionState, goalText: String?, step: Int?, captionRoman: String?, error: String?) {
            Log.d(TAG, "state $state step=$step${error?.let { " error=$it" } ?: ""}") // never screen text
            RehnumaEvents.emit(state.name, goalText, step, captionRoman, error)
        }
    }

    private val sessionObserver = object : SessionObserver {
        override fun onStep(m: StepMetrics) = metrics.onStep(m)

        override fun onSensitivePause(sessionId: String, packageName: String) {
            Log.i(TAG, "sensitive_pause $packageName") // package only (SPEC 5.4)
            metrics.onSensitivePause(sessionId, packageName)
        }
    }

    // ---- Bubble and panel ----

    /** SPEC 5.8 gestures; see docs/DECISIONS.md (Phase 2) for how Hold and Tap mode differ. */
    private val bubbleListener = object : BubbleOverlay.Listener {
        private val tapMode get() = config.talkMode == AppConfig.TALK_TAP

        override fun onHoldStart() {
            if (!tapMode) startListening()
        }

        override fun onHoldEnd() {
            if (!tapMode) speechIn.stop()
        }

        override fun onTap() {
            when {
                tapMode && speechIn.isListening -> speechIn.stop()
                tapMode && session.state in TAP_TO_TALK_STATES -> startListening()
                session.state == SessionState.IDLE -> panel.show()
                else -> session.onBubbleTap()
            }
        }

        override fun onLongPress() {
            if (tapMode && session.state == SessionState.IDLE) panel.show()
        }

        override fun onClose() {
            speechIn.cancel()
            session.cancel()
        }
    }

    private fun startListening() {
        if (!bubbleVisible) return
        session.listeningStarted()
        speechIn.start(object : SpeechInput.Listener {
            override fun onPartial(text: String) = session.speechPartial(text)

            override fun onTranscribing() = session.transcribing()

            override fun onResult(result: SpeechResult) {
                if (result.error == "microphone permission") toast("Mic ki ijazat dein (Rehnuma app → Ijazatein)")
                session.speechResult(result.bestText)
            }
        })
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
        speechOut.prewarm()
    }

    fun hideBubble() {
        Log.i(TAG, "hideBubble")
        speechIn.cancel()
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
        const val LEARNING_FILE = "learning.json"
        const val PLAN_CACHE_FILE = "plan_cache.json"
        private val TAP_TO_TALK_STATES = setOf(SessionState.IDLE, SessionState.ASKING, SessionState.COMPLETED)

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

        fun metricsSummary(context: Context): Map<String, Any?> =
            MetricsLogger(File(context.filesDir, MetricsLogger.FILE_NAME)).summary()

        /** Copies `metrics.jsonl` to `filesDir/exports/` and opens the share sheet (SPEC 5.14). */
        fun exportMetrics(activity: Activity): String? {
            val copy = MetricsLogger(File(activity.filesDir, MetricsLogger.FILE_NAME))
                .exportCopy(File(activity.filesDir, "exports")) ?: return null
            SnapshotSaver.share(activity, copy, "text/plain")
            return copy.path
        }

        fun resetLearning(context: Context) {
            val live = instance
            if (live != null && live::learning.isInitialized) live.learning.reset() else File(context.filesDir, LEARNING_FILE).delete()
        }
    }
}
