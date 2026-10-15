package pk.pointly.app.service

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
import pk.pointly.app.bridge.PointlyEvents
import pk.pointly.app.config.AppConfig
import pk.pointly.app.metrics.MetricsLogger
import pk.pointly.app.session.StepMetrics
import pk.pointly.app.net.PlannerClient
import pk.pointly.app.overlay.BubbleOverlay
import pk.pointly.app.overlay.BubbleState
import pk.pointly.app.overlay.DebugBoxesOverlay
import pk.pointly.app.overlay.PointerOverlay
import pk.pointly.app.overlay.PointerTarget
import pk.pointly.app.overlay.QuickPanel
import pk.pointly.app.screen.Labels
import pk.pointly.app.screen.ScreenReader
import pk.pointly.app.screen.ScreenSnapshot
import pk.pointly.app.screen.SnapshotSaver
import pk.pointly.app.screen.recycleCompat
import pk.pointly.app.session.GuidanceSession
import pk.pointly.app.session.LearningStore
import pk.pointly.app.session.PlanCache
import pk.pointly.app.session.SessionObserver
import pk.pointly.app.session.ScreenEvent
import pk.pointly.app.session.SessionState
import pk.pointly.app.session.SessionUi
import pk.pointly.app.session.SourceInfo
import pk.pointly.app.voice.SpeechInput
import pk.pointly.app.voice.SpeechOutput
import pk.pointly.app.voice.SpeechResult

class PointlyAccessibilityService : AccessibilityService() {

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
        panel = QuickPanel(this, { config.language }, { config.debugBoxes }, panelListener)
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
            else -> return
        }
        val pkg = event.packageName?.toString()
        val source = if (type == ScreenEvent.Type.WINDOW_STATE || type == ScreenEvent.Type.WINDOW_CONTENT || pkg == packageName) {
            null
        } else {
            sourceInfo(event)
        }
        session.onEvent(ScreenEvent(type, pkg, event.windowId, source))
    }

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
        PointlyEvents.emit(SessionState.IDLE.name)
    }

    private fun keyboardTop(): Int? = try {
        windows.orEmpty()
            .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { w -> Rect().also { w.getBoundsInScreen(it) }.takeIf { !it.isEmpty }?.top }
    } catch (_: RuntimeException) {
        null
    }

    suspend fun readScreen(): ScreenSnapshot? = withContext(readDispatcher) {
        try {
            reader.read()
        } catch (e: RuntimeException) {
            Log.w(TAG, "screen read failed", e)
            null
        }
    }

    private val sessionUi = object : SessionUi {
        override fun bubble(state: BubbleState, showClose: Boolean) {
            if (!bubbleVisible) return
            bubble.state = state
            bubble.showClose = showClose
        }

        override fun point(target: PointerTarget, caption: String?, withHand: Boolean, onArrived: () -> Unit) {
            pointer.keyboardTop = keyboardTop()
            pointer.show(
                target, caption, urduScript = config.language == AppConfig.LANG_UR,
                from = bubble.bubbleCenterOnScreen(), withHand = withHand, withRing = withHand, onArrived = onArrived,
            )
        }

        override fun caption(text: String?) {
            pointer.keyboardTop = keyboardTop()
            if (text == null) pointer.hideAll() else pointer.showCaption(text, config.language == AppConfig.LANG_UR)
        }

        override fun revealHand() = pointer.revealHand(bubble.bubbleCenterOnScreen())

        override fun movePointer(target: PointerTarget) = pointer.moveTo(target)

        override fun flashCaption(text: String) = pointer.flashCaption(text, config.language == AppConfig.LANG_UR)

        override fun clear() = pointer.hideAll()

        override fun emit(state: SessionState, goalText: String?, step: Int?, caption: String?, error: String?) {
            Log.d(TAG, "state $state step=$step${error?.let { " error=$it" } ?: ""}")
            PointlyEvents.emit(state.name, goalText, step, caption, error)
        }
    }

    private val sessionObserver = object : SessionObserver {
        override fun onStep(m: StepMetrics) = metrics.onStep(m)

        override fun onSensitivePause(sessionId: String, packageName: String) {
            Log.i(TAG, "sensitive_pause $packageName")
            metrics.onSensitivePause(sessionId, packageName)
        }
    }

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
                if (result.error == "microphone permission") toast("Please allow the microphone (Pointly app → Permissions)")
                session.speechResult(result.bestText)
            }
        })
    }

    private val panelListener = object : QuickPanel.Listener {
        override fun onGoal(goal: String) = startGoal(goal)

        override fun onDebugPoint(elementId: Int) {
            session.cancel()
            scope.launch {
                delay(400)
                val e = readScreen()?.element(elementId)
                if (e == null) {
                    toast("#$elementId not found")
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
            delay(PANEL_CLOSE_MS)
            session.submitGoal(goal)
        }
    }

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
            toast("No screen found")
            return
        }
        val result = withContext(Dispatchers.IO) { SnapshotSaver.save(this@PointlyAccessibilityService, snapshot) }
        when (result) {
            is SnapshotSaver.Result.Sensitive -> toast("This screen is not saved (PIN/password)")
            is SnapshotSaver.Result.Saved -> {
                toast("Saved: ${result.file.name}")
                SnapshotSaver.share(this, result.file)
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

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
        private const val TAG = "Pointly"
        private const val PANEL_CLOSE_MS = 400L
        const val LEARNING_FILE = "learning.json"
        const val PLAN_CACHE_FILE = "plan_cache.json"
        private val TAP_TO_TALK_STATES = setOf(SessionState.IDLE, SessionState.ASKING, SessionState.COMPLETED)

        @Volatile
        var instance: PointlyAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            if (instance != null) return true
            val expected = ComponentName(context, PointlyAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }

        fun metricsSummary(context: Context): Map<String, Any?> =
            MetricsLogger(File(context.filesDir, MetricsLogger.FILE_NAME)).summary()

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
