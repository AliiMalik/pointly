package pk.rehnuma.app.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pk.rehnuma.app.config.AppConfig
import pk.rehnuma.app.net.HistoryItem
import pk.rehnuma.app.net.PlanRequest
import pk.rehnuma.app.net.PlanResponse
import pk.rehnuma.app.net.PlannerClient
import pk.rehnuma.app.net.Prefs
import pk.rehnuma.app.net.toApi
import pk.rehnuma.app.overlay.BubbleState
import pk.rehnuma.app.overlay.PointerTarget
import pk.rehnuma.app.screen.GuardResult
import pk.rehnuma.app.screen.Masking
import pk.rehnuma.app.screen.SafetyGuard
import pk.rehnuma.app.screen.ScreenElement
import pk.rehnuma.app.screen.ScreenSnapshot
import pk.rehnuma.app.voice.Phrase
import java.util.UUID

/** One line of `metrics.jsonl` (SPEC 5.14). Never contains screen text. */
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

/** Metrics and privacy-safe events coming out of the session (SPEC 5.4, 5.14). */
interface SessionObserver {
    fun onStep(m: StepMetrics) {}

    /** Only the package is reported for a sensitive screen (SPEC 5.4). */
    fun onSensitivePause(sessionId: String, packageName: String) {}
}

/**
 * The guidance loop (SPEC 5.5): read the screen, ask the planner (or the plan cache) for one step,
 * point, wait for the user's own tap, verify, repeat. Help fades as steps are learned (SPEC 9).
 * Runs on one thread (the main thread in the app). It never acts for the user.
 */
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

    /** Rule 4: screen events are only looked at while this is true. */
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
    private var lastSaid: Pair<String, String>? = null
    private var yourTurnSaid = false
    private var settledAt = 0L
    private var beforeListening: SessionState = SessionState.IDLE

    private class Step(
        val number: Int,
        val plan: PlanResponse,
        val target: ScreenElement?,
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

    // ---------------------------------------------------------------- inputs

    /** A goal from speech, a quick task or the text box. While ASKING it is the answer instead. */
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
        // SPEC 4 step 3: "Ek second, dekh rahi hoon" while the screen is read and planned.
        ui.caption(caption(Phrase.THINKING.urdu, Phrase.THINKING.roman))
        say(Phrase.THINKING)
        runFlow { planFromScreen(fromNow = false) }
    }

    /** The bubble is held (or tapped in Tap mode): stop talking and listen (SPEC 5.5 -> LISTENING). */
    fun listeningStarted() {
        beforeListening = if (state == SessionState.LISTENING || state == SessionState.TRANSCRIBING) beforeListening else state
        abandonStep("interrupted")
        cancelJobs()
        voice.stop()
        ui.clear()
        setState(SessionState.LISTENING)
        ui.bubble(BubbleState.LISTENING, showClose = true)
        ui.caption(caption(Phrase.ASK_GOAL.urdu, Phrase.ASK_GOAL.roman))
    }

    /** Words recognized so far, shown while listening (SPEC 5.10). */
    fun speechPartial(text: String) {
        if (state == SessionState.LISTENING) ui.caption(text)
    }

    /** Cloud fallback is uploading the recording. */
    fun transcribing() {
        if (state != SessionState.LISTENING) return
        setState(SessionState.TRANSCRIBING)
        ui.bubble(BubbleState.THINKING, showClose = true)
    }

    /** Speech finished. Blank: DIDNT_HEAR and back to what was happening. Otherwise a goal or an answer. */
    fun speechResult(text: String?) {
        if (state != SessionState.LISTENING && state != SessionState.TRANSCRIBING) return
        val previous = beforeListening
        if (text.isNullOrBlank()) {
            ui.caption(caption(Phrase.DIDNT_HEAR.urdu, Phrase.DIDNT_HEAR.roman))
            say(Phrase.DIDNT_HEAR)
            when {
                previous == SessionState.ASKING -> {
                    setState(SessionState.ASKING)
                    ui.bubble(BubbleState.IDLE, showClose = true)
                }
                goalText.isNotEmpty() && previous != SessionState.IDLE && previous != SessionState.COMPLETED ->
                    runFlow { planFromScreen(fromNow = false) } // pick the guidance up again
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

    /** Short tap on the bubble during a session: repeat with full help; in ERROR: retry. */
    fun onBubbleTap() {
        when (state) {
            SessionState.ERROR -> runFlow { planFromScreen(fromNow = false) }
            SessionState.POINTING, SessionState.WAITING_FOR_USER -> {
                val s = step ?: return
                showFull(s, speak = true)
            }
            SessionState.ASKING, SessionState.SENSITIVE_PAUSE -> lastSaid?.let { (u, r) -> say(u, r) }
            else -> Unit
        }
    }

    /** Audio really started (for the `ttsStartMs` metric). */
    fun onSpeechStarted() {
        val s = step ?: return
        if (s.speechStartedAt == 0L && s.shownAt != 0L) s.speechStartedAt = clock()
    }

    /** Bubble "×" or notification "Band karein" (rule 7): stop everything now. */
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
                    // Done after 3 s with no more typing (SPEC 5.7).
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
                if (state == SessionState.WAITING_FOR_USER && s.verifier.onScrolled(e.source)) stepDone(s, "done")
            }
        }
    }

    // ---------------------------------------------------------------- event handling

    private fun onClick(e: ScreenEvent) {
        val s = step ?: return
        if (state != SessionState.POINTING && state != SessionState.WAITING_FOR_USER) return
        when (s.verifier.onClick(e.packageName, e.source)) {
            StepVerifier.Click.MATCH -> stepDone(s, "done")
            // A wrong tap may or may not change the screen; look once it settles.
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

    /** One pending look at the screen after it settles; new events only extend the wait. */
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
                    // A PIN field can appear on the same window (e.g. a bottom sheet): hide the pointer now.
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
            StepVerifier.Verdict.UNCHANGED -> Unit
            StepVerifier.Verdict.WRONG_TAP -> if (!s.wrongTapSaid) {
                s.wrongTapSaid = true
                say(Phrase.WRONG_TAP)
            }
            StepVerifier.Verdict.PROGRESS -> stepDone(s, "done", next = snapshot)
            StepVerifier.Verdict.DEVIATION -> stepDone(s, "deviated", next = snapshot, phrase = Phrase.DEVIATION)
        }
    }

    /** Records the step and moves on to the next screen (SPEC 5.5 WAITING_FOR_USER -> READING_SCREEN). */
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
                    sayUrdu = s.plan.sayUrdu,
                    sayRoman = s.plan.sayRoman,
                )
            }
        }
        ui.clear()
        voice.stop() // the old instruction is no longer true
        phrase?.let { say(it) }
        runFlow {
            if (next != null) planFrom(next) else planFromScreen(fromNow = true)
        }
    }

    /** A step that was shown but never finished (cancel, new goal) still gets a metrics line. */
    private fun abandonStep(outcome: String) {
        val s = step ?: return
        step = null
        report(s, outcome, tapped = false)
    }

    private fun addHistory(s: Step, outcome: String) {
        _history += HistoryItem(
            step = _history.size + 1,
            instructionRoman = s.plan.sayRoman,
            targetLabel = s.target?.displayLabel,
            outcome = outcome,
        )
        while (_history.size > MAX_HISTORY) _history.removeAt(0)
    }

    // ---------------------------------------------------------------- read -> plan -> point

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
            fail("اسکرین نہیں پڑھ سکی۔ بلبلہ دبا کر دوبارہ کوشش کریں۔", "Screen nahi parh saki. Bubble daba kar dobara koshish karein.", "no readable window")
            return
        }
        planFrom(snapshot)
    }

    private suspend fun planFrom(snapshot: ScreenSnapshot, replans: Int = 0) {
        if (snapshot.packageName == ownPackage) {
            // Rehnuma's own screen is in front (e.g. a quick task from Home): guide the user out first.
            val p = PlanResponse(
                action = PlanResponse.HOME,
                sayUrdu = "گھر والا بٹن دبائیں یا نیچے سے اوپر سوائپ کریں",
                sayRoman = "Home button dabayein ya neeche se upar swipe karein",
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

        // SPEC 9.2: after the first step, a known screen of a known task needs no network.
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
                    sayUrdu = hit.step.sayUrdu,
                    sayRoman = hit.step.sayRoman,
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
            prefs = Prefs(config().caption),
        )
        val result = planner.plan(request) {
            ui.caption(caption(Phrase.NETWORK_SLOW.urdu, Phrase.NETWORK_SLOW.roman))
            voice.speak(Phrase.NETWORK_SLOW.urdu, Phrase.NETWORK_SLOW.roman)
        }
        when (result) {
            is PlannerClient.Result.Failed -> {
                stepIndex-- // the retry asks for the same step again
                val detail = "${result.failure}: ${result.message}"
                when (result.failure) {
                    PlannerClient.Failure.AUTH -> fail("ایپ کی غلط ہے۔ سیٹنگز دیکھیں۔", "App key ghalat hai. Settings dekhein.", detail)
                    else -> fail("انٹرنیٹ کا مسئلہ ہے۔ دوبارہ کوشش کے لیے بلبلہ دبائیں۔", "Internet ka masla hai. Dobara koshish ke liye bubble dabayein.", detail)
                }
            }
            is PlannerClient.Result.Ok -> {
                if (dirty && replans < MAX_REPLANS) {
                    // The screen moved while we were thinking: make sure the plan still fits.
                    val now = screen.read()
                    if (now != null && (now.signature != snapshot.signature || now.windowId != snapshot.windowId)) {
                        stepIndex--
                        settledAt = clock()
                        planFrom(now, replans + 1)
                        return
                    }
                }
                if (goalKey == null) goalKey = result.value.goalKey
                handlePlan(result.value, snapshot, fromCache = false, plannerMs = result.ms)
            }
        }
    }

    private fun handlePlan(plan: PlanResponse, snapshot: ScreenSnapshot, fromCache: Boolean, plannerMs: Long?) {
        when (plan.action) {
            PlanResponse.TAP, PlanResponse.TYPE, PlanResponse.SCROLL -> {
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
                setState(SessionState.ASKING, plan.sayRoman)
                ui.bubble(BubbleState.IDLE, showClose = true)
                ui.caption(caption(plan.sayUrdu, plan.sayRoman))
                say(plan.sayUrdu, plan.sayRoman)
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
        setState(SessionState.POINTING, plan.sayRoman)
        s.shownAt = clock()
        when (level) {
            GuidanceLevel.FULL -> showFull(s, speak = true)
            GuidanceLevel.HINT -> {
                // Short spoken hint and caption; the hand only if the user hesitates (SPEC 9.1).
                val urdu = firstClause(plan.sayUrdu)
                val roman = firstClause(plan.sayRoman)
                ui.bubble(BubbleState.SPEAKING, showClose = true)
                ui.point(pointerTarget(s), caption(urdu, roman), withHand = false) { arrived(s) }
                say(urdu, roman) { if (step === s) ui.bubble(BubbleState.IDLE, showClose = true) }
                revealAfter(s, HINT_REVEAL_MS)
            }
            GuidanceLevel.SILENT -> {
                ui.bubble(BubbleState.IDLE, showClose = true)
                ui.point(pointerTarget(s), null, withHand = false) { arrived(s) }
                if (!yourTurnSaid) {
                    yourTurnSaid = true
                    say(Phrase.YOUR_TURN)
                }
                revealAfter(s, SILENT_REVEAL_MS)
            }
        }
    }

    /** Hand, ring and caption, with the full spoken instruction (FULL level, repeats, hesitation). */
    private fun showFull(s: Step, speak: Boolean) {
        ui.bubble(BubbleState.SPEAKING, showClose = true)
        ui.point(pointerTarget(s), caption(s.plan.sayUrdu, s.plan.sayRoman), withHand = true) { arrived(s) }
        if (speak) {
            say(s.plan.sayUrdu, s.plan.sayRoman) {
                if (step === s) ui.bubble(BubbleState.IDLE, showClose = true)
            }
        } else {
            ui.bubble(BubbleState.IDLE, showClose = true)
        }
    }

    private fun arrived(s: Step) {
        if (step === s && state == SessionState.POINTING) setState(SessionState.WAITING_FOR_USER, s.plan.sayRoman)
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
        PlanResponse.BACK, PlanResponse.HOME -> PointerTarget.Nav(s.plan.action)
        else -> PointerTarget.Element(s.target!!.bounds)
    }

    // ---------------------------------------------------------------- endings and pauses

    private fun enterSensitivePause(snapshot: ScreenSnapshot) {
        step = null
        hesitationJob?.cancel()
        if (state != SessionState.SENSITIVE_PAUSE) observer.onSensitivePause(sessionId, snapshot.packageName)
        setState(SessionState.SENSITIVE_PAUSE, Phrase.SENSITIVE_PAUSE.roman)
        ui.clear()
        ui.bubble(BubbleState.PAUSED, showClose = true)
        ui.caption(caption(Phrase.SENSITIVE_PAUSE.urdu, Phrase.SENSITIVE_PAUSE.roman))
        say(Phrase.SENSITIVE_PAUSE)
    }

    private fun complete(plan: PlanResponse) {
        step = null
        goalKey?.let { cache.store(it, finishedSteps.toList()) }
        // "done" because the rest is the user's own (PIN, payment): say the planner's sentence.
        val userFinishes = plan.sayRoman.contains("khud", ignoreCase = true)
        val urdu = if (userFinishes) plan.sayUrdu else Phrase.DONE.urdu
        val roman = if (userFinishes) plan.sayRoman else Phrase.DONE.roman
        setState(SessionState.COMPLETED, roman)
        ui.clear()
        ui.bubble(BubbleState.SPEAKING, showClose = false)
        ui.caption(caption(urdu, roman))
        finishAfterSpeech(urdu, roman)
    }

    private fun endWith(phrase: Phrase) {
        step = null
        setState(SessionState.COMPLETED, phrase.roman)
        ui.clear()
        ui.bubble(BubbleState.SPEAKING, showClose = false)
        ui.caption(caption(phrase.urdu, phrase.roman))
        finishAfterSpeech(phrase.urdu, phrase.roman)
    }

    private fun finishAfterSpeech(urdu: String, roman: String) {
        var finished = false
        val finish = {
            if (!finished && state == SessionState.COMPLETED) {
                finished = true
                ui.clear()
                ui.bubble(BubbleState.IDLE, showClose = false)
                setState(SessionState.IDLE)
            }
        }
        say(urdu, roman) { scope.launch { delay(END_LINGER_MS); finish() } }
        // Never stay stuck if speech never reports back.
        scope.launch { delay(END_MAX_MS); finish() }
    }

    /** Recoverable error (SPEC 5.5): plain words for the user, details only for the app's status line. */
    private fun fail(urdu: String, roman: String, detail: String) {
        step = null
        setState(SessionState.ERROR, captionRoman = roman, error = detail)
        ui.clear()
        ui.bubble(BubbleState.ERROR, showClose = true)
        ui.caption(caption(urdu, roman))
        lastSaid = urdu to roman
    }

    // ---------------------------------------------------------------- metrics

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

    // ---------------------------------------------------------------- helpers

    private fun say(p: Phrase) = say(p.urdu, p.roman)

    private fun say(urdu: String, roman: String, onDone: () -> Unit = {}) {
        lastSaid = urdu to roman
        voice.speak(urdu, roman, onDone)
    }

    private fun caption(urdu: String, roman: String) =
        if (config().caption == AppConfig.CAPTION_URDU) urdu else roman

    private fun setState(s: SessionState, captionRoman: String? = null, error: String? = null) {
        state = s
        ui.emit(s, goalText.takeIf { it.isNotEmpty() }, stepIndex.takeIf { it > 0 }, captionRoman, error)
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

        /** "Sara ke naam pe dabayein, phir photo chunein" -> "Sara ke naam pe dabayein" (SPEC 9.1 HINT). */
        fun firstClause(s: String): String {
            val cut = CLAUSE_END.find(s)?.range?.first ?: return s.trim()
            return s.substring(0, cut).trim().ifEmpty { s.trim() }
        }
    }
}
