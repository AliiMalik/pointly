package pk.pointly.app.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import pk.pointly.app.screen.Labels
import pk.pointly.app.screen.ScreenElement
import java.io.File
import java.security.MessageDigest

enum class GuidanceLevel { FULL, HINT, SILENT }

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
        HashMap()
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
