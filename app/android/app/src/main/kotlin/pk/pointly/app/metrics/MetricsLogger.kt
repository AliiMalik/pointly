package pk.pointly.app.metrics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pk.pointly.app.session.SessionObserver
import pk.pointly.app.session.StepMetrics
import java.io.File

class MetricsLogger(private val file: File, private val now: () -> Long = { System.currentTimeMillis() }) : SessionObserver {

    @Serializable
    data class Line(
        val sessionId: String? = null,
        val step: Int? = null,
        val goalKey: String? = null,
        @kotlinx.serialization.SerialName("package") val packageName: String? = null,
        val action: String? = null,
        val readMs: Long? = null,
        val plannerMs: Long? = null,
        val ttsStartMs: Long? = null,
        val timeToUserTapMs: Long? = null,
        val settleToPointerMs: Long? = null,
        val outcome: String? = null,
        val fromCache: Boolean? = null,
        val guidanceLevel: String? = null,
        val event: String? = null,
        val ts: Long,
    )

    override fun onStep(m: StepMetrics) = append(
        Line(
            sessionId = m.sessionId, step = m.step, goalKey = m.goalKey, packageName = m.packageName,
            action = m.action, readMs = m.readMs, plannerMs = m.plannerMs, ttsStartMs = m.ttsStartMs,
            timeToUserTapMs = m.timeToUserTapMs, settleToPointerMs = m.settleToPointerMs, outcome = m.outcome,
            fromCache = m.fromCache, guidanceLevel = m.guidanceLevel, ts = now(),
        ),
    )

    override fun onSensitivePause(sessionId: String, packageName: String) =
        append(Line(sessionId = sessionId, packageName = packageName, event = "sensitive_pause", ts = now()))

    @Synchronized
    private fun append(line: Line) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(json.encodeToString(Line.serializer(), line) + "\n")
        }
    }

    fun lines(): List<Line> = if (!file.exists()) emptyList() else file.readLines().mapNotNull { l ->
        runCatching { json.decodeFromString(Line.serializer(), l) }.getOrNull()
    }

    fun summary(): Map<String, Any?> {
        val all = lines()
        val steps = all.filter { it.event == null }
        val sessions = steps.mapNotNull { it.sessionId }.toSet()
        val completed = steps.filter { it.outcome == "completed" }.mapNotNull { it.sessionId }.toSet()
        val guided = steps.filter { it.action in GUIDED_ACTIONS }
        return mapOf(
            "sessions" to sessions.size,
            "steps" to steps.size,
            "successRate" to if (sessions.isEmpty()) null else completed.size.toDouble() / sessions.size,
            "medianPlannerMs" to median(steps.filter { it.fromCache != true }.mapNotNull { it.plannerMs }),
            "medianCachedMs" to median(steps.filter { it.fromCache == true }.mapNotNull { it.plannerMs }),
            "medianStepMs" to median(steps.mapNotNull { it.timeToUserTapMs }),
            "medianReadMs" to median(steps.mapNotNull { it.readMs }),
            "medianSettleToPointerMs" to median(guided.mapNotNull { it.settleToPointerMs }),
            "cacheHitRate" to if (guided.isEmpty()) null else guided.count { it.fromCache == true }.toDouble() / guided.size,
            "sensitivePauses" to all.count { it.event == "sensitive_pause" },
        )
    }

    fun exportCopy(exportDir: File): File? {
        if (!file.exists()) return null
        exportDir.mkdirs()
        val out = File(exportDir, "metrics-${now()}.jsonl")
        file.copyTo(out, overwrite = true)
        return out
    }

    fun clear() {
        file.delete()
    }

    companion object {
        const val FILE_NAME = "metrics.jsonl"
        private val GUIDED_ACTIONS = setOf("tap", "type", "scroll", "back", "home")
        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        fun median(values: List<Long>): Long? {
            if (values.isEmpty()) return null
            val s = values.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }
    }
}
