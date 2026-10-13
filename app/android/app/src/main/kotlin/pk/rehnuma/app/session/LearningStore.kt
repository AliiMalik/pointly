package pk.rehnuma.app.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import pk.rehnuma.app.screen.Labels
import pk.rehnuma.app.screen.ScreenElement
import java.io.File
import java.security.MessageDigest

/** SPEC 9.1 guidance levels. */
enum class GuidanceLevel { FULL, HINT, SILENT }

/**
 * Remembers how often the user completed each step, so help fades as they learn (SPEC 9.1).
 * Stored in `filesDir/learning.json` as `goalKey|package|targetKey -> {successes, lastSeenTs}`.
 * Target labels are stored as short hashes, never as screen text.
 */
class LearningStore(private val file: File?, private val clock: () -> Long) {

    @Serializable
    data class Entry(val successes: Int = 0, val lastSeenTs: Long = 0)

    private val entries: MutableMap<String, Entry> = load()

    fun successes(key: String): Int = entries[key]?.successes ?: 0

    fun level(key: String): GuidanceLevel = when (successes(key)) {
        0, 1 -> GuidanceLevel.FULL
        2, 3 -> GuidanceLevel.HINT
        else -> GuidanceLevel.SILENT
    }

    fun recordSuccess(key: String) {
        val e = entries[key] ?: Entry()
        entries[key] = Entry(e.successes + 1, clock())
        save()
    }

    fun reset() {
        entries.clear()
        save()
    }

    private fun load(): MutableMap<String, Entry> = try {
        if (file != null && file.exists()) json.decodeFromString(serializer, file.readText()).toMutableMap() else HashMap()
    } catch (e: Exception) {
        HashMap() // a corrupt file just means starting over
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            val tmp = File(f.path + ".tmp")
            tmp.writeText(json.encodeToString(serializer, entries))
            tmp.renameTo(f) || run { f.delete(); tmp.renameTo(f) }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = MapSerializer(String.serializer(), Entry.serializer())

        fun key(goalKey: String, packageName: String, targetKey: String) = "$goalKey|$packageName|$targetKey"

        /** viewId if present, else a hash of the normalized label (SPEC 9.1), else the action. */
        fun targetKey(target: ScreenElement?, action: String): String {
            target ?: return "nav:$action"
            target.viewId?.let { return "id:$it" }
            Labels.normalize(target.displayLabel)?.let { return "h:" + labelHash(it) }
            return "cls:${target.cls}"
        }

        fun labelHash(normLabel: String): String =
            MessageDigest.getInstance("SHA-1").digest(normLabel.toByteArray(Charsets.UTF_8))
                .take(8).joinToString("") { "%02x".format(it) }
    }
}
