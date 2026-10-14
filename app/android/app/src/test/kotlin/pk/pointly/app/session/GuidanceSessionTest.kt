package pk.pointly.app.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pk.pointly.app.config.AppConfig
import pk.pointly.app.net.PlanRequest
import pk.pointly.app.net.PlanResponse
import pk.pointly.app.net.PlannerClient
import pk.pointly.app.overlay.BubbleState
import pk.pointly.app.overlay.PointerTarget
import pk.pointly.app.screen.ScreenSnapshot
import pk.pointly.app.screen.TestScreens.el
import pk.pointly.app.screen.TestScreens.snap
import pk.pointly.app.voice.Line
import pk.pointly.app.voice.Phrase

@OptIn(ExperimentalCoroutinesApi::class)
class GuidanceSessionTest {

    private val own = "pk.pointly.app"

    private val launcher = snap(
        el(1, text = "WhatsApp", cls = "TextView", clickable = true, bounds = listOf(100, 1800, 300, 2000)),
        el(2, text = "Settings", cls = "TextView", clickable = true, bounds = listOf(400, 1800, 600, 2000)),
        pkg = "com.android.launcher", windowId = 10,
    )
    private val chats = snap(
        el(1, text = "Chats", clickable = true, bounds = listOf(0, 300, 360, 400)),
        el(2, label = "Sara · Kal milte hain", viewId = "row", clickable = true, bounds = listOf(0, 420, 1080, 560)),
        el(3, label = "Ali · Salam", viewId = "row", clickable = true, bounds = listOf(0, 560, 1080, 700)),
        pkg = "com.whatsapp", windowId = 20,
    )
    private val saraChat = snap(el(1, text = "Sara", bounds = listOf(0, 100, 500, 200)), el(2, desc = "Attach", clickable = true), pkg = "com.whatsapp", windowId = 21)
    private val aliChat = snap(el(1, text = "Ali", bounds = listOf(0, 100, 500, 200)), el(2, desc = "Attach", clickable = true), pkg = "com.whatsapp", windowId = 22)
    private val pinScreen = snap(el(1, text = "Enter MPIN"), el(2, cls = "EditText", editable = true, clickable = true, bounds = listOf(0, 300, 1000, 400)), pkg = "com.wallet", windowId = 30)
    private val walletHome = snap(el(1, text = "Mobile Load", clickable = true), pkg = "com.wallet", windowId = 31)

    private class FakeScreen(var current: ScreenSnapshot?) : ScreenSource {
        var reads = 0
        override suspend fun read(): ScreenSnapshot? {
            reads++
            return current
        }
    }

    private class FakePlanner : PlanSource {
        val requests = mutableListOf<PlanRequest>()
        val answers = ArrayDeque<(PlanRequest, () -> Unit) -> PlannerClient.Result<PlanResponse>>()
        var gate: CompletableDeferred<Unit>? = null

        fun then(plan: PlanResponse) = answers.addLast { _, _ -> PlannerClient.Result.Ok(plan, 900) }

        fun fail(failure: PlannerClient.Failure) = answers.addLast { _, retry ->
            retry()
            PlannerClient.Result.Failed(failure, "down", 16000)
        }

        override suspend fun plan(request: PlanRequest, onRetry: () -> Unit): PlannerClient.Result<PlanResponse> {
            requests += request
            gate?.await()
            val a = answers.removeFirstOrNull() ?: error("no planner answer queued for step ${request.stepIndex}")
            return a(request, onRetry)
        }
    }

    private class FakeUi : SessionUi {
        val points = mutableListOf<Pair<PointerTarget, String?>>()
        val hands = mutableListOf<Boolean>()
        val captions = mutableListOf<String?>()
        val states = mutableListOf<SessionState>()
        var bubble = BubbleState.IDLE
        var showClose = false
        var cleared = 0

        override fun bubble(state: BubbleState, showClose: Boolean) {
            bubble = state
            this.showClose = showClose
        }

        override fun point(target: PointerTarget, caption: String?, withHand: Boolean, onArrived: () -> Unit) {
            points += target to caption
            hands += withHand
            onArrived()
        }

        override fun caption(text: String?) {
            captions += text
        }

        override fun revealHand() {}

        val moves = mutableListOf<PointerTarget>()
        override fun movePointer(target: PointerTarget) {
            moves += target
        }

        val flashes = mutableListOf<String>()
        override fun flashCaption(text: String) {
            flashes += text
        }

        override fun clear() {
            cleared++
        }

        override fun emit(state: SessionState, goalText: String?, step: Int?, caption: String?, error: String?) {
            states += state
        }
    }

    private class FakeVoice : SessionVoice {
        val said = mutableListOf<String>()
        var stops = 0
        override fun speak(line: Line, onDone: () -> Unit) {
            said += line.en
            onDone()
        }

        override fun stop() {
            stops++
        }
    }

    private class FakeObserver : SessionObserver {
        val steps = mutableListOf<StepMetrics>()
        val pauses = mutableListOf<String>()
        override fun onStep(m: StepMetrics) {
            steps += m
        }

        override fun onSensitivePause(sessionId: String, packageName: String) {
            pauses += packageName
        }
    }

    private class Rig(
        val session: GuidanceSession,
        val screen: FakeScreen,
        val planner: FakePlanner,
        val ui: FakeUi,
        val voice: FakeVoice,
        val observer: FakeObserver,
    )

    private fun TestScope.rig(
        start: ScreenSnapshot?,
        config: AppConfig = AppConfig(),
        learning: LearningStore = LearningStore(null) { 0 },
        cache: PlanCache = PlanCache(null),
    ): Rig {
        val screen = FakeScreen(start)
        val planner = FakePlanner()
        val ui = FakeUi()
        val voice = FakeVoice()
        val observer = FakeObserver()
        var n = 0
        val session = GuidanceSession(
            scope = backgroundScope,
            screen = screen,
            planner = planner,
            ui = ui,
            voice = voice,
            config = { config },
            ownPackage = own,
            clock = { testScheduler.currentTime },
            learning = learning,
            cache = cache,
            observer = observer,
            newSessionId = { "s${++n}" },
        )
        return Rig(session, screen, planner, ui, voice, observer)
    }

    private fun tap(id: Int, say: String = "Tap this", key: String = "test_goal") =
        PlanResponse(action = "tap", elementId = id, sayEn = say, sayUr = "اسے دبائیں", goalKey = key, confidence = 0.9)

    private fun plan(action: String, say: String = "OK", id: Int? = null, key: String = "test_goal") =
        PlanResponse(action = action, elementId = id, sayEn = say, sayUr = "ٹھیک ہے", goalKey = key, confidence = 0.9)

    private fun click(pkg: String, bounds: List<Int>?, label: String? = null, viewId: String? = null) =
        ScreenEvent(ScreenEvent.Type.CLICKED, pkg, source = SourceInfo(viewId = viewId, label = label, bounds = bounds))

    private fun windowChange(pkg: String) = ScreenEvent(ScreenEvent.Type.WINDOW_STATE, pkg)

    private fun TestScope.settle() {
        advanceTimeBy(600)
        runCurrent()
    }

    @Test fun happyPathTapThenDone() = runTest {
        val r = rig(launcher)
        r.planner.then(tap(1, "Tap the green WhatsApp icon", "whatsapp_send_photo"))
        r.planner.then(plan("done"))

        r.session.submitGoal("WhatsApp kholna hai")
        runCurrent()
        assertEquals(SessionState.WAITING_FOR_USER, r.session.state)
        assertEquals(PointerTarget.Element(listOf(100, 1800, 300, 2000)), r.ui.points.last().first)
        assertEquals("Tap the green WhatsApp icon", r.ui.points.last().second)
        assertTrue(r.voice.said.contains("Tap the green WhatsApp icon"))
        assertEquals("whatsapp_send_photo", r.session.goalKey)

        r.screen.current = chats
        r.session.onEvent(click("com.android.launcher", listOf(100, 1800, 300, 2000), "whatsapp"))
        runCurrent()
        assertEquals(1, r.session.history.size)
        assertEquals("done", r.session.history[0].outcome)
        settle()

        assertEquals(2, r.planner.requests.size)
        val second = r.planner.requests[1]
        assertEquals(2, second.stepIndex)
        assertEquals("com.whatsapp", second.screen.packageName)
        assertEquals("Tap the green WhatsApp icon", second.history.single().instruction)
        assertEquals(SessionState.COMPLETED, r.session.state)
        assertTrue(r.voice.said.contains(Phrase.DONE.en))

        advanceTimeBy(GuidanceSession.END_MAX_MS + 100)
        runCurrent()
        assertEquals(SessionState.IDLE, r.session.state)
        assertFalse(r.session.isActive)
    }

    @Test fun deviationIsSpokenAndReplans() = runTest {
        val r = rig(chats)
        r.planner.then(tap(2, "Sara ke naam pe dabayein"))
        r.planner.then(plan("back", "Peeche wala button dabayein"))
        r.session.submitGoal("Sara ko photo bhejni hai")
        runCurrent()

        r.session.onEvent(click("com.whatsapp", listOf(0, 560, 1080, 700), "ali · salam", "row"))
        r.screen.current = aliChat
        r.session.onEvent(windowChange("com.whatsapp"))
        settle()

        assertEquals(listOf(Phrase.DEVIATION.en), r.voice.said.filter { it == Phrase.DEVIATION.en })
        assertTrue(r.ui.captions.contains(Phrase.DEVIATION.en))
        assertEquals("deviated", r.session.history.single().outcome)
        assertEquals(2, r.planner.requests.size)
        assertEquals("Ali", r.planner.requests[1].screen.elements[0].text)
        assertEquals(PointerTarget.Nav("back"), r.ui.points.last().first)
    }

    @Test fun wrongTapOnSameScreenIsSaidOnce() = runTest {
        val r = rig(chats)
        r.planner.then(tap(2))
        r.session.submitGoal("Sara ko photo bhejni hai")
        runCurrent()

        r.session.onEvent(click("com.whatsapp", listOf(0, 300, 360, 400), "chats"))
        settle()
        r.session.onEvent(click("com.whatsapp", listOf(0, 300, 360, 400), "chats"))
        settle()

        assertEquals(1, r.voice.said.count { it == Phrase.WRONG_TAP.en })
        assertEquals(listOf(Phrase.WRONG_TAP.en), r.ui.flashes)
        assertEquals(SessionState.WAITING_FOR_USER, r.session.state)
        assertEquals(1, r.planner.requests.size)
    }

    @Test fun noClickEventButWindowChangeCountsAsProgress() = runTest {
        val r = rig(chats)
        r.planner.then(tap(2))
        r.planner.then(plan("done"))
        r.session.submitGoal("Sara")
        runCurrent()

        r.screen.current = saraChat
        r.session.onEvent(windowChange("com.whatsapp"))
        settle()
        assertEquals("done", r.session.history.single().outcome)
        assertEquals(2, r.planner.requests.size)
    }

    @Test fun contentNoiseOnSameScreenKeepsWaiting() = runTest {
        val r = rig(chats)
        r.planner.then(tap(2))
        r.session.submitGoal("Sara")
        runCurrent()
        r.session.onEvent(ScreenEvent(ScreenEvent.Type.WINDOW_CONTENT, "com.android.systemui"))
        settle()
        assertEquals(SessionState.WAITING_FOR_USER, r.session.state)
        assertEquals(1, r.planner.requests.size)
    }

    @Test fun sensitiveScreenPausesWithoutUploadingAndResumes() = runTest {
        val r = rig(walletHome)
        r.planner.then(tap(1, "Mobile Load dabayein"))
        r.planner.then(plan("done", "Shabash"))
        r.session.submitGoal("Mobile load karna hai")
        runCurrent()

        r.screen.current = pinScreen
        r.session.onEvent(click("com.wallet", walletHome.elements[0].bounds))
        settle()
        assertEquals(SessionState.SENSITIVE_PAUSE, r.session.state)
        assertEquals(1, r.planner.requests.size)
        assertTrue(r.voice.said.contains(Phrase.SENSITIVE_PAUSE.en))
        assertEquals(BubbleState.PAUSED, r.ui.bubble)
        assertTrue(r.session.isActive)

        r.session.onEvent(ScreenEvent(ScreenEvent.Type.WINDOW_CONTENT, "com.wallet"))
        settle()
        assertEquals(SessionState.SENSITIVE_PAUSE, r.session.state)
        assertEquals(1, r.planner.requests.size)

        r.screen.current = walletHome
        r.session.onEvent(windowChange("com.wallet"))
        settle()
        assertEquals(2, r.planner.requests.size)
        assertEquals("com.wallet", r.planner.requests[1].screen.packageName)
    }

    @Test fun networkFailureShowsErrorThenRetryWorks() = runTest {
        val r = rig(launcher)
        r.planner.fail(PlannerClient.Failure.NETWORK)
        r.planner.then(tap(1))
        r.session.submitGoal("WhatsApp")
        runCurrent()

        assertEquals(SessionState.ERROR, r.session.state)
        assertEquals(BubbleState.ERROR, r.ui.bubble)
        assertTrue(r.voice.said.contains(Phrase.NETWORK_SLOW.en))
        assertFalse(r.session.isActive)

        r.session.onBubbleTap()
        runCurrent()
        assertEquals(SessionState.WAITING_FOR_USER, r.session.state)
        assertEquals(listOf(1, 1), r.planner.requests.map { it.stepIndex })
    }

    @Test fun cancelStopsEverything() = runTest {
        val r = rig(launcher)
        r.planner.then(tap(1))
        r.session.submitGoal("WhatsApp")
        runCurrent()
        val stopsBefore = r.voice.stops

        r.session.cancel()
        assertEquals(SessionState.IDLE, r.session.state)
        assertTrue(r.voice.stops > stopsBefore)
        assertTrue(r.ui.cleared > 0)
        assertFalse(r.ui.showClose)

        r.screen.current = chats
        r.session.onEvent(windowChange("com.whatsapp"))
        settle()
        assertEquals(1, r.planner.requests.size)
    }

    @Test fun doneForTheUsersOwnPartSaysThePlannerLine() = runTest {
        val r = rig(launcher)
        r.planner.then(plan("done", "Please type your PIN yourself."))
        r.session.submitGoal("Pay my bill")
        runCurrent()
        assertTrue(r.voice.said.contains("Please type your PIN yourself."))
        assertFalse(r.voice.said.contains(Phrase.DONE.en))
        advanceTimeBy(GuidanceSession.END_MAX_MS + 100)
    }

    @Test fun stopsAfter25Steps() = runTest {
        val r = rig(launcher)
        repeat(GuidanceSession.MAX_STEPS) { r.planner.then(tap(1)) }
        r.session.submitGoal("loop")
        runCurrent()
        repeat(GuidanceSession.MAX_STEPS) {
            r.session.onEvent(click("com.android.launcher", listOf(100, 1800, 300, 2000)))
            settle()
        }
        assertEquals(GuidanceSession.MAX_STEPS, r.planner.requests.size)
        assertTrue(r.voice.said.contains(Phrase.CANT_HELP.en))
        assertEquals(8, r.planner.requests.last().history.size)
        advanceTimeBy(GuidanceSession.END_MAX_MS + 100)
        runCurrent()
        assertEquals(SessionState.IDLE, r.session.state)
    }

    @Test fun askThenAnswerReplans() = runTest {
        val r = rig(chats)
        r.planner.then(plan("ask", "Who should get it?"))
        r.planner.then(tap(2))
        r.session.submitGoal("Photo bhejni hai")
        runCurrent()
        assertEquals(SessionState.ASKING, r.session.state)
        assertTrue(r.ui.captions.contains("Who should get it?"))

        r.session.submitGoal("Sara ko")
        runCurrent()
        assertEquals("Photo bhejni hai\nUser answer: Sara ko", r.planner.requests[1].goalText)
        assertEquals("s1", r.planner.requests[1].sessionId)
        assertEquals(SessionState.WAITING_FOR_USER, r.session.state)
    }

    @Test fun ownScreenFirstGuidesHomeWithoutPlanner() = runTest {
        val ownScreen = snap(pkg = own, windowId = 99)
        val r = rig(ownScreen)
        r.planner.then(tap(1))
        r.session.submitGoal("WhatsApp pe photo bhejni hai")
        runCurrent()
        assertEquals(0, r.planner.requests.size)
        assertEquals(PointerTarget.Nav("home"), r.ui.points.last().first)

        r.session.onEvent(windowChange(own))
        settle()
        assertEquals(0, r.planner.requests.size)
        r.screen.current = launcher
        r.session.onEvent(windowChange("com.android.launcher"))
        settle()
        assertEquals(1, r.planner.requests.size)
        assertEquals(1, r.planner.requests[0].history.size)
    }

    @Test fun screenChangeWhilePlanningReplans() = runTest {
        val r = rig(launcher)
        r.planner.gate = CompletableDeferred()
        r.planner.then(tap(1))
        r.planner.then(tap(2))
        r.session.submitGoal("WhatsApp")
        runCurrent()
        assertEquals(SessionState.PLANNING, r.session.state)

        r.screen.current = chats
        r.session.onEvent(windowChange("com.whatsapp"))
        r.planner.gate!!.complete(Unit)
        runCurrent()
        assertEquals(2, r.planner.requests.size)
        assertEquals(1, r.planner.requests[1].stepIndex)
        assertEquals("com.whatsapp", r.planner.requests[1].screen.packageName)
    }

    @Test fun typeStepFinishesAfterThreeQuietSeconds() = runTest {
        val search = snap(el(1, hint = "Search", cls = "EditText", editable = true, clickable = true, viewId = "search", bounds = listOf(0, 200, 1080, 300)), pkg = "com.android.settings")
        val r = rig(search)
        r.planner.then(plan("type", "Wifi likhein", id = 1))
        r.planner.then(plan("done"))
        r.session.submitGoal("WiFi")
        runCurrent()

        r.session.onEvent(ScreenEvent(ScreenEvent.Type.TEXT_CHANGED, "com.android.settings", source = SourceInfo(viewId = "search")))
        advanceTimeBy(2000)
        runCurrent()
        assertTrue(r.session.history.isEmpty())
        advanceTimeBy(1500)
        runCurrent()
        assertEquals("done", r.session.history.single().outcome)
    }

    @Test fun eventsIgnoredWhenIdle() = runTest {
        val r = rig(launcher)
        r.session.onEvent(windowChange("com.whatsapp"))
        settle()
        assertEquals(0, r.screen.reads)
        assertNull(r.session.goalKey)
    }

    @Test fun goalIsMaskedBeforeUpload() = runTest {
        val r = rig(launcher)
        r.planner.then(tap(1))
        r.session.submitGoal("0300 1234567 pe call karni hai")
        runCurrent()
        assertEquals("[number] pe call karni hai", r.planner.requests[0].goalText)
    }

    @Test fun tapDuringPointingRepeatsInstruction() = runTest {
        val r = rig(launcher)
        r.planner.then(tap(1, "Hara icon dabayein"))
        r.session.submitGoal("WhatsApp")
        runCurrent()
        r.session.onBubbleTap()
        assertEquals(2, r.voice.said.count { it == "Hara icon dabayein" })
        assertEquals(2, r.ui.points.size)
        assertEquals(1, r.planner.requests.size)
    }

    @Test fun urduLanguageSetting() = runTest {
        val r = rig(launcher, AppConfig(language = AppConfig.LANG_UR))
        r.planner.then(tap(1))
        r.session.submitGoal("WhatsApp")
        runCurrent()
        assertEquals("اسے دبائیں", r.ui.points.last().second)
        assertEquals("ur", r.planner.requests[0].prefs.language)
    }

    @Test fun spokenGoalStartsSessionAndSaysThinking() = runTest {
        val r = rig(launcher)
        r.planner.then(tap(1))
        r.session.listeningStarted()
        assertEquals(SessionState.LISTENING, r.session.state)
        assertEquals(BubbleState.LISTENING, r.ui.bubble)
        r.session.speechPartial("WhatsApp")
        assertEquals("WhatsApp", r.ui.captions.last())
        r.session.speechResult("WhatsApp pe photo bhejni hai")
        runCurrent()
        assertTrue(r.voice.said.contains(Phrase.THINKING.en))
        assertEquals("WhatsApp pe photo bhejni hai", r.planner.requests.single().goalText)
        assertEquals(SessionState.WAITING_FOR_USER, r.session.state)
    }

    @Test fun silenceFromIdleSaysDidntHearAndStaysIdle() = runTest {
        val r = rig(launcher)
        r.session.listeningStarted()
        r.session.transcribing()
        assertEquals(SessionState.TRANSCRIBING, r.session.state)
        r.session.speechResult("  ")
        runCurrent()
        assertEquals(SessionState.IDLE, r.session.state)
        assertTrue(r.voice.said.contains(Phrase.DIDNT_HEAR.en))
        assertEquals(0, r.planner.requests.size)
    }

    @Test fun spokenAnswerWhileAsking() = runTest {
        val r = rig(chats)
        r.planner.then(plan("ask", "Who should get it?"))
        r.planner.then(tap(2))
        r.session.submitGoal("Photo bhejni hai")
        runCurrent()
        r.session.listeningStarted()
        r.session.speechResult("Sara ko")
        runCurrent()
        assertEquals("Photo bhejni hai\nUser answer: Sara ko", r.planner.requests[1].goalText)
        assertEquals("s1", r.planner.requests[1].sessionId)
    }

    @Test fun silenceWhileAskingKeepsAsking() = runTest {
        val r = rig(chats)
        r.planner.then(plan("ask", "Who should get it?"))
        r.session.submitGoal("Photo bhejni hai")
        runCurrent()
        r.session.listeningStarted()
        r.session.speechResult(null)
        assertEquals(SessionState.ASKING, r.session.state)
    }

    @Test fun silenceDuringGuidanceResumesSameGoal() = runTest {
        val r = rig(launcher)
        r.planner.then(tap(1))
        r.planner.then(tap(1))
        r.session.submitGoal("WhatsApp")
        runCurrent()
        r.session.listeningStarted()
        r.session.speechResult("")
        runCurrent()
        assertEquals(2, r.planner.requests.size)
        assertEquals("WhatsApp", r.planner.requests[1].goalText)
        assertEquals(SessionState.WAITING_FOR_USER, r.session.state)
    }

    private val displayPage = snap(
        el(1, text = "Display size & text", viewId = "title", clickable = true, bounds = listOf(0, 1000, 1080, 1130)),
        pkg = "com.android.settings", windowId = 40,
    )
    private val textPage = snap(el(1, text = "Font size", clickable = true), pkg = "com.android.settings", windowId = 41)

    private fun TestScope.fontSizeRun(r: Rig) {
        r.screen.current = displayPage
        r.planner.then(tap(1, "Display size and text wala option dabayein, phir font", "settings_font_size"))
        r.planner.then(plan("done", key = "settings_font_size"))
        r.session.submitGoal("Phone ke likhai bari karni hai")
        runCurrent()
        r.screen.current = textPage
        r.session.onEvent(click("com.android.settings", listOf(0, 1000, 1080, 1130)))
        settle()
        advanceTimeBy(GuidanceSession.END_MAX_MS + 100)
        runCurrent()
        assertEquals(SessionState.IDLE, r.session.state)
    }

    @Test fun helpFadesOverRepeatedRuns() = runTest {
        val r = rig(displayPage)
        val levels = mutableListOf<String>()
        repeat(5) {
            fontSizeRun(r)
            levels += r.observer.steps.last { it.action == "tap" }.guidanceLevel
        }
        assertEquals(listOf("FULL", "FULL", "HINT", "HINT", "SILENT"), levels)
        assertTrue(r.voice.said.contains(Phrase.YOUR_TURN.en))
        assertTrue(r.voice.said.contains("Display size and text wala option dabayein"))
    }

    @Test fun hintShowsHandOnlyAfterHesitation() = runTest {
        val learning = LearningStore(null) { 0 }
        val key = LearningStore.key("settings_font_size", "com.android.settings", "id:title")
        repeat(2) { learning.recordSuccess(key) }
        val r = rig(displayPage, learning = learning)
        r.planner.then(tap(1, "Display wala option dabayein", "settings_font_size"))
        r.session.submitGoal("font")
        runCurrent()
        assertEquals(listOf(false), r.ui.hands)
        advanceTimeBy(GuidanceSession.HINT_REVEAL_MS + 100)
        runCurrent()
        assertEquals(listOf(false, true), r.ui.hands)
    }

    @Test fun demoModeAlwaysFull() = runTest {
        val learning = LearningStore(null) { 0 }
        repeat(6) { learning.recordSuccess(LearningStore.key("settings_font_size", "com.android.settings", "id:title")) }
        val r = rig(displayPage, AppConfig(demoMode = true), learning)
        r.planner.then(tap(1, "Display", "settings_font_size"))
        r.session.submitGoal("font")
        runCurrent()
        assertEquals(listOf(true), r.ui.hands)
    }

    @Test fun repeatedTaskUsesPlanCacheAfterFirstStep() = runTest {
        val r = rig(launcher, AppConfig(demoMode = true))
        r.planner.then(tap(1, "WhatsApp dabayein", "whatsapp_send_photo"))
        r.planner.then(tap(2, "Sara dabayein", "whatsapp_send_photo"))
        r.planner.then(plan("done", key = "whatsapp_send_photo"))
        r.session.submitGoal("Sara ko photo")
        runCurrent()
        r.screen.current = chats
        r.session.onEvent(click("com.android.launcher", listOf(100, 1800, 300, 2000)))
        settle()
        r.screen.current = saraChat
        r.session.onEvent(click("com.whatsapp", listOf(0, 420, 1080, 560)))
        settle()
        advanceTimeBy(GuidanceSession.END_MAX_MS + 100)
        runCurrent()
        assertEquals(3, r.planner.requests.size)

        r.screen.current = launcher
        r.planner.then(tap(1, "WhatsApp dabayein", "whatsapp_send_photo"))
        r.planner.then(plan("done", key = "whatsapp_send_photo"))
        r.session.submitGoal("Sara ko photo")
        runCurrent()
        r.screen.current = chats
        r.session.onEvent(click("com.android.launcher", listOf(100, 1800, 300, 2000)))
        settle()
        assertEquals(4, r.planner.requests.size)
        assertEquals(PointerTarget.Element(listOf(0, 420, 1080, 560)), r.ui.points.last().first)
        r.screen.current = saraChat
        r.session.onEvent(click("com.whatsapp", listOf(0, 420, 1080, 560)))
        settle()
        assertTrue(r.observer.steps.any { it.fromCache && it.action == "tap" && it.outcome == "done" })
        assertEquals(5, r.planner.requests.size)
    }

    @Test fun pinSheetOnSameWindowHidesPointer() = runTest {
        val home = snap(el(1, text = "Mobile Load", clickable = true, bounds = listOf(0, 500, 1080, 600)), pkg = "com.wallet", windowId = 31)
        val keys = (0..9).map { d -> el(10 + d, text = d.toString(), clickable = true, bounds = listOf(d * 100, 2000, d * 100 + 90, 2090)) }
        val withSheet = snap(
            el(1, text = "Mobile Load", clickable = true, bounds = listOf(0, 500, 1080, 600)),
            *keys.toTypedArray(),
            pkg = "com.wallet", windowId = 31,
        )
        val r = rig(home)
        r.planner.then(tap(1))
        r.session.submitGoal("Mobile load")
        runCurrent()
        r.screen.current = withSheet
        r.session.onEvent(ScreenEvent(ScreenEvent.Type.WINDOW_CONTENT, "com.wallet"))
        settle()
        assertEquals(SessionState.SENSITIVE_PAUSE, r.session.state)
        assertEquals(listOf("com.wallet"), r.observer.pauses)
        assertEquals(1, r.planner.requests.size)
    }

    @Test fun metricsLineHasTimingsAndNoText() = runTest {
        val r = rig(launcher)
        r.planner.then(tap(1, "WhatsApp dabayein", "whatsapp_send_photo"))
        r.planner.then(plan("done", key = "whatsapp_send_photo"))
        r.session.submitGoal("WhatsApp")
        runCurrent()
        advanceTimeBy(1200)
        r.screen.current = chats
        r.session.onEvent(click("com.android.launcher", listOf(100, 1800, 300, 2000)))
        settle()
        val tapLine = r.observer.steps.first { it.action == "tap" }
        assertEquals("done", tapLine.outcome)
        assertEquals(1, tapLine.step)
        assertEquals("whatsapp_send_photo", tapLine.goalKey)
        assertEquals(900L, tapLine.plannerMs)
        assertTrue(tapLine.timeToUserTapMs!! >= 1200)
        assertEquals("FULL", tapLine.guidanceLevel)
        assertFalse(tapLine.fromCache)
        assertEquals("completed", r.observer.steps.last().outcome)
    }

    @Test fun cancelReportsUnfinishedStep() = runTest {
        val r = rig(launcher)
        r.planner.then(tap(1))
        r.session.submitGoal("WhatsApp")
        runCurrent()
        r.session.cancel()
        assertEquals("cancelled", r.observer.steps.single().outcome)
        assertNull(r.observer.steps.single().timeToUserTapMs)
    }

    @Test fun pointerFollowsTargetWhenListShifts() = runTest {
        val r = rig(chats)
        r.planner.then(tap(2, "Sara dabayein"))
        r.session.submitGoal("Sara ko photo")
        runCurrent()
        val shifted = snap(
            el(1, text = "Chats", clickable = true, bounds = listOf(0, 300, 360, 400)),
            el(2, label = "Bilal · Naya message", viewId = "row", clickable = true, bounds = listOf(0, 420, 1080, 560)),
            el(3, label = "Sara · Kal milte hain", viewId = "row", clickable = true, bounds = listOf(0, 560, 1080, 700)),
            el(4, label = "Ali · Salam", viewId = "row", clickable = true, bounds = listOf(0, 700, 1080, 840)),
            pkg = "com.whatsapp", windowId = 20,
        )
        r.screen.current = shifted
        r.session.onEvent(ScreenEvent(ScreenEvent.Type.WINDOW_CONTENT, "com.whatsapp"))
        settle()
        assertEquals(listOf(PointerTarget.Element(listOf(0, 560, 1080, 700))), r.ui.moves)
        assertEquals(SessionState.WAITING_FOR_USER, r.session.state)
        r.planner.then(plan("done"))
        r.screen.current = saraChat
        r.session.onEvent(click("com.whatsapp", listOf(0, 560, 1080, 700)))
        settle()
        assertEquals("done", r.session.history.single().outcome)
    }

    private val displayList = el(1, viewId = "recycler_view", cls = "List", scrollable = true, bounds = listOf(0, 294, 1080, 2400))

    private fun brightnessPage(value: String, warning: Boolean) = snap(
        displayList,
        el(2, label = "Dark mode settings", clickable = true, bounds = listOf(0, 1072, 1080, 1316)),
        el(3, text = value, bounds = listOf(144, 1448, 936, 1532)),
        *(if (warning) arrayOf(el(4, text = "HIGH POWER CONSUMPTION", bounds = listOf(569, 1536, 1008, 1580))) else emptyArray()),
        el(5, label = "Auto brightness · On", clickable = true, bounds = listOf(0, 1580, 1080, 1736)),
        el(6, label = "Eye comfort", clickable = true, bounds = listOf(0, 1820, 1080, 1976)),
        pkg = "com.android.settings", windowId = 7,
    )

    @Test fun liveValuesDoNotFinishAScrollStep() = runTest {
        val r = rig(brightnessPage("501", warning = false))
        r.planner.then(PlanResponse(action = "scroll", elementId = 1, scrollDirection = "down", sayEn = "Scroll down", sayUr = "نیچے سکرول کریں", goalKey = "settings_font_size"))
        r.session.submitGoal("Phone ke likhai bari karni hai")
        runCurrent()
        assertEquals(SessionState.WAITING_FOR_USER, r.session.state)

        r.screen.current = brightnessPage("872", warning = true)
        r.session.onEvent(ScreenEvent(ScreenEvent.Type.WINDOW_CONTENT, "com.android.settings"))
        r.session.onEvent(ScreenEvent(ScreenEvent.Type.SCROLLED, "com.android.settings", source = SourceInfo(bounds = listOf(0, 294, 1080, 2400))))
        settle()
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(SessionState.WAITING_FOR_USER, r.session.state)
        assertTrue(r.session.history.isEmpty())
        assertEquals(1, r.planner.requests.size)
    }

    @Test fun liveValuesWhilePlanningDoNotCauseReplans() = runTest {
        val r = rig(brightnessPage("501", warning = false))
        r.planner.gate = CompletableDeferred()
        r.planner.then(tap(5, "Auto brightness dabayein"))
        r.session.submitGoal("brightness")
        runCurrent()
        r.screen.current = brightnessPage("872", warning = true)
        r.session.onEvent(ScreenEvent(ScreenEvent.Type.WINDOW_CONTENT, "com.android.settings"))
        r.planner.gate!!.complete(Unit)
        runCurrent()
        assertEquals(1, r.planner.requests.size)
        assertEquals(SessionState.WAITING_FOR_USER, r.session.state)
    }
}
