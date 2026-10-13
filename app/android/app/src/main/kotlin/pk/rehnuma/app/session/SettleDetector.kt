package pk.rehnuma.app.session

import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

/**
 * Waits for the screen to stop changing (SPEC 5.6): no content/state events for [quietMs],
 * with a hard cap of [capMs]. Events are reported with [mark].
 */
class SettleDetector(
    private val clock: () -> Long,
    val quietMs: Long = 500,
    val capMs: Long = 2500,
) {
    private var lastEventAt = Long.MIN_VALUE / 2

    fun mark() {
        lastEventAt = clock()
    }

    /**
     * After a tap or window change ([fromNow] = true) the quiet period starts now, so the app has
     * time to begin its transition. Otherwise a screen that has already been quiet counts.
     */
    suspend fun await(fromNow: Boolean) {
        val start = clock()
        while (true) {
            val now = clock()
            val quietSince = if (fromNow) max(lastEventAt, start) else lastEventAt
            val quiet = now - quietSince
            val elapsed = now - start
            if (quiet >= quietMs || elapsed >= capMs) return
            delay(min(quietMs - quiet, capMs - elapsed).coerceAtLeast(1))
        }
    }
}
