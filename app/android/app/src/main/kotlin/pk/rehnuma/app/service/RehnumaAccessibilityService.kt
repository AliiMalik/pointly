package pk.rehnuma.app.service

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pk.rehnuma.app.config.AppConfig
import pk.rehnuma.app.overlay.DebugBoxesOverlay
import pk.rehnuma.app.screen.ScreenReader
import pk.rehnuma.app.screen.ScreenSnapshot
import pk.rehnuma.app.screen.SnapshotSaver

/**
 * Entry point of the Kotlin core (SPEC 5.1). Reads the screen only while a guidance session is
 * active (rule 4). It never performs actions or gestures for the user (rule 1).
 */
class RehnumaAccessibilityService : AccessibilityService() {

    private lateinit var scope: CoroutineScope
    private lateinit var notification: SessionNotification
    private lateinit var reader: ScreenReader
    private lateinit var debugBoxes: DebugBoxesOverlay
    private var config = AppConfig()
    private var bubbleVisible = false
    private var sessionActive = false
    private var debugRefresh: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val readDispatcher = Dispatchers.Default.limitedParallelism(1)

    override fun onServiceConnected() {
        super.onServiceConnected()
        scope = MainScope()
        config = AppConfig.load(this)
        notification = SessionNotification(this) { hideBubble() }
        reader = ScreenReader(this)
        debugBoxes = DebugBoxesOverlay(this)
        instance = this
        if (config.debugBoxes) setDebugBoxes(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Rule 4: ignore everything unless the user started a session (or turned on the debug overlay).
        if (event == null || !(sessionActive || debugBoxes.isShowing)) return
        if (debugBoxes.isShowing) scheduleDebugRefresh()
    }

    override fun onInterrupt() {
        // Nothing to interrupt: Rehnuma gives no spoken accessibility feedback of its own.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (instance === this) instance = null
        sessionActive = false
        bubbleVisible = false
        if (::debugBoxes.isInitialized) debugBoxes.hide()
        if (::notification.isInitialized) notification.release()
        if (::scope.isInitialized) scope.cancel()
    }

    /** Reads the current screen off the main thread. */
    suspend fun readScreen(): ScreenSnapshot? = withContext(readDispatcher) {
        try {
            reader.read()
        } catch (e: RuntimeException) {
            Log.w(TAG, "screen read failed", e)
            null
        }
    }

    // ---- Debug tools (SPEC 11) ----

    fun setDebugBoxes(enabled: Boolean) {
        config = config.copy(debugBoxes = enabled)
        if (enabled) {
            debugBoxes.show()
            scheduleDebugRefresh(0)
        } else {
            debugRefresh?.cancel()
            debugBoxes.update(null)
            debugBoxes.hide()
        }
    }

    private fun scheduleDebugRefresh(delayMs: Long = 300) {
        debugRefresh?.cancel()
        debugRefresh = scope.launch {
            delay(delayMs)
            debugBoxes.update(readScreen())
        }
    }

    fun saveSnapshotAfter(delayMs: Long) {
        scope.launch {
            toast("Snapshot ${delayMs / 1000}s mein")
            delay(delayMs)
            saveSnapshotNow()
        }
    }

    suspend fun saveSnapshotNow() {
        val snapshot = readScreen()
        if (snapshot == null) {
            toast("Koi screen nahi mili")
            return
        }
        val result = withContext(Dispatchers.IO) { SnapshotSaver.save(this@RehnumaAccessibilityService, snapshot) }
        when (result) {
            is SnapshotSaver.Result.Sensitive -> toast("Yeh screen save nahi hogi (PIN/password)")
            is SnapshotSaver.Result.Saved -> {
                toast("Saved: ${result.file.name}")
                SnapshotSaver.share(this, result.file)
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ---- Called from the Flutter bridge (main thread) ----

    fun showBubble() {
        bubbleVisible = true
        notification.show()
    }

    fun hideBubble() {
        sessionActive = false
        bubbleVisible = false
        notification.hide()
    }

    fun isBubbleVisible(): Boolean = bubbleVisible

    fun startQuickTask(goal: String) {
        if (!bubbleVisible) showBubble()
    }

    fun onConfigChanged(newConfig: AppConfig) {
        if (newConfig.debugBoxes != debugBoxes.isShowing) setDebugBoxes(newConfig.debugBoxes)
        config = newConfig
    }

    fun metricsSummary(): Map<String, Any?> = metricsSummary(this)

    companion object {
        private const val TAG = "Rehnuma"

        @Volatile
        var instance: RehnumaAccessibilityService? = null
            private set

        /** True when the service is running or enabled in system settings. */
        fun isEnabled(context: Context): Boolean {
            if (instance != null) return true
            val expected = ComponentName(context, RehnumaAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }

        fun metricsSummary(context: Context): Map<String, Any?> = emptyMap()

        fun exportMetrics(activity: Activity): String? = null

        fun resetLearning(context: Context) {}
    }
}
