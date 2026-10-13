package pk.rehnuma.app.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pk.rehnuma.app.session.StepMetrics
import java.io.File

class MetricsLoggerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun step(
        session: String, n: Int, action: String = "tap", outcome: String = "done",
        planner: Long? = 1500, tap: Long? = 4000, cache: Boolean = false,
    ) = StepMetrics(
        sessionId = session, step = n, goalKey = "settings_font_size", packageName = "com.android.settings",
        action = action, readMs = 20, plannerMs = planner, ttsStartMs = 300, timeToUserTapMs = tap,
        settleToPointerMs = 1800, outcome = outcome, fromCache = cache, guidanceLevel = "FULL",
    )

    @Test fun writesOneLinePerStepWithTimings() {
        val f = File(tmp.root, "metrics.jsonl")
        val log = MetricsLogger(f) { 1000 }
        log.onStep(step("s1", 1))
        log.onSensitivePause("s1", "com.wallet")
        val lines = f.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"plannerMs\":1500"))
        assertTrue(lines[0].contains("\"timeToUserTapMs\":4000"))
        assertTrue(lines[0].contains("\"package\":\"com.android.settings\""))
        assertEquals("""{"sessionId":"s1","package":"com.wallet","event":"sensitive_pause","ts":1000}""", lines[1])
    }

    @Test fun summaryNumbers() {
        val log = MetricsLogger(File(tmp.root, "m.jsonl"))
        // Session 1: two steps then done. Session 2: one cached step, then cancelled.
        log.onStep(step("s1", 1, planner = 1000, tap = 3000))
        log.onStep(step("s1", 2, planner = 2000, tap = 5000))
        log.onStep(step("s1", 3, action = "done", outcome = "completed", planner = 1200, tap = null))
        log.onStep(step("s2", 1, planner = 40, tap = 2000, cache = true))
        log.onStep(step("s2", 2, outcome = "cancelled", planner = 1800, tap = null))
        log.onSensitivePause("s2", "com.wallet")
        val s = log.summary()
        assertEquals(2, s["sessions"])
        assertEquals(5, s["steps"])
        assertEquals(0.5, s["successRate"])
        assertEquals(1500L, s["medianPlannerMs"]) // 1000, 1200, 1800, 2000 (cache excluded)
        assertEquals(40L, s["medianCachedMs"])
        assertEquals(3000L, s["medianStepMs"])
        assertEquals(0.25, s["cacheHitRate"]) // 1 of 4 guided steps
        assertEquals(1, s["sensitivePauses"])
    }

    @Test fun emptyLogSummary() {
        val s = MetricsLogger(File(tmp.root, "none.jsonl")).summary()
        assertEquals(0, s["sessions"])
        assertNull(s["successRate"])
        assertNull(s["medianPlannerMs"])
    }

    @Test fun exportCopiesIntoExportsFolder() {
        val f = File(tmp.root, "metrics.jsonl")
        val log = MetricsLogger(f) { 7 }
        assertNull(log.exportCopy(File(tmp.root, "exports")))
        log.onStep(step("s1", 1))
        val out = log.exportCopy(File(tmp.root, "exports"))!!
        assertEquals("metrics-7.jsonl", out.name)
        assertEquals(f.readText(), out.readText())
        assertFalse(out.readText().contains("Display")) // no screen text ever
    }

    @Test fun median() {
        assertNull(MetricsLogger.median(emptyList()))
        assertEquals(2L, MetricsLogger.median(listOf(3, 1, 2)))
        assertEquals(2L, MetricsLogger.median(listOf(1, 2, 3, 2)))
    }
}
