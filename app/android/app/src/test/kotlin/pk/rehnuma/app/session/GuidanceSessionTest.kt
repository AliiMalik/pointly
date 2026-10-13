package pk.rehnuma.app.session

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
import pk.rehnuma.app.config.AppConfig
import pk.rehnuma.app.net.PlanRequest
import pk.rehnuma.app.net.PlanResponse
import pk.rehnuma.app.net.PlannerClient
import pk.rehnuma.app.overlay.BubbleState
import pk.rehnuma.app.overlay.PointerTarget
import pk.rehnuma.app.screen.ScreenSnapshot
import pk.rehnuma.app.screen.TestScreens.el
import pk.rehnuma.app.screen.TestScreens.snap
import pk.rehnuma.app.voice.Phrase

@OptIn(ExperimentalCoroutinesApi::class)
class GuidanceSessionTest {

    private val own = "pk.rehnuma.app"

    // Screens
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

    // ---------------------------------------------------------------- fakes

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
            onArrived()
        }

        override fun caption(text: String?) {
            captions += text
        }

        override fun revealHand() {}

        override fun clear() {
            cleared++
        }

        override fun emit(state: SessionState, goalText: String?, step: Int?, captionRoman: String?, error: String?) {
            states += state
        }
    }

    private class FakeVoice : SessionVoice {
        val said = mutableListOf<String>()
        var stops = 0
        override fun speak(urdu: String, roman: String, onDone: () -> Unit) {
            said += roman
            onDone()
        }

        override fun stop() {
            stops++
        }
    }

    private class Rig(
        val session: GuidanceSession,
        val screen: FakeScreen,
        val planner: FakePlanner,
        val ui: FakeUi,
        val voice: FakeVoice,
    )

    private fun TestScope.rig(start: ScreenSnapshot?, config: AppConfig = AppConfig()): Rig {
        val screen = FakeScreen(start)
        val planner = FakePlanner()
        val ui = FakeUi()
        val voice = FakeVoice()
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
            newSessionId = { "s${++n}" },
        )
        return Rig(session, screen, planner, ui, voice)
    }

    private fun tap(id: Int, say: String = "Isay dabayein", key: String = "test_goal") =
        PlanResponse(action = "tap", elementId = id, sayUrdu = "اسے دبائیں", sayRoman = say, goalKey = key, confidence = 0.9)

    private fun plan(action: String, say: String = "Theek hai", id: Int? = null, key: String = "test_goal") =
        PlanResponse(action = action, elementId = id, sayUrdu = "ٹھیک ہے", sayRoman = say, goalKey = key, confidence = 0.9)

    private fun click(pkg: String, bounds: List<Int>?, label: String? = null, viewId: String? = null) =
        ScreenEvent(ScreenEvent.Type.CLICKED, pkg, source = SourceInfo(viewId = viewId, label = label, bounds = bounds))

    private fun windowChange(pkg: String) = ScreenEvent(ScreenEvent.Type.WINDOW_STATE, pkg)

    /** Lets the settle detector (500 ms quiet) and any queued work run. */
    private fun TestScope.settle() {
        advanceTimeBy(600)
        runCurrent()
    }

    // ---------------------------------------------------------------- tests

    @Test fun happyPathTapThenDone() = runTest {
        val r = rig(launcher)
        r.planner.then(tap(1, "Hara WhatsApp icon dabayein", "whatsapp_send_photo"))
        r.planner.then(plan("done"))

        r.session.submitGoal("WhatsApp kholna hai")
        runCurrent()
        assertEquals(SessionState.WAITING_FOR_USER, r.session.state)
        assertEquals(PointerTarget.Element(listOf(100, 1800, 300, 2000)), r.ui.points.last().first)
        assertEquals("Hara WhatsApp icon dabayein", r.ui.points.last().second)
        assertTrue(r.voice.said.contains("Hara WhatsApp icon dabayein"))
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
        assertEquals("Hara WhatsApp icon dabayein", second.history.single().instructionRoman)
        assertEquals(SessionState.COMPLETED, r.session.state)
        assertTrue(r.voice.said.contains(Phrase.DONE.roman))

        advanceTimeBy(GuidanceSession.END_MAX_MS + 100) // backgroundScope timers need explicit time
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

        // User taps Ali's row: same view id, different label, outside Sara's bounds.
        r.session.onEvent(click("com.whatsapp", listOf(0, 560, 1080, 700), "ali · salam", "row"))
        r.screen.current = aliChat
        r.session.onEvent(windowChange("com.whatsapp"))
        settle()

        assertEquals(listOf(Phrase.DEVIATION.roman), r.voice.said.filter { it == Phrase.DEVIATION.roman })
        assertEquals("deviated", r.session.history.single().outcome)
        assertEquals(2, r.planner.requests.size)
        assertEquals("Ali", r.planner.requests[1].screen.elements[0].text) // re-planned from Ali's chat
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

        assertEquals(1, r.voice.said.count { it == Phrase.WRONG_TAP.roman })
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
        assertEquals(1, r.planner.requests.size) // the PIN screen never reached the planner
        assertTrue(r.voice.said.contains(Phrase.SENSITIVE_PAUSE.roman))
        assertEquals(BubbleState.PAUSED, r.ui.bubble)
        assertTrue(r.session.isActive)

        // Still sensitive after typing: stay paused.
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
        assertTrue(r.voice.said.contains(Phrase.NETWORK_SLOW.roman))
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
        assertTrue(r.voice.said.contains(Phrase.CANT_HELP.roman))
        assertEquals(8, r.planner.requests.last().history.size) // only the last 8 are sent
        advanceTimeBy(GuidanceSession.END_MAX_MS + 100) // backgroundScope timers need explicit time
        runCurrent()
        assertEquals(SessionState.IDLE, r.session.state)
    }

    @Test fun askThenAnswerReplans() = runTest {
        val r = rig(chats)
        r.planner.then(plan("ask", "Kis ko bhejni hai?"))
        r.planner.then(tap(2))
        r.session.submitGoal("Photo bhejni hai")
        runCurrent()
        assertEquals(SessionState.ASKING, r.session.state)
        assertTrue(r.ui.captions.contains("Kis ko bhejni hai?"))

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

        // Events from Rehnuma itself are ignored; the launcher appearing is progress.
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
        assertEquals(1, r.planner.requests[1].stepIndex) // same step, newer screen
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

    @Test fun urduCaptionSetting() = runTest {
        val r = rig(launcher, AppConfig(caption = AppConfig.CAPTION_URDU))
        r.planner.then(tap(1))
        r.session.submitGoal("WhatsApp")
        runCurrent()
        assertEquals("اسے دبائیں", r.ui.points.last().second)
        assertEquals("urdu", r.planner.requests[0].prefs.caption)
    }
}
