package pk.rehnuma.app.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import pk.rehnuma.app.net.PlanResponse
import pk.rehnuma.app.screen.Labels
import pk.rehnuma.app.screen.ScreenElement
import pk.rehnuma.app.screen.ScreenSnapshot
import java.io.File

/**
 * Steps of finished tasks, reused when the same screen comes back (SPEC 9.2), so repeated tasks
 * skip the network. Stored in `filesDir/plan_cache.json`; target labels only as hashes.
 */
class PlanCache(private val file: File?) {

    @Serializable
    data class Target(val viewId: String? = null, val labelHash: String? = null, val cls: String? = null)

    @Serializable
    data class Step(
        val goalKey: String,
        val signature: String,
        val action: String,
        val target: Target? = null,
        val scrollDirection: String? = null,
        val sayUrdu: String,
        val sayRoman: String,
    )

    data class Hit(val step: Step, val element: ScreenElement?)

    private val byGoal: MutableMap<String, List<Step>> = load()

    val size: Int get() = byGoal.values.sumOf { it.size }

    /** A cached step for this goal on this exact screen whose target is still there, or null. */
    fun lookup(goalKey: String, snapshot: ScreenSnapshot): Hit? {
        val candidates = byGoal[goalKey].orEmpty().filter { it.signature == snapshot.signature }
        for (step in candidates) {
            if (step.action !in PlanResponse.NEEDS_ELEMENT) return Hit(step, null)
            val element = find(step.target ?: continue, snapshot) ?: continue
            return Hit(step, element)
        }
        return null
    }

    /** Stores the steps of a session that ended in `done`; newer steps replace older ones on the same screen. */
    fun store(goalKey: String, steps: List<Step>) {
        if (steps.isEmpty()) return
        val newSigs = steps.map { it.signature }.toSet()
        val kept = byGoal[goalKey].orEmpty().filter { it.signature !in newSigs }
        byGoal[goalKey] = (kept + steps).takeLast(MAX_STEPS_PER_GOAL)
        while (byGoal.size > MAX_GOALS) byGoal.remove(byGoal.keys.first())
        save()
    }

    fun clear() {
        byGoal.clear()
        save()
    }

    private fun find(t: Target, snapshot: ScreenSnapshot): ScreenElement? {
        val hashOf = { e: ScreenElement -> Labels.normalize(e.displayLabel)?.let { LearningStore.labelHash(it) } }
        if (t.viewId != null) {
            val byId = snapshot.elements.filter { it.viewId == t.viewId }
            if (byId.size == 1) return byId[0]
            byId.firstOrNull { t.labelHash != null && hashOf(it) == t.labelHash }?.let { return it }
        }
        if (t.labelHash != null) return snapshot.elements.firstOrNull { hashOf(it) == t.labelHash }
        return null
    }

    private fun load(): MutableMap<String, List<Step>> = try {
        if (file != null && file.exists()) LinkedHashMap(json.decodeFromString(serializer, file.readText())) else LinkedHashMap()
    } catch (e: Exception) {
        LinkedHashMap()
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            val tmp = File(f.path + ".tmp")
            tmp.writeText(json.encodeToString(serializer, byGoal))
            tmp.renameTo(f) || run { f.delete(); tmp.renameTo(f) }
        }
    }

    companion object {
        const val MAX_STEPS_PER_GOAL = 30
        const val MAX_GOALS = 50
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = MapSerializer(String.serializer(), ListSerializer(Step.serializer()))

        fun targetOf(e: ScreenElement?): Target? = e?.let {
            Target(viewId = it.viewId, labelHash = Labels.normalize(it.displayLabel)?.let(LearningStore::labelHash), cls = it.cls)
        }
    }
}
