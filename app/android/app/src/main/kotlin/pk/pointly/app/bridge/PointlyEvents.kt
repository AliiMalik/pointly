package pk.pointly.app.bridge

import android.os.Handler
import android.os.Looper

object PointlyEvents {
    private val main = Handler(Looper.getMainLooper())
    private var listener: ((Map<String, Any?>) -> Unit)? = null

    @Volatile
    var last: Map<String, Any?> = mapOf("state" to "IDLE")
        private set

    fun attach(l: (Map<String, Any?>) -> Unit) {
        main.post {
            listener = l
            l(last)
        }
    }

    fun detach() {
        main.post { listener = null }
    }

    fun emit(
        state: String,
        goalText: String? = null,
        step: Int? = null,
        caption: String? = null,
        error: String? = null,
    ) {
        val m = buildMap<String, Any?> {
            put("state", state)
            goalText?.let { put("goalText", it) }
            step?.let { put("step", it) }
            caption?.let { put("caption", it) }
            error?.let { put("error", it) }
        }
        last = m
        main.post { listener?.invoke(m) }
    }
}
