package pk.pointly.app.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pk.pointly.app.config.AppConfig
import pk.pointly.app.net.HistoryItem
import pk.pointly.app.net.PlanRequest
import pk.pointly.app.net.PlanResponse
import pk.pointly.app.net.PlannerClient
import pk.pointly.app.net.Prefs
import pk.pointly.app.net.toApi
import pk.pointly.app.overlay.BubbleState
import pk.pointly.app.overlay.PointerTarget
import pk.pointly.app.screen.GuardResult
import pk.pointly.app.screen.Masking
import pk.pointly.app.screen.SafetyGuard
import pk.pointly.app.screen.ScreenElement
import pk.pointly.app.screen.ScreenSnapshot
import pk.pointly.app.screen.TargetSignature
import pk.pointly.app.screen.sameControlNear
import pk.pointly.app.voice.Line
import pk.pointly.app.voice.Phrase
import java.util.UUID

data class StepMetrics(
    val sessionId: String,
    val step: Int,
    val goalKey: String?,
    val packageName: String,
    val action: String,
    val readMs: Long,
    val plannerMs: Long?,
    val ttsStartMs: Long?,
    val timeToUserTapMs: Long?,
    val settleToPointerMs: Long?,
    val outcome: String,
    val fromCache: Boolean,
    val guidanceLevel: String,
)

interface SessionObserver {
    fun onStep(m: StepMetrics) {}

    fun onSensitivePause(sessionId: String, packageName: String) {}
}

class GuidanceSession(
    private val scope: CoroutineScope,
    private val screen: ScreenSource,
    private val planner: PlanSource,
    private val ui: SessionUi,
    private val voice: SessionVoice,
    private val config: () -> AppConfig,
    private val ownPackage: String,
    private val clock: () -> Long,
    private val learning: LearningStore = LearningStore(null, clock),
    private val cache: PlanCache = PlanCache(null),
    private val observer: SessionObserver = object : SessionObserver {},
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
) {
    var state = SessionState.IDLE
        private set

    val isActive: Boolean
        get() = state != SessionState.IDLE && state != SessionState.ERROR && state != SessionState.COMPLETED

    var goalText: String = ""
        private set
    var goalKey: String? = null
        private set
    var stepIndex = 0
        private set
    val history: List<HistoryItem> get() = _history.toList()

    private var sessionId = ""
    private val _history = ArrayList<HistoryItem>()
    private val finishedSteps = ArrayList<PlanCache.Step>()
    private val settle = SettleDetector(clock)
    private var flowJob: Job? = null
    private var checkJob: Job? = null
    private var typeDoneJob: Job? = null
    private var hesitationJob: Job? = null
    private var step: Step? = null
    private var dirty = false
    private var lastSaid: Line? = null
    private var yourTurnSaid = false
    private var settledAt = 0L
    private var beforeListening: SessionState = SessionState.IDLE

    private class Step(
        val number: Int,
        val plan: PlanResponse,
        var target: ScreenElement?,
        val snapshot: ScreenSnapshot,
        val verifier: StepVerifier,
        val level: GuidanceLevel,
        val fromCache: Boolean,
        val plannerMs: Long?,
        val settledAt: Long,
        var shownAt: Long = 0,
        var speechStartedAt: Long = 0,
        var wrongTapSaid: Boolean = false,
    )

    fun submitGoal(text: String) {
        val goal = text.trim()
        if (goal.isEmpty()) return
        if (state == SessionState.ASKING) {
            goalText = "$goalText\nUser answer: ${Masking.mask(goal)}"
            runFlow { planFromScreen(fromNow = false) }
            return
        }
        cancelJobs()
        voice.stop()
        ui.clear()
        sessionId = newSessionId()
        goalText = Masking.mask(goal)!!
        goalKey = null
        stepIndex = 0
        _history.clear()
        finishedSteps.clear()
        step = null
        lastSaid = null
        yourTurnSaid = false
        ui.caption(caption(Phrase.THINKING.line))
        say(Phrase.THINKING)
        runFlow { planFromScreen(fromNow = false) }
    }

    fun listeningStarted() {
        beforeListening = if (state == SessionState.LISTENING || state == SessionState.TRANSCRIBING) beforeListening else state
        abandonStep("interrupted")
        cancelJobs()
        voice.stop()
        ui.clear()
        setState(SessionState.LISTENING)
        ui.bubble(BubbleState.LISTENING, showClose = true)
        ui.caption(caption(Phrase.ASK_GOAL.line))
    }

    fun speechPartial(text: String) {
        if (state == SessionState.LISTENING) ui.caption(text)
    }

    fun transcribing() {
        if (state != SessionState.LISTENING) return
        setState(SessionState.TRANSCRIBING)
        ui.bubble(BubbleState.THINKING, showClose = true)
    }

    fun speechResult(text: String?) {
        if (state != SessionState.LISTENING && state != SessionState.TRANSCRIBING) return
        val previous = beforeListening
        if (text.isNullOrBlank()) {
            ui.caption(caption(Phrase.DIDNT_HEAR.line))
            say(Phrase.DIDNT_HEAR)
            when {
                previous == SessionState.ASKING -> {
                    setState(SessionState.ASKING)
                    ui.bubble(BubbleState.IDLE, showClose = true)
                }
                goalText.isNotEmpty() && previous != SessionState.IDLE && previous != SessionState.COMPLETED ->
                    runFlow { planFromScreen(fromNow = false) }
                else -> {
                    setState(SessionState.IDLE)
                    ui.bubble(BubbleState.IDLE, showClose = false)
                    scope.launch {
                        delay(END_LINGER_MS * 2)
                        if (state == SessionState.IDLE) ui.clear()
                    }
                }
            }
            return
        }
        if (previous == SessionState.ASKING) state = SessionState.ASKING
        submitGoal(text)
    }

    fun onBubbleTap() {
        when (state) {
            SessionState.ERROR -> runFlow { planFromScreen(fromNow = false) }
            SessionState.POINTING, SessionState.WAITING_FOR_USER -> {
                val s = step ?: return
                showFull(s, speak = true)
            }
            SessionState.ASKING, SessionState.SENSITIVE_PAUSE -> lastSaid?.let { say(it) }
            else -> Unit
        }
    }

    fun onSpeechStarted() {
        val s = step ?: return
        if (s.speechStartedAt == 0L && s.shownAt != 0L) s.speechStartedAt = clock()
    }

    fun cancel() {
        abandonStep("cancelled")
        cancelJobs()
        voice.stop()
        ui.clear()
        setState(SessionState.IDLE)
        ui.bubble(BubbleState.IDLE, showClose = false)
    }

    fun onEvent(e: ScreenEvent) {
        if (!isActive || e.packageName == ownPackage) return
        when (e.type) {
            ScreenEvent.Type.WINDOW_STATE, ScreenEvent.Type.WINDOW_CONTENT -> {
                settle.mark()
                onScreenChanged()
            }
            ScreenEvent.Type.CLICKED -> onClick(e)
            ScreenEvent.Type.TEXT_CHANGED -> {
                val s = step ?: return
                if (state == SessionState.WAITING_FOR_USER && s.verifier.onTextChanged(e.source)) {
                    typeDoneJob?.cancel()
                    typeDoneJob = scope.launch {
                        delay(TYPE_QUIET_MS)
                        stepDone(s, "done")
                    }
                }
            }
            ScreenEvent.Type.FOCUSED -> {
                val s = step ?: return
                if (state == SessionState.WAITING_FOR_USER && s.verifier.onFocused(e.source)) stepDone(s, "done")
            }
            ScreenEvent.Type.SCROLLED -> {
                settle.mark()
                val s = step ?: return
                if (state == SessionState.WAITING_FOR_USER && s.verifier.onScrolled(e.source)) scheduleCheck(fromNow = true)
            }
        }
    }

    private fun onClick(e: ScreenEvent) {
        val s = step ?: return
        if (state != SessionState.POINTING && state != SessionState.WAITING_FOR_USER) return
        when (s.verifier.onClick(e.packageName, e.source)) {
            StepVerifier.Click.MATCH -> stepDone(s, "done")
            StepVerifier.Click.MISMATCH, StepVerifier.Click.UNKNOWN -> scheduleCheck(fromNow = true)
        }
    }

    private fun onScreenChanged() {
        when (state) {
            SessionState.POINTING, SessionState.WAITING_FOR_USER, SessionState.SENSITIVE_PAUSE -> scheduleCheck(fromNow = true)
            SessionState.READING_SCREEN, SessionState.PLANNING -> dirty = true
            else -> Unit
        }
    }

    private fun scheduleCheck(fromNow: Boolean) {
        if (checkJob?.isActive == true) return
        checkJob = scope.launch {
            settle.await(fromNow)
            settledAt = clock()
            val snapshot = screen.read() ?: return@launch
            when (state) {
                SessionState.SENSITIVE_PAUSE -> if (SafetyGuard.check(snapshot) == GuardResult.Safe) {
                    runFlow { planFrom(snapshot) }
                }
                SessionState.POINTING, SessionState.WAITING_FOR_USER -> {
                    if (SafetyGuard.check(snapshot) is GuardResult.Sensitive) {
                        step?.let { stepDone(it, "done", next = snapshot) }
                    } else {
                        judge(snapshot)
                    }
                }
                else -> Unit
            }
        }
    }

    private fun judge(snapshot: ScreenSnapshot) {
        val s = step ?: return
        when (s.verifier.judge(snapshot)) {
            StepVerifier.Verdict.UNCHANGED -> follow(s, snapshot)
            StepVerifier.Verdict.WRONG_TAP -> {
                follow(s, snapshot)
                if (!s.wrongTapSaid) {
                    s.wrongTapSaid = true
                    ui.flashCaption(caption(Phrase.WRONG_TAP.line))
                    say(Phrase.WRONG_TAP)
                }
            }
            StepVerifier.Verdict.PROGRESS -> stepDone(s, "done", next = snapshot)
            StepVerifier.Verdict.DEVIATION -> stepDone(s, "deviated", next = snapshot, phrase = Phrase.DEVIATION)
        }
    }

    private fun thumbAt(e: ScreenElement): Float {
        val r = e.range ?: return 0.5f
        val span = r[1] - r[0]
        return if (span > 0f) ((r[2] - r[0]) / span).coerceIn(0f, 1f) else 0.5f
    }

    private fun targetGone(plan: PlanResponse, planned: ScreenSnapshot, now: ScreenSnapshot): Boolean {
        val target = plan.elementId?.let { planned.element(it) } ?: return false
        if (TargetSignature.of(planned.packageName, target).findIn(now) != null) return false
        return target.range == null || now.sameControlNear(target) == null
    }

    private fun follow(s: Step, snapshot: ScreenSnapshot) {
        val old = s.target ?: return
        val moved = s.verifier.signature?.findIn(snapshot) ?: return
        if (moved.bounds == old.bounds) return
        s.target = moved
        s.verifier.retarget(moved)
        ui.movePointer(pointerTarget(s))
    }

    private fun stepDone(s: Step, outcome: String, next: ScreenSnapshot? = null, phrase: Phrase? = null) {
        if (step !== s) return
        step = null
        typeDoneJob?.cancel()
        checkJob?.cancel()
        hesitationJob?.cancel()
        addHistory(s, outcome)
        report(s, outcome, tapped = true)
        if (outcome == "done") {
            goalKey?.let { gk ->
                learning.recordSuccess(LearningStore.key(gk, s.snapshot.packageName, LearningStore.targetKey(s.target, s.plan.action)))
                finishedSteps += PlanCache.Step(
                    goalKey = gk,
                    signature = s.snapshot.signature,
                    action = s.plan.action,
                    target = PlanCache.targetOf(s.target),
                    scrollDirection = s.plan.scrollDirection,
                    sayEn = s.plan.sayEn,
                    sayUr = s.plan.sayUr,
                )
            }
        }
        ui.clear()
        voice.stop()
        phrase?.let {
            ui.caption(caption(it.line))
            say(it)
        }
        runFlow {
            if (next != null) planFrom(next) else planFromScreen(fromNow = true)
        }
    }

    private fun abandonStep(outcome: String) {
        val s = step ?: return
        step = null
        report(s, outcome, tapped = false)
    }

    private fun addHistory(s: Step, outcome: String) {
        _history += HistoryItem(
            step = _history.size + 1,
            instruction = s.plan.sayEn,
            targetLabel = s.target?.displayLabel,
            outcome = outcome,
        )
        while (_history.size > MAX_HISTORY) _history.removeAt(0)
    }

    private fun runFlow(block: suspend () -> Unit) {
        flowJob?.cancel()
        checkJob?.cancel()
        hesitationJob?.cancel()
        flowJob = scope.launch { block() }
    }

    private suspend fun planFromScreen(fromNow: Boolean) {
        setState(SessionState.READING_SCREEN)
        ui.bubble(BubbleState.THINKING, showClose = true)
        settle.await(fromNow)
        settledAt = clock()
        var snapshot = screen.read()
        var tries = 0
        while (snapshot == null && tries++ < 2) {
            delay(300)
            snapshot = screen.read()
        }
        if (snapshot == null) {
            fail(Line("I couldn't read the screen. Tap the bubble to try again.", "اسکرین نہیں پڑھ سکی۔ بلبلہ دبا کر دوبارہ کوشش کریں۔"), "no readable window")
            return
        }
        planFrom(snapshot)
    }

    private suspend fun planFrom(snapshot: ScreenSnapshot, replans: Int = 0) {
        if (snapshot.packageName == ownPackage) {
            val p = PlanResponse(
                action = PlanResponse.HOME,
                sayEn = "Press the Home button or swipe up from the bottom",
                sayUr = "گھر والا بٹن دبائیں یا نیچے سے اوپر سوائپ کریں",
                goalKey = goalKey,
            )
            startStep(p, null, snapshot, fromCache = false, plannerMs = null, level = GuidanceLevel.FULL)
            return
        }
        if (SafetyGuard.check(snapshot) is GuardResult.Sensitive) {
            enterSensitivePause(snapshot)
            return
        }
        if (stepIndex >= MAX_STEPS) {
            endWith(Phrase.CANT_HELP)
            return
        }

        val gk = goalKey
        if (gk != null && stepIndex >= 1) {
            val lookupStart = clock()
            val hit = cache.lookup(gk, snapshot)
            if (hit != null) {
                stepIndex++
                val p = PlanResponse(
                    action = hit.step.action,
                    elementId = hit.element?.id,
                    scrollDirection = hit.step.scrollDirection,
                    sayEn = hit.step.sayEn,
                    sayUr = hit.step.sayUr,
                    goalKey = gk,
                    confidence = 1.0,
                )
                handlePlan(p, snapshot, fromCache = true, plannerMs = clock() - lookupStart)
                return
            }
        }

        setState(SessionState.PLANNING)
        ui.bubble(BubbleState.THINKING, showClose = true)
        dirty = false
        stepIndex++
        val request = PlanRequest(
            sessionId = sessionId,
            goalText = goalText,
            stepIndex = stepIndex,
            history = _history.takeLast(MAX_HISTORY),
            screen = snapshot.toApi(),
            prefs = Prefs(config().language),
        )
        val result = planner.plan(request) {
            ui.caption(caption(Phrase.NETWORK_SLOW.line))
            voice.speak(Phrase.NETWORK_SLOW.line)
        }
        when (result) {
            is PlannerClient.Result.Failed -> {
                stepIndex--
                val detail = "${result.failure}: ${result.message}"
                when (result.failure) {
                    PlannerClient.Failure.AUTH -> fail(Line("The app key is wrong. Please check Settings.", "ایپ کی غلط ہے۔ سیٹنگز دیکھیں۔"), detail)
                    else -> fail(Line("There is an internet problem. Tap the bubble to try again.", "انٹرنیٹ کا مسئلہ ہے۔ دوبارہ کوشش کے لیے بلبلہ دبائیں۔"), detail)
                }
            }
            is PlannerClient.Result.Ok -> {
                if (dirty && replans < MAX_REPLANS) {
                    val now = screen.read()
                    if (now != null && (now.windowId != snapshot.windowId || now.packageName != snapshot.packageName ||
                            targetGone(result.value, snapshot, now))
                    ) {
                        stepIndex--
                        settledAt = clock()
                        planFrom(now, replans + 1)
                        return
                    }
                    if (goalKey == null) goalKey = result.value.goalKey
                    handlePlan(result.value, snapshot, fromCache = false, plannerMs = result.ms)
                    if (now != null) step?.let { follow(it, now) }
                    return
                }
                if (goalKey == null) goalKey = result.value.goalKey
                handlePlan(result.value, snapshot, fromCache = false, plannerMs = result.ms)
            }
        }
    }

    private fun handlePlan(plan: PlanResponse, snapshot: ScreenSnapshot, fromCache: Boolean, plannerMs: Long?) {
        when (plan.action) {
            PlanResponse.TAP, PlanResponse.TYPE, PlanResponse.SCROLL, PlanResponse.DRAG -> {
                val target = plan.elementId?.let { snapshot.element(it) }
                if (target == null) {
                    endWith(Phrase.CANT_HELP)
                    return
                }
                startStep(plan, target, snapshot, fromCache, plannerMs, levelFor(plan, target, snapshot))
            }
            PlanResponse.BACK, PlanResponse.HOME ->
                startStep(plan, null, snapshot, fromCache, plannerMs, levelFor(plan, null, snapshot))
            PlanResponse.ASK -> {
                reportTerminal(plan, snapshot, fromCache, plannerMs, "asked")
                setState(SessionState.ASKING, plan.line)
                ui.bubble(BubbleState.IDLE, showClose = true)
                ui.caption(caption(plan.line))
                say(plan.line)
            }
            PlanResponse.DONE -> {
                reportTerminal(plan, snapshot, fromCache, plannerMs, "completed")
                complete(plan)
            }
            else -> {
                reportTerminal(plan, snapshot, fromCache, plannerMs, "cant_help")
                endWith(Phrase.CANT_HELP)
            }
        }
    }

    private fun levelFor(plan: PlanResponse, target: ScreenElement?, snapshot: ScreenSnapshot): GuidanceLevel {
        if (config().demoMode) return GuidanceLevel.FULL
        val gk = goalKey ?: return GuidanceLevel.FULL
        return learning.level(LearningStore.key(gk, snapshot.packageName, LearningStore.targetKey(target, plan.action)))
    }

    private fun startStep(
        plan: PlanResponse,
        target: ScreenElement?,
        snapshot: ScreenSnapshot,
        fromCache: Boolean,
        plannerMs: Long?,
        level: GuidanceLevel,
    ) {
        val s = Step(
            number = stepIndex, plan = plan, target = target, snapshot = snapshot,
            verifier = StepVerifier(plan.action, target, snapshot), level = level,
            fromCache = fromCache, plannerMs = plannerMs, settledAt = settledAt,
        )
        step = s
        setState(SessionState.POINTING, plan.line)
        s.shownAt = clock()
        when (level) {
            GuidanceLevel.FULL -> showFull(s, speak = true)
            GuidanceLevel.HINT -> {
                val hint = Line(firstClause(plan.sayEn), firstClause(plan.sayUr))
                ui.bubble(BubbleState.SPEAKING, showClose = true)
                ui.point(pointerTarget(s), caption(hint), withHand = false) { arrived(s) }
                say(hint) { if (step === s) ui.bubble(BubbleState.IDLE, showClose = true) }
                revealAfter(s, HINT_REVEAL_MS)
            }
            GuidanceLevel.SILENT -> {
                ui.bubble(BubbleState.IDLE, showClose = true)
                ui.point(pointerTarget(s), null, withHand = false) { arrived(s) }
                if (!yourTurnSaid) {
                    yourTurnSaid = true
                    ui.flashCaption(caption(Phrase.YOUR_TURN.line))
                    say(Phrase.YOUR_TURN)
                }
                revealAfter(s, SILENT_REVEAL_MS)
            }
        }
    }

    private fun showFull(s: Step, speak: Boolean) {
        ui.bubble(BubbleState.SPEAKING, showClose = true)
        ui.point(pointerTarget(s), caption(s.plan.line), withHand = true) { arrived(s) }
        if (speak) {
            say(s.plan.line) {
                if (step === s) ui.bubble(BubbleState.IDLE, showClose = true)
            }
        } else {
            ui.bubble(BubbleState.IDLE, showClose = true)
        }
    }

    private fun arrived(s: Step) {
        if (step === s && state == SessionState.POINTING) setState(SessionState.WAITING_FOR_USER, s.plan.line)
    }

    private fun revealAfter(s: Step, ms: Long) {
        hesitationJob?.cancel()
        hesitationJob = scope.launch {
            delay(ms)
            if (step === s) showFull(s, speak = false)
        }
    }

    private fun pointerTarget(s: Step): PointerTarget = when (s.plan.action) {
        PlanResponse.SCROLL -> PointerTarget.Scroll(s.target!!.bounds, s.plan.scrollDirection ?: "down")
        PlanResponse.DRAG -> PointerTarget.Drag(s.target!!.bounds, s.plan.scrollDirection ?: "right", thumbAt(s.target!!))
        PlanResponse.BACK, PlanResponse.HOME -> PointerTarget.Nav(s.plan.action)
        else -> PointerTarget.Element(s.target!!.bounds)
    }

    private fun enterSensitivePause(snapshot: ScreenSnapshot) {
        step = null
        hesitationJob?.cancel()
        if (state != SessionState.SENSITIVE_PAUSE) observer.onSensitivePause(sessionId, snapshot.packageName)
        setState(SessionState.SENSITIVE_PAUSE, Phrase.SENSITIVE_PAUSE.line)
        ui.clear()
        ui.bubble(BubbleState.PAUSED, showClose = true)
        ui.caption(caption(Phrase.SENSITIVE_PAUSE.line))
        say(Phrase.SENSITIVE_PAUSE)
    }

    private fun complete(plan: PlanResponse) {
        step = null
        goalKey?.let { cache.store(it, finishedSteps.toList()) }
        val userFinishes = plan.sayEn.contains("yourself", ignoreCase = true) || "خود" in plan.sayUr
        val line = if (userFinishes) plan.line else Phrase.DONE.line
        setState(SessionState.COMPLETED, line)
        ui.clear()
        ui.bubble(BubbleState.SPEAKING, showClose = false)
        ui.caption(caption(line))
        finishAfterSpeech(line)
    }

    private fun endWith(phrase: Phrase) {
        step = null
        setState(SessionState.COMPLETED, phrase.line)
        ui.clear()
        ui.bubble(BubbleState.SPEAKING, showClose = false)
        ui.caption(caption(phrase.line))
        finishAfterSpeech(phrase.line)
    }

    private fun finishAfterSpeech(line: Line) {
        var finished = false
        val finish = {
            if (!finished && state == SessionState.COMPLETED) {
                finished = true
                ui.clear()
                ui.bubble(BubbleState.IDLE, showClose = false)
                setState(SessionState.IDLE)
            }
        }
        say(line) { scope.launch { delay(END_LINGER_MS); finish() } }
        scope.launch { delay(END_MAX_MS); finish() }
    }

    private fun fail(line: Line, detail: String) {
        step = null
        setState(SessionState.ERROR, line, error = detail)
        ui.clear()
        ui.bubble(BubbleState.ERROR, showClose = true)
        ui.caption(caption(line))
        lastSaid = line
    }

    private fun report(s: Step, outcome: String, tapped: Boolean) {
        val now = clock()
        observer.onStep(
            StepMetrics(
                sessionId = sessionId,
                step = s.number,
                goalKey = goalKey,
                packageName = s.snapshot.packageName,
                action = s.plan.action,
                readMs = s.snapshot.readMs,
                plannerMs = s.plannerMs,
                ttsStartMs = if (s.speechStartedAt > 0) s.speechStartedAt - s.shownAt else null,
                timeToUserTapMs = if (tapped) now - s.shownAt else null,
                settleToPointerMs = if (s.settledAt > 0) s.shownAt - s.settledAt else null,
                outcome = outcome,
                fromCache = s.fromCache,
                guidanceLevel = s.level.name,
            ),
        )
    }

    private fun reportTerminal(plan: PlanResponse, snapshot: ScreenSnapshot, fromCache: Boolean, plannerMs: Long?, outcome: String) {
        observer.onStep(
            StepMetrics(
                sessionId = sessionId, step = stepIndex, goalKey = goalKey, packageName = snapshot.packageName,
                action = plan.action, readMs = snapshot.readMs, plannerMs = plannerMs, ttsStartMs = null,
                timeToUserTapMs = null, settleToPointerMs = if (settledAt > 0) clock() - settledAt else null,
                outcome = outcome, fromCache = fromCache, guidanceLevel = GuidanceLevel.FULL.name,
            ),
        )
    }

    private fun say(p: Phrase) = say(p.line)

    private fun say(line: Line, onDone: () -> Unit = {}) {
        lastSaid = line
        voice.speak(line, onDone)
    }

    private fun caption(line: Line) = line.pick(config().language)

    private fun setState(s: SessionState, line: Line? = null, error: String? = null) {
        state = s
        ui.emit(s, goalText.takeIf { it.isNotEmpty() }, stepIndex.takeIf { it > 0 }, line?.let(::caption), error)
    }

    private fun cancelJobs() {
        flowJob?.cancel()
        checkJob?.cancel()
        typeDoneJob?.cancel()
        hesitationJob?.cancel()
    }

    companion object {
        const val MAX_STEPS = 25
        const val MAX_HISTORY = 8
        const val MAX_REPLANS = 2
        const val TYPE_QUIET_MS = 3000L
        const val HINT_REVEAL_MS = 6000L
        const val SILENT_REVEAL_MS = 10_000L
        const val END_LINGER_MS = 1500L
        const val END_MAX_MS = 8000L

        private val CLAUSE_END = Regex("""[،,؛;.۔!?]""")

        fun firstClause(s: String): String {
            val cut = CLAUSE_END.find(s)?.range?.first ?: return s.trim()
            return s.substring(0, cut).trim().ifEmpty { s.trim() }
        }
    }
}
