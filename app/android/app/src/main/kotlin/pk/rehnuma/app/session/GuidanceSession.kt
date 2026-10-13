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

/**
 * The guidance loop (SPEC 5.5): read the screen, ask the planner for one step, point, wait for the
 * user's own tap, verify, repeat. Runs on one thread (the main thread in the app).
 * It never acts for the user: it only shows, speaks and waits.
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
    private val settle = SettleDetector(clock)
    private var flowJob: Job? = null
    private var checkJob: Job? = null
    private var typeDoneJob: Job? = null
    private var step: Step? = null
    private var dirty = false
    private var lastSaid: Pair<String, String>? = null

    private class Step(
        val plan: PlanResponse,
        val target: ScreenElement?,
        val snapshot: ScreenSnapshot,
        val verifier: StepVerifier,
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
        step = null
        lastSaid = null
        runFlow { planFromScreen(fromNow = false) }
    }

    /** Short tap on the bubble during a session: repeat; in ERROR: retry. */
    fun onBubbleTap() {
        when (state) {
            SessionState.ERROR -> runFlow { planFromScreen(fromNow = false) }
            SessionState.POINTING, SessionState.WAITING_FOR_USER -> {
                val s = step ?: return
                showStep(s, repeat = true)
            }
            SessionState.ASKING, SessionState.SENSITIVE_PAUSE -> lastSaid?.let { (u, r) -> say(u, r) }
            else -> Unit
        }
    }

    /** Bubble "×" or notification "Band karein" (rule 7): stop everything now. */
    fun cancel() {
        cancelJobs()
        voice.stop()
        ui.clear()
        step = null
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
            StepVerifier.Click.MISMATCH -> scheduleCheck(fromNow = true)
            StepVerifier.Click.UNKNOWN -> scheduleCheck(fromNow = true)
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
            val snapshot = screen.read() ?: return@launch
            when (state) {
                SessionState.SENSITIVE_PAUSE -> if (SafetyGuard.check(snapshot) == GuardResult.Safe) {
                    runFlow { planFrom(snapshot) }
                }
                SessionState.POINTING, SessionState.WAITING_FOR_USER -> judge(snapshot)
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
        addHistory(s, outcome)
        ui.clear()
        voice.stop() // the old instruction is no longer true
        phrase?.let { say(it) }
        runFlow {
            if (next != null) planFrom(next) else planFromScreen(fromNow = true)
        }
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
        flowJob = scope.launch { block() }
    }

    private suspend fun planFromScreen(fromNow: Boolean) {
        setState(SessionState.READING_SCREEN)
        ui.bubble(BubbleState.THINKING, showClose = true)
        settle.await(fromNow)
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

    private suspend fun planFrom(first: ScreenSnapshot, replans: Int = 0) {
        val snapshot = first
        if (snapshot.packageName == ownPackage) {
            // Rehnuma's own screen is in front (e.g. a quick task from Home): guide the user out first.
            pointNav(PlanResponse.HOME, "گھر والا بٹن دبائیں یا نیچے سے اوپر سوائپ کریں", "Home button dabayein ya neeche se upar swipe karein", snapshot)
            return
        }
        if (SafetyGuard.check(snapshot) is GuardResult.Sensitive) {
            enterSensitivePause()
            return
        }
        if (stepIndex >= MAX_STEPS) {
            endWith(Phrase.CANT_HELP)
            return
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
                        planFrom(now, replans + 1)
                        return
                    }
                }
                if (goalKey == null) goalKey = result.value.goalKey
                handlePlan(result.value, snapshot)
            }
        }
    }

    private fun handlePlan(plan: PlanResponse, snapshot: ScreenSnapshot) {
        when (plan.action) {
            PlanResponse.TAP, PlanResponse.TYPE, PlanResponse.SCROLL -> {
                val target = plan.elementId?.let { snapshot.element(it) }
                if (target == null) {
                    endWith(Phrase.CANT_HELP)
                    return
                }
                val s = Step(plan, target, snapshot, StepVerifier(plan.action, target, snapshot))
                step = s
                showStep(s, repeat = false)
            }
            PlanResponse.BACK, PlanResponse.HOME -> pointNav(plan.action, plan.sayUrdu, plan.sayRoman, snapshot, plan)
            PlanResponse.ASK -> {
                setState(SessionState.ASKING, plan.sayRoman)
                ui.bubble(BubbleState.IDLE, showClose = true)
                ui.caption(caption(plan.sayUrdu, plan.sayRoman))
                say(plan.sayUrdu, plan.sayRoman)
            }
            PlanResponse.DONE -> complete(plan)
            else -> endWith(Phrase.CANT_HELP)
        }
    }

    private fun pointNav(action: String, urdu: String, roman: String, snapshot: ScreenSnapshot, plan: PlanResponse? = null) {
        val p = plan ?: PlanResponse(action = action, sayUrdu = urdu, sayRoman = roman, goalKey = goalKey)
        val s = Step(p, null, snapshot, StepVerifier(action, null, snapshot))
        step = s
        showStep(s, repeat = false)
    }

    private fun showStep(s: Step, repeat: Boolean) {
        val target = when (s.plan.action) {
            PlanResponse.SCROLL -> PointerTarget.Scroll(s.target!!.bounds, s.plan.scrollDirection ?: "down")
            PlanResponse.BACK, PlanResponse.HOME -> PointerTarget.Nav(s.plan.action)
            else -> PointerTarget.Element(s.target!!.bounds)
        }
        if (!repeat) setState(SessionState.POINTING, s.plan.sayRoman)
        ui.bubble(BubbleState.SPEAKING, showClose = true)
        ui.point(target, caption(s.plan.sayUrdu, s.plan.sayRoman), withHand = true) {
            if (step === s && state == SessionState.POINTING) setState(SessionState.WAITING_FOR_USER, s.plan.sayRoman)
        }
        say(s.plan.sayUrdu, s.plan.sayRoman) {
            if (step === s) ui.bubble(BubbleState.IDLE, showClose = true)
        }
    }

    // ---------------------------------------------------------------- endings and pauses

    private fun enterSensitivePause() {
        step = null
        setState(SessionState.SENSITIVE_PAUSE, Phrase.SENSITIVE_PAUSE.roman)
        ui.clear()
        ui.bubble(BubbleState.PAUSED, showClose = true)
        ui.caption(caption(Phrase.SENSITIVE_PAUSE.urdu, Phrase.SENSITIVE_PAUSE.roman))
        say(Phrase.SENSITIVE_PAUSE)
    }

    private fun complete(plan: PlanResponse) {
        step = null
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
    }

    companion object {
        const val MAX_STEPS = 25
        const val MAX_HISTORY = 8
        const val MAX_REPLANS = 2
        const val TYPE_QUIET_MS = 3000L
        const val END_LINGER_MS = 1500L
        const val END_MAX_MS = 8000L
    }
}
