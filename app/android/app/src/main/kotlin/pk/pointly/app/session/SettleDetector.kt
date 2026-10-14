package pk.pointly.app.session

import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

class SettleDetector(
    private val clock: () -> Long,
    val quietMs: Long = 500,
    val capMs: Long = 2500,
) {
    private var lastEventAt = Long.MIN_VALUE / 2

    fun mark() {
        lastEventAt = clock()
    }

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
